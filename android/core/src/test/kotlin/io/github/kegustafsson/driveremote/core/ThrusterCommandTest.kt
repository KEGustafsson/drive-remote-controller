package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The mode change is the interesting case, and it is a regression suite before
 * it is anything else: a held direction used to survive it, because the release
 * event that would have cleared it is destroyed along with the buttons that
 * would have reported it.
 */
class ThrusterCommandTest {

  /** Holding PORT in MANUAL -- one thumb down on a contact. */
  private val holdingPort =
    ThrusterCommand().withDirection(ThrusterDirection.PORT)

  @Nested
  inner class ModeChangeReleasesTheDirection {

    /**
     * The vector this class exists for, walked in the order two thumbs walk it.
     *
     * Selecting HOLD while PORT is held takes the contacts off the screen, so
     * their release is never reported. If the direction survived that, every
     * heartbeat would keep publishing a thrust nobody is asking for.
     */
    @Test
    fun `selecting HOLD while PORT is held releases the thrust`() {
      val held = holdingPort
      assertEquals(ThrusterDirection.PORT, held.direction)

      val holding = held.withMode(ThrusterMode.HOLD)

      assertEquals(ThrusterDirection.OFF, holding.direction)
      assertEquals(ThrusterMode.HOLD, holding.mode)
    }

    /**
     * The second half, and the dangerous one. Coming back to MANUAL must not
     * replay the direction that was held on the way out: MANUAL has no firmware
     * dwell, so HH would act on it at once, with no finger on the glass.
     */
    @Test
    fun `returning to MANUAL does not replay the direction held on the way out`() {
      val backInManual = holdingPort.withMode(ThrusterMode.HOLD).withMode(ThrusterMode.MANUAL)

      assertEquals(ThrusterDirection.OFF, backInManual.direction)
      assertEquals(ThrusterMode.MANUAL, backInManual.mode)
    }

    /** The same release in the other direction of travel, for completeness. */
    @Test
    fun `leaving MANUAL releases a held STBD too`() {
      val held = ThrusterCommand().withDirection(ThrusterDirection.STBD)

      assertEquals(ThrusterDirection.OFF, held.withMode(ThrusterMode.HOLD).direction)
    }

    /**
     * Re-tapping the mode that is ALREADY selected is not a mode change and must
     * not cut the thrust out from under a finger that is still down -- nothing
     * leaves the screen in that case, so nothing would ever restore it.
     */
    @Test
    fun `re-tapping the selected mode leaves a held direction alone`() {
      assertSame(holdingPort, holdingPort.withMode(ThrusterMode.MANUAL))
    }
  }

  @Nested
  inner class Trim {

    @Test
    fun `leaving HOLD zeroes the trim so re-entering starts from the fresh capture`() {
      val trimmed = ThrusterCommand(mode = ThrusterMode.HOLD).trimmedBy(10.0)
      assertEquals(10.0, trimmed.trimDeg)

      assertEquals(0.0, trimmed.withMode(ThrusterMode.MANUAL).trimDeg)
    }

    @Test
    fun `entering HOLD keeps what is there, which is already zero`() {
      val manual = ThrusterCommand()
      assertEquals(0.0, manual.withMode(ThrusterMode.HOLD).trimDeg)
    }

    @Test
    fun `steps are clamped, not accumulated past the limit`() {
      var command = ThrusterCommand(mode = ThrusterMode.HOLD)
      repeat(10) { command = command.trimmedBy(10.0) }

      assertEquals(SkContract.MAX_TRIM_DEG, command.trimDeg)
    }

    @Test
    fun `untrimmed drops the offset without disturbing mode or direction`() {
      val holding =
        ThrusterCommand(mode = ThrusterMode.HOLD).trimmedBy(-10.0).untrimmed()

      assertEquals(0.0, holding.trimDeg)
      assertEquals(ThrusterMode.HOLD, holding.mode)
    }
  }

  @Nested
  inner class Safe {

    /**
     * MANUAL + OFF + no trim is the only tuple that commands nothing under both
     * modes' rules -- see the companion's KDoc for why OFF alone is not enough.
     */
    @Test
    fun `the safe value is inert under either mode's rules`() {
      assertEquals(ThrusterDirection.OFF, ThrusterCommand.SAFE.direction)
      assertEquals(ThrusterMode.MANUAL, ThrusterCommand.SAFE.mode)
      assertEquals(0.0, ThrusterCommand.SAFE.trimDeg)
    }

    /** It has to agree with the intent :core already publishes as safe. */
    @Test
    fun `it matches the safe intent's thruster fields`() {
      val safeIntent =
        ClientIntent.safe(clientId = "phone", session = 1, seq = 1, armReq = 0, disarmReq = 0)

      assertEquals(safeIntent.thruster, ThrusterCommand.SAFE.direction)
      assertEquals(safeIntent.thrusterMode, ThrusterCommand.SAFE.mode)
      assertEquals(safeIntent.trimDeg, ThrusterCommand.SAFE.trimDeg)
    }
  }
}
