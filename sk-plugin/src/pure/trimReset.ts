// When does this station's heading TRIM go back to 0? Pure, so the rule is
// testable without React, sockets or timers -- and so the Android station's
// twin can be checked against the same table.
//
// Owner decision (2026-09-24): THE TRIM IS KEPT WHEN THE LIVE-DATA STREAM
// DROPS; IT IS RESET ONLY WHEN THE HOLD ENDS.
//
// The trim is an offset from the heading HH captured on engage, not a heading.
// Zeroing it therefore does not "stop" anything -- it swings the boat back
// toward the captured heading, by up to MAX_TRIM_DEG, with nobody touching
// anything. The rule used to zero it whenever the thruster was not commandable,
// and "not commandable" includes the READ side of this station going dark: the
// WebSocket dropping, or the server stream going silent on an open socket. But
// intents travel a separate HTTP POST that keeps running through both
// (App.tsx), so the hold was still this station's and still running -- and the
// operator's own screen losing its data feed turned the boat.
//
// So the conditions split by what we can actually know:
//
//  - Mode is not HOLD: always reset. That is local UI state, known with
//    certainty whatever the network is doing, and a trim outside HOLD means
//    nothing (the arbiter drops it: arbiter.cjs, the `thrusterMode === 'hold'`
//    screen in onIntent).
//
//  - Stream connected: reset when this station does not hold the token, or when
//    HH is not answering. Both are verdicts READ off the stream, so they are
//    only evidence while the stream is live.
//
//  - Stream NOT connected: never reset. Keep the value and keep sending it. The
//    token and HH's liveness are both unknowable from here: `activeClient` is a
//    retained last-known value, and HH's liveness is judged on arrival, so it
//    goes stale the moment the socket does -- reading either as "the hold has
//    ended" would be reading our own blindness as a fact about the boat. The
//    trim buttons are disabled meanwhile (the thruster is not commandable, so
//    ThrusterControl greys them): you cannot trim against a heading you cannot
//    see.
//
// And HH truly dying while this station is blind is covered where it can
// actually be observed -- at the arbiter, which keeps hearing HH:
// `_refreshUnitLiveness` erases the holder's trim to 0 on HH's falling edge,
// `_thrusterCommandable()` masks every stored and published trim to 0 while HH
// is absent or quarantined, and `_maybeRelease` does not lift that quarantine
// until the station is seen sending trim 0 with HH live again (arbiter.cjs).
// A kept trim from a blind station therefore can never restart automatic
// thrust off a returning HH.
//
// BOTH EDGES OF AN OUTAGE. HH's verdict and the stream's are separate clocks
// over separate arrivals, so neither edge of an outage flips them together:
//  - going dark: every arrival stops at once, but HH's last delta may predate
//    the arbiter's last publish by up to one HH publish period, so HH's window
//    runs out first and for a poll or two the stream still reads connected
//    with HH "gone";
//  - coming back: the stream reads connected as soon as the arbiter's publish
//    lands, while HH's last arrival is as old as the outage until its first
//    new delta follows.
// Read at face value, either moment would reset the trim on an outage about
// as often as the race went the wrong way. So "HH not answering" only counts
// once the verdict has held CONTINUOUSLY, on a connected stream, for a full HH
// liveness window (RX_TELEMETRY_STALE_MS, the window the verdict itself is
// judged on). An HH that is still not live after that has been silent on a
// live stream for all of it, which is what "not answering" means; the stream
// dropping restarts the count. The only price is that a real HH loss resets
// the trim here one window later -- and the arbiter has already zeroed it at
// the server by then.
//
// The token needs no such window: the stream only reads connected once the
// arbiter's own publish has arrived on it (pure/serverStream.ts), and that
// publish carries activeClient.

import { RX_TELEMETRY_STALE_MS } from '../config';
import type { ThrusterMode } from '../clientIntent';

export interface TrimResetInputs {
  /** The mode this station is asking for (local UI state). */
  mode: ThrusterMode;
  /** The server stream is live right now (socket open AND arbiter publish arriving). */
  connected: boolean;
  /** This station holds the arm token, per the arbiter's activeClient. */
  armed: boolean;
  /** HH's liveness verdict (useHhLiveness -> rxReadyToArm). */
  hhLive: boolean;
  /**
   * How long `connected && !hhLive` has held continuously, ms; 0 whenever it
   * does not hold, and on the very render it starts to.
   */
  hhGoneForMs: number;
  /** How long that must hold before it counts. */
  hhSettleMs?: number;
}

/** Must the trim be forced back to 0 now? */
export function trimMustReset({
  mode,
  connected,
  armed,
  hhLive,
  hhGoneForMs,
  hhSettleMs = RX_TELEMETRY_STALE_MS,
}: TrimResetInputs): boolean {
  if (mode !== 'hold') return true;
  if (!connected) return false;
  if (!armed) return true;
  return !hhLive && hhGoneForMs >= hhSettleMs;
}
