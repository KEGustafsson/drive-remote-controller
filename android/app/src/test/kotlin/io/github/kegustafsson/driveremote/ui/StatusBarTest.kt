package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.HoldPhase
import io.github.kegustafsson.driveremote.core.IntentStatus
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.SkContract
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.UnitLiveness
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The collapsed status bar: five lamps of fixed short names, one height in
 * every state (ControlPositionStabilityTest holds the controls still around
 * it). What these check is that fitting in one row cost nothing that matters:
 * a fault is still named with the bar collapsed, no name is clipped at the
 * largest system text, and the readings it no longer prints are behind the tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusBarTest {

  @get:Rule val compose = createComposeRule()

  /** "Collapsing may not hide a fault": the quiet unit is named without a tap. */
  @Test
  fun `a quiet unit is named with the bar collapsed`() {
    compose.show(live.copy(rxLiveness = UnitLiveness.STALE))
    compose.onNodeWithContentDescription("DRIVE UNIT: NOT RESPONDING").assertExists()
    compose.onNodeWithContentDescription("THRUSTER UNIT: responding").assertExists()
  }

  @Test
  fun `a failing command path is named with the bar collapsed`() {
    compose.show(live.copy(intentStatus = IntentStatus.AUTH))
    compose.onNodeWithContentDescription("COMMANDS: BLOCKED — login refused").assertExists()
  }

  /**
   * The owner's S25 wrapped "COMMANDS" and "connected" mid-word at 1.3x. The
   * names are short now, but "short" is a claim about widths, so it is
   * measured at 2.0x on the narrowest phone.
   */
  @Test
  fun `no lamp name is clipped at double system text`() {
    compose.show(live.copy(rxLiveness = UnitLiveness.STALE), fontScale = 2.0f)
    for (name in listOf("LINK", "CMD", "CTRL", "DRV", "THR")) {
      val layout = mutableListOf<TextLayoutResult>()
      compose.onNodeWithText(name, useUnmergedTree = true)
        .fetchSemanticsNode()
        .config[SemanticsActions.GetTextLayoutResult]
        .action!!(layout)
      // Not hasVisualOverflow: with softWrap off the paragraph is laid out at
      // the full constraint width and the box shrinks to the text, so that
      // flag reads true for a name that fits. Clipped means the text wants
      // more width than its box was given.
      val result = layout.first()
      val wanted = result.multiParagraph.intrinsics.maxIntrinsicWidth
      assertFalse(
        "the '$name' lamp name wants ${wanted}px but has ${result.size.width}px at 2.0x",
        wanted > result.size.width + 0.5f,
      )
    }
  }

  @Test
  fun `the detail view carries the readings and the app version`() {
    compose.show(live, appVersion = "v0.224")
    compose.onNodeWithText("v0.224", substring = true).assertDoesNotExist()

    compose.onNodeWithContentDescription("Show telemetry detail").performClick()
    compose.onNodeWithText("reaching boat").assertExists()
    compose.onNodeWithText("this app", substring = false).assertExists()
    compose.onNodeWithText("app v0.224").assertExists()
  }

  @Test
  fun `an auth message is in the detail view`() {
    compose.show(live, authError = "Disarm before changing server — this station is armed.")
    compose.onNodeWithContentDescription("Show telemetry detail").performClick()
    compose.onNodeWithText("Disarm before changing server — this station is armed.").assertExists()
  }

  private fun ComposeContentTestRule.show(
    view: StationView,
    fontScale: Float = 1f,
    appVersion: String = "",
    authError: String? = null,
  ) {
    setContent {
      val base = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(density = base.density, fontScale = fontScale)
      ) {
        DriveRemoteTheme {
          // The control screen's 10 dp gutter, so the bar is as wide as it is
          // on the phone and the clipping check is not measured with room to spare.
          Box(Modifier.padding(10.dp)) {
            StatusPanel(
              view = view,
              authError = authError,
              onChangeServer = {},
              appVersion = appVersion,
            )
          }
        }
      }
    }
  }
}

private val live =
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
    intentStatus = IntentStatus.OK,
  )
