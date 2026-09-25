package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.HoldPhase
import io.github.kegustafsson.driveremote.core.HoldStall
import io.github.kegustafsson.driveremote.core.SkContract
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.ThrusterMode
import io.github.kegustafsson.driveremote.core.UnitLiveness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Choosing the thruster's gate BEFORE arming.
 *
 * The MANUAL/HOLD chooser is the one control in the thruster panel that stays
 * live while the thruster is not commandable, and the distinction it rests on
 * is worth stating: it commands nothing. It selects which gate the next arm
 * will open. Everything that does reach the thruster -- the PORT/STBD contacts,
 * the trim steps -- stays inert exactly as before, so the boundary between
 * "chose a mode" and "commanded the machine" is what these tests pin.
 *
 * The consequence the operator must not be surprised by: with HOLD selected,
 * arming alone starts the hold -- no further press is involved. So the panel
 * says so while disarmed, and it must not label a heading it is not holding as
 * held: disarmed, HH mirrors that path to the fused heading.
 *
 * (`setContent` may be called only once per rule, so each case renders its own
 * state in its own test rather than re-rendering mid-test.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = ModeReferencePhone)
class ThrusterModeSelectionTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `HOLD can be selected with nothing armed`() {
    var chosen: ThrusterMode? = null
    compose.showControlScreen(mode = ThrusterMode.MANUAL, onModeChange = { chosen = it })

    compose.onNodeWithText("HOLD").assertIsEnabled()
    compose.onNodeWithText("HOLD").assertHasClickAction()
    compose.onNodeWithText("HOLD").performClick()

