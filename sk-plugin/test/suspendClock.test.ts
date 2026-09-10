import { describe, expect, it } from 'vitest';
// @ts-expect-error -- pure CJS module, no type declarations by design (as arbiter.cjs)
import { SuspendAwareClock, SUSPEND_SKEW_MS } from '../suspendClock.cjs';

/**
 * The clock every safety timeout in the plugin is measured against.
 *
 * Tested here rather than through the plugin shell for the reason it was moved
 * out of it: what this arithmetic counts decides when a vanished holder is
 * evicted, when a unit is marked absent and when its stored command is erased.
 * Two failure directions, and it has to survive both — a wall clock that jumps
 * backwards must not rewind an age and keep an abandoned holder alive, and a
 * host suspend must not be invisible and let a retained command resume motion.
 *
 * Samples are passed in, so no fake timers are needed and every case is exact.
 */
describe('SuspendAwareClock', () => {
  it('tracks the monotonic clock while nothing unusual happens', () => {
    const c = new SuspendAwareClock(1000, 500_000);
    expect(c.nowMs(1250, 500_250)).toBe(1250);
    expect(c.nowMs(1500, 500_500)).toBe(1500);
    expect(c.suspendedMs).toBe(0);
  });

  it('adds the time a suspend stopped the monotonic clock', () => {
    const c = new SuspendAwareClock(1000, 500_000);
    // Wall time moves a minute; the monotonic clock barely moves, as it does
    // not run while the host is asleep.
    expect(c.nowMs(1010, 560_000)).toBeCloseTo(1010 + 59_990, 5);
    expect(c.suspendedMs).toBeCloseTo(59_990, 5);
  });

  it('ignores a backwards civil correction entirely', () => {
    const c = new SuspendAwareClock(1000, 500_000);
    // NTP steps the wall clock back an hour. Nothing may be added, and nothing
    // subtracted: an age that could shrink would let an abandoned command
    // holder look alive, which is why Date.now() is not used directly.
    expect(c.nowMs(1250, 500_250 - 3_600_000)).toBe(1250);
    expect(c.suspendedMs).toBe(0);
  });

  it('never returns a smaller value than before', () => {
    const c = new SuspendAwareClock(1000, 500_000);
    const readings = [
      c.nowMs(1250, 500_250), // ordinary
      c.nowMs(1300, 530_300), // suspend
      c.nowMs(1350, 500_000), // then a large backwards step
      c.nowMs(1600, 500_250), // ordinary again
    ];
    for (let i = 1; i < readings.length; i += 1) {
      expect(readings[i]).toBeGreaterThanOrEqual(readings[i - 1]);
    }
  });

  it('does not accumulate an offset from sub-threshold jitter', () => {
    const c = new SuspendAwareClock(0, 0);
    // Every step carries a little skew, all of it under the threshold. It is
    // discarded AND the baseline still advances, so nothing creeps up.
    let runtime = 0;
    let wall = 0;
    for (let i = 0; i < 500; i += 1) {
      runtime += 250;
      wall += 250 + SUSPEND_SKEW_MS - 1;
      c.nowMs(runtime, wall);
    }
    expect(c.suspendedMs).toBe(0);
    expect(c.nowMs(runtime, wall)).toBe(runtime);
  });

  it('treats a large forward step as a suspend, which fails safe', () => {
    // Indistinguishable from a suspend, and the consequence of guessing
    // "suspend" is that everything ages out and the arbiter disarms. The
    // consequence of guessing "not a suspend" is a retained command resuming
    // motion. The asymmetry is the whole argument.
    const c = new SuspendAwareClock(1000, 500_000);
    expect(c.nowMs(1250, 500_250 + 30_000)).toBeGreaterThan(30_000);
  });
});
