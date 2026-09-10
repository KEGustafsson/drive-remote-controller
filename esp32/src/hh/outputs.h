#pragma once

// ENABLE/PORT/STBD GPIO driver. All safety logic --
// never-both-directions, directions-inert-unless-armed -- lives in
// control_core::ComputeOutputLevels() (lib/control_core/output_map.h,
// host-tested exhaustively). This class only stores the last-requested
// (armed, cmd) pair, recomputes levels through that pure function on every
// change, and writes the three GPIOs (applying config::kOutputActiveHigh).

#include "heading/switcher.h"  // control_core::Cmd
#include "freertos/FreeRTOS.h"

class Outputs {
 public:
  // Configures the three pins as outputs and drives the fully-safe state
  // (disarmed, all off) immediately -- call once from setup().
  void begin();

  // Atomic (armed, dir) write: one call, one pin update, so the
  // armed/direction invariant can never be split across two calls with an
  // unsafe intermediate state. armed comes from the FSM's arm state
  // (ARMED_IDLE/HOLDING = true); dir is forced to all-low by
  // ComputeOutputLevels() whenever !armed, regardless of what's passed
  // here (SAFETY.md thruster invariants 2/3). When !armed the stored direction is
  // also cleared, so re-arming always starts from coast, never a stale
  // direction.
  void apply(bool armed, control_core::Cmd dir);

  // Fail-safe reset: ENABLE=0, PORT=0, STBD=0. Also the one method invoked
  // cross-task: the fail-off watchdog (control_task.cpp, esp_timer task on
  // core 0) calls it once the control task's heartbeat has gone stale.
  //
  // On the race with the owning task: "heartbeat stale" is evidence the
  // control task was not running recently, NOT a guarantee it cannot resume
  // mid-callback -- a task blocked for 101 ms then rescheduled will. So the
  // two can interleave, and the honest statement of what survives that is:
  // every write here and in apply() routes through ComputeOutputLevels() with
  // a single Cmd value, which cannot express two directions, so the
  // never-both invariant (SAFETY.md thruster invariant 1) holds regardless of
  // interleaving. The worst case is a lost fail-off write immediately
  // reasserted by the recovering task's own FSM-derived (and therefore safe)
  // state, or vice versa -- and the watchdog repeats every check period, so a
  // genuinely dead task is driven off within one more period either way.
  void allOff();

 private:
  void ApplyLocked();

  // apply() runs on the pinned control task while allOff() runs on the timer
  // service task. Serialize both state and GPIO writes so a recovering control
  // tick cannot interleave individual pins with the watchdog's fail-off write.
  portMUX_TYPE mux_ = portMUX_INITIALIZER_UNLOCKED;

  bool armed_ = false;
  control_core::Cmd dir_ = control_core::Cmd::kOff;
};
