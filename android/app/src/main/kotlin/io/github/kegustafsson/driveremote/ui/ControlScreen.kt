package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kegustafsson.driveremote.core.DrivePosition
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.ThrusterDirection
import io.github.kegustafsson.driveremote.core.ThrusterMode

/** Identifies the port/starboard bank so its reserved height can be measured. */
const val DriveBankTag = "driveBank"

/** Identifies the telemetry region — the only scrolling thing on the screen. */
const val TelemetryScrollTag = "telemetryScroll"

/** Identifies the notice shown when live controls do not fit on screen. */
const val ClippedWarningTag = "clippedWarning"

/**
 * The control screen: kill switch, bow thruster, both drives, telemetry.
 *
 * Takes plain values and callbacks rather than the ViewModel, so the layout can
 * be rendered — and measured — without a server, a token or a boat. That is not
 * decoration: the geometry below is a safety property, and until this was
 * separable from [io.github.kegustafsson.driveremote.StationViewModel] the only
 * way to check it was to carry a phone to the boat. See `LayoutFloorsTest`.
 *
 * Three rules hold this arrangement together, and the first two come from real
 * defects.
 *
 * **Nothing that can command a machine has a scrolling ancestor.** A momentary
 * contact claims the pointer on touch down ([momentaryPress]), so a drag that
 * begins on one is a press. If a scrolling ancestor then claims that gesture as
 * a drag, the operator gets a scroll *and* a live command for the length of the
 * drag — while armed. The kill switch, the thruster contacts and the drive bank
 * are therefore all fixed, and only the telemetry panel scrolls. `momentaryPress`
 * also releases on `isConsumed`, but that is the backstop; this layout is the
 * primary defence.
 *
 * **The live controls take their space first.** The drive bank has a floor no
 * sibling can negotiate down — that is what the old `weight(1f)` allowed, and it
 * once squeezed both drive contacts to 4.7 dp, a tenth of Android's minimum
 * touch target, while they were still commanding.
 *
 * **And they take the leftover too.** The floor was only ever half the rule: on
 * anything bigger than the reference phone the bank used to stay pinned at its
 * 280 dp minimum while the panel that commands nothing — telemetry, holding the
 * `weight(1f)` — absorbed every spare pixel. On a 10" tablet that is most of the
 * screen spent on lamps. The surplus now goes the other way (see [ControlSurface]
 * and the landscape arrangements below), which is what the browser UI has always
 * done with its `1fr` drives row. Telemetry is still the thing that gives way when
 * the screen is too short, because it is still the thing that commands nothing.
 *
 * The scale itself is [HelmScale], read from the real window rather than from
 * the device — so a split-screen pane is sized as the small window it is.
 */
@Composable
fun ControlScreen(
  view: StationView,
  thrusterMode: ThrusterMode,
  trimDeg: Double,
  authError: String?,
  onArm: () -> Unit,
  onDisarm: () -> Unit,
  onThrusterModeChange: (ThrusterMode) -> Unit,
  onThrusterDirectionChange: (ThrusterDirection) -> Unit,
  onTrim: (Double) -> Unit,
  onPortChange: (DrivePosition) -> Unit,
  onStbdChange: (DrivePosition) -> Unit,
  onChangeServer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    val helm = remember(maxWidth, maxHeight) { helmScaleFor(maxWidth, maxHeight) }

    CompositionLocalProvider(LocalHelmScale provides helm) {
      // Shape, not width. A tablet held UPRIGHT gets the same stacked
      // arrangement as a phone — one wide kill switch, the thruster block, the
      // two drives side by side, telemetry under them — because that is what the
      // shape of the window is asking for, and because the browser UI does
      // exactly this below its own breakpoint. Choosing on width alone put an
      // 800 x 1280 dp tablet into the three-column arrangement, which is where
      // "BOW THRUSTER" came out one letter per line and the fourth trim button
      // fell off the side of a 250 dp middle column.
      //
      // Turned on its side it becomes the browser UI's wide arrangement: the
      // same stack, narrower, with telemetry moved out into a sidebar down the
      // right (`sk-plugin/src/index.css`, the `min-width: 860px` rule). All
      // three command stations then read as one system in both orientations,
      // which was the point of sharing the scale in the first place.
      //
      // A landscape window too SHORT for that stack — a phone on its side, a
      // split-screen pane — gets neither: see [EdgeControlScreen].
      val landscape = maxWidth > maxHeight

      when {
        !landscape || maxWidth < WideLayoutMinWidth ->
          TallControlScreen(
            view, thrusterMode, trimDeg, authError, onArm, onDisarm,
            onThrusterModeChange, onThrusterDirectionChange, onTrim,
            onPortChange, onStbdChange, onChangeServer,
          )

        maxHeight >= SidebarLayoutMinHeight ->
          SidebarControlScreen(
            view, thrusterMode, trimDeg, authError, onArm, onDisarm,
            onThrusterModeChange, onThrusterDirectionChange, onTrim,
            onPortChange, onStbdChange, onChangeServer,
            windowWidth = maxWidth,
          )

        else ->
          EdgeControlScreen(
            view, thrusterMode, trimDeg, authError, onArm, onDisarm,
            onThrusterModeChange, onThrusterDirectionChange, onTrim,
            onPortChange, onStbdChange, onChangeServer,
          )
      }
    }
  }
}

