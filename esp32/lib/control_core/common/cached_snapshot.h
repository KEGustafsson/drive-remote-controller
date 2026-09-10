#pragma once

// Ageing a cached remote snapshot -- how long a copy taken on an earlier tick
// may keep speaking for its source.
//
// WHY THIS IS IN THE PURE CORE. Both control tasks take their remote commands
// with a NON-BLOCKING snapshot, so a tick that collides with a listener callback
// gets nothing and falls back to the copy it already holds (SAFETY.md drive
// invariant 7 / thruster invariant 5). Falling back is right -- a momentary
// callback collision is not a link loss -- but the fallback decides how long
// cached authority survives, and "how long may this still command the machine"
// is a safety rule, not glue. It was written out by hand six times across RX and
// HH; one shared, Unity-tested answer is what stops the drive and thruster paths
// drifting apart the next time a timeout or the wraparound handling changes.
//
// Deliberately a template rather than an interface: the drive family carries
// control_core::RemoteCommand and the thruster family control_core::
// ThrusterRemote, they share no base, and neither should grow one just to be
// aged. Anything with `live` and `last_update_ms` fits.

#include <stdint.h>

namespace control_core {

/**
 * Expire a cached snapshot that has outlived its source's timeout.
 *
 * Only ever CLEARS liveness -- a cached copy can go stale but can never come
 * back to life without a fresh snapshot, because nothing here has any new
 * evidence to offer. That one-way property is what makes it safe to apply on
 * every contended tick.
 *
 * Unsigned arithmetic is load-bearing: `now_ms - last_update_ms` on uint32_t
 * gives the correct elapsed time across the ~49.7-day millis() rollover, where
 * comparing the two timestamps directly would read a wrapped clock as an
 * enormous age and drop a healthy source. Same idiom as LinkWatchdog, and the
 * reason this is written once.
 *
 * @param snapshot cached copy, updated in place
 * @param now_ms current monotonic time
 * @param timeout_ms how long a copy may speak for its source
 */
template <typename Snapshot>
void AgeCachedSnapshot(Snapshot& snapshot, uint32_t now_ms,
                       uint32_t timeout_ms) {
  if (!snapshot.live) return;
  snapshot.live = (now_ms - snapshot.last_update_ms) <= timeout_ms;
}

}  // namespace control_core
