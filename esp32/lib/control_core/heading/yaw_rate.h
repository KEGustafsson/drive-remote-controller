#pragma once

// Pure C++17 yaw-rate estimator: wrap-safe differentiation, spike
// rejection, and a low-pass filter (ARCHITECTURE.md §7). No Arduino.h --
// host-testable under env:native.

namespace control_core {

class YawRate {
 public:
  // spike_threshold_deg_per_tick: reject a sample-to-sample delta larger
  // than this (mag-glitch guard).
  // lpf_tau_s: low-pass filter time constant for the rate. This is the
  // dominant source of fused-heading lag, and it costs exactly what it
  // says: filtering the rate with time constant tau and then integrating
  // it makes fused/true a first-order lag of the same tau, whose
  // steady-state error under a constant turn rate is precisely
  // -tau * rate. Measured on the water 2026-08-20 at the old 0.3 s: the
  // fused heading sat 0.24-0.30 s behind navigation.headingTrue (two
  // independent estimates -- cross-correlation, and regressing the error
  // against the turn rate), i.e. ~1.2 deg behind at the ~3.9 deg/s the bow
  // can be swung by hand. Lowered to 0.1 s because the noise this filter
  // exists to suppress turned out to be far smaller than assumed: the
  // BNO086 RVC yaw rate measures 0.034 deg/s rms at rest, so 0.1 s still
  // leaves ~0.06 deg/s, which contributes 0.06 deg to the Switcher's lead
  // variable (Td = 1.0 s) against a 2.0 deg on-threshold. Do not raise
  // this without re-measuring that noise floor.
  // max_consecutive_rejects: how many rejects in a row before we conclude
  // the yaw has genuinely moved (real fast motion / a sensor that jumped
  // and stayed there) rather than a one-tick glitch, and RESYNC to it
  // instead of latching. See Update().
  explicit YawRate(float spike_threshold_deg_per_tick = 2.0f,
                    float lpf_tau_s = 0.1f, int max_consecutive_rejects = 3);

  // Feed a new yaw sample (degrees, any wrap) taken dt_s after the
  // previous call. Returns the current filtered yaw rate in deg/s.
  //
  // The very first call has no previous sample: it seeds the filter and
  // returns 0 without being rejected. A rejected sample (spike, or
  // dt_s <= 0) holds the last filtered rate and does NOT update the
  // stored previous yaw -- a single-tick glitch is assumed to be bad
  // data, not a real motion, so the next good sample is still compared
  // against the last known-good heading rather than the glitch.
  //
  // BUT rejection must not LATCH: if the yaw really rotated fast (past the
  // threshold) and stayed there, every later sample would also exceed the
  // frozen prev-yaw and be rejected forever, while the held (stale, high)
  // rate keeps rolling the downstream heading integrator. So after
  // max_consecutive_rejects rejects in a row, we treat the new sample as
  // real: resync prev-yaw to it, clear the counter, and zero the held rate
  // (we can't trust a rate fabricated across an unknown gap -- the next
  // accepted sample measures it afresh). This bounds any glitch-hold to a
  // few ticks and guarantees the estimator recovers instead of running away.
  float Update(float yaw_deg, float dt_s);

  bool LastRejected() const { return last_rejected_; }

 private:
  const float spike_threshold_deg_per_tick_;
  const float lpf_tau_s_;
  const int max_consecutive_rejects_;

  bool has_prev_ = false;
  float prev_yaw_deg_ = 0.0f;
  float filtered_rate_dps_ = 0.0f;
  bool last_rejected_ = false;
  int consecutive_rejects_ = 0;
};

}  // namespace control_core
