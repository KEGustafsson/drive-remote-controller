#pragma once

// Pure C++17 safety state machine (SAFETY.md). No Arduino.h --
// host-testable under env:native.
//
// SAFETY-CRITICAL: this is where SAFETY.md thruster invariants 3 ("ENABLE =
// arm/active state"), 4 (IMU silence -> FAULT), and 6 (manual authority
// dominates) get decided. It owns no sensors and no GPIOs -- it only turns
// a snapshot of already-evaluated inputs into a state. The caller (the
// control task) is responsible for: reading ENGAGE/deadman, checking
// RvcReader::timedOut(), judging whether the latest SK heading is good
// enough to arm on (present + fresh + quality + plausible -- same "caller
// already judged it" boundary as control_core::GnssHeading::valid), and
// driving Outputs from the resulting FsmState.
//
// States: DISARMED (engage/deadman not both asserted) -> ARMED_IDLE
// (engaged, BNO healthy, but not actively holding) -> HOLDING (actively
// steering) -> FAULT (BNO silent). See state table in safety_fsm.cpp.
//
// Design choice worth stating explicitly: after any forced
// exit from HOLDING (disarm, fault, or a GNSS coast-max disengage), does
// the FSM silently resume holding as soon as conditions recover, or does it
// require a fresh ENGAGE press first? This implementation requires a fresh
// press (a rising edge on `engage`) -- an operator who watched the system
// give up holding (GNSS lost too long, BNO died, they let go of engage)
// should see a deliberate "it's idle now" state and consciously re-arm,
// rather than thrust silently resuming mid-maneuver once some sensor comes
// back. The one case that DOES auto-promote without a fresh edge is the
// very first arm attempt with no heading yet available: holding `engage`
// through ARMED_IDLE while waiting for the first fix is not a "recovery
// from interruption," nothing was ever holding to interrupt.

#include <cstdint>

namespace control_core {

enum class FsmState { kDisarmed, kArmedIdle, kHolding, kFault };

// ARCHITECTURE.md §11 defaults (T_coast_warn / T_coast_max).
struct FsmCfg {
  uint32_t coast_warn_ms = 10000;
  uint32_t coast_max_ms = 30000;
};

struct FsmInputs {
  bool engage = false;      // ENGAGE line asserted (debounced by caller)
  bool deadman_ok = true;   // true if no deadman wired/required, or held
  bool bno_ok = false;      // !RvcReader::timedOut()
  // Present + fresh + quality + plausible, judged by the caller right now
  // (SAFETY.md rule 4: "arm only on a good reference").
  bool heading_ok_to_arm = false;
  // Age of the latest accepted GNSS fix, for the coast warn/max timers
  // while HOLDING. Irrelevant in other states.
  uint32_t heading_age_ms = 0;
};

class SafetyFsm {
 public:
  explicit SafetyFsm(const FsmCfg& cfg);

  FsmState Update(const FsmInputs& in);

  FsmState state() const { return state_; }

  // One-shot: true only on the tick Update() transitions INTO kHolding --
  // the caller's cue to capture a fresh setpoint (the setpoint is
  // captured at engage). Always freshly captured, never a stale resume.
  bool JustEnteredHolding() const { return just_entered_holding_; }

  // True while HOLDING with GNSS age past coast_warn_ms (but not yet
  // coast_max_ms). Telemetry only; does not change state.
  bool CoastWarning() const { return coast_warning_; }

 private:
  const FsmCfg cfg_;
  FsmState state_ = FsmState::kDisarmed;
  bool prev_engage_ = false;
  bool hold_requested_ = false;
  bool just_entered_holding_ = false;
  bool coast_warning_ = false;
};

}  // namespace control_core
