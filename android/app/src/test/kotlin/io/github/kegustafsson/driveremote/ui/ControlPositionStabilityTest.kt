package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.HoldPhase
import io.github.kegustafsson.driveremote.core.HoldStall
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.SkContract
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.ThrusterMode
import io.github.kegustafsson.driveremote.core.UnitLiveness
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every live control stays exactly where it is when the state changes.
 *
 * The owner's report, from the boat: "When disarmed and armed, control buttons
 * must stay in same places. Now it changes places." It did. A note under the
 * thruster panel existed only while disarmed, the kill switch's second line
 * was one line or two depending on what it said, and every notice in the
 * thruster panel and under each drive took a line only while it was showing.
 * The drives sit below all of them, so each ARM and DISARM moved both drive
 * contacts by a line -- the moment the operator is reaching for one.
 *
 * So the rule is: **what the screen says may change with state; where its
 * controls are may not.** Position depends on the window and the font scale,
 * nothing else. Messages get their room reserved whether or not they are
 * showing (`ReservedLines`), and the thruster body is the same height in both
 * modes.
 *
 * Measured as a switch within ONE composition, the way a real arm happens,
 * rather than by rendering two screens and comparing: that is what catches a
 * layout that only settles differently on the second pass.
 *
 * Telemetry's collapsed status bar is NOT yet in scope here. Its lamp readings
 * wrap differently with what they say, and where the drive bank has grown past
 * its floor that still resizes the drives; the status bar is being redesigned
 * to a fixed height to close that. These cases hold the telemetry panel's
 * content constant so they test what has been fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = StableReferencePhone)
class ControlPositionStabilityTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `arming moves nothing`() {
    compose.assertSameGeometry(disarmed, armed)
  }

  @Test
  fun `arming moves nothing at the owner's font scale`() {
    compose.assertSameGeometry(disarmed, armed, fontScale = 1.3f)
  }

  @Test
  @Config(sdk = [35], qualifiers = TallerPhone)
  fun `arming moves nothing on a taller phone`() {
    compose.assertSameGeometry(disarmed, armed)
  }

  @Test
  fun `arming in HOLD moves nothing`() {
    compose.assertSameGeometry(disarmed, armed, mode = ThrusterMode.HOLD)
  }

  /**
   * The kill switch's line grows to "tap to disarm · drive unit not
   * responding" -- two lines at this font scale, where "tap to arm" is one.
   *
   * Only the chrome is compared: the status bar also names the quiet unit on a
   * line of its own, which still resizes the drives below (see the class KDoc).
   */
  @Test
  fun `a unit going quiet while armed moves nothing above the drives`() {
    compose.assertSameGeometry(
      armed,
      armed.copy(rxLiveness = UnitLiveness.STALE),
      fontScale = 1.3f,
      drives = false,
    )
  }

  @Test
  fun `another source taking a drive moves nothing`() {
    compose.assertSameGeometry(armed, armed.copy(portOverriddenBy = CommandSource.TX.label))
  }

  @Test
  fun `a thruster notice appearing moves nothing`() {
    compose.assertSameGeometry(armed, armed.copy(reversalPending = true))
    compose.assertSameGeometryAfter(armed.copy(thrusterOverriddenBy = CommandSource.TX.label))
  }

  @Test
  fun `the MANUAL refusal band appearing moves nothing`() {
    compose.assertSameGeometry(armed, manualRefused)
  }

  @Test
  fun `switching thruster mode moves nothing below the thruster`() {
    compose.assertSameGeometry(armed, armed, fromMode = ThrusterMode.MANUAL, toMode = ThrusterMode.HOLD)
  }

  @Test
  fun `switching thruster mode moves nothing at the owner's font scale`() {
    compose.assertSameGeometry(
      armed,
      armed,
      fromMode = ThrusterMode.MANUAL,
      toMode = ThrusterMode.HOLD,
      fontScale = 1.3f,
    )
  }

  // ---- Harness -----------------------------------------------------------

  private var view by mutableStateOf(armed)
  private var mode by mutableStateOf(ThrusterMode.MANUAL)
  private lateinit var baseline: Map<String, DpRect>

  private fun ComposeContentTestRule.assertSameGeometry(
    from: StationView,
    to: StationView,
    mode: ThrusterMode = ThrusterMode.MANUAL,
    fontScale: Float = 1f,
    drives: Boolean = true,
  ) = assertSameGeometry(from, to, mode, mode, fontScale, drives)

  private fun ComposeContentTestRule.assertSameGeometry(
    from: StationView,
    to: StationView,
    fromMode: ThrusterMode,
    toMode: ThrusterMode,
    fontScale: Float = 1f,
    drives: Boolean = true,
  ) {
    view = from
    mode = fromMode
    show(fontScale)
    baseline = geometry(withThruster = true, withDrives = drives)
    view = to
    mode = toMode
    // The thruster's own contacts only exist in MANUAL, so a mode switch is
    // judged on what is below them: the kill switch and the drives.
    assertEquals(
      "a live control moved",
      baseline.filterKeys { fromMode == toMode || !it.startsWith("thruster") },
      geometry(withThruster = fromMode == toMode, withDrives = drives),
    )
  }

  /** A further change from the state the last assertion left, same baseline. */
  private fun ComposeContentTestRule.assertSameGeometryAfter(next: StationView) {
    view = next
    assertEquals("a live control moved", baseline, geometry(withThruster = true))
  }

  private fun ComposeContentTestRule.show(fontScale: Float) {
    setContent {
      val base = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(density = base.density, fontScale = fontScale)
      ) {
        DriveRemoteTheme {
          Box(Modifier.fillMaxSize()) {
            ControlScreen(
              view = view,
              thrusterMode = mode,
              trimDeg = 0.0,
              authError = null,
              onArm = {},
              onDisarm = {},
              onThrusterModeChange = {},
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
  }

  /**
   * Where every live control is. Found by substring so a greyed contact --
   * "FWD, unavailable" -- is the same entry as the live one.
   */
  private fun ComposeContentTestRule.geometry(
    withThruster: Boolean,
    withDrives: Boolean = true,
  ): Map<String, DpRect> {
    waitForIdle()
    return buildMap {
      put("killSwitch", onNodeWithTag(KillSwitchTag).getUnclippedBoundsInRoot())
      val labels =
        (if (withDrives) listOf("FWD", "REV") else emptyList()) +
          if (withThruster) listOf("PORT", "STBD") else emptyList()
      for (label in labels) {
        val nodes = onAllNodesWithContentDescription(label, substring = true, useUnmergedTree = true)
        val found = nodes.fetchSemanticsNodes().size
        for (index in 0 until found) {
          val key = if (label == "PORT" || label == "STBD") "thruster $label" else "$label#$index"
          put(key, nodes[index].getUnclippedBoundsInRoot())
        }
      }
    }
  }
}

/** The reference phone, as LayoutFloorsTest: a Galaxy S25, 360 x 780 dp. */
private const val StableReferencePhone = "w360dp-h780dp-xxhdpi"

/**
 * A taller phone -- roughly the Nokia 7.2 the owner compared against, 411 x
 * 846 dp once its bars are off. Here the drive bank has grown past its floor,
 * so anything that changed height above it would resize the drives rather than
 * only moving them.
 */
private const val TallerPhone = "w411dp-h846dp-xxhdpi"

/**
 * Armed, both units answering, nothing overriding. The telemetry panel's lamps
 * are held to readings that match [disarmed]'s in length, so the comparison is
 * of the controls rather than of the status bar (see the class KDoc).
 */
private val armed =
  StationView(
    connectionState = ConnectionState.OPEN,
    serverLive = true,
    linkPhase = LinkPhase.ONLINE,
    controlState = ControlState.YOU,
    rxLiveness = UnitLiveness.LIVE,
    hhLiveness = UnitLiveness.LIVE,
    canArm = true,
    driveCommandable = true,
    thrusterCommandable = true,
    portState = DisplayDrivePosition.NEUTRAL,
    stbdState = DisplayDrivePosition.NEUTRAL,
    portSource = CommandSource.PLUGIN,
    stbdSource = CommandSource.PLUGIN,
    thrusterSource = CommandSource.PLUGIN,
    portOverriddenBy = null,
    stbdOverriddenBy = null,
    thrusterOverriddenBy = null,
    heldDeg = 96.0,
    currentHeadingDeg = 172.0,
    reversalPending = false,
    rxLinkUp = true,
    rxLinkOk = true,
    rxMasterEnable = true,
    hhArmed = true,
    hhMode = "manual",
    hhFsmState = SkContract.HH_FSM_ARMED_IDLE,
    holdPhase = HoldPhase.IDLE,
    thrusterState = "off",
  )

/** Nobody armed, both units answering: the moment before the ARM. */
private val disarmed =
  armed.copy(
    controlState = ControlState.NONE,
    driveCommandable = false,
    thrusterCommandable = false,
    hhArmed = false,
    hhFsmState = SkContract.HH_FSM_DISARMED,
  )

/** Armed in MANUAL with HH refusing this station: the red band. */
private val manualRefused =
  armed.copy(
    hhArmed = false,
    hhFsmState = SkContract.HH_FSM_DISARMED,
    manualRefusal = HoldStall.REFUSED,
  )
