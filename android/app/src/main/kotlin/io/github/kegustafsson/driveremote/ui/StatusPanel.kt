package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.UnitLiveness

/**
 * Traffic-light lamps: a coloured lamp, a fixed title, and the current reading.
 *
 * Colour is readable at a glance and in peripheral vision while the operator is
 * looking at the boat rather than the screen -- which is the normal case during
 * a manoeuvre, and why this is lamps rather than prose rows.
 *
 * **Nothing derived is shown as live when it cannot be confirmed.** Every lamp
 * below the connection row greys out when the stream is down or when the unit
 * that publishes it has gone quiet. They keep their last-known text but are
 * dimmed: an unlit, dimmed lamp means "was, not is". Only the connection lamp
 * stays fully coloured while offline, since it is the one reporting the fault.
 *
 * **The detail is collapsed by default**, so the space goes to the controls
 * instead. What collapsing may NOT do is hide a fault: an auth error, and the
 * name of any unit that has stopped responding, stay on screen in the collapsed
 * state. Only the per-lamp readings -- which of the two drives is where, who
 * commanded it, the thruster's last state -- are behind the tap. Those are
 * detail an operator consults; a unit going quiet is something they must be
 * told, and burying it behind a tap the operator has no reason to make would
 * be a way of presenting a degraded system as a healthy one.
 */
@Composable
fun StatusPanel(
  view: StationView,
  authError: String?,
  onChangeServer: () -> Unit,
  /**
   * Shown small in the summary bar, so the running build is identifiable from a
   * photo of the helm screen without digging through Android's app info.
   * Defaults to blank: the layout suite and the previews render without one,
   * and a blank version then costs no height at all.
   */
  appVersion: String = "",
  modifier: Modifier = Modifier,
) {
  val live = view.connected
  val helm = LocalHelmScale.current
  var expanded by rememberSaveable { mutableStateOf(false) }

  // Named here, in the collapsed summary, precisely so collapsing cannot hide
  // them. UnitLamp reports the same thing in full once expanded.
  val silentUnits =
    buildList {
      if (view.rxLiveness != UnitLiveness.LIVE) add("drive unit")
      if (view.hhLiveness != UnitLiveness.LIVE) add("thruster unit")
    }

  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(DriveColors.surfaceRaised)
      .padding(helm.size(8.dp))
  ) {
    if (authError != null) {
      Text(
        authError,
        fontSize = helm.text(12.sp),
        color = DriveColors.bad,
        modifier = Modifier.padding(bottom = helm.size(6.dp)),
      )
    }

    // The always-visible summary, and the whole row is the toggle -- a big
    // target rather than a chevron a thumb has to hunt for on a moving boat.
    Row(
      Modifier.fillMaxWidth()
        .clickable { expanded = !expanded }
        .semantics {
          role = Role.Button
          contentDescription =
            if (expanded) "Hide telemetry detail" else "Show telemetry detail"
        },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
      // The connection lamp is never dimmed -- it is the one telling you the
      // link is down, so dimming it would hide the only reliable reading.
      Lamp(
        title = "LINK",
        // LinkPhase, not ConnectionState, for the same reason the kill switch
        // uses it: a socket that has not opened YET is a start-up, and lighting
        // this amber and then red for the few hundred milliseconds of every
        // launch trains the operator to ignore the one lamp that reports the
        // link. Once a session has been open, a gap is reported immediately.
        reading =
          when (view.linkPhase) {
            LinkPhase.ONLINE -> "connected"
            LinkPhase.CONNECTING -> "connecting…"
            LinkPhase.OFFLINE ->
              if (view.connectionState == ConnectionState.CONNECTING) "reconnecting…"
              else "disconnected"
          },
        colour =
          when (view.linkPhase) {
            LinkPhase.ONLINE -> DriveColors.good
            LinkPhase.CONNECTING -> DriveColors.neutral
            LinkPhase.OFFLINE ->
              if (view.connectionState == ConnectionState.CONNECTING) DriveColors.warn
              else DriveColors.bad
          },
        dimmed = false,
        modifier = Modifier.weight(1f),
      )

      Lamp(
        title = "CONTROL",
        reading =
          when (view.controlState) {
            ControlState.YOU -> "this app"
            ControlState.OTHER -> "another station"
            ControlState.NONE -> "nobody armed"
          },
        colour = if (view.armed) DriveColors.good else DriveColors.neutral,
        dimmed = !live,
        modifier = Modifier.weight(1f),
      )

      // Chevron and version share one column so the version costs no WIDTH --
      // the two lamps beside it are weight(1f) and would give up space for it.
      // Height is the cheaper axis here: this row is inside the telemetry
      // region, which scrolls and commands nothing.
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = helm.size(8.dp)),
      ) {
        Text(
          if (expanded) "▴" else "▾",
          fontSize = helm.text(16.sp),
          color = DriveColors.inkMuted,
        )
        if (appVersion.isNotBlank()) {
          Text(
            appVersion,
            fontSize = helm.text(9.sp),
            color = DriveColors.inkMuted,
          )
        }
      }
    }

    // Not while the first socket is still opening. A unit cannot be judged
    // "not responding" before there is a stream to hear it on, and every
    // launch would otherwise open with both of them named in red for the few
    // hundred milliseconds the connection takes -- the same noise the kill
    // switch's OFFLINE flash was, in smaller type. See LinkPhase.
    if (!expanded && silentUnits.isNotEmpty() && view.linkPhase != LinkPhase.CONNECTING) {
      Text(
        "${silentUnits.joinToString(" + ")} not responding — tap for detail",
        fontSize = helm.text(12.sp),
        color = DriveColors.bad,
        modifier = Modifier.padding(top = helm.size(6.dp)),
      )
    }

    if (expanded) {
      // Bounded and scrollable rather than free-growing: expanding this must
      // never push the drive buttons off the bottom of the screen. The
      // controls stay where the thumb left them.
      Column(
        Modifier.fillMaxWidth()
          .heightIn(max = helm.size(200.dp))
          .verticalScroll(rememberScrollState())
      ) {
        Row(
          Modifier.fillMaxWidth().padding(top = helm.size(8.dp)),
          horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
          UnitLamp("DRIVE UNIT", view.rxLiveness, Modifier.weight(1f))
          UnitLamp("THRUSTER UNIT", view.hhLiveness, Modifier.weight(1f))
        }

        Row(
          Modifier.fillMaxWidth().padding(top = helm.size(8.dp)),
          horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
          DriveLamp("PORT", view.portState, view.portSource, view.rxLiveness, Modifier.weight(1f))
          DriveLamp("STBD", view.stbdState, view.stbdSource, view.rxLiveness, Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth().padding(top = helm.size(8.dp))) {
          Lamp(
            title = "THRUSTER",
            reading =
              if (view.hhLiveness != UnitLiveness.LIVE) "no data"
              else listOfNotNull(view.thrusterState, view.thrusterSource.label).joinToString(" · "),
            colour =
              if (view.hhLiveness != UnitLiveness.LIVE) DriveColors.neutral else DriveColors.good,
            dimmed = view.hhLiveness != UnitLiveness.LIVE,
            modifier = Modifier.weight(1f),
          )
        }
      }

      // Disconnecting lives behind the detail toggle on purpose: it is a
      // deliberate, occasional act, and a control that ends the session has no
      // business sitting where a thumb can find it during a manoeuvre.
      //
      // Inert while armed rather than hidden. Disconnecting armed would leave
      // this station holding the arm token until the arbiter stale-evicts it,
      // with the operator already on a screen that shows no controls -- armed,
      // still commanding, and unable to see or stop it. Saying so is more use
      // than a button that has silently vanished.
      Box(
        Modifier.fillMaxWidth()
          .padding(top = helm.size(10.dp))
          .clip(RoundedCornerShape(10.dp))
          .background(if (view.armed) DriveColors.disarmed.copy(alpha = 0.4f) else DriveColors.surface)
          .clickable(enabled = !view.armed, onClick = onChangeServer)
          .padding(vertical = helm.size(12.dp))
          .semantics {
            role = Role.Button
            contentDescription =
              if (view.armed) "Disconnect, unavailable while armed" else "Disconnect and change server"
          },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          if (view.armed) "Disarm to change server" else "Disconnect / change server",
          fontSize = helm.text(13.sp),
          color = if (view.armed) DriveColors.inkMuted else DriveColors.ink,
        )
      }
    }
  }
}

