package io.github.kegustafsson.driveremote.core

/**
 * The Signal K path contract and the timings that go with it.
 *
 * This is the Kotlin mirror of `sk-plugin/src/config.ts`, which is itself a
 * mirror of the firmware's `include/config.h`. There is no shared build step
 * between the PlatformIO project, the npm project and this Gradle project, so
 * **a change to a path or a timing constant must be applied to all three.**
 * Constants are cross-referenced by name below so a diff is easy to spot --
 * the same convention `config.ts` already uses, extended to a third copy.
 *
 * See `docs/ARCHITECTURE.md` §9 (the path contract) and §11 (configuration).
 */
object SkContract {

  // ---- Command paths, written ONLY by the plugin server ----------------
  // Listed because this file mirrors the firmware's path contract, NOT
  // because this app writes them. Like the browser UI, the Android station
  // never writes a Signal K path: all intent leaves as an HTTP POST to the
  // arbiter, which is the single authoritative writer of every path here.
  const val PLUGIN_PORT_COMMAND = "control.remoteController.plugin.port.command"
  const val PLUGIN_STBD_COMMAND = "control.remoteController.plugin.stbd.command"
  const val PLUGIN_ENABLED = "control.remoteController.plugin.enabled"
  const val PLUGIN_THRUSTER_COMMAND = "control.remoteController.plugin.thruster.command"
  const val PLUGIN_THRUSTER_MODE = "control.remoteController.plugin.thruster.mode"
  const val PLUGIN_THRUSTER_TRIM = "control.remoteController.plugin.thruster.trimDeg"

  /**
   * The clientId currently holding the arm token ('' when nobody does). Each
   * station derives its own ARMED state from `activeClient == myClientId` --
   * authority-driven, never a local guess. That is what makes exclusive arming
   * work across a browser, a phone and anything else that connects.
   */
  const val PLUGIN_ACTIVE_CLIENT = "control.remoteController.plugin.activeClient"

  /**
   * The arbiter's own verdict on whether each unit is alive right now.
   *
   * Deliberately not `rx.linkUp`: that is a value published BY RX, and Signal K
   * retains a path's last value forever, so a switched-off board leaves it
   * reading `true` indefinitely -- a dead unit that looks healthy. These are
   * recomputed by the always-running server from the ARRIVAL of telemetry.
   */
  const val PLUGIN_RX_LIVE = "control.remoteController.plugin.rxLive"
  const val PLUGIN_HH_LIVE = "control.remoteController.plugin.hhLive"

  // ---- RX telemetry (read-only) ----------------------------------------
  const val RX_PORT_STATE = "control.remoteController.rx.port.state"
  const val RX_STBD_STATE = "control.remoteController.rx.stbd.state"
  const val RX_PORT_SOURCE = "control.remoteController.rx.port.source"
  const val RX_STBD_SOURCE = "control.remoteController.rx.stbd.source"
  const val RX_MASTER_ENABLE = "control.remoteController.rx.masterEnable"
  const val RX_LINK_OK = "control.remoteController.rx.linkOk"

  /**
   * "RX link is up and ready" -- any remote source is LIVE at RX, armed or not.
   * Deliberately not `rx.linkOk` (live AND enabled, i.e. a consequence of
   * something already being armed): the plugin publishes a steady disarmed
   * heartbeat, so a healthy link reads as up here even while nothing is armed.
   */
  const val RX_LINK_UP = "control.remoteController.rx.linkUp"

  // ---- Heading-hold unit telemetry (read-only) -------------------------
  const val HH_LINK_UP = "control.remoteController.hh.linkUp"
  const val HH_THRUSTER_STATE = "control.remoteController.hh.thruster.state"
  const val HH_MODE = "control.remoteController.hh.mode"
  const val HH_SOURCE = "control.remoteController.hh.source"
  const val HH_SETPOINT = "control.remoteController.hh.setpointDeg"
  const val HH_ARMED = "control.remoteController.hh.armed"
  const val HH_REVERSAL_PENDING = "control.remoteController.hh.reversalPending"

  /**
   * HH's own fused heading, in RADIANS -- the boat's current heading as HH
   * believes it.
   *
   * Not part of the `control.remoteController.*` command contract: it is
   * telemetry HH publishes about itself. It is here because HH_SETPOINT is only
   * the held target while HH is actually holding, and mirrors this value the
   * rest of the time -- so a station that is not the one holding cannot use
   * HH_SETPOINT to show a current heading.
   */
  const val HH_FUSED_HEADING_RAD = "sensors.headingHold.fusedHeading"