/**
 * The phone arrangement: everything stacked, drives above telemetry.
 *
 * The vertical split is [ControlSurface]'s, not a `Column`'s, because what is
 * wanted here cannot be said with weights — see that function.
 */
@Composable
private fun TallControlScreen(
  view: StationView,
  thrusterMode: ThrusterMode,
  trimDeg: Double,
  authError: String?,
  onArm: () -> Unit,
  onDisarm: () -> Unit,
  onThrusterModeChange: (ThrusterMode) -> Unit,
  onThrusterDirectionChange: (ThrusterDirection) -> Unit,
  onTrim: (Double) -> Unit,
  onPortChange: (DrivePosition) -> Unit,
  onStbdChange: (DrivePosition) -> Unit,
  onChangeServer: () -> Unit,
) {
  val helm = LocalHelmScale.current
  val gutter = helm.size(10.dp)
  val overflow = rememberOverflowWatch()

  ControlSurface(
    modifier = Modifier.fillMaxSize().padding(gutter).then(overflow.viewport),
    bankMinHeight = helm.size(DriveBankMinHeight),
    contactGrowth = helm.size(ContactButtonMaxHeight - ContactButtonMinHeight),
    gap = gutter,
    chrome = {
      KillSwitch(view = view, onArm = onArm, onDisarm = onDisarm)

      // Directly under the kill switch, which is the one place guaranteed to be
      // on screen. Showing it costs a little height and so cannot clear the
      // overflow it reports -- which is correct, and does not oscillate: it
      // appears only when the bank is already cut off without it.
      ClippedNotice(overflow.clipped)

      ThrusterControl(
        view = view,
        mode = thrusterMode,
        trimDeg = trimDeg,
        onModeChange = onThrusterModeChange,
        onDirectionChange = onThrusterDirectionChange,
        onTrim = onTrim,
        modifier = Modifier.padding(top = gutter).then(overflow.probe("thruster")),
      )
    },
    bank = {
      // The tag is how LayoutFloorsTest measures the reservation itself rather
      // than inferring it from the buttons inside. The reservation is enforced
      // by ControlSurface now rather than by a `requiredHeightIn` here: this Row
      // is handed an exact height that is never below the floor, and the
      // contacts inside keep their own un-negotiable floor regardless, so a
      // window too short still pushes them off the bottom rather than shrinking
      // them.
      Row(Modifier.fillMaxWidth().testTag(DriveBankTag)) {
        DriveControl(
          label = "Port",
          enabled = view.driveCommandable,
          overriddenBy = view.portOverriddenBy,
          onPositionChange = onPortChange,
          modifier = Modifier.weight(1f).fillMaxHeight().padding(end = helm.size(5.dp)),
        )
        DriveControl(
          label = "Starboard",
          enabled = view.driveCommandable,
          overriddenBy = view.stbdOverriddenBy,
          onPositionChange = onStbdChange,
          modifier = Modifier.weight(1f).fillMaxHeight().padding(start = helm.size(5.dp)),
          overflowProbe = overflow.probe("starboard"),
        )
      }
    },
    telemetry = { TelemetryPanel(view, authError, onChangeServer) },
  )
}

