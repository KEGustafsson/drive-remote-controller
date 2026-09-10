#include <unity.h>

#include <cmath>
#include <limits>

#include "heading/angle_math.h"
#include "heading/heading_filter.h"

using control_core::GnssHeading;
using control_core::HeadingFilter;

namespace {

HeadingFilter::Cfg TestCfg() {
  HeadingFilter::Cfg cfg;
  cfg.tau_corr_s = 2.0f;
  cfg.t_fresh_s = 1.0f;
  cfg.max_rate_dps = 10.0f;
  return cfg;
}

// Gain the filter should apply to a fix arriving dt_s after the previous
// accepted one. Mirrors Correct()'s formula so the rate-independence tests
// below assert against the CONTRACT (a time constant in seconds) rather than
// re-deriving a magic number per fix rate.
float ExpectedGain(float dt_s, float tau_s) {
  return 1.0f - std::exp(-dt_s / tau_s);
}

}  // namespace

void setUp() {}
void tearDown() {}

// ==========================================================================
// Predict (fast path)
// ==========================================================================

void test_predict_integrates_rate() {
  HeadingFilter f(TestCfg(), 0.0f);
  f.Predict(10.0f, 1.0f);
  TEST_ASSERT_FLOAT_WITHIN(0.001, 10.0f, f.fused_deg());
  f.Predict(10.0f, 1.0f);
  TEST_ASSERT_FLOAT_WITHIN(0.001, 20.0f, f.fused_deg());
}

void test_predict_wraps_across_boundary() {
  HeadingFilter f(TestCfg(), 170.0f);
  f.Predict(20.0f, 1.0f);  // 170 + 20 = 190 -> wraps to -170
  TEST_ASSERT_FLOAT_WITHIN(0.001, -170.0f, f.fused_deg());
}

void test_predict_ignores_nonpositive_dt() {
  HeadingFilter f(TestCfg(), 5.0f);
  f.Predict(100.0f, 0.0f);
  TEST_ASSERT_FLOAT_WITHIN(0.001, 5.0f, f.fused_deg());
}

// ==========================================================================
// Correct (slow path): gating
// ==========================================================================

void test_first_correction_seeds_directly_to_the_fix() {
  HeadingFilter f(TestCfg(), 0.0f);
  GnssHeading gnss{90.0f, 1000, true};
  TEST_ASSERT_TRUE(f.Correct(gnss, 1000));
  // First-ever fix SEEDS the absolute heading directly (no crawl from the
  // construction guess of 0) -- so the reported heading is correct from the
  // first fix instead of climbing over many seconds on every boot.
  TEST_ASSERT_FLOAT_WITHIN(0.01, 90.0f, f.fused_deg());
}

void test_stale_gnss_rejected() {
  HeadingFilter f(TestCfg(), 0.0f);
  GnssHeading gnss{90.0f, 0, true};
  TEST_ASSERT_FALSE(f.Correct(gnss, 2000));  // 2 s old, t_fresh_s = 1 s
  TEST_ASSERT_FLOAT_WITHIN(0.001, 0.0f, f.fused_deg());
}

void test_invalid_gnss_ignored() {
  HeadingFilter f(TestCfg(), 0.0f);
  GnssHeading gnss{90.0f, 1000, false};
  TEST_ASSERT_FALSE(f.Correct(gnss, 1000));
  TEST_ASSERT_FLOAT_WITHIN(0.001, 0.0f, f.fused_deg());
}

void test_implausible_jump_between_consecutive_fixes_rejected() {
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{10.0f, 1000, true}, 1000);  // accepted, bootstraps

  // 160 deg jump in 10 ms -- far beyond any real heading rate.
  bool accepted = f.Correct(GnssHeading{170.0f, 1010, true}, 1010);
  TEST_ASSERT_FALSE(accepted);
}

