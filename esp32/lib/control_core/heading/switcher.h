#pragma once

// Pure C++17 bang-bang lead/Schmitt switching core (ARCHITECTURE.md §7).
// No Arduino.h -- host-testable under env:native.
//
// SAFETY-CRITICAL: this is the only place that decides PORT vs STBD vs
// OFF. It is structurally impossible for Update() to return a direction
// directly from the opposite direction -- while thrusting, the only exit
// is to kOff (see Update()); a new direction can only be requested from
// kOff. That single property is what lets the output driver
// trust it will never be asked to assert both directions at once.

#include <cstdint>

namespace control_core {

enum class Cmd { kOff, kPort, kStbd };

// Tunable thresholds. Angle thresholds in degrees, times in seconds,
// duty as a 0-1 fraction of an approximate sliding time window.
struct SwitchCfg {
  float on_thr_deg = 3.0f;
  float off_thr_deg = 1.0f;       // must be < on_thr_deg (Schmitt deadband)
  float lead_time_s = 1.0f;       // Td: s = e - Td * r
  float min_on_s = 0.3f;          // stay thrusting at least this long once on
  // Hard ceiling on ONE continuous thrust, regardless of the lead variable.
  // Without it the only exit from thrusting is s crossing off_thr, so a boat
  // that CANNOT turn -- pinned by wind or current, a fouled thruster, a dead
  // or wrong-signed yaw rate -- leaves s stuck at e and the motor running
  // indefinitely. Duty inhibition does not save that case: duty_inhibited_ is
  // only consulted when leaving OFF, so it can block the next ON but can
  // never interrupt one in progress. Capping the pulse is what gives the S2
  // duty limiter an OFF window to act in, and turns a continuous burn under
  // sustained load into a pulse train. Must be >= min_on_s to be reachable
  // (SetTunables enforces it); min_on_s remains the absolute floor, so the
  // anti-chatter guarantee holds even if a hand-built cfg inverts the two.
  float max_on_s = 2.0f;          // ...and never longer than this
  float min_off_s = 0.5f;         // stay off at least this long once off
  float reversal_dwell_s = 0.5f;  // extra OFF time before the OPPOSITE dir
  float duty_warn = 0.5f;         // duty above which the deadband widens
  float duty_max = 0.7f;          // duty above which new ONs are inhibited
  float duty_window_s = 60.0f;    // approx. sliding-window length for duty
};

class Switcher {
 public:
  explicit Switcher(const SwitchCfg& cfg);

  // e_deg: heading error (setpoint - fused), wrapped to (-180,180].
  // r_dps: filtered yaw rate, deg/s. now_ms: monotonic sample time.
  Cmd Update(float e_deg, float r_dps, uint32_t now_ms);

  // Reset the switching state machine to an OFF start: current command and the
  // min-on/min-off/reversal-dwell timers. Call when a NEW hold session begins
  // (SafetyFsm::JustEnteredHolding, or the mode switching into hold).
  //
  // last_thrust_dir / thrust_ended_ms carry the direction the THRUSTER was
  // last physically driven in and when that stopped -- whichever gate produced
  // it, this one or ManualThrust. The reversal dwell is then measured against
  // real hardware history instead of being cleared: the control box's interlock
  // does not care which gate commanded the previous thrust, and clearing it let
  // a manual thrust followed by a mode switch reverse in min_off (0.5 s)
  // instead of the measured dwell. Pass kOff when there is genuinely nothing to
  // reverse from; the one-argument form does that.
  //
  // Duty accounting (duty_ema_ / inhibition) is deliberately PRESERVED: S2
  // thermal protection must not be defeatable by releasing and re-pressing
  // engage. now_ms seeds the duty clock so the first post-reset duty step sees
  // a sane dt, not the whole idle gap since the last Update().
  void Reset(uint32_t now_ms, Cmd last_thrust_dir, uint32_t thrust_ended_ms);
  void Reset(uint32_t now_ms) { Reset(now_ms, Cmd::kOff, now_ms); }

