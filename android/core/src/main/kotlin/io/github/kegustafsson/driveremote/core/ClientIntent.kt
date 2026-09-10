package io.github.kegustafsson.driveremote.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What one station POSTs to the arbiter. Port of
 * `sk-plugin/src/clientIntent.ts`; the wire counterpart is `arbiter.cjs`.
 *
 * This is an INTENT, not a command. The station says what it wants; the
 * server-side arbiter decides who holds the single arm token and is the sole
 * writer of the `plugin.*` paths. That is what lets a phone, a tablet and a
 * browser all be open at once without their armed states fighting -- and it is
 * why this app, like the browser UI, never writes a Signal K path itself.
 */

/** Bow-thruster direction. 'off', not 'neutral' -- a thruster coasts rather than sitting in gear. */
enum class ThrusterDirection(val wire: String) {
  PORT("port"),
  OFF("off"),
  STBD("stbd"),
}

/** Which gate owns the thruster: the operator's buttons, or heading hold. */
enum class ThrusterMode(val wire: String) {
  MANUAL("manual"),
  HOLD("hold"),
}

data class ClientIntent(
  /** Stable per-installation id. Two stations must never share one. */
  val clientId: String,
  /**
   * Per-PROCESS id, regenerated on every launch -- unlike [clientId], which is
   * persisted because the Signal K access token is issued to that device id.
   *
   * ORDERED, not merely unique: it lets the arbiter say which of two sessions is
   * newer and close the older one permanently. It is what tells a restart from a
   * replay. [seq] and the
   * counters below live in memory and begin again at 0 on relaunch, so a
   * process restarted inside the arbiter's stale timeout sends packets with a
   * LOWER seq than the record it left behind -- indistinguishable from a
   * delayed heartbeat, and discarded by the ordering gate. That silently
   * swallowed the new session's STOP. A changed session id says "new session"
   * outright rather than asking the arbiter to guess from the counters.
   */
  val session: Long,
  /**
   * Increments on EVERY send, including the 250 ms heartbeat, so no
   * change-detecting transport can dedupe a heartbeat and starve the arbiter's
   * liveness view of this client. The arbiter also enforces strict ordering on
   * it: an intent whose seq is not greater than the last accepted one is
   * discarded whole, so a delayed heartbeat cannot revert live commands.
   */
  val seq: Long,
  /** Increments once per operator ARM tap. Edge-triggered by the arbiter. */
  val armReq: Long,
  /** Increments once per operator DISARM tap. Edge-triggered; universal. */
  val disarmReq: Long,
  val port: DrivePosition,
  val stbd: DrivePosition,
  /** Thruster direction wanted right now (manual mode only). */
  val thruster: ThrusterDirection,
  val thrusterMode: ThrusterMode,
  /**
   * Heading-hold TRIM in degrees, RELATIVE to the heading HH captured when hold
   * engaged. 0 = no trim. Published as a self-correcting LEVEL rather than
   * per-press events: an event has to arrive exactly once to stay correct,
   * while a level is self-correcting over a lossy link and survives a reboot.
   */
  val trimDeg: Double,
) {
  /**
   * The exact JSON the arbiter expects. Field names are the wire contract with
   * `arbiter.cjs` -- kept in lockstep with `clientIntent.ts`.
   */
  fun toJson(): String =
    buildJsonObject {
        put("clientId", clientId)
        put("session", session)
        put("seq", seq)
        put("armReq", armReq)
        put("disarmReq", disarmReq)
        put("port", port.wire)
        put("stbd", stbd.wire)
        put("thruster", thruster.wire)
        put("thrusterMode", thrusterMode.wire)
        put("trimDeg", trimDeg)
      }
      .toString()

  companion object {
    /**
     * The intent a station sends when it is not commanding anything: disarmed,
     * both drives neutral, thruster off, no trim. This is the value the app
     * falls back to whenever it loses the right (or the ability) to command --
     * SAFETY.md: fail to the safe value, never to the last commanded one.
     */
    fun safe(
      clientId: String,
      session: Long,
      seq: Long,
      armReq: Long,
      disarmReq: Long,
    ): ClientIntent =
      ClientIntent(
        clientId = clientId,
        session = session,
        seq = seq,
        armReq = armReq,
        disarmReq = disarmReq,
        port = DrivePosition.NEUTRAL,
        stbd = DrivePosition.NEUTRAL,
        thruster = ThrusterDirection.OFF,
        thrusterMode = ThrusterMode.MANUAL,
        trimDeg = 0.0,
      )
  }
}

/**
 * Parses the arbiter's acknowledgement. The body is only `{ok:true}` today and
 * the app deliberately does not depend on it -- a station learns who holds the
 * token from the `activeClient` path it already subscribes to, never from this
 * reply. Parsed anyway so a malformed body is distinguishable from a transport
 * failure in logs.
 */
fun parseIntentAck(body: String): Boolean =
  runCatching {
      val obj = Json.parseToJsonElement(body) as? JsonObject ?: return false
      // booleanOrNull, not content == "true": the latter would also accept the
      // STRING "true", which is not what the route sends and not what we mean.
      (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
    }
    .getOrDefault(false)
