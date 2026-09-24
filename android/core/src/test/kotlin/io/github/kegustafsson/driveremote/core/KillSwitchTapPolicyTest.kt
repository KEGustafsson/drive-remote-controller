package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A tap aimed at STOP stays a STOP. The same rule as the browser's kill switch
 * holdover, with the clock passed in.
 */
class KillSwitchTapPolicyTest {

  private val holdover = SkContract.KILL_SWITCH_STOP_HOLDOVER_MS

  /**
   * The defect. First tap disarms; the arbiter's release is back in a few
   * milliseconds and the button flips to "tap to arm"; the second tap of the
   * double-tap lands on it. Deciding from the screen alone, that ARMED the
   * station -- and in HOLD, arming is the hold request.
   */
  @Test
  fun `the second tap of a double-tapped STOP is still a stop`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = true, nowMs = 1_000)
    assertEquals(KillSwitchTap.DISARM, policy.onTap(meansStop = true, nowMs = 1_000))

    // activeClient "" arrives; the button now offers ARM.
    policy.observe(meansStop = false, nowMs = 1_040)
    assertEquals(KillSwitchTap.DISARM, policy.onTap(meansStop = false, nowMs = 1_250))
  }

  @Test
  fun `a stop is still a stop right up to the end of the holdover`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = true, nowMs = 0)
    policy.observe(meansStop = false, nowMs = 5_000)
    assertEquals(KillSwitchTap.DISARM, policy.onTap(meansStop = false, nowMs = 5_000 + holdover - 1))
  }

  /** A held-over STOP restarts the window, so hammering STOP never reaches ARM. */
  @Test
  fun `hammered STOP taps never reach ARM`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = true, nowMs = 0)
    policy.observe(meansStop = false, nowMs = 1_000)
    var t = 1_000L
    repeat(6) {
      t += holdover - 100
      assertEquals(KillSwitchTap.DISARM, policy.onTap(meansStop = false, nowMs = t))
    }
    assertEquals(KillSwitchTap.ARM, policy.onTap(meansStop = false, nowMs = t + holdover))
  }

  @Test
  fun `once the holdover has passed a tap arms`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = true, nowMs = 0)
    policy.observe(meansStop = false, nowMs = 5_000)
    assertEquals(KillSwitchTap.ARM, policy.onTap(meansStop = false, nowMs = 5_000 + holdover))
  }

  /** IN USE, a second person's STOP, and the holder disarms just before it lands. */
  @Test
  fun `a stop aimed at another station's arm does not take the arm`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = true, nowMs = 0) // IN USE
    // The holder disarms; this screen flips to DISARMED; our tap lands after.
    assertEquals(KillSwitchTap.DISARM, policy.onTap(meansStop = false, nowMs = 300))
  }

  /** A screen that simply opens on DISARMED arms on its first tap. */
  @Test
  fun `with no stop to hold over, a tap arms at once`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = false, nowMs = 0)
    assertEquals(KillSwitchTap.ARM, policy.onTap(meansStop = false, nowMs = 1))

    // And with no observation at all.
    assertEquals(KillSwitchTap.ARM, KillSwitchTapPolicy().onTap(meansStop = false, nowMs = 1))
  }

  /** A flip the caller has not yet reported is caught by the tap itself. */
  @Test
  fun `a flip seen only by the tap starts the holdover from that tap`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = true, nowMs = 0)
    assertEquals(KillSwitchTap.DISARM, policy.onTap(meansStop = false, nowMs = 10_000))
    assertEquals(KillSwitchTap.ARM, policy.onTap(meansStop = false, nowMs = 10_000 + holdover))
  }

  /** Whatever the history, a button that means STOP now always stops. */
  @Test
  fun `a tap on a button that means stop always disarms`() {
    val policy = KillSwitchTapPolicy()
    for (t in listOf(0L, 10L, 5_000L, 50_000L)) {
      assertEquals(KillSwitchTap.DISARM, policy.onTap(meansStop = true, nowMs = t))
    }
  }

  // ---- The gesture: a STOP on the way down, an ARM on the way up -----------

  @Test
  fun `a press on a button that means stop disarms at once`() {
    val policy = KillSwitchTapPolicy()
    assertEquals(KillSwitchTap.DISARM, policy.onPress(meansStop = true, nowMs = 0))
  }

  @Test
  fun `a press on a button that offers an arm sends nothing yet`() {
    val policy = KillSwitchTapPolicy()
    assertNull(policy.onPress(meansStop = false, nowMs = 0))
    assertEquals(KillSwitchTap.ARM, policy.onRelease(meansStop = false, nowMs = 80, inside = true))
  }

  /** The defect: the lift of a STOP gesture, long after the holdover, armed. */
  @Test
  fun `the lift of a stop gesture sends nothing, however long it was held`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = true, nowMs = 0)
    assertEquals(KillSwitchTap.DISARM, policy.onPress(meansStop = true, nowMs = 0))
    // The arbiter's release lands while the finger is still down.
    policy.observe(meansStop = false, nowMs = 40)
    assertNull(policy.onRelease(meansStop = false, nowMs = 40 + 3 * holdover, inside = true))
  }

  @Test
  fun `an arm lifted outside the button sends nothing`() {
    val policy = KillSwitchTapPolicy()
    assertNull(policy.onPress(meansStop = false, nowMs = 0))
    assertNull(policy.onRelease(meansStop = false, nowMs = 100, inside = false))
  }

  /** Someone else armed while the finger was down: the lift is a STOP, not an arm. */
  @Test
  fun `an arm whose button came to mean stop mid-press lifts as a stop`() {
    val policy = KillSwitchTapPolicy()
    assertNull(policy.onPress(meansStop = false, nowMs = 0))
    policy.observe(meansStop = true, nowMs = 50)
    assertEquals(KillSwitchTap.DISARM, policy.onRelease(meansStop = true, nowMs = 90, inside = true))
  }

  /** A press held over as a STOP fires on the way down and restarts the window. */
  @Test
  fun `a held-over press stops on the way down and restarts the holdover`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = true, nowMs = 0)
    policy.observe(meansStop = false, nowMs = 1_000)
    val first = 1_000 + holdover - 1
    assertEquals(KillSwitchTap.DISARM, policy.onPress(meansStop = false, nowMs = first))
    assertNull(policy.onRelease(meansStop = false, nowMs = first + 50, inside = true))
    // Restarted from that press, not from the flip: still a STOP just inside it...
    assertEquals(KillSwitchTap.DISARM, policy.onPress(meansStop = false, nowMs = first + holdover - 1))
    assertNull(policy.onRelease(meansStop = false, nowMs = first + holdover, inside = true))
    // ...and an arm once a whole holdover has passed after the last STOP press.
    val later = first + 2 * holdover
    assertNull(policy.onPress(meansStop = false, nowMs = later))
    assertEquals(KillSwitchTap.ARM, policy.onRelease(meansStop = false, nowMs = later + 50, inside = true))
  }

  /** A release with no press in front of it -- a gesture begun elsewhere -- is nothing. */
  @Test
  fun `a release without a press sends nothing`() {
    assertNull(KillSwitchTapPolicy().onRelease(meansStop = false, nowMs = 0, inside = true))
  }

  /** The two ways in share one clock: a finger STOP holds over an accessibility click. */
  @Test
  fun `a pressed stop holds over a following accessibility click`() {
    val policy = KillSwitchTapPolicy()
    policy.observe(meansStop = true, nowMs = 0)
    assertEquals(KillSwitchTap.DISARM, policy.onPress(meansStop = true, nowMs = 0))
    policy.observe(meansStop = false, nowMs = 30)
    assertNull(policy.onRelease(meansStop = false, nowMs = 60, inside = true))
    assertEquals(KillSwitchTap.DISARM, policy.onTap(meansStop = false, nowMs = 400))
  }

  @Test
  fun `the holdover matches the browser's`() {
    // Hand-synced with sk-plugin/src/config.ts KILL_SWITCH_STOP_HOLDOVER_MS.
    assertEquals(1000L, SkContract.KILL_SWITCH_STOP_HOLDOVER_MS)
  }
}
