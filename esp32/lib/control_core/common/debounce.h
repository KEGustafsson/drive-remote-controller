#pragma once

// Pure C++17 two-sided debouncer. No Arduino.h -- host-testable under
// env:native. Lives in common/ because ALL THREE firmwares use it; it is the
// one pure module shared across the drive/ and heading/ families.
//
// Used on every physical switch/contact read: TX's 2 shift switches +
// enable switch, RX's 2 local shift switches + master enable switch, and
// HH's ENGAGE opto input. The raw contact level must be stably ASSERTED for
// assert_stable_ms before the debounced state rises, and stably RELEASED
// for release_stable_ms before it falls -- so a noisy contact that flickers
// can't produce a phantom edge in either direction. Any bounce back to the
// current state restarts the pending transition from zero.
//
// SAFETY-RELEVANT on the HH side: SafetyFsm::Update() acts on ENGAGE
// *edges* (a rising edge requests hold) and FsmInputs documents engage as
// "debounced by caller" -- this is that debounce. A noisy contact must not
// produce a phantom rising edge (unintended hold request) nor a phantom
// falling edge followed by a real-looking rise that silently re-arms hold.
//
// This is contact-bounce settling, NOT a dwell / dead time. The drive
// (TX/RX) side deliberately has no dwell at all (SAFETY.md drive
// invariant 2), and on the HH side the release delay eats
// directly into "manual authority dominates" (SAFETY.md thruster invariant 6). Keep the
// stable periods short in both -- tens of ms (config::kSwitchAssertStableMs
// / kEngageAssertStableMs): long enough to bridge contact bounce, far too
// short to matter against human reaction time.

#include <cstdint>

namespace control_core {

class Debounce {
 public:
  // Stable periods in ms; 0 passes the raw input through on that side.
  Debounce(uint32_t assert_stable_ms, uint32_t release_stable_ms,
           bool initial = false)
      : assert_stable_ms_(assert_stable_ms),
        release_stable_ms_(release_stable_ms),
        state_(initial) {}

  // Feed the raw sampled level at monotonic time now_ms; returns the
  // debounced state. Call at the sampling rate (every control tick) --
  // stability is judged by consecutive calls agreeing for long enough,
  // so a gap in calls can't fabricate stability that wasn't observed.
  bool Update(bool raw, uint32_t now_ms) {
    if (raw == state_) {
      // Bounce back to the current state: cancel any pending transition.
      pending_ = false;
      return state_;
    }
    if (!pending_) {
      pending_ = true;
      pending_since_ms_ = now_ms;
    }
    uint32_t required_ms = raw ? assert_stable_ms_ : release_stable_ms_;
    if (now_ms - pending_since_ms_ >= required_ms) {
      state_ = raw;
      pending_ = false;
    }
    return state_;
  }

  bool state() const { return state_; }

 private:
  const uint32_t assert_stable_ms_;
  const uint32_t release_stable_ms_;

  bool state_;
  bool pending_ = false;
  uint32_t pending_since_ms_ = 0;
};

}  // namespace control_core
