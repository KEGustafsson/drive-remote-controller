// A test WebSocket server that runs the REAL arbiter (arbiter.cjs) behind
// it -- so the UI integration tests exercise the true production loop:
// UI publishes an intent -> arbiter decides -> server echoes
// enabled/port/stbd/activeClient -> UI reflects it. No mock of the
// server-side arming logic; the same code the Signal K plugin runs.
//
// This mirrors how the plugin's index.cjs shells the arbiter, minus the
// Signal K plumbing (subscriptionmanager/handleMessage) which the plain `ws`
// server stands in for -- exactly the substitution skClient.test.ts already
// makes for the client side.

import { WebSocketServer, type WebSocket as WsSocket } from 'ws';
import type { AddressInfo } from 'node:net';
// @ts-expect-error -- pure CommonJS module, no .d.ts by design.
import { ArmArbiter } from '../arbiter.cjs';
import {
  PERIODIC_REFRESH_MS,
  SK_HH_LINK_UP_PATH,
  SK_PLUGIN_ACTIVE_CLIENT_PATH,
  SK_PLUGIN_ENABLED_PATH,
  SK_PLUGIN_HH_LIVE_PATH,
  SK_PLUGIN_PORT_COMMAND_PATH,
  SK_PLUGIN_RX_LIVE_PATH,
  SK_PLUGIN_STBD_COMMAND_PATH,
  SK_PLUGIN_THRUSTER_COMMAND_PATH,
  SK_PLUGIN_THRUSTER_MODE_PATH,
  SK_PLUGIN_THRUSTER_TRIM_PATH,
  SK_RX_LINK_UP_PATH,
} from '../src/config';

export interface ArbiterHarness {
  server: WebSocketServer;
  url: string;
  /**
   * Feed the arbiter one intent (as the production plugin does on each POST)
   * and broadcast the resulting state over the WS. Wire this to the App's
   * injected `postIntent` in tests; `injectIntent` is the same thing under a
   * name that reads better when simulating a *different* device.
   */
  feedIntent: (intent: Record<string, unknown>, nowMs?: number) => void;
  injectIntent: (intent: Record<string, unknown>, nowMs?: number) => void;
  /** Force a staleness tick (evicts clients past the timeout), then broadcast. */
  tick: (nowMs: number) => void;
  /** The arbiter's current published state. */
  state: () => {
    enabled: boolean;
    port: string;
    stbd: string;
    thruster: string;
    thrusterMode: string;
    trimDeg: number;
    activeClient: string;
    rxLive: boolean;
    hhLive: boolean;
  };
  /**
   * Resolves with the next client intent the server receives that matches
   * `pred` (or the very next one if no predicate). Because the UI also sends
   * a 250 ms heartbeat, tests that care about a specific field should pass a
   * predicate rather than assume the next raw message is theirs.
   */
  nextIntent: (
    pred?: (i: Record<string, unknown>) => boolean,
  ) => Promise<Record<string, unknown>>;
  /** Broadcast arbitrary path/value pairs to all clients (e.g. RX telemetry). */
  sendDelta: (values: Array<{ path: string; value: unknown }>) => void;
  /** Every message the UI sent over the WS -- used to prove it publishes no deltas. */
  clientMessages: () => unknown[];
  /** Drop all current client sockets (simulates a WiFi blip); arbiter state persists. */
  dropConnections: () => void;
  /**
   * Stop simulating the RX unit: no more telemetry deltas. Models the board
   * being switched off / losing WiFi -- note that NOTHING announces this, no
   * "RX is gone" message exists; its absence is the only evidence, which is
   * precisely why the bug where a dead RX read as healthy was possible.
   */
  stopRx: () => void;
  /** Resume simulating a live RX unit. */
  startRx: () => void;
  /** The same for the heading-hold (bow thruster) unit -- an independent board
   *  that can be switched off on its own. */
  stopHh: () => void;
  startHh: () => void;
  close: () => Promise<void>;
}

