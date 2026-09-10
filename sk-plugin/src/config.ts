// Mirrors the relevant constants in ../../include/config.h -- the two
// files are kept in sync by hand (no shared build step between the
// PlatformIO firmware and this npm project), so a change to either side's
// SK paths or timing constants must be applied to both. Cross-referenced
// by name so a diff is easy to spot.

// ---- Signal K path contract (ARCHITECTURE.md §9) ----
// This plugin only needs the "plugin.*" paths (what it publishes) and the
// "rx.*" paths (what it reads for the status display) -- it has no reason
// to touch TX's own paths, though rx.linkOk/rx.*.source indirectly reflect
// TX's activity too.
// These three are the RX-facing command paths. IMPORTANT: this web UI no
// longer WRITES them -- the Node plugin (index.cjs) is their single
// authoritative writer, arbitrating between all open UI instances (see
// arbiter.cjs / ARCHITECTURE.md §10). They stay here because config.ts mirrors the
// firmware's include/config.h path contract, and RX still subscribes to
// exactly these. The UI reads `activeClient` (below) to know who holds the
// arm token, and POSTs its own intent to the plugin (see below).
export const SK_PLUGIN_PORT_COMMAND_PATH =
  'control.remoteController.plugin.port.command';
export const SK_PLUGIN_STBD_COMMAND_PATH =
  'control.remoteController.plugin.stbd.command';
export const SK_PLUGIN_ENABLED_PATH = 'control.remoteController.plugin.enabled';

// The arbiter publishes the clientId currently holding the arm token here
// ('' when nobody holds it). Each UI derives its own ARMED state from
// "activeClient === myClientId" -- authority-driven, never a local guess --
// which is what makes exclusive arming and the oscillation fix work. This is
// the ONE internal-ish path kept in the SK data model; the per-client
// intents deliberately do NOT go here (they'd litter the tree with a
// UUID-named node per browser tab) -- they're POSTed instead (below).
export const SK_PLUGIN_ACTIVE_CLIENT_PATH =
  'control.remoteController.plugin.activeClient';

// The arbiter's own verdict on whether RX is alive right now, i.e. whether
// its telemetry is still arriving at the server (index.cjs / arbiter.cjs).
// This is the condition the server applies to arm requests, published so the
// UI can show and enforce exactly the same rule instead of guessing.
//
// Why this and not rx.linkUp: linkUp is a VALUE published BY RX, so when RX
// is switched off it stops being updated and simply keeps reading `true`
// forever -- a dead board that looks perfectly healthy. rxLive is recomputed
// by the always-running server from the ARRIVAL of RX's deltas, so it goes
// false when RX goes away, and even the cached copy a reconnecting browser
// receives was a true statement when it was written. (Plugin-internal, so
// not mirrored in include/config.h -- the firmware neither reads nor writes
// it, exactly like activeClient.)
export const SK_PLUGIN_RX_LIVE_PATH = 'control.remoteController.plugin.rxLive';

// The same verdict for the heading-hold (bow thruster) unit. Tracked
// separately because the two boards fail independently, and because one ARM
// covers both: the operator needs to be told WHICH unit is missing, not just
// that something is.
export const SK_PLUGIN_HH_LIVE_PATH = 'control.remoteController.plugin.hhLive';

// Bow-thruster command paths (ARCHITECTURE.md §6). Written ONLY by the plugin
// server, exactly like the drive command paths above -- listed here because
// config.ts mirrors the firmware's path contract, not because this UI writes
// them.
export const SK_PLUGIN_THRUSTER_COMMAND_PATH =
  'control.remoteController.plugin.thruster.command';
export const SK_PLUGIN_THRUSTER_MODE_PATH =
  'control.remoteController.plugin.thruster.mode';
export const SK_PLUGIN_THRUSTER_TRIM_PATH =
  'control.remoteController.plugin.thruster.trimDeg';

// ---- Heading-hold unit telemetry (read-only) ----
// hh.linkUp is this UI's heartbeat for the thruster board, judged on ARRIVAL
// exactly like rx.linkUp. hh.setpointDeg is the one whose VALUE matters: it is
// the actual held heading, shown for reference while trimming.
export const SK_HH_LINK_UP_PATH = 'control.remoteController.hh.linkUp';
export const SK_HH_THRUSTER_STATE_PATH =
  'control.remoteController.hh.thruster.state';
export const SK_HH_MODE_PATH = 'control.remoteController.hh.mode';
export const SK_HH_SOURCE_PATH = 'control.remoteController.hh.source';
export const SK_HH_SETPOINT_PATH = 'control.remoteController.hh.setpointDeg';
export const SK_HH_ARMED_PATH = 'control.remoteController.hh.armed';
export const SK_HH_REVERSAL_PENDING_PATH =
  'control.remoteController.hh.reversalPending';

// Largest heading-trim offset a station may command, in degrees. Mirrors
// control_core::kMaxTrimDeg (setpoint.h). A relative trim needs no "not
// commanding" sentinel: 0 is the well-defined rest, so the old
// NO_COMMANDED_TARGET_DEG (999) is gone.
export const MAX_TRIM_DEG = 45;

