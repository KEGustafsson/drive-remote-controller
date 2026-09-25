// The trim is an offset from the captured heading, so zeroing it TURNS the
// boat. It may only happen when the hold has ended -- never because this
// station's own read side went dark (pure/trimReset.ts).

import { describe, expect, it } from 'vitest';
import { RX_TELEMETRY_STALE_MS } from '../config';
import { trimMustReset } from './trimReset';

const holding = {
  mode: 'hold' as const,
  connected: true,
  armed: true,
  hhLive: true,
  hhGoneForMs: 0,
};

describe('trimMustReset', () => {
  it('keeps the trim while armed, connected, in HOLD, with HH answering', () => {
    expect(trimMustReset(holding)).toBe(false);
  });

  it('resets outside HOLD, whatever the stream is doing', () => {
    expect(trimMustReset({ ...holding, mode: 'manual' })).toBe(true);
    expect(trimMustReset({ ...holding, mode: 'manual', connected: false })).toBe(true);
  });

  it('resets on disarm while connected', () => {
    expect(trimMustReset({ ...holding, armed: false })).toBe(true);
  });

  it('resets once HH has stayed silent a full window on a live stream', () => {
    expect(
      trimMustReset({ ...holding, hhLive: false, hhGoneForMs: RX_TELEMETRY_STALE_MS }),
    ).toBe(true);
  });

  // THE owner decision: the read side going dark says nothing about the hold.
  it('keeps the trim while the stream is down, even though the token and HH read gone', () => {
    expect(
      trimMustReset({ ...holding, connected: false, armed: false, hhLive: false }),
    ).toBe(false);
    // Even with a (stale) long HH-gone count handed in: blind is blind.
    expect(
      trimMustReset({
        ...holding,
        connected: false,
        hhLive: false,
        hhGoneForMs: RX_TELEMETRY_STALE_MS * 10,
      }),
    ).toBe(false);
  });

  // Either edge of an outage can briefly read "connected, HH gone": going
  // dark, HH's window may run out a poll before the stream's; coming back, the
  // stream reads connected before HH's first new delta lands. Neither is HH
  // going away, so the verdict must hold a full HH window first.
  it('does not read HH as gone until the verdict has held a full HH window', () => {
    const gone = { ...holding, hhLive: false };
    expect(trimMustReset({ ...gone, hhGoneForMs: 0 })).toBe(false);
    expect(trimMustReset({ ...gone, hhGoneForMs: RX_TELEMETRY_STALE_MS - 1 })).toBe(false);
    expect(trimMustReset({ ...gone, hhGoneForMs: RX_TELEMETRY_STALE_MS })).toBe(true);
  });

  // The token has no such window: the stream only reads connected once the
  // arbiter's own publish -- which carries activeClient -- has arrived on it.
  it('reads a lost token at once, even on a stream that has just come back', () => {
    expect(trimMustReset({ ...holding, armed: false, hhGoneForMs: 0 })).toBe(true);
  });
});
