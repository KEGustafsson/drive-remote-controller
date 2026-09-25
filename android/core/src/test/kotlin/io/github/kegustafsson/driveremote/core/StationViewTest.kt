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
      listOf(
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
        // The arbiter republishes activeClient every 250 ms whether or not
        // anyone holds the token ('' when nobody does), and its arrival is what
        // makes the stream live (serverStreamLive).
        SkContract.PLUGIN_ACTIVE_CLIENT to (activeClient ?: ""),
      ),
      nowMs,
    )
    return store
  }

  /**
   * The arbiter's 250 ms republish landing at [atMs] -- what keeps the stream
   * live (serverStreamLive) while a unit's own telemetry is what goes quiet.
   */
  private fun arbiterPublishes(store: SkValueStore, atMs: Long, activeClient: String = "") =
    store.apply(listOf(SkContract.PLUGIN_ACTIVE_CLIENT to activeClient), atMs)

  private fun view(store: SkValueStore, nowMs: Long, state: ConnectionState = ConnectionState.OPEN) =
    deriveStationView(store, state, me, nowMs)

  /** The same view, for a station that has been asking for a hold since [askedAtMs]. */
  private fun holdView(store: SkValueStore, askedAtMs: Long, nowMs: Long) =
    deriveStationView(store, ConnectionState.OPEN, me, nowMs, holdRequestedSinceMs = askedAtMs)

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
    // RX keeps publishing; HH stops. Only RX's arrival (and the arbiter's) advances.
    val later = 10_000 + SkContract.TELEMETRY_STALE_MS + 1
    store.apply(listOf(SkContract.RX_LINK_UP to true), later)
    arbiterPublishes(store, later, activeClient = me)

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
    arbiterPublishes(store, later)

    val v = view(store, later, ConnectionState.OPEN)
    assertEquals(UnitLiveness.STALE, v.rxLiveness)
    assertEquals(UnitLiveness.STALE, v.hhLiveness)
    assertFalse(v.canArm)
    assertEquals(listOf("drive unit", "thruster unit"), v.missingUnits)
  }

  @Test
  fun `a dead board still reads linkUp true -- only the age reveals it`() {
    // The bug this whole liveness design exists for, end to end.
    val store = healthyStore(nowMs = 10_000)
    val later = 10_000 + SkContract.TELEMETRY_STALE_MS + 1
    arbiterPublishes(store, later)
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
    arbiterPublishes(store, later, activeClient = me)
    val v = view(store, later)
    assertEquals(UnitLiveness.STALE, v.hhLiveness)
    assertEquals(true, v.hhArmed, "the retained value still says armed")
    assertFalse(v.holdEngaged, "but nothing is holding on a board that stopped publishing")
  }

  /**
   * The window exists so that the state EVERY arm passes through is not drawn as
   * a fault.
   *
   * A hold is asked for on this station's next intent, republished by the
   * arbiter, taken by HH on a control tick and reported back on its telemetry
   * cycle. For those few hundred milliseconds "asked, not confirmed" is the
   * truth and nothing is wrong -- and a warning shown every single time is one
   * the operator stops reading, which is what made the one that matters
   * invisible.
   */
  @Test
  fun `a hold in flight is a transition until it has had long enough to be a fault`() {
    // The units keep publishing throughout -- this is a hold that is not
    // arriving, not a boat that has gone quiet, and those must not be confused.
    // So the store is rebuilt at each instant rather than left to go stale.
    fun asked(forMs: Long, atMs: Long = 10_000 + forMs) =
      holdView(healthyStore(nowMs = atMs, activeClient = me), askedAtMs = atMs - forMs, nowMs = atMs)

    // Just asked. HH has said nothing about itself yet.
    assertEquals(HoldPhase.REQUESTED, asked(forMs = 0).holdPhase)
    assertEquals(HoldPhase.REQUESTED, asked(forMs = SkContract.HOLD_ENGAGE_GRACE_MS - 1).holdPhase)

    // Past the window with nothing back: the unit has had every chance.
    val stalled = asked(forMs = SkContract.HOLD_ENGAGE_GRACE_MS)
    assertEquals(HoldPhase.NOT_ENGAGING, stalled.holdPhase)
    assertEquals(HoldStall.UNKNOWN, stalled.holdStall, "HH has not said why")
  }

  @Test
  fun `a hold HH reports is engaged however long it took`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(
      listOf(
        SkContract.HH_ARMED to true,
        SkContract.HH_MODE to "hold",
        SkContract.HH_FSM_STATE to SkContract.HH_FSM_HOLDING,
      ),
      10_000,
    )
    val v = holdView(store, askedAtMs = 0, nowMs = 10_000)
    assertEquals(HoldPhase.ENGAGED, v.holdPhase)
    assertEquals(HoldStall.NONE, v.holdStall)
  }

  /**
   * ARMED_IDLE publishes `hh.armed` exactly as HOLDING does.
   *
   * HH asserts ENABLE in both states (`control_step.cpp`), so the armed+hold
   * pair alone reads a hold that never started -- no heading it trusts -- or one
   * it gave up after coasting past `coast_max`, as a running hold. Its own FSM
   * state is the only thing that separates them, and the remedy is different
   * enough to be worth naming: a fresh arm, once the fix is back.
   */
  @Test
  fun `a unit armed but idle is not holding, and says why`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(
      listOf(
        SkContract.HH_ARMED to true,
        SkContract.HH_MODE to "hold",
        SkContract.HH_FSM_STATE to SkContract.HH_FSM_ARMED_IDLE,
      ),
      10_000,
    )
    val v = holdView(store, askedAtMs = 0, nowMs = 10_000)
    assertFalse(v.holdEngaged, "armed and idle is not a hold")
    assertEquals(HoldPhase.NOT_ENGAGING, v.holdPhase)
    assertEquals(HoldStall.NO_REFERENCE, v.holdStall)
  }

  /**
   * The case that sent the operator looking for a fault that was not there: HH
   * refusing a station it has not seen disarm (SAFETY.md thruster invariant 9).
   * Nothing on the boat is broken and nothing will change until the operator
   * disarms -- so the one thing the panel must not do is sit there saying
   * "waiting".
   */
  @Test
  fun `a refused hold is reported as refused, not as still waiting`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(
      listOf(
        SkContract.HH_ARMED to false,
        SkContract.HH_MODE to "hold",
        SkContract.HH_FSM_STATE to SkContract.HH_FSM_DISARMED,
      ),
      10_000,
    )
    val v = holdView(store, askedAtMs = 0, nowMs = 10_000)
    assertEquals(HoldPhase.NOT_ENGAGING, v.holdPhase)
    assertEquals(HoldStall.REFUSED, v.holdStall)
  }

  @Test
  fun `a faulted unit is named as one rather than blamed on the station`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(listOf(SkContract.HH_FSM_STATE to SkContract.HH_FSM_FAULT), 10_000)
    assertEquals(HoldStall.UNIT_FAULT, holdView(store, askedAtMs = 0, nowMs = 10_000).holdStall)
  }

  /**
   * A hold that is not running because the thruster belongs to the local ENGAGE
   * switch or to TX is not a fault and is already explained by the
   * "controlled by ..." note. Saying it twice, once in red, would send the
   * operator after a unit that is doing exactly what it was told.
   */
  @Test
  fun `a thruster somebody else owns is not reported as a stalled hold`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(
      listOf(
        SkContract.HH_SOURCE to "tx",
        SkContract.HH_FSM_STATE to SkContract.HH_FSM_DISARMED,
      ),
      10_000,
    )
    val v = holdView(store, askedAtMs = 0, nowMs = 10_000)
    assertEquals(HoldPhase.NOT_ENGAGING, v.holdPhase)
    assertEquals(HoldStall.OTHER_SOURCE, v.holdStall)
  }

  @Test
  fun `a station asking for no hold has no hold diagnostics at all`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(listOf(SkContract.HH_FSM_STATE to SkContract.HH_FSM_DISARMED), 10_000)

    // MANUAL selected, or simply not tracked by this caller.
    val manual = view(store, 10_000)
    assertEquals(HoldPhase.IDLE, manual.holdPhase)
    assertEquals(HoldStall.NONE, manual.holdStall)

    // Asking, but with the thruster not commandable -- nothing this station says
    // is reaching HH, and the kill switch says so already.
    val noToken = deriveStationView(healthyStore(nowMs = 10_000), ConnectionState.OPEN, me, 10_000,
      holdRequestedSinceMs = 0)
    assertEquals(HoldPhase.IDLE, noToken.holdPhase)
  }

  /**
   * The FSM path is a refinement, not a dependency: a server that has not
   * delivered it yet -- or an HH built before it was published -- still gets the
   * ARCHITECTURE.md §9 pair, which is what this station used before.
   */
  @Test
  fun `with no FSM state published the armed and hold pair still stands`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(listOf(SkContract.HH_ARMED to true, SkContract.HH_MODE to "hold"), 10_000)
    val v = holdView(store, askedAtMs = 0, nowMs = 10_000)
    assertNull(v.hhFsmState)
    assertTrue(v.holdEngaged)
    assertEquals(HoldPhase.ENGAGED, v.holdPhase)
  }

  // ---- MANUAL: HH refusing an armed station -------------------------------

  /** Armed in MANUAL since [askedAtMs], with HH reporting [fsmState]. */
  private fun manualView(fsmState: String?, askedAtMs: Long, nowMs: Long, hhSource: String = "none"):
    StationView {
    val store = healthyStore(nowMs = nowMs, activeClient = me)
    store.apply(
      listOfNotNull(
        SkContract.HH_MODE to "manual",
        SkContract.HH_SOURCE to hhSource,
        fsmState?.let { SkContract.HH_FSM_STATE to it },
      ),
      nowMs,
    )
    return deriveStationView(store, ConnectionState.OPEN, me, nowMs, manualRequestedSinceMs = askedAtMs)
  }

  /**
   * A local ENGAGE release latches every armed remote out until it STOPs and
   * ARMs again. The arbiter still hands this station the token, so it reads
   * armed and its contacts light under a thumb -- while HH, reporting DISARMED,
   * does nothing with any of it.
   */
  @Test
  fun `MANUAL armed with HH reporting DISARMED past the grace is a refusal`() {
    val v = manualView(SkContract.HH_FSM_DISARMED, askedAtMs = 0, nowMs = SkContract.HOLD_ENGAGE_GRACE_MS)
    assertTrue(v.thrusterCommandable, "the arbiter's half still says commandable")
    assertEquals(HoldStall.REFUSED, v.manualRefusal)
  }

  /** HH reports DISARMED for the round trip after every arm: that is not a refusal. */
  @Test
  fun `inside the grace a DISARMED report is not yet a refusal`() {
    val v = manualView(SkContract.HH_FSM_DISARMED, askedAtMs = 0, nowMs = SkContract.HOLD_ENGAGE_GRACE_MS - 1)
    assertEquals(HoldStall.NONE, v.manualRefusal)
  }

  @Test
  fun `HH armed in MANUAL is no refusal`() {
    for (state in listOf(SkContract.HH_FSM_HOLDING, SkContract.HH_FSM_ARMED_IDLE)) {
      assertEquals(HoldStall.NONE, manualView(state, askedAtMs = 0, nowMs = 10_000).manualRefusal, state)
    }
  }

  @Test
  fun `a thruster another source controls is no refusal`() {
    val v = manualView(SkContract.HH_FSM_DISARMED, askedAtMs = 0, nowMs = 10_000, hhSource = "local")
    assertEquals("local switch", v.thrusterOverriddenBy)
    assertEquals(HoldStall.NONE, v.manualRefusal)
  }

  @Test
  fun `a faulted unit refuses MANUAL too`() {
    assertEquals(
      HoldStall.UNIT_FAULT,
      manualView(SkContract.HH_FSM_FAULT, askedAtMs = 0, nowMs = 10_000).manualRefusal,
    )
  }

  /** No FSM state -- an older HH, or nothing arrived yet -- is no evidence of refusal. */
  @Test
  fun `no FSM state, or one this build does not know, raises no MANUAL alarm`() {
    assertEquals(HoldStall.NONE, manualView(null, askedAtMs = 0, nowMs = 10_000).manualRefusal)
    assertEquals(HoldStall.NONE, manualView("COASTING", askedAtMs = 0, nowMs = 10_000).manualRefusal)
  }

  @Test
  fun `a station not asking in MANUAL has no MANUAL diagnostics`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    store.apply(listOf(SkContract.HH_FSM_STATE to SkContract.HH_FSM_DISARMED), 10_000)
    assertEquals(HoldStall.NONE, view(store, 10_000).manualRefusal)
    // Asking, but not holding the token: nothing it says reaches HH anyway.
    val noToken =
      deriveStationView(healthyStore(nowMs = 10_000), ConnectionState.OPEN, me, 10_000, manualRequestedSinceMs = 0)
    assertEquals(HoldStall.NONE, noToken.manualRefusal)
  }

  @Test
  fun `reversal interlock is surfaced so a dead-looking thruster is explained`() {
    val store = healthyStore(nowMs = 10_000, activeClient = me)
    assertFalse(view(store, 10_000).reversalPending)

    store.apply(listOf(SkContract.HH_REVERSAL_PENDING to true), 10_000)
    assertTrue(view(store, 10_000).reversalPending)
  }

  // ---- Values that must not outlive their unit ---------------------------

  /** HH's fused heading, in the radians it is published in. */
  private fun SkValueStore.withHeading(deg: Double, reversal: Boolean, atMs: Long): SkValueStore {
    apply(
      listOf(
        SkContract.HH_FUSED_HEADING_RAD to Math.toRadians(deg),
        SkContract.HH_REVERSAL_PENDING to reversal,
      ),
      atMs,
    )
    return this
  }

  @Test
  fun `the current heading is shown while HH is answering`() {
    val store = healthyStore(nowMs = 10_000).withHeading(172.0, reversal = true, atMs = 10_000)
    val v = view(store, 10_000)
    assertEquals(172.0, v.currentHeadingDeg!!, 1e-6)
    assertTrue(v.reversalPending)
  }

  /**
   * The defect: HH switched off, its last heading still retained in the store,
   * and the panel drawing it under CURRENT HEADING as though the boat were still
   * pointing there. Likewise a retained reversal-pending explaining a thruster
   * delay that no unit is imposing.
   */
  @Test
  fun `a silent HH shows no current heading and no reversal wait`() {
    val store = healthyStore(nowMs = 10_000).withHeading(172.0, reversal = true, atMs = 10_000)
    // RX keeps publishing; HH has gone quiet.
    val later = 10_000 + SkContract.TELEMETRY_STALE_MS + 1
    store.apply(listOf(SkContract.RX_LINK_UP to true), later)
    arbiterPublishes(store, later)

    val v = view(store, later)
    assertEquals(UnitLiveness.STALE, v.hhLiveness)
    assertNull(v.currentHeadingDeg, "a heading from a unit that stopped answering is not current")
    assertFalse(v.reversalPending, "no live unit is waiting out an interlock")
  }

  @Test
  fun `a dropped socket shows no current heading and no reversal wait`() {
    val store = healthyStore(nowMs = 10_000).withHeading(172.0, reversal = true, atMs = 10_000)
    val v = view(store, 10_000, ConnectionState.CLOSED)
    assertEquals(UnitLiveness.OFFLINE, v.hhLiveness)
    assertNull(v.currentHeadingDeg)
    assertFalse(v.reversalPending)
  }

  // ---- Leaving the server ------------------------------------------------

  @Test
  fun `changing server is refused only while armed on a live link`() {
    val armedLive = view(healthyStore(activeClient = me), 10_000)
    assertTrue(armedLive.changeServerRefused)
    assertFalse(armedLive.changeServerSendsStop)

    val disarmedLive = view(healthyStore(), 10_000)
    assertFalse(disarmedLive.changeServerRefused)
    assertFalse(disarmedLive.changeServerSendsStop, "leaving disarmed must not stop anyone else")
  }

  /**
   * The lockout. Offline, "armed" is the last-known activeClient that the store
   * keeps across a drop; a disarm sent now cannot be seen to clear it, so a
   * refusal here kept the operator on a server they could not reach. Leaving is
   * allowed, and leaving carries a STOP.
   */
  @Test
  fun `offline and last seen armed, changing server is allowed and sends a stop`() {
    val v = view(healthyStore(activeClient = me), 10_000, ConnectionState.CLOSED)
    assertTrue(v.armed, "the retained activeClient still names this station")
    assertFalse(v.changeServerRefused, "offline lockout: no disarm could ever clear this")
    assertTrue(v.changeServerSendsStop)

    val otherHolds = view(healthyStore(activeClient = "ui-other"), 10_000, ConnectionState.CLOSED)
    assertFalse(otherHolds.changeServerRefused)
    assertFalse(otherHolds.changeServerSendsStop, "not ours to stop on the way out")
  }
}
