// Pure arming-arbitration core -- the server-side equivalent of the
// firmware's lib/control_core/*: NO Signal K / Node-server / socket deps,
// so it is exhaustively host-testable (test/arbiter.test.ts) exactly like
// arbitration.cpp is. index.cjs is the thin I/O shell that feeds this
// object client intents and publishes what state() reports; ALL the safety
// logic lives here.
//
// WHY THE SERVER ARBITRATES, AND NOT THE BROWSERS:
// if every open web-UI instance published control.remoteController.
// plugin.enabled itself, they would all share one $source, and two instances
// disagreeing about ARMED would make the single merged value oscillate
// true<->false at the republish cadence -- the receiving unit would see the
// plugin flapping between qualifying and not, and its output would chase it.
// So the Node plugin is the SOLE writer of the plugin.* paths, and this file
// is the arbitration behind them.
//
// SAFETY MODEL (matches the discussion in ARCHITECTURE.md §10):
//  - Exclusive ARM: at most one client holds the token at a time. Two
//    concurrent arm requests are resolved by this single-threaded arbiter --
//    first one wins, the rest are denied (locked out).
//  - Universal DISARM: ANY client's disarm request releases the token
//    immediately (global kill). Disarm is never gated on being the holder --
//    whoever is nearest the machinery can always stop it.
//  - Fail to NEUTRAL: no live holder => enabled=false and both commands
//    NEUTRAL. A holder that stops heart-beating (tab closed, WiFi drop) is
//    evicted after staleTimeoutMs and the token auto-releases.
//  - NO ARM WITHOUT A VERIFIED RX: arming is granted only while RX is
//    demonstrably alive, i.e. its telemetry has been seen within
//    rxStaleTimeoutMs (index.cjs feeds every rx.linkUp delta to
//    onRxTelemetry(), and every hh.linkUp delta to onHhTelemetry()). If RX dies while armed, the token is released on the
//    next tick. Before this existed the plugin would happily report ARMED
//    with the RX board switched off -- the UI showed a confident green
//    "ready" state for a controller that could not possibly move anything,
//    which is precisely the false confidence this control must never show.
//    RX itself already fails to NEUTRAL when commands stop arriving; this
//    closes the other half, so nothing anywhere claims "armed" without a
//    live RX behind it.
//  - EDGE, not level, arming: arm/disarm are monotonically-increasing
//    request counters, so a client holding an old request across a release
//    can NEVER silently re-arm -- re-arming requires a fresh press (a new
//    counter value). This is what makes a global disarm actually stick even
//    while the previous holder keeps heart-beating armReq unchanged.
//
// A client intent (POSTed by each UI to the plugin's own
// /plugins/<id>/intent route, ~250 ms heartbeat -- deliberately NOT a Signal
// K delta, so no per-tab node appears in the data model) is:
//   { clientId, seq, armReq, disarmReq, port, stbd,
//     thruster, thrusterMode, trimDeg, session? }
// where seq increments every publish (so heartbeats are never deduped by
// any change-detecting transport), armReq/disarmReq increment once per
// operator tap, the three thruster fields are the bow-thruster half of the
// contract (ARCHITECTURE.md §6), and session is the Android station's launch
// generation (absent from the browser UI -- see the session check below).
//
// seq is ENFORCED, not just documented: an intent whose seq is not strictly
// greater than the last accepted seq for that client is discarded whole. On
// a lossy LAN a delayed heartbeat can arrive AFTER a newer one, and replaying
// that stale intent must not revert commands -- and, critically, must not
// touch the edge baselines below. The baselines are additionally clamped
// monotonically (they can never move backward), because "never silently
// re-arm" must not depend on transport ordering alone: lowering lastArmReq
// (by a stale packet or a buggy client) and then replaying an old armReq
// value would otherwise read as a fresh tap and re-grant the token after a
// universal disarm that no operator ever answered.

'use strict';

const POSITIONS = ['forward', 'neutral', 'reverse'];
const THRUSTER_DIRECTIONS = ['port', 'off', 'stbd'];
const THRUSTER_MODES = ['manual', 'hold'];

// Largest heading-trim offset a station may command (deg). Mirrors
// control_core::kMaxTrimDeg (setpoint.h) and sk-plugin/src/config.ts MAX_TRIM_DEG.
const MAX_TRIM_DEG = 45;

// Ceiling on how many clients are tracked at once. Stale clients are evicted
// on every tick (staleTimeoutMs), so in normal use this sits at the number of
// open browser tabs -- a handful. The cap bounds the window BETWEEN ticks, in
// which a caller POSTing a fresh clientId in a loop would otherwise grow the
// Map without limit. Reaching it never affects an established client (existing
// records are still updated); it only refuses to start tracking NEW ones,
// which is the safe direction: an untracked client cannot arm and cannot
// command. Arming is already behind the SK server's auth (see SAFETY.md), so
// this is depth, not the primary control.
const MAX_TRACKED_CLIENTS = 32;

function sanitizePosition(v) {
  return POSITIONS.includes(v) ? v : 'neutral';
}

// Anything unrecognised reads as 'off' -- never as a direction for a thruster.
function sanitizeThruster(v) {
  return THRUSTER_DIRECTIONS.includes(v) ? v : 'off';
}

// Anything unrecognised reads as 'hold' -- never as direct manual control of a
// thruster. Same defensive default as the firmware's ThrusterModeFromSkString.
/**
 * The thruster mode published for an ARMED holder whose thruster is not
 * commandable (HH absent, or quarantined after an absence). Deliberately
 * 'manual', not 'hold': see state() -- with enabled=true, 'hold' is a live
 * engage request on HH, while 'manual' + 'off' is inert. 'hold' stays the
 * default for an unrecognised mode value (sanitizeThrusterMode) and for the
 * no-holder tuple, where enabled=false makes it inert.
 */
