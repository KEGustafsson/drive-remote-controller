package io.github.kegustafsson.driveremote.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
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
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the README's screenshots from the real control screen.
 *
 * Not a test: every case is skipped unless `WRITE_SCREENSHOTS` is set, so CI
 * and an ordinary `:app:testDebugUnitTest` never write into the tree. To
 * refresh them after a UI change:
 *
 * ```
 * WRITE_SCREENSHOTS=1 ./gradlew :app:testDebugUnitTest --tests '*ReadmeScreenshots*' --rerun-tasks
 * ```
 *
 * They are Compose rendered under Robolectric's native graphics at the
 * reference phone's 360 x 780 dp and 480 dpi -- the same tree the phone draws,
 * without its status bar, navigation bar or a live server. Presses are
 * injected pointers, so a held contact is drawn as it is while held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadmeScreenshots {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun onlyWhenAsked() {
    assumeTrue("set WRITE_SCREENSHOTS to render the README screenshots", System.getenv("WRITE_SCREENSHOTS") != null)
  }

  @Test
  fun disarmed() {
    compose.show(disarmedView)
    compose.save("phone-disarmed")
  }

  @Test
  fun `drive held`() {
    compose.show(armedView)
    compose.onAllNodesWithContentDescription("FWD", useUnmergedTree = true)[0]
      .performTouchInput { down(center) }
    compose.save("phone-drive-held")
  }

  @Test
  fun `thruster manual`() {
    compose.show(armedView)
    compose.onNodeWithContentDescription("PORT", useUnmergedTree = true)
      .performTouchInput { down(center) }
    compose.save("phone-thruster-manual")
  }

  @Test
  fun `heading hold`() {
    compose.show(
      armedView.copy(hhMode = "hold", hhFsmState = SkContract.HH_FSM_HOLDING, holdPhase = HoldPhase.ENGAGED),
      mode = ThrusterMode.HOLD,
      trimDeg = 10.0,
    )
    compose.save("phone-heading-hold")
  }

  @Test
  fun `drive unit silent`() {
    compose.show(armedView.copy(rxLiveness = UnitLiveness.STALE, driveCommandable = false))
    compose.save("phone-drive-unit-silent")
  }

  @Test
  fun `detail open`() {
    compose.show(armedView)
    compose.onNodeWithContentDescription("Show telemetry detail").performClick()
    compose.save("phone-detail-open")
  }

  /** The owner's own setting: system font at 1.3x. */
  @Test
  fun `large system text`() {
    compose.show(armedView, fontScale = 1.3f)
    compose.save("phone-font-1.3x")
  }

  /** 2.0x system text, where the text has fitted itself to the window. */
  @Test
  fun `double system text`() {
    compose.show(armedView, fontScale = 2.0f)
    compose.save("phone-font-2x")
  }

  private fun AndroidComposeTestRule<*, ComponentActivity>.show(
    view: StationView,
    mode: ThrusterMode = ThrusterMode.MANUAL,
    trimDeg: Double = 0.0,
    fontScale: Float = 1f,
  ) {
    setContent {
      val base = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(density = base.density, fontScale = fontScale)
      ) {
        DriveRemoteTheme {
          Box(Modifier.fillMaxSize().background(DriveColors.surface)) {
            ControlScreen(
              view = view,
              thrusterMode = mode,
              trimDeg = trimDeg,
              authError = null,
              onArm = {},
              onDisarm = {},
              onThrusterModeChange = {},
              onThrusterDirectionChange = {},
              onTrim = {},
              onPortChange = {},
              onStbdChange = {},
              onChangeServer = {},
              appVersion = "v0.224",
            )
          }
        }
      }
    }
  }

  // Drawn rather than captured: `captureToImage` waits on a PixelCopy that
  // Robolectric never completes. A software draw of the decor view is the same
  // pixels Compose would put on screen.
  private fun AndroidComposeTestRule<*, ComponentActivity>.save(name: String) {
    waitForIdle()
    val root = activity.window.decorView
    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { root.draw(Canvas(bitmap)) }
    // Run from app/, so this is android/docs/screenshots.
    val file = File("../docs/screenshots/$name.png")
    file.parentFile!!.mkdirs()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    println("SCREENSHOT ${file.canonicalPath} ${bitmap.width}x${bitmap.height}")
  }
}

private val armedView =
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

private val disarmedView =
  armedView.copy(
    controlState = ControlState.NONE,
    driveCommandable = false,
    thrusterCommandable = false,
    hhArmed = false,
    hhFsmState = SkContract.HH_FSM_DISARMED,
  )