void test_plausible_jump_between_consecutive_fixes_accepted() {
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{10.0f, 1000, true}, 1000);
  float after_first = f.fused_deg();

  // 5 deg change over 1 s = 5 deg/s, under the 10 deg/s bound.
  bool accepted = f.Correct(GnssHeading{15.0f, 2000, true}, 2000);
  TEST_ASSERT_TRUE(accepted);
  TEST_ASSERT_TRUE(f.fused_deg() > after_first);
}

void test_rejected_spike_does_not_corrupt_last_good_fix() {
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{10.0f, 1000, true}, 1000);

  // Corrupted single report -- rejected.
  TEST_ASSERT_FALSE(f.Correct(GnssHeading{170.0f, 1010, true}, 1010));

  // A normal follow-up close to the ORIGINAL good fix (10 deg, ~1 s
  // later) must still be accepted -- proves the spike didn't clobber
  // the stored "last good" GNSS reading.
  bool accepted = f.Correct(GnssHeading{12.0f, 2000, true}, 2000);
  TEST_ASSERT_TRUE(accepted);
}

// ==========================================================================
// age_since_accepted_correction_ms: trust, not arrival, freshness
// ==========================================================================

void test_age_since_accepted_correction_is_max_before_any_fix() {
  HeadingFilter f(TestCfg(), 0.0f);
  TEST_ASSERT_EQUAL_UINT32(UINT32_MAX, f.age_since_accepted_correction_ms(5000));
}

void test_age_since_accepted_correction_tracks_last_accepted_fix() {
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{10.0f, 1000, true}, 1000);
  TEST_ASSERT_EQUAL_UINT32(500, f.age_since_accepted_correction_ms(1500));
}

void test_age_since_accepted_correction_ignores_rejected_samples() {
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{10.0f, 1000, true}, 1000);  // accepted, bootstraps

  // A stream of fresh-but-implausible samples must NOT reset the age --
  // this is the fix for a reviewer-caught bug where a stream of
  // fresh-but-rejected GNSS deltas kept the FSM's arm/coast gate looking
  // "current" even though the fusion filter was silently rejecting every
  // one of them and coasting on gyro alone.
  bool accepted = f.Correct(GnssHeading{170.0f, 1010, true}, 1010);
  TEST_ASSERT_FALSE(accepted);
  TEST_ASSERT_EQUAL_UINT32(1010 - 1000, f.age_since_accepted_correction_ms(1010));

  // Stale samples are rejected the same way and must not reset it either.
  accepted = f.Correct(GnssHeading{10.5f, 900, true}, 5000);  // 4.1s old
  TEST_ASSERT_FALSE(accepted);
  TEST_ASSERT_EQUAL_UINT32(5000 - 1000, f.age_since_accepted_correction_ms(5000));
}

// ==========================================================================
// Non-finite and out-of-order input (2026-07-23 review findings)
// ==========================================================================

// NaN fails every comparison, so before the explicit isfinite reject it
// sailed straight through the plausibility gate (the one whose entire job
// is rejection), poisoned fused_deg_ permanently, and -- because Correct()
// kept returning true -- kept resetting the accepted-correction age so the
// coast-timeout escape hatch never fired. And with a NaN heading error the
// Switcher's off-threshold comparison also goes false, so a thruster
// already thrusting could never be commanded off by the control law. These
// vectors pin all of that shut.
void test_nan_heading_rejected_and_does_not_poison_fusion() {
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{10.0f, 1000, true}, 1000);  // accepted, bootstraps

  float nan = std::numeric_limits<float>::quiet_NaN();
  TEST_ASSERT_FALSE(f.Correct(GnssHeading{nan, 2000, true}, 2000));
  TEST_ASSERT_TRUE(std::isfinite(f.fused_deg()));
  // Rejected means rejected: the accepted-correction age keeps aging, so
  // the coast timeout still fires on a stream of NaN "fixes".
  TEST_ASSERT_EQUAL_UINT32(2000 - 1000, f.age_since_accepted_correction_ms(2000));

  // A later good fix is still accepted against the last GOOD one.
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{12.0f, 3000, true}, 3000));
}

