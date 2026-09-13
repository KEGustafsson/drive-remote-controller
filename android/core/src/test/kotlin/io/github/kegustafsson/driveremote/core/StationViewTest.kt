package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The scenarios the plugin's `App.test.tsx` exercises through a rendered UI,
 * checked here without one. These are the cases that matter operationally: a
 * unit switched off, TX holding precedence, another station armed.
 */
class StationViewTest {

  private val me = "ui-me"

  /** A store with both units publishing healthily at [nowMs]. */
  private fun healthyStore(nowMs: Long = 10_000, activeClient: String? = null): SkValueStore {
    val store = SkValueStore()
    store.apply(
      listOfNotNull(
        SkContract.RX_LINK_UP to true,
        SkContract.RX_LINK_OK to true,
        SkContract.RX_PORT_STATE to "neutral",
        SkContract.RX_STBD_STATE to "neutral",
        SkContract.RX_PORT_SOURCE to "none",
        SkContract.RX_STBD_SOURCE to "none",
        SkContract.HH_LINK_UP to true,
        SkContract.HH_SOURCE to "none",
        SkContract.PLUGIN_RX_LIVE to true,
        SkContract.PLUGIN_HH_LIVE to true,
        activeClient?.let { SkContract.PLUGIN_ACTIVE_CLIENT to it },
      ),
      nowMs,
    )
    return store
  }

  private fun view(store: SkValueStore, nowMs: Long, state: ConnectionState = ConnectionState.OPEN) =
    deriveStationView(store, state, me, nowMs)

  @Test
  fun `a freshly opened station is disarmed and commands nothing`() {
    val v = view(healthyStore(), nowMs = 10_000)
    assertEquals(ControlState.NONE, v.controlState)
    assertFalse(v.armed)
    assertTrue(v.canArm, "both units are answering, so ARM is offered")
    assertFalse(v.driveCommandable, "disarmed: a press must not reach the drives")
    assertFalse(v.thrusterCommandable)
  }

  @Test
  fun `holding the token makes both machines commandable`() {
    val v = view(healthyStore(activeClient = me), nowMs = 10_000)
    assertTrue(v.armed)
    assertTrue(v.driveCommandable)
    assertTrue(v.thrusterCommandable)
    assertEquals(emptyList<String>(), v.missingUnits)
  }

  @Test
  fun `another station holding the token locks this one out`() {
    val v = view(healthyStore(activeClient = "ui-the-other-phone"), nowMs = 10_000)
    assertEquals(ControlState.OTHER, v.controlState)
    assertFalse(v.armed)
    assertFalse(v.driveCommandable)
  }

  @Test
  fun `a switched-off thruster board greys the thruster but not the drives`() {
    // The fifth screenshot in the plugin README, as a unit test. The drives are
    // what you need at the dock; the thruster board is the likelier to be off.
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    // RX keeps publishing; HH stops. Only RX's arrival advances.
    val later = 10_000 + SkContract.TELEMETRY_STALE_MS + 1
    store.apply(listOf(SkContract.RX_LINK_UP to true), later)

    val v = view(store, later, ConnectionState.OPEN)
    assertEquals(UnitLiveness.LIVE, v.rxLiveness)
    assertEquals(UnitLiveness.STALE, v.hhLiveness)
    assertTrue(v.driveCommandable, "the drives must keep working")
    assertFalse(v.thrusterCommandable)
    assertTrue(v.canArm, "one live unit is enough to arm")
    assertEquals(listOf("thruster unit"), v.missingUnits)
  }

  @Test
  fun `arming is withdrawn only once neither unit answers, and names both`() {
    val store = healthyStore(nowMs = 10_000)
    val later = 10_000 + SkContract.TELEMETRY_STALE_MS + 1

    val v = view(store, later, ConnectionState.OPEN)
    assertFalse(v.canArm)
    assertEquals(listOf("drive unit", "thruster unit"), v.missingUnits)
  }

  @Test
  fun `a dead board still reads linkUp true -- only the age reveals it`() {
    // The bug this whole liveness design exists for, end to end.
    val store = healthyStore(nowMs = 10_000)
    val later = 10_000 + SkContract.TELEMETRY_STALE_MS + 1
    val v = view(store, later, ConnectionState.OPEN)

    assertEquals(true, v.rxLinkUp, "the published value never withdraws itself")
    assertEquals(UnitLiveness.STALE, v.rxLiveness, "but the station knows better")
  }

  @Test
  fun `TX holding a drive is named, and only while we could otherwise command`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(listOf(SkContract.RX_PORT_SOURCE to "tx"), 10_000)

    val armedView = view(store, 10_000)
    assertEquals("TX remote", armedView.portOverriddenBy)
    assertNull(armedView.stbdOverriddenBy, "starboard is not overridden")

