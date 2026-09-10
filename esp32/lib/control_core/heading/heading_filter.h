#pragma once

// Complementary filter fusing local BNO yaw rate (fast path) with a slow
// Signal K / UM982 GNSS heading correction (ARCHITECTURE.md §7). Pure
// C++17, no Arduino.h -- host-testable under env:native.

#include <cstdint>

namespace control_core {

// A single GNSS (UM982) true-heading sample. `valid` is set by the
// caller (the SK subscriber / the arm-gate) once it has already
// judged quality (RTK fix / stdev threshold -- ARCHITECTURE.md Item 3,
// real path/units still TBD, see MEASUREMENTS.md). HeadingFilter itself
// only gates on freshness and plausibility, never on quality directly.
struct GnssHeading {
  float heading_deg = 0.0f;
  uint32_t t_ms = 0;
  bool valid = false;
};

class HeadingFilter {
 public:
  struct Cfg {
    // Correction TIME CONSTANT in seconds -- NOT a per-fix gain. The gain
    // applied to each accepted fix is derived from the interval since the
    // previous accepted one:
    //
    //     k = 1 - exp(-dt_gnss_s / tau_corr_s)
    //
    // so the convergence the operator actually sees stays tau_corr_s
    // whatever rate the GNSS happens to publish at.
    //
    // This replaces a fixed per-fix gain, which silently made the filter N
    // times slower whenever the fix rate dropped by N -- and that was not
    // hypothetical. This header used to document "~2 s" on the assumption of
    // a ~5 Hz stream (inferred from config::kSkHeadingListenDelayMs, which
    // is only a subscribe THROTTLE and says nothing about how fast fixes
    // arrive), while the UM982 was in fact delivering 1 Hz. Measured
    // 2026-08-20 over a 240 s live capture: the real time constant was
    // ~10 s, and a 1-2 deg offset took 10-15 s to wash out after the bow was
    // moved by hand. The receiver has since been reconfigured to 10 Hz, but
    // the point of this form is that the tuning no longer depends on that
    // staying true.
    //
    // MEASUREMENTS.md Item 3 (GNSS quality/RTK gating) is resolved upstream
    // (signalk-um982-plugin nulls navigation.headingTrue on a bad RTK
    // solution, and SkHeadingIn's HeadingTrueListener drops those nulls --
    // see sk_heading_in.cpp), so every fix reaching Correct() has already
    // passed the plugin's quality check, not just freshness + plausibility.
    float tau_corr_s = 2.0f;

    // Age of the last ACCEPTED correction beyond which the fused heading
    // stops counting as trustworthy. control_step.cpp turns this into
    // heading_valid, which gates arming and coasts an existing hold, so it
    // is a safety bound, not just a display detail.
    //
    // Sized as "several missed fixes" rather than as a multiple of one
    // nominal period, because the nominal period has already been wrong once.
    // At the measured 10 Hz (2026-08-20: max observed inter-fix gap 0.300 s,
    // max observed age 0.403 s) this is ~20 fixes of margin; it also still
    // leaves ~1 s of margin if the receiver ever falls back to 1 Hz, where
    // the previous 1.5 s left only 0.47 s against a measured 1.03 s
    // worst-case age -- one dropped sentence from refusing to arm.
    float t_fresh_s = 2.0f;

    float max_rate_dps = 10.0f;  // plausibility bound BETWEEN consecutive
                                 // accepted GNSS samples: max real heading
                                 // rate (same spike-rejection pattern as
                                 // YawRate, applied to the GNSS stream
                                 // instead of the gyro one). Measured
                                 // headroom: the fastest this bow can be
                                 // swung by hand is ~3.9 deg/s (2026-08-20,
                                 // owner swinging lock to lock as fast as
                                 // possible), so this never trips during
                                 // manual handling -- 2.5x margin.
  };

  explicit HeadingFilter(const Cfg& cfg, float initial_heading_deg = 0.0f);

  // Fast path: integrate local yaw rate (deg/s) over dt_s.
  void Predict(float r_dps, float dt_s);

  // Slow path: nudge the fused estimate toward a GNSS heading if it
  // passes freshness (age vs now_ms), caller-supplied quality
  // (gnss.valid), and plausibility gates. The plausibility check compares
  // this sample against the last ACCEPTED GNSS sample (not the fused
  // estimate) -- the fused estimate is expected to have drifted, that's
  // what correction is for; a single corrupted/garbled report is instead
  // caught by an impossible jump from the previous good reading. A
  // rejected sample does not update the stored "last good" GNSS reading,
  // so it can't cascade into rejecting the next real one. Returns true
  // if the correction was applied.
  bool Correct(const GnssHeading& gnss, uint32_t now_ms);

  float fused_deg() const { return fused_deg_; }

  // How long the last accepted fix stays usable as a reference -- both as the
  // plausibility baseline in Correct() and as the thing
  // age_since_accepted_correction_ms() measures from. One hour: far beyond any
  // coast window (FsmCfg::coast_max_ms is 30 s, and every consumer of the age
  // has long since given up on the heading by then), and far short of the
  // 2^32 ms (~49.7 day) point at which the unsigned age arithmetic wraps.
  //
  // Without an expiry, an HH left powered through a multi-week GNSS outage
  // (the receiver or the server switched off for the season) fails in two
  // ways when the fixes return. Past ~24.8 days the signed fix-to-fix interval
  // in Correct() goes negative and EVERY new fix is rejected as out-of-order,
  // permanently, until reboot. And at exactly ~49.7 days the age itself wraps
  // to a small number, so heading_valid reads true for one t_fresh window on
  // a reference that is seven weeks old -- long enough to arm a hold on pure
  // dead reckoning. Forgetting the reference after an hour makes both
  // impossible: the next fix seeds afresh, exactly as the first fix after
  // boot does, and the age reads UINT32_MAX ("nothing trusted") in between.
  static constexpr uint32_t kReferenceMaxAgeMs = 60u * 60u * 1000u;

  // Call every control tick with the same now_ms handed to everything else.
  // Forgets the last accepted fix once it is older than kReferenceMaxAgeMs.
  // Correct() applies the same rule itself, but only when a fix arrives; this
  // is what keeps the AGE honest across the wrap when none does.
  void ExpireStaleReference(uint32_t now_ms);

  // Age (ms) of the last ACCEPTED correction (the last Correct() call that
  // returned true), or UINT32_MAX if none has ever been accepted -- or if the
  // last one has expired (see kReferenceMaxAgeMs). This is deliberately NOT
  // the same as a GnssHeading's own arrival freshness: a steady stream of
  // fresh-but-rejected samples (stale, or implausible per the plausibility
  // gate) must not look "current" to a caller gating arm-readiness or a
  // coast-timeout on it -- those callers care whether the fused heading is
  // actually being trusted/corrected, not whether SK deltas are still
  // arriving on time. See control_task.cpp.
  uint32_t age_since_accepted_correction_ms(uint32_t now_ms) const {
    return has_prev_gnss_ ? now_ms - prev_gnss_t_ms_ : UINT32_MAX;
  }

 private:
  const Cfg cfg_;

  float fused_deg_;

  bool has_prev_gnss_ = false;
  float prev_gnss_heading_deg_ = 0.0f;
  uint32_t prev_gnss_t_ms_ = 0;
};

}  // namespace control_core
