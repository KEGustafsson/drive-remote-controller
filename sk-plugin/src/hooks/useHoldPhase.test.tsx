import { renderHook } from '@testing-library/react';
import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HOLD_ENGAGE_GRACE_MS } from '../config';
import { resetRuntimeClockForTests } from '../pure/runtimeClock';
import { useHoldPhase, useManualRefusal } from './useHoldPhase';

/**
 * The clock half of the rule. `pure/holdPhase.ts` decides what a given elapsed
 * time means; this is the part that has to notice the time passing at all --
 * and a hold that never arrives produces no event, so nothing would ever
 * re-render to discover it.
 */
describe('useHoldPhase', () => {
  afterEach(() => {
    vi.useRealTimers();
    resetRuntimeClockForTests();
  });

  /** Drive both clocks explicitly, as pure/runtimeClock.test.ts does. */
  function fakeClocks() {
    vi.useFakeTimers({ toFake: ['Date', 'performance', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(1_000_000));
    resetRuntimeClockForTests();
  }

  it('holds its peace at first, then reports the fault and why', () => {
    fakeClocks();
    const { result } = renderHook(() =>
      useHoldPhase(true, false, 'DISARMED', false),
    );

    expect(result.current.phase).toBe('requested');
    expect(result.current.stall).toBe('none');

    act(() => {
      vi.advanceTimersByTime(HOLD_ENGAGE_GRACE_MS + 250);
    });

    expect(result.current.phase).toBe('not-engaging');
    expect(result.current.stall).toBe('refused');
  });

  it('never leaves the transition while the unit is holding', () => {
    fakeClocks();
    const { result } = renderHook(() => useHoldPhase(true, true, 'HOLDING', false));

    act(() => {
      vi.advanceTimersByTime(10 * HOLD_ENGAGE_GRACE_MS);
    });

    expect(result.current.phase).toBe('engaged');
  });

  /**
   * The window is stamped on the transition, so a hold asked for again after a
   * disarm starts a fresh one rather than inheriting the expired one -- which
   * would put a red band on screen the instant the operator re-armed, before
   * HH could possibly have answered.
   */
  it('starts a fresh window for a later request', () => {
    fakeClocks();
    const { result, rerender } = renderHook(
      ({ requesting }: { requesting: boolean }) =>
        useHoldPhase(requesting, false, 'DISARMED', false),
      { initialProps: { requesting: true } },
    );

    act(() => {
      vi.advanceTimersByTime(HOLD_ENGAGE_GRACE_MS + 250);
    });
    expect(result.current.phase).toBe('not-engaging');

    // Disarmed, or MANUAL selected: no hold wanted, so no diagnostics.
    rerender({ requesting: false });
    expect(result.current.phase).toBe('idle');

    // ...and asking again is a new request, not the old one continued.
    rerender({ requesting: true });
    expect(result.current.phase).toBe('requested');
  });
});

/** The same window and clock, for MANUAL's refusal. */
describe('useManualRefusal', () => {
  afterEach(() => {
    vi.useRealTimers();
    resetRuntimeClockForTests();
  });

  function fakeClocks() {
    vi.useFakeTimers({ toFake: ['Date', 'performance', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(1_000_000));
    resetRuntimeClockForTests();
  }

  it('holds its peace inside the window, then reports the refusal', () => {
    fakeClocks();
    const { result } = renderHook(() => useManualRefusal(true, 'DISARMED', false));
    expect(result.current).toBe('none');
    act(() => {
      vi.advanceTimersByTime(HOLD_ENGAGE_GRACE_MS + 250);
    });
    expect(result.current).toBe('refused');
  });

  it('starts a fresh window when asked again after a disarm', () => {
    fakeClocks();
    const { result, rerender } = renderHook(
      ({ requesting }) => useManualRefusal(requesting, 'DISARMED', false),
      { initialProps: { requesting: true } },
    );
    act(() => {
      vi.advanceTimersByTime(HOLD_ENGAGE_GRACE_MS + 250);
    });
    expect(result.current).toBe('refused');
    rerender({ requesting: false });
    expect(result.current).toBe('none');
    rerender({ requesting: true });
    expect(result.current).toBe('none');
  });
});
