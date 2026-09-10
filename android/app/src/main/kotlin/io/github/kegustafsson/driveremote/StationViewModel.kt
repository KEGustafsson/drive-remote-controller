package io.github.kegustafsson.driveremote

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kegustafsson.driveremote.auth.AccessRequestClient
import io.github.kegustafsson.driveremote.core.AccessState
import io.github.kegustafsson.driveremote.core.ClientIntent
import io.github.kegustafsson.driveremote.core.DrivePosition
import io.github.kegustafsson.driveremote.core.ServerAddress
import io.github.kegustafsson.driveremote.core.SkContract
import io.github.kegustafsson.driveremote.core.StationView
import io.github.kegustafsson.driveremote.core.ThrusterCommand
import io.github.kegustafsson.driveremote.core.ThrusterDirection
import io.github.kegustafsson.driveremote.core.ThrusterMode
import io.github.kegustafsson.driveremote.core.TokenHealth
import io.github.kegustafsson.driveremote.core.deriveStationView
import io.github.kegustafsson.driveremote.net.IntentPoster
import io.github.kegustafsson.driveremote.net.SkStream
import io.github.kegustafsson.driveremote.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** What the app is currently able to do. Drives which screen is shown. */
sealed interface Stage {
  /**
   * Settings have not been read yet, so which screen to show is not yet known.
   *
   * The stage a launch starts in, and it deliberately shows NOTHING. Reading
   * the stored server is a suspending DataStore call, so for the first frames
   * of a cold start there is no answer -- and this used to start at
   * [NeedsServer] instead, which is a guess. On a configured station the guess
   * is wrong every single time: the setup screen appeared, started an mDNS
   * browse and took a multicast lock, and was replaced by the control panel a
   * few frames later. That flash is what the operator saw at every launch.
   *
   * Showing nothing is not a placeholder or a spinner. The window background,
   * the Compose surface and this state are all the same near-black, so the
   * whole start is one continuous dark screen that the controls appear on. A
   * spinner would be a second thing to flash.
   *
   * It is not a state the app can get stuck in silently: see
   * [SettingsReadRevealMs].
   */
  data object Starting : Stage

  /** No server chosen yet -- discovery + manual entry. */
  data object NeedsServer : Stage

  /** Server known, no usable token. Carries progress through the access flow. */
  data class NeedsToken(val access: AccessState) : Stage

  /** Token held; the control panel is live. */
  data object Ready : Stage
}

data class UiState(
  val stage: Stage = Stage.Starting,
  val server: ServerAddress? = null,
  val view: StationView? = null,
  val thrusterMode: ThrusterMode = ThrusterMode.MANUAL,
  val trimDeg: Double = 0.0,
  /** Set when the server rejects our credentials, so the UI can say so. */
  val authError: String? = null,
  /** Set when a chosen address is refused before any connection is attempted. */
  val serverError: String? = null,
  /**
   * Why the app returned to the server screen on its own -- a token that
   * expired or was withdrawn. Null when the operator navigated there.
   */
  val setupNotice: String? = null,
  /** The last server used, offered for one-tap reconnection. */
  val lastServer: ServerAddress? = null,
)

/**
 * How long the app will show nothing while it reads its settings, before giving
 * up and showing the setup screen anyway.
 *
 * Long enough that a normal cold start never reaches it -- the read is a local
 * DataStore file -- and short enough that a blank screen never becomes a
 * mystery. See [Stage.Starting].
 */
private const val SettingsReadRevealMs = 1_000L

/**
 * Holds the station's command state and runs its two loops: the 250 ms intent
 * heartbeat, and the clock that re-evaluates telemetry staleness.
 *
 * The second loop exists because staleness is the ABSENCE of a delta -- nothing
 * will ever arrive to announce that a unit went away, so the check has to be
 * driven by a clock rather than by an event.
 *
 * Every decision this class appears to make is actually delegated to :core
 * ([deriveStationView], [ThrusterCommand], and the intent model). What lives
 * here is sequencing and I/O.
 */
class StationViewModel(application: Application) : AndroidViewModel(application) {

