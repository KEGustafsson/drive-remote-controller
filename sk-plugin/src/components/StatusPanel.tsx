import {
  SK_HH_MODE_PATH,
  SK_HH_SOURCE_PATH,
  SK_HH_THRUSTER_STATE_PATH,
  SK_RX_LINK_UP_PATH,
  SK_RX_MASTER_ENABLE_PATH,
  SK_RX_PORT_SOURCE_PATH,
  SK_RX_PORT_STATE_PATH,
  SK_RX_STBD_SOURCE_PATH,
  SK_RX_STBD_STATE_PATH,
} from '../config';
import { parseDisplayPosition } from '../pure/driveCommand';
import { commandsIndication, type IntentStatus } from '../pure/intentStatus';
import type { RxLiveness } from '../pure/rxLiveness';
import { sourceLabel } from '../pure/sources';
import type { WriteStatus } from '../pure/writeAccess';
import type { ConnectionState, SkSnapshot } from '../skClient';

interface StatusPanelProps {
  connectionState: ConnectionState;
  writeStatus: WriteStatus;
  /**
   * How the plugin answered this app's last intent POST -- the real effect,
   * which outranks `writeStatus` (a pre-send proxy) whenever it is known. See
   * pure/intentStatus.ts.
   */
  intentStatus?: IntentStatus;
  /**
   * Who holds the arm token, per the arbiter: 'you' = this device, 'other' =
   * a different device, 'none' = nobody armed. Authoritative, never a local
   * guess.
   */
  controlState: 'you' | 'other' | 'none';
  /** Is RX telemetry still arriving? Judged on arrival, never on a value. */
  rxLiveness: RxLiveness;
  /** The same for the heading-hold (bow thruster) unit. */
  hhLiveness: RxLiveness;
  values: SkSnapshot['values'];
}

// 'stale' is not a health verdict -- it means "this reading is not live".
// Everything below the connection light is derived from the last Signal K
// snapshot (skClient never clears `values` on disconnect), so while the socket
// is down, or the unit that publishes it has gone quiet, we deliberately do
// NOT paint it green/amber/red. Greying it says "was, not is" instead of
// presenting last-known data with the confidence of a live reading -- the bug
// that once showed an all-green panel for an unpowered board.
type LampStatus = 'good' | 'warn' | 'bad' | 'stale';

/**
 * One traffic light: a coloured lamp, a small fixed title, and the current
 * reading underneath.
 *
 * Why this replaced the old label/value rows: the panel had grown to nine
 * full-width rows of prose, which on a phone pushed the drive controls off
 * screen and made the one row that mattered ("is the board answering?") no
 * more prominent than the rest. A lamp is readable at a glance and in
 * peripheral vision while the operator is looking at the boat, not the screen
 * -- and the colour carries the meaning even when the text is too small to
 * read from arm's length. The title stays visible so a lamp is never a colour
 * with no stated subject.
 */
function Lamp({
  title,
  value,
  status,
}: {
  title: string;
  value: string;
  status: LampStatus;
}) {
  return (
    <div
      className={`lamp lamp--${status}`}
      role="status"
      aria-label={`${title}: ${value}`}
    >
      <span className="lamp__light" aria-hidden="true" />
      <span className="lamp__title">{title}</span>
      <span className="lamp__value">{value}</span>
    </div>
  );
}

function commandsLamp(
  writeStatus: WriteStatus,
  intentStatus: IntentStatus,
): {
  value: string;
  status: LampStatus;
} {
  return commandsIndication(writeStatus, intentStatus);
}

// A unit's own presence, worded so "check power" appears only when that is
// actually the likely cause.
function unitLamp(
  liveness: RxLiveness,
  live: boolean,
): { value: string; status: LampStatus } {
  if (!live) return { value: 'unknown', status: 'stale' };
  switch (liveness) {
    case 'live':
      return { value: 'responding', status: 'good' };
    case 'never-seen':
      return { value: 'NOT SEEN — check power', status: 'bad' };
    default:
      return { value: 'NOT RESPONDING', status: 'bad' };
  }
}

/**
 * The critical statistics at a glance: can this app reach the boat, is each
 * unit answering, and -- separately, because these can disagree -- which
 * station is actually authoritative for each output right now. That last one
 * matters specifically because TX outranks this app by fixed precedence: if TX
 * is also live and armed, pressing a button here may visibly do nothing, and
 * the operator needs to see why ("TX remote") rather than wonder whether the
 * app is broken.
 *
 * **The detail is collapsed on load**, so the space goes to the drive buttons
 * instead -- the panel had grown to ten lamps, which on a phone is most of the
 * screen. Link, Commands and Control stay above the toggle; only the per-lamp
 * readings sit behind it.
 *
 * Collapsing may not hide a fault, and here it does not: **the kill switch
 * already names a unit that has stopped answering** ("tap to arm -- drive unit
 * not responding"), it is the largest thing on screen, and it is never
 * collapsed. A second copy of that sentence in this panel was written and then
 * removed -- two tests caught it as a duplicate, which is what it was. The
 * Android station carries one because its kill switch does NOT name the unit
 * in the can-still-arm case; this one would be pure noise.
 */
