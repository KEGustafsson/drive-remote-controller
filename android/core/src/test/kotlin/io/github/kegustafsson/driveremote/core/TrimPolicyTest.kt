package io.github.kegustafsson.driveremote.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Owner decision, 2026-09-24: the heading trim is KEPT when the live-data
 * stream drops, and reset only when the hold ends.
 *
 * The trim is relative to the heading HH captured, so resetting it to 0 swings
 * the boat back by the whole offset with nobody touching anything. The old rule
 * did exactly that whenever the read socket dropped -- including when only the
 * WebSocket had gone and the intents were still steering the hold over HTTP.
 */
class TrimPolicyTest {

  private val me = "ui-me"

  /** Armed, HOLD running, trim +20, both units publishing at [nowMs]. */
  private fun holdingStore(nowMs: Long): SkValueStore {
    val store = SkValueStore()
    publish(store, nowMs)
    store.apply(listOf(SkContract.PLUGIN_ACTIVE_CLIENT to me), nowMs)
    return store
  }

  /** One round of the units' telemetry, and the arbiter's verdicts, at [nowMs]. */
  private fun publish(store: SkValueStore, nowMs: Long) {
    store.apply(
      listOf(
        SkContract.RX_LINK_UP to true,
        SkContract.HH_LINK_UP to true,
        SkContract.HH_ARMED to true,
        SkContract.HH_MODE to "hold",
        SkContract.HH_FSM_STATE to SkContract.HH_FSM_HOLDING,
        SkContract.PLUGIN_RX_LIVE to true,
        SkContract.PLUGIN_HH_LIVE to true,
      ),
      nowMs,
    )
  }

  private val trimmed = ThrusterCommand(mode = ThrusterMode.HOLD, trimDeg = 20.0)

  /** What the heartbeat would send for [command] -- the trim actually on the wire. */
  private fun wireTrim(command: ThrusterCommand): Double {
    val intent =
      ClientIntent(
        clientId = me,
        session = 1,
        seq = 1,
        armReq = 1,
        disarmReq = 0,
        port = DrivePosition.NEUTRAL,
        stbd = DrivePosition.NEUTRAL,
        thruster = command.direction,
        thrusterMode = command.mode,
        trimDeg = command.trimDeg,
      )
    return Json.parseToJsonElement(intent.toJson()).jsonObject["trimDeg"]!!.jsonPrimitive.double
  }

  @Test
  fun `the socket dropping keeps the trim, keeps sending it, and freezes the steps`() {
    val store = holdingStore(nowMs = 10_000)
    // Long enough that every liveness verdict has gone stale -- which, with the
    // socket down, it cannot help doing.
    val later = 10_000 + 5 * SkContract.TELEMETRY_STALE_MS
    val offline = deriveStationView(store, ConnectionState.CLOSED, me, later)

    val next = trimmed.trimGovernedBy(offline)
    assertEquals(20.0, next.trimDeg, "a dropped read socket swung the boat by the trim")
    assertEquals(20.0, wireTrim(next), "the kept trim must keep being SENT")
    assertFalse(offline.thrusterCommandable, "the trim steps must be inert offline")
  }

  /** And the same while the socket is still trying to come back. */
  @Test
  fun `a socket still reconnecting keeps the trim`() {
    val store = holdingStore(nowMs = 10_000)
    val view = deriveStationView(store, ConnectionState.CONNECTING, me, 20_000)
    assertEquals(20.0, trimmed.trimGovernedBy(view).trimDeg)
  }

  @Test
  fun `reconnecting still armed with HH answering keeps the trim and re-enables the steps`() {
    val store = holdingStore(nowMs = 10_000)
    val reopenedAt = 30_000L
    // The arbiter's first publish, and HH's next frame, on the new socket.
    publish(store, reopenedAt + 60)
    store.apply(listOf(SkContract.PLUGIN_ACTIVE_CLIENT to me), reopenedAt + 60)
    val view =
      deriveStationView(store, ConnectionState.OPEN, me, reopenedAt + 100, streamOpenedAtMs = reopenedAt)

    assertEquals(20.0, trimmed.trimGovernedBy(view).trimDeg)
    assertTrue(view.thrusterCommandable, "the steps come back with the link")
  }

  // ---- Both edges of an outage, as the ViewModel sees them ---------------
  //
  // StationViewModel.refreshView derives a view every TELEMETRY_POLL_MS and
  // hands each one's hhGoneSinceMs to the next. These run that loop.

