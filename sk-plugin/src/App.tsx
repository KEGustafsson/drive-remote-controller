import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { DriveControl } from './components/DriveControl';
import { KillSwitch } from './components/KillSwitch';
import { StatusPanel } from './components/StatusPanel';
import { ThrusterControl } from './components/ThrusterControl';
import {
  PERIODIC_REFRESH_MS,
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
import { isPlausibleHeading, trimBy } from './pure/trimOffset';
import { useHhLiveness, useRxLiveness } from './hooks/useRxLiveness';
import { useSkConnection } from './hooks/useSkConnection';
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
  // engage. 0 = no trim. Reset to 0 whenever we cannot command the thruster
  // (below), so arming always starts at "hold current heading" and never swings
  // the boat to a pre-dialed number -- trim is an after-arming action.
  const [trimOffset, setTrimOffset] = useState(0);

  // Who the arbiter says holds the token right now.
  const activeClient = values[SK_PLUGIN_ACTIVE_CLIENT_PATH];
  const controlState: ControlState =
    activeClient === clientId
      ? 'you'
      : typeof activeClient === 'string' && activeClient.length > 0
        ? 'other'
        : 'none';

  // Is the socket open right now? `values` (hence `activeClient`/controlState)
  // is last-known while it isn't -- the SK client never clears it on
  // disconnect. IMPORTANT: the socket is only the READ side. The intent
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
  const connected = connectionState === 'open';
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
  const heldRaw = values[SK_HH_SETPOINT_PATH];
  const heldDeg = isPlausibleHeading(heldRaw) ? heldRaw : null;

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
  const sendIntent = useCallback(() => {
    seqRef.current += 1;
    const intent: ClientIntent = { seq: seqRef.current, ...bodyRef.current };
    postIntent(intent).then(
      () => reportIntentStatus('ok'),
      (err: unknown) => reportIntentStatus(classifyIntentFailure(err)),
    );
  }, [postIntent, reportIntentStatus]);

  // Send the instant anything changes -- no polling delay (SAFETY.md drive
  // invariant 2: react as fast as the loop runs).
  useEffect(() => {
    sendIntent();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intentBody, sendIntent]);

  // Plus a periodic heartbeat regardless of change, matching the firmware's
  // cadence -- keeps the arbiter's per-client liveness fresh during a long
  // steady press. Stable interval (fed via refs) so rapid taps don't tear it
  // down and rebuild it.
  useEffect(() => {
    const id = setInterval(sendIntent, PERIODIC_REFRESH_MS);
    return () => clearInterval(id);
  }, [sendIntent]);

  // Arm-first, then trim: force the trim back to 0 whenever the thruster is not
  // commandable (disarmed, offline, or HH not live). Arming therefore always
  // begins at "hold the captured heading" and never swings the boat to an
  // offset dialed in earlier -- trimming is a deliberate action taken after
  // arming, matching how the operator chose this to behave.
  useEffect(() => {
    if (!thrusterCommandable) setTrimOffset(0);
  }, [thrusterCommandable]);

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
        overriddenBy={thrusterOverride}
        reversalPending={values[SK_HH_REVERSAL_PENDING_PATH] === true}
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
