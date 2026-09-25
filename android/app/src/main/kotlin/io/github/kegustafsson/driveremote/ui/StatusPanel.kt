package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.CommandsTone
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.UnitLiveness

/**
 * The status bar: five lamps in one row, and the full readings behind a tap.
 *
 * Colour is readable at a glance and in peripheral vision while the operator is
 * looking at the boat rather than the screen -- which is the normal case during
 * a manoeuvre, and why this is lamps rather than prose rows.
 *
 * **The collapsed bar is the same height in every state.** Each lamp is a
 * symbol dot over a fixed short name -- LINK, CMD, CTRL, DRV, THR -- and
 * nothing in it changes length with what it reports. It used to carry each
 * lamp's reading as text ("connected", "reaching boat", "nobody armed"), which
 * wrapped differently with its words and at the owner's 1.3x system font came
 * out as "COMMAND / S" and "connecte / d"; and a fault added a line of its own
 * beneath. The drive bank takes what this bar leaves, so either of those
 * resized the drive contacts when a unit went quiet or the operator armed.
 * ControlPositionStabilityTest holds it still now.
 *
 * **A fault is still said, not just coloured.** The lamp turns red, its dot
 * reads ✕ rather than ✓ -- so the state does not rest on colour alone -- and
 * its name turns red, so which unit went quiet is on screen without a tap. The
 * kill switch's second line names it in words as well. What moved behind the
 * tap is the detail: the exact readings, per-drive positions and sources, the
 * thruster's state, an auth message, the change-server control and the app
 * version. Each lamp's reading is its accessibility description too.
 *
 * **Nothing derived is shown as live when it cannot be confirmed.** Every lamp
 * that reads from the stream greys out when the stream is down or when the unit
 * that publishes it has gone quiet: dimmed means "was, not is". LINK and CMD are
 * never dimmed -- LINK is the one reporting the fault, and CMD reads the HTTP
 * path, which is independent of the stream.
 */
