#include <unity.h>

#include "heading/safety_fsm.h"

using control_core::FsmCfg;
using control_core::FsmInputs;
using control_core::FsmState;
using control_core::SafetyFsm;

void setUp() {}
void tearDown() {}

namespace {

FsmCfg TestCfg() {
  FsmCfg cfg;
  cfg.coast_warn_ms = 10000;
  cfg.coast_max_ms = 30000;
  return cfg;
}

FsmInputs Healthy(bool engage) {
  FsmInputs in;
  in.engage = engage;
  in.deadman_ok = true;
  in.bno_ok = true;
  in.heading_ok_to_arm = true;
  in.heading_age_ms = 0;
  return in;
}

void test_starts_disarmed() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in;  // everything false/default
  TEST_ASSERT_EQUAL(FsmState::kDisarmed, fsm.Update(in));
}

// Fault injection: "bad-quality heading at arm -> arm
// refused."
void test_engage_without_good_heading_stays_armed_idle() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  in.heading_ok_to_arm = false;
  for (int i = 0; i < 5; ++i) {
    TEST_ASSERT_EQUAL(FsmState::kArmedIdle, fsm.Update(in));
    TEST_ASSERT_FALSE(fsm.JustEnteredHolding());
  }
}

// Once a good heading arrives while still engaged, ARMED_IDLE promotes to
// HOLDING and JustEnteredHolding() fires exactly once.
void test_armed_idle_promotes_to_holding_once_heading_becomes_good() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  in.heading_ok_to_arm = false;
  fsm.Update(in);
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, fsm.state());

  in.heading_ok_to_arm = true;
  TEST_ASSERT_EQUAL(FsmState::kHolding, fsm.Update(in));
  TEST_ASSERT_TRUE(fsm.JustEnteredHolding());

  TEST_ASSERT_EQUAL(FsmState::kHolding, fsm.Update(in));
  TEST_ASSERT_FALSE(fsm.JustEnteredHolding());  // one-shot, not sticky
}

// Engage + healthy BNO + already-good heading arms straight into HOLDING
// on the very first tick.
void test_engage_with_good_heading_arms_directly_into_holding() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  TEST_ASSERT_EQUAL(FsmState::kHolding, fsm.Update(in));
  TEST_ASSERT_TRUE(fsm.JustEnteredHolding());
}

// Deadman release mid-hold -> OFF.
void test_deadman_release_mid_hold_forces_disarmed() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  fsm.Update(in);
  TEST_ASSERT_EQUAL(FsmState::kHolding, fsm.state());

  in.deadman_ok = false;
  TEST_ASSERT_EQUAL(FsmState::kDisarmed, fsm.Update(in));
}

// Releasing engage itself, mid-hold, also forces DISARMED (invariant 6:
// manual authority dominates).
void test_engage_release_mid_hold_forces_disarmed() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  fsm.Update(in);
  TEST_ASSERT_EQUAL(FsmState::kHolding, fsm.state());

  in.engage = false;
  TEST_ASSERT_EQUAL(FsmState::kDisarmed, fsm.Update(in));
}

// Kill the IMU -> OFF (here: FAULT, which drives ENABLE low same as
// DISARMED -- see Outputs/ComputeOutputLevels).
void test_bno_loss_mid_hold_forces_fault() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  fsm.Update(in);
  TEST_ASSERT_EQUAL(FsmState::kHolding, fsm.state());

  in.bno_ok = false;
  TEST_ASSERT_EQUAL(FsmState::kFault, fsm.Update(in));
}

// Recovering from FAULT (BNO frames resume) with engage still continuously
// held returns to ARMED_IDLE, NOT straight back to HOLDING -- resuming an
// interrupted hold requires a fresh engage press (see safety_fsm.h design
// note), even though heading_ok_to_arm is still true throughout.
void test_bno_recovery_without_fresh_engage_stays_armed_idle() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  fsm.Update(in);
  in.bno_ok = false;
  fsm.Update(in);
  TEST_ASSERT_EQUAL(FsmState::kFault, fsm.state());

  in.bno_ok = true;  // recovered, engage never released
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, fsm.Update(in));
  TEST_ASSERT_FALSE(fsm.JustEnteredHolding());
  // Stays idle on subsequent ticks too -- not a one-tick fluke.
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, fsm.Update(in));
}

// A fresh engage edge (release, then re-press) after that recovery does
// resume holding.
void test_fresh_engage_edge_resumes_holding_after_fault_recovery() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  fsm.Update(in);
  in.bno_ok = false;
  fsm.Update(in);
  in.bno_ok = true;
  fsm.Update(in);
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, fsm.state());

  in.engage = false;
  fsm.Update(in);
  in.engage = true;
  TEST_ASSERT_EQUAL(FsmState::kHolding, fsm.Update(in));
  TEST_ASSERT_TRUE(fsm.JustEnteredHolding());
}

// Drop WiFi briefly -> coast and hold: GNSS age past coast_warn_ms
// (but under coast_max_ms) keeps HOLDING and just raises the telemetry
// flag.
void test_coast_warning_between_warn_and_max_keeps_holding() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  fsm.Update(in);

  in.heading_age_ms = 15000;  // > warn (10s), < max (30s)
  TEST_ASSERT_EQUAL(FsmState::kHolding, fsm.Update(in));
  TEST_ASSERT_TRUE(fsm.CoastWarning());
}

// Long GNSS loss -> disengage: past coast_max_ms drops out of
// HOLDING to ARMED_IDLE, and (per the design note) needs a fresh engage
// edge to resume even once a fix returns.
void test_coast_max_disengages_to_armed_idle_and_requires_fresh_engage() {
  SafetyFsm fsm(TestCfg());
  FsmInputs in = Healthy(true);
  fsm.Update(in);

  in.heading_age_ms = 30001;  // > max
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, fsm.Update(in));
  TEST_ASSERT_FALSE(fsm.CoastWarning());

  // A fresh fix arriving without a new engage press does NOT silently
  // resume the hold.
  in.heading_ok_to_arm = true;
  in.heading_age_ms = 0;
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, fsm.Update(in));

  in.engage = false;
  fsm.Update(in);
  in.engage = true;
  TEST_ASSERT_EQUAL(FsmState::kHolding, fsm.Update(in));
}

}  // namespace

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_starts_disarmed);
  RUN_TEST(test_engage_without_good_heading_stays_armed_idle);
  RUN_TEST(test_armed_idle_promotes_to_holding_once_heading_becomes_good);
  RUN_TEST(test_engage_with_good_heading_arms_directly_into_holding);
  RUN_TEST(test_deadman_release_mid_hold_forces_disarmed);
  RUN_TEST(test_engage_release_mid_hold_forces_disarmed);
  RUN_TEST(test_bno_loss_mid_hold_forces_fault);
  RUN_TEST(test_bno_recovery_without_fresh_engage_stays_armed_idle);
  RUN_TEST(test_fresh_engage_edge_resumes_holding_after_fault_recovery);
  RUN_TEST(test_coast_warning_between_warn_and_max_keeps_holding);
  RUN_TEST(test_coast_max_disengages_to_armed_idle_and_requires_fresh_engage);
  return UNITY_END();
}
