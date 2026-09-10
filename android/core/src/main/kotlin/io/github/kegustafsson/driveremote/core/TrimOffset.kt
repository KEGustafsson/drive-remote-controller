package io.github.kegustafsson.driveremote.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Relative heading-TRIM offset. Port of `sk-plugin/src/pure/trimOffset.ts`,
 * itself the counterpart of the firmware's `heading/heading_nudge.h`.
 *
 * WHY A RELATIVE OFFSET rather than an absolute target: a station never asks
 * for an absolute heading -- it arms (HH captures whatever heading it is on)
 * and then nudges. An offset from the captured heading means the resting value
 * is a plain 0 ("no trim"), so no seed is needed, no out-of-band "not
 * commanding" sentinel is needed (0 says it), and it fails soft: a lost or
 * corrupted value is at most a clamped offset, never an arbitrary heading.
 *
 * It remains a self-correcting LEVEL over a lossy link -- the whole offset,
 * not per-press events -- so the last message is the whole truth and a repeat
 * is harmless.
 */

/**
 * Wrap to (-180, 180], matching `control_core::WrapDeg180`.
 *
 * Modulo-based, not a subtract-360 loop, for the reason `angle_math.h` spells
 * out: a loop never terminates for +/-Infinity and takes an unbounded number
 * of iterations for a huge finite value. On the ESP32 that stalls a task; here
 * it would wedge the UI thread -- including the kill switch. `%` is
 * constant-time for any magnitude and yields NaN for non-finite input, so
 * garbage stays garbage (callers screen it) but can never lock the app up.
 */
fun wrapDeg180(deg: Double): Double {
  var d = deg % 360.0
  if (d > 180.0) d -= 360.0 else if (d <= -180.0) d += 360.0
  return d
}

/**
 * Is this a plausible heading? Used to screen HH's reported held heading
 * before it is displayed.
 *
 * Accepts any [Number], because a JSON delta may decode an integral heading as
 * Int and a fractional one as Double. Everything else -- String, Boolean, null,
 * absent -- is rejected, matching what `typeof deg === 'number'` rejects in the
 * TypeScript original. Kotlin needs no separate Boolean guard the way a
 * JavaScript port might: `Boolean` is not a `Number` here, so `true` fails the
 * type check outright (pinned by a case in TrimOffsetTest).
 */
fun isPlausibleHeading(raw: Any?): Boolean {
  if (raw !is Number) return false
  val d = raw.toDouble()
  return d.isFinite() && d >= -360.0 && d <= 360.0
}

/**
 * The value if it is a plausible heading, else null -- the shape callers
 * actually want (`App.tsx` does exactly this test-then-narrow). Kotlin has no
 * type-guard narrowing, so returning the value is more useful than a Boolean.
 */
fun plausibleHeadingOrNull(raw: Any?): Double? =
  if (isPlausibleHeading(raw)) (raw as Number).toDouble() else null

/**
 * Coerce a trim offset to a safe, bounded value: non-finite reads as 0 (no
 * trim), anything else is clamped to +/-[SkContract.MAX_TRIM_DEG]. Mirrors
 * `control_core::ClampTrimDeg`. There is deliberately no "invalid" outcome --
 * 0 is the safe, meaningful rest.
 */
fun clampTrim(deg: Double): Double =
  when {
    !deg.isFinite() -> 0.0
    deg > SkContract.MAX_TRIM_DEG -> SkContract.MAX_TRIM_DEG
    deg < -SkContract.MAX_TRIM_DEG -> -SkContract.MAX_TRIM_DEG
    else -> deg
  }

/** Apply one signed trim step to the current offset, clamped. */
fun trimBy(current: Double, stepDeg: Double): Double = clampTrim(current + stepDeg)

/** Format a signed trim offset for display: "+15", "0", "−10". */
fun formatTrim(deg: Double): String {
  val rounded = deg.roundToInt()
  return when {
    rounded > 0 -> "+$rounded"
    rounded < 0 -> "−${abs(rounded)}" // U+2212 minus, not a hyphen
    else -> "0"
  }
}

/**
 * Format an absolute heading (the held heading, shown for reference) as three
 * digits. Null renders as dashes -- "no reading", never a plausible-looking 000.
 */
fun formatHeading(deg: Double?): String {
  if (deg == null) return "---"
  val positive = ((deg % 360.0) + 360.0) % 360.0
  // Round BEFORE the final modulo: 359.6 must render as 000, not 360.
  val whole = (positive.roundToLong() % 360L).toInt()
  return whole.toString().padStart(3, '0')
}
