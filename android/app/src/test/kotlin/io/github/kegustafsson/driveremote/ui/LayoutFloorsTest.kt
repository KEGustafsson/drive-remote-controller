package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import io.github.kegustafsson.driveremote.core.CommandSource
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.ControlState
import io.github.kegustafsson.driveremote.core.DisplayDrivePosition
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.ThrusterMode
import io.github.kegustafsson.driveremote.core.UnitLiveness
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The control screen's geometry, measured — because on this screen geometry is
 * a safety property, not a matter of taste.
 *
 * This exists because of two real defects, both of which reached a phone.
 *
 * **The squeeze.** Expanding the telemetry panel used to compress both drive
 * contacts from 131 dp to **4.7 dp** — a tenth of Android's 48 dp minimum touch
 * target — while they were still live and still commanding a machine. Nothing
 * caught it: `:core` holds the decisions and has no idea how tall anything is,
 * `:app` had no tests at all, and CI built no Android. A human found it reading
 * a `uiautomator dump`.
 *
 * **The drag.** The first fix put the controls inside a `verticalScroll`. A
 * momentary contact claims the pointer on touch down, so dragging to reach
 * telemetry pressed a drive or thruster contact for the whole drag, while
 * armed. The layout was the mistake, so the layout is what changed: nothing
 * that can command a machine has a scrolling ancestor now, and there is a test
 * below that walks the tree to keep it that way.
 *
 * Assertions are deliberately written against the *requirement* — literal 88 dp
 * and 280 dp — and not against [DriveBankMinHeight] or the private contact
 * constant. Asserting against the constants would let the code and its test
 * drift downward together, which is precisely the failure being guarded against.
 *
 * What this still does NOT prove, and cannot: that a thumb can find these
 * buttons on a wet, moving boat, that two fingers work at once, and that a drag
 * beginning on a contact really does neither scroll nor command. Robolectric
 * lays out a real Compose tree but injects no pointers, so the drag invariant is
 * checked structurally here and by hand on the phone. Those remain checklist
 * items in SAFETY.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = ReferencePhone)
class LayoutFloorsTest {

  @get:Rule val compose = createComposeRule()

  // ---- The floors --------------------------------------------------------

  @Test
  fun `drive contacts meet the touch-target floor as the screen opens`() {
    compose.showControlScreen()
    compose.assertContactFloors()
  }

  /**
   * The regression test for the 4.7 dp defect, in the exact state that caused
   * it: telemetry expanded, drives still live.
   */
  @Test
  fun `expanding telemetry cannot squeeze the drive contacts`() {
    compose.showControlScreen()
    compose.expandTelemetry()
    compose.assertContactFloors()
  }

  /**
   * A small phone — 720x1280 at xhdpi, the smallest screen anyone is plausibly
   * standing at a helm with. Expanding telemetry must not cost the contacts a
   * pixel here either: telemetry holds `weight(1f)`, so it is the thing that
   * gives up room, and it commands nothing.
   */
  @Test
  @Config(sdk = [35], qualifiers = SmallPhone)
  fun `a small phone keeps every contact at its floor`() {
    compose.showControlScreen()
    compose.expandTelemetry()
    compose.assertContactFloors()
  }

  /**
   * The documented bottom of the envelope, pinned so it cannot drift silently.
   *
   * Nothing scrolls above the telemetry panel — that is deliberate, and the
   * whole point of keeping live contacts out of a scrolling gesture — so once
   * the fixed content no longer fits, `heightIn(min = 280.dp)` gets coerced by
   * the Column's remaining space and the LAST child absorbs the shortfall: at
   * 504 dp the starboard REV contact measures 80 dp, at 480 dp it measures 56.
   * 512 dp is where everything still holds.
   *
   * That is well below any realistic phone (the reference S25 has 780 dp), but
   * the margin is thinner than it looks: `safeDrawingPadding` takes the status
   * and navigation bars and any display cutout off the top of this number, and
   * a large font-scale setting inflates the kill switch and the readouts. If
   * this test ever has to be relaxed, the layout needs an escape valve — not a
   * smaller number here.
   */
  @Test
  @Config(sdk = [35], qualifiers = MinimumSupportedScreen)
  fun `the supported minimum screen still holds every floor`() {
    compose.showControlScreen()
    compose.expandTelemetry()
    compose.assertContactFloors()
  }

