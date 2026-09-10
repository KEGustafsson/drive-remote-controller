package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.ThrusterDirection
import io.github.kegustafsson.driveremote.core.ThrusterMode
import io.github.kegustafsson.driveremote.core.UnitLiveness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two fail-safe properties that only exist once the screen is real: a press
 * whose release is destroyed along with the button, and a STOP whose tap has to
 * do something whatever the telemetry says.
 *
 * Neither can be reached from :core. The first is a composition lifecycle event
 * and the second is a tap; both were reasoned about and wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = FailSafeReferencePhone)
class FailSafeControlsTest {

  @get:Rule val compose = createComposeRule()

  // ---- The direction held across a mode change ---------------------------

  /**
   * Hold PORT, then select HOLD with the other thumb.
   *
   * The chooser stays live while a contact is held (it commands nothing), so
   * this is an ordinary two-fingered thing to do -- and it takes the PORT/STBD
   * contacts off the screen. The button's own release fires, but the effect that
   * REPORTS a release is disposed in the same pass, so without the
   * DisposableEffect in ThrusterControl nothing tells the ViewModel the thumb is
   * gone: a PORT thrust stands, republished on every 250 ms heartbeat, with no
   * finger anywhere near the glass.
   */
  @Test
  fun `selecting HOLD while PORT is held reports the release`() {
    val reported = mutableListOf<ThrusterDirection>()
    val mode = compose.showThruster(reported)

    compose.onNodeWithContentDescription("PORT", useUnmergedTree = true).performTouchInput {
      down(center)
    }
    compose.waitForIdle()
    assertEquals("the press itself was not reported", ThrusterDirection.PORT, reported.last())

    // The other thumb, on the chooser. The pointer on PORT is never lifted.
    compose.runOnUiThread { mode.value = ThrusterMode.HOLD }
    compose.waitForIdle()

    assertEquals(
      "a held direction survived the mode change -- it would keep publishing unpressed",
      ThrusterDirection.OFF,
      reported.last(),
    )
  }

  /**
   * The dangerous half. Coming back to MANUAL must not replay the direction that
   * was held on the way out: MANUAL has no firmware dwell by design (SAFETY.md
   * thruster invariant 7), so HH acts on it at once.
   */
  @Test
  fun `returning to MANUAL emits no phantom thrust`() {
    val reported = mutableListOf<ThrusterDirection>()
    val mode = compose.showThruster(reported)

    compose.onNodeWithContentDescription("PORT", useUnmergedTree = true).performTouchInput {
      down(center)
    }
    compose.waitForIdle()

    compose.runOnUiThread { mode.value = ThrusterMode.HOLD }
    compose.waitForIdle()
    val afterLeaving = reported.size

    compose.runOnUiThread { mode.value = ThrusterMode.MANUAL }
    compose.waitForIdle()

    val onTheWayBack = reported.drop(afterLeaving)
    assertFalse(
      "returning to MANUAL replayed a thrust nobody is pressing: $onTheWayBack",
      onTheWayBack.contains(ThrusterDirection.PORT) ||
        onTheWayBack.contains(ThrusterDirection.STBD),
    )
    assertEquals(ThrusterDirection.OFF, reported.last())
  }

  // ---- The kill switch's tap ---------------------------------------------

  /**
   * Offline, the kill switch promises "tap to STOP -- disarm always works". It
   * has to be true.
   *
   * `armed` and `foreign` are derived from `activeClient`, which is last-known
   * telemetry from a store that deliberately keeps its values across a
   * disconnect. Deciding the tap from those meant that a station which had never
   * seen an arm -- or had seen the token released before the link dropped --
   * offered a STOP that did nothing at all, while a browser might well be holding
   * the arm on the other side of the dropped link.
   */
  @Test
  fun `offline, the kill switch tap sends a stop`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(offline, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription("OFFLINE", substring = true).performClick()

    assertEquals("offline tap did not send a stop", 1, disarmTaps)
    assertEquals("offline tap tried to arm", 0, armTaps)
  }

