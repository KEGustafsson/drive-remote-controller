package io.github.kegustafsson.driveremote.net

import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.ServerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A server that closes the stream must read as a dropped link at once.
 *
 * OkHttp stops reading when the peer's close frame arrives and only reports
 * `onClosed` once THIS side has answered with its own close. Nothing answered,
 * so after a server restart the station kept reporting an OPEN link with nothing
 * arriving and scheduled no reconnect, until a write or ping finally failed tens
 * of seconds later -- a live-looking link that was not one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkStreamServerCloseTest {

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

  @Test
  fun `a close frame from the server drops the link and reconnects`() {
    // The first connection is closed by the server as soon as it opens; the
    // second is simply held open, which is what a restarted server does.
    server.enqueue(
      MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            // Give the client a moment to register OPEN before the close lands.
            Thread.sleep(200)
            webSocket.close(1001, "going away")
          }
        }
      )
    )
    server.enqueue(
      MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
          // Answer the client's close at teardown, so the server can shut down.
          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
          }
        }
      )
    )

    stream.connect(
      ServerAddress(host = server.hostName, port = server.port, useTls = false),
      token = null,
    )

    assertTrue("the stream never opened", waitFor(5_000) { stream.everConnected })

    // Well inside any ping or close-handshake timeout: the close frame itself is
    // the news, and it must be acted on as it arrives.
    assertTrue(
      "a server-initiated close left the link reported OPEN, with no reconnect",
      waitFor(2_000) { server.requestCount >= 2 },
    )
    assertTrue(
      "the reconnect never came back up",
      waitFor(5_000) { stream.connectionState.value == ConnectionState.OPEN },
    )
  }
}
