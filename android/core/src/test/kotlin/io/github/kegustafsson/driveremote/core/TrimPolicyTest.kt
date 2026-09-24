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
    publish(store, reopenedAt + 60) // HH's next frame after the resubscribe
    val view =
      deriveStationView(store, ConnectionState.OPEN, me, reopenedAt + 100, streamOpenedAtMs = reopenedAt)

    assertEquals(20.0, trimmed.trimGovernedBy(view).trimDeg)
    assertTrue(view.thrusterCommandable, "the steps come back with the link")
  }

  /**
   * The instant the socket reopens, before HH's next frame has arrived, every
   * arrival stamp in the store is from before the drop and HH reads STALE. That
   * is this station not having listened, not HH going away, and it must not
   * cost the trim on the way back from an outage.
   */
  @Test
  fun `the instant of a reconnect, before HH has been heard again, keeps the trim`() {
    val store = holdingStore(nowMs = 10_000)
    val reopenedAt = 30_000L
    val view =
      deriveStationView(store, ConnectionState.OPEN, me, reopenedAt, streamOpenedAtMs = reopenedAt)

    assertEquals(UnitLiveness.STALE, view.hhLiveness, "the stamps predate the reconnect")
    assertFalse(view.hhVerdictSettled)
    assertEquals(20.0, trimmed.trimGovernedBy(view).trimDeg)
    assertFalse(view.thrusterCommandable, "still frozen until HH is heard")
  }

  /** ...but an HH that stays silent after the reconnect is gone, and the hold with it. */
  @Test
  fun `an HH still silent a staleness window after the reconnect resets the trim`() {
    val store = holdingStore(nowMs = 10_000)
    val reopenedAt = 30_000L
    store.apply(listOf(SkContract.RX_LINK_UP to true), reopenedAt + SkContract.TELEMETRY_STALE_MS)
    val view =
      deriveStationView(
        store,
        ConnectionState.OPEN,
        me,
        reopenedAt + SkContract.TELEMETRY_STALE_MS,
        streamOpenedAtMs = reopenedAt,
      )

    assertTrue(view.hhNotAnswering)
    assertEquals(0.0, trimmed.trimGovernedBy(view).trimDeg)
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
  fun `HH not answering on a live link resets the trim`() {
    val store = holdingStore(nowMs = 10_000)
    val later = 10_000 + SkContract.TELEMETRY_STALE_MS + 1
    store.apply(listOf(SkContract.RX_LINK_UP to true), later) // RX still there, HH gone
    val view = deriveStationView(store, ConnectionState.OPEN, me, later)

    assertTrue(view.armed)
    assertEquals(UnitLiveness.STALE, view.hhLiveness)
    assertEquals(0.0, trimmed.trimGovernedBy(view).trimDeg)
  }

  /** The arbiter's verdict counts too: fresh deltas but plugin.hhLive false. */
  @Test
  fun `the arbiter calling HH gone on a live link resets the trim`() {
    val store = holdingStore(nowMs = 10_000)
    store.apply(listOf(SkContract.PLUGIN_HH_LIVE to false), 10_050)
    val view = deriveStationView(store, ConnectionState.OPEN, me, 10_100)
    assertEquals(0.0, trimmed.trimGovernedBy(view).trimDeg)
  }

  @Test
  fun `a healthy running hold keeps its trim`() {
    val view = deriveStationView(holdingStore(nowMs = 10_000), ConnectionState.OPEN, me, 10_100)
    assertEquals(20.0, trimmed.trimGovernedBy(view).trimDeg)
    assertTrue(view.thrusterCommandable)
  }

  /** The rule itself, as a table. */
  @Test
  fun `the rule`() {
    val hold = ThrusterMode.HOLD
    // Not HOLD: always.
    for (connected in listOf(true, false)) for (armed in listOf(true, false)) for (hh in listOf(true, false)) {
      assertTrue(trimMustReset(ThrusterMode.MANUAL, connected, armed, hh))
    }
    // HOLD, disconnected: never, whatever the last-known armed and HH say.
    for (armed in listOf(true, false)) for (hh in listOf(true, false)) {
      assertFalse(trimMustReset(hold, connected = false, armed = armed, hhAnswering = hh))
    }
    // HOLD, connected: kept only while armed with HH answering.
    assertFalse(trimMustReset(hold, connected = true, armed = true, hhAnswering = true))
    assertTrue(trimMustReset(hold, connected = true, armed = false, hhAnswering = true))
    assertTrue(trimMustReset(hold, connected = true, armed = true, hhAnswering = false))
    assertTrue(trimMustReset(hold, connected = true, armed = false, hhAnswering = false))
  }

  @Test
  fun `the verdict settles on HH's first frame after the open, or after a staleness window`() {
    val opened = 5_000L
    // Closed: nothing to settle -- the socket's own state already says so.
    assertTrue(hhVerdictSettledOf(ConnectionState.CLOSED, opened, opened, hhTelemetryAtMs = 0))
    // Not tracked: settled, the pre-existing behaviour.
    assertTrue(hhVerdictSettledOf(ConnectionState.OPEN, opened, null, hhTelemetryAtMs = 0))
    // Open, old stamps, inside the window: not yet.
    assertFalse(hhVerdictSettledOf(ConnectionState.OPEN, opened + 10, opened, hhTelemetryAtMs = 0))
    assertFalse(hhVerdictSettledOf(ConnectionState.OPEN, opened + 10, opened, hhTelemetryAtMs = null))
    // HH heard since the open: settled at once.
    assertTrue(hhVerdictSettledOf(ConnectionState.OPEN, opened + 10, opened, hhTelemetryAtMs = opened + 5))
    // A whole staleness window with nothing: settled, and the verdict is "gone".
    assertTrue(
      hhVerdictSettledOf(
        ConnectionState.OPEN,
        opened + SkContract.TELEMETRY_STALE_MS,
        opened,
        hhTelemetryAtMs = 0,
      )
    )
  }
}
