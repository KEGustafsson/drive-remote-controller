#include <unity.h>

#include "drive/arbitration.h"
#include "drive/arm_gate.h"

using control_core::Arbitrate;
using control_core::ActiveSource;
using control_core::ArmGate;
using control_core::ArmGateInputs;
using control_core::ArmInhibit;
using control_core::ArmInhibitName;
using control_core::DrivePosition;
using control_core::RemoteSource;

void setUp() {}
void tearDown() {}

namespace {

// All clear: master enable ON, both levers neutral, no local override.
ArmGateInputs AllClear() {
  ArmGateInputs in;
  in.master_enable = true;
  in.port_lever_neutral = true;
  in.stbd_lever_neutral = true;
  in.local_command_active = false;
  return in;
}

// A gate already armed via the all-clear condition -- the starting point for
// every "stays armed" test.
ArmGate ArmedGate() {
  ArmGate gate;
  gate.Update(AllClear());
  return gate;
}

}  // namespace

// ---- Boot / default state ----

void test_starts_disarmed() {
  ArmGate gate;
  TEST_ASSERT_FALSE(gate.armed());
  TEST_ASSERT_TRUE(gate.status().inhibit == ArmInhibit::kMasterEnableOff);
}

void test_master_enable_alone_does_not_arm() {
  ArmGate gate;
  ArmGateInputs in;
  in.master_enable = true;  // levers unknown/not neutral
  auto s = gate.Update(in);
  TEST_ASSERT_FALSE(s.armed);
  TEST_ASSERT_TRUE(s.inhibit == ArmInhibit::kBothLeversNotNeutral);
}

void test_both_levers_neutral_alone_does_not_arm() {
  ArmGate gate;
  ArmGateInputs in = AllClear();
  in.master_enable = false;
  auto s = gate.Update(in);
  TEST_ASSERT_FALSE(s.armed);
  TEST_ASSERT_TRUE(s.inhibit == ArmInhibit::kMasterEnableOff);
}

// ---- The core requirement: BOTH levers, not either ----

void test_arms_when_all_clear() {
  ArmGate gate;
  auto s = gate.Update(AllClear());
  TEST_ASSERT_TRUE(s.armed);
  TEST_ASSERT_TRUE(s.inhibit == ArmInhibit::kNone);
}

void test_port_lever_out_of_neutral_refuses_arm() {
  ArmGate gate;
  ArmGateInputs in = AllClear();
  in.port_lever_neutral = false;
  auto s = gate.Update(in);
  TEST_ASSERT_FALSE(s.armed);
  TEST_ASSERT_TRUE(s.inhibit == ArmInhibit::kPortLeverNotNeutral);
}

void test_stbd_lever_out_of_neutral_refuses_arm() {
  ArmGate gate;
  ArmGateInputs in = AllClear();
  in.stbd_lever_neutral = false;
  auto s = gate.Update(in);
  TEST_ASSERT_FALSE(s.armed);
  TEST_ASSERT_TRUE(s.inhibit == ArmInhibit::kStbdLeverNotNeutral);
}

void test_both_levers_out_of_neutral_refuses_arm() {
  ArmGate gate;
  ArmGateInputs in = AllClear();
  in.port_lever_neutral = false;
  in.stbd_lever_neutral = false;
  auto s = gate.Update(in);
  TEST_ASSERT_FALSE(s.armed);
  TEST_ASSERT_TRUE(s.inhibit == ArmInhibit::kBothLeversNotNeutral);
}

// A disconnected or dead sensor reads "not neutral" (the pin's external pull-up
// wins), so the interlock must refuse -- the fail-safe direction. This is the
// firmware half of that guarantee; the wiring half is a commissioning check.
void test_missing_sensor_reads_as_not_neutral_and_refuses() {
  ArmGate gate;
  ArmGateInputs in;
  in.master_enable = true;
  in.port_lever_neutral = false;  // sensor unplugged -> pin pulled HIGH
  in.stbd_lever_neutral = true;
  auto s = gate.Update(in);
  TEST_ASSERT_FALSE(s.armed);
}

// ---- Arming with a servo parked off neutral would slam the lever ----

void test_local_command_active_refuses_arm() {
  ArmGate gate;
  ArmGateInputs in = AllClear();
  in.local_command_active = true;
  auto s = gate.Update(in);
  TEST_ASSERT_FALSE(s.armed);
  TEST_ASSERT_TRUE(s.inhibit == ArmInhibit::kLocalCommandActive);
}

