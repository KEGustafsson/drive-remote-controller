package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Ported one-for-one from `sk-plugin/src/pure/driveCommand.test.ts`, which in
 * turn mirrors the firmware's `test/test_drive_command`. The point of porting
 * the VECTORS and not just the code is that this is now the third
 * implementation of the same truth table; the tables are what keep the three
 * from drifting apart silently.
 */
class DriveCommandTest {

  @Test
  fun `forward only maps to forward`() {
    assertEquals(DrivePosition.FORWARD, fromSwitch(forwardActive = true, reverseActive = false))
  }

  @Test
  fun `reverse only maps to reverse`() {
    assertEquals(DrivePosition.REVERSE, fromSwitch(forwardActive = false, reverseActive = true))
  }

  @Test
  fun `neither maps to neutral`() {
    assertEquals(DrivePosition.NEUTRAL, fromSwitch(forwardActive = false, reverseActive = false))
  }

  /**
   * The case a single physical switch cannot produce but two independent touch
   * buttons can: both fingers down at once. Must fail to neutral rather than
   * pick a direction (SAFETY.md drive invariant 1).
   */
  @Test
  fun `both pressed at once maps to neutral, never a direction`() {
    assertEquals(DrivePosition.NEUTRAL, fromSwitch(forwardActive = true, reverseActive = true))
  }

  @Test
  fun `wire strings match the Signal K path contract exactly`() {
    // These strings cross the network to RX, which parses them with
    // FromSkString. A rename here is a silent break of the firmware contract,
    // so the literals are pinned rather than derived from the enum name.
    assertEquals("forward", DrivePosition.FORWARD.wire)
    assertEquals("neutral", DrivePosition.NEUTRAL.wire)
    assertEquals("reverse", DrivePosition.REVERSE.wire)
  }

  @ParameterizedTest
  @MethodSource("validTelemetry")
  fun `accepts the exact telemetry string`(raw: String, expected: DisplayDrivePosition) {
    assertEquals(expected, parseDisplayPosition(raw))
  }

  @ParameterizedTest
  @MethodSource("garbageTelemetry")
  fun `rejects garbage as unknown rather than a silent guess`(raw: Any?) {
    assertEquals(DisplayDrivePosition.UNKNOWN, parseDisplayPosition(raw))
  }

  companion object {
    @JvmStatic
    fun validTelemetry() =
      listOf(
        org.junit.jupiter.params.provider.Arguments.of("forward", DisplayDrivePosition.FORWARD),
        org.junit.jupiter.params.provider.Arguments.of("neutral", DisplayDrivePosition.NEUTRAL),
        org.junit.jupiter.params.provider.Arguments.of("reverse", DisplayDrivePosition.REVERSE),
      )

    /**
     * Mirrors the vitest list `[undefined, null, '', 'FORWARD', 'fwd', 42, {},
     * []]`. `undefined` has no Kotlin equivalent -- absent and null are the
     * same thing here -- so null covers both, and the rest map directly.
     */
    @JvmStatic
    fun garbageTelemetry(): List<Any?> =
      listOf(null, "", "FORWARD", "fwd", 42, emptyMap<String, Any>(), emptyList<Any>())
  }
}
