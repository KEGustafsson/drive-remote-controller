package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.SkContract
import io.github.kegustafsson.driveremote.core.SkValueStore
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.ThrusterMode
import io.github.kegustafsson.driveremote.core.deriveStationView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An open socket that has stopped delivering, drawn end to end: store -> view
 * -> screen, with no hand-built [StationView] in between.
 *
 * The Signal K host losing power, or the Wi-Fi path breaking without a FIN,
 * leaves the socket OPEN and the retained `activeClient` naming this station.
 * Judged on the socket, this screen went on saying ARMED, the LINK lamp
 * "connected" and CONTROL "this app", over a boat it could not see
 * (AGENTS.md non-negotiable 6). It must read as a closed socket does: the kill
 * switch OFFLINE with its tap a STOP, the LINK lamp saying why, the derived
 * lamps greyed, and every commanding control inert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class SilentStreamScreenTest {

  @get:Rule val compose = createComposeRule()

  private val me = "ui-me"

  /**
   * Armed and holding, everything arriving -- then the arbiter's publish stops,
   * and stays stopped past the window. With [unitsStillArriving], RX and HH go on
   * publishing straight to the server (its plugin stopped, say): the units are
   * then as fresh as ever, and only the arbiter's silence can say the stream is
   * no longer this station's link.
   */
  private fun silentView(unitsStillArriving: Boolean = false): StationView {
    val lastHeard = 10_000L
    val store = SkValueStore()
    store.apply(
      listOf(
        SkContract.RX_LINK_UP to true,
        SkContract.RX_LINK_OK to true,
        SkContract.RX_MASTER_ENABLE to true,
        SkContract.RX_PORT_STATE to "neutral",
        SkContract.RX_STBD_STATE to "neutral",
        SkContract.RX_PORT_SOURCE to "plugin",
        SkContract.RX_STBD_SOURCE to "plugin",
        SkContract.HH_LINK_UP to true,
        SkContract.HH_ARMED to true,
        SkContract.HH_MODE to "hold",
        SkContract.HH_FSM_STATE to SkContract.HH_FSM_HOLDING,
        SkContract.HH_SOURCE to "plugin",
        SkContract.HH_SETPOINT to 96.0,
        SkContract.PLUGIN_RX_LIVE to true,
        SkContract.PLUGIN_HH_LIVE to true,
        SkContract.PLUGIN_ACTIVE_CLIENT to me,
      ),
      lastHeard,
    )
    val now = lastHeard + SkContract.SERVER_STREAM_STALE_MS + 1
    if (unitsStillArriving) {
      store.apply(listOf(SkContract.RX_LINK_UP to true, SkContract.HH_LINK_UP to true), now)
    }
    return deriveStationView(
      store = store,
      connectionState = ConnectionState.OPEN,
      myClientId = me,
      nowMs = now,
      everConnected = true,
      linkAttemptStartedMs = 0,
      streamOpenedAtMs = 5_000,
    )
  }

  private fun ComposeContentTestRule.show(
    view: StationView,
    mode: ThrusterMode = ThrusterMode.MANUAL,
    onArm: () -> Unit = {},
    onDisarm: () -> Unit = {},
  ) {
    setContent {
      DriveRemoteTheme {
        Box(Modifier.fillMaxSize()) {
          ControlScreen(
            view = view,
            thrusterMode = mode,
            trimDeg = 20.0,
            authError = null,
            onArm = onArm,
            onDisarm = onDisarm,
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
    waitForIdle()
  }

  @Test
  fun `the kill switch reads OFFLINE and its tap is a STOP`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.show(silentView(), onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription("OFFLINE. ", substring = true).performClick()
    assertEquals("a silent stream's kill switch did not send a stop", 1, disarmTaps)
    assertEquals(0, armTaps)
  }

  @Test
  fun `the LINK lamp says no data is arriving, and CONTROL is not claimed`() {
    compose.show(silentView())
    compose.onNodeWithContentDescription("LINK: no data from server").assertExists()
    compose.onNodeWithContentDescription("LINK: connected").assertDoesNotExist()
    // "this app" is still the reading -- the retained activeClient -- but only
    // dimmed; the kill switch, not this lamp, is what says ARMED, and it doesn't.
    compose.onNodeWithContentDescription("ARMED. ", substring = true).assertDoesNotExist()
  }

  @Test
  fun `every commanding control is inert`() {
    compose.show(silentView())
    assertNoLiveContacts()
  }

  /** The case the units' own freshness cannot catch. */
  @Test
  fun `every commanding control is inert with the units still arriving`() {
    compose.show(silentView(unitsStillArriving = true))
    assertNoLiveContacts()
    compose.onNodeWithContentDescription("OFFLINE. ", substring = true).assertExists()
  }

  private fun assertNoLiveContacts() {
    for (label in listOf("FWD", "REV", "PORT", "STBD")) {
      assertEquals(
        "'$label' is live on a silent stream",
        0,
        compose.onAllNodesWithContentDescription(label, useUnmergedTree = true)
          .fetchSemanticsNodes()
          .size,
      )
    }
  }

  @Test
  fun `the trim steps are frozen, not live`() {
    compose.show(silentView(unitsStillArriving = true), mode = ThrusterMode.HOLD)
    compose.onNodeWithText("+10°").assertIsNotEnabled()
    compose.onNodeWithText("+1°").assertIsNotEnabled()
  }
}
