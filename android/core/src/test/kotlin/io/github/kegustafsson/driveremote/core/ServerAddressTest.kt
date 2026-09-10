package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * URL construction is real logic in a standalone app -- the browser UI simply
 * reads `window.location` and has nothing equivalent to get wrong.
 */
class ServerAddressTest {

  private val boat = ServerAddress("192.168.0.100", 3000)

  @Test
  fun `builds the endpoints the station uses`() {
    assertEquals("http://192.168.0.100:3000", boat.httpBaseUrl)
    assertEquals("ws://192.168.0.100:3000/signalk/v1/stream?subscribe=none", boat.streamUrl)
    assertEquals(
      "http://192.168.0.100:3000/plugins/signalk-drive-remote-controller/intent",
      boat.intentUrl,
    )
    assertEquals("http://192.168.0.100:3000/signalk/v1/access/requests", boat.accessRequestsUrl)
  }

  @Test
  fun `uses the secure schemes when TLS is on`() {
    val tls = ServerAddress("boat.example", 3443, useTls = true)
    assertEquals("https://boat.example:3443", tls.httpBaseUrl)
    assertEquals("wss://boat.example:3443/signalk/v1/stream?subscribe=none", tls.streamUrl)
  }

  @Test
  fun `brackets an IPv6 literal so the port stays parseable`() {
    assertEquals("http://[fe80::1]:3000", ServerAddress("fe80::1", 3000).httpBaseUrl)
  }

  @Test
  fun `resolves a server-relative href, an absolute one, and a bare one`() {
    assertEquals(
      "http://192.168.0.100:3000/signalk/v1/access/requests/abc",
      boat.resolve("/signalk/v1/access/requests/abc"),
    )
    assertEquals("http://elsewhere:3000/x", boat.resolve("http://elsewhere:3000/x"))
    assertEquals("http://192.168.0.100:3000/abc", boat.resolve("abc"))
  }

  @Test
  fun `parses what an operator is likely to type`() {
    assertEquals(ServerAddress("192.168.0.100", 3000), ServerAddress.parse("192.168.0.100"))
    assertEquals(ServerAddress("192.168.0.100", 3000), ServerAddress.parse("192.168.0.100:3000"))
    assertEquals(ServerAddress("sensesp.local", 3000), ServerAddress.parse("sensesp.local"))
    assertEquals(ServerAddress("boat", 8080), ServerAddress.parse("boat:8080"))
    assertEquals(ServerAddress("  10.0.0.2 ".trim(), 3000), ServerAddress.parse("  10.0.0.2 "))
  }

  @Test
  fun `refuses a host that cannot form a URL rather than crashing on it later`() {
    // OkHttp's Request.Builder.url() throws on these. Before this gate the
    // exception escaped a coroutine and took the app down -- and again on every
    // relaunch, since the address had already been persisted by then.
    for (bad in listOf("my boat", "boat#1", "user@boat", "fe80::1%wlan0", "a?b", "\"boat\"")) {
      assertNull(ServerAddress.parse(bad), bad)
      assertNull(runCatching { ServerAddress(bad, 3000) }.getOrNull(), bad)
    }
    // Ordinary hosts, and a pasted path, are untouched.
    assertEquals(ServerAddress("boat-2.local", 3000), ServerAddress.parse("boat-2.local"))
    assertEquals(ServerAddress("boat", 3000), ServerAddress.parse("boat/admin"))
  }

  @Test
  fun `parses a URL pasted from a browser`() {
    assertEquals(ServerAddress("192.168.0.100", 3000), ServerAddress.parse("http://192.168.0.100:3000"))
    assertEquals(
      ServerAddress("192.168.0.100", 3000),
      ServerAddress.parse("http://192.168.0.100:3000/signalk-drive-remote-controller/"),
    )
    assertEquals(
      ServerAddress("boat.example", 3443, useTls = true),
      ServerAddress.parse("https://boat.example:3443"),
    )
    // No port given with https: the default port still applies, since the app
    // asks for a Signal K server rather than a web page.
    assertEquals(
      ServerAddress("boat.example", 3000, useTls = true),
      ServerAddress.parse("https://boat.example"),
    )
  }

  @Test
  fun `parses IPv6 forms`() {
    assertEquals(ServerAddress("fe80::1", 3000), ServerAddress.parse("[fe80::1]"))
    assertEquals(ServerAddress("fe80::1", 8080), ServerAddress.parse("[fe80::1]:8080"))
    // Bare IPv6 has several colons and no port -- must not be read as host:port.
    assertEquals(ServerAddress("fe80::1", 3000), ServerAddress.parse("fe80::1"))
  }

  @Test
  fun `returns null for input it cannot make sense of, rather than throwing`() {
    // This backs a text field. A typo is an ordinary event that deserves a
    // validation message, not a crash.
    val bad = listOf("", "   ", "http://", "boat:notaport", "boat:0", "boat:70000", "[", "[]")
    for (input in bad) {
      assertNull(ServerAddress.parse(input), "should not have parsed: '$input'")
    }
  }

  @Test
  fun `rejects an out-of-range port at construction`() {
    for (port in listOf(0, -1, 65536)) {
      val threw = runCatching { ServerAddress("boat", port) }.isFailure
      assert(threw) { "port $port should have been rejected" }
    }
  }

  @Test
  fun `displays as host and port`() {
    assertEquals("192.168.0.100:3000", boat.toString())
  }
}
