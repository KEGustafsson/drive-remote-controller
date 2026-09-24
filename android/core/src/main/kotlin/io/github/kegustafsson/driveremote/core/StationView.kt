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
 * is `changeServer`, which refuses outright while armed on a live link, and
 * when the link is down leaves with a disarm -- after which the heartbeat is
 * stopped, so the arbiter stale-evicts this station even if that disarm is
 * lost. There is no state in which [CONNECTING] hides a live command.
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
 * What has become of this station's request for a heading hold.
 *
 * The point of the type is the middle state. A hold is not instantaneous: the
 * request leaves on the next intent, the arbiter republishes it, HH takes it on
 * a control tick and reports back on its own telemetry cycle -- so for a few
 * hundred milliseconds after every arm the honest answer is "asked, not
 * confirmed". Before this existed that interval was drawn as a warning, which
 * put a yellow line on the panel on every single arm and taught the operator to
 * read the one that MATTERS -- a hold the unit is genuinely refusing -- as the
 * same harmless flicker.
 *
 * Note what is NOT in here: "holding, probably". [ENGAGED] is HH's own report
 * and nothing else. The grace window moves the boundary between [REQUESTED] and
 * [NOT_ENGAGING]; it never lets an unconfirmed hold read as a running one.
 */
enum class HoldPhase {
  /** This station is not asking for a hold, or cannot command the thruster. */
  IDLE,

  /**
   * Asked for, not yet confirmed by HH, and still inside
   * [SkContract.HOLD_ENGAGE_GRACE_MS]. A transition, shown as a plain statement
   * of what was asked -- never as a warning.
   */
  REQUESTED,

  /** HH reports the hold running. See [StationView.holdEngaged]. */
  ENGAGED,

  /**
   * Asked for, and HH has had long enough to take it and has not. A fault, and
   * shown as one: the operator is looking at a thruster that is not holding the
   * heading they asked it to hold. [StationView.holdStall] names the reason.
   */
  NOT_ENGAGING,
}

/**
 * Why a requested hold is not running, as far as this station can tell.
 *
 * Read from HH's own FSM state ([SkContract.HH_FSM_STATE]) rather than guessed,
 * because the remedies differ: a refused hold needs a disarm and a fresh arm, a
 * missing heading reference needs the fix to come back first, and a faulted unit
 * needs someone to go and look at it. Only meaningful while the phase is
 * [HoldPhase.NOT_ENGAGING].
 */
enum class HoldStall {
  /** Not stalled. */
  NONE,

  /**
   * HH is not armed at all: it is refusing the request. The usual cause is the
   * re-engage latch (SAFETY.md thruster invariant 9) -- a station whose link
   * went stale mid-hold is refused until HH has seen it live and DISARMED, so
   * that a returning link cannot silently restart thrust. The operator's move is
   * to disarm and arm again.
   */
  REFUSED,

  /**
   * HH is armed but idle, which in HOLD means it has no heading it trusts enough
   * to steer against -- either it never had one, or it gave the hold up after
   * coasting past `coast_max` on a stale fix. Either way it needs a fresh arm
   * once the fix is back; nothing on the phone can shorten that.
   */
  NO_REFERENCE,

  /** HH has faulted -- its motion sensor has gone silent. Thrust is refused. */
  UNIT_FAULT,

  /**
   * Somebody else has the thruster (the unit's own ENGAGE input, or the
   * handheld). Not a fault at all, and already explained by the
   * "controlled by ..." note, which is why this one is drawn as nothing.
   */
  OTHER_SOURCE,

  /**
   * HH has not said, or said something this build does not recognise. The
   * message falls back to the fact -- it is not holding -- and to the one remedy
   * that is safe to suggest in every case.
   */
  UNKNOWN,
}

/**
 * [HoldPhase] from the facts. Pure, and the whole rule in one place.
 *
 * [requestedForMs] is how long this station has been asking for a hold, or null
 * when it is not asking for one at all -- so the null case is "no hold wanted"
 * rather than "unknown", and a caller that never tracks it gets [HoldPhase.IDLE]
 * and no hold diagnostics, never a false alarm.
 */
