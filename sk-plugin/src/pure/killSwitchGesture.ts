// What a kill-switch gesture MEANS, and when it acts. Pure, so the rule is
// testable without React or DOM events -- and so the Android station's twin can
// be checked against the same table.
//
// Owner decision (2026-09-24): A KILL-SWITCH GESTURE'S MEANING IS FIXED AT
// TOUCH-DOWN.
//
// The button used to act on click, i.e. on LIFT -- and a click only fires when
// the pointer comes up inside the button. A hurried STOP whose finger slid off
// the button therefore sent nothing at all. So:
//
//  - Pointer down that means STOP: STOP there and then. The gesture is spent,
//    and it can never arm: the click that follows it (same gesture) is
//    swallowed, whatever the button shows by the time the finger lifts.
//  - Pointer down that means ARM: nothing yet. ARM happens on the click, so
//    sliding off still cancels an arm the operator did not mean -- lift-to-act
//    is the right shape for ARM and the wrong one for STOP. The meaning is
//    re-decided at the click; if the button has come to mean STOP meanwhile
//    (another station armed during the press), the click is a STOP.
//  - A click with no pointer down behind it -- keyboard, a screen reader, or
//    anything else that activates the button without a touch -- is decided by
//    the same policy at the click, exactly as before.
//
// A SWALLOW MUST NEVER OUTLIVE ITS GESTURE. The flag that says "the next click
// belongs to a STOP already sent" is cleared by every click (swallowed or not),
// by the next pointer down (which decides afresh), and by a pointer cancel
// (after which no click comes). A click with `detail === 0` -- keyboard,
// screen reader, script -- is never swallowed, so a STOP that slid off and
// left the flag standing cannot eat a later Enter/Space. A POINTER click
// (`detail >= 1`) on the button always has its own pointer down on the button
// first -- a click is dispatched to the common ancestor of where the pointer
// went down and came up -- so that pointer down has already re-decided the
// flag before the click can see it.
//
// Deliberately NOT keyed on the click's pointerId: browsers disagree about
// what id a click carries, and a mismatch there would un-swallow the STOP
// gesture's own lift -- which, held past the holdover, would ARM.

import { KILL_SWITCH_STOP_HOLDOVER_MS } from '../config';

export type KillTapMeaning = 'stop' | 'arm';

/**
 * What a tap means right now -- the existing holdover policy.
 *
 * @param disarms the button currently means STOP (armed, IN USE, or offline).
 * @param stopEndedAtMs when the button last stopped meaning STOP; null if it
 *   has not, this mount.
 * @returns the meaning, and whether this tap restarts the holdover: a tap held
 *   over as STOP restarts the window, so hammered STOP taps at any pace under
 *   the holdover never reach ARM, however many there are.
 */
export function killTapMeaning(
  disarms: boolean,
  stopEndedAtMs: number | null,
  nowMs: number,
  holdoverMs: number = KILL_SWITCH_STOP_HOLDOVER_MS,
): { meaning: KillTapMeaning; restartHoldover: boolean } {
  if (disarms) return { meaning: 'stop', restartHoldover: false };
  const heldOver = stopEndedAtMs !== null && nowMs - stopEndedAtMs < holdoverMs;
  return heldOver
    ? { meaning: 'stop', restartHoldover: true }
    : { meaning: 'arm', restartHoldover: false };
}

/** The gesture in progress, as far as swallowing its click goes. */
export interface KillGesture {
  /** This gesture already sent its STOP on pointer down; its click is spent. */
  stopSentOnDown: boolean;
}

export const NO_GESTURE: KillGesture = { stopSentOnDown: false };

export interface KillGestureStep {
  gesture: KillGesture;
  /** What to do now; null = nothing. */
  fire: KillTapMeaning | null;
}

/** Pointer down: a STOP acts now, an ARM waits for the click. */
export function killPointerDown(meaning: KillTapMeaning): KillGestureStep {
  return meaning === 'stop'
    ? { gesture: { stopSentOnDown: true }, fire: 'stop' }
    : { gesture: NO_GESTURE, fire: null };
}

/**
 * Click. `detail` is the click's UIEvent.detail: 0 for a keyboard-, assistive-
 * or script-generated click, >= 1 for a pointer's. `decide` runs the policy
 * NOW, and is called only for a click that will act -- a swallowed click must
 * not restart the holdover, since its gesture's STOP already went out on the
 * way down.
 */
export function killClick(
  gesture: KillGesture,
  detail: number,
  decide: () => KillTapMeaning,
): KillGestureStep {
  const spent = gesture.stopSentOnDown && detail > 0;
  return { gesture: NO_GESTURE, fire: spent ? null : decide() };
}

/** Pointer cancel: the gesture ended with no click coming. */
export function killPointerCancel(): KillGestureStep {
  return { gesture: NO_GESTURE, fire: null };
}
