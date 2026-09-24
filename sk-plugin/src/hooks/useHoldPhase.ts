import { useEffect, useState } from 'react';
import { RX_TELEMETRY_POLL_MS } from '../config';
import {
  evaluateHoldPhase,
  holdStallReason,
  manualRefusal,
  type HoldPhase,
  type HoldStall,
} from '../pure/holdPhase';
import { runtimeNowMs } from '../pure/runtimeClock';

export interface HoldStatus {
  phase: HoldPhase;
  stall: HoldStall;
}

/**
 * Watches a requested hold and reports what has become of it, re-evaluating on
 * a timer.
 *
 * The timer is the same necessity as the liveness one next door: a hold that
 * never arrives produces NO event -- it is precisely the absence of one -- so
 * React would never re-render to discover that the window has run out.
 *
 * The clock is `runtimeNowMs`, which counts time a suspended device spent
 * asleep, so a laptop closed mid-request does not wake inside a window that
 * expired minutes ago.
 *
 * @param requesting HOLD selected AND this station able to command the thruster
 *   -- in HOLD the arm IS the request, there is no further press.
 */
export function useHoldPhase(
  requesting: boolean,
  holdEngaged: boolean,
  hhFsmState: unknown,
  overridden: boolean,
): HoldStatus {
  const requestedForMs = useRequestedForMs(requesting);

  const phase = evaluateHoldPhase({
    thrusterCommandable: requesting,
    holdEngaged,
    requestedForMs,
  });

  return {
    phase,
    stall:
      phase === 'not-engaging' ? holdStallReason(hhFsmState, overridden) : 'none',
  };
}

/**
 * MANUAL's counterpart: is HH refusing the arm this station holds? The rule is
 * pure/manualRefusal (holdPhase.ts); the window and its clock are the same ones
 * useHoldPhase uses.
 *
 * @param requesting MANUAL selected AND this station able to command the
 *   thruster -- armed in MANUAL is the request.
 */
export function useManualRefusal(
  requesting: boolean,
  hhFsmState: unknown,
  overridden: boolean,
): HoldStall {
  const requestedForMs = useRequestedForMs(requesting);
  return manualRefusal({
    thrusterCommandable: requesting,
    requestedForMs,
    hhFsmState,
    overridden,
  });
}

/** How long `requesting` has been continuously true, ms; null while it is not. */
function useRequestedForMs(requesting: boolean): number | null {
  const [now, setNow] = useState(() => runtimeNowMs());
  const [since, setSince] = useState<number | null>(null);

  useEffect(() => {
    const id = setInterval(() => setNow(runtimeNowMs()), RX_TELEMETRY_POLL_MS);
    return () => clearInterval(id);
  }, []);

  // Stamped on the transition, so a later arm always starts a fresh window
  // rather than inheriting an expired one. The effect runs after the render
  // that first saw the request, which puts the start of the window one render
  // late -- always in the direction of patience, and invisible beside a 2 s
  // window.
  useEffect(() => {
    setSince(requesting ? runtimeNowMs() : null);
  }, [requesting]);

  // Clamped for the same reason the liveness age is: a throttled background
  // tab's timer can lag a stamp taken from the same clock.
  return since === null ? null : Math.max(0, now - since);
}
