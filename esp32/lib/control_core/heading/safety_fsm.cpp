#include "heading/safety_fsm.h"

namespace control_core {

SafetyFsm::SafetyFsm(const FsmCfg& cfg) : cfg_(cfg) {}

FsmState SafetyFsm::Update(const FsmInputs& in) {
  just_entered_holding_ = false;

  bool engage_rising_edge = in.engage && !prev_engage_;
  prev_engage_ = in.engage;
  if (engage_rising_edge) {
    hold_requested_ = true;
  }

  // Manual authority dominates (invariant 6): released engage/deadman
  // always wins, regardless of any other input.
  if (!in.engage || !in.deadman_ok) {
    state_ = FsmState::kDisarmed;
    hold_requested_ = false;
    coast_warning_ = false;
    return state_;
  }

  // Local gyro silence is an unconditional fail-off (invariant 4).
  if (!in.bno_ok) {
    state_ = FsmState::kFault;
    hold_requested_ = false;
    coast_warning_ = false;
    return state_;
  }

  // Engaged, deadman ok, BNO healthy -> at least armed/coasting.
  if (state_ == FsmState::kDisarmed || state_ == FsmState::kFault) {
    state_ = FsmState::kArmedIdle;
  }

  if (state_ == FsmState::kArmedIdle) {
    coast_warning_ = false;
    if (hold_requested_ && in.heading_ok_to_arm) {
      state_ = FsmState::kHolding;
      just_entered_holding_ = true;
    }
  } else if (state_ == FsmState::kHolding) {
    if (in.heading_age_ms > cfg_.coast_max_ms) {
      // Graceful lost-lock (SAFETY.md rule 6): disengage cleanly to idle
      // rather than keep steering on a stale reference. Requires a fresh
      // engage edge to resume -- see header comment.
      state_ = FsmState::kArmedIdle;
      hold_requested_ = false;
      coast_warning_ = false;
    } else {
      coast_warning_ = in.heading_age_ms > cfg_.coast_warn_ms;
    }
  }

  return state_;
}

}  // namespace control_core
