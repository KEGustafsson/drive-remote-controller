package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performSemanticsAction
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.DrivePosition
import io.github.kegustafsson.driveremote.core.HoldPhase
import io.github.kegustafsson.driveremote.core.IntentStatus
import io.github.kegustafsson.driveremote.core.SkContract
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

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

  /**
   * Backgrounding releases every command, but a pause need not cancel the
   * pointer: a contact held through it stayed lit, the readout saying FORWARD
   * while the ViewModel was sending NEUTRAL. The release epoch the ViewModel
   * bumps must release the contact on screen as well.
   */
  @Test
  fun `a forced release lets go of a contact still held on screen`() {
    val reported = mutableListOf<DrivePosition>()
    val epoch = EpochHolder()
    compose.setContent {
      DriveRemoteTheme {
        Box(Modifier.fillMaxSize()) {
          ControlScreen(
            view = armed,
            thrusterMode = ThrusterMode.MANUAL,
            trimDeg = 0.0,
            authError = null,
            onArm = {},
            onDisarm = {},
            onThrusterModeChange = {},
            onThrusterDirectionChange = {},
            onTrim = {},
            onPortChange = { reported += it },
            onStbdChange = {},
            onChangeServer = {},
            releaseEpoch = epoch.value,
          )
        }
      }
    }
    compose.waitForIdle()

    compose.onAllNodesWithContentDescription("FWD", useUnmergedTree = true)[0].performTouchInput {
      down(center)
    }
    compose.waitForIdle()
    assertEquals("the press itself was not reported", DrivePosition.FORWARD, reported.last())
    compose.onNodeWithText("FORWARD").assertExists()

    // The app is paused; releaseAllControls() bumps the epoch. The pointer is
    // never cancelled and never lifted.
    compose.runOnUiThread { epoch.value += 1 }
    compose.waitForIdle()

    assertEquals(DrivePosition.NEUTRAL, reported.last())
    compose.onNodeWithText("FORWARD").assertDoesNotExist()

    // And the finger still on the glass does not press again by itself.
    compose.onAllNodesWithContentDescription("FWD", useUnmergedTree = true)[0].performTouchInput {
      moveBy(Offset(0f, 1f))
    }
    compose.waitForIdle()
    assertEquals(DrivePosition.NEUTRAL, reported.last())
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

  /**
   * Double-tap STOP. The first tap disarms, the arbiter's release comes back and
   * the button flips to DISARMED, and the second tap -- aimed at STOP -- lands on
   * it. Decided from the screen alone that tap ARMED the station, and in HOLD an
   * arm is a hold request. Within the holdover it must still be a stop.
   */
  @Test
  fun `the second tap of a double-tapped STOP does not arm`() {
    var armTaps = 0
    var disarmTaps = 0
    val shown = ViewHolder(armed)
    compose.showKillSwitch(shown, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription("ARMED", substring = true).performClick()
    assertEquals(1, disarmTaps)

    // activeClient "" arrives: the button now offers an arm.
    compose.runOnUiThread { shown.value = readyToArm }
    compose.waitForIdle()
    ShadowSystemClock.advanceBy(Duration.ofMillis(200))
    compose.onNodeWithContentDescription("DISARMED", substring = true).performClick()

    assertEquals("the second tap of a STOP armed the station", 0, armTaps)
    assertEquals(2, disarmTaps)

    // A deliberate arm after the holdover still works.
    ShadowSystemClock.advanceBy(Duration.ofMillis(SkContract.KILL_SWITCH_STOP_HOLDOVER_MS))
    compose.onNodeWithContentDescription("DISARMED", substring = true).performClick()
    assertEquals("the arm was lost for good", 1, armTaps)
  }

  // ---- The kill switch's gesture: its meaning is fixed at touch-down -------

  /**
   * A hurried STOP must not depend on a clean lift. `clickable` fires on the
   * lift and cancels when the finger slides off, so a thumb that lands on STOP
   * and skids away -- the normal way a hand hits a button on a moving boat --
   * sent nothing at all. The STOP goes on the way DOWN.
   */
  @Test
  fun `a STOP fires on touch down, before any lift`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(armed, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription(KillSwitchAny, substring = true).performTouchInput {
      down(center)
    }
    compose.waitForIdle()

    assertEquals("the STOP waited for the finger to lift", 1, disarmTaps)
    assertEquals(0, armTaps)
  }

  /**
   * The rest of a STOP gesture belongs to the STOP. The arbiter's release flips
   * the button to DISARMED while the finger is still down; lifting long after
   * the holdover has expired must still not turn that gesture into an ARM.
   */
  @Test
  fun `the lift of a STOP gesture never arms, however long it is held`() {
    var armTaps = 0
    var disarmTaps = 0
    val shown = ViewHolder(armed)
    compose.showKillSwitch(shown, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription(KillSwitchAny, substring = true).performTouchInput {
      down(center)
    }
    compose.waitForIdle()
    compose.runOnUiThread { shown.value = readyToArm }
    compose.waitForIdle()
    ShadowSystemClock.advanceBy(Duration.ofMillis(SkContract.KILL_SWITCH_STOP_HOLDOVER_MS * 3))

    compose.onNodeWithContentDescription(KillSwitchAny, substring = true).performTouchInput {
      up()
    }
    compose.waitForIdle()

    assertEquals("a STOP gesture armed the station on its lift", 0, armTaps)
    assertEquals(1, disarmTaps)
  }

  /** An ARM is deliberate: nothing on the way down, the arm on a lift inside. */
  @Test
  fun `an ARM waits for the lift and fires on it`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(readyToArm, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    val button = compose.onNodeWithContentDescription(KillSwitchAny, substring = true)
    button.performTouchInput { down(center) }
    compose.waitForIdle()
    assertEquals("an ARM fired on touch down", 0, armTaps)

    button.performTouchInput { up() }
    compose.waitForIdle()
    assertEquals(1, armTaps)
    assertEquals(0, disarmTaps)
  }

  /** A finger that lands on ARM and slides off has changed its mind: nothing. */
  @Test
  fun `sliding off an ARM sends nothing`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(readyToArm, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription(KillSwitchAny, substring = true).performTouchInput {
      down(center)
      moveTo(Offset(center.x, -height.toFloat()))
      up()
    }
    compose.waitForIdle()

    assertEquals(0, armTaps)
    assertEquals(0, disarmTaps)
  }

  /** And sliding off a STOP does not take the STOP back: it has already gone. */
  @Test
  fun `sliding off a STOP keeps the STOP`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(foreign, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    compose.onNodeWithContentDescription(KillSwitchAny, substring = true).performTouchInput {
      down(center)
      moveTo(Offset(center.x, -height.toFloat()))
      up()
    }
    compose.waitForIdle()

    assertEquals(1, disarmTaps)
    assertEquals(0, armTaps)
  }

  /**
   * TalkBack's double-tap and switch access reach the button through its
   * semantics click, not through pointers. That path must exist -- a kill switch
   * a screen-reader user cannot press is a kill switch they do not have -- and
   * must go through the same STOP-holdover policy as a finger.
   */
  @Test
  fun `the accessibility click arms and stops through the same policy`() {
    var armTaps = 0
    var disarmTaps = 0
    val shown = ViewHolder(armed)
    compose.showKillSwitch(shown, onArm = { armTaps++ }, onDisarm = { disarmTaps++ })

    val button = compose.onNodeWithContentDescription(KillSwitchAny, substring = true)
    button.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    button.performSemanticsAction(SemanticsActions.OnClick)
    assertEquals(1, disarmTaps)

    // The release lands; a second accessibility click inside the holdover is
    // still a STOP, exactly as a second finger tap would be.
    compose.runOnUiThread { shown.value = readyToArm }
    compose.waitForIdle()
    ShadowSystemClock.advanceBy(Duration.ofMillis(200))
    button.performSemanticsAction(SemanticsActions.OnClick)
    assertEquals(0, armTaps)
    assertEquals(2, disarmTaps)

    ShadowSystemClock.advanceBy(Duration.ofMillis(SkContract.KILL_SWITCH_STOP_HOLDOVER_MS))
    button.performSemanticsAction(SemanticsActions.OnClick)
    assertEquals(1, armTaps)
  }

  // ---- Commands not reaching the boat -----------------------------------

  /**
   * The plugin is stopped: every intent POST is answered 503, so a press here
   * -- and a STOP -- reaches nothing. The kill switch used to go on reading a
   * healthy "DISARMED. tap to arm" over it. It must say so, with the reason,
   * and still offer the ARM: a retry is harmless, and if it lands the path is
   * back.
   */
  @Test
  fun `a 503 puts the reason on the kill switch and the arm stays available`() {
    var armTaps = 0
    compose.showKillSwitch(
      readyToArm.copy(intentStatus = IntentStatus.UNAVAILABLE),
      onArm = { armTaps++ },
      onDisarm = {},
    )

    compose
      .onNodeWithContentDescription("DISARMED. commands not reaching boat — plugin stopped · tap to arm")
      .assertExists()
    compose.onNodeWithContentDescription("DISARMED. tap to arm").assertDoesNotExist()
    // And the COMMANDS lamp says it in the browser's words.
    compose.onNodeWithText("BLOCKED — plugin not running", useUnmergedTree = true).assertExists()

    compose.onNodeWithContentDescription(KillSwitchAny, substring = true).performClick()
    assertEquals("the arm was withdrawn over a failing path", 1, armTaps)
  }

  /** Armed over a dead path: said, and the tap is still a STOP. */
  @Test
  fun `armed with no network, the kill switch says so and still stops`() {
    var armTaps = 0
    var disarmTaps = 0
    compose.showKillSwitch(
      armed.copy(intentStatus = IntentStatus.NETWORK),
      onArm = { armTaps++ },
      onDisarm = { disarmTaps++ },
    )

    compose
      .onNodeWithContentDescription("ARMED. commands not reaching boat — no network · tap to disarm")
      .assertExists()
    compose.onNodeWithContentDescription(KillSwitchAny, substring = true).performTouchInput {
      down(center)
    }
    compose.waitForIdle()
    assertEquals(1, disarmTaps)
    assertEquals(0, armTaps)
  }

  /** A refused token is named as that, not as a network fault. */
  @Test
  fun `a refused login is named on the kill switch`() {
    compose.showKillSwitch(offline.copy(intentStatus = IntentStatus.AUTH), onArm = {}, onDisarm = {})
    compose
      .onNodeWithContentDescription("OFFLINE. commands not reaching boat — login refused · tap to STOP")
      .assertExists()
  }

  /** And a healthy path leaves the ordinary line alone -- no warning at every launch. */
  @Test
  fun `commands landing, or not yet answered, leave the kill switch line alone`() {
    compose.showKillSwitch(readyToArm.copy(intentStatus = IntentStatus.OK), onArm = {}, onDisarm = {})
    compose.onNodeWithContentDescription("DISARMED. tap to arm").assertExists()
    compose.onNodeWithText("reaching boat", useUnmergedTree = true).assertExists()
  }
}

/**
 * Matches the kill switch in every state these tests put it in: its semantics
 * text is "<label>. <line>", and every line names what a tap does.
 */
private const val KillSwitchAny = "tap"

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

private class EpochHolder {
  var value by mutableStateOf(0)
}

private class ViewHolder(initial: StationView) {
  var value by mutableStateOf(initial)
}

private fun ComposeContentTestRule.showKillSwitch(
  view: StationView,
  onArm: () -> Unit,
  onDisarm: () -> Unit,
) = showKillSwitch(ViewHolder(view), onArm, onDisarm)

private fun ComposeContentTestRule.showKillSwitch(
  view: ViewHolder,
  onArm: () -> Unit,
  onDisarm: () -> Unit,
) {
  setContent {
    DriveRemoteTheme {
      Box(Modifier.fillMaxSize()) {
        ControlScreen(
          view = view.value,
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
    // HH's own report, which is what the HOLD panel's "HOLDING" caption reads
    // (StationView.holdEngaged). Armed AND in hold AND its FSM in HOLDING -- the
    // pair alone is also what ARMED_IDLE publishes -- so a HOLD-mode rendering
    // here is the engaged one.
    hhMode = "hold",
    hhFsmState = SkContract.HH_FSM_HOLDING,
    holdPhase = HoldPhase.ENGAGED,
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
