import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { DriveControl } from './components/DriveControl';
import { KillSwitch } from './components/KillSwitch';
import { StatusPanel } from './components/StatusPanel';
import { ThrusterControl } from './components/ThrusterControl';
import {
  PERIODIC_REFRESH_MS,
  SK_HH_ARMED_PATH,
  SK_HH_FSM_STATE_PATH,
  SK_HH_MODE_PATH,
  SK_HH_REVERSAL_PENDING_PATH,
  SK_HH_SETPOINT_PATH,
  SK_HH_SOURCE_PATH,
  SK_PLUGIN_ACTIVE_CLIENT_PATH,
  SK_RX_PORT_SOURCE_PATH,
  SK_RX_STBD_SOURCE_PATH,
} from './config';
import {
  makeClientId,
  postIntent as defaultPostIntent,
  type ClientIntent,
  type PostIntent,
  type ThrusterDirection,
  type ThrusterMode,
} from './clientIntent';
import { classifyIntentFailure, type IntentStatus } from './pure/intentStatus';
import { holdEngagedFrom } from './pure/holdPhase';
import { isPlausibleHeading, trimBy } from './pure/trimOffset';
import { useHoldPhase, useManualRefusal } from './hooks/useHoldPhase';
import {
  useHhLiveness,
  useRxLiveness,
  useServerStreamLive,
} from './hooks/useRxLiveness';
import { useSkConnection } from './hooks/useSkConnection';
import { useTrimReset } from './hooks/useTrimReset';
import { useWriteAccess } from './hooks/useWriteAccess';
import type { DrivePosition } from './pure/driveCommand';
import { rxReadyToArm } from './pure/rxLiveness';
import { overridesThisApp, sourceLabel } from './pure/sources';

// Which of the three arm states this instance is in, derived ENTIRELY from
// the arbiter's published `activeClient` -- never from a local guess. This
// is the crux of the oscillation fix: exactly one instance can be 'you' at a
// time, and every instance agrees on who that is because they all read the
// same authoritative value.
type ControlState = 'you' | 'other' | 'none';

// Corner-bracket icons: outward = enter full screen, inward = exit.
function EnterFullscreenIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M8 3H5a2 2 0 0 0-2 2v3" />
      <path d="M21 8V5a2 2 0 0 0-2-2h-3" />
      <path d="M3 16v3a2 2 0 0 0 2 2h3" />
      <path d="M16 21h3a2 2 0 0 0 2-2v-3" />
    </svg>
  );
}
function ExitFullscreenIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M8 3v3a2 2 0 0 1-2 2H3" />
      <path d="M21 8h-3a2 2 0 0 1-2-2V3" />
      <path d="M3 16h3a2 2 0 0 1 2 2v3" />
      <path d="M16 21v-3a2 2 0 0 1 2-2h3" />
    </svg>
  );
}

interface AppProps {
  // Injectable only so tests can drive the write-access check
  // deterministically without a network (production uses the hook's own
  // same-origin /skServer/loginStatus fetch). Mirrors how the Sk client is
  // injected via context for tests.
  fetchLoginStatus?: () => Promise<unknown>;
  // Injectable intent transport so tests can drive a real in-process arbiter
  // without an HTTP server (production POSTs to the plugin's route).
  postIntent?: PostIntent;
}

