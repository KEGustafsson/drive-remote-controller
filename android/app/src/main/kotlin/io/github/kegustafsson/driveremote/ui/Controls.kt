package io.github.kegustafsson.driveremote.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DrivePosition
import io.github.kegustafsson.driveremote.core.HoldPhase
import io.github.kegustafsson.driveremote.core.HoldStall
import io.github.kegustafsson.driveremote.core.KillSwitchTap
import io.github.kegustafsson.driveremote.core.KillSwitchTapPolicy
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.SkContract
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.ThrusterDirection
import io.github.kegustafsson.driveremote.core.ThrusterMode
import io.github.kegustafsson.driveremote.core.formatHeading
import io.github.kegustafsson.driveremote.core.formatTrim
import io.github.kegustafsson.driveremote.core.fromSwitch

/**
 * How every momentary contact button -- the drives' FWD/REV and the thruster's
 * PORT/STBD alike -- decides its height.
 *
 * Three modifiers, and the ORDER of them is load-bearing:
 *
 * 1. `requiredHeightIn(min)` is the floor, and the required form is what makes
 *    it a floor at all -- see [ContactButtonMinHeight]. It ignores the incoming
 *    constraint entirely, which is the point and also the trap: it discards the
 *    exact *minimum* a `weight(...)` parent hands down, so a bare
 *    `weight(1f).requiredHeightIn(min = ...)` yields a button that keeps its
 *    floor and **never grows**, with the surplus left as dead space. That is
 *    the bug this ordering exists to avoid.
 * 2. `heightIn(max)` is the ceiling ([ContactButtonMaxHeight]), applied second
 *    so it is constrained by -- rather than constraining -- the floor above it.
 * 3. `fillMaxHeight()` last, which is what actually claims the space: it makes
 *    the height exact at whatever maximum survived steps 1 and 2.
 *
 * The result is `clamp(what the parent offered, floor, ceiling)`. Offered less
 * than the floor, the button keeps the floor and overflows visibly; offered more
 * than the ceiling, it stops growing and the parent's `Arrangement.Center`
 * leaves the slack around it.
 */
private fun Modifier.contactHeight(helm: HelmScale): Modifier =
  this.requiredHeightIn(min = helm.size(ContactButtonMinHeight))
    .heightIn(max = helm.size(ContactButtonMaxHeight))
    .fillMaxHeight()

/**
 * The kill switch: arm/disarm, and the reasons arming is unavailable.
 *
 * The one control held to full parity with TX's latching enable. It **never
 * greys out** and its disarm is **never gated** -- not on the socket, not on
 * unit liveness, not on anything. Stopping must always work, and its disarm
 * travels over HTTP independently of the read stream.
 *
 * Offline renders as its own state, never as DISARMED. The stream is only the
 * read side; the intent heartbeat keeps POSTing regardless, so this station may
 * genuinely still hold the token and still be commanding while unable to see
 * the boat. Showing a confident "DISARMED" there would be a lie in the most
 * dangerous possible direction.
 *
 * **Starting up is not offline, though, and used to be drawn as it.** The first
 * socket of a session takes a few hundred milliseconds to open, and for those
 * frames this rendered the full amber OFFLINE panel before flipping to
 * DISARMED -- a warning on the STOP button at every single launch. A warning
 * that always fires is one the operator stops reading, which costs exactly the
 * times it means something. [LinkPhase.CONNECTING] is the same fact said
 * calmly, and it is safe to say calmly because this station cannot be armed
 * before its stream is first live (see [LinkPhase]). The moment a session HAS
 * been live, every later gap is OFFLINE with no grace at all -- and "live" is
 * judged on the arbiter's publish ARRIVING, so an open socket that has gone
 * silent is OFFLINE too, not a confident ARMED over a boat it cannot see.
 */
/** Identifies the kill switch whatever it currently says. */
const val KillSwitchTag = "killSwitch"