@Composable
private fun UnitLamp(title: String, liveness: UnitLiveness, modifier: Modifier = Modifier) {
  val helm = LocalHelmScale.current
  val (reading, colour) =
    when (liveness) {
      UnitLiveness.LIVE -> "responding" to DriveColors.good
      UnitLiveness.STALE -> "NOT RESPONDING" to DriveColors.bad
      UnitLiveness.NEVER_SEEN -> "NOT SEEN — check power" to DriveColors.bad
      UnitLiveness.OFFLINE -> "unknown — offline" to DriveColors.neutral
    }
  Lamp(title, reading, colour, dimmed = liveness == UnitLiveness.OFFLINE, modifier = modifier)
}

@Composable
private fun DriveLamp(
  title: String,
  position: DisplayDrivePosition,
  source: CommandSource,
  liveness: UnitLiveness,
  modifier: Modifier = Modifier,
) {
  val stale = liveness != UnitLiveness.LIVE
  val helm = LocalHelmScale.current
  val reading =
    if (stale) "no data"
    else
      when (position) {
        DisplayDrivePosition.FORWARD -> "FORWARD"
        DisplayDrivePosition.REVERSE -> "REVERSE"
        DisplayDrivePosition.NEUTRAL -> "neutral"
        // Unparseable telemetry is shown as unknown, never relabelled to
        // something plausible -- that would misrepresent what RX actually said.
        DisplayDrivePosition.UNKNOWN -> "unknown"
      } + " · " + source.label

  val colour =
    when {
      stale -> DriveColors.neutral
      position == DisplayDrivePosition.FORWARD -> DriveColors.forward
      position == DisplayDrivePosition.REVERSE -> DriveColors.reverse
      position == DisplayDrivePosition.UNKNOWN -> DriveColors.warn
      else -> DriveColors.good
    }

  Lamp(title, reading, colour, dimmed = stale, modifier = modifier)
}

@Composable
private fun Lamp(
  title: String,
  reading: String,
  colour: Color,
  dimmed: Boolean,
  modifier: Modifier = Modifier,
) {
  val alpha = if (dimmed) 0.4f else 1f
  val helm = LocalHelmScale.current
  Row(
    modifier.padding(horizontal = helm.size(4.dp)),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(helm.size(10.dp)).clip(CircleShape).background(colour.copy(alpha = alpha)))
    Column(Modifier.padding(start = helm.size(6.dp))) {
      Text(title, fontSize = helm.text(10.sp), color = DriveColors.inkMuted.copy(alpha = alpha))
      Text(reading, fontSize = helm.text(12.sp), color = DriveColors.ink.copy(alpha = alpha))
    }
  }
}
