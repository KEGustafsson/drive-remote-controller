package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.HoldPhase
import io.github.kegustafsson.driveremote.core.IntentStatus
import io.github.kegustafsson.driveremote.core.LinkPhase
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
import org.robolectric.annotation.GraphicsMode

/**
 * The three rows of contacts -- thruster PORT/STBD, drive FWD, drive REV -- are
 * one height.
 *
 * The owner: "Increase PORT and STBD & FWD & REV buttons. Make PORT & STBD and
 * FWD & REV more balanced, same size." The thruster contacts used to sit at
 * their 88 dp floor while every spare pixel went to the drives: ~94 dp over
 * ~190 dp on the reference phone. The leftover is shared equally now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactBalanceTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `all three rows are one height on the reference phone`() {
    compose.show()
    compose.assertBalanced()
  }

  @Test
  fun `all three rows are one height at the owner's font scale`() {
    compose.show(fontScale = 1.3f)
    compose.assertBalanced()
  }

  @Test
  @Config(sdk = [35], qualifiers = "w411dp-h846dp-xxhdpi")
  fun `all three rows are one height on a taller phone`() {
    compose.show(fontScale = 1.3f)
    compose.assertBalanced()
  }

  /** On a big screen they all stop at the same ceiling rather than one of them. */
  @Test
  @Config(sdk = [35], qualifiers = "w800dp-h1280dp-xhdpi")
  fun `all three rows are one height on a tablet`() {
    compose.show()
    compose.assertBalanced()
  }

  /** HOLD's trim steps take the same share rather than leaving it empty. */
  @Test
  fun `in HOLD the trim steps grow into the row`() {
    compose.show(mode = ThrusterMode.HOLD, fontScale = 1.3f)
    // The live trim steps: the sizing copy's are cleared from the merged tree.
    val trim = compose.onAllNodesWithText("+10°")[0].getUnclippedBoundsInRoot()
    val fwd = compose.height("FWD")
    // The trim steps share the row with the heading readout, so they are
    // shorter than a contact -- but they must have taken the row, not kept
    // the chip height they had.
    println("BALANCE HOLD trim=${trim.bottom - trim.top} FWD=$fwd")
    assertTrue("trim step ${trim.bottom - trim.top} vs FWD $fwd", (trim.bottom - trim.top).value > 60f)
  }

  private fun ComposeContentTestRule.height(label: String): Dp =
    onAllNodesWithContentDescription(label, useUnmergedTree = true)[0]
      .getUnclippedBoundsInRoot()
      .let { it.bottom - it.top }

  private fun ComposeContentTestRule.assertBalanced() {
    val port = height("PORT")
    val fwd = height("FWD")
    val rev = height("REV")
    println("BALANCE PORT=$port FWD=$fwd REV=$rev")
    assertEquals("PORT vs FWD", fwd.value, port.value, 1f)
    assertEquals("REV vs FWD", fwd.value, rev.value, 1f)
  }

  private fun ComposeContentTestRule.show(
    mode: ThrusterMode = ThrusterMode.MANUAL,
    fontScale: Float = 1f,
  ) {
    setContent {
      val base = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(density = base.density, fontScale = fontScale)
      ) {
        DriveRemoteTheme {
          Box(Modifier.fillMaxSize()) {
            ControlScreen(
              view = balanceView,
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
}

private val balanceView =
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
    currentHeadingDeg = 96.0,
    reversalPending = false,
    rxLinkUp = true,
    rxLinkOk = true,
    rxMasterEnable = true,
    hhArmed = true,
    hhMode = "manual",
    hhFsmState = SkContract.HH_FSM_ARMED_IDLE,
    holdPhase = HoldPhase.IDLE,
    thrusterState = "off",
    intentStatus = IntentStatus.OK,
  )