@Composable
fun KillSwitch(view: StationView, onArm: () -> Unit, onDisarm: () -> Unit, modifier: Modifier = Modifier) {
  val helm = LocalHelmScale.current
  val foreign = view.controlState == ControlState.OTHER
  val label: String
  var sub: String
  val colour: Color

  when {
    // Before OFFLINE, and only ever true before this session's stream is first live.
    // Deliberately the DISARMED grey: the transition into DISARMED a moment
    // later is then a change of words rather than a change of colour, which is
    // what stops the launch reading as an alarm going off and clearing.
    view.linkPhase == LinkPhase.CONNECTING -> {
      label = "CONNECTING"
      sub = "reaching the boat — disarm always works"
      colour = DriveColors.disarmed
    }
    !view.connected -> {
      label = "OFFLINE"
      sub = "tap to STOP — disarm always works"
      colour = DriveColors.warn
    }
    view.armed -> {
      label = "ARMED"
      sub =
        if (view.missingUnits.isEmpty()) "tap to disarm"
        else "tap to disarm · ${view.missingUnits.joinToString(" + ")} not responding"
      colour = DriveColors.armed
    }
    foreign -> {
      label = "IN USE"
      sub = "another station is armed — tap to STOP it, then tap again to take over"
      colour = DriveColors.warn
    }
    !view.canArm -> {
      label = "CANNOT ARM"
      sub = "${view.missingUnits.joinToString(" + ")} not responding"
      colour = DriveColors.disarmed
    }
    else -> {
      label = "DISARMED"
      sub = "tap to arm"
      colour = DriveColors.disarmed
    }
  }

  // Arming is the narrow case; STOP is everything else.
  //
  // Stated this way round on purpose. The obvious phrasing -- disarm if
  // `armed || foreign`, else arm -- reads its condition from `activeClient`,
  // which is last-known telemetry from a store that deliberately keeps its
  // values across a disconnect. Offline, or before the first delta has landed,
  // that is a guess, and the tap it produced was a silent no-op: a browser could
  // hold the arm while this phone's stream was down, and the button promising
  // "tap to STOP -- disarm always works" did nothing at all.
  //
  // So an ARM is offered only from a state that positively supports one --
  // connected, nobody holding the token, a unit answering -- and every other
  // state taps through to disarm. A disarm nobody needed costs nothing: it is a
  // universal stop, the arbiter takes it as an edge, and it travels over HTTP
  // independently of the read socket. SAFETY.md: disarm is never gated on
  // anything.
  val canOfferArm = view.connected && !view.armed && !foreign && view.canArm

  // Commands not reaching the boat is said HERE, on the button, and not only on
  // the COMMANDS lamp: this is what the operator reads before trusting a tap to
  // do anything, and "DISARMED -- tap to arm" over a path that reaches nothing
  // is a healthy face on a broken one (a stopped plugin answering 503, a refused
  // token, no network). It replaces the line rather than adding one, so the
  // button grows by at most the line's wrap. What a tap DOES is unchanged and
  // still said: an ARM stays on offer -- a retry is harmless, and if it lands
  // the path is back -- and a STOP stays a STOP.
  view.commandsNotReaching?.let { failing ->
    sub =
      "$failing · " +
        when {
          canOfferArm -> "tap to arm"
          view.armed && view.connected -> "tap to disarm"
          else -> "tap to STOP"
        }
  }

  // ...and a tap aimed at STOP stays a STOP. Deciding from what is on screen
  // when the click fires is not enough: double-tap STOP, the arbiter's release
  // is back in milliseconds, the button flips to "tap to arm", and the second
  // tap ARMS -- which in HOLD is a hold request. KillSwitchTapPolicy (:core)
  // keeps a tap a STOP for KILL_SWITCH_STOP_HOLDOVER_MS after the button last
  // meant one, as the browser's kill switch does. The flip is stamped from a
  // SideEffect, which runs in the frame that draws it, before any tap on it.
  val meansStop = !canOfferArm
  val tapPolicy = remember { KillSwitchTapPolicy() }
  SideEffect { tapPolicy.observe(meansStop, SystemClock.elapsedRealtime()) }

  // Read through rememberUpdatedState so the gesture loop below, which is keyed
  // on nothing and so never restarts mid-press, still sees what the button
  // means NOW and calls the current callbacks.
  val currentMeansStop by rememberUpdatedState(meansStop)
  val currentOnArm by rememberUpdatedState(onArm)
  val currentOnDisarm by rememberUpdatedState(onDisarm)
  val act: (KillSwitchTap?) -> Unit = { tap ->
    when (tap) {
      KillSwitchTap.ARM -> currentOnArm()
      KillSwitchTap.DISARM -> currentOnDisarm()
      null -> Unit
    }
  }

  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(colour)
      // Not `clickable`, which acts on the LIFT and cancels when the finger
      // slides off: a thumb that lands on STOP and skids away -- the ordinary
      // way a hand meets a button on a moving boat -- sent nothing at all. The
      // gesture's meaning is fixed at touch-down instead (KillSwitchTapPolicy's
      // onPress / onRelease): a STOP goes out on the way down and the rest of
      // that gesture is spent, so its lift can never arm; an ARM waits for a
      // lift inside the button and a slide-off sends nothing.
      .pointerInput(Unit) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          down.consume()
          act(tapPolicy.onPress(currentMeansStop, SystemClock.elapsedRealtime()))
          var liftedInside = false
          try {
            while (true) {
              val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) {
                liftedInside = !change.isOutOfBounds(size, extendedTouchPadding)
                change.consume()
                break
              }
              // Slid off: the gesture is over as far as an ARM is concerned,
              // exactly as `clickable` treats it. A STOP has already gone.
              if (change.isOutOfBounds(size, extendedTouchPadding)) break
              change.consume()
            }
          } finally {
            // Also reached on cancellation -- the button leaving the screen
            // mid-press -- with liftedInside false, which sends nothing.
            act(tapPolicy.onRelease(currentMeansStop, SystemClock.elapsedRealtime(), liftedInside))
          }
        }
      }
      .padding(vertical = helm.size(12.dp))
      // The same button for TalkBack's double-tap and switch access, which
      // arrive as a semantics click rather than as pointers. A whole tap at
      // once, through the same policy -- so the STOP holdover holds there too.
      .semantics(mergeDescendants = true) {
        role = Role.Button
        contentDescription = "$label. $sub"
        onClick {
          act(tapPolicy.onTap(currentMeansStop, SystemClock.elapsedRealtime()))
          true
        }
      }
      .testTag(KillSwitchTag),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(label, fontSize = helm.text(30.sp), fontWeight = FontWeight.Bold, color = DriveColors.ink)
    // Two lines, whatever the state says. "tap to arm" is one line and "tap to
    // disarm · drive unit not responding" can be two, so a sub line that took
    // only what it needed made this button change height on the ARM itself --
    // and moved every control below it, under a thumb, at the moment the
    // operator was reaching for one. The reservation is what keeps the screen
    // still; see ControlPositionStabilityTest.
    ReservedLines(lines = 2, fontSize = helm.text(13.sp)) {
      Text(
        sub,
        fontSize = helm.text(13.sp),
        color = DriveColors.ink.copy(alpha = 0.85f),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = helm.size(12.dp)),
      )
    }
  }
}

