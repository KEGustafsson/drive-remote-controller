/**
 * The clock every liveness age in the browser UI is measured against.
 *
 * `performance.now()` is monotonic, which is why it is used instead of
 * `Date.now()`: a phone or laptop correcting its wall clock must not be able to
 * rewind an age and keep an abandoned reading looking fresh.
 *
 * But monotonic is not the same as complete. On the platforms behind
 * `performance.now()` the underlying counter does **not** advance while the
 * device is suspended, so a laptop closed with the UI open wakes with every
 * arrival still scored as young — and the panel reports units as *responding*
 * on data that is minutes stale. That is SAFETY.md invariant 6 exactly: never
 * present unconfirmable data as live.
 *
 * So: monotonic, plus the time the monotonic clock refused to count. Wall time
 * running AHEAD of runtime between two samples is the signature of a suspend,
 * and that difference is added on, which ages every arrival out on the first
 * sample after resume.
 *
 * This mirrors `runtimeNowMs()` in `index.cjs`, deliberately and for the same
 * reason. The plugin is the authority and its version is what stops motion
 * resuming after a server suspend; this one only governs what the operator is
 * *told*. Both are the same three lines because they are answering the same
 * question, and a reader who has understood one should recognise the other.
 *
 * The Android station needs no equivalent: `SystemClock.elapsedRealtime()`
 * counts deep sleep already, unlike `uptimeMillis()`.
 */

/**
 * How far wall time must run ahead of runtime between two samples before we
 * conclude the device was suspended rather than merely busy.
 *
 * Both clocks advance together while the page is running, so the honest skew is
 * ~0 and this only has to clear timer jitter — including the heavy throttling a
 * background tab gets, which delays samples but does not desynchronise the two
 * clocks. It sits below the 1 s staleness budget so any suspend long enough to
 * matter is caught.
 */
export const SUSPEND_SKEW_MS = 500;

let lastRuntimeMs = performance.now();
let lastWallMs = Date.now();
let suspendedMs = 0;

/**
 * Monotonic elapsed milliseconds, including time spent suspended.
 *
 * Never decreases: the offset only ever grows, so a backwards civil correction
 * cannot rewind this clock — the property the whole reason for avoiding
 * `Date.now()` depends on. A large forward clock step is indistinguishable from
 * a suspend and is treated as one; that ages readings out and degrades the
 * display to "not responding", which is the direction to err in.
 */
export function runtimeNowMs(): number {
  const runtime = performance.now();
  const wall = Date.now();
  const skewMs = wall - lastWallMs - (runtime - lastRuntimeMs);
  if (skewMs > SUSPEND_SKEW_MS) suspendedMs += skewMs;
  lastRuntimeMs = runtime;
  lastWallMs = wall;
  return runtime + suspendedMs;
}

/** Test seam: forget any accumulated suspend and re-baseline both samples. */
export function resetRuntimeClockForTests(): void {
  lastRuntimeMs = performance.now();
  lastWallMs = Date.now();
  suspendedMs = 0;
}
