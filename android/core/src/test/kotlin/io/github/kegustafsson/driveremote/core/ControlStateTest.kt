package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Armed state is derived from the arbiter's published `activeClient`, never
 * guessed locally. These cases pin that, plus the per-machine commandability
 * and the one-arm-covers-both rule -- the same properties `App.test.tsx`
 * exercises through a rendered UI, checked here without one.
 */
class ControlStateTest {

  private val me = "ui-me"

  @Test
  fun `holding the token reads as YOU`() {
    assertEquals(ControlState.YOU, controlStateOf(me, me))
  }

  @Test
  fun `another holder reads as OTHER`() {
    assertEquals(ControlState.OTHER, controlStateOf("ui-someone-else", me))
  }

  @Test
  fun `an empty holder means nobody -- that is how the arbiter says released`() {
    assertEquals(ControlState.NONE, controlStateOf("", me))
  }

  @Test
  fun `a missing or malformed holder degrades to NONE, never to a claim of control`() {
    for (raw in listOf(null, 42, true, emptyList<String>())) {
      assertEquals(ControlState.NONE, controlStateOf(raw, me), "activeClient=$raw")
    }
  }

  @Test
  fun `commanding needs the token, the socket and that unit -- all three`() {
    assertTrue(canCommand(ControlState.YOU, ConnectionState.OPEN, UnitLiveness.LIVE))

    assertFalse(canCommand(ControlState.NONE, ConnectionState.OPEN, UnitLiveness.LIVE))
    assertFalse(canCommand(ControlState.OTHER, ConnectionState.OPEN, UnitLiveness.LIVE))
    assertFalse(canCommand(ControlState.YOU, ConnectionState.CLOSED, UnitLiveness.LIVE))
    assertFalse(canCommand(ControlState.YOU, ConnectionState.CONNECTING, UnitLiveness.LIVE))
    for (l in listOf(UnitLiveness.STALE, UnitLiveness.NEVER_SEEN, UnitLiveness.OFFLINE)) {
      assertFalse(canCommand(ControlState.YOU, ConnectionState.OPEN, l), "liveness $l")
    }
  }

  @Test
  fun `the two machines are judged independently`() {
    // A dead thruster board must not grey out the gears, and vice versa: RX
    // drives the gears, HH drives the thruster, and they fail independently.
    val driveOk = canCommand(ControlState.YOU, ConnectionState.OPEN, UnitLiveness.LIVE)
    val thrusterOk = canCommand(ControlState.YOU, ConnectionState.OPEN, UnitLiveness.STALE)
    assertTrue(driveOk)
    assertFalse(thrusterOk)
  }

  @Test
  fun `one arm covers both machines and either live unit permits it`() {
    // Refusing to arm the drives because the thruster board is switched off
    // would take away the primary docking control at the worst moment.
    assertTrue(canArm(UnitLiveness.LIVE, UnitLiveness.STALE))
    assertTrue(canArm(UnitLiveness.STALE, UnitLiveness.LIVE))
    assertTrue(canArm(UnitLiveness.LIVE, UnitLiveness.LIVE))
  }

  @Test
  fun `arming is withdrawn only when neither unit is answering`() {
    for (rx in listOf(UnitLiveness.STALE, UnitLiveness.NEVER_SEEN, UnitLiveness.OFFLINE)) {
      for (hh in listOf(UnitLiveness.STALE, UnitLiveness.NEVER_SEEN, UnitLiveness.OFFLINE)) {
        assertFalse(canArm(rx, hh), "rx=$rx hh=$hh")
      }
    }
  }
}
