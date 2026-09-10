#include "heading/manual_thrust.h"

namespace control_core {
namespace {

// Seconds -> milliseconds, guarding against a negative/garbage config value
// turning into a huge unsigned dwell (which would silently make reversals
// impossible rather than merely slow).
uint32_t DwellMs(float seconds) {
  if (!(seconds > 0.0f)) return 0;  // also catches NaN
  const float ms = seconds * 1000.0f;
  if (ms > 60000.0f) return 60000;  // a minute is already absurd; clamp
  return static_cast<uint32_t>(ms);
}

}  // namespace

ManualThrust::ManualThrust(float reversal_dwell_s)
    : dwell_ms_(DwellMs(reversal_dwell_s)) {}

void ManualThrust::Reset(uint32_t now_ms, Cmd last_thrust_dir,
                         uint32_t thrust_ended_ms) {
  current_ = Cmd::kOff;
  last_thrust_dir_ = last_thrust_dir;
  // With no history to carry, the dwell is measured from now (nothing to
  // reverse from anyway). With history, it is measured from when the thruster
  // actually stopped, so time already spent coasting counts toward it.
  off_since_ms_ =
      (last_thrust_dir == Cmd::kOff) ? now_ms : thrust_ended_ms;
  reversal_pending_ = false;
}

Cmd ManualThrust::Update(Cmd requested, uint32_t now_ms) {
  reversal_pending_ = false;

  // Release, always immediate. Checked first so no path below can defer it.
  if (requested == Cmd::kOff) {
    if (current_ != Cmd::kOff) {
      last_thrust_dir_ = current_;
      off_since_ms_ = now_ms;
      current_ = Cmd::kOff;
    }
    return current_;
  }

  // Already driving what was asked for -- nothing to time, nothing to change.
  if (current_ == requested) return current_;

  // Driving the OPPOSITE direction and the operator has flicked across. Go
  // through kOff on this tick; the dwell is then measured from here. This is
  // the structural interlock: a direct direction->direction transition is not
  // expressible, so the output driver can never be asked for both lines.
  if (current_ != Cmd::kOff) {
    last_thrust_dir_ = current_;
    off_since_ms_ = now_ms;
    current_ = Cmd::kOff;
    reversal_pending_ = true;
    return current_;
  }

  // Currently off, a direction is wanted. Immediate unless this is a reversal
  // still inside the control box's interlock window.
  const bool is_reversal =
      last_thrust_dir_ != Cmd::kOff && last_thrust_dir_ != requested;
  if (is_reversal && (now_ms - off_since_ms_) < dwell_ms_) {
    reversal_pending_ = true;
    return current_;  // kOff -- the thruster coasts out the interlock
  }

  current_ = requested;
  return current_;
}

}  // namespace control_core
