package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DrivePosition
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
 * before its first open (see [LinkPhase]). The moment a session HAS been open,
 * every later gap is OFFLINE with no grace at all.
 */
@Composable
fun KillSwitch(view: StationView, onArm: () -> Unit, onDisarm: () -> Unit, modifier: Modifier = Modifier) {
  val helm = LocalHelmScale.current
  val foreign = view.controlState == ControlState.OTHER
  val label: String
  val sub: String
  val colour: Color

  when {
    // Before OFFLINE, and only ever true before this session's first open.
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
  val onTap: () -> Unit = { if (canOfferArm) onArm() else onDisarm() }

  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(colour)
      .clickable(onClick = onTap)
      .padding(vertical = helm.size(18.dp))
      .semantics {
        role = Role.Button
        contentDescription = "$label. $sub"
      },
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(label, fontSize = helm.text(30.sp), fontWeight = FontWeight.Bold, color = DriveColors.ink)
    Text(
      sub,
      fontSize = helm.text(13.sp),
      color = DriveColors.ink.copy(alpha = 0.85f),
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = helm.size(12.dp)),
    )
  }
}

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
    Text(label.uppercase(), fontSize = helm.text(13.sp), color = DriveColors.inkMuted)

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
    // exactly the case where a press here visibly does nothing.
    if (overriddenBy != null) {
      Text(
        "controlled by $overriddenBy",
        fontSize = helm.text(12.sp),
        color = DriveColors.warn,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = helm.size(4.dp)),
      )
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
      .momentaryPress(enabled, onPressedChange)
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
 * be showing. Disarmed, the panel says which mode is selected and that arming
 * is what makes it live -- and in HOLD it says the hold starts on arm, because
 * HOLD needs no further press to begin working the thruster.
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

    if (mode == ThrusterMode.MANUAL) {
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
      // Padding BEFORE height, and this order is load-bearing. Chained the
      // other way round, `.height(88.dp).padding(top = 8.dp)` fixes the row at
      // 88 dp and then eats 8 of them for the gap, leaving 80 dp contacts --
      // which is what these buttons silently were until LayoutFloorsTest
      // measured them. Padding first means the 88 dp is the button.
      //
      // An exact height, not a floor with room to grow: this block sits in the
      // screen's natural-height chrome, so there is no leftover here to grow
      // INTO -- the leftover goes to the drives, which are the pair held
      // through a manoeuvre. It still scales, so on a tablet these are 128 dp
      // rather than a phone-sized button marooned in a bigger panel.
      Row(
        Modifier.fillMaxWidth()
          .padding(top = helm.size(8.dp))
          .requiredHeight(helm.size(ContactButtonMinHeight))
      ) {
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
    } else {
      Row(
        Modifier.padding(top = helm.size(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
      ) {
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
          if (enabled) "°  HOLDING · TRIM ${formatTrim(trimDeg)}°" else "°  CURRENT HEADING",
          fontSize = helm.text(13.sp),
          color = DriveColors.inkMuted,
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
          TrimButton(step, enabled) { onTrim(step) }
        }
      }
    }

    // Nothing armed here: says which gate the next arm opens, so a selection
    // made with nothing armed is never mistaken for a live command -- and so
    // the HOLD case is stated outright, since arming alone starts it.
    //
    // Gated on `armed`, NOT on `enabled`: armed-but-not-commandable (socket
    // down, HH switched off) leaves the same controls inert for a completely
    // different reason, and telling the operator to "arm" there would send them
    // at the one control that is already doing its job. The kill switch names
    // that reason instead.
    if (!view.armed) {
      Text(
        if (mode == ThrusterMode.MANUAL) "MANUAL selected — arm to thrust"
        else "HOLD selected — starts holding when you arm",
        fontSize = helm.text(12.sp),
        color = DriveColors.inkMuted,
        modifier = Modifier.padding(top = helm.size(6.dp)),
      )
    }

    if (view.reversalPending) {
      Text(
        "reversing — waiting for the thruster interlock",
        fontSize = helm.text(12.sp),
        color = DriveColors.warn,
        modifier = Modifier.padding(top = helm.size(6.dp)),
      )
    }
    if (view.thrusterOverriddenBy != null) {
      Text(
        "controlled by ${view.thrusterOverriddenBy}",
        fontSize = helm.text(12.sp),
        color = DriveColors.warn,
        modifier = Modifier.padding(top = helm.size(4.dp)),
      )
    }
  }
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
private fun TrimButton(step: Double, enabled: Boolean, onClick: () -> Unit) {
  val helm = LocalHelmScale.current
  Box(
    Modifier.clip(RoundedCornerShape(8.dp))
      .background(if (enabled) DriveColors.surface else DriveColors.disarmed.copy(alpha = 0.4f))
      .border(1.dp, DriveColors.border, RoundedCornerShape(8.dp))
      .clickable(enabled = enabled, onClick = onClick)
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