  // Bounds enforced by SetTunables() on every live value before it can
  // reach cfg_. Public so the config UI / tests can reference the same
  // limits. The time bounds are generous sea-trial ceilings, not tuning
  // advice -- their job is to keep a corrupted or fat-fingered persisted
  // value from turning into an hour-long lockout or a 10^30-degree
  // threshold, not to define sensible values.
  static constexpr float kOnThrMinDeg = 0.1f;
  static constexpr float kOnThrMaxDeg = 45.0f;
  static constexpr float kOffThrMinDeg = 0.05f;
  static constexpr float kLeadTimeMaxS = 10.0f;
  static constexpr float kMinOnOffMaxS = 30.0f;
  // The cap is a protection limit, so its own ceiling is deliberately far
  // tighter than kMinOnOffMaxS: a "generous" 30 s max-on would be no
  // protection at all for an S2-rated motor. 0 is not offered -- the cap
  // cannot be disabled, only lengthened within this bound.
  static constexpr float kMaxOnMinS = 0.1f;
  static constexpr float kMaxOnMaxS = 10.0f;

  // Live-update the non-safety-critical tuning knobs (ARCHITECTURE.md §11: on_thr,
  // off_thr, lead_time/Td, min_on, max_on, min_off, duty_warn, duty_max) without
  // resetting switching state or duty memory -- for sea-trial tuning from
  // the web UI while a hold may be in progress. Deliberately does NOT touch
  // reversal_dwell_s or duty_window_s: reversal_dwell is the measured
  // MEASUREMENTS.md safety value (SAFETY.md thruster invariant 7) and stays
  // fixed at construction; duty_window_s defines what the already-
  // accumulated duty_ema_ means, so changing it live would silently
  // reinterpret history.
  //
  // These values arrive from flash-persisted, web-editable settings, so
  // every one of them is treated as untrusted at this boundary: a
  // non-finite value (NaN/inf) is REJECTED and the previous (last-good or
  // constructed-default) value kept; a finite value is clamped into the
  // bounds above (angles positive and bounded, times non-negative and
  // bounded, duty fractions within [0,1]). Cross-field relations are then
  // re-enforced on the sanitized values: off_thr_deg is clamped below
  // on_thr_deg so the Schmitt no-chatter guarantee (off_thr < on_thr) can
  // never be violated, duty_warn is clamped below duty_max the same way, and
  // max_on_s is raised to min_on_s if it would otherwise sit below it (an
  // unreachable cap is a cap that does not protect). See test_switcher's
  // set_tunables_* cases.
  void SetTunables(float on_thr_deg, float off_thr_deg, float lead_time_s,
                    float min_on_s, float max_on_s, float min_off_s,
                    float duty_warn, float duty_max);

  // Advance the S2 duty accounting from the ACTUAL thrust state, whatever
  // is producing it. Update() already does this internally for HOLD mode;
  // this exists so the caller can keep the duty model honest on every other
  // tick too -- decaying while disarmed/idle (a thruster that has sat off
  // for ten minutes has genuinely cooled; freezing the EMA would leave a
  // duty_max inhibition standing forever and read as a stuck fault), and
  // accumulating during MANUAL thrust (a heavy manual session heats the
  // motor exactly like a held one, so re-entering HOLD must start from the
  // real figure, not an understated frozen one). Calling it in the same
  // tick as Update() is a harmless no-op (dt == 0).
  void TrackDuty(bool thrusting, uint32_t now_ms);

  float duty() const { return duty_ema_; }
  Cmd current_cmd() const { return current_cmd_; }

 private:
  bool CanLeaveOff(Cmd requested, uint32_t now_ms) const;
  bool CanLeaveThrusting(uint32_t now_ms) const;
  // Has the current thrust used up its max_on_s allowance? Measured from
  // state_entered_ms_, the same clock CanLeaveThrusting uses, so the floor and
  // the ceiling are always talking about the same pulse.
  bool MaxOnExpired(uint32_t now_ms) const;
  void UpdateDuty(uint32_t now_ms);

  SwitchCfg cfg_;

  Cmd current_cmd_ = Cmd::kOff;
  Cmd last_thrust_dir_ = Cmd::kOff;  // direction we most recently left
  uint32_t state_entered_ms_ = 0;
  uint32_t last_update_ms_ = 0;

  float duty_ema_ = 0.0f;
  bool duty_inhibited_ = false;
};

}  // namespace control_core
