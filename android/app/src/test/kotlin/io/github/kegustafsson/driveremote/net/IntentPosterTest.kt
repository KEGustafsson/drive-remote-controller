package io.github.kegustafsson.driveremote.net

import io.github.kegustafsson.driveremote.core.AuthScheme
import io.github.kegustafsson.driveremote.core.ClientIntent
import io.github.kegustafsson.driveremote.core.DrivePosition
import io.github.kegustafsson.driveremote.core.ServerAddress
import io.github.kegustafsson.driveremote.core.ThrusterDirection
import io.github.kegustafsson.driveremote.core.ThrusterMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The write side's auth-scheme probe, and what it must not learn from a server
 * the station has already left.
 *
 * `app/` carries no other behaviour tests -- the rule is that decisions live in
 * `:core` -- but this one cannot: the defect is about a reply racing a change of
 * target, which is a property of the HTTP shell itself and invisible to any pure
 * test. `mockwebserver` had been declared in `app/build.gradle.kts` for tests
 * that did not exist; this is the case that needed it.
 */
class IntentPosterTest {

  private lateinit var serverA: MockWebServer
  private lateinit var serverB: MockWebServer
  private lateinit var poster: IntentPoster

  @Before
  fun setUp() {
    serverA = MockWebServer().apply { start() }
    serverB = MockWebServer().apply { start() }
    poster =
      IntentPoster(
        OkHttpClient.Builder()
          .callTimeout(5, TimeUnit.SECONDS)
          .readTimeout(5, TimeUnit.SECONDS)
          .build()
      )
  }

  @After
  fun tearDown() {
    serverA.shutdown()
    serverB.shutdown()
  }

  private fun address(server: MockWebServer) =
    ServerAddress(host = server.hostName, port = server.port, useTls = false)

  private fun intent(seq: Long) =
    ClientIntent(
      clientId = "test",
      session = 1,
      seq = seq,
      armReq = 0,
      disarmReq = 0,
      port = DrivePosition.NEUTRAL,
      stbd = DrivePosition.NEUTRAL,
      thruster = ThrusterDirection.OFF,
      thrusterMode = ThrusterMode.MANUAL,
      trimDeg = 0.0,
    )

  @Test
  fun `probes Bearer first and advances to JWT after a refusal`() {
    serverA.enqueue(MockResponse().setResponseCode(401))
    serverA.enqueue(MockResponse().setResponseCode(200))

    val first = runBlocking { poster.send(address(serverA), intent(1), "tok") }
    assertTrue("expected an Unauthorized verdict, got $first", first is IntentPoster.Result.Unauthorized)
    assertEquals(401, (first as IntentPoster.Result.Unauthorized).code)
    assertEquals(AuthScheme.BEARER, first.schemeTried)
    assertEquals("Bearer tok", serverA.takeRequest().headers["Authorization"])

    runBlocking { poster.send(address(serverA), intent(2), "tok") }
    assertEquals("JWT tok", serverA.takeRequest().headers["Authorization"])
  }

  @Test
  fun `changing server restarts the probe from the first scheme`() {
    serverA.enqueue(MockResponse().setResponseCode(401))
    serverB.enqueue(MockResponse().setResponseCode(200))

    runBlocking { poster.send(address(serverA), intent(1), "tok") }
    serverA.takeRequest()

    runBlocking { poster.send(address(serverB), intent(2), "tokB") }
    assertEquals("Bearer tokB", serverB.takeRequest().headers["Authorization"])
  }

  /**
   * Choosing a new server must cut off the old server's replies immediately,
   * not when the next request happens to be sent.
   *
   * The generation otherwise advances only inside `send()`, and during setup
   * nothing is sent: a server with no stored token goes to the access-request
   * screen and waits for approval. Throughout that wait the old server is still
   * the current target, so its delayed reply would classify as current and land
   * on the new session — ending a setup that was going fine, or later counting
   * against a token that had only just been issued.
   */
  @Test
  fun `abandoning in flight makes a late reply stale with no new request sent`() {
    serverA.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(400, TimeUnit.MILLISECONDS))

    val late = runBlocking {
      val inFlight = async(Dispatchers.IO) { poster.send(address(serverA), intent(1), "tokA") }
      Thread.sleep(80)
      // The operator picks a different server. No request is sent to it -- there
      // is no token yet -- so only this call can invalidate the old reply.
      poster.abandonInFlight()
      inFlight.await()
    }

