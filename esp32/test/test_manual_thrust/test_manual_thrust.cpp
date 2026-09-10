#include <unity.h>

#include "heading/manual_thrust.h"

using control_core::Cmd;
using control_core::ManualThrust;

void setUp() {}
void tearDown() {}

namespace {
constexpr float kDwellS = 1.85f;  // config::kReversalDwellS
constexpr uint32_t kDwellMs = 1850;
}  // namespace

// ---- The point of manual mode: immediate, untimed response ----

void test_press_thrusts_on_the_same_tick() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  TEST_ASSERT_TRUE(m.Update(Cmd::kPort, 0) == Cmd::kPort);
}

void test_release_stops_on_the_same_tick() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  // No min-on time: releasing 1 ms after pressing stops immediately.
  TEST_ASSERT_TRUE(m.Update(Cmd::kOff, 1) == Cmd::kOff);
}

void test_no_min_off_time_repressing_same_direction_is_immediate() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  m.Update(Cmd::kOff, 100);
  // Same direction again 1 ms later -- matches the measured control-box
  // behaviour that same-direction re-pulsing has no delay.
  TEST_ASSERT_TRUE(m.Update(Cmd::kPort, 101) == Cmd::kPort);
}

void test_rapid_pulsing_same_direction_never_blocked() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  for (uint32_t t = 0; t < 40; t += 2) {
    TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, t) == Cmd::kStbd);
    TEST_ASSERT_TRUE(m.Update(Cmd::kOff, t + 1) == Cmd::kOff);
  }
}

void test_holding_a_direction_stays_on_indefinitely() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  for (uint32_t t = 0; t < 30000; t += 10) {
    TEST_ASSERT_TRUE(m.Update(Cmd::kPort, t) == Cmd::kPort);
  }
}

// ---- The one timing rule that stays: the control box's reversal interlock ----

void test_reversal_goes_through_off_first_never_direct() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  // Operator flicks straight across.
  TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, 100) == Cmd::kOff);
}

void test_reversal_is_withheld_until_the_dwell_expires() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  m.Update(Cmd::kStbd, 100);  // -> kOff, dwell starts at t=100
  TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, 100 + kDwellMs - 1) == Cmd::kOff);
  TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, 100 + kDwellMs) == Cmd::kStbd);
}

void test_reversal_dwell_also_applies_after_a_clean_release() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  m.Update(Cmd::kOff, 500);  // operator lets go, dwell starts at t=500
  TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, 500 + kDwellMs - 1) == Cmd::kOff);
  TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, 500 + kDwellMs) == Cmd::kStbd);
}

void test_reversal_pending_is_reported_while_waiting() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  m.Update(Cmd::kStbd, 100);
  TEST_ASSERT_TRUE(m.reversal_pending());
  m.Update(Cmd::kStbd, 100 + kDwellMs);
  TEST_ASSERT_FALSE(m.reversal_pending());
}

// Letting go during the wait must clear the request -- the operator changed
// their mind, and the thruster must not fire the moment the dwell expires.
void test_releasing_during_the_dwell_cancels_the_reversal() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  m.Update(Cmd::kStbd, 100);
  m.Update(Cmd::kOff, 200);  // operator releases while waiting
  TEST_ASSERT_TRUE(m.Update(Cmd::kOff, 100 + kDwellMs + 1) == Cmd::kOff);
  TEST_ASSERT_FALSE(m.reversal_pending());
}

// Mashing the opposite button throughout the interlock must not accumulate or
// re-trigger anything -- it just stays off until the window passes.
void test_mashing_the_opposite_button_does_not_extend_or_shorten_the_dwell() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  m.Update(Cmd::kStbd, 100);
  for (uint32_t t = 110; t < 100 + kDwellMs; t += 10) {
    TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, t) == Cmd::kOff);
  }
  TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, 100 + kDwellMs) == Cmd::kStbd);
}

void test_first_thrust_after_reset_is_never_delayed() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  m.Update(Cmd::kOff, 10);
  // A new manual session: stale dwell from the previous one must not leak in.
  m.Reset(20);
  TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, 20) == Cmd::kStbd);
}

void test_reset_while_thrusting_drops_the_output() {
  ManualThrust m(kDwellS);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  m.Reset(50);
  TEST_ASSERT_TRUE(m.current() == Cmd::kOff);
}

// ---- Config-boundary hardening ----

void test_zero_dwell_allows_immediate_reversal() {
  ManualThrust m(0.0f);
  m.Reset(0);
  m.Update(Cmd::kPort, 0);
  TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, 1) == Cmd::kOff);  // still via off
  TEST_ASSERT_TRUE(m.Update(Cmd::kStbd, 1) == Cmd::kStbd);
}

void test_negative_or_nan_dwell_is_treated_as_zero_not_forever() {
  // A corrupted config value must not silently make reversal impossible.
  ManualThrust neg(-5.0f);
  neg.Reset(0);
  neg.Update(Cmd::kPort, 0);
  neg.Update(Cmd::kStbd, 1);
  TEST_ASSERT_TRUE(neg.Update(Cmd::kStbd, 1) == Cmd::kStbd);

  ManualThrust nan_dwell(0.0f / 0.0f);
  nan_dwell.Reset(0);
  nan_dwell.Update(Cmd::kPort, 0);
  nan_dwell.Update(Cmd::kStbd, 1);
  TEST_ASSERT_TRUE(nan_dwell.Update(Cmd::kStbd, 1) == Cmd::kStbd);
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_press_thrusts_on_the_same_tick);
  RUN_TEST(test_release_stops_on_the_same_tick);
  RUN_TEST(test_no_min_off_time_repressing_same_direction_is_immediate);
  RUN_TEST(test_rapid_pulsing_same_direction_never_blocked);
  RUN_TEST(test_holding_a_direction_stays_on_indefinitely);
  RUN_TEST(test_reversal_goes_through_off_first_never_direct);
  RUN_TEST(test_reversal_is_withheld_until_the_dwell_expires);
  RUN_TEST(test_reversal_dwell_also_applies_after_a_clean_release);
  RUN_TEST(test_reversal_pending_is_reported_while_waiting);
  RUN_TEST(test_releasing_during_the_dwell_cancels_the_reversal);
  RUN_TEST(test_mashing_the_opposite_button_does_not_extend_or_shorten_the_dwell);
  RUN_TEST(test_first_thrust_after_reset_is_never_delayed);
  RUN_TEST(test_reset_while_thrusting_drops_the_output);
  RUN_TEST(test_zero_dwell_allows_immediate_reversal);
  RUN_TEST(test_negative_or_nan_dwell_is_treated_as_zero_not_forever);
  return UNITY_END();
}
