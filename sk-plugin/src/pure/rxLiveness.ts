// "Is the RX board actually there right now?" -- pure, so it is exhaustively
// testable without timers, sockets or React (same split as pure/writeAccess.ts).
//
// WHY IT CANNOT READ A VALUE: `control.remoteController.rx.linkUp` is
// published by the unit itself, Signal K retains a path's last value
// indefinitely, and the SK client keeps its last-known copy across
// disconnects. A unit that is switched off therefore leaves `rx.linkUp: true`
// standing forever -- an all-green panel and a working ARM button for a board
// with no power. The only thing that distinguishes a healthy steady link from
// a dead one is whether telemetry is still ARRIVING, so liveness is judged on
// age here, and never on the value.

import { RX_TELEMETRY_STALE_MS } from '../config';
import type { ConnectionState } from '../skClient';

export type RxLiveness =
  /** RX telemetry is arriving right now. The only state in which arming is offered. */
  | 'live'
  /** The socket is down, so we genuinely cannot tell -- claiming either way would be a guess. */
  | 'offline'
  /** Connected, but no RX telemetry has EVER arrived (RX off since before this page loaded). */
  | 'never-seen'
  /** RX telemetry was arriving and has stopped -- the board went away. */
  | 'stale';

export interface RxLivenessInputs {
  connectionState: ConnectionState;
  /**
   * Age of the most recent RX telemetry delta, ms; undefined = none ever
   * received. Comes from SkClient.getReceivedAt() plus a clock (see
   * useRxLiveness), never from a value in the data model.
   */
  rxTelemetryAgeMs: number | undefined;
  /**
   * control.remoteController.plugin.rxLive -- the server-side arbiter's own
   * verdict, which is what actually gates arming. Consulted so the button
   * agrees with the authority rather than second-guessing it.
   *
   * `undefined` (path not received yet, or an older plugin build that does
   * not publish it) is treated as "no opinion" and falls back to the age
   * check, so a missing path can never lock the operator out of arming a
   * perfectly healthy system. It cannot make the UI unsafe either: the
   * server applies its own gate no matter what this UI believes.
   */
  pluginRxLive: unknown;
  staleTimeoutMs?: number;
}

export function evaluateRxLiveness({
  connectionState,
  rxTelemetryAgeMs,
  pluginRxLive,
  staleTimeoutMs = RX_TELEMETRY_STALE_MS,
}: RxLivenessInputs): RxLiveness {
  // While the socket is down, every value we hold is last-known and no delta
  // can arrive by definition -- reporting 'stale' would blame RX for what is
  // our own connection's fault, and reporting 'live' would be a lie.
  if (connectionState !== 'open') return 'offline';

  if (rxTelemetryAgeMs === undefined) return 'never-seen';
  if (rxTelemetryAgeMs > staleTimeoutMs) return 'stale';

  // Fresh deltas, but the server says RX is gone: believe the server, since
  // it owns the arm gate and a UI that offers an ARM the server will refuse
  // is worse than one that is briefly over-cautious.
  if (pluginRxLive === false) return 'stale';

  return 'live';
}

/** Convenience: the single condition under which arming may be offered. */
export function rxReadyToArm(liveness: RxLiveness): boolean {
  return liveness === 'live';
}
