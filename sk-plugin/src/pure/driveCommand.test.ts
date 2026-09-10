import { describe, expect, it } from 'vitest';
import { fromSwitch, parseDisplayPosition } from './driveCommand';

describe('fromSwitch', () => {
  it('forward only -> forward', () => {
    expect(fromSwitch(true, false)).toBe('forward');
  });

  it('reverse only -> reverse', () => {
    expect(fromSwitch(false, true)).toBe('reverse');
  });

  it('neither -> neutral', () => {
    expect(fromSwitch(false, false)).toBe('neutral');
  });

  // The case a single physical switch can't produce but two independent
  // touch buttons can: both fingers down at once. Must fail to neutral,
  // not pick a direction (SAFETY.md drive invariant 1).
  it('both pressed at once -> neutral, never a direction', () => {
    expect(fromSwitch(true, true)).toBe('neutral');
  });
});

describe('parseDisplayPosition', () => {
  it.each(['forward', 'neutral', 'reverse'] as const)(
    'accepts the exact string %s',
    (value) => {
      expect(parseDisplayPosition(value)).toBe(value);
    },
  );

  it.each([undefined, null, '', 'FORWARD', 'fwd', 42, {}, []])(
    'rejects garbage input %p as unknown, not a silent guess',
    (value) => {
      expect(parseDisplayPosition(value)).toBe('unknown');
    },
  );
});
