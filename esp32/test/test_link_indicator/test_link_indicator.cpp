#include <unity.h>

#include "drive/link_indicator.h"

using control_core::EvaluateLinkIndicator;
using control_core::LinkIndicator;
using control_core::LinkIndicatorLedOn;

void setUp() {}
void tearDown() {}

// Signature: EvaluateLinkIndicator(socket_connected, rx_live, hh_live, armed).

// Socket down dominates regardless of the (necessarily last-known) unit
// verdicts or the arm state: TX cannot reach the server, so it must not claim
// anything. LED analogue of rxLiveness.ts returning 'offline' first.
void test_socket_down_is_off_whatever_else() {
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(false, false, false, false) ==
                   LinkIndicator::kOff);
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(false, true, true, true) ==
                   LinkIndicator::kOff);
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(false, true, false, true) ==
                   LinkIndicator::kOff);
}

// Nothing reachable is always a fault -- armed or not, a slow "ready" light
// must never appear for units that are all off (the §5.2 false-confidence
// rule, preserved through the arm dimension).
void test_no_unit_live_is_fault_whether_armed_or_not() {
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(true, false, false, false) ==
                   LinkIndicator::kFault);
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(true, false, false, true) ==
                   LinkIndicator::kFault);
}

// Disarmed but a commandable unit is live: ready to be armed (slow blink),
// whether one unit or both are answering -- while disarmed the operator is not
// commanding, so the "which unit" detail is deferred until they arm.
void test_disarmed_with_a_live_unit_is_ready_to_arm() {
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(true, true, true, false) ==
                   LinkIndicator::kReadyToArm);
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(true, true, false, false) ==
                   LinkIndicator::kReadyToArm);
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(true, false, true, false) ==
                   LinkIndicator::kReadyToArm);
}

// Once ARMED, the per-unit liveness detail returns: exactly one unit live is a
// warning (you are commanding something you cannot reach).
void test_armed_with_one_unit_missing_warns() {
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(true, false, true, true) ==
                   LinkIndicator::kWarn);
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(true, true, false, true) ==
                   LinkIndicator::kWarn);
}

// Solid only when ARMED and both units are actually reachable.
void test_ready_requires_armed_and_both_units() {
  TEST_ASSERT_TRUE(EvaluateLinkIndicator(true, true, true, true) ==
                   LinkIndicator::kReady);
}

// kOff never lights, in any phase.
void test_off_never_lights() {
  TEST_ASSERT_FALSE(LinkIndicatorLedOn(LinkIndicator::kOff, false, false, false));
  TEST_ASSERT_FALSE(LinkIndicatorLedOn(LinkIndicator::kOff, true, true, true));
}

// kReady is solid: on in every phase.
void test_ready_is_solid() {
  TEST_ASSERT_TRUE(LinkIndicatorLedOn(LinkIndicator::kReady, false, false, false));
  TEST_ASSERT_TRUE(LinkIndicatorLedOn(LinkIndicator::kReady, true, true, true));
}

// Ready-to-arm, warn and fault each follow their OWN phase, so the three blink
// rates stay distinguishable -- none may light on another's phase, which is
// what would blur them into one indistinct flicker.
void test_blink_states_follow_their_own_phases() {
  // ready-to-arm ~1 Hz
  TEST_ASSERT_TRUE(LinkIndicatorLedOn(LinkIndicator::kReadyToArm, true, false, false));
  TEST_ASSERT_FALSE(LinkIndicatorLedOn(LinkIndicator::kReadyToArm, false, true, true));
  // warn ~2 Hz
  TEST_ASSERT_TRUE(LinkIndicatorLedOn(LinkIndicator::kWarn, false, true, false));
  TEST_ASSERT_FALSE(LinkIndicatorLedOn(LinkIndicator::kWarn, true, false, true));
  // fault ~5 Hz
  TEST_ASSERT_TRUE(LinkIndicatorLedOn(LinkIndicator::kFault, false, false, true));
  TEST_ASSERT_FALSE(LinkIndicatorLedOn(LinkIndicator::kFault, true, true, false));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_socket_down_is_off_whatever_else);
  RUN_TEST(test_no_unit_live_is_fault_whether_armed_or_not);
  RUN_TEST(test_disarmed_with_a_live_unit_is_ready_to_arm);
  RUN_TEST(test_armed_with_one_unit_missing_warns);
  RUN_TEST(test_ready_requires_armed_and_both_units);
  RUN_TEST(test_off_never_lights);
  RUN_TEST(test_ready_is_solid);
  RUN_TEST(test_blink_states_follow_their_own_phases);
  return UNITY_END();
}