void test_nan_heading_rejected_on_the_bootstrap_fix_too() {
  HeadingFilter f(TestCfg(), 0.0f);
  float nan = std::numeric_limits<float>::quiet_NaN();
  // The first-ever fix seeds fused_deg_ directly, with no previous fix to
  // check plausibility against -- so the isfinite gate is the ONLY thing
  // standing between a NaN bootstrap and a permanently-NaN filter.
  TEST_ASSERT_FALSE(f.Correct(GnssHeading{nan, 1000, true}, 1000));
  TEST_ASSERT_TRUE(std::isfinite(f.fused_deg()));
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{90.0f, 2000, true}, 2000));
  TEST_ASSERT_FLOAT_WITHIN(0.01, 90.0f, f.fused_deg());
}

void test_infinite_heading_rejected() {
  HeadingFilter f(TestCfg(), 0.0f);
  float inf = std::numeric_limits<float>::infinity();
  TEST_ASSERT_FALSE(f.Correct(GnssHeading{inf, 1000, true}, 1000));
  TEST_ASSERT_FALSE(f.Correct(GnssHeading{-inf, 1000, true}, 1000));
  TEST_ASSERT_TRUE(std::isfinite(f.fused_deg()));
}

// WrapDeg180 itself must terminate for ANY input -- the old
// subtract-360-in-a-loop hung forever on +/-Inf and effectively forever on
// huge finite magnitudes, inside a WebSocket callback holding a mutex.
void test_wrap_deg180_is_safe_for_any_magnitude() {
  using control_core::WrapDeg180;
  // Huge finite values return instantly and land in range.
  float w = WrapDeg180(1.0e30f);
  TEST_ASSERT_TRUE(w > -180.0f && w <= 180.0f);
  w = WrapDeg180(-1.0e30f);
  TEST_ASSERT_TRUE(w > -180.0f && w <= 180.0f);
  // Non-finite input yields non-finite output (garbage stays visibly
  // garbage for the boundary checks) -- but returns, never hangs.
  TEST_ASSERT_FALSE(std::isfinite(WrapDeg180(std::numeric_limits<float>::infinity())));
  TEST_ASSERT_FALSE(std::isfinite(WrapDeg180(std::numeric_limits<float>::quiet_NaN())));
  // And the ordinary cases still wrap exactly as before.
  TEST_ASSERT_FLOAT_WITHIN(0.001, 180.0f, WrapDeg180(180.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.001, 180.0f, WrapDeg180(-180.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.001, -170.0f, WrapDeg180(190.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.001, 0.0f, WrapDeg180(-720.0f));
}

// An out-of-order (or replayed) fix has t_ms EARLIER than the previously
// accepted one. The old unsigned subtraction wrapped that into a huge dt,
// making max_jump_deg astronomically large -- the plausibility gate became
// trivially satisfiable by any delta whenever delivery reordered.
void test_out_of_order_fix_rejected_not_wrapped_into_a_huge_window() {
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{10.0f, 2000, true}, 2000);  // accepted

  // A fix stamped EARLIER arrives late, carrying a wild heading. Under the
  // wraparound bug this passed (dt ~= 4 billion ms); it must be rejected.
  TEST_ASSERT_FALSE(f.Correct(GnssHeading{170.0f, 1500, true}, 2100));
  // A duplicate timestamp is equally not a fresh fix.
  TEST_ASSERT_FALSE(f.Correct(GnssHeading{170.0f, 2000, true}, 2100));

  // Ordering resumed: a plausible later fix is accepted normally.
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{12.0f, 3000, true}, 3000));
}

