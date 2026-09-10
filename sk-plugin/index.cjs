// Signal K plugin registration + the server-side arming AUTHORITY.
//
// This file is the Signal K I/O shell. All the safety logic -- exclusive arm,
// universal disarm, edge-triggered requests, stale eviction, fail-to-safe --
// lives in the pure, host-tested arbiter.cjs, the same split as the firmware's
// src/ (I/O) vs lib/control_core/ (pure).
//
// The browser UIs never write a Signal K path. Each open UI POSTs its own
// INTENT (clientId + arm/disarm request counters + its drive positions,
// thruster direction, mode and commanded heading) to this plugin's HTTP route
// on a ~250 ms heartbeat, and THIS plugin is the single authority that
// arbitrates between them and is the sole writer of:
//   control.remoteController.plugin.enabled              (bool)
//   control.remoteController.plugin.port.command         (forward|neutral|reverse)
//   control.remoteController.plugin.stbd.command         (forward|neutral|reverse)
//   control.remoteController.plugin.thruster.command     (port|off|stbd)
//   control.remoteController.plugin.thruster.mode        (manual|hold)
//   control.remoteController.plugin.thruster.trimDeg     (deg, relative; 0 = none)
//   control.remoteController.plugin.activeClient         (holder id, '' = none)
//   control.remoteController.plugin.rxLive / .hhLive     (unit liveness verdicts)
// The units subscribe to those paths and see exactly ONE stable source.
//
// WHY THE INTENTS GO OVER HTTP AND NOT AS SK DELTAS: a delta-based intent needs
// a path per client, which litters the Signal K data model with a UUID-named
// node per open browser tab -- internal plumbing masquerading as boat data.
// A POST to this plugin's own route keeps that out of the data tree while
// staying equally secured: signalk-server wraps /plugins/* with its auth
// middleware, so an unauthenticated request is rejected with 401 exactly as an
// unauthenticated delta write would be dropped.
//
// .cjs so it loads as CommonJS regardless of package.json "type":"module".

'use strict';

const { ArmArbiter } = require('./arbiter.cjs');
const { SuspendAwareClock } = require('./suspendClock.cjs');

// ---- SK path contract (mirror of src/config.ts and include/config.h) ----
const PLUGIN_ID = 'signalk-drive-remote-controller';
const ENABLED_PATH = 'control.remoteController.plugin.enabled';
const PORT_COMMAND_PATH = 'control.remoteController.plugin.port.command';
const STBD_COMMAND_PATH = 'control.remoteController.plugin.stbd.command';
const ACTIVE_CLIENT_PATH = 'control.remoteController.plugin.activeClient';
// The server's own verdict on "is RX alive right now", published so every UI
// can show exactly the condition the arm gate applies (see arbiter.cjs).
const RX_LIVE_PATH = 'control.remoteController.plugin.rxLive';
// Bow-thruster command paths (ARCHITECTURE.md §6). Same shape as the drive
// commands, same sole-writer rule: the UIs never write these either.
const THRUSTER_COMMAND_PATH =
  'control.remoteController.plugin.thruster.command';
const THRUSTER_MODE_PATH = 'control.remoteController.plugin.thruster.mode';
const THRUSTER_TRIM_PATH =
  'control.remoteController.plugin.thruster.trimDeg';
// The server's verdict on the heading-hold unit, published for the same reason
// as rxLive: every UI should show the exact condition the server applied.
const HH_LIVE_PATH = 'control.remoteController.plugin.hhLive';

// The RX telemetry path whose ARRIVAL is used as proof RX is powered and on
// the network. RX republishes its whole telemetry set on one 250 ms timer
// (src/rx/main.cpp), so any one of them is an equally good heartbeat; linkUp
// is chosen because it is the one the phone UI already displays, which keeps
// "the row is green" and "arming is allowed" driven by the same delta.
// IMPORTANT: only the delta's ARRIVAL matters here, never its value -- a
// value nobody is updating any more reads as its last state forever, which
// is exactly how a switched-off unit would otherwise read as linkUp=true.
const RX_TELEMETRY_PATH = 'control.remoteController.rx.linkUp';
// The same, for the heading-hold unit. HH republishes its telemetry set on one
// ~15 Hz timer (src/hh/main.cpp), so hh.linkUp arrives steadily while the board
// is powered -- and stops the instant it is not, which is the only thing that
// can reveal its absence.
const HH_TELEMETRY_PATH = 'control.remoteController.hh.linkUp';

