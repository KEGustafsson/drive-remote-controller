package io.github.kegustafsson.driveremote.core

/**
 * Which Signal K server this station talks to.
 *
 * The browser UI has no equivalent: it is served BY the server, so it derives
 * everything from `window.location`. A standalone app has to be told, whether
 * by mDNS discovery or by the operator typing it -- so URL construction becomes
 * real logic, and real logic belongs here where it is tested.
 *
 * Plain HTTP is the norm on a boat network (the units talk to
 * `http://192.168.0.100:3000`), so [useTls] defaults to false. It exists
 * because a server behind TLS is a legitimate setup, not because it is
 * expected.
 */
data class ServerAddress(val host: String, val port: Int, val useTls: Boolean = false) {

  init {
    require(host.isNotBlank()) { "host must not be blank" }
    // Only what can actually become a URL. OkHttp's HttpUrl refuses a host
    // containing whitespace or URL delimiters, and Request.Builder.url() then
    // THROWS -- which, reached from a coroutine with no handler, took the whole
    // app down on "my boat" typed into the address field, and again on every
    // relaunch since the address had already been persisted. A '%' zone id on
    // a link-local IPv6 literal (fe80::1%wlan0) is refused for the same reason.
    // Rejected here, in the pure core, so parse() returns null and the Connect
    // button never enables for an address no request can be built for.
    require(host.none { it.isWhitespace() || it in INVALID_HOST_CHARS }) {
      "host contains characters that cannot form a URL: $host"
    }
    require(port in 1..65535) { "port out of range: $port" }
  }

  private val httpScheme = if (useTls) "https" else "http"
  private val wsScheme = if (useTls) "wss" else "ws"

  /** Bracketed for IPv6 so `fe80::1` becomes `[fe80::1]` and the port stays parseable. */
  private val authority: String
    get() = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

  val httpBaseUrl: String
    get() = "$httpScheme://$authority"

  val streamUrl: String
    get() = "$wsScheme://$authority${SkContract.STREAM_PATH}"

  val intentUrl: String
    get() = "$httpBaseUrl${SkContract.INTENT_PATH}"

  val accessRequestsUrl: String
    get() = "$httpBaseUrl${SkContract.ACCESS_REQUESTS_PATH}"

  /**
   * Resolve an href the server handed back (step 1 of the access-request flow).
   * The spec's example is server-relative; absolute is accepted too so a server
   * that answers with a full URL still works.
   */
  fun resolve(href: String): String =
    when {
      href.startsWith("http://") || href.startsWith("https://") -> href
      href.startsWith("/") -> "$httpBaseUrl$href"
      else -> "$httpBaseUrl/$href"
    }

  /**
   * May this address be talked to over plain HTTP?
   *
   * True when TLS is in use (the traffic is not cleartext at all), or when the
   * host is on a private network. Everything else is refused rather than
   * connected to: reaching a public host over cleartext would put the Signal K
   * access token -- which authorises commanding machinery -- on the wire in
   * clear text, and a typo in the address field is all it would take.
   *
   * The check lives in [isPrivateHost] rather than in Android's network
   * security config because that file cannot express IP ranges at all; see the
   * long note there.
   */
  val isCleartextSafe: Boolean
    get() = useTls || isPrivateHost(host)

  /** How to show this server to the operator. */
  override fun toString(): String = authority

  companion object {
    /** signalk-server's default. */
    const val DEFAULT_PORT = 3000

    /** Characters a host may not contain: URL delimiters, quotes, a zone id. */
    private const val INVALID_HOST_CHARS = "#%/?@[]\\\"<>"

    /**
     * Parse operator input: "192.168.0.100", "192.168.0.100:3000",
     * "sensesp.local", or a full "http://host:port" pasted from a browser.
     * Returns null rather than throwing -- this is a text field, and a typo is
     * an ordinary event that should show a validation message, not crash.
     */
    fun parse(input: String, defaultPort: Int = DEFAULT_PORT): ServerAddress? {
      val trimmed = input.trim()
      if (trimmed.isEmpty()) return null

      var rest = trimmed
      var tls = false
      when {
        rest.startsWith("https://", ignoreCase = true) -> {
          tls = true
          rest = rest.removeRange(0, 8)
        }
        rest.startsWith("http://", ignoreCase = true) -> rest = rest.removeRange(0, 7)
      }
      rest = rest.substringBefore('/').trim()
      if (rest.isEmpty()) return null

      // IPv6 literal in brackets, optionally followed by :port.
      if (rest.startsWith("[")) {
        val close = rest.indexOf(']')
        if (close <= 1) return null
        val host = rest.substring(1, close)
        val portPart = rest.substring(close + 1).removePrefix(":")
        val port = if (portPart.isEmpty()) defaultPort else portPart.toIntOrNull() ?: return null
        return runCatching { ServerAddress(host, port, tls) }.getOrNull()
      }

      // A bare IPv6 literal has several colons and no port; anything with a
      // single colon is host:port.
      val colons = rest.count { it == ':' }
      val (host, port) =
        when {
          colons == 0 -> rest to defaultPort
          colons == 1 -> {
            val p = rest.substringAfterLast(':').toIntOrNull() ?: return null
            rest.substringBeforeLast(':') to p
          }
          else -> rest to defaultPort
        }
      if (host.isBlank()) return null
      return runCatching { ServerAddress(host, port, tls) }.getOrNull()
    }
  }
}