@Composable
fun StatusPanel(
  view: StationView,
  authError: String?,
  onChangeServer: () -> Unit,
  /**
   * Shown small at the foot of the detail view, so the running build is
   * identifiable without digging through Android's app info. Defaults to blank:
   * the layout suite and the previews render without one.
   */
  appVersion: String = "",
  modifier: Modifier = Modifier,
  /** Applied to the collapsed bar row itself -- how the screen finds its bottom edge. */
  barModifier: Modifier = Modifier,
) {
  val helm = LocalHelmScale.current
  var expanded by rememberSaveable { mutableStateOf(false) }
  val lamps = statusLamps(view)

  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(DriveColors.surfaceRaised)
      // 6 dp above and below rather than 8: the 640 dp budget phone at 1.0x
      // had the bar 1.3 dp off the bottom with the drive bank at its floor.
      .padding(horizontal = helm.size(8.dp), vertical = helm.size(6.dp))
  ) {
    // The always-visible bar, and the whole row is the toggle -- a big target
    // rather than a chevron a thumb has to hunt for on a moving boat.
    Row(
      barModifier.fillMaxWidth()
        .clickable { expanded = !expanded }
        .semantics {
          role = Role.Button
          contentDescription =
            if (expanded) "Hide telemetry detail" else "Show telemetry detail"
        },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      for (lamp in lamps) {
        BarLamp(lamp, Modifier.weight(1f))
      }
      Text(
        if (expanded) "▴" else "▾",
        fontSize = helm.text(16.sp),
        color = DriveColors.inkMuted,
        modifier = Modifier.padding(horizontal = helm.size(6.dp)),
      )
    }

    if (expanded) {
      // Bounded and scrollable rather than free-growing: expanding this must
      // never push the drive buttons off the bottom of the screen.
      Column(
        Modifier.fillMaxWidth()
          .heightIn(max = helm.size(220.dp))
          .verticalScroll(rememberScrollState())
      ) {
        if (authError != null) {
          Text(
            authError,
            fontSize = helm.text(12.sp),
            color = DriveColors.bad,
            modifier = Modifier.padding(top = helm.size(8.dp)),
          )
        }

        // The bar's five, with their readings in full, two to a row.
        for (pair in lamps.chunked(2)) {
          Row(Modifier.fillMaxWidth().padding(top = helm.size(8.dp))) {
            for (lamp in pair) {
              Lamp(lamp.title, lamp.reading, lamp.colour, lamp.dimmed, Modifier.weight(1f))
            }
            if (pair.size == 1) {
              Lamp(
                title = "THRUSTER",
                reading =
                  if (view.hhLiveness != UnitLiveness.LIVE) "no data"
                  else
                    listOfNotNull(view.thrusterState, view.thrusterSource.label)
                      .joinToString(" · "),
                colour =
                  if (view.hhLiveness != UnitLiveness.LIVE) DriveColors.neutral
                  else DriveColors.good,
                dimmed = view.hhLiveness != UnitLiveness.LIVE,
                modifier = Modifier.weight(1f),
              )
            }
          }
        }

        Row(Modifier.fillMaxWidth().padding(top = helm.size(8.dp))) {
          DriveLamp("PORT", view.portState, view.portSource, view.rxLiveness, Modifier.weight(1f))
          DriveLamp("STBD", view.stbdState, view.stbdSource, view.rxLiveness, Modifier.weight(1f))
        }
      }

      // Disconnecting lives behind the detail toggle on purpose: it is a
      // deliberate, occasional act, and a control that ends the session has no
      // business sitting where a thumb can find it during a manoeuvre.
      //
      // Inert while armed on a live link rather than hidden. Disconnecting armed
      // would leave this station holding the arm token until the arbiter
      // stale-evicts it, with the operator already on a screen that shows no
      // controls -- armed, still commanding, and unable to see or stop it.
      // Saying so is more use than a button that has silently vanished.
      //
      // NOT inert offline: there "armed" is only the last-known activeClient,
      // which no disarm could visibly clear, and refusing on it locked the
      // operator onto a server they could not reach. Offline the button leaves
      // with a STOP instead (StationView.changeServerSendsStop), and says so.
      val refused = view.changeServerRefused
      Box(
        Modifier.fillMaxWidth()
          .padding(top = helm.size(10.dp))
          .clip(RoundedCornerShape(10.dp))
          .background(if (refused) DriveColors.disarmed.copy(alpha = 0.4f) else DriveColors.surface)
          .clickable(enabled = !refused, onClick = onChangeServer)
          .padding(vertical = helm.size(12.dp))
          .semantics {
            role = Role.Button
            contentDescription =
              when {
                refused -> "Disconnect, unavailable while armed"
                view.changeServerSendsStop -> "Stop, disconnect and change server"
                else -> "Disconnect and change server"
              }
          },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          when {
            refused -> "Disarm to change server"
            view.changeServerSendsStop -> "STOP + change server"
            else -> "Disconnect / change server"
          },
          fontSize = helm.text(13.sp),
          color = if (refused) DriveColors.inkMuted else DriveColors.ink,
        )
      }

      if (appVersion.isNotBlank()) {
        Text(
          "app $appVersion",
          fontSize = helm.text(10.sp),
          color = DriveColors.inkMuted,
          modifier = Modifier.align(Alignment.End).padding(top = helm.size(6.dp)),
        )
      }
    }
  }
}

/** How a lamp reads at a glance: the symbol in its dot, the colour, and the name's weight. */
private enum class LampTone(val symbol: String) {
  GOOD("✓"),
  NEUTRAL("–"),
  WARN("!"),
  BAD("✕"),
}

/** One of the bar's five lamps: its short name, its detail title and reading, and its state. */
private class StatusLamp(
  val code: String,
  val title: String,
  val reading: String,
  val tone: LampTone,
  val dimmed: Boolean,
) {
  val colour: Color
    get() =
      when (tone) {
        LampTone.GOOD -> DriveColors.good
        LampTone.NEUTRAL -> DriveColors.neutral
        LampTone.WARN -> DriveColors.warn
        LampTone.BAD -> DriveColors.bad
      }
}

