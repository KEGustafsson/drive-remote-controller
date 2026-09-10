import type { RxLiveness } from '../pure/rxLiveness';

interface KillSwitchProps {
  /** This instance currently holds the arm token (arbiter: activeClient === us). */
  armed: boolean;
  /** A DIFFERENT device currently holds the token. */
  foreignControl: boolean;
  /**
   * The Signal K socket is open right now. When false, `armed`/`foreignControl`
   * are last-known (stale) values -- see below -- so we must NOT keep showing
   * a confident ARMED/IN USE.
   */
  connected: boolean;
  /** Why RX is or isn't considered alive -- used to explain a blocked ARM. */
  rxLiveness: RxLiveness;
  /** The same for the heading-hold (bow thruster) unit. */
  hhLiveness: RxLiveness;
  /**
   * Arming is permitted right now, i.e. AT LEAST ONE unit's telemetry is
   * currently arriving. Mirrors the gate the server-side arbiter applies.
   */
  canArm: boolean;
  /** Request the arm token (only meaningful when nobody holds it). */
  onArm: () => void;
  /** Release the token -- universal global stop, works from any instance. */
  onDisarm: () => void;
}

/**
 * A latching ARM/DISARM control, and -- crucially -- a universal kill.
 *
 * Three states, driven entirely by the arbiter's published `activeClient`
 * (never a local guess), which is what makes exclusive arming safe:
 *  - DISARMED (nobody holds it): a tap requests the token (arm).
 *  - ARMED (we hold it): a tap releases it (disarm).
 *  - IN USE (another device holds it): the arm path is intentionally NOT
 *    available -- only one device may command at a time -- but a tap still
 *    works as a global STOP that disarms whoever holds it. Disarm is never
 *    locked out, so the person nearest the machinery can always stop it
 *    (ARCHITECTURE.md §10). Taking over is then a deliberate second tap (arm).
 *
 * Mirrors TX's/RX's latching (not momentary) hardware enable switches, and
 * still defaults to disarmed on every page load (App.tsx) since there is no
 * physical switch position to read on load.
 *
 * OFFLINE takes precedence over all three states -- but only for what is
 * SHOWN, never for whether a tap works. `armed`/`foreignControl` are derived
 * from the arbiter's last-known `activeClient`, which the SK client never
 * clears on disconnect, so once the socket drops they'd otherwise keep
 * asserting a confident ARMED/IN USE that can no longer be verified. While
 * disconnected we render a distinct OFFLINE state instead.
 *
 * THE TAP STAYS LIVE, AND IT ALWAYS MEANS STOP. The socket that died is the
 * READ side; disarm travels a separate HTTP POST, and App's intent heartbeat
 * keeps POSTing regardless of the socket's state. That split matters: if
 * only the WebSocket is down while HTTP still reaches the server, the
 * arbiter is still receiving our heartbeats -- we may genuinely still be
 * armed and commanding, with no way to see it. Disabling this button in that
 * state would disable the one control that can stop the machinery, in
 * exactly the network conditions where an operator most wants a kill switch
 * (SAFETY.md: "Disarm is never gated on anything"). So the offline tap
 * always fires onDisarm: at worst it is a no-op that reaches nobody, at
 * best it is the stop that mattered. Arming while offline is still
 * impossible -- the only offline action is STOP.
 *
 * NO REACHABLE UNIT, NO ARM. When neither unit's telemetry is arriving, the
 * arm path is withdrawn and the reason is named. Without that condition,
 * arming would depend only on the arbiter's token, so with a board switched
 * off entirely the button would still go to a confident red ARMED and the
 * controls would go live -- an operator working the controls of a system that
 * is not powered, learning nothing until they needed it. The server refuses
 * such an arm anyway (arbiter.cjs); this makes the refusal visible instead of
 * leaving a dead button.
 *
 * ONE ARM, BOTH MACHINES -- and one unit missing does NOT block it. The drives
 * are the primary docking tool and the thruster board is the likelier of the
 * two to be switched off; refusing to arm the drives because the thruster is
 * absent would take away the more important control at the worst moment. So a
 * single reachable unit is enough to arm, and the partial state is stated on
 * the button rather than hidden -- being told "thruster unit not responding"
 * while still having gear control is strictly better than a dead button with
 * no explanation.
 */
export function KillSwitch({
  armed,
  foreignControl,
  connected,
  rxLiveness,
  hhLiveness,
  canArm,
  onArm,
  onDisarm,
}: KillSwitchProps) {
  if (!connected) {
    return (
      <button
        type="button"
        className="kill-switch kill-switch--offline"
        aria-pressed={false}
        onClick={() => onDisarm()}
      >
        <span className="kill-switch__state">OFFLINE</span>
        <span className="kill-switch__hint">
          connection lost — reconnecting… tap to STOP
        </span>
      </button>
    );
  }

  // Any tap while armed or while another device holds it => disarm (stop).
  // Only when nobody holds it does a tap arm this instance.
  const disarms = armed || foreignControl;

  // Nothing is armed and RX isn't answering: block the arm and say why.
  // Checked AFTER `disarms` so it can never suppress a stop -- if something
  // is somehow still armed, the tap must remain a working kill regardless of
  // what RX's link is doing.
  if (!disarms && !canArm) {
    return (
      <button
        type="button"
        className="kill-switch kill-switch--blocked"
        aria-pressed={false}
        disabled
      >
        <span className="kill-switch__state">DISARMED</span>
        <span className="kill-switch__hint">
          {rxLiveness === 'never-seen' && hhLiveness === 'never-seen'
            ? 'no units seen — check they are powered on'
            : 'no unit responding — cannot arm'}
        </span>
      </button>
    );
  }
  const stateClass = armed
    ? 'kill-switch--armed'
    : foreignControl
      ? 'kill-switch--foreign'
      : 'kill-switch--disarmed';
  const stateLabel = armed ? 'ARMED' : foreignControl ? 'IN USE' : 'DISARMED';
  // Name a missing unit even when arming is allowed: with one ARM covering
  // both machines, an operator who is not told the thruster is absent will
  // discover it by pressing PORT at the dock and getting nothing.
  const missing =
    rxLiveness !== 'live'
      ? 'drive unit'
      : hhLiveness !== 'live'
        ? 'thruster unit'
        : null;
  const hint = armed
    ? missing
      ? `tap to disarm — ${missing} not responding`
      : 'tap to disarm'
    : foreignControl
      ? 'another device is armed — tap to STOP'
      : missing
        ? `tap to arm — ${missing} not responding`
        : 'tap to arm remote control';

  return (
    <button
      type="button"
      className={`kill-switch ${stateClass}`}
      aria-pressed={armed}
      onClick={() => (disarms ? onDisarm() : onArm())}
    >
      <span className="kill-switch__state">{stateLabel}</span>
      <span className="kill-switch__hint">{hint}</span>
    </button>
  );
}
