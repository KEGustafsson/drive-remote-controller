#include "heading/yaw_rate.h"

#include <cmath>

#include "heading/angle_math.h"

namespace control_core {

YawRate::YawRate(float spike_threshold_deg_per_tick, float lpf_tau_s,
                 int max_consecutive_rejects)
    : spike_threshold_deg_per_tick_(spike_threshold_deg_per_tick),
      lpf_tau_s_(lpf_tau_s),
      max_consecutive_rejects_(max_consecutive_rejects) {}

float YawRate::Update(float yaw_deg, float dt_s) {
  last_rejected_ = false;

  // Defense in depth: the only production caller (rvc_parse) emits bounded
  // finite values from validated int16 fields, but a NaN here would defeat
  // the spike check below (NaN fails every comparison) and poison
  // filtered_rate_dps_ permanently. Reject it like any other bad sample --
  // without counting toward the resync heuristic, since "resyncing" onto a
  // non-finite yaw would be worse than holding.
  if (!std::isfinite(yaw_deg)) {
    last_rejected_ = true;
    return filtered_rate_dps_;
  }

  if (!has_prev_) {
    has_prev_ = true;
    prev_yaw_deg_ = yaw_deg;
    return filtered_rate_dps_;  // 0 on the very first sample
  }

  float delta_deg = WrapDeg180(yaw_deg - prev_yaw_deg_);

  if (dt_s <= 0.0f || std::fabs(delta_deg) > spike_threshold_deg_per_tick_) {
    last_rejected_ = true;

    // Don't latch: after enough consecutive rejects, the yaw has really
    // moved (fast rotation, or a sensor that jumped and stayed), so resync
    // to it and stop holding a stale rate that would otherwise roll the
    // downstream heading integrator forever. A lone glitch never reaches
    // this count -- the next good sample resets it.
    if (++consecutive_rejects_ >= max_consecutive_rejects_) {
      prev_yaw_deg_ = yaw_deg;
      filtered_rate_dps_ = 0.0f;
      consecutive_rejects_ = 0;
    }
    return filtered_rate_dps_;
  }

  consecutive_rejects_ = 0;
  prev_yaw_deg_ = yaw_deg;
  float raw_rate_dps = delta_deg / dt_s;

  // First-order low-pass: alpha derived from the time constant so the
  // filter behaves consistently across varying dt.
  float alpha = dt_s / (lpf_tau_s_ + dt_s);
  filtered_rate_dps_ += alpha * (raw_rate_dps - filtered_rate_dps_);

  return filtered_rate_dps_;
}

}  // namespace control_core