    assertTrue("late reply survived a session change: $late", late is IntentPoster.Result.Failed)
    serverA.takeRequest()
  }

  /** After abandoning, the next server's probe starts from the first scheme. */
  @Test
  fun `abandoning resets the probe for whatever comes next`() {
    serverA.enqueue(MockResponse().setResponseCode(401))
    runBlocking { poster.send(address(serverA), intent(1), "tok") }
    serverA.takeRequest() // probe has advanced to JWT for this target

    poster.abandonInFlight()

    serverB.enqueue(MockResponse().setResponseCode(200))
    runBlocking { poster.send(address(serverB), intent(2), "tokB") }
    assertEquals("Bearer tokB", serverB.takeRequest().headers["Authorization"])
  }

  /**
   * The defect. Teardown's final intent is sent outside the send lane, so the
   * operator can choose a new server while a request to the old one is still in
   * flight. That reply must not touch the new server's probe.
   *
   * Left unfixed it is not a cosmetic ordering wrinkle: the stale 401 moves the
   * new server's probe off a scheme that server never refused, and its verdict
   * would count against the new token in `TokenHealth`.
   */
  @Test
  fun `a 401 from the previous server cannot move the new server's probe`() {
    // Server A answers slowly, so its 401 lands after the switch to B.
    serverA.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(400, TimeUnit.MILLISECONDS))
    serverB.enqueue(MockResponse().setResponseCode(401))
    serverB.enqueue(MockResponse().setResponseCode(200))

    val stale = runBlocking {
      val inFlight = async(Dispatchers.IO) { poster.send(address(serverA), intent(1), "tokA") }
      // Give A's request time to leave before retargeting.
      Thread.sleep(80)
      poster.send(address(serverB), intent(2), "tokB")
      inFlight.await()
    }

    // The stale reply is reported as a transport-style failure, which feeds
    // nothing: not the scheme probe, and not TokenHealth.
    assertTrue("stale reply leaked as an auth verdict: $stale", stale is IntentPoster.Result.Failed)

    serverA.takeRequest()
    assertEquals("Bearer tokB", serverB.takeRequest().headers["Authorization"])

    // B's own 401 is what advances B's probe -- and it starts from Bearer, not
    // from wherever A's reply would have left it.
    runBlocking { poster.send(address(serverB), intent(3), "tokB") }
    assertEquals("JWT tokB", serverB.takeRequest().headers["Authorization"])
  }

  /**
   * Settled on JWT, the probe must still get back to Bearer when refusals
   * resume. A probe that only climbs presented a revoked token with JWT alone,
   * forever -- and TokenHealth, which needs EVERY scheme refused, then never
   * declared it dead, so the station kept presenting as able to command.
   */
  @Test
  fun `a probe settled on the last scheme wraps back to the first on refusal`() {
    serverA.enqueue(MockResponse().setResponseCode(401)) // Bearer refused
    serverA.enqueue(MockResponse().setResponseCode(200)) // JWT accepted: settled
    serverA.enqueue(MockResponse().setResponseCode(401)) // token revoked
    serverA.enqueue(MockResponse().setResponseCode(401))

    runBlocking { poster.send(address(serverA), intent(1), "tok") }
    assertEquals("Bearer tok", serverA.takeRequest().headers["Authorization"])
    runBlocking { poster.send(address(serverA), intent(2), "tok") }
    assertEquals("JWT tok", serverA.takeRequest().headers["Authorization"])

    val refused = runBlocking { poster.send(address(serverA), intent(3), "tok") }
    assertEquals("JWT tok", serverA.takeRequest().headers["Authorization"])
    assertEquals(AuthScheme.JWT, (refused as IntentPoster.Result.Unauthorized).schemeTried)

    runBlocking { poster.send(address(serverA), intent(4), "tok") }
    assertEquals(
      "a refused last scheme must wrap round so every scheme is retried",
      "Bearer tok",
      serverA.takeRequest().headers["Authorization"],
    )
  }

  /**
   * On a Bearer-only server, one stray refusal must not strand the probe on JWT:
   * the JWT refusal that follows brings it back, and the good token is accepted.
   */
  @Test
  fun `a stray refusal on a Bearer-only server recovers to Bearer`() {
    serverA.enqueue(MockResponse().setResponseCode(401)) // stray Bearer refusal
    serverA.enqueue(MockResponse().setResponseCode(401)) // JWT: not this server's scheme
    serverA.enqueue(MockResponse().setResponseCode(200)) // Bearer again: accepted

    runBlocking { poster.send(address(serverA), intent(1), "tok") }
    serverA.takeRequest()
    runBlocking { poster.send(address(serverA), intent(2), "tok") }
    assertEquals("JWT tok", serverA.takeRequest().headers["Authorization"])
    val ok = runBlocking { poster.send(address(serverA), intent(3), "tok") }
    assertEquals("Bearer tok", serverA.takeRequest().headers["Authorization"])
    assertTrue("expected Ok, got $ok", ok is IntentPoster.Result.Ok)
  }
}