const REST_MODE_WHILE_ARMED = 'manual';

function sanitizeThrusterMode(v) {
  return THRUSTER_MODES.includes(v) ? v : 'hold';
}

// A commanded heading TRIM is a relative offset, so it always resolves to a
// number: non-finite (NaN, null, corruption) becomes 0 = no trim (the safe
// rest, since 0 is a real command here), and any finite value is clamped to
// +/-MAX_TRIM_DEG. Mirrors control_core::ClampTrimDeg.
//
// THE CLAMP IS DELIBERATE, and it is the one place this file reads against the
// letter of SAFETY.md's "implausible numbers are rejected, not clamped". Both
// halves of that rule are honoured where they bite: a value that is not a
// number at all is REJECTED to the safe rest (0 = no trim), and only a finite
// number is clamped. Clamping the finite case is what control_core::ClampTrimDeg
// (setpoint.h) and the Android core both do, and the three copies are hand-synced
// with no shared build step -- changing one of them would put the stations and
// the units into disagreement about what a station just asked for, which is a
// worse failure than the bounded one being accepted here. The bound itself is
// what keeps the outcome safe: the worst a corrupted finite trim can produce is
// +/-45 deg of commanded offset, slewed at HH's rate limit, against a heading
// hold the operator has already armed -- never an arbitrary heading, and never a
// direct thrust command. See ARCHITECTURE.md 6.4.
function sanitizeTrimDeg(v) {
  if (typeof v !== 'number' || !Number.isFinite(v)) return 0;
  if (v > MAX_TRIM_DEG) return MAX_TRIM_DEG;
  if (v < -MAX_TRIM_DEG) return -MAX_TRIM_DEG;
  return v;
}