private fun statusLamps(view: StationView): List<StatusLamp> {
  val live = view.connected
  return listOf(
    // The connection lamp is never dimmed -- it is the one telling you the
    // link is down, so dimming it would hide the only reliable reading.
    //
    // LinkPhase, not ConnectionState, for the same reason the kill switch
    // uses it: a socket that has not opened YET is a start-up, and lighting
    // this amber and then red for the few hundred milliseconds of every
    // launch trains the operator to ignore the one lamp that reports the
    // link. Once a session has been live, a gap is reported immediately.
    //
    // "no data from server" is the socket that still reads open while
    // nothing arrives on it -- the far end gone without a FIN. Named apart
    // from "disconnected" because it looks nothing like one from the phone:
    // Wi-Fi up, the app still attached, and the boat invisible. The
    // browser's Link lamp says the same words.
    StatusLamp(
      code = "LINK",
      title = "LINK",
      reading =
        when (view.linkPhase) {
          LinkPhase.ONLINE -> "connected"
          LinkPhase.CONNECTING -> "connecting…"
          LinkPhase.OFFLINE ->
            when (view.connectionState) {
              ConnectionState.OPEN -> "no data from server"
              ConnectionState.CONNECTING -> "reconnecting…"
              ConnectionState.CLOSED -> "disconnected"
            }
        },
      tone =
        when (view.linkPhase) {
          LinkPhase.ONLINE -> LampTone.GOOD
          LinkPhase.CONNECTING -> LampTone.NEUTRAL
          LinkPhase.OFFLINE ->
            if (view.connectionState == ConnectionState.CONNECTING) LampTone.WARN
            else LampTone.BAD
        },
      dimmed = false,
    ),
    // Whether this station's commands are actually landing, from the outcome
    // of its own intent POSTs -- the browser's Commands lamp, same wording.
    // Never dimmed, unlike the browser's copy: the reading does not come from
    // the stream, it comes from the HTTP path, which is independent of it --
    // and "the stream is down but commands still land" is precisely the case
    // where this lamp is the one telling the operator their STOP still works.
    StatusLamp(
      code = "CMD",
      title = "COMMANDS",
      reading = view.commands.value,
      tone =
        when (view.commands.tone) {
          CommandsTone.GOOD -> LampTone.GOOD
          CommandsTone.NEUTRAL -> LampTone.NEUTRAL
          CommandsTone.BAD -> LampTone.BAD
        },
      dimmed = false,
    ),
    StatusLamp(
      code = "CTRL",
      title = "CONTROL",
      reading =
        when (view.controlState) {
          ControlState.YOU -> "this app"
          ControlState.OTHER -> "another station"
          ControlState.NONE -> "nobody armed"
        },
      tone = if (view.armed) LampTone.GOOD else LampTone.NEUTRAL,
      dimmed = !live,
    ),
    unitLamp("DRV", "DRIVE UNIT", view.rxLiveness),
    unitLamp("THR", "THRUSTER UNIT", view.hhLiveness),
  )
}

private fun unitLamp(code: String, title: String, liveness: UnitLiveness): StatusLamp {
  val (reading, tone) =
    when (liveness) {
      UnitLiveness.LIVE -> "responding" to LampTone.GOOD
      UnitLiveness.STALE -> "NOT RESPONDING" to LampTone.BAD
      UnitLiveness.NEVER_SEEN -> "NOT SEEN — check power" to LampTone.BAD
      UnitLiveness.OFFLINE -> "unknown — offline" to LampTone.NEUTRAL
    }
  return StatusLamp(code, title, reading, tone, dimmed = liveness == UnitLiveness.OFFLINE)
}

/**
 * One lamp in the collapsed bar: a dot carrying the state's symbol, and the
 * lamp's fixed short name under it.
 *
 * Both are sized in `sp`, the dot included, so the symbol inside it scales with
 * the system font rather than overflowing a dp circle at 2.0x. Neither can wrap:
 * the name is one short word that never changes, which is what makes the bar's
 * height a function of the font scale alone.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BarLamp(lamp: StatusLamp, modifier: Modifier = Modifier) {
  val helm = LocalHelmScale.current
  val alpha = if (lamp.dimmed) 0.4f else 1f
  val dot = with(LocalDensity.current) { helm.text(15.sp).toDp() }
  Column(
    modifier.semantics(mergeDescendants = true) {
      contentDescription = "${lamp.title}: ${lamp.reading}"
    },
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      Modifier.size(dot).clip(CircleShape).background(lamp.colour.copy(alpha = alpha)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        lamp.tone.symbol,
        fontSize = helm.text(10.sp),
        lineHeight = 1.em,
        fontWeight = FontWeight.Black,
        color = DriveColors.surface,
        modifier = Modifier.clearAndSetSemantics {},
      )
    }
    Text(
      lamp.code,
      fontSize = helm.text(10.sp),
      lineHeight = 1.3.em,
      letterSpacing = 0.04.em,
      maxLines = 1,
      softWrap = false,
      fontWeight = if (lamp.tone == LampTone.BAD) FontWeight.Bold else FontWeight.Normal,
      color =
        (if (lamp.tone == LampTone.BAD) DriveColors.bad else DriveColors.ink).copy(alpha = alpha),
      // Out of TalkBack's way -- the lamp's description already says it, with
      // the reading -- but left in the semantics tree, where the layout suite
      // checks that no name is clipped at 2.0x system text.
      modifier = Modifier.padding(top = helm.size(3.dp)).semantics { invisibleToUser() },
    )
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