export function App({
  fetchLoginStatus,
  postIntent = defaultPostIntent,
}: AppProps = {}) {
  const connection = useSkConnection();
  const { connectionState, values } = connection;
  const writeStatus = useWriteAccess(connectionState, fetchLoginStatus);
  // Is RX still publishing? Judged on telemetry ARRIVAL, never on the value
  // of rx.linkUp -- a switched-off RX leaves that value standing at `true`
  // forever (see pure/rxLiveness.ts).
  const rxLiveness = useRxLiveness(connection);
  // The same for the heading-hold unit -- independently, because the two
  // boards can be powered, fail or leave WiFi range independently.
  const hhLiveness = useHhLiveness(connection);
  // Is the arbiter's own 250 ms republish still ARRIVING on this socket? The
  // socket reading 'open' cannot say: a half-open socket (no FIN) reads open
  // forever, and the activeClient VALUE is retained across silence and across
  // a reconnect alike. See pure/serverStream.ts.
  const serverLive = useServerStreamLive(connection);

  // Stable per-tab identity. Generated once and never regenerated (not even
  // across a WebSocket reconnect, since this component stays mounted) so the
  // arbiter keeps recognising this instance as the same holder.
  const [clientId] = useState(makeClientId);

  // Arm/disarm are EDGE requests: we bump a counter on each tap and the
  // arbiter acts on the rising edge. We never publish a raw "enabled"
  // boolean -- that is the arbiter's to decide and publish. Starting both at
  // 0 means "no request yet", so simply appearing never arms anything.
  const [armReq, setArmReq] = useState(0);
  const [disarmReq, setDisarmReq] = useState(0);
  const [portPosition, setPortPosition] = useState<DrivePosition>('neutral');
  const [stbdPosition, setStbdPosition] = useState<DrivePosition>('neutral');
  // Bow thruster. Defaults to MANUAL: direct thrust is the primary use of this
  // control (docking manoeuvres), so the UI opens on it rather than making the
  // operator switch every session. Safe to default here because a fresh page
  // load is always DISARMED and MANUAL commands nothing until a contact is
  // pressed, so the initial mode is never "one arm from firing" on its own.
  // (The firmware/arbiter still degrade any UNRECOGNISED mode value to 'hold'
  // -- that safe-default-for-garbage is unchanged; this only sets which mode
  // the UI presents on open.)
  //
  // The operator MAY change this while disarmed -- the mode chooser is not
  // gated on arming (ThrusterControl's `armed` doc). That is what makes the
  // selection useful: it decides which gate the NEXT arm opens. It also means
  // arming with HOLD selected engages the hold with no further press, which is
  // the operator's deliberate choice and is stated on the widget while
  // disarmed. Nothing reaches the thruster until then: the arbiter publishes a
  // non-holder's mode nowhere, and HH's own gates (enable, the shared
  // last-thrust history behind both reversal dwells) are unchanged.
  const [thrusterMode, setThrusterMode] = useState<ThrusterMode>('manual');
  const [thrusterDir, setThrusterDir] = useState<ThrusterDirection>('off');
  // Heading-hold TRIM: a RELATIVE offset (deg) from the heading HH captures on
  // engage. 0 = no trim. Reset to 0 when the hold ends (below), so arming always
  // starts at "hold current heading" and never swings the boat to a pre-dialed
  // number -- trim is an after-arming action.
  const [trimOffset, setTrimOffset] = useState(0);

  // Who the arbiter says holds the token right now.
  const activeClient = values[SK_PLUGIN_ACTIVE_CLIENT_PATH];
  const controlState: ControlState =
    activeClient === clientId
      ? 'you'
      : typeof activeClient === 'string' && activeClient.length > 0
        ? 'other'
        : 'none';

  // Is the server stream live right now -- socket open AND the arbiter's
  // publish still arriving on it? `values` (hence `activeClient`/controlState)
  // is last-known while it isn't -- the SK client never clears it on
  // disconnect, and a silent socket delivers nothing to clear. IMPORTANT: the socket is only the READ side. The intent
  // heartbeat below POSTs over HTTP and keeps running regardless of the
  // socket's state, so if only the WebSocket is down the arbiter may still be
  // seeing our heartbeats -- we can still be armed and commanding without
  // being able to see it. That is deliberate (it is what lets the offline
  // STOP tap work, and it mirrors TX publishing its true switch state
  // regardless of its LED), but it means offline must never be presented as
  // "safely disarmed": the KillSwitch renders a distinct OFFLINE state (not
  // DISARMED) whose tap always disarms via the HTTP path. The command widgets
  // grey out and go inert while offline -- honest, since we cannot confirm a
  // command is landing -- but they are NOT the stop; the always-live kill
  // switch is, so greying them takes nothing away from the operator's ability
  // to stop the machinery.
  const connected = connectionState === 'open' && serverLive;
  const armed = controlState === 'you';
  // Arming is offered only while RX is proven alive. The server enforces this
  // too (arbiter.cjs) and is the real gate -- this is so the button never
  // invites a press the server would silently refuse, and so the operator is
  // told WHY. Disarm is deliberately not gated: stopping must always work.
  // One ARM covers both machines and is offered when EITHER unit is reachable
  // -- see KillSwitch's note on why an absent thruster board must not take
  // away gear control.
  const canArm = rxReadyToArm(rxLiveness) || rxReadyToArm(hhLiveness);
  // Per-machine commandability: "actually able to command THIS machine right
  // now" -- holds the token, the link is up, AND that machine's own unit is
  // answering at the far end. The two are independent because the two boards
  // are (RX drives the gears, HH drives the thruster), so a dead thruster board
  // must not grey out the gears and vice versa. A widget that is not commandable
  // is greyed and made inert (DriveControl/ThrusterControl `disabled`) rather
  // than left looking live: a control that cannot move its machine must not
  // invite a press. The kill switch is unaffected -- it is the always-live stop.
  const driveCommandable = armed && connected && rxReadyToArm(rxLiveness);
  const thrusterCommandable = armed && connected && rxReadyToArm(hhLiveness);

  // The heading the unit reports it is actually holding -- shown for reference
  // while trimming (the trim is relative to whatever HH captured). Read for
  // DISPLAY only; the trim offset itself is self-contained and needs no seed.
  //
  // Only while HH is answering: a unit that has gone quiet leaves its last
  // setpoint standing forever, and the panel labels the number "current
  // heading" whenever the thruster is not commandable -- a frozen heading
  // presented as live (SAFETY.md invariant 6). The same for reversalPending.
  const hhAnswering = connected && rxReadyToArm(hhLiveness);
  const heldRaw = values[SK_HH_SETPOINT_PATH];
  const heldDeg = hhAnswering && isPlausibleHeading(heldRaw) ? heldRaw : null;

  // Is HH ACTUALLY holding, by its own report? Never a local guess: HH mirrors
  // hh.setpointDeg to the fused heading whenever it is not holding
  // (ARCHITECTURE.md §9), so the number alone reads the same either way and
  // may not be labelled "holding" unconditionally. hh.armed + hh.mode narrows
  // it, HH's own FSM state settles it (ENABLE is asserted in ARMED_IDLE too),
  // and the liveness check is what stops a switched-off HH from reporting a hold
  // it can no longer be running -- every one of those inputs is a VALUE, and
  // Signal K retains those forever. The whole rule is in pure/holdPhase.ts.
  const holdEngaged = holdEngagedFrom(
    values[SK_HH_ARMED_PATH],
    values[SK_HH_MODE_PATH],
    values[SK_HH_FSM_STATE_PATH],
    rxReadyToArm(hhLiveness),
  );

  // The intent payload minus `seq` (which is stamped at send time so every
  // heartbeat is a distinct message).
  const intentBody = useMemo(
    () => ({
      clientId,
      armReq,
      disarmReq,
      port: portPosition,
      stbd: stbdPosition,
      thruster: thrusterDir,
      thrusterMode,
      trimDeg: trimOffset,
    }),
    [
      clientId,
      armReq,
      disarmReq,
      portPosition,
      stbdPosition,
      thrusterDir,
      thrusterMode,
      trimOffset,
    ],
  );

  // One place that actually sends: bumps seq, wraps the body, POSTs this
  // tab's intent to the plugin. Reads the latest body from a ref so the
  // stable heartbeat interval never needs rebuilding.
  //
  // The outcome is NOT swallowed. It is the one direct measurement this app
  // has of whether its commands reach the plugin -- a 401/403 (not allowed),
  // a 503 (plugin not running) or a network failure each mean a STOP tap
  // would do nothing -- so it drives the Commands lamp, ahead of the
  // /skServer/loginStatus proxy (pure/intentStatus.ts). Fail-safety does not
  // depend on this: if our intents stop reaching the plugin it stale-evicts
  // us and clears activeClient, so the UI falls back to disarmed regardless.
  // This is about the operator being TOLD, not about the machinery.
  const [intentStatus, setIntentStatus] = useState<IntentStatus>('unknown');
  // Mirrors the state so a heartbeat that confirms what is already shown
  // (four times a second, for the life of the tab) schedules nothing.
  const intentStatusRef = useRef<IntentStatus>('unknown');
  const reportIntentStatus = useCallback((next: IntentStatus) => {
    if (intentStatusRef.current === next) return;
    intentStatusRef.current = next;
    setIntentStatus(next);
  }, []);
  const seqRef = useRef(0);
  const bodyRef = useRef(intentBody);
  bodyRef.current = intentBody;
  // EVERY POST still waiting for an answer. Tracked so the heartbeat can
  // COALESCE -- see sendHeartbeat below. A set rather than the latest promise:
  // an operator action sends regardless of what is in flight, and if that
  // newer request settles while an older one is still hanging, remembering
  // only the newest would read as "nothing pending" and let the heartbeat
  // rebuild exactly the backlog this exists to prevent.
  const inFlightRef = useRef(new Set<Promise<void>>());
  const sendIntent = useCallback(() => {
    seqRef.current += 1;
    const intent: ClientIntent = { seq: seqRef.current, ...bodyRef.current };
    const settled = postIntent(intent).then(
      () => reportIntentStatus('ok'),
      (err: unknown) => reportIntentStatus(classifyIntentFailure(err)),
    );
    inFlightRef.current.add(settled);
    void settled.finally(() => {
      inFlightRef.current.delete(settled);
    });
  }, [postIntent, reportIntentStatus]);

  // Send the instant anything changes -- no polling delay (SAFETY.md drive
  // invariant 2: react as fast as the loop runs). Deliberately NOT coalesced:
  // a change is an operator action (a contact pressed, STOP tapped) and must go
  // out now, whatever is still in flight.
  useEffect(() => {
    sendIntent();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intentBody, sendIntent]);

  // Plus a periodic heartbeat regardless of change, matching the firmware's
  // cadence -- keeps the arbiter's per-client liveness fresh during a long
  // steady press. Stable interval (fed via refs) so rapid taps don't tear it
  // down and rebuild it.
  //
  // NO HEARTBEAT WHILE ANYTHING IS IN FLIGHT. The tick used to fire a fetch every
  // 250 ms regardless, so a server answering slowly (or not at all -- the
  // 2000 ms abort is the only backstop) stacked up to eight requests, past the
  // browser's per-host connection limit. An urgent STOP then queued BEHIND
  // heartbeats whose content was already obsolete, which is the one thing this
  // transport must never do. Skipping a tick costs nothing: the skipped
  // heartbeat carried the same body as the one already on the wire, and the
  // arbiter reads liveness from arrivals, so it either hears the in-flight
  // request or evicts us and fails safe -- exactly what it should do while the
  // link is that sick.
  const sendHeartbeat = useCallback(() => {
    if (inFlightRef.current.size !== 0) return;
    sendIntent();
  }, [sendIntent]);
  useEffect(() => {
    const id = setInterval(sendHeartbeat, PERIODIC_REFRESH_MS);
    return () => clearInterval(id);
  }, [sendHeartbeat]);

  // The trim goes back to 0 when the HOLD ENDS -- and not when this station
  // merely stops being able to SEE it. Leaving HOLD, being disarmed, or HH
  // going silent on a live stream all reset it, so arming always begins at
  // "hold the captured heading" and never swings the boat to an offset dialed
  // in earlier. But the read side dropping (socket down, or the stream silent)
  // does NOT: intents still flow over HTTP, the hold is still ours and still
  // running, and zeroing a relative trim would swing the boat back by up to
  // MAX_TRIM_DEG with nobody touching anything. While blind the value is kept
  // and kept SENT; the trim buttons are inert meanwhile (thrusterCommandable is
  // false). HH truly dying while we are blind is caught by the arbiter, which
  // zeroes and quarantines the trim server-side. The full rule, including the
  // settling window on reconnect, is pure/trimReset.ts.
  const trimReset = useTrimReset(
    thrusterMode,
    connected,
    armed,
    rxReadyToArm(hhLiveness),
  );
  useEffect(() => {
    if (trimReset && trimOffset !== 0) setTrimOffset(0);
  }, [trimReset, trimOffset]);

  const handlePortChange = useCallback((p: DrivePosition) => {
    setPortPosition(p);
  }, []);
  const handleStbdChange = useCallback((p: DrivePosition) => {
    setStbdPosition(p);
  }, []);
  const handleThrusterDirection = useCallback((d: ThrusterDirection) => {
    setThrusterDir(d);
  }, []);
  const handleThrusterMode = useCallback((m: ThrusterMode) => {
    setThrusterMode(m);
    // Leaving HOLD zeroes the trim, so re-entering HOLD starts from 0 (hold the
    // freshly captured heading) rather than resurrecting an offset.
    if (m !== 'hold') setTrimOffset(0);
  }, []);
  const handleTrim = useCallback((stepDeg: number) => {
    // Relative and self-contained: no seed, just accumulate and clamp.
    setTrimOffset((t) => trimBy(t, stepDeg));
  }, []);

  // Arm only when nobody holds the token; disarm is always available and
  // acts as a global stop (universal disarm -- see arbiter.cjs). Taking
  // control from another device is therefore a deliberate two-tap action:
  // disarm (stops them) then arm (takes it), passing through the safe
  // disarmed state each time.
  const requestArm = useCallback(() => setArmReq((n) => n + 1), []);
  const requestDisarm = useCallback(() => setDisarmReq((n) => n + 1), []);

  // Full-screen toggle for the header button. Tracked via the fullscreenchange
  // event (not the click) so the icon stays correct even if the OS/browser
  // leaves full screen by its own means (Esc, gesture). Guarded so it is inert
  // where the Fullscreen API is unavailable rather than throwing.
  const [isFullscreen, setIsFullscreen] = useState(false);
  useEffect(() => {
    const onChange = () => setIsFullscreen(document.fullscreenElement != null);
    document.addEventListener('fullscreenchange', onChange);
    onChange();
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);
  const toggleFullscreen = useCallback(() => {
    if (document.fullscreenElement) {
      void document.exitFullscreen?.().catch(() => {});
    } else {
      void document.documentElement.requestFullscreen?.().catch(() => {});
    }
  }, []);

  // RX telemetry-derived context: which source RX says owns each drive.
  const portSource = values[SK_RX_PORT_SOURCE_PATH];
  const stbdSource = values[SK_RX_STBD_SOURCE_PATH];
  const thrusterSource = values[SK_HH_SOURCE_PATH];

  // Only show "controlled by X" on a drive when we're actually the armed
  // controller AND a higher-precedence source (local switch or TX) owns it
  // -- the case where a press here visibly does nothing and the operator
  // needs to know why. When we're not the controller, the disarmed/foreign
  // note already explains it.
  const portOverride =
    driveCommandable && overridesThisApp(portSource)
      ? sourceLabel(portSource)
      : undefined;
  const stbdOverride =
    driveCommandable && overridesThisApp(stbdSource)
      ? sourceLabel(stbdSource)
      : undefined;
  const thrusterOverride =
    thrusterCommandable && overridesThisApp(thrusterSource)
      ? sourceLabel(thrusterSource)
      : undefined;

  // What has become of the hold this station is asking for. In HOLD the arm IS
  // the request, so "asking" is HOLD selected while the thruster is
  // commandable. The phase is what keeps the first moment after every arm from
  // being drawn as a fault, and what makes a hold the unit is refusing look
  // like one instead of like more waiting.
  const hold = useHoldPhase(
    thrusterCommandable && thrusterMode === 'hold',
    holdEngaged,
    values[SK_HH_FSM_STATE_PATH],
    thrusterOverride !== undefined,
  );
  // And in MANUAL, where the arm is the whole request: is HH refusing it? A
  // release of HH's own ENGAGE latches every armed remote out until it
  // re-arms, and HH then reports DISARMED while we still hold the token.
  const manualStall = useManualRefusal(
    thrusterCommandable && thrusterMode === 'manual',
    values[SK_HH_FSM_STATE_PATH],
    thrusterOverride !== undefined,
  );

  return (
    <div className="app">
      <header className="app__header">
        <h1>Drive Remote Control</h1>
        <button
          type="button"
          className="app__fullscreen"
          onClick={toggleFullscreen}
          aria-pressed={isFullscreen}
          aria-label={isFullscreen ? 'Exit full screen' : 'Full screen'}
          title={isFullscreen ? 'Exit full screen' : 'Full screen'}
        >
          {isFullscreen ? <ExitFullscreenIcon /> : <EnterFullscreenIcon />}
        </button>
      </header>

      <KillSwitch
        armed={armed}
        foreignControl={controlState === 'other'}
        connected={connected}
        rxLiveness={rxLiveness}
        hhLiveness={hhLiveness}
        canArm={canArm}
        onArm={requestArm}
        onDisarm={requestDisarm}
      />

      <ThrusterControl
        mode={thrusterMode}
        onModeChange={handleThrusterMode}
        onDirectionChange={handleThrusterDirection}
        trimDeg={trimOffset}
        onTrim={handleTrim}
        heldDeg={heldDeg}
        armed={thrusterCommandable}
        // Holding the token is a different question from being able to command
        // the thruster right now, and only the first is fixed by arming --
        // see the prop's doc.
        holdsControl={armed}
        // What HH itself says, not what this app asked for -- the heading is
        // only labelled "holding" when the unit reports the hold is running,
        // and a request it has not taken becomes a fault only once it has had
        // long enough to take it.
        holdPhase={hold.phase}
        holdStall={hold.stall}
        manualStall={manualStall}
        overriddenBy={thrusterOverride}
        reversalPending={
          hhAnswering && values[SK_HH_REVERSAL_PENDING_PATH] === true
        }
      />

      <main className="app__drives">
        <DriveControl
          side="port"
          label="Port"
          overriddenBy={portOverride}
          disabled={!driveCommandable}
          onPositionChange={handlePortChange}
        />
        <DriveControl
          side="stbd"
          label="Starboard"
          overriddenBy={stbdOverride}
          disabled={!driveCommandable}
          onPositionChange={handleStbdChange}
        />
      </main>

      <StatusPanel
        connectionState={connectionState}
        serverSilent={connectionState === 'open' && !serverLive}
        writeStatus={writeStatus}
        intentStatus={intentStatus}
        controlState={controlState}
        rxLiveness={rxLiveness}
        hhLiveness={hhLiveness}
        values={values}
      />
    </div>
  );
}
