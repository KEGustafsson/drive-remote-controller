package io.github.kegustafsson.driveremote.core

/**
 * What the outcome of this station's last intent POST says about whether its
 * commands -- STOP included -- are reaching the boat. Port of
 * `sk-plugin/src/pure/intentStatus.ts`, same states, same classification.
 *
 * Why it exists: the intent POST is the one direct measurement this station has
 * of its own command path. A 401/403 (refused), a 503 (the plugin is not
 * running) or a transport failure each mean that a press here -- and a STOP --
 * does nothing, and the station used to drop that on the floor: the kill switch
 * went on reading a healthy "DISARMED -- tap to arm" over a path that reached
 * nothing. AGENTS.md: never present unconfirmable data as live.
 *
 * Fail-safety does not depend on this. If the intents stop reaching the plugin
 * it stale-evicts this station and the outputs go safe on their own. This is
 * about the operator being TOLD.
 *
 * No `writeStatus` fallback, unlike the browser's [commandsIndication]: the
 * browser's stand-in before the first answer is `/skServer/loginStatus`, and
 * this station has no login -- its credential is the token, whose own recovery
 * is [TokenHealth]'s. Before the first answer the lamp simply says so.
 */
enum class IntentStatus {
  /** Nothing answered yet this session. */
  UNKNOWN,

  /** The plugin accepted the last intent (2xx). */
  OK,

  /** 401 / 403: the server refused this station's credentials. */
  AUTH,

  /** 503: the plugin's route answered, but the plugin is not running. */
  UNAVAILABLE,

  /**
   * Any other failure -- no response at all (no network, a timeout, a refused
   * connection), a 5xx, a 400 from a body the plugin refused to read. Whatever
   * the cause, this station's commands are not being acted on.
   */
  NETWORK,
}

/**
 * Classify one POST's outcome. [httpCode] is the response status, or null when
 * no response arrived at all. Mirrors `classifyIntentFailure` (and the `'ok'`
 * the browser records on a resolved POST): 2xx OK, 401/403 AUTH, 503
 * UNAVAILABLE, everything else NETWORK.
 */
fun classifyIntentOutcome(httpCode: Int?): IntentStatus =
  when {
    httpCode == null -> IntentStatus.NETWORK
    httpCode in 200..299 -> IntentStatus.OK
    httpCode == 401 || httpCode == 403 -> IntentStatus.AUTH
    httpCode == 503 -> IntentStatus.UNAVAILABLE
    else -> IntentStatus.NETWORK
  }

/** The COMMANDS lamp's colour. */
enum class CommandsTone {
  GOOD,

  /**
   * Nothing answered yet. Neutral rather than the browser's amber, for the same
   * reason [LinkPhase.CONNECTING] is: this station's first POST goes out as the
   * control screen appears and is answered within a round trip, so a warning
   * colour here would flash on every single launch -- and a warning that always
   * fires is one the operator learns to look past.
   */
  NEUTRAL,
  BAD,
}

data class CommandsIndication(val value: String, val tone: CommandsTone)

/**
 * What the COMMANDS lamp says. Wording mirrors the browser's lamp, except
 * "log in", which is a remedy this station does not have -- it says what
 * happened instead.
 */
fun commandsIndication(status: IntentStatus): CommandsIndication =
  when (status) {
    IntentStatus.OK -> CommandsIndication("reaching boat", CommandsTone.GOOD)
    IntentStatus.AUTH -> CommandsIndication("BLOCKED — login refused", CommandsTone.BAD)
    IntentStatus.UNAVAILABLE -> CommandsIndication("BLOCKED — plugin not running", CommandsTone.BAD)
    IntentStatus.NETWORK -> CommandsIndication("NOT REACHING BOAT", CommandsTone.BAD)
    IntentStatus.UNKNOWN -> CommandsIndication("unconfirmed", CommandsTone.NEUTRAL)
  }

/**
 * The short reason, for the kill switch, or null when the path is not known to
 * be broken. Short on purpose: it shares one line of the STOP button with what
 * a tap does.
 */
fun commandsBlockedReason(status: IntentStatus): String? =
  when (status) {
    IntentStatus.AUTH -> "login refused"
    IntentStatus.UNAVAILABLE -> "plugin stopped"
    IntentStatus.NETWORK -> "no network"
    IntentStatus.OK,
    IntentStatus.UNKNOWN -> null
  }

/**
 * The kill switch's warning when this station's commands are not reaching the
 * boat -- "commands not reaching boat — plugin stopped" -- or null when they are
 * not known to be failing.
 *
 * Shown on the kill switch, not only on the lamp, because the kill switch is the
 * one thing an operator reads before trusting a tap to do something: a healthy
 * "tap to arm" over a path that reaches nothing is exactly the presentation
 * AGENTS.md rules out. It never changes what a tap DOES: an ARM stays available
 * (a retry is harmless -- if it lands, the path is back) and a STOP always stays.
 */
fun commandsNotReachingLine(status: IntentStatus): String? =
  commandsBlockedReason(status)?.let { "commands not reaching boat — $it" }
