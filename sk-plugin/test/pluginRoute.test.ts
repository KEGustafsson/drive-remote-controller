// How the plugin registers its /intent route with signalk-server.
//
// This is a SECURITY-POSTURE test, not a plumbing one. In signalk-server, a
// route registered directly on a plugin router keeps the /plugins gate's
// admin-only default; only routes registered through the access-scoped
// registrar (`router.access(level)`) let readwrite users through. Commanding
// the drives is a write, not an administrative act, and every
// token-authenticated station -- the Android app, and anything else using the
// Signal K access-request flow the TX/RX/HH firmwares use -- authenticates as
// readwrite, not admin.
//
// So "registered at readwrite" is the difference between those stations
// working and being rejected outright, and it is invisible in any test that
// only drives the handler. Hence this file: it asserts the REGISTRATION, which
// no other test in the suite observes. The handler's own behaviour is covered
// exhaustively by arbiter.test.ts and the UI suites via test/arbiterServer.ts.

import { afterEach, describe, expect, it, vi } from 'vitest';
// @ts-expect-error -- plain CommonJS module, no .d.ts (pure JS by design,
// exactly as arbiter.test.ts imports arbiter.cjs). Shape is exercised below.
import createPlugin from '../index.cjs';

type Handler = (req: unknown, res: unknown) => void;
/** Signature of every route-registering method, on both router shapes. */
type Register = (path: string, ...handlers: Handler[]) => unknown;

// Minimal stand-in for the server's plugin app object. Only what constructing
// the plugin and running the /intent handler touches -- start() is never
// called here, so no streambundle is needed.
function makeApp() {
  return {
    handleMessage: vi.fn(),
    setPluginStatus: vi.fn(),
    debug: vi.fn(),
    error: vi.fn(),
  };
}

// A router exposing the modern access-scoped registrar. Records which level
// was requested and what got registered through it, and keeps `post` separate
// so a test can prove the route did NOT take the admin-only direct path.
function makeAccessRouter() {
  const registrar = {
    get: vi.fn<Register>(),
    post: vi.fn<Register>(),
    put: vi.fn<Register>(),
    patch: vi.fn<Register>(),
    delete: vi.fn<Register>(),
  };
  // The real AccessScopedRouter returns itself so calls can be chained; mirror
  // that rather than returning undefined, so this stand-in stays a faithful
  // stunt double if the plugin ever registers a second route by chaining.
  for (const fn of Object.values(registrar)) fn.mockReturnValue(registrar);
  return {
    access: vi.fn((_level: string) => registrar),
    post: vi.fn<Register>(),
    registrar,
  };
}

// An older server: a plain Express-ish router with no `access`.
function makeLegacyRouter() {
  return { post: vi.fn<Register>() };
}

// Drive whichever handler was registered and capture the response.
function callHandler(handler: Handler, body: unknown) {
  const res = { status: vi.fn(() => res), json: vi.fn(() => res) };
  handler({ body }, res);
  return res;
}