/**
 * Space for [lines] lines of text at [fontSize], whether or not [content] needs
 * them, with the content centred in it.
 *
 * How every state-dependent message on the control screen keeps the controls
 * still: the room is reserved by an empty paragraph of the same type, so it
 * tracks the system font scale exactly rather than being a dp guess that is
 * right at 1.0x and wrong at 1.3x. Content that needs MORE than the reservation
 * still gets it -- a wrapped warning is not truncated to keep a layout tidy --
 * so this holds the geometry steady in every ordinary state and only gives way
 * at extreme font scales, where showing the whole message matters more.
 */
@Composable
private fun ReservedLines(
  lines: Int,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  contentAlignment: Alignment = Alignment.Center,
  /** Extra room above and below the reserved lines, for content drawn in a band. */
  reservePadding: Dp = 0.dp,
  content: @Composable () -> Unit,
) {
  // One line height for the reservation and everything drawn in it, or the
  // reservation measures one thing and the message another. Tighter than the
  // theme's, too: Material's body style sets 24 sp lines, which on 12-13 sp
  // text is double spacing -- and every reserved line is paid for on every
  // screen, in every state, out of the drive contacts.
  CompositionLocalProvider(
    LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(lineHeight = TightLineHeight))
  ) {
    Box(modifier, contentAlignment = contentAlignment) {
      Text(
        "\n".repeat(lines - 1),
        fontSize = fontSize,
        minLines = lines,
        modifier = Modifier.padding(vertical = reservePadding).clearAndSetSemantics {},
      )
      content()
    }
  }
}

/**
 * Line height for small text on the control screen: the text's own size plus a
 * little, rather than the theme's fixed 24 sp.
 */
private val TightLineHeight = 1.3.em

