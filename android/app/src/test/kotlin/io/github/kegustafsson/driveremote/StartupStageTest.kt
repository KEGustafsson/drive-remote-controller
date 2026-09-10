package io.github.kegustafsson.driveremote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app shows before it knows what it should be showing.
 *
 * A small test for a defect that was entirely a default value: [UiState.stage]
 * started at [Stage.NeedsServer], which is a *guess* made before the stored
 * server has been read. On a station that has been set up the guess is wrong
 * every launch, and the operator saw the setup screen flash up — starting an
 * mDNS browse and taking a multicast lock on its way past — before the control
 * panel replaced it a few frames later.
 *
 * The fix is a stage that shows nothing, and the thing worth defending is the
 * default itself: nothing else in the app is what decides the first frame.
 */
class StartupStageTest {

  @Test
  fun `a launch begins in a stage that shows nothing`() {
    assertEquals(
      "a fresh UiState must not name a SCREEN — the settings that decide which " +
        "screen it should be have not been read yet, and any answer here is a " +
        "guess the operator sees flash past",
      Stage.Starting,
      UiState().stage,
    )
  }

  /**
   * And the guess it must not be, named outright: `NeedsServer` is the one that
   * is wrong on every configured station, which is every station in use.
   */
  @Test
  fun `a launch does not begin on the setup screen`() {
    assertEquals(false, UiState().stage is Stage.NeedsServer)
  }
}
