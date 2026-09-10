// Relative heading-TRIM offset for the phone UI -- the TypeScript counterpart
// of the firmware's heading/heading_nudge.h, and pure for the same reason:
// this decides how far off the held heading the boat is asked to point, so it
// must be testable without React, timers or a socket.
//
// WHY A RELATIVE OFFSET (and not an absolute target): a station never asks for
// an absolute heading -- it arms (HH captures whatever heading it is on) and
// then nudges. Representing the command as an offset from the captured heading
// means the resting value is a plain 0 ("no trim"), so:
//   - no seed is needed (a press is well-defined with no knowledge of HH),
//   - no out-of-band sentinel is needed to say "not commanding" (0 says it),
//   - it fails soft: a lost or corrupted value is at most a clamped offset,
//     never an arbitrary heading.
// It is still a self-correcting LEVEL over a lossy link (the whole offset, not
// per-press events): the last message is the whole truth and a repeat is
// harmless -- the same property the absolute design had, without the seed.

import { MAX_TRIM_DEG } from '../config';

/**
 * Wrap to (-180, 180], matching control_core::WrapDeg180.
 *
 * Modulo-based, not a subtract-360 loop, for the same reason angle_math.h
 * spells out: a loop never terminates for +/-Infinity and takes an unbounded
 * number of iterations for a huge finite value. In C++ that stalls a task; in
 * a browser it hangs the tab -- including the kill switch. `%` is
 * constant-time for any magnitude and yields NaN for non-finite input, so
 * garbage stays garbage (callers screen with isPlausibleHeading/clampTrim)
 * but can never lock the UI up.
 */
export function wrapDeg180(deg: number): number {
  let d = deg % 360;
  if (d > 180) d -= 360;
  else if (d <= -180) d += 360;
  return d;
}

/** Is a value a plausible heading? Used to screen HH's reported held heading. */
export function isPlausibleHeading(deg: unknown): deg is number {
  return typeof deg === 'number' && Number.isFinite(deg) && deg >= -360 && deg <= 360;
}

/**
 * Coerce a trim offset to a safe, bounded value: non-finite reads as 0 (no
 * trim), anything else is clamped to +/-MAX_TRIM_DEG. Mirrors
 * control_core::ClampTrimDeg. There is no "invalid" outcome -- 0 is the safe,
 * meaningful rest.
 */
export function clampTrim(deg: number): number {
  if (!Number.isFinite(deg)) return 0;
  if (deg > MAX_TRIM_DEG) return MAX_TRIM_DEG;
  if (deg < -MAX_TRIM_DEG) return -MAX_TRIM_DEG;
  return deg;
}

/** Apply one signed trim step to the current offset, clamped. */
export function trimBy(current: number, stepDeg: number): number {
  return clampTrim(current + stepDeg);
}

/** Format a signed trim offset for display: "+15", "0", "−10". */
export function formatTrim(deg: number): string {
  const rounded = Math.round(deg);
  if (rounded > 0) return `+${rounded}`;
  if (rounded < 0) return `−${Math.abs(rounded)}`; // proper minus sign
  return '0';
}

/** Format an absolute heading (the held heading, for reference) as 3 digits. */
export function formatHeading(deg: number | null): string {
  if (deg === null) return '---';
  const positive = ((deg % 360) + 360) % 360;
  return String(Math.round(positive) % 360).padStart(3, '0');
}
