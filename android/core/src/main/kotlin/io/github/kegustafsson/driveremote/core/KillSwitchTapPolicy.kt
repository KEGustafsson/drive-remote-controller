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
 * Two ways in, one rule. [onTap] is a whole tap at once -- the accessibility
 * click (TalkBack's double-tap, switch access), which has no touch-down to act
 * on. [onPress] and [onRelease] are the same tap split across a finger's
 * gesture, so that a STOP goes out on the way down; see the comment above them.
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

  // ---- The pointer gesture ------------------------------------------------
  //
  // A tap is two events, and which one ACTS depends on what the tap means. A
  // STOP acts on the way DOWN: waiting for the lift, as `clickable` does, loses
  // it to a thumb that lands and skids off -- the ordinary way a hand hits a
  // button on a moving boat, and the one moment the button matters most. An
  // ARM acts on the way UP, inside the button: it is the deliberate direction,
  // and a finger that slides off it has changed its mind.
  //
  // And a gesture's meaning is fixed when it starts. A STOP's own release comes
  // back from the arbiter within milliseconds and flips the button to "tap to
  // arm" while the finger is still down; if the lift were then asked afresh --
  // after a long press, with the holdover expired -- the second half of a STOP
  // would arm the station. So a gesture that stopped on the way down is spent.

  /** What the gesture in progress decided at its touch-down; null between gestures. */
  private var gesture: KillSwitchTap? = null

  /**
   * A finger has landed on a button that currently [meansStop] or not.
   *
   * Returns [KillSwitchTap.DISARM] when the STOP must be sent NOW -- the button
   * means STOP, or the tap is held over as one ([onTap]'s rule, including
   * restarting the window). Returns null for an ARM, which waits for [onRelease].
   */
  fun onPress(meansStop: Boolean, nowMs: Long): KillSwitchTap? {
    val decided = onTap(meansStop, nowMs)
    gesture = decided
    return decided.takeIf { it == KillSwitchTap.DISARM }
  }

  /**
   * The gesture begun by [onPress] has ended. [inside] is false when the finger
   * lifted outside the button, or the gesture was cancelled or taken by
   * something else -- which sends nothing.
   *
   * A gesture that stopped on the way down sends nothing more, whatever the
   * button shows by now. One that was waiting to ARM is asked afresh, so a
   * button that has meanwhile come to mean STOP -- another station armed while
   * the finger was down -- sends the STOP instead. That is never the dangerous
   * direction.
   */
  fun onRelease(meansStop: Boolean, nowMs: Long, inside: Boolean): KillSwitchTap? {
    val decided = gesture
    gesture = null
    if (decided != KillSwitchTap.ARM || !inside) return null
    return onTap(meansStop, nowMs)
  }
}
