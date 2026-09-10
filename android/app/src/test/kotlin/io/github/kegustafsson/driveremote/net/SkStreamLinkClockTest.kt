package io.github.kegustafsson.driveremote.net

import android.os.SystemClock
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.LinkPhase
import io.github.kegustafsson.driveremote.core.SkContract
import io.github.kegustafsson.driveremote.core.linkPhaseOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The clock the start-up grace runs on, before anyone has called `connect`.
 *
 * This is a regression test for the second half of the OFFLINE flash, which
 * survived the first fix and was still visible on the tablet.
 *
 * [LinkPhase] was in place and correct, but it is fed `targetSetAtMs`, and that
 * was initialised to **0**. There is a real gap between this object being built
 * and `connect` being called — the stored server has to come out of DataStore
 * first — and the view is recomputed on a ticker that starts immediately, so it
 * is derived several times inside that gap. With 0 as the start, "how long have
 * we been connecting" evaluates to the device's entire uptime, which is past
 * any grace window, so the phase came out OFFLINE. The control screen's first
 * frame was therefore the amber panel, corrected a tick later.
 *
 * A rule that is right and an input that is wrong look identical from the
 * outside, which is why this is asserted at the input rather than only through
 * [linkPhaseOf].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkStreamLinkClockTest {

  private fun stream() = SkStream(OkHttpClient(), CoroutineScope(Job()))

  @Test
  fun `a stream that has not connected yet is not reported as long overdue`() {
    val now = SystemClock.elapsedRealtime()
    val stream = stream()

    assertFalse("a fresh stream cannot have been connected", stream.everConnected)
    assertTrue(
      "targetSetAtMs is ${stream.targetSetAtMs}, which is not a moment on the " +
        "elapsedRealtime clock — 0 means 'since device boot' and puts every " +
        "start-up past the grace window",
      stream.targetSetAtMs > 0L && stream.targetSetAtMs >= now,
    )
  }

  /**
   * The defect exactly as the operator met it: the view derived in the gap
   * between the ViewModel being built and the socket being told to open.
   *
   * **The clock is wound forward first, and without that this test proves
   * nothing.** Robolectric starts `elapsedRealtime` at zero, so the broken
   * initialiser -- also zero -- looks perfectly healthy: "connecting for 0 ms".
   * A real tablet has been up for hours, which is precisely why 0 evaluated to
   * "long overdue" there and not here. Written without this line the test
   * passed against the bug it is named for.
   */
  @Test
  fun `the view derived before connect reads as starting up, not offline`() {
    ShadowSystemClock.advanceBy(Duration.ofHours(6))
    val stream = stream()

    val phase =
      linkPhaseOf(
        connectionState = stream.connectionState.value,
        everConnected = stream.everConnected,
        connectingForMs = SystemClock.elapsedRealtime() - stream.targetSetAtMs,
      )

    assertEquals(
      "a station that has not been told to connect yet must read as starting " +
        "up; OFFLINE here is the amber flash on the STOP button at every launch",
      LinkPhase.CONNECTING,
      phase,
    )
  }

  /** And it is a window, not a mode: an unreachable server still becomes a fault. */
  @Test
  fun `the same stream reads offline once the grace has run out`() {
    val stream = stream()

    val phase =
      linkPhaseOf(
        connectionState = stream.connectionState.value,
        everConnected = stream.everConnected,
        connectingForMs = SkContract.LINK_STARTUP_GRACE_MS,
      )

    assertEquals(LinkPhase.OFFLINE, phase)
  }

  @Test
  fun `a fresh stream is closed, so nothing is commandable through it`() {
    assertEquals(ConnectionState.CLOSED, stream().connectionState.value)
  }
}
