package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Telling a start-up apart from a boat that has gone away.
 *
 * These are the same fact — the stream is not open — and the operator must be
 * told two different things about them. The defect that produced this rule was
 * the benign half being drawn as the alarming one: every launch opened on the
 * amber OFFLINE kill switch for the few hundred milliseconds the WebSocket took
 * to come up, then flipped to DISARMED. A warning that fires at every single
 * start is one the operator learns to look past, which costs exactly the times
 * it means something — and it was on the STOP button.
 *
 * The asymmetry below is the whole point and is deliberately lopsided: the
 * grace applies **only** before a session's first open, and never again. A
 * connection that drops after having worked is the case the OFFLINE state was
 * written for — the station may still hold the token and still be commanding
 * while unable to see the boat — and it gets no grace at all.
 */
class LinkPhaseTest {

  @Test
  fun `an open stream is online`() {
    assertEquals(
      LinkPhase.ONLINE,
      linkPhaseOf(ConnectionState.OPEN, everConnected = false, connectingForMs = 0),
    )
  }

  @Test
  fun `an open stream is online even long into a session`() {
    assertEquals(
      LinkPhase.ONLINE,
      linkPhaseOf(ConnectionState.OPEN, everConnected = true, connectingForMs = 10 * 60_000),
    )
  }

  // ---- Starting up -------------------------------------------------------

  @Test
  fun `a socket that has never opened yet is starting up, not offline`() {
    assertEquals(
      LinkPhase.CONNECTING,
      linkPhaseOf(ConnectionState.CONNECTING, everConnected = false, connectingForMs = 0),
    )
  }

  /**
   * A first attempt that fails is retried, and between attempts the state is
   * CLOSED rather than CONNECTING. That gap is still the same start-up and must
   * not flash the alarm on its way past — which it would if this keyed on
   * CONNECTING rather than on "never yet open".
   */
  @Test
  fun `the gap between two failed first attempts is still starting up`() {
    assertEquals(
      LinkPhase.CONNECTING,
      linkPhaseOf(ConnectionState.CLOSED, everConnected = false, connectingForMs = 500),
    )
  }

  // ---- ...but not forever ------------------------------------------------

  /**
   * A server that is off never opens, so "connecting…" would otherwise be the
   * permanent reading for an unreachable boat. The grace is a window, not a
   * mode.
   */
  @Test
  fun `a first connection that never lands becomes offline`() {
    assertEquals(
      LinkPhase.OFFLINE,
      linkPhaseOf(
        ConnectionState.CONNECTING,
        everConnected = false,
        connectingForMs = SkContract.LINK_STARTUP_GRACE_MS,
      ),
    )
  }

  @Test
  fun `the last millisecond of the grace window is still starting up`() {
    assertEquals(
      LinkPhase.CONNECTING,
      linkPhaseOf(
        ConnectionState.CONNECTING,
        everConnected = false,
        connectingForMs = SkContract.LINK_STARTUP_GRACE_MS - 1,
      ),
    )
  }

  // ---- A drop gets no grace whatsoever -----------------------------------

  /**
   * The assertion that matters most here. Once a session has been open the
   * station may hold the token and may still be commanding, so the instant the
   * stream goes there is nothing gentle to say about it.
   */
  @Test
  fun `a drop after a session has been open is offline immediately`() {
    assertEquals(
      LinkPhase.OFFLINE,
      linkPhaseOf(ConnectionState.CONNECTING, everConnected = true, connectingForMs = 0),
    )
    assertEquals(
      LinkPhase.OFFLINE,
      linkPhaseOf(ConnectionState.CLOSED, everConnected = true, connectingForMs = 0),
    )
  }

  // ---- ...and the same, through deriveStationView ------------------------

  @Test
  fun `deriveStationView carries the phase, and still reports the raw state`() {
    val view =
      deriveStationView(
        store = SkValueStore(),
        connectionState = ConnectionState.CONNECTING,
        myClientId = "me",
        nowMs = 1_000,
        everConnected = false,
        linkAttemptStartedMs = 900,
      )

    assertEquals(LinkPhase.CONNECTING, view.linkPhase)
    // The presentation softens; the fact does not. Anything gating a COMMAND
    // reads connectionState, and it still says the stream is not open.
    assertEquals(ConnectionState.CONNECTING, view.connectionState)
    assertEquals(false, view.connected)
    assertEquals(false, view.driveCommandable)
    assertEquals(false, view.thrusterCommandable)
    assertEquals(false, view.canArm)
  }

  /**
   * A caller that does not track the two new inputs gets the cautious answer,
   * not a grace period it never asked for.
   */
  @Test
  fun `the defaults keep the pre-LinkPhase behaviour`() {
    val view =
      deriveStationView(
        store = SkValueStore(),
        connectionState = ConnectionState.CONNECTING,
        myClientId = "me",
        nowMs = 1_000,
      )
    assertEquals(LinkPhase.OFFLINE, view.linkPhase)
  }
}
