// Signal K WebSocket client -- this app's equivalent of TX firmware's
// SensESP SKWSClient. Connects directly to the server's own
// /signalk/v1/stream endpoint (same mechanism any device on the boat uses).
//
// STRICTLY READ-ONLY BY CONSTRUCTION: the only message this client ever
// sends is the initial `subscribe`. There is deliberately no publish/send
// capability in the interface at all -- all control intent leaves the app
// as HTTP POSTs to the plugin's own /intent route, through the arbiter
// (ARCHITECTURE.md §10). A working delta-write function here, even unused,
// would be a standing invitation for some future feature to bypass the
// arming authority entirely; removing the capability (2026-07-23 review)
// makes the read-only invariant structural instead of conventional, and
// App.test.tsx pins the wire behavior ("writes NOTHING...").
//
// Deliberately NOT a React hook itself -- this is a plain external store
// (getSnapshot()/subscribe()), meant to be read via useSyncExternalStore
// in useSkConnection.ts, so its connection lifecycle is independent of
// React's render cycle and survives StrictMode's double-invoke in dev.

import {
  PERIODIC_REFRESH_MS,
  RECONNECT_INITIAL_DELAY_MS,
  RECONNECT_MAX_DELAY_MS,
  SUBSCRIBE_PATHS,
} from './config';
import { runtimeNowMs } from './pure/runtimeClock';

export type ConnectionState = 'connecting' | 'open' | 'closed';

export type SubscribedPath = (typeof SUBSCRIBE_PATHS)[number];

export interface SkSnapshot {
  connectionState: ConnectionState;
  values: Partial<Record<SubscribedPath, unknown>>;
}

export interface SkClient {
  getSnapshot(): SkSnapshot;
  /**
   * When a delta for `path` last ARRIVED (ms on the monotonic runtime clock),
   * or undefined if never. Updated on every delta, even
   * one whose value is unchanged.
   *
   * This exists because `values` alone cannot express "this reading has
   * stopped coming". Signal K retains the last value of a path forever, and
   * this client keeps its last-known copy across disconnects, so an RX board
   * that is switched off leaves `rx.linkUp: true` sitting there indefinitely
   * -- a dead link that reads as a healthy one. Callers that display or act
   * on RX telemetry must check this age, not just the value.
   *
   * Deliberately NOT part of the snapshot: it changes on every 250 ms
   * refresh even in a completely steady state, which would re-render the
   * whole app four times a second for nothing. Consumers pair it with their
   * own timer instead (see useRxTelemetryAge), which they need regardless --
   * going stale is the absence of an event, so no notification can ever
   * announce it.
   */
  getReceivedAt(path: SubscribedPath): number | undefined;
  subscribe(listener: () => void): () => void;
  close(): void;
}

// Shape of one entry in an INBOUND delta's values array (parsing only --
// nothing here ever builds one to send).
interface PathValue {
  path: string;
  value: unknown;
}

const SELF_CONTEXT = 'vessels.self';

/**
 * WebSocket constructor is injectable so tests can pass Node's `ws`
 * package explicitly rather than depending on jsdom's own WebSocket
 * support -- keeps the end-to-end test's transport identical to what a
 * real browser uses (the `ws` package is a standards-conformant client),
 * not a mock.
 */
