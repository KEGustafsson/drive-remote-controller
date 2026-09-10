// Pure TypeScript, no DOM/React/WebSocket deps -- host-testable in
// isolation, same discipline as lib/control_core/drive_command.h in the
// firmware (this file is a deliberate line-for-line mirror of it; keep the
// two in sync if either changes).
//
// DrivePosition IS the wire representation here (unlike the firmware,
// which keeps an internal enum and converts at the SK boundary via
// ToSkString/FromSkString) -- in TypeScript the string union type serves
// both roles with no loss of safety, since the type system already
// prevents constructing anything outside these three values from
// literals. fromSwitch() below still can't produce an invalid value even
// though there's no separate "boundary conversion" step to do it in.

export type DrivePosition = 'forward' | 'neutral' | 'reverse';

/**
 * Maps a side's two momentary button states to a position. Structurally
 * mirrors control_core::FromSwitch() in drive_command.h, including its
 * fail-to-neutral behavior when both contacts are active at once -- which
 * a single physical switch can never do, but which IS reachable here: two
 * independent touch buttons (forward, reverse) can both be pressed by two
 * different fingers simultaneously. Neutral, not an arbitrary pick, is the
 * only safe answer (SAFETY.md drive invariant 1: never both directions).
 */
export function fromSwitch(
  forwardActive: boolean,
  reverseActive: boolean,
): DrivePosition {
  if (forwardActive && reverseActive) return 'neutral';
  if (forwardActive) return 'forward';
  if (reverseActive) return 'reverse';
  return 'neutral';
}

/**
 * Defensive parse for values arriving FROM Signal K (RX's telemetry, read
 * for the status display) -- distinct from DrivePosition because a
 * display needs to be able to say "I don't actually know," which
 * commanding never does. Mirrors control_core::FromSkString()'s
 * fail-safe-on-garbage philosophy, but for display purposes silently
 * relabeling unparseable telemetry as "neutral" would misrepresent RX's
 * actual state rather than protect anything, so this returns a distinct
 * 'unknown' instead of guessing.
 */
export type DisplayDrivePosition = DrivePosition | 'unknown';

export function parseDisplayPosition(raw: unknown): DisplayDrivePosition {
  if (raw === 'forward' || raw === 'neutral' || raw === 'reverse') return raw;
  return 'unknown';
}