  // ---- Enlarged system text ----------------------------------------------

  /**
   * The kill switch and the thruster readouts size off `sp`, so the system
   * font-scale setting takes room from everything below them — and the contacts
   * are `dp`, so they cannot shrink to compensate. That is the interaction, and
   * measured, it is worse than either factor alone:
   *
   * | viewport | 1.0x | 1.3x | 1.5x | 2.0x |
   * |---|---|---|---|---|
   * | 512 dp | 88 | 56.7 | — | **0.0** |
   * | 560 dp | — | — | 85 | 43 |
   * | 600 dp | — | — | 88 | 83 |
   * | 640 dp | 88 | 88 | 88 | 88 |
   *
   * Those were the numbers BEFORE the contacts moved to `requiredHeightIn`. A
   * 0 dp live button is the 4.7 dp collapse again in different clothes, and
   * split-screen on the reference phone reaches that regime. The floor is now
   * un-negotiable, so these cases must all hold.
   */
  @Test
  @Config(sdk = [35], qualifiers = SmallPhone)
  fun `enlarged system text cannot squeeze a contact on a small phone`() {
    compose.showControlScreen(fontScale = 2.0f)
    compose.expandTelemetry()
    compose.assertContactFloors()
  }

  @Test
  @Config(sdk = [35], qualifiers = MinimumSupportedScreen)
  fun `enlarged system text cannot squeeze a contact on the smallest viewport`() {
    // 512 dp at 2.0x measured a 0.0 dp REV contact before the floor was made
    // un-negotiable. This is the case that must never come back.
    compose.showControlScreen(fontScale = 2.0f)
    compose.expandTelemetry()
    compose.assertContactFloors()
  }

  /**
   * Split-screen on the reference phone: roughly half of 780 dp, at a font
   * scale someone who needs large text would actually be running. Well outside
   * the supported envelope, and precisely why the floor must hold rather than
   * negotiate — the operator does not stop being able to reach STOP or command
   * a drive because the window got shorter.
   */
  @Test
  @Config(sdk = [35], qualifiers = "w360dp-h390dp-xxhdpi")
  fun `a split-screen window still cannot squeeze a live contact`() {
    compose.showControlScreen(fontScale = 1.5f)
    compose.assertContactFloors()
    compose.killSwitch().assertIsDisplayed()
  }

  /**
   * Keeping the floor means the bank can be pushed off the bottom instead of
   * shrunk. That is the better trade, but a control the operator cannot see is
   * one they do not know they have — so it must be said, not discovered
   * mid-manoeuvre.
   */
  @Test
  @Config(sdk = [35], qualifiers = "w360dp-h390dp-xxhdpi")
  fun `says so when a live control is pushed off screen`() {
    compose.showControlScreen(fontScale = 1.5f)
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertExists()
  }

