import { describe, expect, it } from 'vitest';
import { overridesThisApp, parseSource, sourceLabel } from './sources';

describe('parseSource', () => {
  it.each(['local', 'tx', 'plugin', 'none'] as const)(
    'accepts the exact id %s',
    (value) => {
      expect(parseSource(value)).toBe(value);
    },
  );

  it.each([undefined, null, '', 'TX', 'server', 42, {}])(
    'maps unrecognised %p to unknown',
    (value) => {
      expect(parseSource(value)).toBe('unknown');
    },
  );
});

describe('sourceLabel', () => {
  it('gives human labels for each id', () => {
    expect(sourceLabel('local')).toBe('local switch');
    expect(sourceLabel('tx')).toBe('TX remote');
    expect(sourceLabel('plugin')).toBe('this app');
    expect(sourceLabel('none')).toBe('nobody');
    expect(sourceLabel('garbage')).toBe('unknown');
  });
});

describe('overridesThisApp', () => {
  it('local and tx outrank this app', () => {
    expect(overridesThisApp('local')).toBe(true);
    expect(overridesThisApp('tx')).toBe(true);
  });

  it('plugin (us), none, and unknown do not', () => {
    expect(overridesThisApp('plugin')).toBe(false);
    expect(overridesThisApp('none')).toBe(false);
    expect(overridesThisApp('whatever')).toBe(false);
  });
});