// A reference older than kReferenceMaxAgeMs is re-seeded from, not judged
// against. Under the previous code a gap past ~24.8 days made the signed
// fix-to-fix interval negative, so every later fix was rejected as
// out-of-order until reboot -- an HH left powered through a winter's GNSS
// outage never accepted a heading again.
void test_fix_after_a_multi_week_outage_reseeds_instead_of_being_rejected() {
  HeadingFilter f(TestCfg(), 0.0f);
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{10.0f, 1000, true}, 1000));

  // 2.592e9 ms: fits uint32_t, exceeds INT32_MAX.
  const uint32_t thirty_days_ms = 30u * 24u * 3600u * 1000u;
  const uint32_t t = 1000 + thirty_days_ms;
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{90.0f, t, true}, t));
  TEST_ASSERT_FLOAT_WITHIN(0.01, 90.0f, f.fused_deg());  // seeded, not nudged
  TEST_ASSERT_EQUAL_UINT32(0, f.age_since_accepted_correction_ms(t));
}

// The accepted-correction age wraps too: exactly 2^32 ms after the last fix
// it would read 0 again, letting heading_valid come true on a seven-week-old
// reference. Expiring the reference once per tick (control_step.cpp) makes
// the age read "never" from one hour on, so there is nothing left to wrap.
void test_expired_reference_reads_never_and_cannot_wrap_back_to_fresh() {
  HeadingFilter f(TestCfg(), 0.0f);
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{10.0f, 1000, true}, 1000));

  const uint32_t limit = 1000 + HeadingFilter::kReferenceMaxAgeMs;
  // At the limit: still counting.
  f.ExpireStaleReference(limit);
  TEST_ASSERT_EQUAL_UINT32(HeadingFilter::kReferenceMaxAgeMs,
                           f.age_since_accepted_correction_ms(limit));
  // One ms past it: forgotten.
  f.ExpireStaleReference(limit + 1);
  TEST_ASSERT_EQUAL_UINT32(UINT32_MAX, f.age_since_accepted_correction_ms(limit + 1));
  // The clock wraps all the way round to the fix's own timestamp. Without the
  // expiry this would read 0 ms old.
  TEST_ASSERT_EQUAL_UINT32(UINT32_MAX, f.age_since_accepted_correction_ms(1000));

  // The next fix seeds afresh, exactly like the first fix after boot.
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{90.0f, 2000, true}, 2000));
  TEST_ASSERT_FLOAT_WITHIN(0.01, 90.0f, f.fused_deg());
  TEST_ASSERT_EQUAL_UINT32(0, f.age_since_accepted_correction_ms(2000));
}

// ==========================================================================
// Coast through a gap, converge on return (ARCHITECTURE.md §7 / Sec 12)
// ==========================================================================

void test_coasts_through_gap_and_converges_on_return() {
  HeadingFilter f(TestCfg(), 0.0f);

  // Establish trust with a first fix at the true heading (0 deg).
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{0.0f, 1000, true}, 1000));

  // WiFi/GNSS gap: 20 s of local-gyro-only prediction. A gyro bias makes
  // the fused estimate drift even though the boat is actually still
  // near 0 deg true heading -- this drift is exactly what the slow
  // correction exists to pull back.
  uint32_t t = 1000;
  for (int i = 0; i < 20; ++i) {
    f.Predict(1.0f, 1.0f);
    t += 1000;
  }
  TEST_ASSERT_FLOAT_WITHIN(0.5, 20.0f, f.fused_deg());  // drifted to ~20 deg

  // GNSS returns. The long gap since the last accepted fix (20 s) makes
  // a return to the true ~0.5 deg heading trivially plausible (a huge
  // allowed jump window), and repeated fixes at the true heading pull
  // the fused estimate back down over several corrections.
  for (int i = 0; i < 51; ++i) {
    t += 1000;
    TEST_ASSERT_TRUE(f.Correct(GnssHeading{0.5f, t, true}, t));
  }
  TEST_ASSERT_FLOAT_WITHIN(0.5, 0.5f, f.fused_deg());
}

