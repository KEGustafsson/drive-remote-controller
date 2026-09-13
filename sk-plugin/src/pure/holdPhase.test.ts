import { describe, expect, it } from 'vitest';
import { HOLD_ENGAGE_GRACE_MS } from '../config';
import { evaluateHoldPhase, holdEngagedFrom, holdStallReason } from './holdPhase';

/**
 * The window exists so that the state EVERY arm passes through is not drawn as
 * a fault. A hold is asked for on this station's next intent, republished by
 * the arbiter, taken by HH on a control tick and reported back on its telemetry
 * cycle -- for those few hundred milliseconds "asked, not confirmed" is the
 * truth and nothing is wrong.
 */
describe('evaluateHoldPhase', () => {
  const asking = { thrusterCommandable: true, holdEngaged: false };

  it('is idle when this station is not asking for a hold', () => {
    expect(
      evaluateHoldPhase({ ...asking, requestedForMs: null }),
    ).toBe('idle');
  });

  it('is idle when the thruster cannot be commanded at all', () => {
    // Nothing this station says is reaching HH, and the kill switch and the
    // greyed panel say so already.
    expect(
      evaluateHoldPhase({
        thrusterCommandable: false,
        holdEngaged: false,
        requestedForMs: 10_000,
      }),
    ).toBe('idle');
  });

  it('is a transition until the request has had long enough to be a fault', () => {
    expect(evaluateHoldPhase({ ...asking, requestedForMs: 0 })).toBe('requested');
    expect(
      evaluateHoldPhase({ ...asking, requestedForMs: HOLD_ENGAGE_GRACE_MS - 1 }),
    ).toBe('requested');
    expect(
      evaluateHoldPhase({ ...asking, requestedForMs: HOLD_ENGAGE_GRACE_MS }),
    ).toBe('not-engaging');
  });

  it('is engaged on the unit’s own report, however long it took', () => {
    expect(
      evaluateHoldPhase({
        thrusterCommandable: true,
        holdEngaged: true,
        requestedForMs: 10 * HOLD_ENGAGE_GRACE_MS,
      }),
    ).toBe('engaged');
  });
});

describe('holdStallReason', () => {
  it('names what HH says, because the remedies differ', () => {
    expect(holdStallReason('DISARMED', false)).toBe('refused');
    expect(holdStallReason('ARMED_IDLE', false)).toBe('no-reference');
    expect(holdStallReason('FAULT', false)).toBe('unit-fault');
  });

  it('falls back to unknown when HH has said nothing it recognises', () => {
    expect(holdStallReason(undefined, false)).toBe('unknown');
    expect(holdStallReason('SOMETHING_NEWER', false)).toBe('unknown');
  });

  it('defers to the override note when somebody else owns the thruster', () => {
    // Not a fault, and already explained. Saying it twice, once in red, would
    // send the operator after a unit doing exactly what it was told.
    expect(holdStallReason('DISARMED', true)).toBe('other-source');
  });
});

/**
 * "Holding" is HH's word. The pair is necessary and not sufficient: ENABLE is
 * asserted in ARMED_IDLE as well as HOLDING, so a hold that never started for
 * want of a trustworthy heading -- or one given up after coasting past
 * coast_max -- publishes exactly the same pair as a running hold.
 */
describe('holdEngagedFrom', () => {
  it('requires the unit to be live, the pair, and its FSM in HOLDING', () => {
    expect(holdEngagedFrom(true, 'hold', 'HOLDING', true)).toBe(true);
    expect(holdEngagedFrom(true, 'hold', 'ARMED_IDLE', true)).toBe(false);
    expect(holdEngagedFrom(true, 'hold', 'FAULT', true)).toBe(false);
    expect(holdEngagedFrom(false, 'hold', 'HOLDING', true)).toBe(false);
    expect(holdEngagedFrom(true, 'manual', 'HOLDING', true)).toBe(false);
  });

  it('cannot report a hold on a unit that has stopped answering', () => {
    // Every input here is a VALUE, and Signal K retains those forever, so a
    // board switched off an hour ago still publishes all three.
    expect(holdEngagedFrom(true, 'hold', 'HOLDING', false)).toBe(false);
  });

  it('falls back to the pair when no FSM state has arrived', () => {
    // A server that has not delivered it yet, or an HH built before it was
    // published: the ARCHITECTURE.md §9 pair is what this UI used before.
    expect(holdEngagedFrom(true, 'hold', undefined, true)).toBe(true);
    expect(holdEngagedFrom(true, 'hold', null, true)).toBe(true);
  });
});
