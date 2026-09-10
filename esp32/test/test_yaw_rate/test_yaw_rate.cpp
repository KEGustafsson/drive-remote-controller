#include <unity.h>

#include <cmath>
#include <limits>

#include "heading/yaw_rate.h"

using control_core::YawRate;

void setUp() {}
void tearDown() {}

// ==========================================================================
// First sample / seeding
// ==========================================================================

void test_first_call_seeds_and_returns_zero() {
  YawRate rate_filter;
  TEST_ASSERT_EQUAL_FLOAT(0.0f, rate_filter.Update(45.0f, 0.01f));
  TEST_ASSERT_FALSE(rate_filter.LastRejected());
}

// ==========================================================================
// Constant rate converges
// ==========================================================================

void test_constant_rate_converges() {
  // 10 deg/s at 100 Hz -> 0.1 deg/tick, well under the default 2 deg/tick
  // spike threshold.
  YawRate rate_filter;
  float yaw = 0.0f;
  rate_filter.Update(yaw, 0.01f);
  float rate = 0.0f;
  for (int i = 0; i < 500; ++i) {
    yaw += 0.1f;
    rate = rate_filter.Update(yaw, 0.01f);
  }
  TEST_ASSERT_FLOAT_WITHIN(0.05, 10.0f, rate);
  TEST_ASSERT_FALSE(rate_filter.LastRejected());
}

// ==========================================================================
// Spike rejection
// ==========================================================================

void test_spike_is_rejected_and_holds_rate() {
  YawRate rate_filter;
  rate_filter.Update(0.0f, 0.01f);            // seed
  float held = rate_filter.Update(0.1f, 0.01f);  // normal step, not rejected
  TEST_ASSERT_FALSE(rate_filter.LastRejected());

  // Huge one-tick jump -- way past the 2 deg/tick default threshold.
  float during_spike = rate_filter.Update(50.0f, 0.01f);
  TEST_ASSERT_TRUE(rate_filter.LastRejected());
  TEST_ASSERT_EQUAL_FLOAT(held, during_spike);

  // The glitch must not have clobbered the stored "last good" yaw: the
  // next normal sample should be compared against 0.1, not 50.0.
  float after_spike = rate_filter.Update(0.15f, 0.01f);
  TEST_ASSERT_FALSE(rate_filter.LastRejected());
  TEST_ASSERT_TRUE(after_spike > held);  // small positive nudge, not a cliff
}

void test_sustained_fast_move_recovers_not_latched() {
  // Regression for the "yaw left rolling" bug: a rotation faster than the
  // spike threshold that keeps going could otherwise latch the estimator into
  // permanent rejection while holding a stale non-zero rate -- so the
  // downstream heading integrator rolled forever. After the fix, rejection
  // resyncs and the rate must settle back to ~0 once motion stops.
  YawRate rate_filter;
  float yaw = 0.0f;
  rate_filter.Update(yaw, 0.01f);  // seed

  // Build up a real rate first (10 deg/s), so there's a non-zero "held"
  // rate available to (wrongly) latch onto.
  for (int i = 0; i < 50; ++i) {
    yaw += 0.1f;
    rate_filter.Update(yaw, 0.01f);
  }

  // Rapid sustained rotation: +5 deg/tick, well past the 2 deg/tick
  // threshold, for many ticks. Every one of these is rejected.
  for (int i = 0; i < 20; ++i) {
    yaw += 5.0f;
    rate_filter.Update(yaw, 0.01f);
  }

  // Motion stops; board now sits still at the new heading. With the old
  // latching code prev-yaw stayed frozen ~5 deg behind, so these stationary
  // samples would ALSO be rejected forever and the held high rate would
  // keep being returned. After the fix the estimator resyncs and accepts.
  float rate = 0.0f;
  for (int i = 0; i < 100; ++i) {
    rate = rate_filter.Update(yaw, 0.01f);  // yaw constant now
  }

  TEST_ASSERT_FALSE(rate_filter.LastRejected());   // not stuck rejecting
  TEST_ASSERT_FLOAT_WITHIN(0.5, 0.0f, rate);       // not rolling; settled ~0
}

// ==========================================================================
// Wrap-safe differentiation
// ==========================================================================

