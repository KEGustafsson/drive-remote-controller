package io.github.kegustafsson.driveremote.core

/**
 * How the link should be PRESENTED, which is not the same as what it is.
 *
 * [ConnectionState] is the fact: the socket is open, opening, or shut. This is
 * the reading given to the operator, and the two differ in exactly one place --
 * a socket that is not open yet because the app has only just started is not
 * the same event as a socket that went down, and must not look like one.
 *
 * The distinction is worth a type because getting it wrong is what the operator
 * saw: every launch opened on a yellow OFFLINE kill switch for the few hundred
 * milliseconds the WebSocket took to come up, then flipped to DISARMED. A
 * warning that appears at every single start is not a warning, it is noise the
 * operator learns to look past -- and this one is attached to the STOP button.
 *
 * **Presenting [CONNECTING] calmly is safe, and provably so rather than by
 * assertion.** Arming requires `canArm`, which requires unit liveness, which
 * requires an OPEN socket -- so this station cannot be armed or commanding
 * before its first open. The one other way a session begins connecting afresh
 * is `changeServer`, which refuses outright while armed. There is no state in
 * which [CONNECTING] hides a live command.
 *
 * Once a session HAS been open, every later gap is [OFFLINE] with no grace at
 * all. That is the dangerous case the OFFLINE state was written for: the
 * station may still hold the token and still be commanding while unable to see
 * the boat, and it must say so the instant it happens.
 */
enum class LinkPhase {
  /** The stream is open. */
  ONLINE,

  /**
   * Never yet open this session, and still within
   * [SkContract.LINK_STARTUP_GRACE_MS]. Starting up, not broken.
   */
  CONNECTING,

  /** Was open and is not now, or has been trying long enough to be a fault. */
  OFFLINE,
}

/**
 * [LinkPhase] from the facts. Pure, and the whole rule in one place.
 *
 * [connectingForMs] is measured from when the current target was set, not from
 * the latest socket attempt: a first connection that fails and is retried three
 * times is still one start-up, and restarting the clock on each attempt would
 * keep it in [LinkPhase.CONNECTING] for as long as the backoff kept trying --
 * which is forever, and is the failure this grace window must not become.
 */
fun linkPhaseOf(
  connectionState: ConnectionState,
  everConnected: Boolean,
  connectingForMs: Long,
): LinkPhase =
  when {
    connectionState == ConnectionState.OPEN -> LinkPhase.ONLINE
    everConnected -> LinkPhase.OFFLINE
    connectingForMs < SkContract.LINK_STARTUP_GRACE_MS -> LinkPhase.CONNECTING
    else -> LinkPhase.OFFLINE
  }

/**
 * Everything the UI needs to draw itself, derived in one pure place.
 *
 * The Compose layer renders this and nothing else -- it performs no liveness
 * arithmetic, no precedence comparison and no arm-state guessing of its own.
 * That is what keeps the interesting decisions in a module testable on a
 * laptop, and it is the same reason `App.tsx` reads from `pure/` rather than
 * inlining the rules.
 *
 * Every field that depends on freshness is a function of [nowMs], because
 * staleness is the ABSENCE of a delta: nothing will ever arrive to announce it,
 * so it has to be recomputed on a clock (see [SkContract.TELEMETRY_POLL_MS]).
 */
data class StationView(
  val connectionState: ConnectionState,

  /**
   * The same link, as the operator should be told about it. Differs from
   * [connectionState] only while a session is opening for the first time; see
   * [LinkPhase].
   */
  val linkPhase: LinkPhase,
  val controlState: ControlState,

  /** Per-unit presence, judged on arrival. Never on a published value. */
  val rxLiveness: UnitLiveness,
  val hhLiveness: UnitLiveness,

  /** May the operator be offered an ARM at all? Either unit alive suffices. */
  val canArm: Boolean,

  /** Per-machine: can a press here actually reach that machine right now? */
  val driveCommandable: Boolean,
  val thrusterCommandable: Boolean,

  /** RX's own report of each drive, for display. */
  val portState: DisplayDrivePosition,
  val stbdState: DisplayDrivePosition,

  /** Who RX/HH say is commanding each machine. */
  val portSource: CommandSource,
  val stbdSource: CommandSource,
  val thrusterSource: CommandSource,

  /**
   * "controlled by X" notes -- non-null only when we ARE the armed controller
   * and a higher-precedence source owns that machine, i.e. exactly the case
   * where a press here visibly does nothing and the operator needs to know why.
   * When we are not the controller, the disarmed/foreign state already explains
   * it and a second explanation would be noise.
   */
  val portOverriddenBy: String?,
  val stbdOverriddenBy: String?,
  val thrusterOverriddenBy: String?,

  /** The heading HH reports it is holding, or null if it has not said. */
  val heldDeg: Double?,

  /**
   * The boat's CURRENT heading, from HH's own fused estimate, or null.
   *
   * Separate from [heldDeg] on purpose. HH publishes its setpoint as the held
   * target only while it is actually holding, and as a mirror of this value
   * otherwise -- so on a station that is not the one holding, the setpoint is
   * somebody else's target and says nothing about where the boat is pointing.
   */
  val currentHeadingDeg: Double?,

  /** HH is waiting out the thruster control box's reversal interlock. */
  val reversalPending: Boolean,

  /** Raw-ish telemetry for the status lamps. */
  val rxLinkUp: Boolean?,
  val rxLinkOk: Boolean?,
  val rxMasterEnable: Boolean?,
  val hhArmed: Boolean?,
  val thrusterState: String?,
) {
  val armed: Boolean
    get() = controlState == ControlState.YOU

  val connected: Boolean
    get() = connectionState == ConnectionState.OPEN

  /**
   * Which units are not answering, named for the operator.
   *
   * Naming them matters: with one ARM covering both machines, "arming is
   * blocked" alone leaves the operator guessing which board to go and check.
   */
  val missingUnits: List<String>
    get() = buildList {
      if (!readyToArm(rxLiveness)) add("drive unit")
      if (!readyToArm(hhLiveness)) add("thruster unit")
    }
}