  /** The same reasoning with no unit answering: a stop, never a dead button. */
  @Test
  fun `with no unit responding the tap still sends a stop`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(cannotArm, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription("CANNOT ARM", substring = true).performClick()

    assertEquals(1, disarmTaps)
    assertEquals(0, armTaps)
  }

  /** Another station holding the token: tap one stops it. Unchanged, pinned. */
  @Test
  fun `a foreign arm is stopped rather than taken silently`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(foreign, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription("IN USE", substring = true).performClick()

    assertEquals(1, disarmTaps)
    assertEquals(0, armTaps)
  }

  /**
   * And the case the rewrite must not have broken: from a state that genuinely
   * supports arming, the tap still arms. "Everything else is a stop" would be a
   * poor fix if it had quietly removed the arm.
   */
  @Test
  fun `connected with a unit answering, the tap arms`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(readyToArm, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription("DISARMED", substring = true).performClick()

    assertEquals("the arm was lost", 1, armTaps)
    assertEquals(0, disarmTaps)
  }

  /** Armed: the tap disarms, which is the ordinary case. */
  @Test
  fun `armed, the tap disarms`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(armed, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription("ARMED", substring = true).performClick()

    assertEquals(1, disarmTaps)
    assertEquals(0, armTaps)
  }
}

private const val FailSafeReferencePhone = "w360dp-h780dp-xxhdpi"

/**
 * Renders the control screen with the thruster mode hoisted, so a test can move
 * it the way the ViewModel does -- without having to inject a second pointer on
 * to the chooser, which is not what these cases are about.
 */
private class ModeHolder(initial: ThrusterMode) {
  var value by mutableStateOf(initial)
}

private fun ComposeContentTestRule.showThruster(
  reported: MutableList<ThrusterDirection>
): ModeHolder {
  val mode = ModeHolder(ThrusterMode.MANUAL)
  setContent {
    DriveRemoteTheme {
      Box(Modifier.fillMaxSize()) {
        ControlScreen(
          view = armed,
          thrusterMode = mode.value,
          trimDeg = 0.0,
          authError = null,
          onArm = {},
          onDisarm = {},
          onThrusterModeChange = { mode.value = it },
          onThrusterDirectionChange = { reported += it },
          onTrim = {},
          onPortChange = {},
          onStbdChange = {},
          onChangeServer = {},
        )
      }
    }
  }
  waitForIdle()
  return mode
}

private fun ComposeContentTestRule.showKillSwitch(
  view: StationView,
  onArm: () -> Unit,
  onDisarm: () -> Unit,
) {
  setContent {
    DriveRemoteTheme {
      Box(Modifier.fillMaxSize()) {
        ControlScreen(
          view = view,
          thrusterMode = ThrusterMode.MANUAL,
          trimDeg = 0.0,
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

/** Holding the token with both units answering: everything is commandable. */
private val armed =
  StationView(
    connectionState = ConnectionState.OPEN,
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
    reversalPending = false,
    rxLinkUp = true,
    rxLinkOk = true,
    rxMasterEnable = true,
    hhArmed = true,
    thrusterState = "off",
  )

/** Nobody armed, both units answering: an arm is genuinely on offer. */
private val readyToArm =
  armed.copy(
    controlState = ControlState.NONE,
    driveCommandable = false,
    thrusterCommandable = false,
    portSource = CommandSource.NONE,
    stbdSource = CommandSource.NONE,
    thrusterSource = CommandSource.NONE,
    hhArmed = false,
  )

/**
 * The read socket is down. Every liveness is OFFLINE because nothing can arrive
 * to say otherwise, and `activeClient` reads as last-known -- here, nobody. The
 * station may still be commanding: the intent POST does not use this socket.
 */
private val offline =
  readyToArm.copy(
    connectionState = ConnectionState.CLOSED,
    rxLiveness = UnitLiveness.OFFLINE,
    hhLiveness = UnitLiveness.OFFLINE,
    canArm = false,
  )

/** Connected, but neither board is answering. */
private val cannotArm =
  readyToArm.copy(
    rxLiveness = UnitLiveness.STALE,
    hhLiveness = UnitLiveness.STALE,
    canArm = false,
  )

/** Another station holds the arm token. */
private val foreign = readyToArm.copy(controlState = ControlState.OTHER)