  private val settings = SettingsStore(application)
  private val httpClient =
    OkHttpClient.Builder()
      // Short timeouts: an intent is only relevant for the 250 ms until the
      // next one. A request still in flight after a second is already stale and
      // holding it open just queues obsolete commands behind it.
      .callTimeout(1, TimeUnit.SECONDS)
      .connectTimeout(1, TimeUnit.SECONDS)
      // The stream is long-lived, so it must not be culled for being quiet;
      // OkHttp applies readTimeout per-frame on a WebSocket, and the units
      // publish every 250 ms, so a generous value here is a liveness backstop
      // rather than a limit on normal operation.
      .readTimeout(30, TimeUnit.SECONDS)
      .pingInterval(20, TimeUnit.SECONDS)
      .build()

  private val stream = SkStream(httpClient, viewModelScope)
  private val intentPoster = IntentPoster(httpClient)
  private val accessClient = AccessRequestClient(httpClient)

  private val clientId = settings.clientId()

  /**
   * This launch's session id. Deliberately NOT persisted, and deliberately not
   * derived from [clientId], which is: the access token is issued to that device
   * id, so it has to survive restarts.
   *
   * The arbiter compares generations, so it can tell which of two sessions is
   * newer and close the older one for good. `seq`, `armReq` and
   * `disarmReq` all live in this ViewModel and begin again at 0 on relaunch, so a
   * restart inside the arbiter's 1 s stale timeout meets a record it left behind
   * and its packets carry a LOWER seq -- which the ordering gate cannot tell from
   * a delayed heartbeat, and discards. That swallowed the new session's STOP.
   */
  private val session = settings.nextSessionGeneration()

  private val _uiState = MutableStateFlow(UiState())
  val uiState: StateFlow<UiState> = _uiState.asStateFlow()

  // ---- Command state -----------------------------------------------------
  // Arm/disarm are EDGE requests: a counter per operator tap, acted on by the
  // arbiter's rising edge. A raw "enabled" boolean is never published -- that
  // is the arbiter's to decide. Both start at 0, so merely appearing arms
  // nothing.
  private var armReq = 0L
  private var disarmReq = 0L
  private var seq = 0L

  private var portPosition = DrivePosition.NEUTRAL
  private var stbdPosition = DrivePosition.NEUTRAL

  /**
   * The thruster's direction, mode and trim as ONE value.
   *
   * Held together because a mode change decides all three at once, and keeping
   * them as three independent vars is precisely what let a held direction
   * survive one: the rule now lives in [ThrusterCommand], where a :core test can
   * see it rather than only a phone with two thumbs on it.
   */
  private var thruster = ThrusterCommand.SAFE

  private var heartbeatJob: Job? = null
  private var tickerJob: Job? = null
  /**
   * The access-request flow in progress, if any. Held so it can be cancelled:
   * it polls for up to five minutes, and an approval landing after the
   * operator has moved to another server must not connect them to the one
   * they left. See [requestAccess] and [onApproved].
   */
  private var accessJob: Job? = null
  /** One ordered lane: an old press can never arrive after its release. */
  private val intentMutex = Mutex()
  private var foreground = true

  /**
   * Consecutive credential rejections, so the auth-scheme probe is not mistaken
   * for a withdrawn token. The rule lives in [TokenHealth]; this is only where
   * it is kept.
   */
  private var tokenHealth = TokenHealth()

