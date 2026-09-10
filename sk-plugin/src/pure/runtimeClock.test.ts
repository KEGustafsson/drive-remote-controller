import { afterEach, describe, expect, it, vi } from 'vitest';
import { runtimeNowMs, resetRuntimeClockForTests, SUSPEND_SKEW_MS } from './runtimeClock';

/**
 * The clock the UI's liveness ages are measured against.
 *
 * Two failure modes, in opposite directions, and it has to survive both: a wall
 * clock that jumps must not rewind an age (which would keep an abandoned
 * reading looking fresh), and a device suspend must not be invisible (which
 * would report minutes-old telemetry as responding).
 */
describe('runtimeNowMs', () => {
  afterEach(() => {
    vi.useRealTimers();
    resetRuntimeClockForTests();
  });

  /** Drive both clocks explicitly: runtime is faked, wall is set outright. */
  function fake(startWallMs: number) {
    vi.useFakeTimers({ toFake: ['Date', 'performance'] });
    vi.setSystemTime(new Date(startWallMs));
    resetRuntimeClockForTests();
  }

  it('tracks elapsed runtime while nothing unusual happens', () => {
    fake(1_000_000);
    const start = runtimeNowMs();
    vi.advanceTimersByTime(400);
    expect(runtimeNowMs() - start).toBeCloseTo(400, 0);
  });

  it('counts time the monotonic clock skipped while suspended', () => {
    fake(1_000_000);
    const start = runtimeNowMs();

    // A suspend: wall time moves a full minute, the monotonic clock does not.
    vi.setSystemTime(new Date(1_060_000));
    const afterResume = runtimeNowMs();

    expect(afterResume - start).toBeGreaterThanOrEqual(60_000 - SUSPEND_SKEW_MS);
  });

  it('never rewinds when the wall clock is corrected backwards', () => {
    fake(1_000_000);
    const before = runtimeNowMs();

    // NTP steps civil time back an hour. The offset only ever grows, so this
    // must not move the clock at all -- an age that could shrink would let an
    // abandoned reading pass as fresh, which is the whole reason Date.now() is
    // not used directly.
    vi.setSystemTime(new Date(1_000_000 - 3_600_000));
    const after = runtimeNowMs();

    expect(after).toBeGreaterThanOrEqual(before);
    expect(after - before).toBeLessThan(SUSPEND_SKEW_MS);
  });

  it('is monotonic across a suspend followed by a backwards correction', () => {
    fake(1_000_000);
    const samples = [runtimeNowMs()];
    vi.setSystemTime(new Date(1_030_000)); // suspend
    samples.push(runtimeNowMs());
    vi.setSystemTime(new Date(1_000_000)); // then a backwards step
    samples.push(runtimeNowMs());
    vi.advanceTimersByTime(250);
    samples.push(runtimeNowMs());

    for (let i = 1; i < samples.length; i += 1) {
      expect(samples[i]).toBeGreaterThanOrEqual(samples[i - 1]);
    }
  });

  it('does not accumulate an offset from ordinary sampling jitter', () => {
    fake(1_000_000);
    const start = runtimeNowMs();
    // Many samples well under the threshold. Sub-threshold skew is discarded
    // AND the baseline still advances, so nothing creeps up over time.
    for (let i = 0; i < 200; i += 1) {
      vi.advanceTimersByTime(100);
      runtimeNowMs();
    }
    expect(runtimeNowMs() - start).toBeCloseTo(20_000, -1);
  });
});
