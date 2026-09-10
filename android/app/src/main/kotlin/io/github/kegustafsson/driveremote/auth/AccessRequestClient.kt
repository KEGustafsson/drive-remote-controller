package io.github.kegustafsson.driveremote.auth

import io.github.kegustafsson.driveremote.core.AccessRequest
import io.github.kegustafsson.driveremote.core.AccessState
import io.github.kegustafsson.driveremote.core.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP shell for the Signal K device access-request flow -- how this station
 * gets a token, the same mechanism TX, RX and HH use through SensESP's
 * `SKWSClient`.
 *
 * All parsing and every state transition live in [AccessRequest] (pure, and
 * exhaustively tested); this class only performs requests and waits. The split
 * is the point: DENIED, approved-but-tokenless and malformed bodies are all
 * awkward to produce against a real server, and none of them needs a server to
 * be tested.
 */
class AccessRequestClient(private val httpClient: OkHttpClient) {

  /**
   * Ask for access. The operator then has to approve the request in the Signal
   * K admin UI under Security > Access Requests, choosing a permission level.
   *
   * **Read/write is enough** -- admin is not needed, and should not be granted.
   * That is only true because the plugin registers its intent route at
   * `readwrite`; see docs/ARCHITECTURE.md §10. On a server too old for that
   * API the route stays admin-only and the request has to be approved at admin
   * level instead.
   */
  suspend fun request(
    address: ServerAddress,
    clientId: String,
    description: String,
  ): AccessState =
    withContext(Dispatchers.IO) {
      val body = AccessRequest.buildRequestBody(clientId, description).toRequestBody(JSON)

      // The URL build is inside the runCatching: ServerAddress already refuses
      // a host OkHttp cannot form a URL from, but an IllegalArgumentException
      // thrown here would escape the coroutine and crash the app, so it is
      // reported as a failure rather than trusted never to happen.
      runCatching {
          val request = Request.Builder().url(address.accessRequestsUrl).post(body).build()
          httpClient.newCall(request).execute()
        }
        .fold(
          onSuccess = { response ->
            response.use {
              val text = it.body?.string().orEmpty()
              when {
                // 202 Accepted is the documented answer; 200 is accepted too
                // since a server that approves immediately may use it.
                it.isSuccessful ->
                  AccessRequest.parseRequestResponse(text, System.currentTimeMillis())
                else -> AccessState.Failed("access request rejected: HTTP ${it.code}")
              }
            }
          },
          onFailure = { AccessState.Failed(it.message ?: "could not reach the server") },
        )
    }

  /** Poll once. Returns the new state, which may still be Pending. */
  suspend fun poll(address: ServerAddress, href: String): AccessState =
    withContext(Dispatchers.IO) {
      runCatching {
          val request = Request.Builder().url(address.resolve(href)).get().build()
          httpClient.newCall(request).execute()
        }
        .fold(
          onSuccess = { response ->
            response.use {
              val text = it.body?.string().orEmpty()
              if (it.isSuccessful) {
                AccessRequest.parsePollResponse(text, System.currentTimeMillis())
              } else {
                AccessState.Failed("access poll failed: HTTP ${it.code}")
              }
            }
          },
          onFailure = { AccessState.Failed(it.message ?: "could not reach the server") },
        )
    }

  /**
   * Poll until the request resolves, or until [timeoutMs] elapses.
   *
   * Deliberately patient and deliberately bounded: approval needs a human to
   * walk to a laptop, but a request left pending forever would be a spinner
   * with no end. A transient [AccessState.Failed] while polling does NOT abort
   * the wait -- the admin may simply be mid-approval on a flaky boat network --
   * but it is remembered and returned if the timeout wins.
   */
  suspend fun awaitDecision(
    address: ServerAddress,
    href: String,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    pollIntervalMs: Long = POLL_INTERVAL_MS,
  ): AccessState {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastFailure: AccessState.Failed? = null

    while (System.currentTimeMillis() < deadline) {
      when (val state = poll(address, href)) {
        is AccessState.Approved,
        is AccessState.Denied -> return state
        is AccessState.Failed -> lastFailure = state
        else -> Unit // still pending
      }
      delay(pollIntervalMs)
    }
    return lastFailure ?: AccessState.Failed("no answer to the access request in time")
  }

  private companion object {
    val JSON = "application/json; charset=utf-8".toMediaType()

    /** Long enough for someone to walk to the chart table and click approve. */
    const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
    const val POLL_INTERVAL_MS = 2_000L
  }
}
