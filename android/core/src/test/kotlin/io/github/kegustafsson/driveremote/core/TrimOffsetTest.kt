package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Ported one-for-one from `sk-plugin/src/pure/trimOffset.test.ts`. */
class TrimOffsetTest {

  @Nested
  inner class WrapDeg180 {
    @Test
    fun `leaves in-range values alone`() {
      assertEquals(0.0, wrapDeg180(0.0))
      assertEquals(180.0, wrapDeg180(180.0))
      assertEquals(-179.0, wrapDeg180(-179.0))
    }

    @Test
    fun `wraps out-of-range values into the half-open interval`() {
      assertEquals(-179.0, wrapDeg180(181.0))
      assertEquals(180.0, wrapDeg180(-180.0))
      assertEquals(10.0, wrapDeg180(370.0))
    }

    /**
     * Why this is modulo-based and not a subtract-360 loop: a loop never
     * terminates for +/-Infinity and iterates ~1e28 times for a large finite
     * value. Either would wedge the UI thread -- including the kill switch.
     * Returning NaN for garbage is fine (callers screen it); hanging is not.
     */
    @Test
    fun `returns promptly for non-finite and enormous inputs`() {
      assertTrue(wrapDeg180(Double.POSITIVE_INFINITY).isNaN())
      assertTrue(wrapDeg180(Double.NEGATIVE_INFINITY).isNaN())
      assertTrue(wrapDeg180(Double.NaN).isNaN())
      assertTrue(wrapDeg180(1e30).isFinite())
    }
  }

  @Nested
  inner class IsPlausibleHeading {
    @Test
    fun `accepts finite headings within plus or minus 360`() {
      assertTrue(isPlausibleHeading(0))
      assertTrue(isPlausibleHeading(-360.0))
      assertTrue(isPlausibleHeading(360.0))
    }

    @Test
    fun `rejects out-of-range, non-finite and non-number values`() {
      assertFalse(isPlausibleHeading(361.0))
      assertFalse(isPlausibleHeading(Double.NaN))
      assertFalse(isPlausibleHeading(Double.POSITIVE_INFINITY))
      assertFalse(isPlausibleHeading("40"))
      assertFalse(isPlausibleHeading(null))
      assertFalse(isPlausibleHeading(true)) // Kotlin-specific: Boolean is not a heading
    }

    @Test
    fun `narrowing helper returns the value or null`() {
      assertEquals(41.0, plausibleHeadingOrNull(41))
      assertEquals(null, plausibleHeadingOrNull("41"))
      assertEquals(null, plausibleHeadingOrNull(Double.NaN))
    }
  }

  @Nested
  inner class ClampTrim {
    @Test
    fun `passes a value within range`() {
      assertEquals(15.0, clampTrim(15.0))
      assertEquals(-30.0, clampTrim(-30.0))
      assertEquals(0.0, clampTrim(0.0))
    }

    @Test
    fun `bounds to the maximum trim`() {
      assertEquals(SkContract.MAX_TRIM_DEG, clampTrim(90.0))
      assertEquals(-SkContract.MAX_TRIM_DEG, clampTrim(-90.0))
      // An old 999 sentinel, or corruption, simply saturates -- it does not
      // hang and does not become an arbitrary heading.
      assertEquals(SkContract.MAX_TRIM_DEG, clampTrim(999.0))
    }

    @Test
    fun `maps non-finite to zero -- the safe rest`() {
      assertEquals(0.0, clampTrim(Double.NaN))
      assertEquals(0.0, clampTrim(Double.POSITIVE_INFINITY))
      assertEquals(0.0, clampTrim(Double.NEGATIVE_INFINITY))
    }
  }

  @Nested
  inner class TrimBy {
    @Test
    fun `starts from zero and needs no seed -- a press is always well-defined`() {
      assertEquals(1.0, trimBy(0.0, 1.0))
      assertEquals(-10.0, trimBy(0.0, -10.0))
    }

    @Test
    fun `accumulates and clamps at the limit however many presses`() {
      var t = 0.0
      repeat(100) { t = trimBy(t, 1.0) }
      assertEquals(SkContract.MAX_TRIM_DEG, t)
      repeat(200) { t = trimBy(t, -1.0) }
      assertEquals(-SkContract.MAX_TRIM_DEG, t)
    }

    @Test
    fun `returns to zero by trimming back`() {
      assertEquals(0.0, trimBy(trimBy(0.0, 10.0), -10.0))
    }
  }

  @Nested
  inner class FormatTrim {
    @Test
    fun `shows sign and magnitude, zero as plain zero`() {
      assertEquals("0", formatTrim(0.0))
      assertEquals("+15", formatTrim(15.0))
      // U+2212 minus sign, not a hyphen -- same as the web UI.
      assertEquals("−10", formatTrim(-10.0))
    }
  }

  @Nested
  inner class FormatHeading {
    @Test
    fun `formats to three digits`() {
      assertEquals("000", formatHeading(0.0))
      assertEquals("007", formatHeading(7.0))
      assertEquals("041", formatHeading(41.0))
      assertEquals("180", formatHeading(180.0))
    }

    @Test
    fun `normalises negatives into 0 to 359`() {
      assertEquals("270", formatHeading(-90.0))
      assertEquals("359", formatHeading(-1.0))
    }

    @Test
    fun `wraps 360 to 000`() {
      assertEquals("000", formatHeading(360.0))
      assertEquals("000", formatHeading(-360.0))
    }

    @Test
    fun `rounding up to 360 still renders as 000`() {
      // Not in the vitest suite: Kotlin rounds before the final modulo, and
      // this pins that it does. 359.6 must not render as "360".
      assertEquals("000", formatHeading(359.6))
    }

    @Test
    fun `renders null as dashes rather than a plausible-looking zero`() {
      assertEquals("---", formatHeading(null))
    }
  }
}