/**
 * The landscape arrangement, and the twin of the browser UI's wide layout: the
 * same stack the phone gets — kill switch, thruster, both drives side by side —
 * with telemetry lifted out of the column into a sidebar down the right.
 *
 * `sk-plugin/src/index.css` says the same thing in four lines of
 * `grid-template-areas`, and it is deliberately the same thing: the drives row
 * is the `1fr` there and holds the `weight(1f)` here, so on both stations the
 * contacts take every pixel the chrome above them does not want, up to
 * [ContactButtonMaxHeight]. The sidebar is the browser's fixed `300px` column,
 * scaled and then capped — see [TelemetrySidebarWidth].
 *
 * Telemetry moves out rather than being dropped because a landscape window is
 * short: stacked under the drives it would either scroll the lamps out of sight
 * or take the height the contacts need. Beside them it costs width, which is
 * the axis that has room.
 *
 * No `requiredHeightIn` on the drives row: it carries only its own content, so
 * the portrait bank's [DriveBankMinHeight] — which budgets for a label, a
 * readout and an override notice as well — would demand more than a short
 * landscape window can give and report an overflow that is not real. The
 * contacts' own floor still holds, and [rememberOverflowWatch] still reports it
 * when they are pushed off the bottom.
 */
@Composable
private fun SidebarControlScreen(
  view: StationView,
  thrusterMode: ThrusterMode,
  trimDeg: Double,
  authError: String?,
  onArm: () -> Unit,
  onDisarm: () -> Unit,
  onThrusterModeChange: (ThrusterMode) -> Unit,
  onThrusterDirectionChange: (ThrusterDirection) -> Unit,
  onTrim: (Double) -> Unit,
  onPortChange: (DrivePosition) -> Unit,
  onStbdChange: (DrivePosition) -> Unit,
  onChangeServer: () -> Unit,
  windowWidth: Dp,
) {
  val helm = LocalHelmScale.current
  val gutter = helm.size(10.dp)
  val overflow = rememberOverflowWatch()
  // Scaled like everything else, but capped as a fraction of the window: at the
  // bottom of this arrangement's envelope an unscaled 300 dp would be half the
  // glass spent on lamps, which is the imbalance the tall layout was fixed for.
  val sidebar = minOf(helm.size(TelemetrySidebarWidth), windowWidth * TelemetrySidebarMaxFraction)

  Row(Modifier.fillMaxSize().padding(gutter).then(overflow.viewport)) {
    Column(Modifier.weight(1f).fillMaxHeight()) {
      KillSwitch(view = view, onArm = onArm, onDisarm = onDisarm)
      ClippedNotice(overflow.clipped)

      ThrusterControl(
        view = view,
        mode = thrusterMode,
        trimDeg = trimDeg,
        onModeChange = onThrusterModeChange,
        onDirectionChange = onThrusterDirectionChange,
        onTrim = onTrim,
        modifier = Modifier.padding(top = gutter).then(overflow.probe("thruster")),
      )

      Row(
        Modifier.fillMaxWidth().weight(1f).padding(top = gutter).testTag(DriveBankTag)
      ) {
        DriveControl(
          label = "Port",
          enabled = view.driveCommandable,
          overriddenBy = view.portOverriddenBy,
          onPositionChange = onPortChange,
          modifier = Modifier.weight(1f).fillMaxHeight().padding(end = helm.size(5.dp)),
        )
        DriveControl(
          label = "Starboard",
          enabled = view.driveCommandable,
          overriddenBy = view.stbdOverriddenBy,
          onPositionChange = onStbdChange,
          modifier = Modifier.weight(1f).fillMaxHeight().padding(start = helm.size(5.dp)),
          overflowProbe = overflow.probe("starboard"),
        )
      }
    }

    TelemetryPanel(
      view,
      authError,
      onChangeServer,
      Modifier.width(sidebar).fillMaxHeight().padding(start = gutter),
    )
  }
}

/**
 * The short-landscape fallback: kill switch across the top, then port and
 * starboard down the two outside edges with the thruster and telemetry between
 * them.
 *
 * This is what a window gets when it is wide but too short to stack a kill
 * switch, a thruster block and a drive bank on top of one another — a phone on
 * its side, a split-screen or freeform pane. [SidebarControlScreen] would
 * simply push the contacts off the bottom there and say so; this arrangement
 * still fits them, because each drive column carries nothing but its own two
 * contacts and runs the full height of the row.
 *
 * The drives are at the edges deliberately: the operator is holding the device
 * by its sides, and the two contacts that get held through a manoeuvre belong
 * under the two thumbs rather than side by side in the middle with the whole
 * width of the glass outside them.
 *
 * No `requiredHeightIn` on the row, for the same reason given above.
 */
