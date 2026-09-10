package io.github.kegustafsson.driveremote.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * The proportional scale — one number, derived from the window, that every
 * dimension in the control screen is written against.
 *
 * This is the Kotlin twin of the browser UI's `--u`
 * (`sk-plugin/src/index.css`), deliberately so: the same reference device, the
 * same upper clamp, the same idea that a size written `30` means "30 dp on the
 * reference phone, scaled". All three command stations are meant to read as one
 * system, and a phone-tuned Android layout stretched across a tablet would have
 * been the one that did not.
 *
 * ```css
 * --u: clamp(0.72px, calc(100dvh / 780), 1.45px);
 * ```
 *
 * **What varies between devices is dp, not pixels.** Compose already cancels
 * density out, so a 1080p and a 1440p phone of the same physical size render
 * identical buttons and neither needs anything from this file. What differs is
 * the *window*: 360 x 780 dp on the reference phone, 800 x 1280 on a 10" tablet,
 * 360 x 390 in split screen. That is the axis this scales on, and it is read
 * from the actual constraints rather than from the device, so a split-screen or
 * freeform window gets the size it really has.
 *
 * **The lower clamp is 1.0, where the browser's is 0.72 — a deliberate
 * divergence.** The web UI must fit inside desktop browser windows with
 * toolbars, so it shrinks. This app instead holds its floors and lets a control
 * overflow visibly off the bottom (see [ContactButtonMinHeight]), which is the
 * failure the layout tests are built around. Scaling *down* here would quietly
 * shrink those floors and defeat that, so the scale only ever grows: at the
 * reference phone and anything smaller it is exactly 1.0, and every dimension is
 * the number that was measured and tuned on the boat.
 */
@Immutable
class HelmScale(val u: Float) {
  /** A dimension written at its reference-phone value, scaled. */
  fun size(reference: Dp): Dp = reference * u

  /**
   * A type size written at its reference-phone value, scaled.
   *
   * This multiplies the `sp` value, so the operator's system font-scale setting
   * still applies on top of it — the two compound, which is why the layout
   * tests run the tablet cases at `fontScale = 2.0f` as well.
   */
  fun text(reference: TextUnit): TextUnit = reference * u
}

/**
 * The reference device: Galaxy S25, 360 x 780 dp. Every dimension in the UI is
 * the value that was measured and tuned at this size.
 */
val ReferenceWindowWidth = 360.dp
val ReferenceWindowHeight = 780.dp

/**
 * The upper clamp, matching the browser UI's. Unbounded growth is not a better
 * helm display: type stops needing to be larger once it is legible at arm's
 * length, and a control panel whose every gap has been inflated 3x reads as a
 * mistake rather than as a tablet layout. On a big screen the *buttons* grow —
 * that happens through the layout taking the leftover space, not through this.
 */
const val MaxHelmScale = 1.45f

/**
 * The window width below which there is no landscape arrangement at all — a
 * window this narrow gets the stacked one whatever shape it is.
 *
 * Width alone no longer chooses the arrangement; see [ControlScreen]. An
 * upright tablet is 800 dp wide and still wants the stack.
 */
val WideLayoutMinWidth = 600.dp

/**
 * The height a landscape window needs before telemetry moves to a sidebar and
 * the drives keep the main column — the browser UI's wide layout.
 *
 * Below this the stack does not fit: a kill switch, a thruster block and two
 * contacts at their floor come to roughly 500 dp at the reference scale, and a
 * shorter window would simply push the contacts off the bottom and say so.
 * Those windows — a phone on its side, a split-screen pane — get the
 * drives-at-the-edges arrangement instead.
 */
val SidebarLayoutMinHeight = 500.dp

/**
 * The telemetry sidebar, at the reference scale: the browser UI's fixed
 * `300px` status column (`sk-plugin/src/index.css`).
 */
val TelemetrySidebarWidth = 300.dp

/**
 * ...and the most of the window it may ever take. At the bottom of the
 * landscape envelope the scaled 300 dp would be half the glass spent on lamps,
 * which is the same imbalance the drives were rescued from.
 */
const val TelemetrySidebarMaxFraction = 0.32f

/**
 * The floor for every momentary contact button -- the drives' FWD/REV and the
 * thruster's PORT/STBD alike.
 *
 * One constant rather than a number per call site, because these are the
 * controls a thumb has to find without looking, and the drives must never end
 * up the smaller pair: they are the ones held through a manoeuvre.
 *
 * This is a floor everywhere, and it is applied with `requiredHeightIn` rather
 * than `heightIn` -- deliberately, because the ordinary form is negotiable.
 * `heightIn(min = ...)` is coerced into whatever the parent offers, so on a
 * viewport too short for the whole screen the last child simply absorbs the
 * shortfall: measured at 56 dp on a 512 dp viewport at 1.3x system font scale,
 * and **0 dp** at 2.0x -- a live, commanding button with no height at all. That
 * is the same defect as the original 4.7 dp collapse wearing different clothes,
 * and it is reachable on the reference phone in split-screen.
 *
 * `requiredHeightIn` ignores the incoming maximum, so the button keeps its size
 * and the overflow goes off the bottom of the screen instead. A control that is
 * visibly cut off is a far better failure than one that is quietly too small to
 * hit: the first is obvious, the second invites a mis-press while armed.
 *
 * [HelmScale.size] is applied to it, which can only ever raise it — the scale's
 * lower clamp is 1.0 — so the literal 88 dp remains a true floor everywhere.
 */
val ContactButtonMinHeight = 88.dp

/**
 * The ceiling for the same buttons, at the reference phone — scaled alongside
 * the floor, so a 10" tablet stops at 348 dp rather than at a phone's 240.
 *
 * There has to be a ceiling. The leftover height on a 1280 dp tablet would
 * otherwise produce a single ~500 dp contact, which is not a better control,
 * just a longer reach: past a point the button stops being easier to hit and
 * starts making the operator move their hand. Beyond the ceiling the surplus
 * goes to telemetry (tall arrangement) or is left as slack the column centres
 * the contacts in (wide arrangement) — FWD and REV stay adjacent under one
 * thumb either way, rather than being stretched to opposite edges of the glass.
 */
val ContactButtonMaxHeight = 240.dp

/**
 * Height reserved for the port/starboard bank as a whole: two contacts at
 * [ContactButtonMinHeight] plus the label, the position readout and the
 * override notice between and below them. A MINIMUM, not a target -- on any
 * screen with room to spare the bank gets the leftover and the contacts grow
 * into it, up to [ContactButtonMaxHeight].
 */
val DriveBankMinHeight = 280.dp

/** The scale in force. Provided by [ControlScreen] from the real window size. */
val LocalHelmScale = staticCompositionLocalOf { HelmScale(1f) }

/**
 * The scale for a window of this size.
 *
 * The smaller of the two ratios wins, so a window that is generous in one axis
 * and tight in the other -- a landscape phone, a split-screen pane -- is sized
 * by the axis that is actually short rather than being inflated by the other.
 */
fun helmScaleFor(width: Dp, height: Dp): HelmScale {
  val byWidth = width / ReferenceWindowWidth
  val byHeight = height / ReferenceWindowHeight
  return HelmScale(minOf(byWidth, byHeight).coerceIn(1f, MaxHelmScale))
}