// ==========================================================================
// Correction gain is a TIME CONSTANT, not a per-fix step
//
// The bug these lock down: the filter used to apply a fixed gain per accepted
// fix, so its real convergence speed was gain * fix_rate. The fix rate was
// assumed to be ~5 Hz and was actually 1 Hz (measured 2026-08-20 against a
// live UM982), which made the heading converge 5x slower than the header
// claimed -- 1-2 deg of offset taking 10-15 s to wash out after the bow moved.
// Nothing in the suite could catch that, because every test fed fixes at one
// rate. These feed the SAME elapsed time at DIFFERENT rates and require the
// same answer.
// ==========================================================================

void test_gain_follows_the_configured_time_constant() {
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{0.0f, 1000, true}, 1000);  // seeds to 0

  // 5 deg over 1 s: real movement, and comfortably inside the 10 deg/s
  // plausibility bound (a 10 deg step would sit exactly ON it).
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{5.0f, 2000, true}, 2000));
  TEST_ASSERT_FLOAT_WITHIN(0.01f, 5.0f * ExpectedGain(1.0f, 2.0f),
                           f.fused_deg());
}

void test_convergence_is_independent_of_fix_rate() {
  // Same 4 s of wall time, same 10 deg of error to close, two very different
  // fix rates: 1 Hz (4 fixes) and 10 Hz (40 fixes). A per-fix gain would make
  // the 10 Hz run converge far further in the same time; a time constant makes
  // them agree.
  //
  // The error is introduced by GYRO DRIFT, not by stepping the GNSS value.
  // That is both the real scenario (the fix is the truth, the dead-reckoned
  // estimate is what wanders) and the only one the plausibility gate permits:
  // a 10 deg jump between fixes 100 ms apart is 100 deg/s and is correctly
  // rejected as implausible, which is what an earlier version of this test
  // tripped over.
  const float kDriftDeg = 10.0f;

  HeadingFilter slow(TestCfg(), 0.0f);
  slow.Correct(GnssHeading{0.0f, 1000, true}, 1000);
  slow.Predict(kDriftDeg, 1.0f);  // gyro walks the estimate off by 10 deg
  uint32_t t = 1000;
  for (int i = 0; i < 4; ++i) {
    t += 1000;
    TEST_ASSERT_TRUE(slow.Correct(GnssHeading{0.0f, t, true}, t));
  }

  HeadingFilter fast(TestCfg(), 0.0f);
  fast.Correct(GnssHeading{0.0f, 1000, true}, 1000);
  fast.Predict(kDriftDeg, 1.0f);
  t = 1000;
  for (int i = 0; i < 40; ++i) {
    t += 100;
    TEST_ASSERT_TRUE(fast.Correct(GnssHeading{0.0f, t, true}, t));
  }

  // Both closed 4 s worth of a 2 s time constant, so both retain exp(-2) of
  // the original error regardless of how many fixes it took to get there.
  const float expected = kDriftDeg * std::exp(-4.0f / 2.0f);
  TEST_ASSERT_FLOAT_WITHIN(0.05f, expected, slow.fused_deg());
  TEST_ASSERT_FLOAT_WITHIN(0.05f, expected, fast.fused_deg());
  TEST_ASSERT_FLOAT_WITHIN(0.05f, slow.fused_deg(), fast.fused_deg());
}

void test_duplicate_fixes_1ms_apart_do_not_double_correct() {
  // The um982 plugin publishes navigation.headingTrue from TWO sentences
  // ($GNHPR and #UNIHEADINGA) about 1 ms apart, carrying the same value. Under
  // a fixed per-fix gain each pair spent two full corrections on one
  // measurement. With a time constant, the 1 ms straggler is worth ~nothing.
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{0.0f, 1000, true}, 1000);
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{5.0f, 2000, true}, 2000));
  const float after_real_fix = f.fused_deg();

  TEST_ASSERT_TRUE(f.Correct(GnssHeading{5.0f, 2001, true}, 2001));
  // 1 ms against a 2 s tau is a gain of 5e-4; on the ~3 deg remaining gap that
  // is ~0.0015 deg. Assert it is genuinely negligible, not merely smaller.
  TEST_ASSERT_FLOAT_WITHIN(0.01f, after_real_fix, f.fused_deg());
}