    assertEquals(ThrusterMode.HOLD, chosen)
  }

  @Test
  fun `MANUAL can be selected with nothing armed`() {
    var chosen: ThrusterMode? = null
    compose.showControlScreen(mode = ThrusterMode.HOLD, onModeChange = { chosen = it })

    compose.onNodeWithText("MANUAL").assertIsEnabled()
    compose.onNodeWithText("MANUAL").performClick()

    assertEquals(ThrusterMode.MANUAL, chosen)
  }

  /**
   * Reported from the boat: one station holds and trims a heading, and a second,
   * idle station in HOLD mode shows that number under "CURRENT HEADING".
   *
   * HH publishes `hh.setpointDeg` as the held target only while it is actually
   * holding, and as a mirror of its fused heading otherwise (control_step.cpp).
   * A station that is not the one holding therefore cannot read a current
   * heading out of it -- it is somebody else's target. The firmware is right;
   * the app was reading the wrong field.
   *
   * The fixture sets heldDeg = 96 and currentHeadingDeg = 172 deliberately, so
   * these two tests can tell the quantities apart.
   */
  @Test
  fun `an idle station shows the current heading, not another station's setpoint`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD)

    compose.onNodeWithText("172", substring = true).assertExists()
    compose.onAllNodesWithText("96", substring = true).fetchSemanticsNodes().let {
      assertEquals("the held setpoint of another station must not be shown here", 0, it.size)
    }
    compose.onNodeWithText("CURRENT HEADING", substring = true).assertExists()
  }

  @Test
  fun `the station that is holding shows the heading it holds`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD, view = holding)

    compose.onNodeWithText("96", substring = true).assertExists()
    compose.onNodeWithText(EngagedCaption, substring = true).assertExists()
  }

  /**
   * The caption is HH's report, not this station's request.
   *
   * HH mirrors `hh.setpointDeg` to the fused heading in every state except
   * holding, so 96 is on screen either way -- the word beside it is the only
   * thing that can tell the operator whether anything is holding it. Before
   * this, "HOLDING" appeared as soon as this station could command the thruster,
   * which is true while HH has refused the hold, faulted, been taken by its own
   * ENGAGE input, or is waiting to see a returning station disarm.
   */
  @Test
  fun `a hold this station only asked for is not labelled as holding`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD, view = holdRequested)

    compose.onNodeWithText("96", substring = true).assertExists()
    compose.onNodeWithText(RequestedCaption, substring = true).assertExists()
    // The engaged caption, not the bare word: the alarm text below contains
    // "HOLDING" too, so only the whole phrase can tell the readings apart.
    compose.onAllNodesWithText(EngagedCaption, substring = true).fetchSemanticsNodes().let {
      assertEquals("the panel claimed a hold HH has not reported", 0, it.size)
    }
  }

  /**
   * ...and it does not shout about it either, which is the whole point of the
   * window.
   *
   * Every arm in HOLD passes through this state. Drawing it as a warning put a
   * yellow line on the panel on every single arm -- and a warning that appears
   * every time is one the operator learns to read past, including the time it
   * means the thruster is not going to hold anything. The caption still refuses
   * to say HOLDING, so what is withheld is the alarm, not the truth.
   */
  @Test
  fun `a hold still in flight is not drawn as a fault`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD, view = holdRequested)

    compose.onNodeWithText(RefusedAlarm).assertDoesNotExist()
    compose.onAllNodesWithText("NOT HOLDING", substring = true).fetchSemanticsNodes().let {
      assertEquals("a request in flight was drawn as a fault", 0, it.size)
    }
  }

  /**
   * The case the operator actually hit: the hold never engaged and the panel sat
   * there saying it was waiting. Past the window this is a fault, it is shown as
   * one, and -- because HH says which -- it names the move that fixes it.
   */
  @Test
  fun `a hold the unit is refusing is shown as a fault, with the remedy`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD, view = holdRefused)

    compose.onNodeWithText(StalledCaption, substring = true).assertExists()
    compose.onNodeWithText(RefusedAlarm).assertExists()
    compose.onAllNodesWithText(EngagedCaption, substring = true).fetchSemanticsNodes().let {
      assertEquals("a refused hold must never read as a running one", 0, it.size)
    }
  }

  // Each reason gets its own remedy: what the operator must do differs, and a
  // faulted unit in particular is not something re-arming fixes. One rendering
  // per test because the rule's content may only be set once.

  @Test
  fun `a faulted unit is named as one`() {
    compose.showControlScreen(
      mode = ThrusterMode.HOLD,
      view =
        holdRefused.copy(
          hhFsmState = SkContract.HH_FSM_FAULT,
          holdStall = HoldStall.UNIT_FAULT,
        ),
    )
    compose.onNodeWithText("NOT HOLDING — UNIT FAULT").assertExists()
  }

  /**
   * ARMED_IDLE: HH is armed -- ENABLE asserted, hh.armed published true -- and
   * still not holding, because in HOLD it has no heading it trusts to steer
   * against. Re-arming will not conjure a fix, so the message says what is
   * missing rather than what to press.
   */
  @Test
  fun `a hold with no heading reference says so rather than blaming the station`() {
    compose.showControlScreen(
      mode = ThrusterMode.HOLD,
      view =
        holdRefused.copy(
          hhArmed = true,
          hhFsmState = SkContract.HH_FSM_ARMED_IDLE,
          holdStall = HoldStall.NO_REFERENCE,
        ),
    )
    compose.onNodeWithText("NOT HOLDING — NO HEADING FIX").assertExists()
  }

  /** HH saying nothing is still a fault: state the fact and the safe remedy. */
  @Test
  fun `a stalled hold with no reason from HH still reports the fault`() {
    compose.showControlScreen(
      mode = ThrusterMode.HOLD,
      view = holdRefused.copy(hhFsmState = null, holdStall = HoldStall.UNKNOWN),
    )
    compose.onNodeWithText("NOT HOLDING — CHECK THE UNIT").assertExists()
  }

  /**
   * A thruster the local switch or TX is holding is not a fault, and the
   * "controlled by ..." note already says so. A second message, in red, would
   * send the operator after a unit that is doing exactly what it was told.
   */
  @Test
  fun `a thruster somebody else owns gets one explanation, not two`() {
    compose.showControlScreen(
      mode = ThrusterMode.HOLD,
      view =
        holdRefused.copy(
          holdStall = HoldStall.OTHER_SOURCE,
          thrusterSource = CommandSource.TX,
          thrusterOverriddenBy = CommandSource.TX.label,
        ),
    )

    compose.onNodeWithText("controlled by ${CommandSource.TX.label}").assertExists()
    compose.onAllNodesWithText("NOT HOLDING", substring = true).fetchSemanticsNodes().let {
      assertEquals("an overridden thruster was also reported as a stalled hold", 0, it.size)
    }
    // The caption too, not just the band: a red "HOLD NOT ENGAGED" over a note
    // saying the thruster is being worked by TX describes a fault that is not
    // happening. It reads as a request in flight, which is what it is.
    compose.onAllNodesWithText(StalledCaption, substring = true).fetchSemanticsNodes().let {
      assertEquals("precedence working as designed was drawn as a fault", 0, it.size)
    }
    compose.onNodeWithText(RequestedCaption, substring = true).assertExists()
  }

  /** And the reverse: with the unit reporting the hold, nothing says "requested". */
  @Test
  fun `once HH reports the hold, the panel stops saying it was requested`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD, view = holding)

    compose.onNodeWithText(EngagedCaption, substring = true).assertExists()
    compose.onAllNodesWithText("HOLD REQUESTED", substring = true).fetchSemanticsNodes().let {
      assertEquals("a running hold must not still read as requested", 0, it.size)
    }
    compose.onAllNodesWithText("NOT HOLDING", substring = true).fetchSemanticsNodes().let {
      assertEquals("a running hold must not carry a fault banner", 0, it.size)
    }
  }

  /**
   * The other half of the boundary: selecting a mode is not commanding one, so
   * the thrust contacts stay as inert as they were. ContactButton swaps its
   * description to ", unavailable" when it cannot command, so the live "PORT"
   * and "STBD" nodes must not exist at all.
   */
  @Test
  fun `the thrust contacts stay inert while disarmed`() {
    compose.showControlScreen(mode = ThrusterMode.MANUAL)

    for (label in listOf("PORT", "STBD")) {
      assertEquals(
        "'$label' is live on a disarmed screen",
        0,
        compose.onAllNodesWithContentDescription(label, useUnmergedTree = true)
          .fetchSemanticsNodes()
          .size,
      )
      compose.onNodeWithContentDescription("$label, unavailable", useUnmergedTree = true)
        .assertExists()
    }
  }

  /** The same for HOLD's commanding control: the trim steps. */
  @Test
  fun `the trim steps stay inert while disarmed`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD)
    compose.onNodeWithText("+10°").assertIsNotEnabled()
    compose.onNodeWithText("+1°").assertIsNotEnabled()
  }

  @Test
  fun `disarmed in MANUAL, the panel says arming is what makes it live`() {
    compose.showControlScreen(mode = ThrusterMode.MANUAL)
    compose.onNodeWithText("MANUAL selected — arm to thrust").assertExists()
  }

  /**
   * HOLD needs no press after the arm, so the arm itself is the action that
   * starts the thruster working. That has to be stated, not discovered.
   */
  @Test
  fun `disarmed in HOLD, the panel says the hold starts on arm`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD)
    compose.onNodeWithText("HOLD selected — starts holding when you arm").assertExists()
  }

  /**
   * Disarmed, HH mirrors the setpoint path to the fused heading -- the number
   * on screen is the boat's current heading with nothing holding it. Labelling
   * that "HOLDING" would be the display claiming a state the machine is not in,
   * and this panel is now easy to open with nothing armed.
   */
  @Test
  fun `disarmed, the heading readout does not claim to be holding`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD)
    compose.onNodeWithText("°  CURRENT HEADING").assertExists()
  }

  /**
   * MANUAL, and HH refusing this station -- a local ENGAGE release latched it
   * out until it STOPs and ARMs again. The arbiter still hands it the token, so
   * it reads armed and its contacts light under a thumb; nothing else on the
   * panel said the thruster was ignoring them.
   */
  @Test
  fun `a MANUAL station HH is refusing is told so, with the remedy`() {
    compose.showControlScreen(mode = ThrusterMode.MANUAL, view = manualRefused)
    compose.onNodeWithText(ManualRefusedAlarm).assertExists()
  }

  @Test
  fun `a faulted unit in MANUAL is named as one`() {
    compose.showControlScreen(
      mode = ThrusterMode.MANUAL,
      view = manualRefused.copy(hhFsmState = SkContract.HH_FSM_FAULT, manualRefusal = HoldStall.UNIT_FAULT),
    )
    compose.onNodeWithText("THRUSTER REFUSED — UNIT FAULT").assertExists()
  }

  /** Armed in MANUAL with HH taking the commands: no band. */
  @Test
  fun `an ordinary armed MANUAL panel has no refusal band`() {
    compose.showControlScreen(
      mode = ThrusterMode.MANUAL,
      view = holding.copy(hhMode = "manual", holdPhase = HoldPhase.IDLE),
    )
    compose.onAllNodesWithText("THRUSTER REFUSED", substring = true).fetchSemanticsNodes().let {
      assertEquals(0, it.size)
    }
  }

  /** The band is MANUAL's: a refusal carried into HOLD is the hold band's to report. */
  @Test
  fun `the MANUAL refusal band is not drawn in HOLD`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD, view = manualRefused)
    compose.onNodeWithText(ManualRefusedAlarm).assertDoesNotExist()
  }

  /**
   * Armed, but HH is not answering: the same controls are inert for a
   * completely different reason, so "arm to thrust" would point the operator at
   * the one control already doing its job. The kill switch names the real
   * reason ("thruster unit not responding") and this panel stays quiet.
   */
  @Test
  fun `an armed operator is not told to arm when HH is the thing missing`() {
    compose.showControlScreen(mode = ThrusterMode.MANUAL, view = armedWithoutThruster)
    compose.onNodeWithText("MANUAL selected — arm to thrust").assertDoesNotExist()
  }

  /**
   * The disarmed note is an extra line in the thruster panel, and every dp
   * there comes off the bank below it. The contacts' floor is un-negotiable so
   * this cannot shrink them -- but it is measured rather than assumed, on the
   * smallest screen the layout claims to support, because "it cannot shrink
   * them" is exactly the assumption the 4.7 dp defect was made on.
   */
  /**
   * The alarm band is another line in the same panel, and it appears at the
   * worst possible moment -- armed, in HOLD, with the operator about to reach
   * for a drive contact. Geometry is a safety property on this screen, so the
   * band is measured on the smallest supported window rather than assumed to
   * fit.
   */
  @Test
  @Config(sdk = [35], qualifiers = ModeMinimumScreen)
  fun `the stalled-hold alarm does not cost the drive contacts their floor`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD, view = holdRefused)
    compose.onNodeWithText(RefusedAlarm).assertExists()

    for (label in listOf("FWD", "REV")) {
      val nodes = compose.onAllNodesWithContentDescription(label, useUnmergedTree = true)
      val found = nodes.fetchSemanticsNodes().size
      assertTrue("no '$label' contact found on the armed control screen", found > 0)
      for (index in 0 until found) {
        val box = nodes[index].getUnclippedBoundsInRoot()
        val height: Dp = box.bottom - box.top
        assertTrue(
          "'$label' contact #$index measured $height, below the 88.dp floor",
          height >= 88.dp - 0.01.dp,
        )
      }
    }
  }

  @Test
  @Config(sdk = [35], qualifiers = ModeMinimumScreen)
  fun `the disarmed note does not cost the drive contacts their floor`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD)

    for (label in listOf("FWD, unavailable", "REV, unavailable")) {
      val nodes = compose.onAllNodesWithContentDescription(label, useUnmergedTree = true)
      val found = nodes.fetchSemanticsNodes().size
      assertTrue("no '$label' contact found on the disarmed control screen", found > 0)
      for (index in 0 until found) {
        val box = nodes[index].getUnclippedBoundsInRoot()
        val height: Dp = box.bottom - box.top
        // Same floor and the same rounding slack as LayoutFloorsTest, written
        // against the requirement rather than against the layout's constant.
        assertTrue(
          "'$label' contact #$index measured $height, below the 88.dp floor",
          height >= 88.dp - 0.01.dp,
        )
      }
    }
  }
}

