#pragma once

// Pure C++17 direct-human-control gate for the bow thruster. No Arduino.h --
// host-testable under env:native. This is what runs INSTEAD of the bang-bang
// Switcher when ThrusterMode::kManual is in force: the operator's button IS
// the direction, so there is no error, no lead term, no deadband and no
// control law at all here.
//
// WHAT IS DELIBERATELY ABSENT (owner requirement): no min-on time, no min-off
// time, no anti-chatter, no dwell of any kind on ordinary press/release. A
// human is holding the button and watching the boat; the thrust must start
// when they press and stop when they let go, exactly like the drives' shift
// switches. Adding a minimum-hold timer here would be the same mistake as
// adding one to the drives -- worse, actually, because it
// would keep thrusting after the operator has decided to stop.
//
// THE REVERSAL DWELL IS OPTIONAL HERE, AND SHIPS AT ZERO. The thruster's own
// control box enforces an anti-reversal interlock, measured at ~1.75 s
// (MEASUREMENTS.md HH Item 2), during which it will not accept the opposite
// direction whatever the firmware asks for. This gate can either wait that out
// itself or hand the request straight through and let the box refuse it.
//
// The owner's decision (2026-07-24) is to hand it through:
// config::kManualReversalDwellS = 0. A human is holding the button and watching
// the tunnel, the box protects the contactor either way, and a firmware-imposed
// wait made the control feel dead at exactly the moment it is needed. So a
// straight port->stbd flick passes through OFF for one tick (the structural
// interlock below) and then asserts the opposite direction immediately.
//
// This is the one place the two modes deliberately differ: HOLD keeps the full
// measured dwell (config::kReversalDwellS) because the bang-bang Switcher runs
// with nobody watching. Set a non-zero dwell here and this class enforces it
// exactly as it always did -- including across a mode change, since ControlStep
// seeds both gates from one shared last-thrust history (control_step.cpp).
//
// The direction transition is also structurally interlocked, exactly as in
// Switcher: the only way out of thrusting is through kOff, so this class can
// never emit a direct port->stbd transition and can never ask the output
// driver to assert both lines (SAFETY.md thruster invariant 1).

#include <cstdint>

#include "heading/switcher.h"  // control_core::Cmd

namespace control_core {

class ManualThrust {
 public:
  // reversal_dwell_s: how long this gate itself withholds a reversal. Zero
  // means "ask as soon as the operator does, and let the control box's own
  // interlock be the only delay" -- which is what the firmware passes
  // (config::kManualReversalDwellS). A non-zero value is honoured in full.
  explicit ManualThrust(float reversal_dwell_s);

  // requested: what the operator is asking for right now (kOff when no button
  // is held). now_ms: monotonic. Returns the direction that may actually be
  // driven this tick.
  //
  // Release is ALWAYS honoured immediately: a requested kOff returns kOff on
  // the same tick, in every state, with no timer able to defer it. Stopping
  // must never be something the firmware makes the operator wait for.
  Cmd Update(Cmd requested, uint32_t now_ms);

  // Start a new manual session (entering kManual, or re-arming), carrying the
  // thrust history that actually happened.
  //
  // last_thrust_dir / thrust_ended_ms describe the last direction the THRUSTER
  // was physically driven in and when that stopped -- whichever gate produced
  // it. Passing kOff means "nothing to reverse from", so the first thrust is
  // never delayed. Callers that genuinely have no history use the one-argument
  // form.
  //
  // Why history is carried rather than cleared: the control box's interlock is
  // a property of the box, and it has no idea which software gate commanded the
  // previous thrust. A reversal 50 ms after a hold-driven thrust is the same
  // physical event as one 50 ms after a manual thrust. Clearing the history on
  // a mode change let a two-tap mode flip walk straight past the dwell (see
  // control_step.cpp and test_control_step's cross-mode cases). Each gate still
  // applies its OWN dwell to that shared history, which is what keeps the two
  // modes' deliberately different policies intact.
  void Reset(uint32_t now_ms, Cmd last_thrust_dir, uint32_t thrust_ended_ms);
  void Reset(uint32_t now_ms) { Reset(now_ms, Cmd::kOff, now_ms); }

  Cmd current() const { return current_; }

  // True when a direction is being WITHHELD purely because the reversal dwell
  // has not expired -- i.e. the operator is asking for the opposite direction
  // and is being made to wait out the control box's interlock. Telemetry only,
  // so the UI can say "reversing..." instead of looking unresponsive.
  bool reversal_pending() const { return reversal_pending_; }

 private:
  const uint32_t dwell_ms_;

  Cmd current_ = Cmd::kOff;
  // The direction most recently left. kOff means "nothing to reverse from",
  // so the first thrust after a Reset is never delayed.
  Cmd last_thrust_dir_ = Cmd::kOff;
  uint32_t off_since_ms_ = 0;
  bool reversal_pending_ = false;
};

}  // namespace control_core
