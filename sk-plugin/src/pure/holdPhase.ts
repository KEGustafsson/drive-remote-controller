// "What has become of the hold this station asked for?" -- pure, so the window
// that separates a transition from a fault is testable without timers or React
// (same split as pure/rxLiveness.ts). The Kotlin twin is `StationView.kt`'s
// HoldPhase/HoldStall in the Android station's core module; the two stations
// must not disagree about what is worth alarming an operator over.
//
// WHY THE MIDDLE STATE EXISTS: a hold is not instantaneous. The request leaves
// on the next intent, the arbiter republishes it, HH takes it on a control tick
// and reports back on its own telemetry cycle -- so for a few hundred
// milliseconds after every arm the honest answer is "asked, not confirmed".
// Drawn as a warning, that put one on the panel on every single arm, which is
// how an operator learns to read the one that MATTERS -- a hold the unit is
// genuinely refusing -- as the same harmless flicker.

import {
  HH_FSM_ARMED_IDLE,
  HH_FSM_DISARMED,
  HH_FSM_FAULT,
  HH_FSM_HOLDING,
  HOLD_ENGAGE_GRACE_MS,
} from '../config';

export type HoldPhase =
  /** This station is not asking for a hold, or cannot command the thruster. */
  | 'idle'
  /**
   * Asked for, not yet confirmed by HH, and still inside HOLD_ENGAGE_GRACE_MS.
   * A transition, shown as a plain statement of what was asked -- never as a
   * warning.
   */
  | 'requested'
  /** HH reports the hold running, by its own report and nothing else. */
  | 'engaged'
  /**
   * Asked for, and HH has had long enough to take it and has not. A fault, and
   * shown as one. See HoldStall for the reason.
   */
  | 'not-engaging';

export type HoldStall =
  /** Not stalled. */
  | 'none'
  /**
   * HH is not armed at all: it is refusing the request. The usual cause is the
   * re-engage latch (SAFETY.md thruster invariant 9) -- a station whose link
   * went stale mid-hold is refused until HH has seen it live and DISARMED, so
   * a returning link cannot silently restart thrust. The operator's move is to
   * disarm and arm again.
   */
  | 'refused'
  /**
   * HH is armed but idle, which in HOLD means it has no heading it trusts
   * enough to steer against -- either it never had one, or it gave the hold up
   * after coasting past coast_max on a stale fix. Either way it needs a fresh
   * arm once the fix is back; nothing in this UI can shorten that.
   */
  | 'no-reference'
  /** HH has faulted -- its motion sensor has gone silent. Thrust is refused. */
  | 'unit-fault'
  /**
   * Somebody else has the thruster (the unit's own ENGAGE input, or the
   * handheld). Not a fault at all, and already explained by the
   * "controlled by ..." note, which is why this one is drawn as nothing.
   */
  | 'other-source'
  /**
   * HH has not said, or said something this build does not recognise. The
   * message falls back to the fact -- it is not holding -- and to the one
   * remedy that is safe to suggest in every case.
   */
  | 'unknown';

export interface HoldPhaseInputs {
  /** Can this station command the thruster at all right now? */
  thrusterCommandable: boolean;
  /** HH's own report that the hold is running. Never a local guess. */
  holdEngaged: boolean;
  /**
   * How long this station has been asking for a hold, ms -- or null when it is
   * not asking for one (MANUAL selected, or not armed). The null case is "no
   * hold wanted" rather than "unknown", so a caller that does not track it gets
   * 'idle' and no hold diagnostics, never a false alarm.
   */
  requestedForMs: number | null;
}

/** The whole rule, in one place. */
export function evaluateHoldPhase({
  thrusterCommandable,
  holdEngaged,
  requestedForMs,
}: HoldPhaseInputs): HoldPhase {
  if (requestedForMs === null || !thrusterCommandable) return 'idle';
  if (holdEngaged) return 'engaged';
  return requestedForMs < HOLD_ENGAGE_GRACE_MS ? 'requested' : 'not-engaging';
}

/**
 * Why the hold is not running, from HH's own FSM state.
 *
 * `overridden` is checked first: a hold that is not running because the
 * thruster belongs to a higher-precedence source is not this station's fault to
 * report twice.
 */