  /** Drives the derivation the way the ViewModel does, carrying hhGoneSinceMs. */
  private inner class Station(val store: SkValueStore, var command: ThrusterCommand = trimmed) {
    var state = ConnectionState.OPEN
    var openedAtMs = 0L
    private var hhGoneSinceMs: Long? = null
    lateinit var view: StationView

    fun tick(nowMs: Long): Double {
      view =
        deriveStationView(
          store,
          state,
          me,
          nowMs,
          streamOpenedAtMs = openedAtMs,
          hhGoneSinceMs = hhGoneSinceMs,
        )
      hhGoneSinceMs = view.hhGoneSinceMs
      command = command.trimGovernedBy(view)
      return command.trimDeg
    }
  }

  private fun hhFrame(store: SkValueStore, atMs: Long) =
    store.apply(
      listOf(
        SkContract.HH_LINK_UP to true,
        SkContract.HH_ARMED to true,
        SkContract.HH_MODE to "hold",
        SkContract.HH_FSM_STATE to SkContract.HH_FSM_HOLDING,
      ),
      atMs,
    )

  private fun arbiterFrame(store: SkValueStore, atMs: Long) =
    store.apply(
      listOf(
        SkContract.RX_LINK_UP to true,
        SkContract.PLUGIN_RX_LIVE to true,
        SkContract.PLUGIN_HH_LIVE to true,
        SkContract.PLUGIN_ACTIVE_CLIENT to me,
      ),
      atMs,
    )

  private val poll = SkContract.TELEMETRY_POLL_MS

  /**
   * THE case. The socket stays OPEN and everything stops arriving -- the Signal
   * K host lost power. HH's last frame came a little before the arbiter's last
   * publish, so HH's window runs out first and for a tick the stream still
   * reads connected with HH "gone". Judged on the socket, or on HH at face
   * value, that reset the trim and swung the boat.
   */
  @Test
  fun `the trim is kept across a stream that goes silent on an open socket`() {
    val station = Station(SkValueStore())
    var t = 0L
    while (t <= 10_000) {
      hhFrame(station.store, t - 100) // HH a little ahead of the arbiter
      arbiterFrame(station.store, t)
      assertEquals(20.0, station.tick(t))
      t += poll
    }
    // Silence. The socket is not told; it reads OPEN throughout.
    var sawHhGoneWhileConnected = false
    while (t <= 20_000) {
      assertEquals(20.0, station.tick(t), "a silent stream reset the trim at t=$t")
      if (station.view.hhGone) sawHhGoneWhileConnected = true
      t += poll
    }
    // The race this guards is real in the scenario, not assumed away.
    assertTrue(sawHhGoneWhileConnected, "the going-dark edge was never exercised")
    assertFalse(station.view.connected)
    assertEquals(LinkPhase.OFFLINE, station.view.linkPhase)
    assertEquals(20.0, wireTrim(station.command), "the kept trim must keep being SENT")
  }

  /**
   * ...and back. The silent socket is abandoned and a new one opened; the
   * stream reads connected on the arbiter's first publish, while HH's newest
   * arrival is as old as the outage until its own first frame follows.
   */
  @Test
  fun `the trim is kept across the stream's return`() {
    val station = Station(SkValueStore())
    var t = 0L
    while (t <= 10_000) {
      hhFrame(station.store, t)
      arbiterFrame(station.store, t)
      station.tick(t)
      t += poll
    }
    // Silent, then abandoned: closed and reconnecting for a while.
    while (t <= 15_000) {
      station.tick(t)
      t += poll
    }
    station.state = ConnectionState.CLOSED
    while (t <= 16_000) {
      assertEquals(20.0, station.tick(t))
      t += poll
    }
    // Reopened. The arbiter's publish lands at once; HH's first frame only
    // after most of a window -- the slowest a live HH can be and still count.
    station.state = ConnectionState.OPEN
    station.openedAtMs = t
    val hhBackAt = t + SkContract.TELEMETRY_STALE_MS - poll
    var sawHhGoneWhileConnected = false
    while (t <= 22_000) {
      arbiterFrame(station.store, t + 10)
      if (t >= hhBackAt) hhFrame(station.store, t + 10)
      assertEquals(20.0, station.tick(t + 20), "the reconnect reset the trim at t=$t")
      if (station.view.hhGone) sawHhGoneWhileConnected = true
      t += poll
    }
    assertTrue(sawHhGoneWhileConnected, "the coming-back edge was never exercised")
    assertTrue(station.view.connected)
    assertTrue(station.view.thrusterCommandable, "the steps come back with the link")
  }