/**
 * The caption shown only when HH reports the hold running, with this suite's
 * trim of 0. Written out rather than built from the composable's string so a
 * reworded panel fails here rather than silently passing.
 */
private const val EngagedCaption = "HOLDING · TRIM 0°"

/** The caption for a hold that has been asked for and not yet confirmed. */
private const val RequestedCaption = "°  HOLD REQUESTED"

/** The caption once the request has outlived its window. */
private const val StalledCaption = "°  HOLD NOT ENGAGED"

/** The alarm shown for a hold the unit is refusing, and its remedy. */
private const val RefusedAlarm = "NOT HOLDING — RE-ARM TO ENGAGE"

/** Same reference device as LayoutFloorsTest: a Galaxy S25, 360 x 780 dp. */
private const val ModeReferencePhone = "w360dp-h780dp-xxhdpi"

/** The documented bottom of the supported envelope, as in LayoutFloorsTest. */
private const val ModeMinimumScreen = "w360dp-h512dp-xxhdpi"

/** Every case here is disarmed -- that is the state under test. */
private fun ComposeContentTestRule.showControlScreen(
  mode: ThrusterMode,
  view: StationView = disarmed,
  onModeChange: (ThrusterMode) -> Unit = {},
) {
  setContent {
    DriveRemoteTheme {
      Box(Modifier.fillMaxSize()) {
        ControlScreen(
          view = view,
          thrusterMode = mode,
          trimDeg = 0.0,
          authError = null,
          onArm = {},
          onDisarm = {},
          onThrusterModeChange = onModeChange,
          onThrusterDirectionChange = {},
          onTrim = {},
          onPortChange = {},
          onStbdChange = {},
          onChangeServer = {},
        )
      }
    }
  }
}