export function holdStallReason(
  hhFsmState: unknown,
  overridden: boolean,
): HoldStall {
  if (overridden) return 'other-source';
  switch (hhFsmState) {
    case HH_FSM_FAULT:
      return 'unit-fault';
    case HH_FSM_ARMED_IDLE:
      return 'no-reference';
    case HH_FSM_DISARMED:
      return 'refused';
    default:
      return 'unknown';
  }
}

/**
 * Is HH ACTUALLY holding, by its own report?
 *
 * The armed + hold pair is necessary and not sufficient: HH asserts ENABLE --
 * and so publishes hh.armed -- in ARMED_IDLE as well as HOLDING, and in HOLD
 * mode ARMED_IDLE is precisely the state of a hold that never started, or that
 * was given up when the heading went stale. HH's own FSM state settles it when
 * it is there; when it is not (nothing has arrived yet, or a firmware that
 * predates the path) the pair stands on its own as before.
 *
 * `hhLive` is the caller's liveness verdict, and it is not optional: every input
 * here is a VALUE, and Signal K retains those forever, so without it a board
 * switched off an hour ago still claims to be holding.
 */
export function holdEngagedFrom(
  hhArmed: unknown,
  hhMode: unknown,
  hhFsmState: unknown,
  hhLive: boolean,
): boolean {
  if (!hhLive) return false;
  if (hhArmed !== true || hhMode !== 'hold') return false;
  return hhFsmState === undefined || hhFsmState === null
    ? true
    : hhFsmState === HH_FSM_HOLDING;
}

export interface ManualRefusalInputs {
  /** Can this station command the thruster at all right now? */
  thrusterCommandable: boolean;
  /**
   * How long this station has been asking for MANUAL -- armed and commandable
   * with MANUAL selected -- ms; null when it is not.
   */
  requestedForMs: number | null;
  /** HH's own FSM state (sensors.headingHold.fsmState). */
  hhFsmState: unknown;
  /** A higher-precedence source owns the thruster. */
  overridden: boolean;
}

/**
 * MANUAL's counterpart of the hold diagnosis: is HH refusing an arm this
 * station holds?
 *
 * In MANUAL there is no hold to fail to engage, so nothing above ever fires --
 * yet HH can still refuse the station outright. A release of HH's own ENGAGE
 * input latches every armed remote out until it disarms and re-arms (the same
 * re-engage latch as SAFETY.md thruster invariant 9), and HH then reports
 * DISARMED while this station still holds the arbiter's token. The station
 * reads armed, its PORT/STBD contacts light under a finger, and the thruster
 * does nothing: a live-looking control that reaches nothing. So it is said.
 *
 * Deliberately narrower than holdStallReason:
 *  - DISARMED is the refusal, FAULT is the unit's fault; both are positive
 *    reports that the thruster is not taking commands.
 *  - ARMED_IDLE is NOT a refusal here. It is exactly where an armed MANUAL unit
 *    sits with no contact pressed (hh.armed is true in it); flagging it would
 *    alarm on every MANUAL session.
 *  - Anything else -- HOLDING, a state this build does not know, or none at
 *    all -- is no positive evidence of refusal, and MANUAL, unlike HOLD, has no
 *    "engaged" report whose absence could stand in for one.
 *  - A higher-precedence source in control is drawn as nothing, as in HOLD:
 *    the "controlled by ..." note already says it.
 *  - The same HOLD_ENGAGE_GRACE_MS window, measured from when this station
 *    started asking: HH's state for the first moments after an arm is the
 *    state it was in BEFORE the arm reached it, which is DISARMED every time.
 */
export function manualRefusal({
  thrusterCommandable,
  requestedForMs,
  hhFsmState,
  overridden,
}: ManualRefusalInputs): HoldStall {
  if (requestedForMs === null || !thrusterCommandable || overridden) return 'none';
  if (requestedForMs < HOLD_ENGAGE_GRACE_MS) return 'none';
  switch (hhFsmState) {
    case HH_FSM_DISARMED:
      return 'refused';
    case HH_FSM_FAULT:
      return 'unit-fault';
    default:
      return 'none';
  }
}