  /** HH going silent with the stream still live is the hold ending -- one full window on. */
  @Test
  fun `HH silent a full window on a live stream resets the trim`() {
    val station = Station(SkValueStore())
    var t = 0L
    var hhLastAt = 0L
    var resetAt: Long? = null
    while (t <= 20_000) {
      if (t <= 10_000) {
        hhFrame(station.store, t)
        hhLastAt = t
      }
      arbiterFrame(station.store, t) // the arbiter keeps publishing: stream live
      val trim = station.tick(t)
      if (trim == 0.0 && resetAt == null) resetAt = t
      t += poll
    }
    // HH reads not-live from the first tick past its window, and the count
    // starts there; the reset comes one full window after that, not before.
    val firstGoneTick = (hhLastAt + SkContract.TELEMETRY_STALE_MS) / poll * poll + poll
    assertEquals(firstGoneTick + SkContract.TELEMETRY_STALE_MS, resetAt)
    assertTrue(station.view.hhGone)
  }

  /** The count is CONTINUOUS: the stream dropping in the middle of it starts it again. */
  @Test
  fun `the stream dropping restarts the HH count`() {
    val station = Station(SkValueStore())
    var t = 0L
    while (t <= 10_000) {
      hhFrame(station.store, t)
      arbiterFrame(station.store, t)
      station.tick(t)
      t += poll
    }
    // HH stops; the arbiter carries on. HH has been counted gone for a while...
    while (t <= 12_500) {
      arbiterFrame(station.store, t)
      station.tick(t)
      t += poll
    }
    assertTrue(station.view.hhGoneForMs > 0)
    // ...then the socket drops for a second, and comes back with HH still gone.
    station.state = ConnectionState.CLOSED
    while (t <= 13_500) {
      assertEquals(20.0, station.tick(t))
      assertEquals(0L, station.view.hhGoneForMs)
      t += poll
    }
    station.state = ConnectionState.OPEN
    station.openedAtMs = t
    val backAt = t
    while (t < backAt + SkContract.TELEMETRY_STALE_MS) {
      arbiterFrame(station.store, t)
      assertEquals(20.0, station.tick(t), "the count ran on through the drop, t=$t")
      t += poll
    }
    arbiterFrame(station.store, t)
    assertEquals(0.0, station.tick(t), "HH silent a full window after the return")
  }

  @Test
  fun `disarming on a live link resets the trim`() {
    val store = holdingStore(nowMs = 10_000)
    store.apply(listOf(SkContract.PLUGIN_ACTIVE_CLIENT to ""), 10_050)
    val view = deriveStationView(store, ConnectionState.OPEN, me, 10_100)

    assertFalse(view.armed)
    assertEquals(0.0, trimmed.trimGovernedBy(view).trimDeg)
  }

  /** Another station taking the token ends this station's hold the same way. */
  @Test
  fun `losing the token to another station on a live link resets the trim`() {
    val store = holdingStore(nowMs = 10_000)
    store.apply(listOf(SkContract.PLUGIN_ACTIVE_CLIENT to "ui-other"), 10_050)
    val view = deriveStationView(store, ConnectionState.OPEN, me, 10_100)
    assertEquals(0.0, trimmed.trimGovernedBy(view).trimDeg)
  }

  @Test
  fun `MANUAL resets the trim, connected or not`() {
    val store = holdingStore(nowMs = 10_000)
    val manual = trimmed.copy(mode = ThrusterMode.MANUAL)
    for (state in ConnectionState.values()) {
      val view = deriveStationView(store, state, me, 10_100)
      assertEquals(0.0, manual.trimGovernedBy(view).trimDeg, "with the socket $state")
    }
  }

