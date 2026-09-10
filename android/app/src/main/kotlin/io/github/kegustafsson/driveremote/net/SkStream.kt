package io.github.kegustafsson.driveremote.net

import android.os.SystemClock
import io.github.kegustafsson.driveremote.core.AuthScheme
import io.github.kegustafsson.driveremote.core.ConnectionState
import io.github.kegustafsson.driveremote.core.ReconnectBackoff
import io.github.kegustafsson.driveremote.core.ServerAddress
import io.github.kegustafsson.driveremote.core.SkContract
import io.github.kegustafsson.driveremote.core.SkDelta
import io.github.kegustafsson.driveremote.core.SkValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The read side: a Signal K delta stream over a WebSocket.
 *
 * **Read-only by construction**, exactly as the browser client is: the only
 * frame this ever sends is the initial `subscribe`. There is no publish method
 * here and [SkDelta] has no delta-building function, so bypassing the arming
 * authority is not something a future change could do by accident -- it would
 * have to add the capability first.
 *
 * The one thing a native client can do that a browser cannot is set an
 * `Authorization` header on the WebSocket upgrade. That is the whole reason
 * this app can be pointed at any server while the web UI is confined to
 * same-origin cookies.
 *
 * ## Threading
 *
 * [SkValueStore] is not thread-safe, and OkHttp delivers callbacks on its own
 * threads. Every mutation is therefore marshalled onto [scope] -- which the
 * caller supplies as a main-confined scope -- so the store has exactly one
 * writer. Do not "optimise" this by touching the store from a listener
 * callback.
 */
class SkStream(private val httpClient: OkHttpClient, private val scope: CoroutineScope) {

  private val _connectionState = MutableStateFlow(ConnectionState.CLOSED)
  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  /** Owned by [scope]. Read it from there, or from a [revision] collector. */
  val store = SkValueStore()

  /**
   * Bumped whenever a delta arrives, changed value or not.
   *
   * Deliberately not a snapshot flow of the values: in steady state the units
   * republish identical telemetry four times a second, so a value-diffing flow
   * would emit nothing at all -- and "nothing changed" is exactly the state in
   * which the UI still needs to know telemetry is arriving. Collectors combine
   * this with their own ticker, because staleness is the absence of an event
   * and no emission can ever announce it.
   */
  private val _revision = MutableStateFlow(0L)
  val revision: StateFlow<Long> = _revision.asStateFlow()

  private var webSocket: WebSocket? = null
  private var reconnectJob: Job? = null
  private val backoff = ReconnectBackoff()

  private var target: ServerAddress? = null
  private var token: String? = null
  /** Which scheme the server accepted. Reset per target; see [AuthScheme]. */
  private var schemeIndex = 0
  private var closedByCaller = false
  /** Invalidates callbacks and reconnects belonging to every older socket. */
  private var generation = 0L

  /**
   * Whether this stream has been open at least once since the current target
   * was set, and when that target was set -- the two facts
   * [io.github.kegustafsson.driveremote.core.linkPhaseOf] needs to tell a
   * start-up apart from a dropped connection.
   *
   * Reset together, and only where the target actually changes: a reconnect
   * after a drop must NOT look like a fresh start, because a drop is the case
   * the OFFLINE warning exists for.
   *
   * Both are read from [io.github.kegustafsson.driveremote.StationViewModel] on
   * the same dispatcher this class mutates them on, so neither needs to be a
   * flow or volatile.
   */
  var everConnected: Boolean = false
    private set

  /**
   * elapsedRealtime when this station started trying to reach a server: set
   * here at construction, and again whenever [connect] re-points the stream.
   *
   * **Construction, not first connect, and that is the whole point.** There is
   * a real gap between the two -- the stored server has to be read out of
   * DataStore first -- and the view is refreshed on a ticker that starts
   * immediately, so it is computed several times before [connect] is ever
   * called. Left at 0 this reads as "started trying at device boot", which is
   * hours ago, which is past any grace window: the control screen's first frame
   * came up as the amber OFFLINE panel and corrected itself a tick later. That
   * was the flash, and it survived the first attempt at fixing it.
   *
   * So the clock runs from when the app started caring, not from when the
   * socket was first told to open. That is also the more honest reading of
   * "how long has this station been without a link".
   */
  var targetSetAtMs: Long = SystemClock.elapsedRealtime()
    private set

