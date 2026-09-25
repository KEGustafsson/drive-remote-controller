package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.DrivePosition
import io.github.kegustafsson.driveremote.core.HoldPhase
import io.github.kegustafsson.driveremote.core.HoldStall
import io.github.kegustafsson.driveremote.core.IntentStatus
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.SkContract
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.ThrusterDirection
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
 * No space is held open for a message that is not showing.
 *
 * The owner's report after the controls were made to hold still: "there is
 * empty unused space where text was removed, and you did not use that area to
 * increase buttons." Every notice had its own line reserved in every state --
 * the thruster's refusal / "controlled by" / "reversing" line under PORT/STBD,
 * and each drive's "controlled by" line under REV. At the owner's 1.3x font the
 * longest of each takes two lines, so ~110 dp of the reference phone was blank
 * nearly all the time. The notices are drawn over the controls they concern
 * now, and that height went to the buttons.
 *
 * ControlPositionStabilityTest still asserts that a notice appearing moves
 * nothing; this suite asserts the space is gone, the notice lands on the
 * control it is about, and it does not get between a thumb and a contact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoticeOverlayTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `the drive contacts reach the bottom of the bank`() {
    compose.show(armed)
    compose.assertDrivesFillTheBank()
  }

  @Test
  fun `the drive contacts reach the bottom of the bank at the owner's font scale`() {
    compose.show(armed, fontScale = 1.3f)
    compose.assertDrivesFillTheBank()
  }

  @Test
  fun `nothing is left empty under the thruster contacts`() {
    compose.show(armed, fontScale = 1.3f)
    val panel = compose.onNodeWithTag(ThrusterPanelTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
    val port = compose.contact("PORT")
    // The panel's own 10 dp padding is all that may sit below the contacts.
    assertEquals(
      "space below the thruster contacts",
      10f,
      (panel.bottom - port.bottom).value,
      1f,
    )
  }

  @Test
  fun `a thruster notice is drawn over the thruster contacts`() {
    compose.show(armed.copy(reversalPending = true), fontScale = 1.3f)
    compose.onNodeWithText("reversing — waiting for the thruster interlock").assertInside(compose.contact("PORT"))
  }

  @Test
  fun `a drive override is drawn over that drive's REV contact`() {
    compose.show(armed.copy(portOverriddenBy = CommandSource.LOCAL.label), fontScale = 1.3f)
    compose.onNodeWithText("controlled by local switch").assertInside(compose.contact("REV", 0))
  }

  @Test
  fun `a HOLD refusal is drawn over the trim row`() {
    compose.show(
      armed.copy(
        hhMode = "hold",
        holdPhase = HoldPhase.NOT_ENGAGING,
        holdStall = HoldStall.REFUSED,
      ),
      mode = ThrusterMode.HOLD,
    )
    val band = compose.onNodeWithText("NOT HOLDING — RE-ARM TO ENGAGE").getUnclippedBoundsInRoot()
    val trim = compose.onNodeWithText("+10°").getUnclippedBoundsInRoot()
    assertTrue("the band does not cover the trim row", band.top < trim.bottom && band.bottom > trim.top)
  }

  /**
   * The band is a label, not a control: a press on it reaches the contact under
   * it as it did before the notice appeared. "Reversing" covers the very
   * contact the operator is holding; a refused or outranked press is ignored
   * where it lands, not here.
   */
  @Test
  fun `a press on a thruster notice still reaches the contact`() {
    val directions = mutableListOf<ThrusterDirection>()
    compose.show(armed.copy(reversalPending = true), onDirection = { directions += it })
    val band = compose.onNodeWithText("reversing — waiting for the thruster interlock").getUnclippedBoundsInRoot()
    val port = compose.contact("PORT")
    compose.onAllNodesWithContentDescription("PORT", useUnmergedTree = true)[0].performTouchInput {
      // A point on the band, inside PORT, in the contact's own coordinates.
      down(Offset(width / 2f, (band.top - port.top + (band.bottom - band.top) / 2).toPx()))
    }
    compose.waitForIdle()
    assertEquals(ThrusterDirection.PORT, directions.last())
  }

  @Test
  fun `a press on a drive override still reaches the contact`() {
    val positions = mutableListOf<DrivePosition>()
    compose.show(armed.copy(portOverriddenBy = CommandSource.TX.label), onPort = { positions += it })
    val band = compose.onNodeWithText("controlled by TX remote").getUnclippedBoundsInRoot()
    val rev = compose.contact("REV", 0)
    compose.onAllNodesWithContentDescription("REV", useUnmergedTree = true)[0].performTouchInput {
      down(Offset(width / 2f, (band.top - rev.top + (band.bottom - band.top) / 2).toPx()))
    }
    compose.waitForIdle()
    assertEquals(DrivePosition.REVERSE, positions.last())
  }

  // ---- Harness -----------------------------------------------------------

  private fun ComposeContentTestRule.show(
    view: StationView,
    mode: ThrusterMode = ThrusterMode.MANUAL,
    fontScale: Float = 1f,
    onDirection: (ThrusterDirection) -> Unit = {},
    onPort: (DrivePosition) -> Unit = {},
  ) {
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
              onThrusterDirectionChange = onDirection,
              onTrim = {},
              onPortChange = onPort,
              onStbdChange = {},
              onChangeServer = {},
            )
          }
        }
      }
    }
  }

  private fun ComposeContentTestRule.contact(label: String, index: Int = 0): DpRect =
    onAllNodesWithContentDescription(label, useUnmergedTree = true)[index].getUnclippedBoundsInRoot()

  private fun ComposeContentTestRule.assertDrivesFillTheBank() {
    val bank = onNodeWithTag(DriveBankTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
    for (index in 0 until 2) {
      val rev = contact("REV", index)
      assertEquals("space below REV #$index", 0f, (bank.bottom - rev.bottom).value, 1f)
    }
  }

  private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertInside(control: DpRect) {
    val band = getUnclippedBoundsInRoot()
    assertTrue(
      "the notice at $band is not over the control at $control",
      band.top >= control.top - 1.dp && band.bottom <= control.bottom + 1.dp,
    )
  }
}

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