void test_arms_once_the_local_switch_is_released() {
  ArmGate gate;
  ArmGateInputs in = AllClear();
  in.local_command_active = true;
  TEST_ASSERT_FALSE(gate.Update(in).armed);
  in.local_command_active = false;
  TEST_ASSERT_TRUE(gate.Update(in).armed);
}

// ---- The latch: neutral is permission to ARM, not a condition of STAYING
// armed. RX's own servos move the levers, so requiring neutral continuously
// would disarm the unit on its first commanded shift. ----

void test_stays_armed_when_a_lever_leaves_neutral() {
  ArmGate gate = ArmedGate();
  ArmGateInputs in = AllClear();
  in.port_lever_neutral = false;  // the drive was just shifted into gear
  auto s = gate.Update(in);
  TEST_ASSERT_TRUE(s.armed);
  TEST_ASSERT_TRUE(s.inhibit == ArmInhibit::kNone);
}

void test_stays_armed_with_both_levers_in_gear() {
  ArmGate gate = ArmedGate();
  ArmGateInputs in = AllClear();
  in.port_lever_neutral = false;
  in.stbd_lever_neutral = false;
  TEST_ASSERT_TRUE(gate.Update(in).armed);
}

void test_stays_armed_across_a_local_switch_press() {
  ArmGate gate = ArmedGate();
  ArmGateInputs in = AllClear();
  in.local_command_active = true;
  TEST_ASSERT_TRUE(gate.Update(in).armed);
}

void test_stays_armed_over_many_ticks_of_shifting() {
  ArmGate gate = ArmedGate();
  ArmGateInputs in = AllClear();
  for (int tick = 0; tick < 100; ++tick) {
    in.port_lever_neutral = (tick % 3) == 0;
    in.stbd_lever_neutral = (tick % 5) == 0;
    in.local_command_active = (tick % 7) == 0;
    TEST_ASSERT_TRUE(gate.Update(in).armed);
  }
}

// ---- Disarm is never gated on anything ----

void test_master_enable_off_disarms_immediately() {
  ArmGate gate = ArmedGate();
  ArmGateInputs in = AllClear();
  in.master_enable = false;
  auto s = gate.Update(in);
  TEST_ASSERT_FALSE(s.armed);
  TEST_ASSERT_TRUE(s.inhibit == ArmInhibit::kMasterEnableOff);
}

void test_master_enable_off_disarms_even_mid_shift() {
  ArmGate gate = ArmedGate();
  ArmGateInputs in;
  in.master_enable = false;
  in.port_lever_neutral = false;
  in.stbd_lever_neutral = false;
  in.local_command_active = true;
  TEST_ASSERT_FALSE(gate.Update(in).armed);
}

// After a disarm with the levers in gear, the interlock applies again: the
// operator must return both levers to neutral before RX will retake command.
// This is the reboot/brownout case too -- a fresh ArmGate starts disarmed.
void test_re_arming_requires_neutral_again() {
  ArmGate gate = ArmedGate();
  ArmGateInputs in = AllClear();
  in.port_lever_neutral = false;
  in.stbd_lever_neutral = false;
  gate.Update(in);  // still armed (the latch)

  in.master_enable = false;
  TEST_ASSERT_FALSE(gate.Update(in).armed);  // disarmed, levers still in gear

  in.master_enable = true;
  TEST_ASSERT_FALSE(gate.Update(in).armed);  // refused -- levers not neutral
  TEST_ASSERT_TRUE(gate.status().inhibit == ArmInhibit::kBothLeversNotNeutral);

  in.port_lever_neutral = true;
  TEST_ASSERT_FALSE(gate.Update(in).armed);  // one lever is not enough
  TEST_ASSERT_TRUE(gate.status().inhibit == ArmInhibit::kStbdLeverNotNeutral);

  in.stbd_lever_neutral = true;
  TEST_ASSERT_TRUE(gate.Update(in).armed);  // both neutral -> permitted again
}

// Level-based, per the owner's decision: no fresh master-enable edge is
// required. Leaving the switch ON and returning the levers to neutral re-arms
// on the next tick.
void test_re_arms_without_cycling_the_enable_switch() {
  ArmGate gate;
  ArmGateInputs in = AllClear();
  in.port_lever_neutral = false;
  TEST_ASSERT_FALSE(gate.Update(in).armed);
  in.port_lever_neutral = true;
  TEST_ASSERT_TRUE(gate.Update(in).armed);
}

// ---- status() must agree with the last Update() ----

void test_status_matches_last_update() {
  ArmGate gate;
  auto s = gate.Update(AllClear());
  TEST_ASSERT_TRUE(gate.status().armed == s.armed);
  TEST_ASSERT_TRUE(gate.status().inhibit == s.inhibit);
  TEST_ASSERT_TRUE(gate.armed());
}