  /** (Re)point the stream at a server. Safe to call repeatedly. */
  fun connect(address: ServerAddress, token: String?) {
    closedByCaller = false
    if (target == address && this.token == token && webSocket != null) return
    reconnectJob?.cancel()
    reconnectJob = null
    target = address
    this.token = token
    schemeIndex = 0
    backoff.reset()
    everConnected = false
    targetSetAtMs = SystemClock.elapsedRealtime()
    openSocket()
  }

  fun close() {
    closedByCaller = true
    generation += 1
    reconnectJob?.cancel()
    reconnectJob = null
    webSocket?.close(NORMAL_CLOSURE, null)
    webSocket = null
    _connectionState.value = ConnectionState.CLOSED
  }

  private fun openSocket() {
    val address = target ?: return
    webSocket?.cancel()
    _connectionState.value = ConnectionState.CONNECTING

    val builder = Request.Builder().url(address.streamUrl)
    token?.let { builder.header("Authorization", currentScheme().headerValue(it)) }

    val myGeneration = ++generation
    webSocket = httpClient.newWebSocket(builder.build(), Listener(myGeneration))
  }

  private fun currentScheme(): AuthScheme =
    AuthScheme.TRY_ORDER[schemeIndex.coerceIn(0, AuthScheme.TRY_ORDER.lastIndex)]

  private fun scheduleReconnect(expectedGeneration: Long) {
    if (closedByCaller || reconnectJob != null) return
    val wait = backoff.nextDelay()
    reconnectJob =
      scope.launch {
        delay(wait)
        reconnectJob = null
        if (!closedByCaller && generation == expectedGeneration) openSocket()
      }
  }

  private inner class Listener(private val myGeneration: Long) : WebSocketListener() {

    override fun onOpen(webSocket: WebSocket, response: Response) {
      scope.launch {
        if (myGeneration != generation) return@launch
        backoff.reset()
        everConnected = true
        _connectionState.value = ConnectionState.OPEN
        // The only frame this app ever sends.
        webSocket.send(SkDelta.buildSubscribeMessage())
      }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      // Parse off the callback thread's data but mutate on `scope`, which is
      // the store's single writer.
      val entries = SkDelta.parseUpdates(text, ACCEPT)
      if (entries.isEmpty()) return
      scope.launch {
        if (myGeneration != generation) return@launch
        store.apply(entries, SystemClock.elapsedRealtime())
        // Bump unconditionally: arrival is the signal, not the value.
        _revision.value = _revision.value + 1
      }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      scope.launch {
        if (myGeneration != generation) return@launch
        // 401 with a token means the scheme may be the problem, not the token:
        // some signalk-server versions accept only "JWT <token>" rather than
        // "Bearer <token>" (issue #715). Advance once and retry immediately
        // rather than backing off on what is really a handshake mismatch.
        if (response?.code == 401 && token != null && schemeIndex < AuthScheme.TRY_ORDER.lastIndex) {
          schemeIndex += 1
          _connectionState.value = ConnectionState.CONNECTING
          openSocket()
          return@launch
        }
        this@SkStream.webSocket = null
        _connectionState.value = ConnectionState.CLOSED
        scheduleReconnect(myGeneration)
      }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      scope.launch {
        if (myGeneration != generation) return@launch
        this@SkStream.webSocket = null
        _connectionState.value = ConnectionState.CLOSED
        scheduleReconnect(myGeneration)
      }
    }
  }

  private companion object {
    const val NORMAL_CLOSURE = 1000
    val ACCEPT = SkContract.SUBSCRIBE_PATHS.toSet()
  }
}
