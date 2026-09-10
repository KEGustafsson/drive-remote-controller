#pragma once

// Pure C++17 liveness watchdog. No Arduino.h -- host-testable under
// env:native.
//
// SAFETY-RELEVANT (SAFETY.md): tracks whether a command source (TX-via-SK,
// plugin-via-SK) is currently "live" purely from time-since-last-accepted-
// update. This is deliberately NOT a monotonic-counter comparison such as
// `counter_in > counter_in_prev`: with a counter, a source that reboots (its
// counter resetting to 0) stays reported "not live" until the counter climbs
// back past whatever value was last seen, potentially minutes later. Judging
// on time instead, ANY accepted update -- whatever value it carries, wherever
// a sequence number left off -- makes the source live again immediately.

#include <cstdint>

namespace control_core {

class LinkWatchdog {
 public:
  // Call once per accepted update from the source (e.g. each time a fresh
  // SK delta arrives on that source's command path), with the current
  // monotonic time. The update's *content* is irrelevant here -- only that
  // something new arrived is being recorded.
  void Update(uint32_t now_ms) {
    last_update_ms_ = now_ms;
    has_update_ = true;
  }

  // Live iff at least one update has been recorded, and the most recent one
  // happened within timeout_ms of now. A source that has never updated at all
  // is NOT live -- "no data yet" must read as not-live, never as a
  // coincidental pass before any real data has arrived.
  //
  // Uses unsigned wraparound-safe subtraction, matching the millis()
  // convention used throughout this project. That idiom is only correct while
  // the true gap since the last update is under 2^32 ms (~49.7 days): a source
  // silent for longer -- a TX switched off and left in a drawer while RX stays
  // powered on the house bank -- would see its age wrap back to a small number
  // and read LIVE again for one whole timeout window, resurrecting whatever
  // command and enable flag it last published. To close that, a stale verdict
  // is LATCHED: once this returns false for a recorded update, that update is
  // forgotten (as if it never arrived) and only a fresh Update() can make the
  // source live again. Every caller polls at tens of Hz, so the latch always
  // happens within one timeout of the last update, long before any wrap.
  //
  // Not const, for exactly that reason; a query that can change the verdict
  // is a state change and is spelled as one.
  bool IsLive(uint32_t now_ms, uint32_t timeout_ms) {
    if (!has_update_) return false;
    if ((now_ms - last_update_ms_) <= timeout_ms) return true;
    has_update_ = false;  // stale: latch, so a clock wrap cannot revive it
    return false;
  }

  // True while a recorded update is still being counted -- i.e. the source has
  // updated, and IsLive() has not yet found that update stale.
  bool HasEverUpdated() const { return has_update_; }
  // The most recent recorded update time. Diagnostic only; it is retained
  // across the stale latch above, so it keeps answering "when did we last hear
  // from this source" after IsLive() has stopped counting that update.
  uint32_t LastUpdateMs() const { return last_update_ms_; }

 private:
  uint32_t last_update_ms_ = 0;
  bool has_update_ = false;
};

}  // namespace control_core