/**
 * Derive the whole view. Pure: [store] holds arrival stamps, [nowMs] is passed
 * in, and nothing here reads a clock or a socket.
 */
fun deriveStationView(
  store: SkValueStore,
  connectionState: ConnectionState,
  myClientId: String,
  nowMs: Long,
  /** Has this session's stream been open at least once? See [LinkPhase]. */
  everConnected: Boolean = true,
  /**
   * When the current target was set, on the same clock as [nowMs]; null if that
   * is not being tracked.
   *
   * Both of these default to the cautious answer -- "we have been connected, so
   * a gap is a fault" -- so a caller that does not supply them gets the
   * pre-[LinkPhase] behaviour rather than a quiet grace period it did not ask
   * for.
   */
  linkAttemptStartedMs: Long? = null,
): StationView {
  val rxLiveness =
    evaluateLiveness(
      connectionState = connectionState,
      telemetryAgeMs = store.ageMs(SkContract.RX_LINK_UP, nowMs),
      pluginUnitLive = store.boolOrNull(SkContract.PLUGIN_RX_LIVE),
      pluginVerdictAgeMs = store.ageMs(SkContract.PLUGIN_RX_LIVE, nowMs),
    )
  val hhLiveness =
    evaluateLiveness(
      connectionState = connectionState,
      telemetryAgeMs = store.ageMs(SkContract.HH_LINK_UP, nowMs),
      pluginUnitLive = store.boolOrNull(SkContract.PLUGIN_HH_LIVE),
      pluginVerdictAgeMs = store.ageMs(SkContract.PLUGIN_HH_LIVE, nowMs),
    )

  val controlState = controlStateOf(store[SkContract.PLUGIN_ACTIVE_CLIENT], myClientId)
  val driveCommandable = canCommand(controlState, connectionState, rxLiveness)
  val thrusterCommandable = canCommand(controlState, connectionState, hhLiveness)

  val portSource = parseSource(store[SkContract.RX_PORT_SOURCE])
  val stbdSource = parseSource(store[SkContract.RX_STBD_SOURCE])
  val thrusterSource = parseSource(store[SkContract.HH_SOURCE])

  return StationView(
    connectionState = connectionState,
    linkPhase =
      linkPhaseOf(
        connectionState = connectionState,
        everConnected = everConnected,
        connectingForMs = linkAttemptStartedMs?.let { nowMs - it } ?: Long.MAX_VALUE,
      ),
    controlState = controlState,
    rxLiveness = rxLiveness,
    hhLiveness = hhLiveness,
    canArm = canArm(rxLiveness, hhLiveness),
    driveCommandable = driveCommandable,
    thrusterCommandable = thrusterCommandable,
    portState = parseDisplayPosition(store[SkContract.RX_PORT_STATE]),
    stbdState = parseDisplayPosition(store[SkContract.RX_STBD_STATE]),
    portSource = portSource,
    stbdSource = stbdSource,
    thrusterSource = thrusterSource,
    portOverriddenBy = overrideNote(driveCommandable, portSource),
    stbdOverriddenBy = overrideNote(driveCommandable, stbdSource),
    thrusterOverriddenBy = overrideNote(thrusterCommandable, thrusterSource),
    heldDeg = plausibleHeadingOrNull(store[SkContract.HH_SETPOINT]),
    currentHeadingDeg =
      (store[SkContract.HH_FUSED_HEADING_RAD] as? Number)?.toDouble()?.let { rad ->
        // Published in radians; every other angle here is degrees. Normalised to
        // 0..360 because the fused value is free to run negative.
        plausibleHeadingOrNull(((Math.toDegrees(rad) % 360.0) + 360.0) % 360.0)
      },
    reversalPending = store.boolOrNull(SkContract.HH_REVERSAL_PENDING) == true,
    rxLinkUp = store.boolOrNull(SkContract.RX_LINK_UP),
    rxLinkOk = store.boolOrNull(SkContract.RX_LINK_OK),
    rxMasterEnable = store.boolOrNull(SkContract.RX_MASTER_ENABLE),
    hhArmed = store.boolOrNull(SkContract.HH_ARMED),
    thrusterState = store.stringOrNull(SkContract.HH_THRUSTER_STATE),
  )
}

private fun overrideNote(commandable: Boolean, source: CommandSource): String? =
  if (commandable && source.overridesThisApp) source.label else null
