#include <unity.h>

#include "common/debounce.h"

using control_core::Debounce;

void setUp() {}
void tearDown() {}

namespace {
constexpr uint32_t kAssertMs = 50;
constexpr uint32_t kReleaseMs = 50;
}  // namespace

void test_starts_at_initial_state() {
  Debounce db(kAssertMs, kReleaseMs, false);
  TEST_ASSERT_FALSE(db.state());
  Debounce db_high(kAssertMs, kReleaseMs, true);
  TEST_ASSERT_TRUE(db_high.state());
}

// A single-tick glitch (the classic bounce) must not register at all.
void test_single_tick_assert_glitch_is_ignored() {
  Debounce db(kAssertMs, kReleaseMs, false);
  uint32_t t = 1000;
  TEST_ASSERT_FALSE(db.Update(true, t));       // glitch high
  TEST_ASSERT_FALSE(db.Update(false, t + 10)); // back low
  TEST_ASSERT_FALSE(db.Update(false, t + 20));
  TEST_ASSERT_FALSE(db.state());
}

// Assert must be held stably for the full assert period before it reads.
void test_assert_requires_full_stable_period() {
  Debounce db(kAssertMs, kReleaseMs, false);
  uint32_t t = 1000;
  for (uint32_t dt = 0; dt < kAssertMs; dt += 10) {
    TEST_ASSERT_FALSE(db.Update(true, t + dt));
  }
  TEST_ASSERT_TRUE(db.Update(true, t + kAssertMs));
}

// Release is symmetric: stable low for the full release period.
void test_release_requires_full_stable_period() {
  Debounce db(kAssertMs, kReleaseMs, true);
  uint32_t t = 1000;
  for (uint32_t dt = 0; dt < kReleaseMs; dt += 10) {
    TEST_ASSERT_TRUE(db.Update(false, t + dt));
  }
  TEST_ASSERT_FALSE(db.Update(false, t + kReleaseMs));
}

// A noisy release/re-assert must NOT produce a falling-then-rising
// debounced edge -- e.g. a shift switch chattering near a detent must not
// look like "released then re-pressed" downstream. The state must simply
// stay asserted.
void test_noisy_release_reassert_never_toggles() {
  Debounce db(kAssertMs, kReleaseMs, false);
  uint32_t t = 1000;
  // Get to a clean asserted state first.
  for (uint32_t dt = 0; dt <= kAssertMs; dt += 10) {
    db.Update(true, t + dt);
  }
  TEST_ASSERT_TRUE(db.state());
  t += kAssertMs + 10;

  // Chatter: 20 ms low bursts (below the 50 ms release period) separated
  // by highs. Debounced state must never drop.
  for (int burst = 0; burst < 5; ++burst) {
    TEST_ASSERT_TRUE(db.Update(false, t));
    TEST_ASSERT_TRUE(db.Update(false, t + 10));
    TEST_ASSERT_TRUE(db.Update(false, t + 20));
    TEST_ASSERT_TRUE(db.Update(true, t + 30));  // bounces back
    t += 40;
  }
  TEST_ASSERT_TRUE(db.state());
}

// A bounce mid-way through a pending transition restarts the clock: the
// stability window must be contiguous, not cumulative.
void test_bounce_restarts_stability_clock() {
  Debounce db(kAssertMs, kReleaseMs, false);
  uint32_t t = 1000;
  db.Update(true, t);        // pending rise starts
  db.Update(true, t + 30);   // 30 ms stable...
  db.Update(false, t + 40);  // ...bounce cancels it
  // A fresh 40 ms of high is NOT enough (< 50 ms since the restart).
  TEST_ASSERT_FALSE(db.Update(true, t + 50));
  TEST_ASSERT_FALSE(db.Update(true, t + 89));
  // 50 ms after the restart it finally reads.
  TEST_ASSERT_TRUE(db.Update(true, t + 100));
}

// Zero stable periods pass the raw signal straight through (test configs).
void test_zero_periods_pass_through() {
  Debounce db(0, 0, false);
  TEST_ASSERT_TRUE(db.Update(true, 100));
  TEST_ASSERT_FALSE(db.Update(false, 101));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_starts_at_initial_state);
  RUN_TEST(test_single_tick_assert_glitch_is_ignored);
  RUN_TEST(test_assert_requires_full_stable_period);
  RUN_TEST(test_release_requires_full_stable_period);
  RUN_TEST(test_noisy_release_reassert_never_toggles);
  RUN_TEST(test_bounce_restarts_stability_clock);
  RUN_TEST(test_zero_periods_pass_through);
  return UNITY_END();
}