void test_long_gap_snaps_toward_the_absolute_reference() {
  // After a long outage the gyro has had that whole time to drift, so the
  // absolute reference deserves nearly all the weight -- k -> 1 as dt grows.
  HeadingFilter f(TestCfg(), 0.0f);
  f.Correct(GnssHeading{0.0f, 1000, true}, 1000);
  for (int i = 0; i < 30; ++i) {
    f.Predict(1.0f, 1.0f);  // drift 30 deg over 30 s on gyro alone
  }
  TEST_ASSERT_FLOAT_WITHIN(0.5f, 30.0f, f.fused_deg());

  uint32_t t = 31000;
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{0.5f, t, true}, t));
  // 30 s / 2 s tau: k = 1 - exp(-15), indistinguishable from 1.
  TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.5f, f.fused_deg());
}

void test_nonpositive_tau_snaps_instead_of_producing_nan() {
  // Boundary guard: a misconfigured tau must not divide-by-zero into NaN and
  // poison fused_deg_ permanently (same rule as the non-finite heading check).
  HeadingFilter::Cfg cfg = TestCfg();
  cfg.tau_corr_s = 0.0f;
  HeadingFilter f(cfg, 0.0f);
  f.Correct(GnssHeading{0.0f, 1000, true}, 1000);
  TEST_ASSERT_TRUE(f.Correct(GnssHeading{5.0f, 2000, true}, 2000));
  TEST_ASSERT_TRUE(std::isfinite(f.fused_deg()));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 5.0f, f.fused_deg());

  cfg.tau_corr_s = std::numeric_limits<float>::quiet_NaN();
  HeadingFilter g(cfg, 0.0f);
  g.Correct(GnssHeading{0.0f, 1000, true}, 1000);
  TEST_ASSERT_TRUE(g.Correct(GnssHeading{5.0f, 2000, true}, 2000));
  TEST_ASSERT_TRUE(std::isfinite(g.fused_deg()));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_gain_follows_the_configured_time_constant);
  RUN_TEST(test_convergence_is_independent_of_fix_rate);
  RUN_TEST(test_duplicate_fixes_1ms_apart_do_not_double_correct);
  RUN_TEST(test_long_gap_snaps_toward_the_absolute_reference);
  RUN_TEST(test_nonpositive_tau_snaps_instead_of_producing_nan);

  RUN_TEST(test_predict_integrates_rate);
  RUN_TEST(test_predict_wraps_across_boundary);
  RUN_TEST(test_predict_ignores_nonpositive_dt);
  RUN_TEST(test_first_correction_seeds_directly_to_the_fix);
  RUN_TEST(test_stale_gnss_rejected);
  RUN_TEST(test_invalid_gnss_ignored);
  RUN_TEST(test_implausible_jump_between_consecutive_fixes_rejected);
  RUN_TEST(test_plausible_jump_between_consecutive_fixes_accepted);
  RUN_TEST(test_rejected_spike_does_not_corrupt_last_good_fix);
  RUN_TEST(test_age_since_accepted_correction_is_max_before_any_fix);
  RUN_TEST(test_age_since_accepted_correction_tracks_last_accepted_fix);
  RUN_TEST(test_age_since_accepted_correction_ignores_rejected_samples);
  RUN_TEST(test_nan_heading_rejected_and_does_not_poison_fusion);
  RUN_TEST(test_nan_heading_rejected_on_the_bootstrap_fix_too);
  RUN_TEST(test_infinite_heading_rejected);
  RUN_TEST(test_wrap_deg180_is_safe_for_any_magnitude);
  RUN_TEST(test_out_of_order_fix_rejected_not_wrapped_into_a_huge_window);
  RUN_TEST(test_fix_after_a_multi_week_outage_reseeds_instead_of_being_rejected);
  RUN_TEST(test_expired_reference_reads_never_and_cannot_wrap_back_to_fresh);
  RUN_TEST(test_coasts_through_gap_and_converges_on_return);
  return UNITY_END();
}