describe('registerWithRouter', () => {
  afterEach(() => vi.useRealTimers());

  it('registers /intent at readwrite, not on the admin-only default path', () => {
    const router = makeAccessRouter();
    createPlugin(makeApp()).registerWithRouter(router);

    // The level is the whole point of this test.
    expect(router.access).toHaveBeenCalledWith('readwrite');
    expect(router.registrar.post).toHaveBeenCalledTimes(1);
    expect(router.registrar.post.mock.calls[0][0]).toBe('/intent');

    // And it must NOT also be registered straight onto the router, which
    // would put an admin-only duplicate of the command route in place.
    expect(router.post).not.toHaveBeenCalled();
  });

  it('falls back to a direct registration on a server without router.access', () => {
    const app = makeApp();
    const router = makeLegacyRouter();
    createPlugin(app).registerWithRouter(router);

    expect(router.post).toHaveBeenCalledTimes(1);
    expect(router.post.mock.calls[0][0]).toBe('/intent');
    // Noted for whoever is commissioning a token station against this server,
    // since their access request will need approving at ADMIN level instead.
    expect(app.debug).toHaveBeenCalled();
  });

  it('acknowledges an intent identically in both registration modes', () => {
    const intent = {
      clientId: 'ui-test-client',
      seq: 1,
      armReq: 0,
      disarmReq: 0,
      port: 'neutral',
      stbd: 'neutral',
      thruster: 'off',
      thrusterMode: 'manual',
      trimDeg: 0,
    };

    // Started, because /intent only answers for a RUNNING plugin (below).
    // Fake timers so start()'s heartbeat never actually fires here -- this test
    // is about the acknowledgement, not the publish loop.
    vi.useFakeTimers();
    const modern = makeAccessRouter();
    const modernPlugin = createPlugin(makeApp());
    modernPlugin.registerWithRouter(modern);
    modernPlugin.start();
    const modernRes = callHandler(modern.registrar.post.mock.calls[0][1], intent);

    const legacy = makeLegacyRouter();
    const legacyPlugin = createPlugin(makeApp());
    legacyPlugin.registerWithRouter(legacy);
    legacyPlugin.start();
    const legacyRes = callHandler(legacy.post.mock.calls[0][1], intent);

    for (const res of [modernRes, legacyRes]) {
      expect(res.status).toHaveBeenCalledWith(200);
      expect(res.json).toHaveBeenCalledWith({ ok: true });
    }
    modernPlugin.stop();
    legacyPlugin.stop();
  });

  it('still acknowledges a malformed body rather than throwing', () => {
    // The arbiter is the validator (arbiter.test.ts pins that); the route must
    // not crash the server's request handling on garbage, and must not leave a
    // station's heartbeat hanging on an unanswered request.
    vi.useFakeTimers();
    const router = makeAccessRouter();
    const plugin = createPlugin(makeApp());
    plugin.registerWithRouter(router);
    plugin.start();
    const handler = router.registrar.post.mock.calls[0][1];

    for (const body of [undefined, null, 'not-an-object', { clientId: '' }]) {
      const res = callHandler(handler, body);
      expect(res.status).toHaveBeenCalledWith(200);
      expect(res.json).toHaveBeenCalledWith({ ok: true });
    }
    plugin.stop();
  });

  // The route is mounted by the server at REGISTRATION time and stays mounted
  // for the life of the process, so it outlives stop() -- and the arbiter's
  // liveness verdicts stay cached true for up to rxStaleTimeoutMs after the
  // telemetry subscriptions are dropped. Without a guard, an operator who has
  // just DISABLED the plugin still had a live arming authority: an arm tap
  // inside that window was granted and published ARMED out of a stopped plugin.
  it('refuses intents unless the plugin is running, and cannot be armed after stop', () => {
    vi.useFakeTimers();
    const callbacks = new Map<string, () => void>();
    const app = {
      ...makeApp(),
      streambundle: {
        getSelfStream: (path: string) => ({
          onValue: (cb: () => void) => {
            callbacks.set(path, cb);
            return vi.fn();
          },
        }),
      },
      readPluginOptions: vi.fn(() => ({ configuration: {} })),
      savePluginOptions: vi.fn(),
    };
    const plugin = createPlugin(app);
    const router = makeAccessRouter();
    plugin.registerWithRouter(router);
    const handler = router.registrar.post.mock.calls[0][1];
    const arm = (seq: number, armReq: number) => ({
      clientId: 'ui-lifecycle', seq, armReq, disarmReq: 0,
      port: 'forward', stbd: 'neutral', thruster: 'off',
      thrusterMode: 'manual', trimDeg: 0,
    });

    // Registered but never started: refused, and nothing is published at all.
    const beforeStart = callHandler(handler, arm(1, 0));
    expect(beforeStart.status).toHaveBeenCalledWith(503);
    expect(beforeStart.json).toHaveBeenCalledWith({
      ok: false,
      error: 'plugin not running',
    });
    expect(app.handleMessage).not.toHaveBeenCalled();

    // Running: the same station baselines and arms normally.
    plugin.start();
    for (const cb of callbacks.values()) cb();
    expect(callHandler(handler, arm(2, 0)).status).toHaveBeenCalledWith(200);
    expect(callHandler(handler, arm(3, 1)).status).toHaveBeenCalledWith(200);
    const armedDelta = app.handleMessage.mock.calls.at(-1)?.[1];
    expect(
      armedDelta.updates[0].values.find(
        (v: { path: string }) =>
          v.path === 'control.remoteController.plugin.enabled',
      ).value,
    ).toBe(true);

    // Stopped. The final safe state is published on the way out...
    plugin.stop();
    const publishesAfterStop = app.handleMessage.mock.calls.length;

    // ...and a fresh arm edge arriving afterwards -- inside the window where the
    // arbiter still believes both units are live -- must neither be accepted nor
    // publish anything. This is the assertion the guard exists for.
    const afterStop = callHandler(handler, arm(4, 2));
    expect(afterStop.status).toHaveBeenCalledWith(503);
    expect(app.handleMessage.mock.calls.length).toBe(publishesAfterStop);
    const lastDelta = app.handleMessage.mock.calls.at(-1)?.[1];
    const values = Object.fromEntries(
      lastDelta.updates[0].values.map(
        ({ path, value }: { path: string; value: unknown }) => [path, value],
      ),
    );
    expect(values).toMatchObject({
      'control.remoteController.plugin.enabled': false,
      'control.remoteController.plugin.activeClient': '',
      'control.remoteController.plugin.port.command': 'neutral',
    });
  });

  it('publishes a final disabled, neutral and off state when stopped', () => {
    vi.useFakeTimers();
    const callbacks = new Map<string, () => void>();
    const app = {
      ...makeApp(),
      streambundle: {
        getSelfStream: (path: string) => ({
          onValue: (cb: () => void) => {
            callbacks.set(path, cb);
            return vi.fn();
          },
        }),
      },
      readPluginOptions: vi.fn(() => ({ configuration: {} })),
      savePluginOptions: vi.fn(),
    };
    const plugin = createPlugin(app);
    const router = makeAccessRouter();
    plugin.registerWithRouter(router);
    plugin.start();

    // Announce both units, baseline the station, then arm and command motion.
    for (const cb of callbacks.values()) cb();
    const handler = router.registrar.post.mock.calls[0][1];
    callHandler(handler, {
      clientId: 'ui-stop-test', seq: 1, armReq: 0, disarmReq: 0,
      port: 'neutral', stbd: 'neutral', thruster: 'off',
      thrusterMode: 'manual', trimDeg: 0,
    });
    callHandler(handler, {
      clientId: 'ui-stop-test', seq: 2, armReq: 1, disarmReq: 0,
      port: 'forward', stbd: 'reverse', thruster: 'port',
      thrusterMode: 'manual', trimDeg: 0,
    });

    plugin.stop();
    const finalDelta = app.handleMessage.mock.calls.at(-1)?.[1];
    const values = Object.fromEntries(
      finalDelta.updates[0].values.map(({ path, value }: { path: string; value: unknown }) => [path, value]),
    );
    expect(values).toMatchObject({
      'control.remoteController.plugin.enabled': false,
      'control.remoteController.plugin.port.command': 'neutral',
      'control.remoteController.plugin.stbd.command': 'neutral',
      'control.remoteController.plugin.thruster.command': 'off',
      'control.remoteController.plugin.thruster.mode': 'hold',
      'control.remoteController.plugin.thruster.trimDeg': 0,
      'control.remoteController.plugin.activeClient': '',
    });
  });

  it('expires authority by elapsed runtime when the wall clock moves backwards', () => {
    // `performance` is NOT in vitest's default toFake set, so it has to be
    // asked for explicitly -- otherwise performance.now() keeps running on the
    // real clock, advanceTimersByTime moves nothing the arbiter reads, and
    // this test passes or fails for reasons unrelated to its subject.
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date', 'performance'],
    });
    vi.setSystemTime(new Date('2026-07-25T12:00:00Z'));
    const callbacks: Array<() => void> = [];
    const app = {
      ...makeApp(),
      streambundle: {
        getSelfStream: () => ({
          onValue: (cb: () => void) => {
            callbacks.push(cb);
            return vi.fn();
          },
        }),
      },
      readPluginOptions: vi.fn(() => ({ configuration: {} })),
      savePluginOptions: vi.fn(),
    };
    const plugin = createPlugin(app);
    const router = makeAccessRouter();
    plugin.registerWithRouter(router);
    plugin.start();

    // Establish unit liveness and a genuine arm edge at the current runtime.
    callbacks[0]();
    const handler = router.registrar.post.mock.calls[0][1];
    callHandler(handler, {
      clientId: 'clock-test', seq: 1, armReq: 0, disarmReq: 0,
      port: 'neutral', stbd: 'neutral', thruster: 'off',
      thrusterMode: 'manual', trimDeg: 0,
    });
    callHandler(handler, {
      clientId: 'clock-test', seq: 2, armReq: 1, disarmReq: 0,
      port: 'forward', stbd: 'neutral', thruster: 'off',
      thrusterMode: 'manual', trimDeg: 0,
    });

    // A civil-clock correction must not create a negative age and preserve
    // motion. Advancing fake timers advances performance.now() monotonically.
    vi.setSystemTime(new Date('2026-07-24T12:00:00Z'));
    vi.advanceTimersByTime(1250);

    const finalDelta = app.handleMessage.mock.calls.at(-1)?.[1];
    const values = Object.fromEntries(
      finalDelta.updates[0].values.map(({ path, value }: { path: string; value: unknown }) => [path, value]),
    );
    expect(values).toMatchObject({
      'control.remoteController.plugin.enabled': false,
      'control.remoteController.plugin.port.command': 'neutral',
      'control.remoteController.plugin.stbd.command': 'neutral',
      'control.remoteController.plugin.thruster.command': 'off',
      'control.remoteController.plugin.activeClient': '',
    });
    plugin.stop();
  });

  // The other half of the same clock question. CLOCK_MONOTONIC does not tick
  // while the host is suspended, so a server that sleeps with a station armed
  // wakes with every arrival still scored as young and republishes the
  // retained command -- motion restarting with no operator behind it. Wall
  // time running ahead of runtime is the only evidence available that the gap
  // happened at all.
  it('fails safe when the host suspends with a station armed', () => {
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date', 'performance'],
    });
    vi.setSystemTime(new Date('2026-07-25T12:00:00Z'));
    const callbacks: Array<() => void> = [];
    const app = {
      ...makeApp(),
      streambundle: {
        getSelfStream: () => ({
          onValue: (cb: () => void) => {
            callbacks.push(cb);
            return vi.fn();
          },
        }),
      },
      readPluginOptions: vi.fn(() => ({ configuration: {} })),
      savePluginOptions: vi.fn(),
    };
    const plugin = createPlugin(app);
    const router = makeAccessRouter();
    plugin.registerWithRouter(router);
    plugin.start();

    callbacks[0]();
    const handler = router.registrar.post.mock.calls[0][1];
    callHandler(handler, {
      clientId: 'suspend-test', seq: 1, armReq: 0, disarmReq: 0,
      port: 'neutral', stbd: 'neutral', thruster: 'off',
      thrusterMode: 'manual', trimDeg: 0,
    });
    callHandler(handler, {
      clientId: 'suspend-test', seq: 2, armReq: 1, disarmReq: 0,
      port: 'forward', stbd: 'neutral', thruster: 'off',
      thrusterMode: 'manual', trimDeg: 0,
    });
    const armed = app.handleMessage.mock.calls.at(-1)?.[1];
    expect(
      Object.fromEntries(
        armed.updates[0].values.map(({ path, value }: { path: string; value: unknown }) => [path, value]),
      ),
    ).toMatchObject({
      'control.remoteController.plugin.enabled': true,
      'control.remoteController.plugin.port.command': 'forward',
    });

    // Suspend for a minute: the wall clock keeps real time across it, the
    // monotonic clock does not. setSystemTime moves Date without touching the
    // faked performance/hrtime origin, which is exactly that shape. Then let
    // ONE ordinary 250 ms tick fire -- far less than STALE_TIMEOUT_MS, so
    // without the skew correction the holder still looks alive.
    vi.setSystemTime(new Date('2026-07-25T12:01:00Z'));
    vi.advanceTimersByTime(250);

    const finalDelta = app.handleMessage.mock.calls.at(-1)?.[1];
    const values = Object.fromEntries(
      finalDelta.updates[0].values.map(({ path, value }: { path: string; value: unknown }) => [path, value]),
    );
    expect(values).toMatchObject({
      'control.remoteController.plugin.enabled': false,
      'control.remoteController.plugin.port.command': 'neutral',
      'control.remoteController.plugin.stbd.command': 'neutral',
      'control.remoteController.plugin.thruster.command': 'off',
      'control.remoteController.plugin.activeClient': '',
    });
    plugin.stop();
  });
});