/**
 * One drive: momentary FORWARD / REVERSE, spring-return.
 *
 * Greyed and inert whenever a press could not reach the machine -- disarmed,
 * stream down, or RX not answering. A control that cannot move its machine must
 * not look like it can. This is deliberately NOT parity with TX, whose physical
 * switches are always there to move and always show their true position; a
 * glass button has no true position, so a pressable-looking button that
 * commands nothing would just be misleading.
 *
 * The caller is expected to hand this a BOUNDED height -- `fillMaxHeight()`
 * inside a parent that knows how tall it is. The two contacts are weighted, so
 * they share whatever that height leaves after the label and the readout, up to
 * [ContactButtonMaxHeight]; past the ceiling the column centres them and the
 * slack goes above and below, keeping FWD and REV adjacent under one thumb
 * rather than pushed to opposite edges of a tablet.
 *
 * [overflowProbe] is applied to the REV contact -- the bottom-most live thing
 * in this column -- so the screen can tell whether a control has been pushed off
 * the bottom. It has to be the button rather than this column, because the
 * column's own height is exactly what its parent gave it whether or not the
 * contacts inside overflowed it.
 */
@Composable
fun DriveControl(
  label: String,
  enabled: Boolean,
  overriddenBy: String?,
  onPositionChange: (DrivePosition) -> Unit,
  modifier: Modifier = Modifier,
  overflowProbe: Modifier = Modifier,
) {
  val helm = LocalHelmScale.current
  val contacts = rememberDriveContacts()

  // Report the mapped position whenever either contact changes. fromSwitch is
  // the shared truth table, so both-pressed fails to NEUTRAL here exactly as it
  // does in the firmware and the browser.
  LaunchedEffect(contacts.forwardPressed, contacts.reversePressed, enabled) {
    onPositionChange(
      if (!enabled) DrivePosition.NEUTRAL
      else fromSwitch(contacts.forwardPressed, contacts.reversePressed)
    )
  }

  val position =
    if (!enabled) DrivePosition.NEUTRAL
    else fromSwitch(contacts.forwardPressed, contacts.reversePressed)

  Column(
    modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      label.uppercase(),
      fontSize = helm.text(13.sp),
      lineHeight = TightLineHeight,
      color = DriveColors.inkMuted,
    )

    ContactButton(
      text = "FWD",
      pressed = contacts.forwardPressed,
      activeColour = DriveColors.forward,
      enabled = enabled,
      onPressedChange = { contacts.forwardPressed = it },
      modifier = Modifier.fillMaxWidth().weight(1f, fill = false).contactHeight(helm),
    )

    Text(
      when (position) {
        DrivePosition.FORWARD -> "FORWARD"
        DrivePosition.REVERSE -> "REVERSE"
        DrivePosition.NEUTRAL -> "NEUTRAL"
      },
      fontSize = helm.text(15.sp),
      lineHeight = TightLineHeight,
      fontWeight = FontWeight.Bold,
      color = if (enabled) DriveColors.ink else DriveColors.inkMuted,
      modifier = Modifier.padding(vertical = helm.size(6.dp)),
    )

    ContactButton(
      text = "REV",
      pressed = contacts.reversePressed,
      activeColour = DriveColors.reverse,
      enabled = enabled,
      onPressedChange = { contacts.reversePressed = it },
      modifier = overflowProbe.fillMaxWidth().weight(1f, fill = false).contactHeight(helm),
    )

    // Only shown when we ARE the armed controller and something outranks us --
    // exactly the case where a press here visibly does nothing. Its line is
    // held open when there is nothing to say: this column centres its contents,
    // so a note that came and went moved both contacts under the thumb at the
    // moment TX or the local switch took over. DriveBankMinHeight already
    // budgets for this line, so reserving it costs the floor nothing.
    ReservedLines(
      lines = 1,
      fontSize = helm.text(12.sp),
      modifier = Modifier.padding(top = helm.size(4.dp)),
    ) {
      if (overriddenBy != null) {
        Text(
          "controlled by $overriddenBy",
          fontSize = helm.text(12.sp),
          color = DriveColors.warn,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

@Composable
private fun ContactButton(
  text: String,
  pressed: Boolean,
  activeColour: Color,
  enabled: Boolean,
  onPressedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val background =
    when {
      !enabled -> DriveColors.disarmed.copy(alpha = 0.4f)
      pressed -> activeColour
      else -> DriveColors.surfaceRaised
    }

  Box(
    modifier
      .clip(RoundedCornerShape(10.dp))
      .background(background)
      .border(1.dp, DriveColors.border, RoundedCornerShape(10.dp))
      .momentaryPress(enabled, onPressedChange = onPressedChange)
      .semantics {
        role = Role.Button
        contentDescription = if (enabled) text else "$text, unavailable"
      },
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      fontSize = LocalHelmScale.current.text(26.sp),
      fontWeight = FontWeight.Bold,
      color = if (enabled) DriveColors.ink else DriveColors.inkMuted,
    )
  }
}

/**
 * The bow thruster: MANUAL direct thrust, or trimming the held heading.
 *
 * MANUAL has no dwell, no anti-chatter and no deadband -- a person is holding
 * the button and watching the boat. The only delay is the thruster control
 * box's own measured ~1.75 s anti-reversal interlock, which is stated on screen
 * rather than left to look like a dead button.
 *
 * `view.thrusterCommandable` greys and disables everything that COMMANDS the
 * thruster -- the PORT/STBD contacts and the trim steps. The MANUAL/HOLD
 * chooser is deliberately outside that gate: it commands nothing, it only says
 * which gate the next arm will open, and choosing that is something the
 * operator does BEFORE arming rather than arming into whichever mode happens to
 * be showing. The chip that is lit is the whole statement of which mode the arm
 * will open. There used to be a line under the panel spelling it out while
 * disarmed ("HOLD selected — starts holding when you arm"); the owner removed
 * it, and it was also what moved every drive contact down a line on each
 * DISARM and back up on each ARM.
 *
 * **Nothing in this panel changes its height with state.** The body is the same
 * height in both modes, and every notice -- a refusal, another source holding
 * the thruster, a reversal waiting on the interlock -- shares one reserved line
 * at the bottom. The drives sit directly below this panel, so a line appearing
 * here was a drive contact moving under a thumb; see
 * ControlPositionStabilityTest.
 */
@Composable
fun ThrusterControl(
  view: StationView,
  mode: ThrusterMode,
  trimDeg: Double,
  onModeChange: (ThrusterMode) -> Unit,
  onDirectionChange: (ThrusterDirection) -> Unit,
  onTrim: (Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  val helm = LocalHelmScale.current
  val enabled = view.thrusterCommandable

  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(DriveColors.surfaceRaised)
      .padding(helm.size(10.dp))
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "BOW THRUSTER",
        fontSize = helm.text(13.sp),
        color = DriveColors.inkMuted,
        modifier = Modifier.weight(1f),
      )
      // Always live -- see the KDoc above. Selecting a mode is not commanding.
      ModeChip("MANUAL", mode == ThrusterMode.MANUAL) { onModeChange(ThrusterMode.MANUAL) }
      ModeChip("HOLD", mode == ThrusterMode.HOLD) { onModeChange(ThrusterMode.HOLD) }
    }

    // One box, sized by BOTH modes' bodies whichever is showing, so switching
    // mode -- which the chips allow at any time, armed or not -- moves nothing
    // below this panel. MANUAL's contribution is its contacts' floor; HOLD's is
    // the readout and the trim row, which are `sp` and so cannot be written down
    // as a dp number that stays right at every font scale. It is measured
    // instead: MANUAL carries an invisible, inert copy of the HOLD body purely
    // for its size.
    Box(
      Modifier.fillMaxWidth().padding(top = helm.size(8.dp)),
      contentAlignment = Alignment.Center,
    ) {
      if (mode == ThrusterMode.MANUAL) {
        HoldBody(
          view = view,
          trimDeg = trimDeg,
          enabled = enabled,
          onTrim = {},
          interactive = false,
          modifier = Modifier.alpha(0f).clearAndSetSemantics {},
        )
        Spacer(Modifier.requiredHeight(helm.size(ContactButtonMinHeight)))
        ManualBody(
          enabled = enabled,
          onDirectionChange = onDirectionChange,
          modifier = Modifier.matchParentSize(),
        )
      } else {
        Spacer(Modifier.requiredHeight(helm.size(ContactButtonMinHeight)))
        HoldBody(
          view = view,
          trimDeg = trimDeg,
          enabled = enabled,
          onTrim = onTrim,
          interactive = true,
        )
      }
    }

    // The panel's one notice line, held open when there is nothing to say.
    // At most one notice is shown, most important first:
    //
    // 1. HH refusing what this station asked (the red band), in either mode.
    // 2. Another source holding the thruster -- why a press here does nothing.
    // 3. A reversal waiting on the thruster box's interlock -- why a press here
    //    has not happened YET.
    //
    // 2 outranks 3 because it is the explanation that changes what the operator
    // should do: waiting out an interlock on a thruster this station does not
    // command would be waiting for nothing.
    ReservedLines(
      lines = 1,
      fontSize = helm.text(13.sp),
      modifier = Modifier.fillMaxWidth().padding(top = helm.size(4.dp)),
      contentAlignment = Alignment.CenterStart,
      // The band's own inside padding, so reserving "one line" reserves room
      // for the band -- the tallest thing this line holds.
      reservePadding = helm.size(RefusalBandPadding),
    ) {
      val refusal = thrusterRefusal(view, mode)
      when {
        refusal != null -> RefusalBand(refusal)
        view.thrusterOverriddenBy != null ->
          NoticeText("controlled by ${view.thrusterOverriddenBy}")
        view.reversalPending -> NoticeText("reversing — waiting for the thruster interlock")
      }
    }
  }
}

/**
 * The red band's text, or null when HH is doing what this station asked.
 *
 * HOLD: armed, and HH has had every chance to take the hold and has not.
 * Deliberately NOT shown while the request is in flight. An unconfirmed hold is
 * the normal state of the first moment after every arm, and drawing a warning
 * there put one on the panel every single time -- which is how an operator
 * learns to read the one that matters as the usual flicker. The caption beside
 * the heading still refuses to say "HOLDING" throughout, so nothing is being
 * hidden: what is withheld is the ALARM, until there is one.
 *
 * Loud when it does appear, and specific: the remedies differ, and a hold the
 * unit is refusing (SAFETY.md thruster invariant 9 -- a station whose link went
 * stale mid-hold must disarm before it can engage again) is fixed from this
 * screen, while a faulted unit is not. OTHER_SOURCE is the one case drawn as
 * nothing here: the "controlled by ..." notice says it, and it is not a fault.
 *
 * MANUAL: the same refusal, where nothing used to say it. A local ENGAGE release
 * latches every armed remote out of HH until it STOPs and ARMs again, and the
 * arbiter -- which cannot see that latch -- still hands this station the token:
 * it read armed, its PORT/STBD contacts lit under a thumb, and the thruster did
 * nothing. StationView.manualRefusal is the same machinery and the same grace
 * window as the hold phase, so it never flashes on an ordinary arm; it names
 * only a refusal (HH DISARMED) or a fault.
 */
private fun thrusterRefusal(view: StationView, mode: ThrusterMode): String? =
  when {
    mode == ThrusterMode.HOLD &&
      view.holdPhase == HoldPhase.NOT_ENGAGING &&
      view.holdStall != HoldStall.OTHER_SOURCE ->
      when (view.holdStall) {
        HoldStall.UNIT_FAULT -> "NOT HOLDING — UNIT FAULT"
        HoldStall.NO_REFERENCE -> "NOT HOLDING — NO HEADING FIX"
        HoldStall.REFUSED -> "NOT HOLDING — RE-ARM TO ENGAGE"
        // Including HH saying nothing at all: state the fact, and the one
        // remedy that is safe to suggest whatever the cause.
        else -> "NOT HOLDING — CHECK THE UNIT"
      }
    mode == ThrusterMode.MANUAL && view.manualRefusal != HoldStall.NONE ->
      if (view.manualRefusal == HoldStall.UNIT_FAULT) "THRUSTER REFUSED — UNIT FAULT"
      else "THRUSTER REFUSED — RE-ARM TO COMMAND"
    else -> null
  }

/** MANUAL's body: the PORT and STBD contacts, filling whatever height it is given. */
@Composable
private fun ManualBody(
  enabled: Boolean,
  onDirectionChange: (ThrusterDirection) -> Unit,
  modifier: Modifier = Modifier,
) {
  val helm = LocalHelmScale.current
  val contacts = rememberThrusterContacts()

  // Release on the way out, whatever took these buttons off the screen.
  //
  // The mode chips stay live while a contact is held, so selecting HOLD
  // mid-press disposes this whole block: the button's own release fires, but
  // the effect below -- the thing that would REPORT it -- is being disposed
  // in the same pass and never runs. Without this the report is simply lost.
  //
  // The rule itself lives in ThrusterCommand.withMode, which releases the
  // direction on any mode change without needing an event to arrive at all.
  // This is the belt-and-braces, and it also covers the exits that are not
  // mode changes: the session ending, the screen being torn down.
  val currentOnDirectionChange by rememberUpdatedState(onDirectionChange)
  DisposableEffect(Unit) {
    onDispose { currentOnDirectionChange(ThrusterDirection.OFF) }
  }

  LaunchedEffect(contacts.portPressed, contacts.stbdPressed, enabled) {
    onDirectionChange(
      when {
        !enabled -> ThrusterDirection.OFF
        // Both pressed is ambiguous and must not pick a side. OFF, not
        // NEUTRAL: a thruster coasts rather than sitting in a gear.
        contacts.portPressed && contacts.stbdPressed -> ThrusterDirection.OFF
        contacts.portPressed -> ThrusterDirection.PORT
        contacts.stbdPressed -> ThrusterDirection.STBD
        else -> ThrusterDirection.OFF
      }
    )
  }

  // No height of its own: the caller's box is at least ContactButtonMinHeight
  // tall (its Spacer) and this fills it, so the contacts are exactly that box.
  // The gap above belongs to the box's padding rather than to this row, which
  // is the lesson of `.height(88.dp).padding(top = 8.dp)` -- chained that way
  // round the gap came out of the button, and these contacts were silently
  // 80 dp until LayoutFloorsTest measured them.
  //
  // It still does not grow into leftover space: this block sits in the
  // screen's natural-height chrome, so there is no leftover here to grow INTO
  // -- the leftover goes to the drives, which are the pair held through a
  // manoeuvre. It scales, so on a tablet these are 128 dp rather than a
  // phone-sized button marooned in a bigger panel.
  Row(modifier) {
    ContactButton(
      "PORT",
      contacts.portPressed,
      DriveColors.forward,
      enabled,
      { contacts.portPressed = it },
      Modifier.weight(1f).fillMaxSize(),
    )
    Box(Modifier.padding(helm.size(4.dp)))
    ContactButton(
      "STBD",
      contacts.stbdPressed,
      DriveColors.forward,
      enabled,
      { contacts.stbdPressed = it },
      Modifier.weight(1f).fillMaxSize(),
    )
  }
}

/**
 * HOLD's body: the heading readout and the trim steps.
 *
 * [interactive] false is the sizing copy MANUAL carries (see [ThrusterControl]):
 * the same composable with the same inputs, so it measures exactly as the real
 * one would, and its trim steps cannot be clicked whatever [enabled] says.
 */
@Composable
private fun HoldBody(
  view: StationView,
  trimDeg: Double,
  enabled: Boolean,
  onTrim: (Double) -> Unit,
  interactive: Boolean,
  modifier: Modifier = Modifier,
) {
  val helm = LocalHelmScale.current
  Column(modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        // Two different quantities, and which one is meaningful depends on
        // whether THIS station is the one holding.
        //
        // Armed: heldDeg is our setpoint -- the heading being held.
        //
        // Not armed: heldDeg is HH's setpoint, which mirrors the fused heading
        // ONLY while nothing is holding. If another station is holding, it is
        // that station's target, and labelling it "CURRENT HEADING" put a
        // number under a caption that did not describe it. So an idle station
        // reads the fused heading directly, which is the current heading
        // whoever is holding and whether anyone is.
        formatHeading(if (enabled) view.heldDeg else view.currentHeadingDeg),
        fontSize = helm.text(30.sp),
        fontWeight = FontWeight.Bold,
        color = if (enabled) DriveColors.ink else DriveColors.inkMuted,
      )
      Text(
        // "HOLDING" is HH's word, never this station's guess. `enabled` says
        // only that WE could command the thruster -- token held, socket up, HH
        // answering -- and none of that means the unit engaged. It may have
        // refused the heading, faulted, had the thruster taken by its own
        // ENGAGE input, or be refusing a station whose disarm it has not seen
        // yet; in every one of those the number above is the same live
        // plausible heading, because HH mirrors its setpoint to the fused
        // heading whenever it is NOT holding (ARCHITECTURE.md §9). So the word
        // comes from view.holdEngaged -- hh.armed + hh.mode plus HH's own FSM
        // state, since ENABLE is asserted in ARMED_IDLE too -- and until the
        // unit agrees this says what is actually known: the hold has been
        // REQUESTED, and after long enough, that it is not being taken.
        when (view.holdPhase) {
          HoldPhase.ENGAGED -> "°  HOLDING · TRIM ${formatTrim(trimDeg)}°"
          // Asked for and not yet confirmed. A statement of what was asked,
          // not a warning: this is where every arm passes through.
          HoldPhase.REQUESTED -> "°  HOLD REQUESTED"
          // Asked for, and HH has had its window and not taken it. The notice
          // line says what to do about it -- except when the thruster simply
          // belongs to a higher-precedence source, which is not a fault and is
          // already named by the "controlled by ..." notice. That case reads
          // exactly like a request in flight: our hold is not running, we have
          // asked for it, and nothing is broken.
          HoldPhase.NOT_ENGAGING ->
            if (view.holdStall == HoldStall.OTHER_SOURCE) "°  HOLD REQUESTED"
            else "°  HOLD NOT ENGAGED"
          // Not asking: either nothing is commandable here, or the request is
          // one tick old and the window has not been stamped yet. Both read
          // the number as the fused heading, so the caption follows `enabled`
          // exactly as the number above does -- the two must not disagree.
          HoldPhase.IDLE -> if (enabled) "°  HOLD REQUESTED" else "°  CURRENT HEADING"
        },
        fontSize = helm.text(13.sp),
        color =
          if (view.holdPhase == HoldPhase.NOT_ENGAGING &&
            view.holdStall != HoldStall.OTHER_SOURCE
          ) {
            DriveColors.bad
          } else {
            DriveColors.inkMuted
          },
        modifier = Modifier.padding(start = helm.size(6.dp)),
      )
    }
    // Discrete steps rather than press-and-hold repeat: a thumb on glass
    // wants a target it can tap, not a button it must hold for exactly the
    // right length of time.
    Row(
      Modifier.fillMaxWidth().padding(top = helm.size(8.dp)),
      horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
      // From the contract rather than written out here: these mirror
      // config::kHeadingNudge*StepDeg, which is hand-synced three ways, and a
      // literal ladder is exactly the kind of fourth copy that goes quiet when
      // the firmware retunes a step.
      val steps =
        listOf(
          -SkContract.HEADING_TRIM_COARSE_DEG,
          -SkContract.HEADING_TRIM_FINE_DEG,
          SkContract.HEADING_TRIM_FINE_DEG,
          SkContract.HEADING_TRIM_COARSE_DEG,
        )
      for (step in steps) {
        TrimButton(step, enabled, clickable = interactive) { onTrim(step) }
      }
    }
  }
}

