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
    compose.onNodeWithText("HOLD REQUESTED · UNIT NOT HOLDING", substring = true).assertExists()
    // The engaged caption, not the bare word: "UNIT NOT HOLDING" contains
    // "HOLDING", so only the whole phrase can tell the two readings apart.
    compose.onAllNodesWithText(EngagedCaption, substring = true).fetchSemanticsNodes().let {
      assertEquals("the panel claimed a hold HH has not reported", 0, it.size)
    }
    compose.onNodeWithText(WaitingNote).assertExists()
  }

  /** And the reverse: with the unit reporting the hold, nothing says "requested". */
  @Test
  fun `once HH reports the hold, the panel stops saying it was requested`() {
    compose.showControlScreen(mode = ThrusterMode.HOLD, view = holding)

    compose.onNodeWithText(EngagedCaption, substring = true).assertExists()
    compose.onAllNodesWithText("HOLD REQUESTED", substring = true).fetchSemanticsNodes().let {
      assertEquals("a running hold must not still read as requested", 0, it.size)
    }
    compose.onNodeWithText(WaitingNote).assertDoesNotExist()
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

/** The note that names what is being waited for while HH has not engaged. */
private const val WaitingNote = "hold requested — waiting for the thruster unit to engage"

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
  )

/**
 * The same station, with the hold asked for and HH not holding it: refused on an
 * untrustworthy heading, faulted, taken by its own ENGAGE input, or -- since the
 * firmware refuses a hold a returning station would re-engage -- waiting to see
 * this station disarm. The heading readout is identical in all of them.
 */
private val holdRequested = holding.copy(hhArmed = false)

/** Holding the token, but the thruster unit has stopped answering. */
private val armedWithoutThruster =
  disarmed.copy(
    controlState = ControlState.YOU,
    hhLiveness = UnitLiveness.STALE,
    driveCommandable = true,
    thrusterCommandable = false,
  )
