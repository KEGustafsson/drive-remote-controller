package io.github.kegustafsson.driveremote.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Signal K **device access-request** flow -- how this station gets a token,
 * the same mechanism TX, RX and HH use through SensESP's `SKWSClient`.
 *
 * Flow (Signal K spec, Appendix E):
 *   1. `POST /signalk/v1/access/requests  {clientId, description}` -> 202
 *      `{state: "PENDING", href: "/signalk/v1/access/requests/<id>"}`
 *   2. poll `GET <href>` until `state: "COMPLETED"`, which carries either
 *      `{permission: "APPROVED", token, expirationTime}` or
 *      `{permission: "DENIED"}`
 *
 * The parsing and state transitions live here, pure, so every branch --
 * including the ones that are awkward to produce against a real server, like
 * DENIED or a malformed body -- is pinned by a test. The platform layer only
 * performs the HTTP.
 */

/** Where the station stands in the access-request flow. */
sealed interface AccessState {
  /** No request made yet, or the stored token was discarded. */
  data object None : AccessState

  /** Request accepted by the server, waiting for an admin to approve it. */
  data class Pending(val href: String) : AccessState

  /** Approved. [expiresAtMs] is null when the server did not state one. */
  data class Approved(val token: String, val expiresAtMs: Long?) : AccessState

  /**
   * The admin refused. A terminal state on purpose: silently retrying a denied
   * request would spam the admin's approval queue forever, and the operator
   * needs to be told rather than left watching a spinner.
   */
  data object Denied : AccessState

  /** The server answered something we could not interpret. Carries why, for the UI. */
  data class Failed(val reason: String) : AccessState
}

object AccessRequest {

  private val json = Json { ignoreUnknownKeys = true }

  /** Body for step 1. `clientId` must be a v4 UUID per the spec. */
  fun buildRequestBody(clientId: String, description: String): String =
    buildJsonObject {
        put("clientId", clientId)
        put("description", description)
      }
      .toString()

  /**
   * Parse the 202 response to step 1.
   *
   * A missing or blank `href` is [AccessState.Failed], not a silent retry: with
   * no href there is nothing to poll, and pretending otherwise would leave the
   * app waiting forever on a request it can never check.
   *
   * [nowMs] resolves a duration-style expiry ("1d") on a server that approves
   * immediately, exactly as [parsePollResponse] does. Without it that expiry
   * was measured from epoch 0, so a freshly approved token read as having
   * expired decades ago and the session ended on its first heartbeat.
   */
  fun parseRequestResponse(body: String, nowMs: Long = 0L): AccessState =
    runCatching {
        val obj = json.parseToJsonElement(body) as? JsonObject
          ?: return AccessState.Failed("access request response was not a JSON object")
        // Some servers answer an already-approved device immediately.
        parseCompleted(obj, nowMs)?.let {
          return it
        }
        val href = obj.stringOrNull("href")
        if (href.isNullOrBlank()) {
          AccessState.Failed("access request response carried no href to poll")
        } else {
          AccessState.Pending(href)
        }
      }
      .getOrElse { AccessState.Failed("could not parse access request response: ${it.message}") }

  /**
   * Parse one poll of the request href.
   *
   * [nowMs] is passed in rather than read so expiry maths is testable; it is
   * used only to resolve a relative expiry when the server gives one.
   */
  fun parsePollResponse(body: String, nowMs: Long = 0L): AccessState =
    runCatching {
        val obj = json.parseToJsonElement(body) as? JsonObject
          ?: return AccessState.Failed("access poll response was not a JSON object")
        parseCompleted(obj, nowMs)
          ?: when (obj.stringOrNull("state")?.uppercase()) {
            "PENDING" -> AccessState.Pending(obj.stringOrNull("href") ?: "")
            null -> AccessState.Failed("access poll response carried no state")
            else -> AccessState.Failed("unrecognised access state: ${obj.stringOrNull("state")}")
          }
      }
      .getOrElse { AccessState.Failed("could not parse access poll response: ${it.message}") }

  /** COMPLETED handling shared by both responses, or null if not completed. */
  private fun parseCompleted(obj: JsonObject, nowMs: Long = 0L): AccessState? {
    if (obj.stringOrNull("state")?.uppercase() != "COMPLETED") return null
    val access = obj["accessRequest"] as? JsonObject
      ?: return AccessState.Failed("completed access request carried no accessRequest body")

    return when (access.stringOrNull("permission")?.uppercase()) {
      "APPROVED" -> {
        val token = access.stringOrNull("token")
        if (token.isNullOrBlank()) {
          // Approved but tokenless is unusable, and must not read as success:
          // the app would then believe it could command and be 401'd on every
          // single intent with nothing on screen explaining why.
          AccessState.Failed("access request approved but carried no token")
        } else {
          AccessState.Approved(token, parseExpiry(access.stringOrNull("expirationTime"), nowMs))
        }
      }
      "DENIED" -> AccessState.Denied
      else -> AccessState.Failed("unrecognised permission: ${access.stringOrNull("permission")}")
    }
  }

  /**
   * Resolve `expirationTime` to an epoch-millis deadline.
   *
   * The spec's example is an ISO-8601 instant. Some servers instead send a
   * duration string such as "365d". Both are accepted; anything else yields
   * null, meaning "no stated expiry" -- which is treated as a token that is
   * used until the server rejects it, never as one that has already expired.
   * Guessing an expiry would disarm a working station for no reason.
   */
  internal fun parseExpiry(raw: String?, nowMs: Long): Long? {
    if (raw.isNullOrBlank()) return null
    runCatching { return java.time.Instant.parse(raw).toEpochMilli() }
    val match = Regex("^(\\d+)([smhd])$").find(raw.trim()) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    val unitMs =
      when (match.groupValues[2]) {
        "s" -> 1_000L
        "m" -> 60_000L
        "h" -> 3_600_000L
        "d" -> 86_400_000L
        else -> return null
      }
    return nowMs + amount * unitMs
  }

  private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * How a token is presented on both the WebSocket upgrade and the intent POST.
 *
 * A native client can set an `Authorization` header on a WebSocket handshake --
 * something a browser cannot do, and precisely why the web UI is confined to
 * same-origin cookie auth while this app can be pointed at any server.
 *
 * Two schemes exist because signalk-server has historically accepted only
 * `JWT <token>` on some versions rather than the `Bearer <token>` the spec
 * describes (signalk-server issue #715). The app tries [BEARER] first and falls
 * back, rather than shipping a guess that fails on half the fleet.
 */
enum class AuthScheme(private val prefix: String) {
  BEARER("Bearer"),
  JWT("JWT");

  fun headerValue(token: String): String = "$prefix $token"

  companion object {
    /** Try in this order; the first that does not 401 is the one to keep. */
    val TRY_ORDER: List<AuthScheme> = listOf(BEARER, JWT)
  }
}