  /** Every path this station subscribes to, for the single subscribe message. */
  val SUBSCRIBE_PATHS: List<String> =
    listOf(
      RX_PORT_STATE,
      RX_STBD_STATE,
      RX_PORT_SOURCE,
      RX_STBD_SOURCE,
      RX_MASTER_ENABLE,
      RX_LINK_OK,
      RX_LINK_UP,
      PLUGIN_ACTIVE_CLIENT,
      PLUGIN_RX_LIVE,
      PLUGIN_HH_LIVE,
      HH_LINK_UP,
      HH_THRUSTER_STATE,
      HH_MODE,
      HH_SOURCE,
      HH_SETPOINT,
      HH_ARMED,
      HH_REVERSAL_PENDING,
      HH_FUSED_HEADING_RAD,
    )

  // ---- Endpoints --------------------------------------------------------
  /** WebSocket stream. `subscribe=none` so only our explicit subscribe applies. */
  const val STREAM_PATH = "/signalk/v1/stream?subscribe=none"

  /** Where each station POSTs its intent. Kept in lockstep with index.cjs. */
  const val INTENT_PATH = "/plugins/signalk-drive-remote-controller/intent"

  /** The Signal K device access-request flow -- how TX, RX and HH authenticate. */
  const val ACCESS_REQUESTS_PATH = "/signalk/v1/access/requests"

  // ---- Timings ----------------------------------------------------------
  /**
   * `config::kSkPeriodicRefreshMs`. TX and RX publish on change plus this
   * periodic refresh so a per-source liveness watchdog always has something
   * recent to check even during a long steady press; this station matches the
   * cadence exactly so RX and the arbiter treat it identically to TX.
   */
  const val PERIODIC_REFRESH_MS = 250L

  /** `config::kSkStalenessTimeoutMs` -- RX is the one that enforces it. */
  const val SK_STALENESS_TIMEOUT_MS = 1000L

  /**
   * How long a unit's telemetry may go without a fresh delta before this app
   * stops presenting it as live. Mirrors `RX_STALE_TIMEOUT_MS` in index.cjs so
   * the station and the server call "the unit is gone" at the same moment and
   * the UI never contradicts the arm gate.
   */
  const val TELEMETRY_STALE_MS = 1500L

  /**
   * How often to re-evaluate that age. Staleness is the ABSENCE of deltas, so
   * nothing will ever notify us it happened -- the check must be clock-driven.
   */
  const val TELEMETRY_POLL_MS = PERIODIC_REFRESH_MS

  /** Largest heading trim a station may command. Mirrors `control_core::kMaxTrimDeg`. */
  const val MAX_TRIM_DEG = 45.0

  /** Trim button steps, matching `config::kHeadingNudge*Deg`. */
  const val HEADING_TRIM_FINE_DEG = 1.0
  const val HEADING_TRIM_COARSE_DEG = 10.0

  /**
   * Reconnect backoff. The first retry lands well inside
   * [SK_STALENESS_TIMEOUT_MS], but the cap is deliberately above it: while
   * disconnected this app publishes nothing, so RX's watchdog has already
   * failed this source to NEUTRAL -- backing off further only delays recovery,
   * it cannot prolong a stale command.
   */
  const val RECONNECT_INITIAL_DELAY_MS = 250L
  const val RECONNECT_MAX_DELAY_MS = 4000L

  /**
   * How long the FIRST connection of a session may take before it is presented
   * as a fault rather than as a start-up.
   *
   * Not a timeout: nothing is abandoned when it expires, the reconnect backoff
   * is untouched, and no command is gated on it. It decides only what the
   * operator is TOLD while the socket is being opened for the first time --
   * see [LinkPhase].
   *
   * Sized against what it waits for. A stream opening to a server on the boat's
   * own network takes a few hundred milliseconds, so a healthy start never
   * reaches this; a server that is off or unreachable does, and that should be
   * said out loud rather than dressed up as still-starting.
   */
  const val LINK_STARTUP_GRACE_MS = 4000L
}

/** Mirrors `ConnectionState` in `sk-plugin/src/skClient.ts`. */
enum class ConnectionState {
  CONNECTING,
  OPEN,
  CLOSED,
}