// Coerce a request counter. Anything non-finite is treated as "no value"
// (null) so first-sighting establishes a baseline WITHOUT firing an edge --
// a client appearing must never, by the mere act of appearing, count as a
// fresh arm/disarm press.
function sanitizeCounter(v) {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

// A per-LAUNCH session generation: a positive, strictly increasing integer the
// station persists and bumps once per process start. NOT a uuid, and that is the
// whole point -- generations are ORDERED, so a session can be compared rather
// than merely distinguished.
//
// The unordered version of this needed a memory of superseded ids, which then
// had to be bounded, and a bound is a window in which a forgotten id becomes
// acceptable again: a late packet from it would clear the real holder and mark
// the RUNNING session as superseded, silently killing that station's STOP. An
// order cannot forget. `session < rec.session` is closed for good, with no state
// to keep and nothing to age out.
//
// null means the station sent none -- an older client, or the browser UI, which
// mints a fresh clientId per page load and so needs no generation at all.
function sanitizeSession(v) {
  return typeof v === 'number' && Number.isInteger(v) && v > 0 ? v : null;
}

class ArmArbiter {
  /**
   * @param {object} [opts]
   * @param {number} [opts.staleTimeoutMs=1000] time since a client's last
   *   intent after which it is considered gone. Mirrors the firmware's
   *   config::kSkStalenessTimeoutMs so RX and the plugin judge "gone" on the
   *   same clock: if the holder disappears, RX would fail this source stale
   *   at ~1 s anyway, so releasing here on the same budget keeps the two
   *   views consistent.
   * @param {number} [opts.rxStaleTimeoutMs=1500] time since the last RX
   *   telemetry delta after which RX is considered gone (powered off, out of
   *   WiFi range, crashed). RX republishes its whole telemetry set every
   *   config::kSkPeriodicRefreshMs (250 ms), so this is six missed refreshes
   *   -- deliberately looser than staleTimeoutMs because this path crosses
   *   WiFi *and* the SK server, and a false "RX gone" would drop a live
   *   operator's arm mid-manoeuvre.
   */
  constructor(opts = {}) {
    this.staleTimeoutMs =
      typeof opts.staleTimeoutMs === 'number' ? opts.staleTimeoutMs : 1000;
    this.rxStaleTimeoutMs =
      typeof opts.rxStaleTimeoutMs === 'number' ? opts.rxStaleTimeoutMs : 1500;
    /** @type {string|null} the single client currently holding the token */
    this.holder = null;
    /**
     * When RX telemetry was last seen. null = never seen since this plugin
     * started, which counts as NOT live: on a fresh start we have no evidence
     * RX exists, and "no evidence" must read as "cannot arm", not as "fine".
     * @type {number|null}
     */
    this._rxLastSeenMs = null;
    /** Cached verdict, recomputed at the top of every onIntent()/tick(). */
    this._rxLive = false;
    /**
     * The same, for the heading-hold (bow thruster) unit. Tracked separately
     * from RX because the two are independent machines that can be powered,
     * fail, or leave WiFi range independently -- and because arming must be
     * able to say WHICH one is missing rather than just refusing.
     * @type {number|null}
     */
    this._hhLastSeenMs = null;
    this._hhLive = false;
    /**
     * A machine whose unit has been away needs the operator to LET GO before it
     * will accept motion again.
     *
     * Erasing the stored command when a unit vanishes is only half the job: the
     * holder's next 250 ms heartbeat still carries whatever its buttons say, so
     * a finger that never moved rewrites `forward` the moment the unit answers
     * and motion resumes with no fresh operator action behind it. `seq` does
     * not help -- it orders transport, it does not signal intent.
     *
     * So after an absence the machine is quarantined: safe values are published
     * regardless of what the holder asks for, until the holder is seen asking
     * for the safe value. That "release" is the fresh baseline; the next
     * non-safe value after it is a real new command. It mirrors what the arm
     * token already does -- re-arming needs a fresh press once RX is back --
     * and it is the direction SAFETY.md's invariant 5 points in: a dropout
     * means the machine's state and the operator's mental model have diverged,
     * and the operator is the one who has to reconcile them.
     */
    //
    // START QUARANTINED. A unit never seen since this arbiter started has no
    // live-to-dead edge to trip the gate, so initialising these to false left
    // the first appearance ungated -- and that is reachable at every plugin
    // restart: arming needs only ONE live unit, so a holder can arm through the
    // thruster, keep an active drive command in its heartbeat while RX is still
    // absent (masked, so it looks inert), and have it applied on the first
    // heartbeat after RX powers up. Motion starting on a press made before RX
    // existed.
    //
    // "Never seen" is not the same situation as "was here and came back", but it
    // wants the same baseline for the same reason: nothing has told us the
    // machine and the operator's intent agree. Costs nothing in normal use -- a
    // station with no button pressed sends the safe value on its next heartbeat
    // and clears this within 250 ms.
    this._driveNeedsRelease = true;
    this._thrusterNeedsRelease = true;
    // Per-client bookkeeping, keyed by clientId.
    this._clients = new Map(); // clientId -> { lastSeenMs, lastArmReq, lastDisarmReq, port, stbd }
    // Counter baselines that OUTLIVE eviction from _clients, keyed by the same
    // clientId. A station's arm/disarm counters are cumulative for the life of
    // its session, so a station we have merely stopped hearing from is not a
    // new station when it comes back -- see _rememberCounters().
    this._counterMemory = new Map(); // clientId -> { lastArmReq, lastDisarmReq }
    /**
     * Highest session generation ever seen per client, kept OUTSIDE the record.
     *
     * The record is evictable -- a station silent for staleTimeoutMs loses it --
     * and it held the only copy of the generation. So after an eviction a delayed
     * packet from an older session met `rec == null`, was taken as a first
     * sighting, and two queued packets from that dead session (a baseline, then
     * an ARM edge carrying an active command) could re-arm and move a machine
     * after the live session had gone quiet. The order has to outlive the record
     * that happened to be carrying it.
     *
     * Same bound and ageing as _counterMemory, and the same residual: a client
     * displaced by MAX_TRACKED_CLIENTS others loses its watermark. That is the
     * bound this design already accepts for disarm baselines, stated rather than
     * implied.
     *
     * @type {Map<string, number>}
     */
    this._sessionWatermark = new Map();
  }

  /**
   * Feed evidence that RX is alive: called by index.cjs for EVERY rx.*
   * telemetry delta seen on the server, whatever its value. This is a
   * liveness signal, not a health one -- the *content* of rx.linkUp says
   * whether RX can see a remote source; the mere arrival of the delta is
   * what says RX itself is powered and connected. Reading only the value
   * (as the UI used to) can never detect RX vanishing, because a value
   * nobody updates just sits there at its last reading forever.
   */
  onRxTelemetry(nowMs) {
    // Settle any liveness transition FIRST, against the previous arrival. A
    // gap can open and close entirely between two ticks -- cross the timeout
    // at t+1500 and be answered at t+1600, with the next tick not due until
    // t+1750 -- and then nothing would ever observe the dead edge: this method
    // would simply mark the unit live again. The unit's own watchdog has
    // already failed its outputs safe by then, so skipping the edge would
    // republish the holder's pre-gap command and restart motion off a returning
    // telemetry frame rather than an operator action.
    this._refreshUnitLiveness(nowMs);
    this._rxLastSeenMs = nowMs;
    // Telemetry arriving right now IS the proof, so update the cached verdict
    // here too rather than leaving state() reporting a stale `false` until
    // the next tick -- the caller publishes immediately on the dead->alive
    // edge and must not publish rxLive:false in that same breath.
    this._rxLive = true;
  }

  /** Is RX currently proven alive? Pure read; safe to call any time. */
  rxLive(nowMs) {
    return (
      this._rxLastSeenMs !== null &&
      nowMs - this._rxLastSeenMs <= this.rxStaleTimeoutMs
    );
  }

  /**
   * The heading-hold unit's equivalent of onRxTelemetry: called by index.cjs
   * for every hh.* telemetry delta seen, whatever its value. Same reasoning --
   * arrival is the proof of life, the value never can be.
   */
  onHhTelemetry(nowMs) {
    // Settle the falling edge before recording the arrival -- see
    // onRxTelemetry for why a gap that opens and closes between ticks would
    // otherwise never be seen at all.
    this._refreshUnitLiveness(nowMs);
    this._hhLastSeenMs = nowMs;
    this._hhLive = true;
  }

  /** Is the heading-hold unit currently proven alive? */
  hhLive(nowMs) {
    return (
      this._hhLastSeenMs !== null &&
      nowMs - this._hhLastSeenMs <= this.rxStaleTimeoutMs
    );
  }

  /**
   * Arming requires at least ONE reachable unit -- not both.
   *
   * This is a deliberate choice against the stricter "both units must be live"
   * rule. The drives are the primary docking tool and the thruster board is
   * the more likely of the two to be switched off or unpowered; a strict gate
   * would make an absent thruster board silently disable gear control at the
   * exact moment an operator needs it. So one ARM covers both machines, grants
   * whatever is actually reachable, and the UI states plainly which unit is
   * missing rather than pretending everything is fine (state() publishes both
   * verdicts for exactly that).
   *
   * The false-confidence rule is untouched: nothing is ever armed against a
   * unit that is not answering, because each unit's own commands are gated by
   * its own liveness downstream, and a completely unreachable pair cannot arm
   * at all.
   */
  anyUnitLive(nowMs) {
    return this.rxLive(nowMs) || this.hhLive(nowMs);
  }

  /**
   * Feed one client intent. Returns true if the published state (what
   * state() reports) changed as a result, so the caller can publish
   * immediately on change rather than waiting for the next heartbeat tick.
   */
  onIntent(intent, nowMs) {
    this._refreshUnitLiveness(nowMs);
    if (!intent || typeof intent !== 'object') return false;
    const clientId = intent.clientId;
    if (typeof clientId !== 'string' || clientId.length === 0) return false;
    // Bound per-client bookkeeping: a clientId is a short generated token
    // (makeClientId is ~40 chars); anything oversized is garbage or abuse and
    // must not become a Map key.
    if (clientId.length > 64) return false;

    const before = this._stateKey();

    const seq = sanitizeCounter(intent.seq);
    const armReq = sanitizeCounter(intent.armReq);
    const disarmReq = sanitizeCounter(intent.disarmReq);
    const port = sanitizePosition(intent.port);
    const stbd = sanitizePosition(intent.stbd);
    const thrusterMode = sanitizeThrusterMode(intent.thrusterMode);
    // Structural field separation, mirroring the firmware's ArbitrateThruster:
    // a manual direction cannot survive into hold mode and a commanded target
    // cannot survive into manual mode, so no consumer can act on a stale one.
    const thruster =
      thrusterMode === 'manual' ? sanitizeThruster(intent.thruster) : 'off';
    // A trim is honoured only when the station EXPLICITLY asked for hold.
    //
    // sanitizeThrusterMode degrades anything unrecognised to 'hold', which is
    // the right answer for the mode on its own -- garbage must never read as
    // direct manual thrust. But it used to be only half the tuple: keying the
    // trim off the SANITISED mode meant a mangled mode field arriving with an
    // otherwise valid `trimDeg: 30` was promoted into a live hold with a 30 deg
    // commanded swing behind it. That is a command synthesised out of
    // corruption, which is exactly what screening at the boundary exists to
    // stop, and the safe tuple for the thruster is `hold` AND no trim (the same
    // pair _maybeRelease treats as the operator having let go).
    //
    // So the raw value is tested, not the sanitised one. Both real stations
    // always send the field (App.tsx and the Android ClientIntent), so this
    // costs a legitimate station nothing; a client that omits it is asking for
    // nothing in particular and gets the rest value.
    const trimDeg = intent.thrusterMode === 'hold' ? sanitizeTrimDeg(intent.trimDeg) : 0;

    const session = sanitizeSession(intent.session);

    let rec = this._clients.get(clientId);

    // A DIFFERENT session id on the same clientId is the same station restarted,
    // and it has to be recognised BEFORE the ordering gate below.
    //
    // The phone persists clientId -- it must, since the Signal K access token is
    // issued to that device id -- but `seq` and the arm/disarm counters live in
    // the ViewModel and begin again at 0. If the process restarts inside
    // staleTimeoutMs the old record is still here, so the new session's first
    // packets carry a LOWER seq, hit `seq <= rec.lastSeq`, and are discarded
    // whole: the station gets 200 while its STOP does nothing and the retained
    // command (or another station's arm) stands until eviction. STOP is the one
    // control this project never gates.
    //
    // Why a nonce rather than inferring it from the counters: within a session a
    // delayed packet also arrives with lower counters, so "went backwards" cannot
    // tell a restart from a replay. Guessing restart would let a replayed packet
    // re-fire an old edge; guessing replay is what swallows the STOP. The nonce
    // removes the ambiguity instead of trading one failure for the other.
    //
    // Handled by DELETING the record and falling through to the first-sighting
    // path rather than resetting the baselines here. That path already gets a new
    // session right -- baselines without an edge, STOP honoured on the first
    // packet, commands masked for absent units, counters banked -- and is tested.
    // A second copy of those rules could only drift from it.
    // SESSION GENERATIONS. The full state table, because every corner of it has
    // been a defect: a station restarts with `seq` and its counters back at 0,
    // and `clientId` persists (the Signal K token is issued to that device id),
    // so without this a relaunch inside staleTimeoutMs meets its own old record
    // and every packet is filtered as a replay -- STOP included.
    //
    //   incoming   on record   verdict
    //   --------   ---------   -------
    //   null       null        same session (legacy client, or the browser UI)
    //   n          null        RESTART: the client became session-aware, which
    //                          can only mean a new process
    //   null       m           CLOSED: a process older than the one on record
    //   n > m      m           RESTART
    //   n === m    m           same session
    //   n < m      m           CLOSED: superseded, and permanently so
    //
    // CLOSED is discarded exactly like a replayed packet: not evidence of life,
    // must not touch a baseline, and above all must not restore the command it
    // was carrying. An ordered generation is what makes "permanently" true --
    // the unordered version needed a bounded memory of dead ids, and the bound
    // was a window in which a forgotten id became current again, clearing the
    // real holder and killing the running station's STOP.
    const watermark = this._sessionWatermark.get(clientId) ?? null;
    const closed =
      (rec != null && rec.session != null && (session === null || session < rec.session)) ||
      // No record -- evicted, or never seen. The watermark still knows the order.
      // `session === watermark` is allowed through: that is the SAME session
      // re-registering after its record was evicted, which is ordinary operation
      // for a station that went quiet for a second.
      (rec == null && watermark != null && (session === null || session < watermark));
    if (closed) return false;

    const restarted = rec != null && session !== null && session !== rec.session;
    if (restarted) {
      // Release the arm if this station held it. `holder` is keyed by clientId,
      // which survives the restart -- so without this the boat stays ARMED
      // across a relaunch that no operator authorised: the new process starts
      // with armReq = 0 and has pressed nothing. That is precisely what the
      // edge-not-level arming rule exists to prevent, and a restart is the
      // clearest possible case of an operator whose intent we no longer know.
      if (this.holder === clientId) this.holder = null;
      this._clients.delete(clientId);
      // The dead session's totals must go too, or the restarted station's first
      // STOP would be scored against a counter it never sent.
      this._counterMemory.delete(clientId);
      rec = undefined;
    }

    if (!rec) {
      // STOP is universal and must work even on the first packet we have seen
      // from a station -- the browser's mount heartbeat and a hurried STOP may
      // race, or the former may be lost, and neither may turn STOP into a
      // no-op. Only ARM needs a pre-existing baseline.
      //
      // The baseline compared against is 0 only for a station genuinely never
      // seen. For one we HAVE seen and evicted it is that station's remembered
      // counter: `disarmReq` is cumulative for the life of its session, so a
      // re-registering station carrying the same total is not pressing STOP,
      // it is just back. Without that, any station that had ever pressed STOP
      // would kill another station's arm every time it returned from more than
      // staleTimeoutMs away -- which the Android release-on-background path
      // makes an ordinary occurrence. A counter that ADVANCED while away is a
      // real press and still fires.
      //
      // Handled BEFORE the capacity check below, and WITHOUT creating a
      // record. Universal STOP outranks bookkeeping: a full client table (32
      // live sessions, or a client-id flood) must not be a way to make a
      // genuine stop return 200 and do nothing while a machine is still being
      // commanded. Recording nothing here grants a flood no foothold -- this
      // path can only ever move the system towards safe.
      // A counter that moved BACKWARD is not a replay to be clamped away.
      // There is no record here, so there is no session for it to replay
      // within -- it is the same station restarted. `clientId` is persisted on
      // the phone but the disarm counter lives in the ViewModel, so a process
      // restart reuses the id with a counter that begins again at 0. Scored
      // against the remembered total, the restarted station's first STOP
      // presses are all below it and would be silently swallowed -- and this
      // is the one control the project never gates. Score a reset against 0.
      //
      // The residual is a delayed pre-eviction packet read as a fresh stop,
      // which fires a STOP that need not have fired. That is the same trade
      // _rememberCounters() already documents: a spurious stop is the safe
      // direction, a swallowed one is not.
      // ORDERED evidence of a relaunch, and the only thing that may drop this
      // station's remembered totals here. A generation strictly above the
      // watermark can only come from a new process, so its counters genuinely
      // begin again and scoring them against the dead session's totals would
      // swallow the operator's first taps -- the phone persists clientId (the
      // SK token is issued to it) while its counters live in the ViewModel, so
      // an app relaunch while the record is already evicted lands exactly here.
      // A GUESS from counters going backwards must never do this: that is
      // indistinguishable from a replay, and on the arm side it is the replay
      // that matters (below). This mirrors what the with-record restart branch
      // above already does, for the case where the record is gone.
      const relaunched = session !== null && session > (watermark ?? 0);
      if (relaunched) this._counterMemory.delete(clientId);

      const remembered = this._rememberedDisarmReq(clientId);
      const restarted = disarmReq !== null && disarmReq < remembered;
      if (disarmReq !== null && disarmReq > (restarted ? 0 : remembered)) {
        this.holder = null;
      }
      // Bank the counter whether or not the edge fired, and whether or not the
      // record below is refused. Consuming an edge without remembering it
      // would turn one stop into a ratchet: at capacity, every later heartbeat
      // from this station still reads `disarmReq > 0` against a baseline of
      // zero, so it would re-fire the stop forever and prevent anyone from
      // re-arming. Banking the non-firing values too keeps the memory tracking
      // the CURRENT session, so a restarted station that is evicted a second
      // time is not scored against its dead session's total all over again.
      if (disarmReq !== null || armReq !== null) {
        this._rememberCounters(clientId, {
          lastArmReq: armReq,
          lastDisarmReq: disarmReq,
        });
      }
      // Refuse to start tracking a new client once the table is full (see
      // MAX_TRACKED_CLIENTS). Checked here, before any state is recorded, so a
      // flood cannot displace or disturb the clients already being tracked --
      // in particular it can never evict the current holder, whose kill switch
      // must keep working.
      if (this._clients.size >= MAX_TRACKED_CLIENTS) {
        return this._stateKey() !== before;
      }
      // THE ARM BASELINE ONLY EVER MOVES FORWARD ACROSS AN EVICTION.
      //
      // Everything that keeps a replayed packet from firing an arm edge -- the
      // seq gate and the monotonic clamp -- lives in the record, and eviction
      // destroys the record. Only the counters were remembered, and only the
      // disarm one, so the arm baseline used to be re-established from whichever
      // packet happened to arrive first after a station re-registered. A delayed
      // PRE-TAP heartbeat arriving before the current one therefore reset the
      // baseline below the arm total the operator had already spent, and the
      // very next heartbeat -- carrying no new press at all -- read as a fresh
      // tap and re-granted the token. Two POSTs delivered out of order across a
      // WiFi recovery is all it took, and the arm it handed back could be
      // carrying a held command.
      //
      // So a re-registering station's baseline is max(incoming, remembered).
      // The first-sighting rule is unchanged and still absolute -- no edge is
      // ever fired here, whatever the counters say -- this only decides what the
      // NEXT packet has to beat. A press made while the station was away is
      // swallowed and needs a re-tap, which is the same answer the token itself
      // gives after any release: re-arming takes a fresh press.
      const rememberedArm = this._rememberedArmReq(clientId);
      const armBaseline =
        armReq === null
          ? rememberedArm
          : rememberedArm === null
            ? armReq
            : Math.max(armReq, rememberedArm);

      // First sighting: record baselines, DO NOT treat as an edge.
      rec = {
        lastSeenMs: nowMs,
        // Remembered so a later packet carrying a different one is recognised
        // as a restart rather than filtered as a replay. null when the station
        // sends none, which disables the check for that client only.
        session,
        lastSeq: seq,
        lastArmReq: armBaseline,
        lastDisarmReq: disarmReq,
        // A command for an absent unit must not sit latent and spring to life
        // when that unit returns.  Ignore it at the authority boundary rather
        // than trusting every station to neutralise its own UI state.
        port: this._driveCommandable() ? port : 'neutral',
        stbd: this._driveCommandable() ? stbd : 'neutral',
        thruster: this._thrusterCommandable() ? thruster : 'off',
        thrusterMode: this._thrusterCommandable() ? thrusterMode : REST_MODE_WHILE_ARMED,
        trimDeg: this._thrusterCommandable() ? trimDeg : 0,
      };
      this._clients.set(clientId, rec);
      if (session !== null) this._raiseSessionWatermark(clientId, session);
      this._maybeRelease(clientId, { port, stbd, thruster, trimDeg });
      return this._stateKey() !== before;
    }

    // Ordering gate: discard a stale or replayed intent outright. It must
    // not refresh liveness (a delayed old packet is not proof the client is
    // alive NOW), must not revert commands, and must not touch the edge
    // baselines. Tolerates clients that never send seq (null skips the
    // gate; the monotonic clamps below still protect the baselines).
    if (seq !== null && rec.lastSeq !== null && seq <= rec.lastSeq) {
      return false;
    }
    if (seq !== null) rec.lastSeq = seq;

    rec.lastSeenMs = nowMs;
    rec.port = this._driveCommandable() ? port : 'neutral';
    rec.stbd = this._driveCommandable() ? stbd : 'neutral';
    rec.thruster = this._thrusterCommandable() ? thruster : 'off';
    rec.thrusterMode = this._thrusterCommandable() ? thrusterMode : REST_MODE_WHILE_ARMED;
    rec.trimDeg = this._thrusterCommandable() ? trimDeg : 0;
    // After storing, so a release only takes effect from the next intent.
    this._maybeRelease(clientId, { port, stbd, thruster, trimDeg });

    // An intent carrying BOTH a new disarm and a new arm resolves to DISARMED.
    //
    // Statement order does not achieve that and used to be relied on: the disarm
    // sets holder to null, and the arm gate below is `holder === null`, so the
    // disarm HANDED the arm its precondition and the same packet armed. The
    // kill switch granting the token is as bad as this gets, and the app made it
    // directly reachable when STOP was allowed to bypass the send lane -- an
    // urgent STOP overtakes a not-yet-delivered ARM, and the snapshot it carries
    // has both counters already incremented.
    //
    // So the disarm edge is computed once and suppresses arm processing for the
    // whole intent. The arm baseline is still clamped forward, i.e. the edge is
    // CONSUMED without being granted: the operator pressed STOP, so their intent
    // was to stop, and re-arming afterwards needs a fresh press. That is the
    // edge-not-level rule, not a special case.
    const disarmEdge =
      disarmReq !== null &&
      rec.lastDisarmReq !== null &&
      disarmReq > rec.lastDisarmReq;
    if (disarmEdge) {
      // Universal disarm: any client releases the token, whoever holds it.
      this.holder = null;
    }
    // Monotonic clamp: the baseline may only ever move FORWARD. Assigning a
    // lower incoming value would let a later replay of an already-consumed
    // counter read as a fresh edge (the silent re-arm / dead-kill-switch
    // failure the header describes).
    if (disarmReq !== null) {
      rec.lastDisarmReq =
        rec.lastDisarmReq === null
          ? disarmReq
          : Math.max(rec.lastDisarmReq, disarmReq);
    }

    if (
      !disarmEdge &&
      armReq !== null &&
      rec.lastArmReq !== null &&
      armReq > rec.lastArmReq
    ) {
      // Exclusive arm: granted only when nobody holds the token. If someone
      // else holds it this request is denied (the client stays locked out
      // and its UI shows "another device in control"); it must first disarm
      // (global stop) then arm to take over.
      //
      // AND granted only while RX is proven alive. Note this gate is on RX
      // telemetry ARRIVING, not on the value of rx.linkOk: linkOk means "a
      // remote source is live AND enabled", i.e. it is a consequence of
      // something already being armed -- gating arming on it would deadlock
      // (nothing could ever arm first). rx.linkUp's value is no good as a
      // gate either: it is exactly the value that sits at a stale `true`
      // when RX is switched off.
      if (this.holder === null && (this._rxLive || this._hhLive)) {
        this.holder = clientId;
      }
    }
    // Same monotonic clamp as lastDisarmReq above.
    if (armReq !== null) {
      rec.lastArmReq =
        rec.lastArmReq === null ? armReq : Math.max(rec.lastArmReq, armReq);
    }

    return this._stateKey() !== before;
  }

  /**
   * Evict clients not heard from within staleTimeoutMs. If the holder is
   * evicted the token auto-releases (fail to NEUTRAL). Returns true if the
   * published state changed.
   */
  tick(nowMs) {
    this._refreshUnitLiveness(nowMs);
    const before = this._stateKey();
    for (const [clientId, rec] of this._clients) {
      if (nowMs - rec.lastSeenMs > this.staleTimeoutMs) {
        this._rememberCounters(clientId, rec);
        this._clients.delete(clientId);
        if (this.holder === clientId) this.holder = null;
      }
    }
    // Both output units went away while armed: release the token. Each unit
    // has already failed its own outputs safe, and keeping enabled=true would
    // only make the UI claim authority that reaches no machinery. Re-arming
    // needs a fresh press after either unit returns.
    if (this.holder !== null && !this._rxLive && !this._hhLive) {
      this.holder = null;
    }
    return this._stateKey() !== before;
  }

  /**
   * The canonical state the plugin publishes to the RX-facing paths. When
   * there is no live holder this is the fail-safe (disarmed, both neutral).
   */
  state() {
    const holderRec = this.holder ? this._clients.get(this.holder) : null;
    return {
      enabled: holderRec != null,
      port: holderRec && this._driveCommandable() ? holderRec.port : 'neutral',
      stbd: holderRec && this._driveCommandable() ? holderRec.stbd : 'neutral',
      // Fail to OFF for the thruster exactly as the drives fail to NEUTRAL:
      // with no live holder nothing is commanded and the trim is 0 (hold
      // whatever HH captures). 0 is the safe rest -- no sentinel needed.
      //
      // The MODE published alongside depends on whether anyone is armed. With
      // no holder, enabled=false makes any mode inert and 'hold' (the garbage
      // default) is fine. With a holder but the thruster not commandable, the
      // tuple goes out with enabled=true, and there 'hold' is NOT a rest
      // value: HH reads {live, enabled, mode: hold} as an engage request that
      // enters HOLDING the moment its heading is good -- automatic thrust off
      // a returning telemetry frame, with the operator's own UI showing
      // MANUAL. That is exactly what the quarantine exists to prevent, so the
      // rest mode while armed is MANUAL with direction OFF, which HH turns
      // into ENABLE asserted and both direction lines low -- the same state
      // as an armed MANUAL operator with no finger on a contact.
      thruster: holderRec && this._thrusterCommandable() ? holderRec.thruster : 'off',
      thrusterMode:
        holderRec && this._thrusterCommandable()
          ? holderRec.thrusterMode
          : holderRec
            ? REST_MODE_WHILE_ARMED
            : 'hold',
      trimDeg: holderRec && this._thrusterCommandable() ? holderRec.trimDeg : 0,
      activeClient: holderRec ? this.holder : '',
      // Published so every UI shows the same verdict the gate above actually
      // applied, rather than each browser re-deriving RX health from values
      // that go stale silently. Unlike rx.linkUp, this is recomputed by the
      // server continuously, so even the cached copy a reconnecting UI
      // receives is a true statement about RX at the moment it was written.
      rxLive: this._rxLive,
      // Same contract for the thruster unit, so a UI can grey out its
      // thruster controls specifically rather than the whole panel.
      hhLive: this._hhLive,
    };
  }

  /**
   * Remember an evicted client's arm and disarm counters, so its return is
   * recognised as the SAME station resuming rather than a new one arriving.
   *
   * Bounded like _clients, and insertion-ordered so the oldest memory is the
   * one dropped. Losing a DISARM memory is not dangerous: that station's next
   * re-registration is treated as a first sighting again, and a STOP that fires
   * when it need not is never the dangerous direction. Losing an ARM memory is
   * the residual of the bound documented on _sessionWatermark, and it costs a
   * fresh press at worst -- never a granted one, because a first sighting can
   * only ever raise the arm baseline (below).
   *
   * THE TWO COUNTERS AGE DIFFERENTLY, and the asymmetry is the safety property:
   *
   *  - lastDisarmReq is OVERWRITTEN, so it can move backward. That is what lets
   *    the memory track the CURRENT session of a station that restarted with its
   *    counters back at 0 (see the first-sighting path), and guessing "restart"
   *    there costs at most a STOP that need not have fired.
   *  - lastArmReq only ever moves FORWARD. Guessing "restart" on the arm side is
   *    the dangerous direction -- it hands a replayed heartbeat a lowered
   *    baseline to fire an edge against, which is the silent re-arm this memory
   *    exists to prevent. Ordered evidence of a relaunch (a session generation
   *    above the watermark) clears the whole entry instead; a guess never does.
   */
  _rememberCounters(clientId, rec) {
    const prev = this._counterMemory.get(clientId);
    const incomingArm = typeof rec.lastArmReq === 'number' ? rec.lastArmReq : null;
    const keptArm =
      prev && typeof prev.lastArmReq === 'number' ? prev.lastArmReq : null;
    const lastArmReq =
      incomingArm === null
        ? keptArm
        : keptArm === null
          ? incomingArm
          : Math.max(keptArm, incomingArm);
    // A null incoming disarm total is "this packet said nothing about it", not
    // "it is back to zero", so the remembered value stands.
    const lastDisarmReq =
      typeof rec.lastDisarmReq === 'number'
        ? rec.lastDisarmReq
        : prev && typeof prev.lastDisarmReq === 'number'
          ? prev.lastDisarmReq
          : null;

    if (
      this._counterMemory.size >= MAX_TRACKED_CLIENTS &&
      !this._counterMemory.has(clientId)
    ) {
      this._counterMemory.delete(this._counterMemory.keys().next().value);
    }
    // delete-then-set moves this entry to the back of the insertion order, so
    // the map ages by last use rather than by first sighting.
    this._counterMemory.delete(clientId);
    this._counterMemory.set(clientId, { lastArmReq, lastDisarmReq });
  }

  /**
   * Raise this client's session watermark. Never lowers, and outlives eviction.
   *
   * Bounded and aged by last use with the same delete-then-set idiom as
   * _rememberCounters(), so a client-id flood cannot grow it.
   */
  _raiseSessionWatermark(clientId, session) {
    const current = this._sessionWatermark.get(clientId) ?? 0;
    const raised = Math.max(current, session);
    if (
      this._sessionWatermark.size >= MAX_TRACKED_CLIENTS &&
      !this._sessionWatermark.has(clientId)
    ) {
      this._sessionWatermark.delete(this._sessionWatermark.keys().next().value);
    }
    this._sessionWatermark.delete(clientId);
    this._sessionWatermark.set(clientId, raised);
  }

  /** The disarm total this station had already sent us, or 0 if unknown. */
  _rememberedDisarmReq(clientId) {
    const remembered = this._counterMemory.get(clientId);
    return remembered && typeof remembered.lastDisarmReq === 'number'
      ? remembered.lastDisarmReq
      : 0;
  }

  /** The arm total this station had already sent us, or null if unknown. */
  _rememberedArmReq(clientId) {
    const remembered = this._counterMemory.get(clientId);
    return remembered && typeof remembered.lastArmReq === 'number'
      ? remembered.lastArmReq
      : null;
  }

  /**
   * Re-evaluate both units against `nowMs`, and on a falling edge ERASE the
   * holder's command for the unit that just went away -- rather than merely
   * masking it in state(). A masked command would become active again on the
   * first telemetry packet after the unit returns, with no fresh operator
   * action behind it.
   *
   * Called from BOTH tick() and onIntent(), because both advance the liveness
   * verdict. Doing it in tick() alone left a hole: an intent that observed the
   * unit's death and then bailed out at the ordering gate consumed the edge,
   * so the tick that followed saw was-live already false and never erased.
   */
  _refreshUnitLiveness(nowMs) {
    const rxWasLive = this._rxLive;
    const hhWasLive = this._hhLive;
    this._rxLive = this.rxLive(nowMs);
    this._hhLive = this.hhLive(nowMs);

    // Quarantine the machine on the falling edge, BEFORE the holder check
    // below. The absence is a property of the machine, not of whoever happens
    // to be armed at that instant -- a unit that drops while nobody holds the
    // token still needs a deliberate command after it returns, and a holder
    // that arrives later must not inherit a clean slate it did not earn.
    if (rxWasLive && !this._rxLive) this._driveNeedsRelease = true;
    if (hhWasLive && !this._hhLive) this._thrusterNeedsRelease = true;


    const holderRec = this.holder ? this._clients.get(this.holder) : null;
    if (!holderRec) return;
    if (rxWasLive && !this._rxLive) {
      holderRec.port = 'neutral';
      holderRec.stbd = 'neutral';
    }
    if (hhWasLive && !this._hhLive) {
      holderRec.thruster = 'off';
      holderRec.thrusterMode = REST_MODE_WHILE_ARMED;
      holderRec.trimDeg = 0;
    }
  }

  /**
   * May this machine be commanded to move right now?
   *
   * Liveness alone is not enough -- see [_driveNeedsRelease]. Both are checked
   * everywhere a stored or published command is derived, so there is one answer
   * to "can this move" rather than one per call site.
   */
  _driveCommandable() {
    return this._rxLive && !this._driveNeedsRelease;
  }

  _thrusterCommandable() {
    return this._hhLive && !this._thrusterNeedsRelease;
  }

  /**
   * Lift a quarantine once the operator is seen asking for the safe value.
   *
   * Called with the RAW intent, after the masked values have been stored, so
   * the release takes effect from the NEXT intent onwards: a safe baseline
   * first, then a genuine new command. Clearing it on the same intent would
   * let a held button through on the very packet that was supposed to prove
   * the operator had let go.
   *
   * Only the holder can lift it. With nobody armed nothing is published, so a
   * quarantine is also lifted there rather than left to outlive the session
   * that caused it.
   */
  _maybeRelease(clientId, { port, stbd, thruster, trimDeg }) {
    if (this.holder !== null && this.holder !== clientId) return;
    // Only a safe intent seen while the unit is LIVE counts. A release that
    // arrives during the outage is not the post-recovery edge this gate exists
    // to require -- it happens before there is anything to recover from, and
    // consuming it there satisfies the requirement in advance. The operator
    // could then press again while the unit was still absent (masked, so it
    // looks inert) and the first heartbeat after the unit returned would
    // republish it: motion restarting off a press made during a blackout.
    if (this._rxLive && port === 'neutral' && stbd === 'neutral') {
      this._driveNeedsRelease = false;
    }
    // The thruster's safe tuple is BOTH `off` and no trim, because in HOLD
    // `thruster` is always 'off' -- HOLD commands through the trim, not through
    // a direction. Checking direction alone would let every HOLD heartbeat lift
    // the quarantine while a non-zero trim was still being asked for, and the
    // next heartbeat would then restart automatic thrust off an offset the
    // operator dialled in before the outage. The erase path already treats trim
    // as part of the tuple (it resets trimDeg to 0), so the release must too.
    //
    // The operator has two ways out: trim back to zero, or leave HOLD -- which
    // zeroes the trim in the app anyway (setThrusterMode).
    if (this._hhLive && thruster === 'off' && trimDeg === 0) {
      this._thrusterNeedsRelease = false;
    }
  }

  /** Immediately discard all authority and return to the published safe state. */
  resetToSafe() {
    const before = this._stateKey();
    this.holder = null;
    return this._stateKey() !== before;
  }

  // Compact comparable signature of the published state, for change
  // detection. (Intentionally excludes per-client bookkeeping that doesn't
  // reach the wire.)
  //
  // JSON.stringify, not join(): activeClient is a client-supplied string, so a
  // separator-joined key could be made to collide -- an id containing the
  // separator can spell out a different state's key and make a real change
  // compare equal, suppressing the immediate publish. Only the ~250 ms
  // heartbeat republish would cover it, so the cost is a delay rather than a
  // lost command, but a change-detector that a caller can talk out of firing is
  // not worth keeping for one character of brevity.
  _stateKey() {
    const s = this.state();
    return JSON.stringify([
      s.enabled,
      s.port,
      s.stbd,
      s.thruster,
      s.thrusterMode,
      s.trimDeg,
      s.activeClient,
      s.rxLive,
      s.hhLive,
    ]);
  }
}

module.exports = { ArmArbiter, MAX_TRIM_DEG, MAX_TRACKED_CLIENTS };