  /** ...and stays quiet on a screen where everything fits, at any font scale. */
  @Test
  fun `no off-screen warning on the reference phone`() {
    compose.showControlScreen(fontScale = 2.0f)
    compose.expandTelemetry()
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  @Config(sdk = [35], qualifiers = SmallPhone)
  fun `no off-screen warning on a small phone with large text`() {
    compose.showControlScreen(fontScale = 2.0f)
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun `the drive bank keeps its reserved height with telemetry expanded`() {
    compose.showControlScreen()
    compose.expandTelemetry()

    val box = compose.onNodeWithTag(DriveBankTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
    val reserved = box.bottom - box.top
    assertTrue(
      "the drive bank measured $reserved, below its $DriveBankReservation reservation",
      reserved >= DriveBankReservation,
    )
  }

  // ---- The leftover, and where it goes -----------------------------------

  /**
   * The other half of the floor rule, and the reason this section exists.
   *
   * A floor alone got the contacts to 88 dp and left them there: the bank was
   * unweighted at its 280 dp minimum while telemetry — which commands nothing —
   * held the `weight(1f)` and absorbed every spare pixel on the screen. On the
   * reference phone that was ~190 dp of lamps above an 88 dp drive button; on a
   * 10" tablet it was most of the glass. The leftover goes to the controls now,
   * and this is the assertion that keeps it there: a floor that is also the
   * measured height means the surplus went somewhere else again.
   *
   * 150 dp rather than the 185 dp it measures at, so ordinary drift in the
   * chrome above does not fail the build — but far enough above 88 that the
   * regression this guards against cannot hide under it.
   */
  @Test
  fun `the drive contacts take the leftover space, not telemetry`() {
    compose.showControlScreen()
    compose.assertDriveContactsAtLeast(150.dp)
  }

  /**
   * ...and it stops. Unbounded, the leftover on a 1280 dp tablet makes a single
   * ~500 dp contact, which is a longer reach rather than an easier target.
   * 348 dp is the reference ceiling of 240 dp at the scale's 1.45 clamp.
   */
  @Test
  @Config(sdk = [35], qualifiers = TabletPortrait)
  fun `a contact never grows past its ceiling`() {
    compose.showControlScreen()
    compose.assertContactCeiling(348.dp)
  }

  // ---- Tablets and landscape ---------------------------------------------

  /**
   * A 10" tablet held UPRIGHT, 800 x 1280 dp — and the case this section was
   * rewritten for.
   *
   * Width alone used to choose the arrangement, so this window got the
   * drives-at-the-edges one: a 250 dp middle column in which "BOW THRUSTER"
   * wrapped one letter per line and the fourth trim button fell off the side.
   * An upright tablet is the same shape as an upright phone and gets the same
   * stack, which is also what the browser UI does at this width.
   *
   * [assertStackedArrangement] is what pins that, and it is the assertion that
   * would have caught the defect: the thruster block sits ABOVE both drives
   * rather than between them.
   */
  @Test
  @Config(sdk = [35], qualifiers = TabletPortrait)
  fun `a tablet in portrait uses the stacked arrangement`() {
    compose.showControlScreen()
    compose.assertStackedArrangement()
    compose.assertContactFloors()
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  @Config(sdk = [35], qualifiers = TabletPortrait)
  fun `a tablet in portrait grows the contacts and clips nothing`() {
    compose.showControlScreen()
    compose.assertContactFloors()
    compose.assertDriveContactsAtLeast(250.dp)
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  /**
   * The same tablet on its side, 1280 x 800 — the browser UI's wide layout:
   * the stack keeps the main column and telemetry moves out to a sidebar.
   *
   * The stack assertion holds here too and means something different: it says
   * telemetry went sideways, rather than the drives being pushed out to the
   * edges around it.
   */
  @Test
  @Config(sdk = [35], qualifiers = TabletLandscape)
  fun `a tablet in landscape puts telemetry beside the stack`() {
    compose.showControlScreen()
    compose.assertStackedArrangement()
    compose.assertTelemetryBesideTheDrives()
    compose.assertContactFloors()
    compose.assertDriveContactsAtLeast(200.dp)
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  /**
   * A 7" tablet upright, 600 x 960 — exactly on [WideLayoutMinWidth], so this
   * is the case that would break first if that threshold were wrong. Upright,
   * so it stacks like every other portrait window.
   */
  @Test
  @Config(sdk = [35], qualifiers = SmallTablet)
  fun `the smallest tablet still holds every floor`() {
    compose.showControlScreen()
    compose.assertStackedArrangement()
    compose.assertContactFloors()
    compose.assertDriveContactsAtLeast(200.dp)
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  /**
   * 900 x 500 dp — a wide window at exactly [SidebarLayoutMinHeight], so the
   * shortest one the sidebar arrangement is ever asked to hold. This is where
   * the contacts start going off the bottom if that threshold is set too low,
   * which is the only thing choosing it is for.
   */
  @Test
  @Config(sdk = [35], qualifiers = ShortestSidebarWindow)
  fun `the shortest sidebar window keeps every contact on screen`() {
    compose.showControlScreen()
    compose.assertStackedArrangement()
    compose.assertContactFloors()
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  /**
   * The reference phone turned on its side: 780 x 360 dp — wide, but far too
   * short to stack, so this is the drives-at-the-edges fallback rather than the
   * sidebar arrangement.
   *
   * The manifest still locks PHONES to portrait, so a phone does not reach this
   * by rotating; a split-screen or freeform window can be this shape on
   * anything, and a tablet reaches it in multi-window. 360 dp of height is the
   * hardest case any landscape arrangement has: measured 93 dp contacts, and 88
   * at 1.5x system font.
   */
  @Test
  @Config(sdk = [35], qualifiers = PhoneLandscape)
  fun `a landscape window keeps every contact on screen`() {
    compose.showControlScreen()
    compose.assertContactFloors()
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  @Config(sdk = [35], qualifiers = PhoneLandscape)
  fun `a landscape window survives enlarged system text`() {
    compose.showControlScreen(fontScale = 1.5f)
    compose.assertContactFloors()
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  /**
   * The scale and the font-scale setting multiply, so a tablet at 2.0x is
   * running type at nearly three times the reference. There is room for it
   * there; this is the assertion that says so rather than assuming it.
   */
  @Test
  @Config(sdk = [35], qualifiers = TabletPortrait)
  fun `a tablet at double system text still holds every floor`() {
    compose.showControlScreen(fontScale = 2.0f)
    compose.expandTelemetry()
    compose.assertContactFloors()
    compose.onNodeWithTag(ClippedWarningTag, useUnmergedTree = true).assertDoesNotExist()
  }

  // ---- Nothing live inside a scrolling gesture ---------------------------

  /**
   * The invariant that matters most here, asserted structurally rather than by
   * its symptoms.
   *
   * A momentary contact claims the pointer on touch **down**, so a drag that
   * begins on one is a press. If a scrolling ancestor then claims that gesture
   * as a drag, the operator gets a scroll *and* a live command for the whole
   * drag, while armed — `momentaryPress` releases on `isConsumed` as a
   * backstop, but this layout is the primary defence and it is the one that
   * can be checked here.
   *
   * Walking the semantics tree catches a scrolling ancestor being introduced
   * even in a state where scrolling happens not to move anything, which an
   * assertion about positions would miss.
   */
  @Test
  fun `no live control has a scrolling ancestor`() {
    compose.showControlScreen()
    compose.expandTelemetry()
    compose.assertNoLiveControlScrolls()
  }

  /**
   * And in the sidebar arrangement, where the scrolling telemetry panel is no
   * longer below the drives but *beside* them — a sibling in the same row.
   * Being next to a scroll region is as good as being above one only if the
   * tree says so, which is what this checks.
   */
  @Test
  @Config(sdk = [35], qualifiers = TabletLandscape)
  fun `no live control has a scrolling ancestor in the sidebar arrangement`() {
    compose.showControlScreen()
    compose.expandTelemetry()
    compose.assertNoLiveControlScrolls()
  }

  /** ...and in the short-landscape fallback, where it is between them. */
  @Test
  @Config(sdk = [35], qualifiers = PhoneLandscape)
  fun `no live control has a scrolling ancestor in the edge arrangement`() {
    compose.showControlScreen()
    compose.expandTelemetry()
    compose.assertNoLiveControlScrolls()
  }

  private fun ComposeContentTestRule.assertNoLiveControlScrolls() {
    for (label in LiveControls) {
      val nodes = compose.onAllNodesWithContentDescription(label, useUnmergedTree = true)
      for (index in nodes.fetchSemanticsNodes().indices) {
        var ancestor = nodes[index].fetchSemanticsNode().parent
        while (ancestor != null) {
          val scrolls =
            ancestor.config.contains(SemanticsProperties.VerticalScrollAxisRange) ||
              ancestor.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
          assertTrue(
            "'$label' #$index has a scrolling ancestor: a drag beginning on it " +
              "would scroll AND command for the length of the drag",
            !scrolls,
          )
          ancestor = ancestor.parent
        }
      }
    }
  }

  /**
   * The telemetry panel is the one thing that does scroll, and scrolling it
   * must move nothing that commands a machine — including the kill switch,
   * because a STOP that can be scrolled off-screen would be a worse defect
   * than the button-collapse this layout replaced.
   */
  @Test
  fun `scrolling telemetry moves no live control`() {
    compose.showControlScreen()
    compose.expandTelemetry()

    val before = compose.liveControlBounds()
    // This view is armed, so the disconnect control is the inert "unavailable
    // while armed" variant. It is still the bottom-most thing in the scrolling
    // region, which is all this needs it for.
    compose.onNodeWithContentDescription("Disconnect, unavailable while armed").performScrollTo()
    val after = compose.liveControlBounds()

    assertEquals("scrolling telemetry moved a live control", before, after)
    compose.killSwitch().assertIsDisplayed()
  }

  /**
   * The kill switch is the first child and nothing above it can grow, so STOP
   * stays reachable even on a screen too short for everything below it. This is
   * checked below the supported minimum on purpose: it is the one control that
   * must survive a case the rest of the layout does not.
   */
  @Test
  @Config(sdk = [35], qualifiers = "w360dp-h480dp-xxhdpi")
  fun `the kill switch survives a screen below the supported minimum`() {
    compose.showControlScreen()
    compose.expandTelemetry()
    compose.killSwitch().assertIsDisplayed()
  }
}

// ---- Harness -------------------------------------------------------------

/**
 * The reference device: Galaxy S25, 1080x2340 at density 480 (xxhdpi), which is
 * 360 x 780 dp. The screenshots in android/README.md are this screen.
 */
private const val ReferencePhone = "w360dp-h780dp-xxhdpi"

/** A budget phone: 720x1280 at xhdpi. The smallest screen realistically used. */
private const val SmallPhone = "w360dp-h640dp-xxhdpi"

/**
 * The measured bottom of the envelope. Below this the last drive contact starts
 * shrinking 1 dp for every dp missing — see the test that uses it.
 */
private const val MinimumSupportedScreen = "w360dp-h512dp-xxhdpi"

/** A 10" tablet, both ways up, and a 7" one at exactly the wide threshold. */
private const val TabletPortrait = "w800dp-h1280dp-xhdpi"
private const val TabletLandscape = "w1280dp-h800dp-xhdpi"
private const val SmallTablet = "w600dp-h960dp-xhdpi"
/**
 * A wide window at exactly [SidebarLayoutMinHeight] — the shortest that gets
 * the sidebar arrangement rather than the drives-at-the-edges fallback.
 */
private const val ShortestSidebarWindow = "w900dp-h500dp-xhdpi"

/** The reference phone on its side — the shortest window the wide layout gets. */
private const val PhoneLandscape = "w780dp-h360dp-xxhdpi"

/** Android's minimum touch target, and the floor every contact button holds. */
private val TouchTargetFloor = 88.dp

/** Float-rounding slack only. See the comment at its use site. */
private val RoundingSlack = 0.01.dp

/**
 * Every control that can reach a machine: both drives' contacts, the thruster's
 * contacts, and the kill switch. None of these may sit in a scrolling gesture
 * region or be moved by one.
 */
private val LiveControls = listOf("FWD", "REV", "PORT", "STBD", KillSwitchDescription)

/** What the port/starboard bank reserves, whatever else wants the room. */
private val DriveBankReservation = 280.dp

private fun ComposeContentTestRule.showControlScreen(
  view: StationView = armedAndLive,
  fontScale: Float = 1f,
) {
  setContent {
    // Font scale is applied the way Android applies it -- through Density, so
    // every `sp` dimension inflates and every `dp` one does not. The kill
    // switch and the readouts size off sp; the contact floors are dp.
    val base = LocalDensity.current
    CompositionLocalProvider(
      LocalDensity provides Density(density = base.density, fontScale = fontScale)
    ) {
    DriveRemoteTheme {
      Box(Modifier.fillMaxSize()) {
        ControlScreen(
          view = view,
          thrusterMode = ThrusterMode.MANUAL,
          trimDeg = 0.0,
          authError = null,
          onArm = {},
          onDisarm = {},
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
  }
}

private fun ComposeContentTestRule.expandTelemetry() {
  onNodeWithContentDescription("Show telemetry detail").performClick()
}

/** The armed kill switch's description: "$label. $sub" with nothing missing. */
private const val KillSwitchDescription = "ARMED. tap to disarm"

private fun ComposeContentTestRule.killSwitch() =
  onNodeWithContentDescription(KillSwitchDescription)

/** Where every live control sits, as one comparable snapshot. */
private fun ComposeContentTestRule.liveControlBounds(): Map<String, DpRect> =
  buildMap {
    for (label in LiveControls) {
      val nodes = onAllNodesWithContentDescription(label, useUnmergedTree = true)
      for (index in nodes.fetchSemanticsNodes().indices) {
        put("$label#$index", nodes[index].getUnclippedBoundsInRoot())
      }
    }
  }

/**
 * Every momentary contact on the screen: both drives' FWD and REV, and the
 * thruster's PORT and STBD. All four are held through a manoeuvre and all four
 * hold the same floor.
 */
private fun ComposeContentTestRule.assertContactFloors() {
  for (label in listOf("FWD", "REV", "PORT", "STBD")) {
    // Unmerged: these buttons declare their own contentDescription, and the
    // merged tree would fold in sibling text and report the wrong node's box.
    val found =
      onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().size
    assertTrue("no '$label' contact found on the control screen", found > 0)

    for (index in 0 until found) {
      val box = contact(label, index).getUnclippedBoundsInRoot()
      val height: Dp = box.bottom - box.top
      // Tolerance is for float rounding only -- an sp-driven layout lands on
      // 87.99997 dp where it means 88. It is deliberately far smaller than any
      // real squeeze: the failures this suite exists to catch were 56 dp, 4.7 dp
      // and 0 dp, none of which a hundredth of a dp could hide.
      assertTrue(
        "'$label' contact #$index measured $height, below the $TouchTargetFloor floor",
        height >= TouchTargetFloor - RoundingSlack,
      )
    }
  }
}

private fun ComposeContentTestRule.contact(label: String, index: Int) =
  onAllNodesWithContentDescription(label, useUnmergedTree = true)[index]

/** No contact anywhere may exceed [ceiling] — the growth has to stop somewhere. */
private fun ComposeContentTestRule.assertContactCeiling(ceiling: Dp) {
  for (label in listOf("FWD", "REV", "PORT", "STBD")) {
    val found =
      onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().size
    for (index in 0 until found) {
      val box = contact(label, index).getUnclippedBoundsInRoot()
      val height = box.bottom - box.top
      assertTrue(
        "'$label' contact #$index measured $height, above the $ceiling ceiling",
        height <= ceiling + RoundingSlack,
      )
    }
  }
}

/**
 * The stack: the thruster block spans BOTH drives rather than sitting between
 * them.
 *
 * Horizontal spans, not vertical ones. That is what actually separates the two
 * arrangements and it cannot be faked by vertical centring: stacked, the
 * thruster runs the full width above the drives and so overlaps each of them,
 * while in the drives-at-the-edges fallback it is a middle column between
 * drives pushed out to the edges and overlaps neither.
 *
 * This is the assertion that would have caught the upright tablet going into
 * the wrong arrangement — the defect that put "BOW THRUSTER" one letter per
 * line in a 250 dp column.
 */
private fun ComposeContentTestRule.assertStackedArrangement() {
  val left = contact("PORT", 0).getUnclippedBoundsInRoot().left
  val right = contact("STBD", 0).getUnclippedBoundsInRoot().right

  for (index in 0 until 2) {
    val drive = contact("FWD", index).getUnclippedBoundsInRoot()
    assertTrue(
      "the thruster block spans $left..$right, which does not cover drive " +
        "#$index at ${drive.left}..${drive.right} — this is the " +
        "drives-at-the-edges arrangement, not the stack",
      left < drive.right && right > drive.left,
    )
  }
}

/**
 * The other half of the browser UI's wide layout: telemetry is beside the
 * drives, not under them. Stacked, its left edge is the left edge of the
 * screen; in the sidebar arrangement it starts where the drive bank ends.
 */
private fun ComposeContentTestRule.assertTelemetryBesideTheDrives() {
  val telemetry =
    onNodeWithTag(TelemetryScrollTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
  val drives = onNodeWithTag(DriveBankTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
  assertTrue(
    "telemetry starts at ${telemetry.left}, left of where the drive bank ends " +
      "(${drives.right}) — it is still stacked under the drives",
    telemetry.left >= drives.right,
  )
}

/**
 * The drives specifically — FWD and REV, the pair held through a manoeuvre. The
 * thruster's contacts sit in the screen's natural-height chrome and scale rather
 * than growing into leftover space, so they are deliberately not held to this.
 */
private fun ComposeContentTestRule.assertDriveContactsAtLeast(floor: Dp) {
  for (label in listOf("FWD", "REV")) {
    for (index in 0 until 2) {
      val box = contact(label, index).getUnclippedBoundsInRoot()
      val height = box.bottom - box.top
      assertTrue(
        "'$label' drive contact #$index measured $height on a window with room to " +
          "spare, below the $floor this size of screen should be giving it",
        height >= floor - RoundingSlack,
      )
    }
  }
}

/**
 * Armed, both units answering, nothing overriding — the state in which every
 * control is live and therefore the state whose geometry matters most.
 */
private val armedAndLive =
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
    currentHeadingDeg = 172.0,
    reversalPending = false,
    rxLinkUp = true,
    rxLinkOk = true,
    rxMasterEnable = true,
    hhArmed = true,
    thrusterState = "off",
  )
