package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [serverStreamLive], case for case with `sk-plugin/src/pure/serverStream.test.ts`. */
class ServerStreamTest {

  private fun live(
    connectionState: ConnectionState = ConnectionState.OPEN,
    receivedAtMs: Long? = 10_000,
    openedAtMs: Long? = 5_000,
    nowMs: Long = 10_100,
  ) = serverStreamLive(connectionState, receivedAtMs, openedAtMs, nowMs)

  @Test
  fun `is live while the arbiter publish keeps arriving on an open socket`() {
    assertTrue(live())
  }

  @Test
  fun `is not live once the publish stops arriving, though the socket reads open`() {
    assertFalse(live(nowMs = 10_000 + SkContract.SERVER_STREAM_STALE_MS + 1))
    assertTrue(live(nowMs = 10_000 + SkContract.SERVER_STREAM_STALE_MS))
  }

  @Test
  fun `does not count a pre-reconnect arrival as live, however recent`() {
    // Dropped and reopened within the window: the retained activeClient was
    // heard on the OLD socket and has not been re-sent on this one.
    assertFalse(live(receivedAtMs = 9_990, openedAtMs = 10_000))
    // The first arrival on the new socket is.
    assertTrue(live(receivedAtMs = 10_000, openedAtMs = 10_000))
  }

  @Test
  fun `is not live before anything has arrived, or while the socket is not open`() {
    assertFalse(live(receivedAtMs = null))
    assertFalse(live(connectionState = ConnectionState.CLOSED))
    assertFalse(live(connectionState = ConnectionState.CONNECTING))
  }

  @Test
  fun `treats an arrival stamped after a lagging clock read as fresh`() {
    assertTrue(live(nowMs = 10_000 - 50))
  }

  @Test
  fun `mirrors the browser's windows`() {
    // sk-plugin/src/config.ts: SERVER_STREAM_STALE_MS = RX_TELEMETRY_STALE_MS
    // = 1500, SK_STREAM_SILENCE_RECONNECT_MS = 5000. Hand-synced, so pinned.
    assertTrue(SkContract.SERVER_STREAM_STALE_MS == 1_500L)
    assertTrue(SkContract.SK_STREAM_SILENCE_RECONNECT_MS == 5_000L)
    assertTrue(SkContract.SK_STREAM_SILENCE_RECONNECT_MS > SkContract.SERVER_STREAM_STALE_MS)
  }
}
