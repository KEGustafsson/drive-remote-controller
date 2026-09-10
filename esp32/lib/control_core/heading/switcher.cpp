#include "heading/switcher.h"

#include <cmath>

namespace control_core {

namespace {

// Boundary sanitizer for one live tunable (see SetTunables header
// comment): non-finite -> keep the previous (last-good) value; finite ->
// clamp into [lo, hi]. NaN fails every comparison, so it must be handled
// explicitly -- a plain clamp would let it straight through into cfg_.
float Sanitize(float candidate, float previous, float lo, float hi) {
  if (!std::isfinite(candidate)) return previous;
  if (candidate < lo) return lo;
  if (candidate > hi) return hi;
  return candidate;
}

}  // namespace

Switcher::Switcher(const SwitchCfg& cfg) : cfg_(cfg) {}

void Switcher::SetTunables(float on_thr_deg, float off_thr_deg,
                            float lead_time_s, float min_on_s,
                            float max_on_s, float min_off_s,
                            float duty_warn, float duty_max) {
  cfg_.on_thr_deg =
      Sanitize(on_thr_deg, cfg_.on_thr_deg, kOnThrMinDeg, kOnThrMaxDeg);
  cfg_.lead_time_s = Sanitize(lead_time_s, cfg_.lead_time_s, 0.0f, kLeadTimeMaxS);
  cfg_.min_on_s = Sanitize(min_on_s, cfg_.min_on_s, 0.0f, kMinOnOffMaxS);
  cfg_.min_off_s = Sanitize(min_off_s, cfg_.min_off_s, 0.0f, kMinOnOffMaxS);
  cfg_.duty_max = Sanitize(duty_max, cfg_.duty_max, 0.0f, 1.0f);

  // Cross-field relations are enforced on the SANITIZED values, so e.g. a
  // NaN off_thr can't dodge the off<on clamp by failing the comparison.
  float off = Sanitize(off_thr_deg, cfg_.off_thr_deg, kOffThrMinDeg, kOnThrMaxDeg);
  cfg_.off_thr_deg = (off < cfg_.on_thr_deg) ? off : cfg_.on_thr_deg * 0.5f;

  float warn = Sanitize(duty_warn, cfg_.duty_warn, 0.0f, 1.0f);
  cfg_.duty_warn = (warn < cfg_.duty_max) ? warn : cfg_.duty_max * 0.5f;

  // A cap below min_on_s could never fire (min_on gates every exit from
  // thrusting), leaving the runaway case it exists to bound unprotected. Raise
  // it to min_on rather than rejecting the update: the operator asked for the
  // shortest pulse the timing gates can express, and silently keeping a longer
  // previous cap would be the less safe reading of that.
  float max_on = Sanitize(max_on_s, cfg_.max_on_s, kMaxOnMinS, kMaxOnMaxS);
  cfg_.max_on_s = (max_on >= cfg_.min_on_s) ? max_on : cfg_.min_on_s;
}

void Switcher::Reset(uint32_t now_ms, Cmd last_thrust_dir,
                     uint32_t thrust_ended_ms) {
  current_cmd_ = Cmd::kOff;
  last_thrust_dir_ = last_thrust_dir;
  // state_entered_ms_ is "when we entered OFF", which CanLeaveOff measures the
  // min_off/reversal_dwell against. With carried history that is when the
  // thruster actually stopped, so a coast already served counts toward the
  // dwell rather than restarting it.
  state_entered_ms_ =
      (last_thrust_dir == Cmd::kOff) ? now_ms : thrust_ended_ms;
  // The duty clock is a separate concern and always resumes from now, so the
  // first post-reset TrackDuty() sees a sane dt.
  last_update_ms_ = now_ms;
  // duty_ema_ / duty_inhibited_ intentionally preserved -- see header.
}

bool Switcher::CanLeaveThrusting(uint32_t now_ms) const {
  uint32_t elapsed_ms = now_ms - state_entered_ms_;
  return elapsed_ms >= static_cast<uint32_t>(cfg_.min_on_s * 1000.0f);
}

bool Switcher::MaxOnExpired(uint32_t now_ms) const {
  uint32_t elapsed_ms = now_ms - state_entered_ms_;
  return elapsed_ms >= static_cast<uint32_t>(cfg_.max_on_s * 1000.0f);
}

bool Switcher::CanLeaveOff(Cmd requested, uint32_t now_ms) const {
  uint32_t elapsed_ms = now_ms - state_entered_ms_;
  uint32_t required_ms = static_cast<uint32_t>(cfg_.min_off_s * 1000.0f);
  // A reversal (opposite of the direction we most recently left) also
  // has to honor the remote's reversal dead time, on top of min_off.
  if (last_thrust_dir_ != Cmd::kOff && requested != last_thrust_dir_) {
    uint32_t dwell_ms = static_cast<uint32_t>(cfg_.reversal_dwell_s * 1000.0f);
    if (dwell_ms > required_ms) required_ms = dwell_ms;
  }
  return elapsed_ms >= required_ms;
}

void Switcher::TrackDuty(bool thrusting, uint32_t now_ms) {
  float dt_s = (now_ms - last_update_ms_) / 1000.0f;
  last_update_ms_ = now_ms;
  if (dt_s <= 0.0f || cfg_.duty_window_s <= 0.0f) {
    return;
  }

  float instantaneous_on = thrusting ? 1.0f : 0.0f;
  // Exponential moving average approximates a sliding window of length
  // duty_window_s without needing a history buffer (no dynamic
  // allocation in the hot path).
  float alpha = dt_s / (cfg_.duty_window_s + dt_s);
  duty_ema_ += alpha * (instantaneous_on - duty_ema_);

  if (duty_ema_ >= cfg_.duty_max) {
    duty_inhibited_ = true;
  } else if (duty_ema_ < cfg_.duty_warn) {
    duty_inhibited_ = false;
  }
}

void Switcher::UpdateDuty(uint32_t now_ms) {
  TrackDuty(current_cmd_ != Cmd::kOff, now_ms);
}

Cmd Switcher::Update(float e_deg, float r_dps, uint32_t now_ms) {
  UpdateDuty(now_ms);

  float s = e_deg - cfg_.lead_time_s * r_dps;

  // Widen the deadband as duty climbs from duty_warn to duty_max: less
  // authority, fewer pulses, protects the S2-rated thruster (Sec 4.4).
  float scale = 1.0f;
  if (cfg_.duty_max > cfg_.duty_warn) {
    float t = (duty_ema_ - cfg_.duty_warn) / (cfg_.duty_max - cfg_.duty_warn);
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;
    scale = 1.0f + t;  // up to 2x at duty_max
  }
  float on_thr = cfg_.on_thr_deg * scale;
  float off_thr = cfg_.off_thr_deg * scale;

  Cmd requested = current_cmd_;

  if (current_cmd_ == Cmd::kOff) {
    // Only place a new direction can be requested from.
    if (!duty_inhibited_) {
      if (s > on_thr) {
        requested = Cmd::kStbd;
      } else if (s < -on_thr) {
        requested = Cmd::kPort;
      }
    }
  } else if (MaxOnExpired(now_ms)) {
    // Pulse cap (max_on_s): this thrust has run its full allowance and ends
    // whatever the lead variable says. Distinct from the release below, which
    // is the control law deciding it has done enough -- this one fires
    // precisely when the control law would NOT release, because s is still
    // beyond off_thr. That is the runaway case (see max_on_s in switcher.h):
    // the boat is not answering the helm, and continuing to hold the motor on
    // neither turns it nor lets the S2 duty limiter get a word in. Going OFF
    // hands the decision back to the normal min_off / duty-inhibition gate,
    // which either pulses again or refuses on thermal grounds.
    requested = Cmd::kOff;
  } else if ((current_cmd_ == Cmd::kStbd && s < off_thr) ||
             (current_cmd_ == Cmd::kPort && s > -off_thr)) {
    // Once the lead variable crosses the release threshold for the direction
    // being driven, go OFF. This is intentionally direction-aware: a sample
    // can jump straight past the deadband to a large opposite-sign value, and
    // continuing the old direction there would drive the error farther away.
    // The opposite direction is still never selected directly; a later call
    // must leave OFF through the normal min-off/reversal-dwell gate.
    requested = Cmd::kOff;
  }

  if (requested != current_cmd_) {
    bool allowed = (current_cmd_ == Cmd::kOff)
                       ? CanLeaveOff(requested, now_ms)
                       : CanLeaveThrusting(now_ms);
    if (allowed) {
      if (current_cmd_ != Cmd::kOff) {
        last_thrust_dir_ = current_cmd_;
      }
      current_cmd_ = requested;
      state_entered_ms_ = now_ms;
    }
    // else: timing veto -- hold the current command, re-evaluate next tick.
  }

  return current_cmd_;
}

}  // namespace control_core