/** The inside padding of [RefusalBand], above and below its text. */
private val RefusalBandPadding = 4.dp

/**
 * The thruster panel's red band: HH is not doing what this station asked, and
 * what to do about it.
 */
@Composable
private fun RefusalBand(text: String) {
  val helm = LocalHelmScale.current
  Text(
    text,
    fontSize = helm.text(13.sp),
    fontWeight = FontWeight.Bold,
    color = DriveColors.ink,
    // The band, then padding: the space inside it. The gap above belongs to the
    // notice line holding this. One line, on purpose -- this panel sits in the
    // screen's natural-height chrome, and the line is reserved at one line's
    // height, so anything that wraps here comes out of the drive bank.
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(6.dp))
        .background(DriveColors.bad)
        .padding(horizontal = helm.size(8.dp), vertical = helm.size(RefusalBandPadding)),
  )
}

/** A quieter notice on the thruster panel's notice line: amber text, no band. */
@Composable
private fun NoticeText(text: String) {
  Text(
    text,
    fontSize = LocalHelmScale.current.text(12.sp),
    color = DriveColors.warn,
  )
}

/**
 * A mode chip. No `enabled` parameter by design: this chooser stays live and at
 * full contrast whether or not the thruster is commandable, because it selects
 * the gate the next arm opens rather than commanding anything.
 */