/**
 * Nobody armed, both units answering: the state an operator is in when they
 * decide which mode to arm into. Neither machine is commandable -- that is what
 * disarmed means here -- while `canArm` is true, because both boards are alive
 * and an ARM is on offer.
 */
private val disarmed =
  StationView(
    connectionState = ConnectionState.OPEN,
    serverLive = true,
    linkPhase = LinkPhase.ONLINE,
    controlState = ControlState.NONE,
    rxLiveness = UnitLiveness.LIVE,
    hhLiveness = UnitLiveness.LIVE,
    canArm = true,
    driveCommandable = false,
    thrusterCommandable = false,
    portState = DisplayDrivePosition.NEUTRAL,
    stbdState = DisplayDrivePosition.NEUTRAL,
    portSource = CommandSource.NONE,
    stbdSource = CommandSource.NONE,
    thrusterSource = CommandSource.NONE,
    portOverriddenBy = null,
    stbdOverriddenBy = null,
    thrusterOverriddenBy = null,
    heldDeg = 96.0,
    currentHeadingDeg = 172.0,
    reversalPending = false,
    rxLinkUp = true,
    rxLinkOk = true,
    rxMasterEnable = true,
    hhArmed = false,
    // What HH reports about ITSELF. Disarmed and in manual, so nothing is being
    // held -- which is why the setpoint above is a mirror of the fused heading.
    hhMode = "manual",
    thrusterState = "off",
  )