// Publish cadence: matches config::kSkPeriodicRefreshMs / PERIODIC_REFRESH_MS.
// Publishing the canonical state on this heartbeat (not only on change)
// keeps RX's per-source liveness watchdog fed while a single controller
// holds a steady press -- exactly as TX's own periodic republish does.
const REFRESH_MS = 250;
// Time since a client's last intent after which it is treated as gone and
// its token (if any) auto-releases. Mirrors config::kSkStalenessTimeoutMs.
const STALE_TIMEOUT_MS = 1000;
// Time since the last RX telemetry delta after which RX counts as gone and
// arming is blocked / an existing arm is released. Six of RX's 250 ms
// refreshes -- see the rationale in arbiter.cjs's constructor.
const RX_STALE_TIMEOUT_MS = 1500;

module.exports = function (app) {
  const arbiter = new ArmArbiter({
    staleTimeoutMs: STALE_TIMEOUT_MS,
    rxStaleTimeoutMs: RX_STALE_TIMEOUT_MS,
  });
  let heartbeat = null;
  let unsubscribeRxTelemetry = null;
  let unsubscribeHhTelemetry = null;
  let lastStatus = '';
  // Is this plugin actually running? The router is mounted by the server at
  // REGISTRATION time and stays mounted for the life of the process, so
  // /intent outlives both stop() and the window before the first start() --
  // see the guard in handleIntent.
  let running = false;

  // Safety timeouts measure elapsed runtime, not civil time. Date.now() can
  // jump forwards or backwards when NTP, an administrator, or the host clock
  // corrects the system time; a backwards jump could otherwise keep an
  // abandoned command holder alive indefinitely. performance.now() is
  // monotonic for the lifetime of this Node process -- but it does not advance
  // while the host is SUSPENDED, which would let a sleeping server wake and
  // republish a retained command.
  //
  // Sampling the two clocks is this shell's job; deciding what the pair means
  // is not. That reasoning is command-authority policy, so it lives in the pure
  // suspendClock.cjs beside arbiter.cjs, where the plugin's suite can hold it to
  // account and an alternate shell cannot quietly drop it.
  const suspendClock = new SuspendAwareClock(performance.now(), Date.now());
  const runtimeNowMs = () => suspendClock.nowMs(performance.now(), Date.now());

  // Watch RX's telemetry heartbeat. streambundle's self stream pushes EVERY
  // incoming delta for the path (verified in signalk-server's
  // streambundle.js: getSelfStream(path).push() is called per delta, with no
  // duplicate suppression), so RX's steady unchanged `linkUp: true` still
  // ticks this every 250 ms -- which is the whole point, since an unchanging
  // value is precisely what a dead RX also produces if you look at the value
  // instead of the arrivals.
  //
  // If this subscription can't be established we fail CLOSED: the arbiter
  // simply never sees RX telemetry, so it never grants an arm. For a remote
  // drive control, "the server can't tell me whether RX is there" must
  // block arming, not wave it through.
  // unitName is only used for the log message; wasLive/record are the two
  // arbiter entry points for that unit. One helper for both units so their
  // liveness is judged by identical code -- the thruster must not end up with
  // a subtly different (or more forgiving) notion of "present" than the drives.
  function watchUnitTelemetry(unitName, path, wasLive, record) {
    try {
      const stream = app.streambundle && app.streambundle.getSelfStream
        ? app.streambundle.getSelfStream(path)
        : null;
      if (!stream || typeof stream.onValue !== 'function') {
        app.error(
          'streambundle.getSelfStream unavailable -- cannot verify that ' +
            unitName +
            ' is alive, so arming against it will stay blocked.',
        );
        return null;
      }
      return stream.onValue(() => {
        // Arrival is the signal; the value is deliberately ignored.
        const now = runtimeNowMs();
        const before = wasLive(now);
        record(now);
        // The unit just came back after being gone: publish the new verdict at
        // once so the UIs re-enable their ARM button without waiting a tick.
        if (!before) publishState();
      });
    } catch (e) {
      app.error('could not subscribe to ' + unitName + ' telemetry: ' + e);
      return null;
    }
  }

  // Publish the arbiter's current canonical state to the RX-facing paths.
  // handleMessage() with no explicit context defaults to the server's own
  // vessel and stamps the plugin id as the source -- one legitimate, stable
  // source, so the old shared-$source collision is gone by construction.
  function publishState() {
    const s = arbiter.state();
    app.handleMessage(PLUGIN_ID, {
      updates: [
        {
          values: [
            { path: ENABLED_PATH, value: s.enabled },
            { path: PORT_COMMAND_PATH, value: s.port },
            { path: STBD_COMMAND_PATH, value: s.stbd },
            { path: THRUSTER_COMMAND_PATH, value: s.thruster },
            { path: THRUSTER_MODE_PATH, value: s.thrusterMode },
            { path: THRUSTER_TRIM_PATH, value: s.trimDeg },
            { path: ACTIVE_CLIENT_PATH, value: s.activeClient },
            { path: RX_LIVE_PATH, value: s.rxLive },
            { path: HH_LIVE_PATH, value: s.hhLive },
          ],
        },
      ],
    });
    updateStatus(s);
  }

  function updateStatus(s) {
    // Name the unit that is missing rather than just refusing: with one ARM
    // covering both machines, "arming is blocked" alone would leave an
    // operator guessing which board to go and check.
    const missing = [];
    if (!s.rxLive) missing.push('drive unit');
    if (!s.hhLive) missing.push('thruster unit');
    const next = s.enabled
      ? `ARMED by ${s.activeClient} — drives ${s.port}/${s.stbd}` +
        (s.thrusterMode === 'manual'
          ? `, thruster ${s.thruster}`
          : `, thruster holding heading`) +
        (missing.length ? ` (${missing.join(' + ')} not responding)` : '')
      : missing.length === 2
        ? 'Disarmed — neither unit responding; arming is blocked.'
        : missing.length === 1
          ? `Disarmed — no device in control (${missing[0]} not responding).`
          : 'Disarmed — no device in control.';
    if (next !== lastStatus) {
      app.setPluginStatus(next);
      lastStatus = next;
    }
  }

  // signalk-server logs `<id>:no configuration data` (via console.error, so
  // it lands in the server log looking like a fault) whenever a plugin starts
  // with no *stored* configuration, then substitutes {}. This plugin has an
  // empty schema and ignores its configuration entirely, so it would emit
  // that line on every single restart forever purely because nobody had ever
  // pressed Submit in the admin UI. Persisting an empty configuration object
  // once silences it for good.
  //
  // Note it cannot be suppressed on the very FIRST start of a fresh install:
  // the server checks (and logs) before it ever calls start(). This makes the
  // message a one-time event instead of a permanent fixture.
  //
  // savePluginOptions() merges into the existing stored options, so `enabled`
  // is preserved -- this never re-enables or disables the plugin.
  function ensureConfigurationPersisted() {
    if (
      typeof app.readPluginOptions !== 'function' ||
      typeof app.savePluginOptions !== 'function'
    ) {
      return; // older server without these APIs -- harmless, just stays noisy
    }
    try {
      const opts = app.readPluginOptions() || {};
      if (opts.configuration !== undefined) return; // already persisted
      app.savePluginOptions({}, (err) => {
        if (err) app.error('could not persist empty configuration: ' + err);
      });
    } catch (e) {
      app.error('could not persist empty configuration: ' + e);
    }
  }

  const plugin = {
    id: PLUGIN_ID,
    name: 'Drive Remote Control',

    // A safety-relevant remote control should not come pre-armed for every
    // installation just because the package is installed; and note ARMED is
    // now a per-operator, runtime token owned by the arbiter, never a
    // persisted plugin setting. The boat owner still enables the plugin
    // itself explicitly from the SK admin UI.
    enabledByDefault: false,

    schema: function () {
      return { type: 'object', properties: {} };
    },

    // A command station POSTs its intent here (~250 ms heartbeat).
    // signalk-server mounts this router under /plugins/<id> behind its auth
    // middleware, so req.body is already JSON-parsed and unauthenticated
    // callers are rejected before reaching us.
    //
    // THE ROUTE IS REGISTERED AT 'readwrite', NOT LEFT AT THE DEFAULT. In
    // signalk-server, a route registered DIRECTLY on the plugin router keeps
    // the /plugins gate's **admin-only** default; only routes registered
    // through the access-scoped registrar let readwrite users through
    // (`asPluginRouter()` in the server's src/interfaces/plugins.ts, which
    // records the level via securityStrategy.registerPluginRoutePermissions).
    //
    // Commanding is a WRITE, not an administrative act, so admin-only was
    // both too strong and, in practice, accidental: it happened to work
    // because a browser opened from the SK admin UI carries an admin session
    // cookie. Anything authenticating as a plain read/write client -- notably
    // a device holding an access-request token, which is how the Android
    // station and the TX/RX/HH firmwares authenticate -- was rejected. The
    // security posture is unchanged for the browser and strictly better
    // elsewhere: a phone can now command without being handed admin rights
    // over the whole server.
    registerWithRouter: function (router) {
      // The body IS the intent object {clientId, seq, armReq, disarmReq,
      // port, stbd, thruster, thrusterMode, trimDeg}; the arbiter validates it
      // (bad/blank clientId, garbage positions, etc. are handled there).
      // Fire-and-forget: a station learns who holds the token from the
      // `activeClient` SK path it already subscribes to, so we only need to
      // acknowledge receipt here.
      const handleIntent = (req, res) => {
        // A DISABLED PLUGIN COMMANDS NOTHING. signalk-server mounts this router
        // when the plugin is registered and leaves it mounted; stop() only tears
        // down the heartbeat and the telemetry subscriptions. So without this
        // guard an operator who has just disabled the plugin still had a live
        // arming authority: the arbiter's cached liveness verdicts stay true for
        // up to rxStaleTimeoutMs after the telemetry subscriptions are dropped,
        // so an arm tap inside that window was GRANTED and published an ARMED
        // delta out of a stopped plugin -- and after it, every intent went on
        // mutating arbiter state and publishing on change, indefinitely.
        //
        // 503 rather than a silent 200: a station must be able to tell "the
        // authority is not running" from "your intent was accepted". Its own
        // fail-safes then do the rest -- the units stale-fail their sources to
        // neutral/off within a second of the last published command, and stop()
        // has already published the safe state on the way out.
        //
        // Also covers the window BEFORE the first start(), which is safe today
        // only by accident (no unit has ever been seen, so nothing can arm);
        // this makes it safe by construction.
        if (!running) {
          res.status(503).json({ ok: false, error: 'plugin not running' });
          return;
        }
        if (arbiter.onIntent(req.body, runtimeNowMs())) {
          // React immediately on a state change (no imposed shift delay --
          // SAFETY.md drive invariant 2); the heartbeat only covers steady state.
          publishState();
        }
        res.status(200).json({ ok: true });
      };

      // Feature-detected rather than assumed: `router.access()` is a recent
      // server API. On a server without it the route falls back to the
      // admin-only default -- which is exactly today's behaviour, so the
      // browser UI keeps working unchanged and only the token-authenticated
      // stations are affected. They then need their access request approved
      // at ADMIN level instead of readwrite (docs/ARCHITECTURE.md §10).
      //
      // Noted via app.debug, deliberately not app.error: on a browser-only
      // installation this is a non-event, and flagging the plugin as errored
      // would be a false alarm on a system that works. The commissioning
      // curl in ARCHITECTURE.md §10 is what actually confirms which mode a
      // given server is in.
      if (typeof router.access === 'function') {
        router.access('readwrite').post('/intent', handleIntent);
      } else {
        app.debug(
          'signalk-server has no router.access() -- /intent stays admin-only; ' +
            'token-authenticated stations need an ADMIN-level access request',
        );
        router.post('/intent', handleIntent);
      }
    },

    start: function () {
      ensureConfigurationPersisted();
      // Accept intents from here on. Set before the subscriptions so a station
      // that is already heart-beating is not refused during startup; it still
      // cannot arm until a unit's telemetry has actually arrived.
      running = true;

      // Before anything can be armed, we must be able to see at least one
      // unit. Start watching first so a live unit is recognised as early as
      // possible.
      unsubscribeRxTelemetry = watchUnitTelemetry(
        'RX',
        RX_TELEMETRY_PATH,
        (now) => arbiter.rxLive(now),
        (now) => arbiter.onRxTelemetry(now),
      );
      unsubscribeHhTelemetry = watchUnitTelemetry(
        'the heading-hold unit',
        HH_TELEMETRY_PATH,
        (now) => arbiter.hhLive(now),
        (now) => arbiter.onHhTelemetry(now),
      );

      // Heartbeat: evict stale clients (auto-releasing a vanished holder to
      // NEUTRAL) and republish the canonical state so RX's watchdog stays
      // fed even during a long steady press.
      heartbeat = setInterval(() => {
        arbiter.tick(runtimeNowMs()); // evict a vanished holder -> auto-release
        publishState(); // every tick, so RX's watchdog stays fed
      }, REFRESH_MS);

      // Publish the initial safe (disarmed) state right away.
      publishState();
    },

    stop: function () {
      // Refuse intents FIRST, before anything is published: the route stays
      // mounted after this returns, so an intent racing the teardown must not
      // be able to re-grant the authority we are in the middle of giving up.
      running = false;
      // Do not leave retained armed commands behind while the downstream
      // watchdog counts down. Signal the safe state while the server API is
      // still available, then tear down the heartbeat and subscriptions.
      arbiter.resetToSafe();
      publishState();
      if (heartbeat) {
        clearInterval(heartbeat);
        heartbeat = null;
      }
      if (unsubscribeRxTelemetry) {
        unsubscribeRxTelemetry();
        unsubscribeRxTelemetry = null;
      }
      if (unsubscribeHhTelemetry) {
        unsubscribeHhTelemetry();
        unsubscribeHhTelemetry = null;
      }
    },
  };

  return plugin;
};