    // Disarmed, the note would be noise -- the disarmed state already explains
    // why a press does nothing.
    val disarmed = healthyStore(nowMs = 10_000)
    disarmed.apply(listOf(SkContract.RX_PORT_SOURCE to "tx"), 10_000)
    assertNull(view(disarmed, 10_000).portOverriddenBy)
  }

  @Test
  fun `a local switch also outranks this app`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(listOf(SkContract.RX_STBD_SOURCE to "local"), 10_000)
    assertEquals("local switch", view(store, 10_000).stbdOverriddenBy)
  }

  @Test
  fun `this app commanding is not reported as an override`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(listOf(SkContract.RX_PORT_SOURCE to "plugin"), 10_000)
    assertNull(view(store, 10_000).portOverriddenBy)
  }

  @Test
  fun `an offline socket reports offline units and commands nothing`() {
    // Not 'stale': the units are not to blame for our own connection. And the
    // last-known values stay readable so the UI can grey them rather than blank.
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    val v = view(store, 10_000, ConnectionState.CLOSED)

    assertEquals(UnitLiveness.OFFLINE, v.rxLiveness)
    assertEquals(UnitLiveness.OFFLINE, v.hhLiveness)
    assertFalse(v.canArm)
    assertFalse(v.driveCommandable)
    assertEquals(true, v.rxLinkUp, "last-known value is still there to grey out")
    // Still 'YOU' -- the intent heartbeat runs over HTTP independently of this
    // socket, so we may genuinely still hold the token. The UI must show a
    // distinct OFFLINE state rather than a false DISARMED.
    assertEquals(ControlState.YOU, v.controlState)
  }

  @Test
  fun `an implausible held heading reads as no reading, not a number`() {
    val store = healthyStore(nowMs = 10_000)
    store.apply(listOf(SkContract.HH_SETPOINT to "not-a-heading"), 10_000)
    assertNull(view(store, 10_000).heldDeg)

    store.apply(listOf(SkContract.HH_SETPOINT to 41), 10_000)
    assertEquals(41.0, view(store, 10_000).heldDeg)
  }

  /**
   * "Holding" is HH's word, not this station's.
   *
   * The number cannot be used to tell: HH mirrors `hh.setpointDeg` to the fused
   * heading in every state except holding (ARCHITECTURE.md §9), so it is a live
   * plausible heading whether the unit engaged, refused on an untrustworthy
   * heading, faulted, was taken by its own ENGAGE input, or is refusing a
   * station whose disarm it has not seen yet.
   */
  @Test
  fun `hold is only engaged when HH itself says armed and in hold`() {
    val nowMs = 10_000L

    // Asking for it is not being in it: armed here is OUR arm token, and HH has
    // not reported its own state at all yet.
    val requested = healthyStore(nowMs = nowMs, activeClient = me)
    assertFalse(view(requested, nowMs).holdEngaged, "HH has said nothing")

    // HH in hold but not armed -- refused, faulted, or not yet engaged.
    requested.apply(
      listOf(SkContract.HH_ARMED to false, SkContract.HH_MODE to "hold"),
      nowMs,
    )
    assertFalse(view(requested, nowMs).holdEngaged, "hh.armed false is not holding")

    // Armed but still in manual: its own ENGAGE input, or a mode this station
    // did not ask for.
    requested.apply(
      listOf(SkContract.HH_ARMED to true, SkContract.HH_MODE to "manual"),
      nowMs,
    )
    assertFalse(view(requested, nowMs).holdEngaged, "manual is not holding")

    // Both, and the unit answering: the only case that may say HOLDING.
    requested.apply(
      listOf(SkContract.HH_ARMED to true, SkContract.HH_MODE to "hold"),
      nowMs,
    )
    assertTrue(view(requested, nowMs).holdEngaged)
  }

  @Test
  fun `a switched-off HH cannot report a hold it is no longer running`() {
    // Both halves of the pair are VALUES, and Signal K retains those forever, so
    // without the liveness test a board that has been off for an hour still
    // claims to be holding.
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(listOf(SkContract.HH_ARMED to true, SkContract.HH_MODE to "hold"), 10_000)
    assertTrue(view(store, 10_000).holdEngaged)

    val later = 10_000 + SkContract.TELEMETRY_STALE_MS + 1
    store.apply(listOf(SkContract.RX_LINK_UP to true), later)
    val v = view(store, later)
    assertEquals(UnitLiveness.STALE, v.hhLiveness)
    assertEquals(true, v.hhArmed, "the retained value still says armed")
    assertFalse(v.holdEngaged, "but nothing is holding on a board that stopped publishing")
  }

  @Test
  fun `reversal interlock is surfaced so a dead-looking thruster is explained`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    assertFalse(view(store, 10_000).reversalPending)

    store.apply(listOf(SkContract.HH_REVERSAL_PENDING to true), 10_000)
    assertTrue(view(store, 10_000).reversalPending)
  }
}