export function createSkClient(
  url: string,
  WebSocketImpl: typeof WebSocket = globalThis.WebSocket,
): SkClient {
  let socket: WebSocket | null = null;
  // useSyncExternalStore requires getSnapshot() to return a referentially
  // STABLE value when nothing has changed (React compares with Object.is
  // on every render) -- a fresh `{ connectionState, values }` literal on
  // every call, even with identical contents, reads as "changed" every
  // time and causes an infinite render loop ("Maximum update depth
  // exceeded"), which is exactly what happened before this was a single
  // cached object replaced only on a real change.
  let snapshot: SkSnapshot = { connectionState: 'connecting', values: {} };
  // Arrival times, kept outside the snapshot on purpose -- see getReceivedAt.
  const receivedAt = new Map<SubscribedPath, number>();
  const listeners = new Set<() => void>();
  let reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let closedByCaller = false;

  function notify() {
    for (const listener of listeners) listener();
  }

  function setConnectionState(next: ConnectionState) {
    if (snapshot.connectionState === next) return;
    snapshot = { ...snapshot, connectionState: next };
    notify();
  }

  function scheduleReconnect() {
    if (closedByCaller || reconnectTimer !== null) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, reconnectDelayMs);
    reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_DELAY_MS);
  }

  function handleMessage(raw: string) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return; // malformed frame -- ignore, don't let one bad message crash the client
    }
    if (
      typeof parsed !== 'object' ||
      parsed === null ||
      !('updates' in parsed)
    ) {
      return;
    }
    const updates = (parsed as { updates?: unknown }).updates;
    if (!Array.isArray(updates)) return;

    let nextValues = snapshot.values;
    let changed = false;
    for (const update of updates) {
      const pathValues = (update as { values?: unknown })?.values;
      if (!Array.isArray(pathValues)) continue;
      for (const pv of pathValues) {
        const path = (pv as Partial<PathValue>)?.path;
        if (typeof path !== 'string') continue;
        if (!(SUBSCRIBE_PATHS as readonly string[]).includes(path)) continue;
        const subscribedPath = path as SubscribedPath;
        // Stamp arrival BEFORE the change check: an unchanged value still
        // proves the publisher is alive, and in steady state (RX republishing
        // the same telemetry every 250 ms) unchanged is the normal case. Only
        // stamping on change would make a healthy, quiet link look dead.
        // Liveness is elapsed time, on a clock that cannot jump when the
        // device corrects its wall clock AND does not stop while the device is
        // suspended -- see runtimeNowMs(). It must be the same clock the age is
        // later computed against, in useUnitLiveness.
        receivedAt.set(subscribedPath, runtimeNowMs());
        if (nextValues[subscribedPath] !== (pv as PathValue).value) {
          nextValues = { ...nextValues, [subscribedPath]: (pv as PathValue).value };
          changed = true;
        }
      }
    }
    if (changed) {
      snapshot = { ...snapshot, values: nextValues };
      notify();
    }
  }

  function connect() {
    closedByCaller = false;
    setConnectionState('connecting');
    let ws: WebSocket;
    try {
      ws = new WebSocketImpl(url) as WebSocket;
    } catch {
      // A constructor that throws SYNCHRONOUSLY never produces a socket, so no
      // 'close' event is coming and nothing else would ever schedule the next
      // attempt -- the reconnect chain would end here, permanently, while the
      // UI went on saying "reconnecting…" and the kill switch went on showing
      // OFFLINE. The read side would then never recover without a page reload.
      // Retry on the same backoff instead: this is the same situation as a
      // refused connection, and the app must keep trying to see the boat again.
      setConnectionState('closed');
      scheduleReconnect();
      return;
    }
    socket = ws;

    ws.addEventListener('open', () => {
      reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS;
      setConnectionState('open');
      ws.send(
        JSON.stringify({
          context: SELF_CONTEXT,
          subscribe: SUBSCRIBE_PATHS.map((path) => ({ path })),
        }),
      );
    });

    ws.addEventListener('message', (event: MessageEvent) => {
      if (typeof event.data === 'string') handleMessage(event.data);
    });

    ws.addEventListener('close', () => {
      socket = null;
      setConnectionState('closed');
      scheduleReconnect();
    });

    ws.addEventListener('error', () => {
      // 'close' always follows 'error' for a WebSocket -- reconnect is
      // scheduled there, nothing additional to do here beyond not
      // crashing on an unhandled event.
    });
  }

  connect();

  return {
    getSnapshot(): SkSnapshot {
      return snapshot;
    },

    getReceivedAt(path: SubscribedPath): number | undefined {
      return receivedAt.get(path);
    },

    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    close() {
      closedByCaller = true;
      if (reconnectTimer !== null) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      socket?.close();
      socket = null;
    },
  };
}

// Re-exported so callers that need the cadence (e.g. the periodic-refresh
// interval in useSkConnection.ts) import it from one place.
export { PERIODIC_REFRESH_MS };