  init {
    viewModelScope.launch {
      // Show nothing while the answer is unknown, but not indefinitely. If
      // reading settings is slow enough that the operator would be looking at a
      // blank screen wondering, fall through to the setup screen -- a wrong
      // guess is better than a dark rectangle with nothing to tap. The read is
      // deliberately NOT cancelled: it still lands, and still sends a
      // configured station to its controls.
      val reveal = launch {
        delay(SettingsReadRevealMs)
        if (_uiState.value.stage == Stage.Starting) {
          _uiState.value = _uiState.value.copy(stage = Stage.NeedsServer)
        }
      }

      // Caught, because a failed read must still leave a screen. The reveal
      // job above is a child of this coroutine, so a thrown DataStore error
      // would cancel it on the way past and strand the app on the blank stage
      // -- the one outcome Stage.Starting must never produce.
      //
      // Not `runCatching`, which would swallow the CancellationException that
      // means this ViewModel is being torn down and turn a normal shutdown into
      // a "settings could not be read" notice.
      val stored: Result<ServerAddress?> =
        try {
          Result.success(settings.serverAddress.first())
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (failure: Exception) {
          Result.failure(failure)
        }
      reveal.cancel()
      val address = stored.getOrNull()

      when {
        stored.isFailure ->
          _uiState.value =
            UiState(
              stage = Stage.NeedsServer,
              setupNotice =
                "This device's saved settings could not be read, so the server has to be " +
                  "chosen again.",
            )

        address == null -> _uiState.value = UiState(stage = Stage.NeedsServer)

        // A token whose stated expiry has passed is known-bad before a single
        // request is sent. Drop it here rather than letting the operator reach
        // the control screen and discover it with a 401 on the first press.
        settings.token(address) != null && settings.tokenExpired(System.currentTimeMillis()) -> {
          settings.setToken(null, null)
          _uiState.value =
            UiState(
              stage = Stage.NeedsServer,
              lastServer = address,
              setupNotice =
                "The access token for $address has expired. Choose the server again to " +
                  "request a new one.",
            )
        }

        // The ordinary case: a stored server and a usable token go straight to
        // the controls, which is what a station on a boat should do.
        else -> useServer(address)
      }
    }
    startTicker()

    // Refresh as soon as a delta lands, so a state change (a unit coming back,
    // another station taking the token) shows immediately rather than up to one
    // tick late. The ticker still runs: it is what catches a unit that STOPS
    // publishing, which by definition produces no event to collect.
    viewModelScope.launch { stream.revision.collect { refreshView() } }

    // ...and the same for the link itself, which changes without a delta. This
    // is what makes CONNECTING give way to DISARMED at the instant the socket
    // opens rather than at the next tick, and it matters more in the other
    // direction: a stream that drops mid-manoeuvre now says so immediately
    // instead of up to 250 ms later.
    viewModelScope.launch { stream.connectionState.collect { refreshView() } }
  }

  // ---- Setup -------------------------------------------------------------

  fun useServer(address: ServerAddress) {
    // The cleartext gate, applied to BOTH ways an address arrives -- typed by
    // the operator and tapped from discovery. Refusing here rather than in the
    // text field is what makes it a gate: a discovered service advertising a
    // public hostname gets the same answer as a typo. Nothing is stored and no
    // connection is opened, so the token cannot leave over cleartext.
    if (!address.isCleartextSafe) {
      _uiState.value =
        _uiState.value.copy(
          stage = Stage.NeedsServer,
          serverError =
            "$address is not on a private network, so this app will not talk to it in the " +
              "clear -- the access token would be exposed. Use https:// to reach a server " +
              "outside the boat network.",
        )
      return
    }

    // A pending access request belongs to the server being left. Its approval
    // must not arrive later and connect this station to that server behind
    // the operator's back.
    cancelAccessRequest()

    viewModelScope.launch {
      settings.setServerAddress(address)
      // Cut off replies still in flight to the previous server BEFORE the new
      // session starts. Waiting for the next send() would leave the whole
      // access-request wait -- during which nothing is sent -- with the old
      // server still counting as the current target.
      intentPoster.abandonInFlight()
      tokenHealth = TokenHealth()

      // token(address) withholds a token issued by a DIFFERENT server, so
      // switching servers lands on the access-request screen rather than
      // carrying the previous server's credentials into a guaranteed 401.
      val token =
        settings.token(address)?.takeUnless { settings.tokenExpired(System.currentTimeMillis()) }

      _uiState.value =
        _uiState.value.copy(
          server = address,
          stage = if (token != null) Stage.Ready else Stage.NeedsToken(AccessState.None),
          serverError = null,
          setupNotice = null,
          authError = null,
          lastServer = address,
        )
      if (token != null) {
        stream.connect(address, token)
        startHeartbeat()
        // The stage above has just become Ready, so the control screen is about
        // to be drawn from whatever `view` was last computed -- which is from
        // before this connect. Recompute now rather than letting the first
        // frame be up to one ticker period stale.
        refreshView()
      }
    }
  }

  /**
   * Ask the server for access, then wait for an admin to approve it.
   *
   * Read/write permission is enough; admin should not be granted. See
   * docs/ARCHITECTURE.md §10 for why that is only true on a server new enough
   * for the plugin's `readwrite` route registration.
   */
  fun requestAccess() {
    val address = _uiState.value.server ?: return
    // One flow at a time: a second tap before the first answer would otherwise
    // run two polls and apply two approvals.
    cancelAccessRequest()
    accessJob =
      viewModelScope.launch {
        val description = "Drive Remote Control (${android.os.Build.MODEL})"
        when (val initial = accessClient.request(address, clientId, description)) {
          is AccessState.Approved -> onApproved(address, initial)
          is AccessState.Pending -> {
            _uiState.value = _uiState.value.copy(stage = Stage.NeedsToken(initial))
            when (val decided = accessClient.awaitDecision(address, initial.href)) {
              is AccessState.Approved -> onApproved(address, decided)
              else -> _uiState.value = _uiState.value.copy(stage = Stage.NeedsToken(decided))
            }
          }
          else -> _uiState.value = _uiState.value.copy(stage = Stage.NeedsToken(initial))
        }
      }
  }

  private fun cancelAccessRequest() {
    accessJob?.cancel()
    accessJob = null
  }

  private fun onApproved(address: ServerAddress, state: AccessState.Approved) {
    // Only for the server the operator is still waiting on. An approval that
    // lands after they have moved on -- to another server, or back to the
    // server screen -- would otherwise open a stream to the old server while
    // every intent went to the new one: a control screen drawn from one boat's
    // telemetry and commanding another. Cancellation above normally prevents
    // this reaching here at all; this is the check that makes it impossible.
    val current = _uiState.value
    if (current.server != address || current.stage !is Stage.NeedsToken) return
    settings.setToken(state.token, state.expiresAtMs, address)
    tokenHealth = TokenHealth()
    _uiState.value = current.copy(stage = Stage.Ready, authError = null)
    stream.connect(address, state.token)
    startHeartbeat()
  }

  /**
   * Operator-initiated: stop talking to this server and pick another.
   *
   * Refused while armed. Disconnecting would leave this station holding the arm
   * token until the arbiter stale-evicts it, with the operator already on a
   * setup screen that shows no controls -- armed, commanding, and unable to
   * see or stop it. Disarming first is one tap and makes the state
   * unambiguous.
   *
   * "Armed" here means armed IN A LIVE SESSION. Outside [Stage.Ready] the
   * stream is closed, but the value store it was built from is deliberately
   * never cleared, so `activeClient` can still name this station after a
   * token revocation ended the session -- and the ticker keeps re-deriving
   * `view.armed == true` from it. There is no session holding anything, and
   * refusing there locked the operator on the access screen (which does not
   * even show this refusal) with no way to pick another server.
   */
  fun changeServer() {
    if (_uiState.value.stage == Stage.Ready && _uiState.value.view?.armed == true) {
      _uiState.value =
        _uiState.value.copy(
          authError = "Disarm before changing server — this station is armed."
        )
      return
    }
    teardown()
    _uiState.value =
      _uiState.value.copy(
        stage = Stage.NeedsServer,
        view = null,
        authError = null,
        setupNotice = null,
        lastServer = _uiState.value.server,
      )
  }

  /**
   * The server has stopped accepting our token -- withdrawn in the admin UI, or
   * expired without having said when.
   *
   * Everything stops, the token is discarded, and the operator is returned to
   * the server screen to obtain a new one. Continuing to display a control
   * panel would be the failure mode SAFETY.md names directly: presenting a
   * station as live and commanding when the server has stopped listening to it.
   */
  private fun onTokenRejected(code: Int) =
    endSession(
      "${_uiState.value.server} rejected this device's access token (HTTP $code). It was most " +
        "likely revoked or expired. Choose the server again to request a new one."
    )

  /**
   * The stored token has passed the expiry the server stated when it issued it.
   *
   * Pre-empted rather than waited for: the server will start refusing at that
   * moment anyway, and there is no reason to let the operator press buttons at
   * a station whose authority is already known to have lapsed. Only fires when
   * the server actually stated an expiry -- [SettingsStore.tokenExpired] never
   * guesses one.
   */
  private fun onTokenExpired() =
    endSession(
      "This device's access token for ${_uiState.value.server} has expired. Choose the server " +
        "again to request a new one."
    )

  private fun endSession(notice: String) {
    val server = _uiState.value.server
    teardown()
    settings.setToken(null, null)
    // Deliberately NOT abandonInFlight() here. teardown() has just launched the
    // final all-safe intent, and resetting the probe under it would send that
    // release with the scheme index back at 0 -- on a server that accepts only
    // JWT it would 401, and the release is never retried. useServer() does the
    // invalidation when the NEXT session begins, which is the point that
    // actually needs protecting.
    tokenHealth = TokenHealth()
    _uiState.value =
      _uiState.value.copy(
        stage = Stage.NeedsServer,
        view = null,
        authError = null,
        lastServer = server,
        setupNotice = notice,
      )
  }

  /**
   * Stop commanding, then stop talking.
   *
   * The order matters: a final all-neutral intent goes out while the token is
   * still held and the heartbeat still running, so the arbiter sees this
   * station release its controls rather than merely fall silent. Falling silent
   * also works -- staleness eviction is the backstop -- but it takes a timeout,
   * and there is no reason to spend it.
   */
  private fun teardown() {
    stopHeartbeat()
    cancelAccessRequest()
    setControlsSafe()
    // Capture credentials before endSession clears them. The coroutine may not
    // acquire the send lane until after the settings store has been updated.
    val address = _uiState.value.server
    val token = settings.token(address)
    // Close the read side NOW rather than behind the send lane. A send already
    // in flight is a blocking OkHttp call that cancellation cannot interrupt,
    // so queueing the close behind it would hold the socket open for as long
    // as that call's timeout. The read stream has no bearing on the release.
    stream.close()
    // The final safe intent skips the send lane for the same reason STOP does
    // (see sendIntentNow): it must not wait out a stalled press. It claims its
    // sequence number first, so any press still in flight carries a lower one
    // and the arbiter discards it on arrival rather than applying it after
    // this release.
    val finalSeq = nextSeq()
    // NonCancellable for the same reason releaseAllControls asks for it: this is
    // the send that tells the arbiter this station has LET GO, and it runs while
    // the session around it is being dismantled. Cancelling it costs a stale
    // eviction timeout during which the arbiter still believes we hold the arm.
    viewModelScope.launch {
      withContext(NonCancellable) {
        if (address != null) {
          // ClientIntent.safe() rather than a hand-built tuple. This used to pass
          // the CURRENT thruster mode and trim, which in HOLD is not a safe
          // intent at all: HH follows the trimmed setpoint, not the direction, so
          // it would have kept working the thruster on the way out. :core already
          // had the right answer, tested, and unused; a hand-rolled copy of it
          // here could only ever drift.
          intentPoster.send(
            address,
            ClientIntent.safe(
              clientId = clientId,
              session = session,
              seq = finalSeq,
              armReq = armReq,
              disarmReq = disarmReq,
            ),
            token,
          )
        }
      }
    }
  }

  // ---- Operator actions --------------------------------------------------

  fun requestArm() {
    armReq += 1
    sendIntentNow()
  }

  fun requestDisarm() {
    disarmReq += 1
    // urgent: STOP must never queue behind a press whose POST is stalled.
    //
    // allowWithoutHeartbeat for the same reason it is never gated on anything
    // else. The heartbeat is stopped whenever this station is not in the
    // foreground, and "paused but visible and touchable" is a real state -- a
    // legacy split-screen pane, a partially-obscuring dialog -- in which the
    // operator can see this button, press it, and otherwise have the STOP
    // silently discarded before it reached the wire. The POST does not need the
    // heartbeat to travel; it never did.
    sendIntentNow(allowWithoutHeartbeat = true, urgent = true)
  }

  // A press may queue; a RELEASE may not. If a press POST has stalled, the
  // machine is still moving until the release lands, so making the release wait
  // out a call timeout is the same fault as gating STOP -- just smaller and far
  // more frequent, since every button-lift takes this path. Ordering survives
  // for the same reason it does for STOP: the sequence number is claimed before
  // any I/O, so an overtaken press carries the lower one and the arbiter's
  // `seq <= lastSeq` gate discards it on arrival.
  //
  // Presses stay in the lane. They are the case the lane was added for.
  fun setPort(position: DrivePosition) {
    portPosition = position
    sendIntentNow(urgent = position == DrivePosition.NEUTRAL)
  }

  fun setStbd(position: DrivePosition) {
    stbdPosition = position
    sendIntentNow(urgent = position == DrivePosition.NEUTRAL)
  }

  fun setThrusterDirection(direction: ThrusterDirection) {
    thruster = thruster.withDirection(direction)
    sendIntentNow(urgent = direction == ThrusterDirection.OFF)
  }

  /**
   * Choose which gate the thruster runs under. Callable while disarmed on
   * purpose -- the chooser is not gated on `thrusterCommandable` (Controls.kt)
   * because it decides which gate the NEXT arm opens, and that is a choice made
   * before arming. It reaches no machine on its own: the arbiter publishes a
   * non-holder's mode nowhere, and masks a holder's to `manual` (direction
   * off -- the inert tuple) whenever HH is not commandable.
   */
  fun setThrusterMode(mode: ThrusterMode) {
    // Releasing the held direction is [ThrusterCommand.withMode]'s job, not this
    // one's: selecting a mode takes the PORT/STBD contacts off the screen, so
    // their release fires into an effect that is being disposed in the same pass
    // and is never reported. A direction left standing here would keep
    // publishing on every heartbeat with no finger on the glass, and would be
    // replayed as a live thrust the moment MANUAL came back -- which HH acts on
    // at once, MANUAL having no firmware dwell by design.
    thruster = thruster.withMode(mode)
    _uiState.value =
      _uiState.value.copy(thrusterMode = thruster.mode, trimDeg = thruster.trimDeg)
    // Urgent when the change released something: that send IS the release, and a
    // release must not wait out a stalled press in the lane.
    sendIntentNow(urgent = thruster.direction == ThrusterDirection.OFF)
  }

  fun trim(stepDeg: Double) {
    thruster = thruster.trimmedBy(stepDeg)
    _uiState.value = _uiState.value.copy(trimDeg = thruster.trimDeg)
    sendIntentNow()
  }

  /**
   * Force every control back to its safe value.
   *
   * Called when the app leaves the foreground, and whenever authority is
   * withdrawn mid-press. This is the Android counterpart of the web UI
   * releasing on `visibilitychange`/`pagehide`: an interrupted touch must never
   * leave a drive commanded.
   *
   * Note it does NOT disarm. Backgrounding the app is not the operator saying
   * "stop" -- it is the operator stopping commanding. The arbiter will
   * stale-evict us shortly after the heartbeat stops, which releases the token
   * anyway; commanding neutral immediately is the part that must not wait.
   */
  fun releaseAllControls() {
    setControlsSafe()
    sendIntentNow(allowWithoutHeartbeat = true, urgent = true, nonCancellable = true)
  }

  /**
   * The genuinely inactive tuple -- not just "no direction pressed".
   *
   * Why OFF alone is not enough, and why the mode and trim have to go too, is
   * [ThrusterCommand.SAFE]'s KDoc: in HOLD, HH follows the trimmed setpoint and
   * never looks at the direction.
   *
   * The UI is updated to match: after this the station really is in MANUAL, and
   * on resuming, re-entering HOLD has to be a deliberate act. Showing HOLD while
   * having sent MANUAL would be the display lying about what the boat was told.
   */
  private fun setControlsSafe() {
    portPosition = DrivePosition.NEUTRAL
    stbdPosition = DrivePosition.NEUTRAL
    thruster = ThrusterCommand.SAFE
    _uiState.value =
      _uiState.value.copy(thrusterMode = thruster.mode, trimDeg = thruster.trimDeg)
  }

  /**
   * Lifecycle boundary: release once, then go silent so authority expires.
   *
   * The observer is mounted for every stage, not just [Stage.Ready], so this
   * is gated on actually having a control session. Backgrounding from the
   * setup screens has nothing to release, and must not post an intent to a
   * server the operator has just left.
   */
  fun onBackgrounded() {
    if (!foreground) return
    foreground = false
    stopHeartbeat()
    if (_uiState.value.stage == Stage.Ready) releaseAllControls()
  }

  /** Resume periodic intent only for a still-valid control session. */
  fun onForegrounded() {
    if (foreground) return
    foreground = true
    if (_uiState.value.stage == Stage.Ready) startHeartbeat()
  }

  // ---- Loops -------------------------------------------------------------

  private fun startHeartbeat() {
    if (heartbeatJob != null || !foreground) return
    heartbeatJob =
      viewModelScope.launch {
        while (isActive) {
          // Checked before each send rather than only at startup: a session can
          // outlast the expiry the server stated, and the moment it does this
          // station's authority is gone whether or not a request has been
          // refused yet.
          if (settings.tokenExpired(System.currentTimeMillis())) {
            onTokenExpired()
            return@launch
          }
          val mySeq = nextSeq()
          intentMutex.withLock { sendIntent(mySeq) }
          delay(SkContract.PERIODIC_REFRESH_MS)
        }
      }
  }

  private fun stopHeartbeat() {
    heartbeatJob?.cancel()
    heartbeatJob = null
  }

  /**
   * Send immediately on a change -- no polling delay (SAFETY.md invariant 2).
   *
   * [urgent] sends skip [intentMutex] entirely. The lane exists so a press can
   * never arrive after its own release, but making STOP wait for it means a
   * single stalled POST delays a disarm by a whole `callTimeout`, and disarm is
   * the one thing that is never gated. Ordering is preserved without the lock
   * because the sequence number is claimed HERE, before any I/O: an overtaken
   * press carries the lower seq, and the arbiter discards `seq <= lastSeq`. So
   * the guarantee moves from the client's lock to the server's ordering gate,
   * which is the authority on it anyway.
   */
  private fun sendIntentNow(
    allowWithoutHeartbeat: Boolean = false,
    urgent: Boolean = false,
    nonCancellable: Boolean = false,
  ) {
    if (heartbeatJob == null && !allowWithoutHeartbeat) return
    val mySeq = nextSeq()
    viewModelScope.launch {
      if (nonCancellable) withContext(NonCancellable) { deliver(mySeq, urgent) }
      else deliver(mySeq, urgent)
    }
  }

  /**
   * [nonCancellable] is for the sends that put the machines back to a safe value.
   *
   * Those run at exactly the moment this scope is likely to be cancelled -- the
   * app going to the background, the activity finishing -- and a cancelled
   * release is a release that never reached the boat. Stale eviction and RX's
   * own 1 s watchdog are the backstop, so this is not the only thing standing
   * between a lift and NEUTRAL, but spending a timeout when the POST was already
   * half out the door is a poor trade. It does not rescue a send launched into
   * an ALREADY-cancelled scope; nothing here can.
   */
  private suspend fun deliver(seqForThisSend: Long, urgent: Boolean) {
    if (urgent) sendIntent(seqForThisSend) else intentMutex.withLock { sendIntent(seqForThisSend) }
  }

  /**
   * Claim the next sequence number. Called only from the main dispatcher
   * (viewModelScope defaults to Dispatchers.Main.immediate and every caller is
   * a UI callback or the heartbeat loop), so a plain increment is sufficient --
   * but it must happen BEFORE the send lane is entered, or a send that waited
   * on the lock would carry a number newer than one already on the wire.
   */
  private fun nextSeq(): Long {
    seq += 1
    return seq
  }

  private suspend fun sendIntent(seqForThisSend: Long) {
    // Only a live session sends. A press that was queued in the send lane
    // behind a stalled POST can reach here after endSession() has torn the
    // session down -- and `server` is deliberately kept for the reconnect
    // offer, so it would still have a target. Sending then either 401s with no
    // token and ends the session a second time, or -- if the operator has
    // already chosen another server -- carries the OLD server's token to the
    // new one and gets that token deleted on the 401. teardown()'s final safe
    // intent does not pass through here, so it is unaffected.
    if (_uiState.value.stage != Stage.Ready) return
    val address = _uiState.value.server ?: return
    // Server-bound: a token issued by a different server is withheld rather
    // than sent, for the same reason useServer() binds it.
    val token = settings.token(address)
    val intent =
      ClientIntent(
        clientId = clientId,
        session = session,
        seq = seqForThisSend,
        armReq = armReq,
        disarmReq = disarmReq,
        port = portPosition,
        stbd = stbdPosition,
        thruster = thruster.direction,
        thrusterMode = thruster.mode,
        trimDeg = thruster.trimDeg,
      )

    val result = intentPoster.send(address, intent, token)
    // Validated HERE, where the verdict is consumed, and on the main dispatcher
    // that also runs every session change -- so nothing can retarget between
    // this check and the state it guards. Classifying inside the poster's lock
    // still left the hop back from Dispatchers.IO, and a server change landing
    // in that hop would apply an old server's verdict to the new session.
    if (result is IntentPoster.Result.Verdict &&
      result.generation != intentPoster.currentGeneration()
    ) {
      return
    }
    when (result) {
      is IntentPoster.Result.Ok -> {
        tokenHealth = tokenHealth.accepted()
        if (_uiState.value.authError != null) _uiState.value = _uiState.value.copy(authError = null)
      }

      is IntentPoster.Result.Unauthorized -> {
        // A rejection only counts against the scheme it actually used, so
        // several sends refused on the same unprobed scheme cannot spend the
        // whole budget on one failed probe. A refusal with no scheme at all
        // means we sent no credentials, which no amount of probing fixes.
        result.schemeTried?.let { tokenHealth = tokenHealth.rejected(it) }
        if (result.schemeTried == null || tokenHealth.isDead) {
          // Past the auth-scheme probe and still refused: the token is gone,
          // not mis-formatted.
          onTokenRejected(result.code)
        } else {
          // Still possibly the scheme probe. Say what is happening without
          // tearing the station down over what may be a working handshake.
          _uiState.value =
            _uiState.value.copy(
              authError =
                "Signal K refused this device (HTTP ${result.code}). Retrying with the other " +
                  "authorisation scheme…"
            )
        }
      }

      // A transport failure is NOT an auth failure and must not count towards
      // one: losing the network is not losing authority. Intents repeat every
      // 250 ms, the stream reconnects on its own, and the station stays on the
      // control screen throughout -- which is the whole point, since its disarm
      // must keep working while it cannot see the boat. If the outage outlasts
      // the arbiter's staleness timeout it releases the token itself, and the
      // system falls back to disarmed with no special-casing here.
      is IntentPoster.Result.Failed -> Unit
    }
  }

  /**
   * Recompute the view on a clock. Staleness is the absence of an event, so no
   * emission can ever announce it -- only elapsed time reveals a unit that
   * stopped publishing.
   */
  private fun startTicker() {
    tickerJob =
      viewModelScope.launch {
        while (isActive) {
          refreshView()
          delay(SkContract.TELEMETRY_POLL_MS)
        }
      }
  }

  private fun refreshView() {
    val view =
      deriveStationView(
        store = stream.store,
        connectionState = stream.connectionState.value,
        myClientId = clientId,
        nowMs = SystemClock.elapsedRealtime(),
        // So a launch that has not opened its socket yet reads as starting up
        // rather than as a boat that has gone away. See LinkPhase.
        everConnected = stream.everConnected,
        linkAttemptStartedMs = stream.targetSetAtMs,
      )

    // Arm-first, then trim: force the trim back to 0 whenever the thruster is
    // not commandable, so arming always begins at "hold the captured heading"
    // and never swings the boat to an offset dialled in earlier.
    if (!view.thrusterCommandable) {
      thruster = thruster.untrimmed()
    }

    _uiState.value = _uiState.value.copy(view = view, trimDeg = thruster.trimDeg)
  }

  override fun onCleared() {
    super.onCleared()
    stopHeartbeat()
    tickerJob?.cancel()
    stream.close()
  }
}
