#include "heading/heading_filter.h"

#include <cmath>

#include "heading/angle_math.h"

namespace control_core {

HeadingFilter::HeadingFilter(const Cfg& cfg, float initial_heading_deg)
    : cfg_(cfg), fused_deg_(WrapDeg180(initial_heading_deg)) {}

void HeadingFilter::Predict(float r_dps, float dt_s) {
  if (dt_s <= 0.0f) {
    return;
  }
  fused_deg_ = WrapDeg180(fused_deg_ + r_dps * dt_s);
}

void HeadingFilter::ExpireStaleReference(uint32_t now_ms) {
  if (has_prev_gnss_ && (now_ms - prev_gnss_t_ms_) > kReferenceMaxAgeMs) {
    has_prev_gnss_ = false;
  }
}

bool HeadingFilter::Correct(const GnssHeading& gnss, uint32_t now_ms) {
  if (!gnss.valid) {
    return false;
  }

  // Non-finite input is rejected FIRST, before any gate that works by
  // comparison. NaN fails every comparison, so without this it would sail
  // straight through the plausibility check below (whose entire job is
  // rejection), poison fused_deg_ permanently, and -- because Correct()
  // would keep returning true -- keep resetting the accepted-correction age
  // so the coast-timeout escape hatch never fires either. Same boundary
  // rule as SlewSetpointDeg and Switcher::SetTunables.
  if (!std::isfinite(gnss.heading_deg)) {
    return false;
  }

  // Unsigned subtraction, so a timestamp in the FUTURE (clock skew between
  // the SK producer and us, a replayed frame) wraps to a huge age and is
  // rejected by the same freshness bound -- which is the behaviour we want.
  // Note there is deliberately no `age_s < 0` branch: it would be dead code,
  // since this can never be negative.
  float age_s = (now_ms - gnss.t_ms) / 1000.0f;
  if (age_s > cfg_.t_fresh_s) {
    return false;  // stale, or dated in the future
  }

  // A reference older than an hour is re-seeded from, not judged against --
  // see kReferenceMaxAgeMs. Done here as well as per tick so a bare Correct()
  // caller gets the same answer the control loop does.
  ExpireStaleReference(now_ms);

  if (has_prev_gnss_) {
    float gnss_delta_deg =
        WrapDeg180(gnss.heading_deg - prev_gnss_heading_deg_);
    // Signed on purpose: an out-of-order or replayed fix has t_ms EARLIER
    // than the previously accepted one, and the unsigned subtraction would
    // wrap to a huge dt -- making max_jump_deg astronomically large and the
    // plausibility gate trivially satisfied for any delta. Backward or
    // duplicate time is rejected outright instead.
    int32_t dt_gnss_ms = static_cast<int32_t>(gnss.t_ms - prev_gnss_t_ms_);
    if (dt_gnss_ms <= 0) {
      return false;  // out-of-order, duplicate, or clock anomaly
    }
    float dt_gnss_s = dt_gnss_ms / 1000.0f;
    float max_jump_deg = cfg_.max_rate_dps * dt_gnss_s;
    if (std::fabs(gnss_delta_deg) > max_jump_deg) {
      return false;  // implausible relative to the last accepted fix
    }
    // Normal operation: nudge toward the absolute reference with a gain
    // derived from THIS interval, so the convergence time constant is
    // cfg_.tau_corr_s no matter what rate the fixes arrive at (see
    // Cfg::tau_corr_s for why a fixed per-fix gain was the wrong shape).
    //
    // dt_gnss_s > 0 is already guaranteed above, so k lands in (0, 1) and
    // both ends behave: a long gap drives k toward 1, asymptotically a
    // straight snap to the fix -- correct, because the gyro has had exactly
    // that long to drift -- while a burst of near-simultaneous fixes applies
    // almost nothing each, instead of compounding one correction several
    // times over. That second case is real: the plugin publishes
    // navigation.headingTrue from two different sentences ($GNHPR and
    // #UNIHEADINGA) about 1 ms apart, and under the old fixed gain each pair
    // that straddled a tick boundary spent two full corrections' worth of
    // gain on what is a single measurement.
    //
    // Guarded rather than trusted: a non-positive or NaN tau would make
    // std::exp return NaN or 0 and poison fused_deg_ permanently. Same
    // boundary rule as the non-finite heading check above.
    float k = 1.0f;
    if (cfg_.tau_corr_s > 0.0f) {  // false for NaN too
      k = 1.0f - std::exp(-dt_gnss_s / cfg_.tau_corr_s);
    }
    float correction_delta_deg = WrapDeg180(gnss.heading_deg - fused_deg_);
    fused_deg_ = WrapDeg180(fused_deg_ + k * correction_delta_deg);
  } else {
    // First-ever accepted fix: SEED the fused estimate directly to the
    // absolute heading instead of crawling toward it from the construction
    // guess (0) one gain-step at a time -- otherwise the reported heading
    // starts at 0 and takes many seconds/fixes to reach the true value on
    // every boot. There is nothing to check plausibility against yet, and
    // no reason to distrust the absolute reference at bootstrap.
    fused_deg_ = WrapDeg180(gnss.heading_deg);
  }

  prev_gnss_heading_deg_ = gnss.heading_deg;
  prev_gnss_t_ms_ = gnss.t_ms;
  has_prev_gnss_ = true;
  return true;
}

}  // namespace control_core