@Composable
private fun EdgeControlScreen(
  view: StationView,
  thrusterMode: ThrusterMode,
  trimDeg: Double,
  authError: String?,
  onArm: () -> Unit,
  onDisarm: () -> Unit,
  onThrusterModeChange: (ThrusterMode) -> Unit,
  onThrusterDirectionChange: (ThrusterDirection) -> Unit,
  onTrim: (Double) -> Unit,
  onPortChange: (DrivePosition) -> Unit,
  onStbdChange: (DrivePosition) -> Unit,
  onChangeServer: () -> Unit,
) {
  val helm = LocalHelmScale.current
  val gutter = helm.size(10.dp)
  val overflow = rememberOverflowWatch()

  Column(Modifier.fillMaxSize().padding(gutter).then(overflow.viewport)) {
    KillSwitch(view = view, onArm = onArm, onDisarm = onDisarm)
    ClippedNotice(overflow.clipped)

    Row(Modifier.fillMaxWidth().weight(1f).padding(top = gutter).testTag(DriveBankTag)) {
      DriveControl(
        label = "Port",
        enabled = view.driveCommandable,
        overriddenBy = view.portOverriddenBy,
        onPositionChange = onPortChange,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )

      Column(Modifier.weight(1.25f).fillMaxHeight().padding(horizontal = gutter)) {
        ThrusterControl(
          view = view,
          mode = thrusterMode,
          trimDeg = trimDeg,
          onModeChange = onThrusterModeChange,
          onDirectionChange = onThrusterDirectionChange,
          onTrim = onTrim,
          modifier = overflow.probe("thruster"),
        )
        TelemetryPanel(
          view,
          authError,
          onChangeServer,
          Modifier.padding(top = gutter).weight(1f),
        )
      }

      DriveControl(
        label = "Starboard",
        enabled = view.driveCommandable,
        overriddenBy = view.stbdOverriddenBy,
        onPositionChange = onStbdChange,
        modifier = Modifier.weight(1f).fillMaxHeight(),
        overflowProbe = overflow.probe("starboard"),
      )
    }
  }
}

/**
 * The vertical split, as a measure policy rather than as weights.
 *
 * What is wanted is an order of priority — the bank's floor first, then
 * telemetry's natural height, then the bank up to its ceiling, then whatever is
 * still left back to telemetry — and a `Column` cannot express it. `weight(1f)`
 * on telemetry pins the bank at its minimum and gives every spare pixel to the
 * lamps, which is the defect this replaces; `weight(1f)` on the bank does the
 * reverse and starves telemetry on a tall screen; and a `requiredHeightIn` in
 * the same chain as a `weight` grows nothing at all, because the required form
 * discards the exact minimum the weight handed down (see `Modifier.contactHeight`).
 *
 * So the three regions are measured here directly:
 *
 * - **chrome** — kill switch, the off-screen notice, the thruster block — takes
 *   its natural height. Nothing negotiates with it; the stop button is not a
 *   thing that gives way.
 * - **the bank** gets what is left after telemetry's natural height, clamped
 *   between [bankMinHeight] and the height at which its contacts would hit
 *   [ContactButtonMaxHeight]. Below the floor it keeps the floor and overflows
 *   visibly, which is the failure the whole suite is built around.
 * - **telemetry** gets the remainder, which on a short screen is less than it
 *   wanted — it scrolls, and it is the only thing here that does.
 *
 * Intrinsics rather than a trial measure, because a `Measurable` may only be
 * measured once per pass; `maxIntrinsicHeight` asks the same question without
 * spending that.
 */