/**
 * Armed and commanding the thruster, with HH reporting the hold RUNNING.
 *
 * `hhArmed` + `hhMode` is the pair that makes this the holding station rather
 * than merely the station that asked (ARCHITECTURE.md §9, StationView
 * .holdEngaged). Without them this fixture was "armed and hoping".
 */
private val holding =
  disarmed.copy(
    controlState = ControlState.YOU,
    thrusterCommandable = true,
    hhArmed = true,
    hhMode = "hold",
    hhFsmState = SkContract.HH_FSM_HOLDING,
    holdPhase = HoldPhase.ENGAGED,
  )

/**
 * The same station in the first moment after an arm: the request is out, HH has
 * not answered yet, and the window it is given to answer has not run out. The
 * heading readout is identical to the holding one -- HH mirrors the setpoint to
 * the fused heading whenever it is not holding -- so the caption beside it is
 * the only thing that can tell the two apart.
 */
private val holdRequested =
  holding.copy(hhArmed = false, hhFsmState = null, holdPhase = HoldPhase.REQUESTED)

/**
 * And the same station once HH has had its window and not taken the hold, with
 * HH itself reporting DISARMED: the firmware is refusing a station it has not
 * seen disarm (SAFETY.md thruster invariant 9). Nothing will change until the
 * operator disarms and arms again, which is why this one is drawn as a fault
 * rather than as more waiting.
 */
private val holdRefused =
  holding.copy(
    // Armed at both machines, which is the state an operator is really in when
    // this appears -- and it is what puts live drive contacts on the same screen
    // as the alarm band, which is why the floor is measured with it showing.
    driveCommandable = true,
    hhArmed = false,
    hhFsmState = SkContract.HH_FSM_DISARMED,
    holdPhase = HoldPhase.NOT_ENGAGING,
    holdStall = HoldStall.REFUSED,
  )

/** The MANUAL refusal band, as the owner worded it. */
private const val ManualRefusedAlarm = "THRUSTER REFUSED — RE-ARM TO COMMAND"

/**
 * Armed in MANUAL, and HH reporting DISARMED past the grace window: refusing
 * this station. See StationView.manualRefusal.
 */
private val manualRefused =
  holding.copy(
    driveCommandable = true,
    hhArmed = false,
    hhMode = "manual",
    hhFsmState = SkContract.HH_FSM_DISARMED,
    holdPhase = HoldPhase.IDLE,
    manualRefusal = HoldStall.REFUSED,
  )

/** Holding the token, but the thruster unit has stopped answering. */
private val armedWithoutThruster =
  disarmed.copy(
    controlState = ControlState.YOU,
    hhLiveness = UnitLiveness.STALE,
    driveCommandable = true,
    thrusterCommandable = false,
  )
