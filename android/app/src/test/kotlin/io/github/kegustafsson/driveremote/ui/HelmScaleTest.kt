package io.github.kegustafsson.driveremote.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale itself, away from any layout.
 *
 * `LayoutFloorsTest` measures what the scale *does* to the screen; this pins
 * the rule it follows, which is easier to read here than to infer from a
 * measured button height. No Robolectric: [helmScaleFor] is arithmetic.
 */
class HelmScaleTest {

  @Test
  fun `the reference phone is exactly 1`() {
    assertEquals(1f, helmScaleFor(360.dp, 780.dp).u, 0.001f)
  }

  /**
   * The divergence from the browser UI's `--u`, which clamps down to 0.72.
   *
   * Scaling below 1 would shrink [ContactButtonMinHeight] with everything else,
   * and that floor is the thing the whole layout suite exists to defend: an
   * 88 dp button that quietly becomes 63 dp on a short window is the defect,
   * not the fix for it. A short window overflows visibly instead.
   */
  @Test
  fun `nothing below the reference phone shrinks`() {
    assertEquals(1f, helmScaleFor(360.dp, 640.dp).u, 0.001f)
    assertEquals(1f, helmScaleFor(360.dp, 512.dp).u, 0.001f)
    assertEquals(1f, helmScaleFor(360.dp, 390.dp).u, 0.001f)
  }

  @Test
  fun `a 7 inch tablet scales by its shorter axis`() {
    // 600/360 = 1.67 wide, 960/780 = 1.23 tall. The tall one wins: the screen
    // is only as generous as its tightest dimension.
    assertEquals(1.23f, helmScaleFor(600.dp, 960.dp).u, 0.01f)
  }

  @Test
  fun `a 10 inch tablet stops at the clamp`() {
    assertEquals(MaxHelmScale, helmScaleFor(800.dp, 1280.dp).u, 0.001f)
  }

  /**
   * A landscape phone is 780 dp wide and 360 dp tall. Scaling it by its width
   * would inflate the type past what its 360 dp of height can hold — the axis
   * that is actually short has to be the one that decides.
   */
  @Test
  fun `a wide but short window is sized by its height`() {
    assertEquals(1f, helmScaleFor(780.dp, 360.dp).u, 0.001f)
  }

  /** The floors and the ceiling only ever move apart, never cross. */
  @Test
  fun `the contact ceiling stays above the floor at every scale`() {
    for (height in listOf(360, 512, 640, 780, 960, 1280, 2400)) {
      val helm = helmScaleFor(800.dp, height.dp)
      assertTrue(
        "ceiling ${helm.size(ContactButtonMaxHeight)} is not above the floor " +
          "${helm.size(ContactButtonMinHeight)} at ${height}dp",
        helm.size(ContactButtonMaxHeight) > helm.size(ContactButtonMinHeight),
      )
      assertTrue(
        "the floor fell below its literal 88 dp at ${height}dp",
        helm.size(ContactButtonMinHeight) >= ContactButtonMinHeight,
      )
    }
  }
}
