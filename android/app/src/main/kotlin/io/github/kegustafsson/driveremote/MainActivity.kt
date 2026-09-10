package io.github.kegustafsson.driveremote

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kegustafsson.driveremote.discovery.DiscoveredServer
import io.github.kegustafsson.driveremote.discovery.MdnsDiscovery
import io.github.kegustafsson.driveremote.ui.AccessScreen
import io.github.kegustafsson.driveremote.ui.ControlScreen
import io.github.kegustafsson.driveremote.ui.DriveColors
import io.github.kegustafsson.driveremote.ui.DriveRemoteTheme
import io.github.kegustafsson.driveremote.ui.ServerScreen

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Keep the screen on while the app is in front. The web UI cannot do this
    // at all -- the Screen Wake Lock API is secure-context-gated and the Signal
    // K server is plain HTTP -- and a screen that locks mid-manoeuvre is
    // exactly when the operator needs the controls.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    lockOrientationForFormFactor()

    setContent { DriveRemoteTheme { StationApp() } }
  }

  /**
   * Phones stay locked upright. Tablets rotate. Neither is stated in the
   * manifest, and that is the point.
   *
   * A phone must not rotate: a rotation mid-manoeuvre moves every control out
   * from under the thumb that was on it. A tablet must, because locking one
   * upright does not keep it upright -- it letterboxes, and the bigger screen
   * then gets the smaller control panel. `android:screenOrientation` cannot
   * express "it depends", so it says nothing and this decides.
   *
   * **Deciding it here rather than in the manifest is what removed a visible
   * artefact, not just a tidy-up.** With `screenOrientation="portrait"` the
   * activity LAUNCHED locked, so on a landscape tablet the window was created
   * letterboxed -- splash included -- and the unlock below then resized it. The
   * operator saw a small black rectangle grow to fill the screen at every
   * launch. Declaring nothing means the window is created at the size the
   * device is already at, and this call then agrees with it.
   *
   * The cost is narrow and lands where it hurts least: a phone whose owner has
   * auto-rotate ON and is holding it sideways at launch now comes up landscape
   * for a moment before snapping upright. A tablet in landscape was doing that
   * on **every** launch.
   *
   * Two spellings of the size test are wrong, both tried:
   *
   * - **A `values-sw600dp` resource on `android:screenOrientation`.** The
   *   attribute takes a resource reference and it resolves, but the manifest is
   *   read by the package manager, which resolves it without a device
   *   configuration. The qualified value is never consulted; the tablet stayed
   *   letterboxed.
   * - **`configuration.smallestScreenWidthDp`.** While an activity is
   *   letterboxed its Configuration describes the *pane*, not the display --
   *   about 355 dp on the 1920 x 1200 tablet -- so it would read "phone" in
   *   exactly the state that needs the tablet answer. Less reachable now that
   *   nothing launches letterboxed, but still the wrong question.
   *
   * So this asks the display. `maximumWindowMetrics` is the whole display
   * whatever shape this activity's own window has.
   *
   * SCREEN_ORIENTATION_USER, not SENSOR: it respects the operator's own
   * rotation lock, which somebody at a helm may well have on deliberately.
   */
  private fun lockOrientationForFormFactor() {
    requestedOrientation =
      if (smallestDisplayWidthDp() >= TabletSmallestWidthDp) {
        ActivityInfo.SCREEN_ORIENTATION_USER
      } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      }
  }

  /** The short side of the whole display in dp, letterboxing notwithstanding. */
  private fun smallestDisplayWidthDp(): Float {
    // maximumWindowMetrics is API 30, which is minSdk, so there is no fallback
    // branch here any more. It used to call the deprecated defaultDisplay
    // .getRealMetrics() for API 26-29.
    val density = resources.displayMetrics.density
    val bounds = windowManager.maximumWindowMetrics.bounds
    return minOf(bounds.width(), bounds.height()) / density
  }
}

/**
 * Android's own tablet breakpoint, and the one it uses for the `sw600dp`
 * resource qualifier and for ignoring `screenOrientation` from targetSdk 36.
 */
private const val TabletSmallestWidthDp = 600

@Composable
private fun StationApp(viewModel: StationViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  ForceReleaseOnBackground(viewModel)

  Box(Modifier.fillMaxSize().background(DriveColors.surface).safeDrawingPadding()) {
    when (val stage = state.stage) {
      // Nothing, on purpose: the settings that decide which screen this should
      // be have not been read yet. This Box already paints DriveColors.surface
      // and the window background behind it is the same near-black, so the
      // operator sees one continuous dark screen rather than the setup screen
      // flashing up and being replaced. See Stage.Starting.
      is Stage.Starting -> Unit

      is Stage.NeedsServer -> {
        val servers by rememberDiscoveredServers()
        ServerScreen(
          discovered = servers,
          error = state.serverError,
          notice = state.setupNotice,
          lastServer = state.lastServer,
          onChoose = viewModel::useServer,
        )
      }

      is Stage.NeedsToken ->
        AccessScreen(
          server = state.server ?: return@Box,
          state = stage.access,
          onRequest = viewModel::requestAccess,
          onChangeServer = viewModel::changeServer,
        )

      is Stage.Ready -> {
        val view = state.view
        // The layout itself lives in ui/ControlScreen.kt, taking plain values
        // rather than this ViewModel, so its geometry can be measured on a
        // laptop instead of only on a phone connected to the boat.
        if (view != null) {
          ControlScreen(
            view = view,
            thrusterMode = state.thrusterMode,
            trimDeg = state.trimDeg,
            authError = state.authError,
            onArm = viewModel::requestArm,
            onDisarm = viewModel::requestDisarm,
            onThrusterModeChange = viewModel::setThrusterMode,
            onThrusterDirectionChange = viewModel::setThrusterDirection,
            onTrim = viewModel::trim,
            onPortChange = viewModel::setPort,
            onStbdChange = viewModel::setStbd,
            onChangeServer = viewModel::changeServer,
          )
        }
      }
    }
  }
}

/**
 * Force every control back to its safe value when the app leaves the
 * foreground.
 *
 * This is the Android counterpart of the web UI's `visibilitychange` /
 * `pagehide` handlers: a touch interrupted by the Home button, an incoming
 * call, or the screen locking never delivers a pointer-up, so without this a
 * drive could stay commanded by a finger that is no longer there.
 *
 * ON_STOP is deliberately NOT the trigger -- ON_PAUSE fires first and covers
 * every case that matters, including a partially-obscuring dialog.
 *
 * Note this does not disarm. Backgrounding immediately commands safe values,
 * then deliberately stops the heartbeat so the arbiter stale-evicts this
 * station and releases its authority.
 */
@Composable
private fun ForceReleaseOnBackground(viewModel: StationViewModel) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> viewModel.onBackgrounded()
        Lifecycle.Event.ON_RESUME -> viewModel.onForegrounded()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
}

/**
 * Browse for Signal K servers while the setup screen is on show.
 *
 * Scoped to the composition on purpose: discovery holds a multicast lock, which
 * costs battery and is pointless once a server has been chosen. Leaving the
 * setup screen tears it down.
 */
@Composable
private fun rememberDiscoveredServers(): State<List<DiscoveredServer>> {
  val context = LocalContext.current
  return produceState<List<DiscoveredServer>>(initialValue = emptyList(), key1 = context) {
    MdnsDiscovery(context).discover().collect { value = it }
  }
}