@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
  val helm = LocalHelmScale.current
  Box(
    Modifier.padding(start = helm.size(6.dp))
      .clip(RoundedCornerShape(8.dp))
      .background(if (selected) DriveColors.forward else DriveColors.disarmed)
      .clickable(onClick = onClick)
      .padding(horizontal = helm.size(12.dp), vertical = helm.size(6.dp))
  ) {
    Text(text, fontSize = helm.text(13.sp), color = DriveColors.ink)
  }
}

@Composable
private fun TrimButton(
  step: Double,
  enabled: Boolean,
  clickable: Boolean = true,
  onClick: () -> Unit,
) {
  val helm = LocalHelmScale.current
  Box(
    Modifier.clip(RoundedCornerShape(8.dp))
      .background(if (enabled) DriveColors.surface else DriveColors.disarmed.copy(alpha = 0.4f))
      .border(1.dp, DriveColors.border, RoundedCornerShape(8.dp))
      .clickable(enabled = enabled && clickable, onClick = onClick)
      .padding(horizontal = helm.size(18.dp), vertical = helm.size(12.dp))
  ) {
    Text(
      formatTrim(step) + "°",
      fontSize = helm.text(17.sp),
      fontWeight = FontWeight.Bold,
      color = if (enabled) DriveColors.ink else DriveColors.inkMuted,
    )
  }
}
