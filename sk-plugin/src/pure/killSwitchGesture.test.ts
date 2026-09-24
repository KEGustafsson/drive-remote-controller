// A kill-switch gesture's meaning is fixed at touch-down: a STOP acts on the
// way down and its gesture can never arm; an ARM waits for the lift, so
// sliding off still cancels it (pure/killSwitchGesture.ts).

import { describe, expect, it, vi } from 'vitest';
import { KILL_SWITCH_STOP_HOLDOVER_MS } from '../config';
import {
  killClick,
  killPointerCancel,
  killPointerDown,
  killTapMeaning,
  NO_GESTURE,
} from './killSwitchGesture';

describe('killTapMeaning (the holdover policy)', () => {
  it('means STOP whenever the button means STOP, without touching the window', () => {
    expect(killTapMeaning(true, null, 0)).toEqual({ meaning: 'stop', restartHoldover: false });
    expect(killTapMeaning(true, 1_000, 1_100)).toEqual({
      meaning: 'stop',
      restartHoldover: false,
    });
  });

  it('holds a tap just after STOP ended over as STOP, and restarts the window', () => {
    expect(killTapMeaning(false, 1_000, 1_000 + KILL_SWITCH_STOP_HOLDOVER_MS - 1)).toEqual({
      meaning: 'stop',
      restartHoldover: true,
    });
  });

  it('arms once the window has passed, or when STOP never ended this mount', () => {
    expect(killTapMeaning(false, 1_000, 1_000 + KILL_SWITCH_STOP_HOLDOVER_MS).meaning).toBe(
      'arm',
    );
    expect(killTapMeaning(false, null, 5_000).meaning).toBe('arm');
  });
});

describe('the kill-switch gesture', () => {
  it('fires STOP on the way down, and swallows that gesture’s click', () => {
    const down = killPointerDown('stop');
    expect(down.fire).toBe('stop');
    // Whatever the button means by the lift -- even ARM -- the click is spent.
    const up = killClick(down.gesture, 1, () => 'arm');
    expect(up.fire).toBeNull();
    expect(up.gesture).toEqual(NO_GESTURE);
  });

  it('does nothing on the way down for ARM, and arms on the click', () => {
    const down = killPointerDown('arm');
    expect(down.fire).toBeNull();
    expect(killClick(down.gesture, 1, () => 'arm').fire).toBe('arm');
  });

  it('re-decides an ARM gesture at the click: if it now means STOP, it stops', () => {
    const down = killPointerDown('arm');
    expect(killClick(down.gesture, 1, () => 'stop').fire).toBe('stop');
  });

  it('acts on a keyboard click (detail 0) by the policy, even after a spent STOP', () => {
    // STOP down, slid off: no click came, so the flag was left standing.
    const { gesture } = killPointerDown('stop');
    expect(killClick(gesture, 0, () => 'arm').fire).toBe('arm');
    expect(killClick(gesture, 0, () => 'stop').fire).toBe('stop');
  });

  it('clears a spent STOP on pointer cancel and on the next pointer down', () => {
    expect(killPointerCancel().gesture).toEqual(NO_GESTURE);
    const stale = killPointerDown('stop').gesture;
    // The next gesture decides afresh: an ARM down replaces the flag...
    const next = killPointerDown('arm');
    expect(next.gesture).toEqual(NO_GESTURE);
    // ...so its click arms, not swallowed by the earlier STOP.
    expect(killClick(next.gesture, 1, () => 'arm').fire).toBe('arm');
    expect(stale.stopSentOnDown).toBe(true);
  });

  it('does not even consult the policy for a swallowed click', () => {
    // Consulting it would restart the holdover off a click that did nothing.
    const decide = vi.fn(() => 'arm' as const);
    killClick(killPointerDown('stop').gesture, 1, decide);
    expect(decide).not.toHaveBeenCalled();
    killClick(NO_GESTURE, 1, decide);
    expect(decide).toHaveBeenCalledTimes(1);
  });

  it('a swallow is consumed by the one click it was for', () => {
    const { gesture } = killPointerDown('stop');
    const first = killClick(gesture, 1, () => 'arm');
    expect(first.fire).toBeNull();
    expect(killClick(first.gesture, 1, () => 'arm').fire).toBe('arm');
  });
});