export async function startArbiterServer(
  opts: {
    staleTimeoutMs?: number;
    rxStaleTimeoutMs?: number;
    now?: () => number;
  } = {},
): Promise<ArbiterHarness> {
  const now = opts.now ?? (() => Date.now());
  const arbiter = new ArmArbiter({
    staleTimeoutMs: opts.staleTimeoutMs,
    // These UI tests are about client arbitration, and several drive the
    // arbiter with synthetic tick() timestamps that have nothing to do with
    // the wall clock the RX simulation below runs on. Mixing the two clocks
    // in a staleness comparison would make RX flicker for reasons unrelated
    // to what is under test, so here "RX has been seen" alone counts as
    // present. The RX staleness WINDOW itself is pinned exhaustively (against
    // one consistent clock) in test/arbiter.test.ts.
    rxStaleTimeoutMs: opts.rxStaleTimeoutMs ?? Number.POSITIVE_INFINITY,
  });
  const server = new WebSocketServer({ port: 0 });
  await new Promise<void>((resolve) => server.once('listening', resolve));
  const { port } = server.address() as AddressInfo;
  const url = `ws://127.0.0.1:${port}`;

  const sockets = new Set<WsSocket>();
  const clientMessages: unknown[] = [];
  const intentWaiters: Array<{
    pred: (i: Record<string, unknown>) => boolean;
    resolve: (i: Record<string, unknown>) => void;
  }> = [];

  function stateDelta() {
    const s = arbiter.state();
    return {
      context: 'vessels.self',
      updates: [
        {
          values: [
            { path: SK_PLUGIN_ENABLED_PATH, value: s.enabled },
            { path: SK_PLUGIN_PORT_COMMAND_PATH, value: s.port },
            { path: SK_PLUGIN_STBD_COMMAND_PATH, value: s.stbd },
            { path: SK_PLUGIN_THRUSTER_COMMAND_PATH, value: s.thruster },
            { path: SK_PLUGIN_THRUSTER_MODE_PATH, value: s.thrusterMode },
            { path: SK_PLUGIN_THRUSTER_TRIM_PATH, value: s.trimDeg },
            { path: SK_PLUGIN_ACTIVE_CLIENT_PATH, value: s.activeClient },
            { path: SK_PLUGIN_RX_LIVE_PATH, value: s.rxLive },
            { path: SK_PLUGIN_HH_LIVE_PATH, value: s.hhLive },
          ],
        },
      ],
    };
  }

  function broadcast() {
    const msg = JSON.stringify(stateDelta());
    for (const ws of sockets) {
      if (ws.readyState === ws.OPEN) ws.send(msg);
    }
  }

  function feed(intent: Record<string, unknown>, nowMs: number) {
    arbiter.onIntent(intent, nowMs);
    broadcast();
    const idx = intentWaiters.findIndex((w) => w.pred(intent));
    if (idx !== -1) {
      const [waiter] = intentWaiters.splice(idx, 1);
      waiter.resolve(intent);
    }
  }

  function sendValues(values: Array<{ path: string; value: unknown }>) {
    const msg = JSON.stringify({
      context: 'vessels.self',
      updates: [{ values }],
    });
    for (const ws of sockets) {
      if (ws.readyState === ws.OPEN) ws.send(msg);
    }
  }

  // The simulated RX unit's telemetry heartbeat: RX republishes its whole
  // telemetry set every PERIODIC_REFRESH_MS whether or not anything changed
  // (src/rx/main.cpp). Both halves of the fix depend on these arrivals -- the
  // server's arm gate and the UI's freshness check -- so the harness has to
  // reproduce the cadence, not just the values.
  let rxTimer: ReturnType<typeof setInterval> | null = null;
  function rxPulse() {
    arbiter.onRxTelemetry(now());
    // Value deliberately constant: a healthy link publishes the SAME linkUp
    // forever, so anything that judges liveness by a CHANGING value would
    // (wrongly) call this dead.
    sendValues([{ path: SK_RX_LINK_UP_PATH, value: true }]);
  }
  function startRx() {
    if (rxTimer !== null) return;
    rxPulse();
    rxTimer = setInterval(rxPulse, PERIODIC_REFRESH_MS);
    // Don't hold the test process open on this timer.
    if (typeof rxTimer.unref === 'function') rxTimer.unref();
  }
  function stopRx() {
    if (rxTimer === null) return;
    clearInterval(rxTimer);
    rxTimer = null;
  }
  startRx();

  // The simulated heading-hold unit, on its own independent timer -- the two
  // boards are separate machines and either can vanish without the other.
  let hhTimer: ReturnType<typeof setInterval> | null = null;
  function hhPulse() {
    arbiter.onHhTelemetry(now());
    sendValues([{ path: SK_HH_LINK_UP_PATH, value: true }]);
  }
  function startHh() {
    if (hhTimer !== null) return;
    hhPulse();
    hhTimer = setInterval(hhPulse, PERIODIC_REFRESH_MS);
    if (typeof hhTimer.unref === 'function') hhTimer.unref();
  }
  function stopHh() {
    if (hhTimer === null) return;
    clearInterval(hhTimer);
    hhTimer = null;
  }
  startHh();

  server.on('connection', (ws: WsSocket) => {
    sockets.add(ws);
    // Give a freshly connected UI the current authoritative state at once,
    // so a reconnecting instance re-syncs its armed view immediately.
    ws.send(JSON.stringify(stateDelta()));
    // A real RX is already publishing when a UI connects, and the server has
    // its retained value to hand over -- without this the UI would spend its
    // first refresh interval believing no RX exists.
    if (rxTimer !== null) {
      ws.send(
        JSON.stringify({
          context: 'vessels.self',
          updates: [{ values: [{ path: SK_RX_LINK_UP_PATH, value: true }] }],
        }),
      );
    }
    if (hhTimer !== null) {
      ws.send(
        JSON.stringify({
          context: 'vessels.self',
          updates: [{ values: [{ path: SK_HH_LINK_UP_PATH, value: true }] }],
        }),
      );
    }
    // Intents arrive via feedIntent() (standing in for the plugin's HTTP
    // route), NOT over this socket. Everything the UI sends here is recorded
    // so tests can assert it never writes to the Signal K data model.
    ws.on('message', (raw) => {
      try {
        clientMessages.push(JSON.parse(raw.toString()));
      } catch {
        /* ignore malformed */
      }
    });
    ws.on('close', () => sockets.delete(ws));
  });

  return {
    server,
    url,
    feedIntent: (intent, nowMs) => feed(intent, nowMs ?? now()),
    injectIntent: (intent, nowMs) => feed(intent, nowMs ?? now()),
    tick: (nowMs) => {
      arbiter.tick(nowMs);
      broadcast();
    },
    state: () => arbiter.state(),
    clientMessages: () => clientMessages,
    sendDelta: (values) => sendValues(values),
    nextIntent: (pred) =>
      new Promise<Record<string, unknown>>((resolve) =>
        intentWaiters.push({ pred: pred ?? (() => true), resolve }),
      ),
    stopRx,
    startRx,
    stopHh,
    startHh,
    dropConnections: () => {
      for (const ws of sockets) ws.terminate();
      sockets.clear();
    },
    close: () => {
      stopRx();
      stopHh();
      return new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}
