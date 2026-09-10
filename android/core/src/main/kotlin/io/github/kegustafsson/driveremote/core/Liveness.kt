package io.github.kegustafsson.driveremote.core

/**
 * "Is that unit actually there right now?" -- and therefore whether the UI may
 * offer an ARM at all. Port of `sk-plugin/src/pure/rxLiveness.ts`.
 *
 * WHY IT CANNOT READ A VALUE: `rx.linkUp` is published by the unit itself,
 * Signal K retains a path's last value indefinitely, and this app keeps its
 * last-known copy across disconnects. A unit that is switched off therefore
 * leaves `rx.linkUp: true` standing forever -- an all-green panel and a working
 * ARM button for a board with no power. That was a real reported bug. The only
 * thing distinguishing a healthy steady link from a dead one is whether
 * telemetry is still ARRIVING, so liveness is judged on age, never on value.
 *
 * AGENTS.md non-negotiable 2: liveness is time-since-last-update, judged on
 * message *arrival*.
 */
enum class UnitLiveness {
  /** Telemetry is arriving right now. The only state in which arming is offered. */
  LIVE,

  /** The socket is down, so we genuinely cannot tell -- either claim would be a guess. */
  OFFLINE,

  /** Connected, but no telemetry has EVER arrived (unit off since before launch). */
  NEVER_SEEN,

  /** Telemetry was arriving and has stopped -- the board went away. */
  STALE,
}

/**
 * @param connectionState the read socket's state.
 * @param telemetryAgeMs age of the most recent telemetry delta for this unit;
 *   null = none ever received. Comes from arrival timestamps plus a clock,
 *   never from a value in the data model.
 * @param pluginUnitLive the server-side arbiter's own verdict
 *   (`plugin.rxLive` / `plugin.hhLive`), which is what actually gates arming.
 *   Consulted so the button agrees with the authority rather than
 *   second-guessing it. `null` means "no opinion" -- the path has not arrived
 *   yet, or an older plugin build does not publish it -- and falls back to the
 *   age check, so a missing path can never lock the operator out of arming a
 *   healthy system. It cannot make the app unsafe either: the server applies
 *   its own gate regardless of what this app believes.
 * @param pluginVerdictAgeMs age of that verdict. A retained, old `false` must
 *   not overrule telemetry which is arriving now. `null` means the age is not
 *   known, and is deliberately treated as "believe the verdict": an unsupplied
 *   age must never be the thing that silently switches off the server's gate.
 */
fun evaluateLiveness(
  connectionState: ConnectionState,
  telemetryAgeMs: Long?,
  pluginUnitLive: Boolean?,
  pluginVerdictAgeMs: Long? = null,
  staleTimeoutMs: Long = SkContract.TELEMETRY_STALE_MS,
): UnitLiveness {
  // While the socket is down every value we hold is last-known and no delta can
  // arrive by definition. Reporting STALE would blame the unit for what is our
  // own connection's fault; reporting LIVE would be a lie.
  if (connectionState != ConnectionState.OPEN) return UnitLiveness.OFFLINE

  if (telemetryAgeMs == null) return UnitLiveness.NEVER_SEEN
  if (telemetryAgeMs > staleTimeoutMs) return UnitLiveness.STALE

  // Fresh deltas, but the server says the unit is gone: believe the server. It
  // owns the arm gate, and a UI offering an ARM the server will refuse is worse
  // than one that is briefly over-cautious.
  // ...but only while that verdict is itself current. A retained `false` from a
  // plugin that has since stopped publishing is last-known, not news. An
  // UNKNOWN age falls back to believing it -- the fail-safe direction.
  if (pluginUnitLive == false && (pluginVerdictAgeMs == null || pluginVerdictAgeMs <= staleTimeoutMs)) {
    return UnitLiveness.STALE
  }

  return UnitLiveness.LIVE
}

/** The single condition under which arming may be offered. */
fun readyToArm(liveness: UnitLiveness): Boolean = liveness == UnitLiveness.LIVE