void test_wraps_across_plus_minus_180_boundary() {
  YawRate rate_filter;
  rate_filter.Update(179.0f, 0.01f);  // seed
  // True motion is +1.5 deg (clockwise through the seam), not -358.5.
  float rate = rate_filter.Update(-179.5f, 0.01f);
  TEST_ASSERT_FALSE(rate_filter.LastRejected());
  TEST_ASSERT_TRUE(rate > 0.0f);
  // Raw rate would be 1.5 deg / 0.01 s = 150 deg/s; even lightly filtered
  // on the very first tick, sign must be positive and magnitude bounded.
  TEST_ASSERT_TRUE(rate < 150.0f);
}

// ==========================================================================
// Noise is smoothed, not tracked instantaneously
// ==========================================================================

void test_single_noisy_sample_is_damped() {
  YawRate rate_filter;
  float yaw = 0.0f;
  rate_filter.Update(yaw, 0.01f);
  float baseline = 0.0f;
  for (int i = 0; i < 300; ++i) {
    yaw += 0.1f;  // steady 10 deg/s
    baseline = rate_filter.Update(yaw, 0.01f);
  }
  TEST_ASSERT_FLOAT_WITHIN(0.1, 10.0f, baseline);

  // One noisy sample implying a momentary 100 deg/s (delta = 1.0 deg,
  // still under the 2 deg/tick spike threshold so it's not rejected).
  yaw += 1.0f;
  float after_noise = rate_filter.Update(yaw, 0.01f);
  TEST_ASSERT_FALSE(rate_filter.LastRejected());
  // Smoothed, not tracked: nudged up from baseline but nowhere near the
  // momentary 100 deg/s raw rate.
  TEST_ASSERT_TRUE(after_noise > baseline);
  TEST_ASSERT_TRUE(after_noise < 20.0f);
}

// ==========================================================================
// Non-positive dt is rejected
// ==========================================================================

void test_zero_dt_is_rejected() {
  YawRate rate_filter;
  rate_filter.Update(0.0f, 0.01f);
  float before = rate_filter.Update(0.1f, 0.01f);
  float after = rate_filter.Update(0.2f, 0.0f);
  TEST_ASSERT_TRUE(rate_filter.LastRejected());
  TEST_ASSERT_EQUAL_FLOAT(before, after);
}

// ==========================================================================
// Non-finite input (defense in depth -- 2026-07-23 review)
// ==========================================================================

// NaN defeats the spike check (every comparison is false), so before the
// explicit isfinite reject a single NaN yaw would have poisoned
// filtered_rate_dps_ permanently. Unreachable from rvc_parse (which emits
// bounded finite values only), but hardened to the same standard as the
// other network-adjacent boundaries.
void test_nonfinite_yaw_is_rejected_and_never_poisons_the_rate() {
  YawRate rate_filter;
  rate_filter.Update(0.0f, 0.01f);  // seed
  float before = rate_filter.Update(0.1f, 0.01f);
  TEST_ASSERT_TRUE(std::isfinite(before));

  float nan = std::numeric_limits<float>::quiet_NaN();
  float inf = std::numeric_limits<float>::infinity();
  // Repeated non-finite samples: always rejected, rate stays finite and
  // unchanged, and they must NOT trip the resync heuristic onto garbage.
  for (int i = 0; i < 50; ++i) {
    float r = rate_filter.Update((i % 2) ? nan : inf, 0.01f);
    TEST_ASSERT_TRUE(rate_filter.LastRejected());
    TEST_ASSERT_TRUE(std::isfinite(r));
    TEST_ASSERT_EQUAL_FLOAT(before, r);
  }

  // A good sample afterwards resumes normal operation.
  float r = rate_filter.Update(0.2f, 0.01f);
  TEST_ASSERT_FALSE(rate_filter.LastRejected());
  TEST_ASSERT_TRUE(std::isfinite(r));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_first_call_seeds_and_returns_zero);
  RUN_TEST(test_constant_rate_converges);
  RUN_TEST(test_spike_is_rejected_and_holds_rate);
  RUN_TEST(test_sustained_fast_move_recovers_not_latched);
  RUN_TEST(test_wraps_across_plus_minus_180_boundary);
  RUN_TEST(test_single_noisy_sample_is_damped);
  RUN_TEST(test_zero_dt_is_rejected);
  RUN_TEST(test_nonfinite_yaw_is_rejected_and_never_poisons_the_rate);
  return UNITY_END();
}
