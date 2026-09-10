package io.github.kegustafsson.driveremote.core

/**
 * The thruster's three command fields, and the rules that couple them.
 *
 * They are one value rather than three because a MODE CHANGE is not a change to
 * the mode alone -- it decides what happens to the held direction and to the
 * trim as well, and holding them apart is what let those two answers drift.
 *
 * WHY THE DIRECTION CANNOT SIMPLY BE LEFT ALONE ACROSS A MODE CHANGE. The
 * MANUAL/HOLD chooser stays live while a PORT/STBD contact is held (it commands
 * nothing itself -- Controls.kt), so "hold PORT with one thumb, tap HOLD with
 * the other" is an ordinary two-fingered thing to do. The contacts belong to the
 * MANUAL block, so selecting HOLD removes them from the screen: the button's own
 * release fires, but the effect that TRANSLATES a release into
 * [ThrusterDirection.OFF] is being disposed in the same pass and never runs. The
 * release is real and the report of it is lost -- the one shape a momentary
 * control must never have. A held PORT then stands in the command state with no
 * finger anywhere near the glass: every heartbeat keeps publishing it, and
 * selecting MANUAL again replays it as a fresh manual thrust, which HH acts on
 * immediately because MANUAL has no firmware dwell by design (SAFETY.md thruster
 * invariant 7).
 *
 * So a mode change releases the direction HERE, in the state itself, where it
 * cannot depend on a UI event arriving. The UI also releases on dispose, but
 * that is the belt-and-braces; this is the rule.
 *
 * SAFETY.md: fail to the safe value -- OFF for the thruster -- never to the last
 * commanded one.
 */
data class ThrusterCommand(
  /** What the operator's thumbs are asking for right now. MANUAL only. */
  val direction: ThrusterDirection = ThrusterDirection.OFF,
  val mode: ThrusterMode = ThrusterMode.MANUAL,
  /** Relative heading trim, already clamped. 0 = no trim. HOLD only. */
  val trimDeg: Double = 0.0,
) {

  /**
   * Select a mode, releasing whatever the previous one was holding.
   *
   * An unchanged mode is deliberately a no-op rather than a release: the chips
   * are tappable whether or not they are already selected, and re-tapping the
   * selected one must not cut the thrust out from under a finger that is still
   * down. Nothing leaves the composition in that case, so nothing would restore
   * the direction either -- the thrust would stay dead until the operator lifted
   * and pressed again.
   *
   * Leaving HOLD zeroes the trim, so re-entering it starts from the heading HH
   * captures at that moment rather than resurrecting an offset dialled in
   * earlier. Entering HOLD keeps what is there, which is already 0 for exactly
   * that reason.
   */
  fun withMode(next: ThrusterMode): ThrusterCommand =
    if (next == mode) this
    else
      ThrusterCommand(
        direction = ThrusterDirection.OFF,
        mode = next,
        trimDeg = if (next == ThrusterMode.HOLD) trimDeg else 0.0,
      )

  /** Report what the PORT/STBD contacts are asking for. */
  fun withDirection(next: ThrusterDirection): ThrusterCommand = copy(direction = next)

  /** Apply one signed trim step, clamped by [trimBy] to +/-[SkContract.MAX_TRIM_DEG]. */
  fun trimmedBy(stepDeg: Double): ThrusterCommand = copy(trimDeg = trimBy(trimDeg, stepDeg))

  /**
   * Drop the trim without touching mode or direction -- for when the thruster
   * stops being commandable, so a later arm begins at the captured heading
   * instead of swinging the boat to an offset dialled in before the unit went
   * away.
   */
  fun untrimmed(): ThrusterCommand = if (trimDeg == 0.0) this else copy(trimDeg = 0.0)

  companion object {
    /**
     * The one tuple that commands nothing under EITHER mode's rules.
     *
     * `direction = OFF` alone does not stop the thruster: in HOLD, HH follows
     * the trimmed setpoint and does not consult the direction at all, so
     * MODE=HOLD with a non-zero trim still qualifies this station as a live
     * source and keeps the thruster working. Anything promising an immediate
     * safe release therefore has to leave HOLD and zero the trim as well, which
     * is what this is.
     */
    val SAFE = ThrusterCommand()
  }
}
