import { describe, expect, it } from 'vitest';
import { SERVER_STREAM_STALE_MS } from '../config';
import { serverStreamLive } from './serverStream';

const base = {
  connectionState: 'open' as const,
  receivedAtMs: 10_000,
  openedAtMs: 5_000,
  nowMs: 10_100,
};

describe('serverStreamLive', () => {
  it('is live while the arbiter publish keeps arriving on an open socket', () => {
    expect(serverStreamLive(base)).toBe(true);
  });

  it('is not live once the publish stops arriving, though the socket reads open', () => {
    expect(
      serverStreamLive({ ...base, nowMs: base.receivedAtMs + SERVER_STREAM_STALE_MS + 1 }),
    ).toBe(false);
    expect(
      serverStreamLive({ ...base, nowMs: base.receivedAtMs + SERVER_STREAM_STALE_MS }),
    ).toBe(true);
  });

  it('does not count a pre-reconnect arrival as live, however recent', () => {
    // Dropped and reopened within the window: the retained activeClient was
    // heard on the OLD socket and has not been re-sent on this one.
    expect(serverStreamLive({ ...base, receivedAtMs: 9_990, openedAtMs: 10_000 })).toBe(
      false,
    );
    // The first arrival on the new socket is.
    expect(serverStreamLive({ ...base, receivedAtMs: 10_000, openedAtMs: 10_000 })).toBe(
      true,
    );
  });

  it('is not live before anything has arrived, or while the socket is not open', () => {
    expect(serverStreamLive({ ...base, receivedAtMs: undefined })).toBe(false);
    expect(serverStreamLive({ ...base, connectionState: 'closed' })).toBe(false);
    expect(serverStreamLive({ ...base, connectionState: 'connecting' })).toBe(false);
  });

  it('treats an arrival stamped after a lagging clock read as fresh', () => {
    expect(serverStreamLive({ ...base, nowMs: base.receivedAtMs - 50 })).toBe(true);
  });
});