// Each open UI POSTs its intent {clientId, seq, armReq, disarmReq, port,
// stbd, thruster, thrusterMode, trimDeg} here (~250 ms heartbeat; see
// clientIntent.ts for the full shape). signalk-server mounts the plugin's router
// under /plugins/<id> behind its auth middleware, so this write is secured
// exactly like a delta write would be, but leaves no trace in the data tree.
// Kept in lockstep with index.cjs's router path.
export const SK_PLUGIN_INTENT_ENDPOINT =
  '/plugins/signalk-drive-remote-controller/intent';

export const SK_RX_PORT_STATE_PATH = 'control.remoteController.rx.port.state';
export const SK_RX_STBD_STATE_PATH = 'control.remoteController.rx.stbd.state';
export const SK_RX_PORT_SOURCE_PATH =
  'control.remoteController.rx.port.source';
export const SK_RX_STBD_SOURCE_PATH =
  'control.remoteController.rx.stbd.source';
export const SK_RX_MASTER_ENABLE_PATH =
  'control.remoteController.rx.masterEnable';
export const SK_RX_LINK_OK_PATH = 'control.remoteController.rx.linkOk';
// "RX link is up and ready" -- any remote source is LIVE at RX, armed or not.
// This is what the status panel's "RX link" row shows. It is deliberately
// NOT rx.linkOk (live+enabled, which tracks the RX board's LED / "a remote is
// armed"): the plugin publishes a steady disarmed heartbeat, so a healthy
// link reads as "up" here even while nothing is armed. Armed state is shown
// separately by the ARM button and the "Control" row.
export const SK_RX_LINK_UP_PATH = 'control.remoteController.rx.linkUp';

// Every path this app subscribes to, for the single subscribe message sent
// after connecting (see skClient.ts).
export const SUBSCRIBE_PATHS = [
  SK_RX_PORT_STATE_PATH,
  SK_RX_STBD_STATE_PATH,
  SK_RX_PORT_SOURCE_PATH,
  SK_RX_STBD_SOURCE_PATH,
  SK_RX_MASTER_ENABLE_PATH,
  SK_RX_LINK_OK_PATH,
  SK_RX_LINK_UP_PATH,
  // Published by the plugin authority; read to know who holds the arm token.
  SK_PLUGIN_ACTIVE_CLIENT_PATH,
  // Published by the plugin authority; read to know whether RX is alive.
  SK_PLUGIN_RX_LIVE_PATH,
  // ... and the same for the heading-hold unit.
  SK_PLUGIN_HH_LIVE_PATH,
  SK_HH_LINK_UP_PATH,
  SK_HH_THRUSTER_STATE_PATH,
  SK_HH_MODE_PATH,
  SK_HH_SOURCE_PATH,
  SK_HH_SETPOINT_PATH,
  SK_HH_ARMED_PATH,
  SK_HH_REVERSAL_PENDING_PATH,
] as const;

// config::kSkPeriodicRefreshMs. TX and RX publish on change plus this
// periodic refresh so a per-source liveness watchdog always has something
// recent to check even during a long steady press; this app matches that
// cadence exactly so RX treats it identically to TX.
export const PERIODIC_REFRESH_MS = 250;

// config::kSkStalenessTimeoutMs -- not enforced here (RX is the one that
// judges staleness), but used to size the reconnect-backoff ceiling below
// so a reconnect attempt can't itself take longer than the timeout RX is
// about to apply to this source.
export const SK_STALENESS_TIMEOUT_MS = 1000;

// How long RX telemetry may go without a fresh delta before this UI stops
// presenting it as live. Mirrors RX_STALE_TIMEOUT_MS in index.cjs so the
// browser and the server call "RX is gone" at the same moment and the UI
// never contradicts the arm gate.
//
// This is the fix for the display half of the switched-off-RX bug: RX
// republishes its whole telemetry set every PERIODIC_REFRESH_MS, so six
// missed refreshes means the board is off, crashed or out of range -- but
// the VALUES (rx.linkUp and friends) stay at their last reading forever, so
// only the age can tell the difference. Erring long here is safe: it delays
// a warning, and arming is gated server-side regardless.
export const RX_TELEMETRY_STALE_MS = 1500;

// How often the UI re-evaluates that age. Staleness is the ABSENCE of
// deltas, so nothing will ever notify us that it happened -- the check has
// to be driven by a clock. Matches the publish cadence, so a link that dies
// is noticed within one refresh of the timeout above.
export const RX_TELEMETRY_POLL_MS = PERIODIC_REFRESH_MS;

// Heading-trim button steps, matching config::kHeadingNudge*Deg. On a phone
// these are four discrete buttons rather than the handheld remote's
// press-and-hold repeat -- a thumb on glass wants a target it can tap, not a
// button it must hold at exactly the right length.
export const HEADING_TRIM_FINE_DEG = 1;
export const HEADING_TRIM_COARSE_DEG = 10;

// Reconnect backoff for the WebSocket connection to the Signal K server,
// not present in the firmware (SensESP's WiFi/SK client handles TX's
// reconnects internally) -- a browser tab needs its own equivalent.
// The FIRST retry lands well inside SK_STALENESS_TIMEOUT_MS, but the cap
// is deliberately above it: while this app is disconnected it publishes
// nothing, so RX's watchdog has already failed this source to NEUTRAL --
// backing off further only delays *recovery* (worst case one cap interval,
// 4 s), it cannot prolong a stale command. A tighter cap would just
// hammer a server that's actually down.
export const RECONNECT_INITIAL_DELAY_MS = 250;
export const RECONNECT_MAX_DELAY_MS = 4000;
