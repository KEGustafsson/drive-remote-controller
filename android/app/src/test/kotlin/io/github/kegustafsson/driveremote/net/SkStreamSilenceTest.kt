package io.github.kegustafsson.driveremote.net

import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.ServerAddress
import io.github.kegustafsson.driveremote.core.SkContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A socket that stays open and delivers nothing is dropped and replaced.
 *
 * The far end vanishing without a FIN -- the Signal K host losing power, the
 * Wi-Fi path breaking -- leaves the socket OPEN, and this station sends nothing
 * after subscribing, so only OkHttp's 20 s ping would ever notice. The view
 * degrades to OFFLINE on arrival (SERVER_STREAM_STALE_MS, in :core); this is the
 * recovery half, the browser's `skClient.ts` silence watchdog.
 *
 * Silence is measured on `SystemClock.elapsedRealtime`, which Robolectric
 * holds still until told to move, so the tests wind it forward rather than wait
 * five real seconds; the watchdog's own poll is real time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkStreamSilenceTest {

  private lateinit var server: MockWebServer
  private lateinit var stream: SkStream

  @Before
  fun setUp() {
    server = MockWebServer().apply { start() }
    stream = SkStream(OkHttpClient(), CoroutineScope(Job()))
  }

  @After
  fun tearDown() {
    stream.close()
    server.shutdown()
  }

  private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return true
      Thread.sleep(10)
    }
    return condition()
  }

  /** Holds the socket open and says nothing, answering only a close at teardown. */
  private fun silentServer() =
    MockResponse().withWebSocketUpgrade(
      object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          webSocket.close(1000, null)
        }
      }
    )

  private fun connect() =
    stream.connect(
      ServerAddress(host = server.hostName, port = server.port, useTls = false),
      token = null,
    )

  @Test
  fun `a socket silent past the window is abandoned and reopened`() {
    server.enqueue(silentServer())
    server.enqueue(silentServer())
    connect()
    assertTrue("the stream never opened", waitFor(5_000) { stream.everConnected })
    val firstOpen = stream.openedAtMs

    ShadowSystemClock.advanceBy(Duration.ofMillis(SkContract.SK_STREAM_SILENCE_RECONNECT_MS + 1))

    assertTrue(
      "a silent socket was left standing OPEN with no reconnect",
      waitFor(3_000) { server.requestCount >= 2 },
    )
    assertTrue(
      "the replacement socket never came up",
      waitFor(5_000) {
        stream.connectionState.value == ConnectionState.OPEN && stream.openedAtMs != firstOpen
      },
    )
    assertNotEquals(firstOpen, stream.openedAtMs)
  }

  @Test
  fun `a socket quiet for less than the window is left alone`() {
    server.enqueue(silentServer())
    connect()
    assertTrue(waitFor(5_000) { stream.everConnected })

    ShadowSystemClock.advanceBy(Duration.ofMillis(SkContract.SK_STREAM_SILENCE_RECONNECT_MS - 500))
    // More than one watchdog poll in real time.
    Thread.sleep(2_500)

    assertEquals(1, server.requestCount)
    assertEquals(ConnectionState.OPEN, stream.connectionState.value)
  }

  /**
   * Silence is time since the last FRAME, not since the open: a stream that keeps
   * delivering is healthy however long it has been up.
   */
  @Test
  fun `a socket that keeps delivering is never abandoned`() {
    val serverSide = AtomicBoolean(true)
    server.enqueue(
      MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            Thread {
                while (serverSide.get()) {
                  webSocket.send(
                    """{"updates":[{"values":[{"path":"${SkContract.PLUGIN_ACTIVE_CLIENT}","value":""}]}]}"""
                  )
                  Thread.sleep(50)
                }
              }
              .start()
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            serverSide.set(false)
            webSocket.close(1000, null)
          }
        }
      )
    )
    connect()
    assertTrue(waitFor(5_000) { stream.everConnected })
    val firstRevision = stream.revision.value
    try {
      // 20 s of device time in 2 s of real time, in steps well inside the window,
      // with frames landing between every step.
      repeat(20) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
        Thread.sleep(100)
      }
      assertTrue("no frames arrived at all", stream.revision.value > firstRevision)
      assertEquals("a delivering socket was churned", 1, server.requestCount)
      assertEquals(ConnectionState.OPEN, stream.connectionState.value)
    } finally {
      serverSide.set(false)
    }
  }
}