// ---- SK string vocabulary ----

void test_inhibit_names() {
  TEST_ASSERT_EQUAL_STRING("", ArmInhibitName(ArmInhibit::kNone));
  TEST_ASSERT_EQUAL_STRING("masterEnableOff",
                           ArmInhibitName(ArmInhibit::kMasterEnableOff));
  TEST_ASSERT_EQUAL_STRING("portLeverNotNeutral",
                           ArmInhibitName(ArmInhibit::kPortLeverNotNeutral));
  TEST_ASSERT_EQUAL_STRING("stbdLeverNotNeutral",
                           ArmInhibitName(ArmInhibit::kStbdLeverNotNeutral));
  TEST_ASSERT_EQUAL_STRING("bothLeversNotNeutral",
                           ArmInhibitName(ArmInhibit::kBothLeversNotNeutral));
  TEST_ASSERT_EQUAL_STRING("localCommandActive",
                           ArmInhibitName(ArmInhibit::kLocalCommandActive));
}

// ---- Composition with arbitration: the gate is what actually gates ----

// The whole point of the feature: a live, enabled, commanding remote source
// reaches NOTHING while the interlock refuses to arm.
void test_disarmed_gate_blocks_a_live_enabled_remote() {
  ArmGate gate;
  ArmGateInputs in = AllClear();
  in.stbd_lever_neutral = false;  // starboard lever still in gear
  auto s = gate.Update(in);

  RemoteSource tx;
  tx.command = DrivePosition::kForward;
  tx.enabled = true;
  tx.live = true;

  auto r = Arbitrate(DrivePosition::kNeutral, s.armed, tx, RemoteSource{});
  TEST_ASSERT_TRUE(r.command == DrivePosition::kNeutral);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kNone);
}

void test_armed_gate_lets_a_live_enabled_remote_through() {
  ArmGate gate;
  auto s = gate.Update(AllClear());

  RemoteSource tx;
  tx.command = DrivePosition::kForward;
  tx.enabled = true;
  tx.live = true;

  auto r = Arbitrate(DrivePosition::kNeutral, s.armed, tx, RemoteSource{});
  TEST_ASSERT_TRUE(r.command == DrivePosition::kForward);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kTx);
}

// SAFETY.md drive invariant 3 is untouched by any of this: RX's own local
// switch still commands its drive with the gate refusing to arm. (Whether the
// linkage is physically engaged is the engage relay's business -- see
// SAFETY.md's note under invariant 3.)
void test_local_switch_still_commands_while_disarmed() {
  ArmGate gate;
  ArmGateInputs in;
  in.master_enable = false;
  auto s = gate.Update(in);
  TEST_ASSERT_FALSE(s.armed);

  auto r = Arbitrate(DrivePosition::kReverse, s.armed, RemoteSource{},
                      RemoteSource{});
  TEST_ASSERT_TRUE(r.command == DrivePosition::kReverse);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kLocal);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_starts_disarmed);
  RUN_TEST(test_master_enable_alone_does_not_arm);
  RUN_TEST(test_both_levers_neutral_alone_does_not_arm);
  RUN_TEST(test_arms_when_all_clear);
  RUN_TEST(test_port_lever_out_of_neutral_refuses_arm);
  RUN_TEST(test_stbd_lever_out_of_neutral_refuses_arm);
  RUN_TEST(test_both_levers_out_of_neutral_refuses_arm);
  RUN_TEST(test_missing_sensor_reads_as_not_neutral_and_refuses);
  RUN_TEST(test_local_command_active_refuses_arm);
  RUN_TEST(test_arms_once_the_local_switch_is_released);
  RUN_TEST(test_stays_armed_when_a_lever_leaves_neutral);
  RUN_TEST(test_stays_armed_with_both_levers_in_gear);
  RUN_TEST(test_stays_armed_across_a_local_switch_press);
  RUN_TEST(test_stays_armed_over_many_ticks_of_shifting);
  RUN_TEST(test_master_enable_off_disarms_immediately);
  RUN_TEST(test_master_enable_off_disarms_even_mid_shift);
  RUN_TEST(test_re_arming_requires_neutral_again);
  RUN_TEST(test_re_arms_without_cycling_the_enable_switch);
  RUN_TEST(test_status_matches_last_update);
  RUN_TEST(test_inhibit_names);
  RUN_TEST(test_disarmed_gate_blocks_a_live_enabled_remote);
  RUN_TEST(test_armed_gate_lets_a_live_enabled_remote_through);
  RUN_TEST(test_local_switch_still_commands_while_disarmed);
  return UNITY_END();
}
