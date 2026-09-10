package io.github.kegustafsson.driveremote.net

import io.github.kegustafsson.driveremote.core.AuthScheme
import io.github.kegustafsson.driveremote.core.ClientIntent
import io.github.kegustafsson.driveremote.core.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The write side: POSTs this station's intent to the arbiter.
 *
 * Note what is NOT here: any way to write a Signal K path. Commanding goes
 * through the server-side arming authority or it does not happen, which is what
 * lets several stations be open at once without their armed states fighting.
 *
 * The POST travels over HTTP independently of the read WebSocket. That is
 * deliberate and load-bearing: it is what makes the kill switch work while the
 * stream is down. A station may still hold the token and still be commanding
 * even when it cannot see the boat, so "offline" must never be presented as
 * "safely disarmed" -- and the disarm tap must never be gated on the stream.
 */
class IntentPoster(private val httpClient: OkHttpClient) {

  /** Which auth scheme the server accepted, sticky once one works. */
  private var schemeIndex = 0
  private var authTarget: Pair<ServerAddress, String?>? = null

  /**
   * Bumped every time the target changes, and carried by each request so a
   * reply can be matched to the session that sent it.
   *
   * Teardown's final intent is sent outside the send lane, so the operator can
   * pick a new server while a request to the old one is still in flight. Its
   * reply says nothing about the server the station is on now -- and acting on
   * it is not harmless. A stale 401 advancing [schemeIndex] would leave a
   * Bearer-only server permanently probed as JWT, with no way back: the index
   * only climbs, the target no longer changes, and because `TokenHealth` now
   * requires EVERY scheme to have been refused, a token that is never tried
   * with Bearer can never be declared dead either. The station would sit
   * refused forever, reporting that it was retrying.
   */
  private var authGeneration = 0

  /**
   * Guards the three probe fields above, and nothing else.
   *
   * `send()` runs on an arbitrary `Dispatchers.IO` worker and urgent sends
   * deliberately do not queue behind the heartbeat, so several are genuinely
   * concurrent. Without this the fields are ordinary unsynchronised mutable
   * state: there is no happens-before between one worker bumping the generation
   * and another comparing against it, so the generation guard could be defeated
   * by exactly the interleaving it was added to prevent — and `schemeIndex += 1`
   * is a read-modify-write that can simply lose an update.
   *
   * A plain lock rather than a coroutine `Mutex` because every critical section
   * here is a handful of field accesses with no suspension and no I/O; the HTTP
   * call itself is deliberately OUTSIDE it, so this never serialises requests.
   * Command ordering stays where it belongs — the sequence number and the
   * arbiter's `seq <= lastSeq` gate — and is untouched by this.
   */
  private val authLock = Any()

  sealed interface Result {
    /**
     * A verdict that says something about the CURRENT session carries the
     * generation it was produced for, so the caller can check it is still
     * current at the moment it acts.
     *
     * Classifying inside the lock is not sufficient on its own: `send()` still
     * has to hand the result back from `Dispatchers.IO` to the caller, and a
     * server change can land during that hop. The verdict would then be applied
     * to the new session -- old refusals helping discard its working token, an
     * old success erasing its genuine rejection history. The check has to happen
     * where the verdict is CONSUMED, not only where it is made.
     */
    sealed interface Verdict : Result {
      val generation: Int
    }

    data class Ok(override val generation: Int) : Verdict

    /**
     * The server rejected our credentials.
     *
     * [schemeTried] is what the refused request actually carried, and
     * `TokenHealth` needs it to tell a scheme probe from a dead token. Reading
     * the poster's current scheme afterwards would not do: concurrent sends
     * advance it, so by the time this is handled it may name a scheme this
     * request never used.
     */
    data class Unauthorized(
      val code: Int,
      val schemeTried: AuthScheme?,
      override val generation: Int,
    ) : Verdict

    /** Transport failure, or any other status. */
    data class Failed(val reason: String) : Result
  }

