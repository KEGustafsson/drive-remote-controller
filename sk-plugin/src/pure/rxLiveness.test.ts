// The rule that decides whether the UI may present RX as present -- and
// therefore whether it offers an ARM at all. Pure, so every case is pinned
// here rather than inferred from a rendered screen.

import { describe, expect, it } from 'vitest';
import { evaluateRxLiveness, rxReadyToArm } from './rxLiveness';
import { RX_TELEMETRY_STALE_MS } from '../config';

const fresh = RX_TELEMETRY_STALE_MS - 1;
const stale = RX_TELEMETRY_STALE_MS + 1;

describe('evaluateRxLiveness', () => {
  it('is live when telemetry is fresh and the server agrees', () => {
    expect(
      evaluateRxLiveness({
        connectionState: 'open',
        rxTelemetryAgeMs: fresh,
        pluginRxLive: true,
      }),
    ).toBe('live');
  });

  it('is stale once telemetry ages past the timeout', () => {
    // THE BUG: rx.linkUp is still `true` here -- it always will be, forever,
    // because the board that publishes it is off. Only the age reveals it.
    expect(
      evaluateRxLiveness({
        connectionState: 'open',
        rxTelemetryAgeMs: stale,
        pluginRxLive: true,
      }),
    ).toBe('stale');
  });

  it('treats the timeout boundary itself as still live', () => {
    expect(
      evaluateRxLiveness({
        connectionState: 'open',
        rxTelemetryAgeMs: RX_TELEMETRY_STALE_MS,
        pluginRxLive: true,
      }),
    ).toBe('live');
  });

  it('distinguishes "never seen" from "went away"', () => {
    expect(
      evaluateRxLiveness({
        connectionState: 'open',
        rxTelemetryAgeMs: undefined,
        pluginRxLive: undefined,
      }),
    ).toBe('never-seen');
  });

  it('believes the server when it says RX is gone, even with fresh deltas', () => {
    expect(
      evaluateRxLiveness({
        connectionState: 'open',
        rxTelemetryAgeMs: fresh,
        pluginRxLive: false,
      }),
    ).toBe('stale');
  });

  it('falls back to the age check when the server has no opinion yet', () => {
    // An older plugin build, or the rxLive path simply not received yet.
    // Must not lock the operator out of arming a healthy system -- the
    // server enforces its own gate regardless of what this UI believes.
    expect(
      evaluateRxLiveness({
        connectionState: 'open',
        rxTelemetryAgeMs: fresh,
        pluginRxLive: undefined,
      }),
    ).toBe('live');
  });

  it('reports offline (not stale) while the socket is down -- RX is not to blame', () => {
    for (const connectionState of ['connecting', 'closed'] as const) {
      expect(
        evaluateRxLiveness({
          connectionState,
          rxTelemetryAgeMs: stale,
          pluginRxLive: true,
        }),
      ).toBe('offline');
    }
  });
});

describe('rxReadyToArm', () => {
  it('permits arming ONLY when RX is live', () => {
    expect(rxReadyToArm('live')).toBe(true);
    for (const s of ['offline', 'never-seen', 'stale'] as const) {
      expect(rxReadyToArm(s)).toBe(false);
    }
  });
});
