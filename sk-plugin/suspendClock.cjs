// Pure suspend-aware clock -- the same kind of file as arbiter.cjs: no Signal K,
// no Node-server, no timers, no clock reads of its own. The shell samples the
// two clocks and hands them in; every decision about what the pair MEANS is
// here, and is covered by test/suspendClock.test.ts.
//
// WHY THIS IS NOT IN index.cjs. The safety timeouts that evict a vanished
// holder, mark a unit absent and erase its stored command are all measured on
// this clock, so what it counts is command-authority policy, not I/O plumbing.
// AGENTS.md's rule is that anything making a decision lives in the pure,
// host-tested layer; leaving the arithmetic in the shell also meant a direct
// ArmArbiter user -- or a future alternate shell -- could feed it raw
// performance.now() and silently restore command resurrection after host sleep,
// with no test anywhere to notice.
//
// THE PROBLEM IT SOLVES. performance.now() is monotonic, which is why it is used
// instead of Date.now(): a server correcting its wall clock must not be able to
// rewind an age and keep an abandoned command holder alive. But on Linux it is
// CLOCK_MONOTONIC (libuv's uv_hrtime), which STOPS while the host is suspended.
// A server that sleeps with a station armed would wake with every client and
// telemetry arrival still scored as younger than its timeout, so the first tick
// republishes the retained drive/thruster commands and motion resumes with no
// fresh operator intent. RX's own watchdog cannot cover that: it is being fed a
// live command again, which is exactly what it waits for.
//
// So: monotonic, plus the time the monotonic clock refused to count. Wall time
// running AHEAD of runtime between two samples is the signature of a suspend.
//
// The browser UI carries the same logic in src/pure/runtimeClock.ts, for the
// same reason on a device that sleeps -- there it governs only what the operator
// is TOLD (invariant 6), where this one governs whether machinery may move. Two
// copies because the plugin is CJS and the UI is bundled TypeScript with no
// shared build step; they are deliberately the same three lines, and a change to
// one belongs in the other. The Android station needs no equivalent:
// SystemClock.elapsedRealtime() already counts deep sleep.

'use strict';

/**
 * How far wall time must run ahead of elapsed runtime between two samples before
 * we conclude the host was suspended rather than merely busy.
 *
 * Both clocks advance together while the process runs, so the honest skew is ~0
 * and this only has to clear scheduling jitter. It sits well under the 1 s
 * staleness budget so that any suspend long enough to matter is caught.
 */
const SUSPEND_SKEW_MS = 500;

class SuspendAwareClock {
  /**
   * @param {number} runtimeMs first sample of the monotonic clock
   * @param {number} wallMs first sample of the wall clock
   */
  constructor(runtimeMs, wallMs) {
    this._lastRuntimeMs = runtimeMs;
    this._lastWallMs = wallMs;
    /** Total time the monotonic clock did not count. Only ever grows. */
    this._suspendedMs = 0;
  }

  /**
   * Fold in a fresh pair of samples and return the corrected elapsed time.
   *
   * Never decreases. The offset only ever grows, so a backwards civil
   * correction cannot rewind this clock -- the property the whole reason for
   * avoiding Date.now() depends on. A large forward NTP step is
   * indistinguishable from a suspend and is treated as one: that ages every
   * arrival out and fails the arbiter safe, which is the direction to err in
   * when the two clocks cannot be told apart.
   *
   * @param {number} runtimeMs monotonic reading now
   * @param {number} wallMs wall-clock reading now
   * @returns {number} monotonic elapsed ms, including time spent suspended
   */
  nowMs(runtimeMs, wallMs) {
    const skewMs = wallMs - this._lastWallMs - (runtimeMs - this._lastRuntimeMs);
    if (skewMs > SUSPEND_SKEW_MS) this._suspendedMs += skewMs;
    this._lastRuntimeMs = runtimeMs;
    this._lastWallMs = wallMs;
    return runtimeMs + this._suspendedMs;
  }

  /** Total time attributed to suspends so far. Exposed for tests and logging. */
  get suspendedMs() {
    return this._suspendedMs;
  }
}

module.exports = { SuspendAwareClock, SUSPEND_SKEW_MS };