  /**
   * Send one intent.
   *
   * A failure is reported but never retried here: intents are sent on a 250 ms
   * heartbeat, so the next one is already on its way and a retry would only
   * queue stale commands behind it. If they stop arriving the arbiter
   * stale-evicts this station and releases the token, so the system falls back
   * to disarmed on its own -- which is the correct outcome and needs no
   * special-casing.
   */
  suspend fun send(address: ServerAddress, intent: ClientIntent, token: String?): Result =
    withContext(Dispatchers.IO) {
      // Retarget and capture in ONE atomic step. Split apart, two workers could
      // interleave between the retarget and the capture and each end up holding
      // the other's generation.
      val newTarget = address to token
      val schemeUsed: AuthScheme?
      val myGeneration: Int
      synchronized(authLock) {
        if (authTarget != newTarget) {
          authTarget = newTarget
          authGeneration += 1
          schemeIndex = 0
        }
        // Captured here, for THIS request. Concurrent senders move schemeIndex
        // while the call is in flight, so it must travel with the result -- as
        // must the generation, so a reply from a server we have since left can
        // be recognised and ignored.
        schemeUsed = token?.let { scheme() }
        myGeneration = authGeneration
      }
      val body = intent.toJson().toRequestBody(JSON)

      // The URL build is inside the runCatching (see AccessRequestClient): a
      // host OkHttp refuses must read as Failed, never throw past the caller.
      runCatching {
          val builder = Request.Builder().url(address.intentUrl).post(body)
          token?.let { builder.header("Authorization", schemeUsed!!.headerValue(it)) }
          httpClient.newCall(builder.build()).execute()
        }
        .fold(
          onSuccess = { response ->
            val code = response.use { it.code }
            // Classify INSIDE the lock, together with the staleness test.
            // Checking staleness first and then building the result outside is a
            // check/use gap: a retarget landing in that window returns an `Ok`
            // or `Unauthorized` from the old server as though it were current,
            // and the caller applies it to the new session's TokenHealth --
            // overlapping old refusals helping discard a working new token, or
            // an old success clearing genuine current ones. Guarding only
            // `schemeIndex` was not enough, because the VERDICT is the thing the
            // caller acts on.
            synchronized(authLock) {
              when {
                // Anything at all from a server this station has already left is
                // not evidence about the one it is on now. Failed is the result
                // that feeds nothing.
                myGeneration != authGeneration ->
                  Result.Failed("reply from a previous server, ignored")
                code in 200..299 -> Result.Ok(myGeneration)
                code == 401 || code == 403 -> {
                  // Try the other auth scheme once before calling it a
                  // credentials problem: signalk-server has historically
                  // accepted only "JWT <token>" on some versions (issue #715).
                  if (token != null && schemeIndex < AuthScheme.TRY_ORDER.lastIndex) {
                    schemeIndex += 1
                  }
                  Result.Unauthorized(code, schemeUsed, myGeneration)
                }
                else -> Result.Failed("intent POST $code")
              }
            }
          },
          onFailure = { Result.Failed(it.message ?: it::class.java.simpleName) },
        )
    }

  /**
   * Abandon every reply still in flight: this station has left that server.
   *
   * Must be called when the SESSION changes, not left to the next `send()`.
   * The generation otherwise advances only when a request to the new target
   * actually enters `send()` — and during setup none does: choosing a server
   * with no stored token goes to the access-request screen and sends nothing
   * until approval. Throughout that window `authTarget` still names the
   * previous server, so a delayed reply from it classifies as current and the
   * caller applies it to the new session: an old 401 can end a setup that was
   * going fine, or later count against a freshly issued token.
   *
   * Clearing the target as well as bumping the generation means the next send
   * re-baselines from scratch rather than inheriting a probe position that
   * belonged to a different server.
   */
  fun abandonInFlight() {
    synchronized(authLock) {
      authTarget = null
      authGeneration += 1
      schemeIndex = 0
    }
  }

  /**
   * The generation a verdict must still match to be worth acting on.
   *
   * Read by the caller on the main dispatcher, which is also where
   * [abandonInFlight] and every session change run -- so the check and the state
   * update it guards cannot be interleaved. That is what makes validating here
   * safe rather than another check/use gap.
   */
  fun currentGeneration(): Int = synchronized(authLock) { authGeneration }

  /** Caller must hold [authLock]. */
  private fun scheme(): AuthScheme =
    AuthScheme.TRY_ORDER[schemeIndex.coerceIn(0, AuthScheme.TRY_ORDER.lastIndex)]

  // There is deliberately no `isStale(generation)` accessor. One existed, and it
  // was the bug: a helper that takes the lock, answers, and releases it invites
  // the caller to act on the answer afterwards -- which is a check/use gap by
  // construction. The generation is compared in the same locked block that
  // builds the result, and nowhere else.

  private companion object {
    val JSON = "application/json; charset=utf-8".toMediaType()
  }
}
