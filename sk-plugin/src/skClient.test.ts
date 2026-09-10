// Runs createSkClient() against a REAL WebSocket server (the `ws` package,
// in-process on an ephemeral port) rather than a mock -- these tests
// exercise the actual wire protocol this app depends on: the same-origin
// stream endpoint's subscribe/publish message shapes verified against the
// real signalk-server-node source (see JOURNAL.md).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WebSocketServer, WebSocket as NodeWebSocket } from 'ws';
import type { AddressInfo } from 'node:net';
import { createSkClient, type SkClient } from './skClient';

let server: WebSocketServer;
let url: string;
let client: SkClient | null = null;

beforeEach(async () => {
  server = new WebSocketServer({ port: 0 });
  await new Promise<void>((resolve) => server.once('listening', resolve));
  const { port } = server.address() as AddressInfo;
  url = `ws://127.0.0.1:${port}`;
});

afterEach(async () => {
  client?.close();
  client = null;
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

function nextConnection(): Promise<import('ws').WebSocket> {
  return new Promise((resolve) => server.once('connection', resolve));
}

function nextMessage(socket: import('ws').WebSocket): Promise<unknown> {
  return new Promise((resolve) => {
    socket.once('message', (raw) => resolve(JSON.parse(raw.toString())));
  });
}

describe('createSkClient', () => {
  it('connects and sends a scoped subscribe message for exactly the rx.* paths', async () => {
    const connectionPromise = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    const serverSocket = await connectionPromise;
    const subscribeMsg = (await nextMessage(serverSocket)) as {
      context: string;
      subscribe: { path: string }[];
    };

    expect(subscribeMsg.context).toBe('vessels.self');
    const paths = subscribeMsg.subscribe.map((s) => s.path);
    expect(paths).toContain('control.remoteController.rx.port.state');
    expect(paths).toContain('control.remoteController.rx.linkOk');
    expect(paths).toContain('control.remoteController.rx.linkUp');
    // Never subscribes to its own outbound paths -- it has no reason to
    // hear an echo of what it just published.
    expect(paths).not.toContain('control.remoteController.plugin.port.command');
  });

  it('reports connectionState as open only after the socket actually opens', async () => {
    const connectionPromise = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    expect(client.getSnapshot().connectionState).toBe('connecting');
    await connectionPromise;
    await vi.waitFor(() =>
      expect(client!.getSnapshot().connectionState).toBe('open'),
    );
  });

  it('exposes no publish/write capability at all (read-only by construction)', async () => {
    // The 2026-07-23 review removed publish() outright: a working
    // delta-write function, even with zero callers, was a standing invitation
    // to bypass the arbiter. The wire-level "never sends updates" behavior is
    // pinned end-to-end in App.test.tsx; this pins the structural half.
    const connectionPromise = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    await connectionPromise;
    expect(
      Object.keys(client as unknown as Record<string, unknown>).sort(),
    ).toEqual(['close', 'getReceivedAt', 'getSnapshot', 'subscribe']);
  });

  it('incoming deltas for subscribed rx.* paths update the snapshot', async () => {
    const connectionPromise = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    const serverSocket = await connectionPromise;
    await nextMessage(serverSocket); // subscribe message

    serverSocket.send(
      JSON.stringify({
        context: 'vessels.self',
        updates: [
          {
            $source: 'sensesp.rx',
            values: [
              { path: 'control.remoteController.rx.port.state', value: 'forward' },
              { path: 'control.remoteController.rx.linkOk', value: true },
            ],
          },
        ],
      }),
    );

    await vi.waitFor(() => {
      const snap = client!.getSnapshot();
      expect(snap.values['control.remoteController.rx.port.state']).toBe(
        'forward',
      );
      expect(snap.values['control.remoteController.rx.linkOk']).toBe(true);
    });
  });

  it('ignores deltas for paths it did not subscribe to', async () => {
    const connectionPromise = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    const serverSocket = await connectionPromise;
    await nextMessage(serverSocket);

    serverSocket.send(
      JSON.stringify({
        context: 'vessels.self',
        updates: [{ values: [{ path: 'navigation.speedOverGround', value: 3 }] }],
      }),
    );
    // Give the (non-)update a tick to (not) land, then confirm it didn't.
    await new Promise((r) => setTimeout(r, 20));
    expect(client.getSnapshot().values).not.toHaveProperty(
      'navigation.speedOverGround',
    );
  });

  it('malformed frames are ignored rather than crashing the client', async () => {
    const connectionPromise = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    const serverSocket = await connectionPromise;
    await nextMessage(serverSocket);

    serverSocket.send('{not json');
    serverSocket.send(JSON.stringify({ no: 'updates field' }));
    await new Promise((r) => setTimeout(r, 20));
    expect(client.getSnapshot().connectionState).toBe('open');
  });

  it('reconnects with backoff after the connection drops', async () => {
    vi.useFakeTimers();
    const firstConnection = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    const firstSocket = await firstConnection;
    await vi.waitFor(() =>
      expect(client!.getSnapshot().connectionState).toBe('open'),
    );

    const secondConnection = nextConnection();
    firstSocket.close();
    await vi.waitFor(() =>
      expect(client!.getSnapshot().connectionState).toBe('closed'),
    );

    await vi.advanceTimersByTimeAsync(1000);
    await secondConnection;
    await vi.waitFor(() =>
      expect(client!.getSnapshot().connectionState).toBe('open'),
    );
    vi.useRealTimers();
  });
});

// Values alone cannot express "this reading stopped coming": Signal K retains
// a path's last value forever and this client keeps its last-known copy, so a
// switched-off RX leaves rx.linkUp reading `true` indefinitely. Arrival times
// are what make that detectable, and the subtle part is that they must be
// stamped for UNCHANGED values too -- a healthy RX republishes the identical
// telemetry every 250 ms, so "only stamp on change" would mark a perfectly
// live link as dead.
describe('createSkClient delta arrival times', () => {
  const LINK_UP = 'control.remoteController.rx.linkUp';

  function linkUpDelta(value: boolean) {
    return JSON.stringify({
      context: 'vessels.self',
      updates: [{ values: [{ path: LINK_UP, value }] }],
    });
  }

  it('has no arrival time for a path before anything arrives', async () => {
    const conn = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    await conn;
    expect(client.getReceivedAt(LINK_UP)).toBeUndefined();
  });

  it('stamps arrival on every delta, including an unchanged repeat', async () => {
    const conn = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    const socket = await conn;

    socket.send(linkUpDelta(true));
    await vi.waitFor(() =>
      expect(client!.getReceivedAt(LINK_UP)).toBeDefined(),
    );
    const first = client.getReceivedAt(LINK_UP)!;

    // Same value again, as RX's steady republish sends it.
    await new Promise((r) => setTimeout(r, 20));
    socket.send(linkUpDelta(true));
    await vi.waitFor(() =>
      expect(client!.getReceivedAt(LINK_UP)!).toBeGreaterThan(first),
    );
    // The value never changed; only the freshness did.
    expect(client.getSnapshot().values[LINK_UP]).toBe(true);
  });

  it('leaves the arrival time frozen once deltas stop (a dead publisher)', async () => {
    const conn = nextConnection();
    client = createSkClient(url, NodeWebSocket as unknown as typeof WebSocket);
    const socket = await conn;

    socket.send(linkUpDelta(true));
    await vi.waitFor(() =>
      expect(client!.getReceivedAt(LINK_UP)).toBeDefined(),
    );
    const last = client.getReceivedAt(LINK_UP)!;

    // RX goes away: nothing arrives, and nothing announces that.
    await new Promise((r) => setTimeout(r, 100));
    expect(client.getReceivedAt(LINK_UP)).toBe(last); // ages, so callers can tell
    expect(client.getSnapshot().values[LINK_UP]).toBe(true); // but the value lies on
  });
});
