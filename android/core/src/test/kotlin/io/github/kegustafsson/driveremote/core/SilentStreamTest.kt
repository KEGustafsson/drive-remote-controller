package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A socket that stays OPEN but has stopped delivering is not a live link.
 *
 * The Signal K host losing power, or the Wi-Fi path breaking without a FIN,
 * leaves the socket reading open until OkHttp's ping fails -- and the store keeps
 * the retained `activeClient` naming this station the whole time. Judged on the
 * socket, the screen went on saying ARMED, connected, control yours, with
 * nothing arriving to back any of it (AGENTS.md non-negotiable 6). The browser
 * station judges the stream on ARRIVAL of the arbiter's 250 ms republish
 * (`sk-plugin/src/pure/serverStream.ts`); these are the same cases through
 * [deriveStationView].
 */
class SilentStreamTest {

  private val me = "ui-me"
  private val openedAt = 5_000L
  private val lastHeard = 10_000L

  /** Both units and the arbiter publishing, this station holding the token, all at [atMs]. */
  private fun publish(store: SkValueStore, atMs: Long, activeClient: String = me) {
    store.apply(
      listOf(
        SkContract.RX_LINK_UP to true,
        SkContract.HH_LINK_UP to true,
        SkContract.RX_PORT_SOURCE to "none",
        SkContract.RX_STBD_SOURCE to "none",
        SkContract.HH_SOURCE to "none",
        SkContract.PLUGIN_RX_LIVE to true,
        SkContract.PLUGIN_HH_LIVE to true,
        SkContract.PLUGIN_ACTIVE_CLIENT to activeClient,
      ),
      atMs,
    )
  }

  private fun storeHeardAt(atMs: Long) = SkValueStore().also { publish(it, atMs) }

  private fun view(store: SkValueStore, nowMs: Long, opened: Long? = openedAt) =
    deriveStationView(
      store = store,
      connectionState = ConnectionState.OPEN,
      myClientId = me,
      nowMs = nowMs,
      everConnected = true,
      linkAttemptStartedMs = 0,
      streamOpenedAtMs = opened,
    )

  @Test
  fun `an open socket still delivering is connected, up to the last millisecond of the window`() {
    val store = storeHeardAt(lastHeard)
    val v = view(store, lastHeard + SkContract.SERVER_STREAM_STALE_MS)
    assertTrue(v.connected)
    assertEquals(LinkPhase.ONLINE, v.linkPhase)
    assertTrue(v.driveCommandable)
  }

  @Test
  fun `a silent open socket past the window is not connected, and reads OFFLINE`() {
    val v = view(storeHeardAt(lastHeard), lastHeard + SkContract.SERVER_STREAM_STALE_MS + 1)

    assertEquals(ConnectionState.OPEN, v.connectionState, "the socket still says open")
    assertFalse(v.connected, "an open socket with nothing arriving was read as a live link")
    assertEquals(LinkPhase.OFFLINE, v.linkPhase, "the kill switch must read OFFLINE, tap = STOP")
    // The retained activeClient still names this station -- which is exactly
    // why the kill switch must not present it as a confident ARMED.
    assertTrue(v.armed)
    assertFalse(v.driveCommandable)
    assertFalse(v.thrusterCommandable)
    assertFalse(v.canArm)
    // "Cannot tell", not "the units have gone": the silence is the server's.
    assertEquals(UnitLiveness.OFFLINE, v.rxLiveness)
    assertEquals(UnitLiveness.OFFLINE, v.hhLiveness)
    assertFalse(v.holdEngaged)
    // Leaving must be possible, and carry a STOP -- as for a closed socket.
    assertFalse(v.changeServerRefused)
    assertTrue(v.changeServerSendsStop)
  }

  /**
   * The Signal K server alive but the plugin stopped: RX and HH keep arriving
   * straight from the boat, and the arbiter -- which owns the arm token -- does
   * not. The retained activeClient is then nobody's live word.
   */
  @Test
  fun `units still arriving do not make the stream live without the arbiter's publish`() {
    val store = storeHeardAt(lastHeard)
    val later = lastHeard + SkContract.SERVER_STREAM_STALE_MS + 1
    store.apply(listOf(SkContract.RX_LINK_UP to true, SkContract.HH_LINK_UP to true), later)

    val v = view(store, later)
    assertFalse(v.connected)
    assertFalse(v.driveCommandable, "a press here would reach an arbiter nobody can hear")
  }

  @Test
  fun `an activeClient arrival from before the current socket opened does not count`() {
    val store = storeHeardAt(lastHeard)
    // Dropped and reopened within the window: the retained activeClient was
    // heard on the OLD socket and has not been re-sent on this one.
    val reopened = lastHeard + 50
    val v = view(store, reopened + 50, opened = reopened)
    assertFalse(v.connected, "a pre-reconnect arrival was read as proof of the new socket")
    assertFalse(v.driveCommandable)

    // The first arrival on the new socket is.
    publish(store, reopened + 60)
    assertTrue(view(store, reopened + 100, opened = reopened).connected)
  }

  /**
   * The first moments of a session: the socket has opened and the arbiter's
   * first publish is still on its way. That is a start-up, and it must not put
   * the amber OFFLINE panel on the STOP button at every launch.
   */
  @Test
  fun `open but not yet heard from, inside the start-up grace, is connecting`() {
    val v =
      deriveStationView(
        store = SkValueStore(),
        connectionState = ConnectionState.OPEN,
        myClientId = me,
        nowMs = 1_000,
        everConnected = true,
        linkAttemptStartedMs = 700,
        streamOpenedAtMs = 950,
      )
    assertFalse(v.connected)
    assertEquals(LinkPhase.CONNECTING, v.linkPhase)
  }

  /** ...and a server whose arbiter never speaks becomes a fault once the grace runs out. */
  @Test
  fun `open and never heard from, past the grace, is offline`() {
    val v =
      deriveStationView(
        store = SkValueStore(),
        connectionState = ConnectionState.OPEN,
        myClientId = me,
        nowMs = 10_000,
        everConnected = true,
        linkAttemptStartedMs = 10_000 - SkContract.LINK_STARTUP_GRACE_MS,
        streamOpenedAtMs = 9_000,
      )
    assertEquals(LinkPhase.OFFLINE, v.linkPhase)
  }
}