  @Test
  fun `HH not answering on a live link resets the trim, once it has held a full window`() {
    val store = holdingStore(nowMs = 10_000)
    val later = 10_000 + SkContract.TELEMETRY_STALE_MS + 1
    // RX and the arbiter still there, HH gone.
    store.apply(listOf(SkContract.RX_LINK_UP to true, SkContract.PLUGIN_ACTIVE_CLIENT to me), later)

    val first = deriveStationView(store, ConnectionState.OPEN, me, later)
    assertTrue(first.armed)
    assertTrue(first.connected)
    assertEquals(UnitLiveness.STALE, first.hhLiveness)
    assertEquals(later, first.hhGoneSinceMs, "the count starts on the first view that sees it")
    assertEquals(20.0, trimmed.trimGovernedBy(first).trimDeg, "not on the first moment")

    val held =
      deriveStationView(
        store,
        ConnectionState.OPEN,
        me,
        later,
        hhGoneSinceMs = later - SkContract.TELEMETRY_STALE_MS,
      )
    assertEquals(SkContract.TELEMETRY_STALE_MS, held.hhGoneForMs)
    assertEquals(0.0, trimmed.trimGovernedBy(held).trimDeg)
  }

  /** The arbiter's verdict counts too: fresh deltas but plugin.hhLive false. */
  @Test
  fun `the arbiter calling HH gone on a live link resets the trim`() {
    val store = holdingStore(nowMs = 10_000)
    store.apply(listOf(SkContract.PLUGIN_HH_LIVE to false), 10_050)
    val view =
      deriveStationView(
        store,
        ConnectionState.OPEN,
        me,
        10_100,
        hhGoneSinceMs = 10_100 - SkContract.TELEMETRY_STALE_MS,
      )
    assertEquals(0.0, trimmed.trimGovernedBy(view).trimDeg)
  }

  @Test
  fun `a healthy running hold keeps its trim`() {
    val view = deriveStationView(holdingStore(nowMs = 10_000), ConnectionState.OPEN, me, 10_100)
    assertEquals(20.0, trimmed.trimGovernedBy(view).trimDeg)
    assertTrue(view.thrusterCommandable)
    assertEquals(null, view.hhGoneSinceMs)
    assertEquals(0L, view.hhGoneForMs)
  }

  /**
   * The rule itself, as a table -- the same cases, in the same order, as
   * `sk-plugin/src/pure/trimReset.test.ts`, so the two stations can be checked
   * against each other line by line.
   */
  @Test
  fun `the rule`() {
    val hold = ThrusterMode.HOLD
    val window = SkContract.TELEMETRY_STALE_MS
    fun reset(
      mode: ThrusterMode = hold,
      connected: Boolean = true,
      armed: Boolean = true,
      hhLive: Boolean = true,
      hhGoneForMs: Long = 0,
    ) = trimMustReset(mode, connected, armed, hhLive, hhGoneForMs)

    // Kept while armed, connected, in HOLD, with HH answering.
    assertFalse(reset())
    // Not HOLD: always, whatever the stream is doing.
    for (connected in listOf(true, false)) for (armed in listOf(true, false)) for (hh in listOf(true, false)) {
      assertTrue(reset(mode = ThrusterMode.MANUAL, connected = connected, armed = armed, hhLive = hh))
    }
    // Disarmed on a live stream: at once -- no window for the token.
    assertTrue(reset(armed = false))
    assertTrue(reset(armed = false, hhGoneForMs = 0))
    // HH silent a full window on a live stream.
    assertTrue(reset(hhLive = false, hhGoneForMs = window))
    // Disconnected: never, whatever the last-known armed and HH say, and even
    // with a (stale) long HH-gone count handed in -- blind is blind.
    for (armed in listOf(true, false)) for (hh in listOf(true, false)) {
      assertFalse(reset(connected = false, armed = armed, hhLive = hh))
    }
    assertFalse(reset(connected = false, hhLive = false, hhGoneForMs = window * 10))
    // HH not live only counts once the verdict has held a full window.
    assertFalse(reset(hhLive = false, hhGoneForMs = 0))
    assertFalse(reset(hhLive = false, hhGoneForMs = window - 1))
    assertTrue(reset(hhLive = false, hhGoneForMs = window))
  }

  @Test
  fun `the HH count is continuous and restarts on any break`() {
    assertEquals(null, continuousSince(holds = false, sinceMs = null, nowMs = 5_000))
    assertEquals(5_000L, continuousSince(holds = true, sinceMs = null, nowMs = 5_000))
    assertEquals(4_000L, continuousSince(holds = true, sinceMs = 4_000, nowMs = 5_000))
    assertEquals(null, continuousSince(holds = false, sinceMs = 4_000, nowMs = 5_000))
  }
}
