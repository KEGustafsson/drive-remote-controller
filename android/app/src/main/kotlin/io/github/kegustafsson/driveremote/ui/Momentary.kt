package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * One momentary, spring-return button -- this app's equivalent of one contact
 * on TX's physical 3-position switches, and the Compose counterpart of
 * `useMomentaryButton.ts`.
 *
 * ## Why not `clickable`
 *
 * `Modifier.clickable` is a single-pointer, click-on-release abstraction. It
 * would break the flagship case outright: port FORWARD and starboard REVERSE
 * held by two different fingers at the same time. Compose hit-tests each
 * pointer independently and delivers it to the `pointerInput` of whatever
 * composable it landed on, so giving each button its own gesture loop makes
 * simultaneous presses work **by construction** -- there is no shared "currently
 * pressed" state anywhere to serialise them. That is the same reason
 * `useMomentaryButton` tracks a `pointerId` per button rather than a singleton.
 *
 * ## Release is forced on more than a clean lift
 *
 * The `finally` block runs when the gesture ends for ANY reason -- a normal
 * lift, a cancellation, the pointer being consumed by a parent, or the whole
 * gesture loop being torn down. Tearing down is also how the [enabled]
 * force-release works: [enabled] is a `pointerInput` key, so flipping it to
 * false restarts the block and the `finally` fires, releasing a press that was
 * already in flight when authority was withdrawn (disarm, or the unit going
 * offline). A button that is visually dead must never still be commanding.
 *
 * The remaining case -- the app leaving the foreground mid-press -- cannot be
 * seen from here and is handled by the lifecycle observer in MainActivity,
 * which calls `releaseAllControls()`. RX's own 1 s staleness watchdog is the
 * final backstop under all of them.
 *
 * @param onPressedChange called with true on press and false on release. Must
 *   be idempotent: false may arrive without a preceding true.
 */
@Composable
fun Modifier.momentaryPress(enabled: Boolean, onPressedChange: (Boolean) -> Unit): Modifier {
  // rememberUpdatedState so a recomposition with a new lambda does not restart
  // the gesture loop -- restarting mid-press would release the button.
  val currentOnPressedChange by rememberUpdatedState(onPressedChange)

  return this.pointerInput(enabled) {
    if (!enabled) {
      // Nothing to await; the caller has already been told to release by the
      // teardown of the previous block.
      return@pointerInput
    }
    awaitEachGesture {
      // requireUnconsumed = false: we want this press even if an ancestor has
      // seen the event, because nothing above a control button legitimately
      // claims it first.
      val down = awaitFirstDown(requireUnconsumed = false)
      currentOnPressedChange(true)
      try {
        var pressed = true
        while (pressed) {
          val event = awaitPointerEvent()
          val change = event.changes.firstOrNull { it.id == down.id }
          // A pointer that vanished from the event stream is gone: treat it as
          // released rather than waiting for an up that will never come.
          //
          // isConsumed is the other way a press ends without a lift: an
          // ancestor (a scroll container, a pager) has claimed the gesture as
          // a drag. Compose does NOT cancel this loop when that happens -- the
          // pointer stays `pressed` for the whole drag -- so without this the
          // docstring's promise above would be false and a drag beginning on a
          // live contact would command the machine until the finger lifted.
          // No ancestor of these buttons currently scrolls (MainActivity keeps
          // every live control out of the scrolling region, which is the
          // primary defence); this is the guard that makes reintroducing one
          // safe rather than silently dangerous.
          pressed = change != null && change.pressed && !change.isConsumed
        }
      } finally {
        currentOnPressedChange(false)
      }
    }
  }
}

/**
 * The two contacts of one drive side.
 *
 * The mapping to a position is
 * [io.github.kegustafsson.driveremote.core.fromSwitch] -- including the
 * both-pressed case, which a single physical switch cannot produce but two
 * touch targets can, and which must fail to NEUTRAL.
 */
class DriveContacts {
  var forwardPressed by mutableStateOf(false)
  var reversePressed by mutableStateOf(false)
}

@Composable fun rememberDriveContacts(): DriveContacts = remember { DriveContacts() }

/**
 * The two thruster buttons.
 *
 * A separate type from [DriveContacts] on purpose. These map to PORT/STBD
 * thrust, not to FORWARD/REVERSE gear, and the two machines have genuinely
 * different safe values -- NEUTRAL for a drive, OFF for a thruster. Sharing one
 * state class would have meant reading `forwardPressed` to decide whether to
 * thrust to port, which is exactly the kind of quiet mislabelling that turns
 * into a wrong-direction bug during a later edit.
 */
class ThrusterContacts {
  var portPressed by mutableStateOf(false)
  var stbdPressed by mutableStateOf(false)
}

@Composable fun rememberThrusterContacts(): ThrusterContacts = remember { ThrusterContacts() }
