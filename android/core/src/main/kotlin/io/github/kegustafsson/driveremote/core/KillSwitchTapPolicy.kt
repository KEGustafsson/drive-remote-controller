package io.github.kegustafsson.driveremote.core

/** What one tap on the kill switch does. */
enum class KillSwitchTap {
  ARM,
  /** The universal stop. Harmless when nothing is armed. */
  DISARM,
}

/**
 * Decides what a tap on the kill switch MEANS -- the Kotlin twin of the
 * browser's `KillSwitch.tsx` holdover.
 *
 * Reading the meaning off the screen at the instant the click fires is wrong in
 * the dangerous direction. Double-tap STOP: the first tap disarms, the arbiter's
 * `activeClient: ""` is back within milliseconds, the button flips to "tap to
 * arm" -- and the second tap, aimed at STOP, arms. In HOLD that is not even
 * inert: arming IS the hold request, so HH starts working the thruster.
 *
 * So for [holdoverMs] after the button last STOPPED meaning STOP, a tap still
 * means STOP -- and such a tap restarts the window, so an arm needs a pause of
 * [holdoverMs] after the last STOP-meant tap. Only a STOP -> arm transition stamps the clock; a screen that
 * simply opens on DISARMED arms on its first tap, as the browser's does.
 *
 * Pure, with the clock passed in, so the rule is tested here rather than only
 * with two thumbs on a phone.
 */
class KillSwitchTapPolicy(private val holdoverMs: Long = SkContract.KILL_SWITCH_STOP_HOLDOVER_MS) {

  /** What the button meant at the last observation; null before the first. */
  private var lastMeantStop: Boolean? = null

  /** When the button last stopped meaning STOP; null if it has not. */
  private var stopEndedAtMs: Long? = null

  /**
   * Record what the button means now. Call every time that is (re)decided --
   * in Compose, from a `SideEffect`, so the stamp lands in the same frame that
   * flips the button and no tap can fall between the two.
   */
  fun observe(meansStop: Boolean, nowMs: Long) {
    if (lastMeantStop == true && !meansStop) stopEndedAtMs = nowMs
    lastMeantStop = meansStop
  }

  /**
   * A tap has landed on a button that currently [meansStop] or not.
   *
   * Observes first, so a flip the caller has not yet reported is still counted
   * from this moment rather than missed.
   */
  fun onTap(meansStop: Boolean, nowMs: Long): KillSwitchTap {
    observe(meansStop, nowMs)
    if (meansStop) return KillSwitchTap.DISARM
    val endedAt = stopEndedAtMs
    if (endedAt != null && nowMs - endedAt < holdoverMs) {
      // A tap held over as STOP restarts the window: hammered STOP taps at any
      // pace under holdoverMs never reach ARM, however many there are.
      stopEndedAtMs = nowMs
      return KillSwitchTap.DISARM
    }
    return KillSwitchTap.ARM
  }
}