fun holdPhaseOf(
  thrusterCommandable: Boolean,
  holdEngaged: Boolean,
  requestedForMs: Long?,
): HoldPhase =
  when {
    requestedForMs == null || !thrusterCommandable -> HoldPhase.IDLE
    holdEngaged -> HoldPhase.ENGAGED
    requestedForMs < SkContract.HOLD_ENGAGE_GRACE_MS -> HoldPhase.REQUESTED
    else -> HoldPhase.NOT_ENGAGING
  }

/**
 * Why the hold is not running, from HH's FSM state.
 *
 * [thrusterOverridden] is checked first: a hold that is not running because the
 * thruster belongs to a higher-precedence source is not this station's fault to
 * report twice.
 */
fun holdStallOf(hhFsmState: String?, thrusterOverridden: Boolean): HoldStall =
  when {
    thrusterOverridden -> HoldStall.OTHER_SOURCE
    hhFsmState == SkContract.HH_FSM_FAULT -> HoldStall.UNIT_FAULT
    hhFsmState == SkContract.HH_FSM_ARMED_IDLE -> HoldStall.NO_REFERENCE
    hhFsmState == SkContract.HH_FSM_DISARMED -> HoldStall.REFUSED
    else -> HoldStall.UNKNOWN
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
   *
   * Null unless HH is live ([readyToArm] on [hhLiveness]): the last value a
   * silent unit published is not the current heading.
   */
  val currentHeadingDeg: Double?,

  /**
   * HH is waiting out the thruster control box's reversal interlock. False
   * unless HH is live -- a retained `true` from a unit that has gone quiet is
   * not an interlock anyone is waiting out.
   */
  val reversalPending: Boolean,

  /** Raw-ish telemetry for the status lamps. */
  val rxLinkUp: Boolean?,
  val rxLinkOk: Boolean?,
  val rxMasterEnable: Boolean?,
  val hhArmed: Boolean?,

  /**
   * HH's own mode -- `manual` or `hold`, or null if it has not said.
   *
   * Read together with [hhArmed] and never alone: the pair is what
   * ARCHITECTURE.md §9 names as the only way to tell "holding this" from "would
   * hold this". See [holdEngaged].
   */
  val hhMode: String?,
  val thrusterState: String?,

  /**
   * HH's own safety-FSM state ([SkContract.HH_FSM_STATE]), or null if it has not
   * said. A VALUE, so it is only ever read through something that has already
   * established HH is live -- [holdEngaged] and [holdPhase] both are.
   */
  val hhFsmState: String? = null,

  /**
   * What has become of this station's hold request. Defaults to the answer for a
   * station that is not asking for one, so a caller that does not track the
   * request gets no hold diagnostics rather than a wrong one.
   */
  val holdPhase: HoldPhase = HoldPhase.IDLE,

  /** Why, when [holdPhase] is [HoldPhase.NOT_ENGAGING]. */
  val holdStall: HoldStall = HoldStall.NONE,
) {
  val armed: Boolean
    get() = controlState == ControlState.YOU

  val connected: Boolean
    get() = connectionState == ConnectionState.OPEN

  /**
   * Must "disconnect / change server" be refused? Only while this station is
   * armed IN A LIVE VIEW of the boat, where disarming first is one tap and makes
   * the state unambiguous.
   *
   * Offline, [armed] is only the last-known `activeClient` that the store keeps
   * across a dropped socket. A disarm sent then can land and still never be
   * seen, so refusing on it locked the operator onto a server they could not
   * reach, with no way to pick another. See [changeServerSendsStop] for what
   * happens instead.
   */
  val changeServerRefused: Boolean
    get() = armed && connected

  /**
   * Leaving while the last-known view says armed but the link is down: the
   * departure carries a disarm, so this station cannot walk away still holding
   * the arm token. A disarm nobody needed costs nothing -- it is the universal
   * stop, and it travels over HTTP independently of the read stream.
   */
  val changeServerSendsStop: Boolean
    get() = armed && !connected

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

  /**
   * Is HH ACTUALLY holding, by its own report?
   *
   * Never a local guess, and this is the one place in the view where that
   * distinction changes a word on the screen. HH mirrors `hh.setpointDeg` to the
   * fused heading in every state EXCEPT holding (ARCHITECTURE.md §9), so the
   * number is a live, plausible heading whether the unit engaged, refused
   * because the heading was not trustworthy, faulted, had the thruster taken by
   * its own ENGAGE input, or is refusing a station whose disarm it has not seen
   * yet. "This station can command the thruster" -- [thrusterCommandable] --
   * answers none of that: it is only our own half, the arm token and a live
   * unit.
   *
   * [hhArmed] and [hhMode] are the pair §9 names, and the liveness test is what
   * stops a switched-off HH from reporting a hold it cannot still be running:
   * both are VALUES, and Signal K retains those forever. Same predicate as
   * [canCommand] uses, so "live enough to hold" and "live enough to command"
   * cannot drift apart.
   */
  val holdEngaged: Boolean
    get() =
      hhArmed == true &&
        hhMode == ThrusterMode.HOLD.wire &&
        readyToArm(hhLiveness) &&
        // The pair above is necessary and not sufficient: HH asserts ENABLE --
        // and so publishes hh.armed -- in ARMED_IDLE as well as HOLDING
        // (control_step.cpp), and in HOLD mode ARMED_IDLE is precisely the state
        // of a hold that never started, or that was given up when the heading
        // went stale past coast_max. Both publish armed + hold and neither is
        // holding anything. HH's own FSM state settles it when it is there;
        // when it is not -- nothing has arrived yet, or a firmware that predates
        // the path -- the pair stands on its own as before.
        (hhFsmState == null || hhFsmState == SkContract.HH_FSM_HOLDING)
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
  /**
   * When this station's CURRENT hold request began, on the same clock as
   * [nowMs]; null when it is not asking for a hold (MANUAL selected, or not
   * armed).
   *
   * Kept by the caller because it is a fact about what this station is sending,
   * which nothing in [store] can answer -- the store holds what the boat says
   * back. Defaults to null, the "no hold wanted" answer, so a caller that does
   * not track it gets no hold diagnostics rather than a false alarm.
   */
  holdRequestedSinceMs: Long? = null,
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

  // Everything except the hold phase, which is a function of this view's own
  // holdEngaged -- derived from the half-built view rather than recomputed from
  // the store, so there is exactly one definition of "HH reports the hold
  // running" and no second copy of it to drift.
  val view =
    StationView(
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
      // Both of these are HH's VALUES, and Signal K retains values forever: with
      // HH switched off or the socket down, the last heading is still in the
      // store, and was drawn frozen under a caption promising the CURRENT
      // heading. So they exist only while HH is live, by the same predicate
      // holdEngaged uses -- "---" is the honest reading of a unit that is not
      // answering (AGENTS.md: never present unconfirmable data as live).
      currentHeadingDeg =
        (store[SkContract.HH_FUSED_HEADING_RAD] as? Number)?.toDouble()?.takeIf {
          readyToArm(hhLiveness)
        }?.let { rad ->
          // Published in radians; every other angle here is degrees. Normalised to
          // 0..360 because the fused value is free to run negative.
          plausibleHeadingOrNull(((Math.toDegrees(rad) % 360.0) + 360.0) % 360.0)
        },
      reversalPending =
        readyToArm(hhLiveness) && store.boolOrNull(SkContract.HH_REVERSAL_PENDING) == true,
      rxLinkUp = store.boolOrNull(SkContract.RX_LINK_UP),
      rxLinkOk = store.boolOrNull(SkContract.RX_LINK_OK),
      rxMasterEnable = store.boolOrNull(SkContract.RX_MASTER_ENABLE),
      hhArmed = store.boolOrNull(SkContract.HH_ARMED),
      hhMode = store.stringOrNull(SkContract.HH_MODE),
      thrusterState = store.stringOrNull(SkContract.HH_THRUSTER_STATE),
      hhFsmState = store.stringOrNull(SkContract.HH_FSM_STATE),
    )

  val holdPhase =
    holdPhaseOf(
      thrusterCommandable = view.thrusterCommandable,
      holdEngaged = view.holdEngaged,
      requestedForMs = holdRequestedSinceMs?.let { nowMs - it },
    )
  return view.copy(
    holdPhase = holdPhase,
    holdStall =
      if (holdPhase == HoldPhase.NOT_ENGAGING) {
        holdStallOf(view.hhFsmState, view.thrusterOverriddenBy != null)
      } else {
        HoldStall.NONE
      },
  )
}

private fun overrideNote(commandable: Boolean, source: CommandSource): String? =
  if (commandable && source.overridesThisApp) source.label else null