export function StatusPanel({
  connectionState,
  writeStatus,
  intentStatus = 'unknown',
  controlState,
  rxLiveness,
  hhLiveness,
  values,
}: StatusPanelProps) {
  const live = connectionState === 'open';
  const rxAlive = rxLiveness === 'live';
  const hhAlive = hhLiveness === 'live';

  const rxLinkUp = values[SK_RX_LINK_UP_PATH] === true;
  const rxMasterEnable = values[SK_RX_MASTER_ENABLE_PATH] === true;
  const portState = parseDisplayPosition(values[SK_RX_PORT_STATE_PATH]);
  const stbdState = parseDisplayPosition(values[SK_RX_STBD_STATE_PATH]);
  const portSource = values[SK_RX_PORT_SOURCE_PATH];
  const stbdSource = values[SK_RX_STBD_SOURCE_PATH];
  const thrusterState = values[SK_HH_THRUSTER_STATE_PATH];
  const thrusterMode = values[SK_HH_MODE_PATH];
  const thrusterSource = values[SK_HH_SOURCE_PATH];

  const commands = commandsLamp(writeStatus, intentStatus);
  const rxUnit = unitLamp(rxLiveness, live);
  const hhUnit = unitLamp(hhLiveness, live);

  // Collapse to 'stale' when the reading is not currently backed by a live
  // stream from the unit that publishes it.
  const derived = (status: LampStatus): LampStatus => (live ? status : 'stale');
  const fromRx = (status: LampStatus): LampStatus =>
    live && rxAlive ? status : 'stale';
  const fromHh = (status: LampStatus): LampStatus =>
    live && hhAlive ? status : 'stale';

  return (
    <div className="status-panel" aria-label="Connection and unit status">
      <div className="status-panel__summary">
        <Lamp
          title="Link"
          value={
            connectionState === 'open'
              ? 'connected'
              : connectionState === 'connecting'
                ? 'connecting…'
                : 'disconnected'
          }
          status={connectionState === 'open' ? 'good' : 'bad'}
        />
        <Lamp
          title="Commands"
          value={commands.value}
          status={derived(commands.status)}
        />
        <Lamp
          title="Control"
          value={
            controlState === 'you'
              ? 'yours'
              : controlState === 'other'
                ? 'another device'
                : 'none armed'
          }
          status={derived(controlState === 'other' ? 'warn' : 'good')}
        />
      </div>

      {/*
        <details>, not a useState toggle: it collapses by default, is
        keyboard-operable and screen-reader-announced with no code of ours,
        and the lamps stay mounted so nothing has to re-derive when it opens.
      */}
      <details className="status-panel__details">
        <summary className="status-panel__toggle">Detail</summary>
        <div className="status-panel__lamps">
          {/*
            The two unit lamps are the precondition for everything after them:
            without one, a powered-down board would pass as healthy. They come
            first for that reason.
          */}
          <Lamp title="Drive unit" value={rxUnit.value} status={rxUnit.status} />
          <Lamp
            title="Thruster unit"
            value={hhUnit.value}
            status={hhUnit.status}
          />
          <Lamp
            title="RX link"
            value={!live || !rxAlive ? 'no data' : rxLinkUp ? 'up' : 'no remote'}
            status={fromRx(rxLinkUp ? 'good' : 'warn')}
          />
          <Lamp
            title="RX enable"
            value={
              !live || !rxAlive ? 'no data' : rxMasterEnable ? 'ON' : 'OFF'
            }
            status={fromRx(rxMasterEnable ? 'good' : 'warn')}
          />
          <Lamp
            title="Port"
            value={`${portState} · ${sourceLabel(portSource)}`}
            status={fromRx(portState === 'unknown' ? 'warn' : 'good')}
          />
          <Lamp
            title="Starboard"
            value={`${stbdState} · ${sourceLabel(stbdSource)}`}
            status={fromRx(stbdState === 'unknown' ? 'warn' : 'good')}
          />
          <Lamp
            title="Thruster"
            value={
              !live || !hhAlive
                ? 'no data'
                : `${typeof thrusterState === 'string' ? thrusterState.toLowerCase() : 'unknown'} · ${
                    typeof thrusterMode === 'string' ? thrusterMode : 'unknown'
                  } · ${sourceLabel(thrusterSource)}`
            }
            status={fromHh('good')}
          />
        </div>
      </details>

      {live && controlState === 'other' && (
        <p className="status-panel__note" role="alert">
          Another device holds the arm — disarm here to take over.
        </p>
      )}
    </div>
  );
}