@Composable
private fun ControlSurface(
  bankMinHeight: Dp,
  contactGrowth: Dp,
  gap: Dp,
  chrome: @Composable () -> Unit,
  bank: @Composable () -> Unit,
  telemetry: @Composable () -> Unit,
  modifier: Modifier = Modifier,
) {
  Layout(contents = listOf(chrome, bank, telemetry), modifier = modifier) { slots, constraints ->
    val (chromeSlot, bankSlot, telemetrySlot) = slots
    val width = constraints.maxWidth
    val natural = Constraints(minWidth = width, maxWidth = width)

    val chromePlaceables = chromeSlot.map { it.measure(natural) }
    // The gaps belong to the layout rather than to a `padding` inside a region:
    // padding inside the bank would come OUT of its reservation, leaving it
    // 270 dp of the 280 it is supposed to guarantee -- the same trap that once
    // had the thruster contacts at 80 dp, and what the drive-bank measurement in
    // LayoutFloorsTest catches.
    val gapPx = gap.roundToPx()
    val chromeHeight = chromePlaceables.sumOf { it.height } + gapPx

    val bankWanted = bankSlot.sumOf { it.maxIntrinsicHeight(width) }
    val telemetryWanted = telemetrySlot.sumOf { it.maxIntrinsicHeight(width) }

    val height =
      if (constraints.hasBoundedHeight) constraints.maxHeight
      else chromeHeight + gapPx + bankWanted + telemetryWanted

    val available = (height - chromeHeight - gapPx).coerceAtLeast(0)

    // The bank as it measures with its contacts at their floor, plus the room
    // those two contacts have left to grow. Derived rather than written down, so
    // it stays right when the label and the readout inflate with the font scale.
    val bankFloor = bankMinHeight.roundToPx()
    val bankCeiling = (bankWanted + 2 * contactGrowth.roundToPx()).coerceAtLeast(bankFloor)
    val bankHeight = (available - telemetryWanted).coerceIn(bankFloor, bankCeiling)
    val telemetryHeight = (available - bankHeight).coerceAtLeast(0)

    val bankPlaceables =
      bankSlot.map { it.measure(natural.copy(minHeight = bankHeight, maxHeight = bankHeight)) }
    val telemetryPlaceables =
      telemetrySlot.map {
        it.measure(natural.copy(minHeight = telemetryHeight, maxHeight = telemetryHeight))
      }

    layout(width, height) {
      var y = 0
      chromePlaceables.forEach {
        it.place(0, y)
        y += it.height
      }
      y += gapPx
      bankPlaceables.forEach { it.place(0, y) }
      y += bankHeight + gapPx
      telemetryPlaceables.forEach { it.place(0, y) }
    }
  }
}

/**
 * Where the screen ends, and where the lowest LIVE control ends.
 *
 * The contact floors are un-negotiable ([ContactButtonMinHeight]), so a window
 * too short to hold them pushes a contact off the bottom rather than shrinking
 * it. That is the right trade -- but a control the operator cannot see is one
 * they do not know they have, so it has to be said out loud rather than left to
 * be discovered mid-manoeuvre. SAFETY.md invariant 6, applied to the controls
 * themselves rather than to a reading.
 *
 * The probe goes on the controls themselves rather than on the region holding
 * them, because a region's own height is whatever its parent gave it whether or
 * not the buttons inside overflowed it.
 */
@Composable
private fun rememberOverflowWatch(): OverflowWatch {
  var viewportBottomPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
  // Keyed rather than a running maximum: a max that only ever grows would latch
  // the notice on after one short-lived layout -- a rotation, a font-scale
  // change -- and never clear it. Each probe owns its own entry and overwrites
  // it, so the reading is always of where the controls are now.
  val bottoms = remember { mutableStateMapOf<String, Float>() }
  val liveBottomPx = bottoms.values.maxOrNull() ?: 0f

  return OverflowWatch(
    bottoms = bottoms,
    viewport =
      Modifier.onGloballyPositioned { viewportBottomPx = it.positionInRoot().y + it.size.height },
    clipped = liveBottomPx > viewportBottomPx + 1f,
  )
}

private class OverflowWatch(
  private val bottoms: MutableMap<String, Float>,
  val viewport: Modifier,
  val clipped: Boolean,
) {
  /** Reports where this live control ends, under its own name. */
  fun probe(name: String): Modifier =
    Modifier.onGloballyPositioned { bottoms[name] = it.positionInRoot().y + it.size.height }
}

@Composable
private fun ClippedNotice(clipped: Boolean) {
  if (!clipped) return
  val helm = LocalHelmScale.current
  Text(
    "Window too short — some drive controls are off screen. Use the full screen.",
    fontSize = helm.text(12.sp),
    color = DriveColors.bad,
    modifier =
      Modifier.fillMaxWidth()
        .padding(top = helm.size(6.dp))
        .semantics { contentDescription = "Some drive controls are off screen" }
        .testTag(ClippedWarningTag),
  )
}

/** The only scrollable thing on the screen, and it commands nothing. */
@Composable
private fun TelemetryPanel(
  view: StationView,
  authError: String?,
  onChangeServer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .testTag(TelemetryScrollTag)
  ) {
    StatusPanel(view = view, authError = authError, onChangeServer = onChangeServer)
  }
}
