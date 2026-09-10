#include <unity.h>

#include "heading/output_map.h"
#include "heading/switcher.h"

using control_core::Cmd;
using control_core::ComputeOutputLevels;
using control_core::OutputLevels;

void setUp() {}
void tearDown() {}

namespace {

// Exhaustively checks every (armed, cmd) combination against the output
// map table -- disarmed -> all off; armed+OFF -> coast
// (EN=1,P=0,S=0); armed+PORT / armed+STBD assert exactly one direction.
void test_matches_spec_output_map_table() {
  OutputLevels disarmed_off = ComputeOutputLevels(false, Cmd::kOff);
  TEST_ASSERT_FALSE(disarmed_off.enable);
  TEST_ASSERT_FALSE(disarmed_off.port);
  TEST_ASSERT_FALSE(disarmed_off.stbd);

  OutputLevels disarmed_port = ComputeOutputLevels(false, Cmd::kPort);
  TEST_ASSERT_FALSE(disarmed_port.enable);
  TEST_ASSERT_FALSE(disarmed_port.port);
  TEST_ASSERT_FALSE(disarmed_port.stbd);

  OutputLevels disarmed_stbd = ComputeOutputLevels(false, Cmd::kStbd);
  TEST_ASSERT_FALSE(disarmed_stbd.enable);
  TEST_ASSERT_FALSE(disarmed_stbd.port);
  TEST_ASSERT_FALSE(disarmed_stbd.stbd);

  OutputLevels armed_coast = ComputeOutputLevels(true, Cmd::kOff);
  TEST_ASSERT_TRUE(armed_coast.enable);
  TEST_ASSERT_FALSE(armed_coast.port);
  TEST_ASSERT_FALSE(armed_coast.stbd);

  OutputLevels armed_port = ComputeOutputLevels(true, Cmd::kPort);
  TEST_ASSERT_TRUE(armed_port.enable);
  TEST_ASSERT_TRUE(armed_port.port);
  TEST_ASSERT_FALSE(armed_port.stbd);

  OutputLevels armed_stbd = ComputeOutputLevels(true, Cmd::kStbd);
  TEST_ASSERT_TRUE(armed_stbd.enable);
  TEST_ASSERT_FALSE(armed_stbd.port);
  TEST_ASSERT_TRUE(armed_stbd.stbd);
}

// The structural property SAFETY.md thruster invariant 1 depends on: no (armed,
// cmd) combination may ever produce port==stbd==true.
void test_never_both_directions_for_any_combination() {
  const bool armed_values[] = {false, true};
  const Cmd cmd_values[] = {Cmd::kOff, Cmd::kPort, Cmd::kStbd};

  for (bool armed : armed_values) {
    for (Cmd cmd : cmd_values) {
      OutputLevels levels = ComputeOutputLevels(armed, cmd);
      TEST_ASSERT_FALSE(levels.port && levels.stbd);
    }
  }
}

// SAFETY.md thruster invariant 2: directions are inert unless armed, regardless of
// what direction was requested.
void test_directions_forced_low_when_disarmed_for_any_cmd() {
  const Cmd cmd_values[] = {Cmd::kOff, Cmd::kPort, Cmd::kStbd};
  for (Cmd cmd : cmd_values) {
    OutputLevels levels = ComputeOutputLevels(false, cmd);
    TEST_ASSERT_FALSE(levels.port);
    TEST_ASSERT_FALSE(levels.stbd);
    TEST_ASSERT_FALSE(levels.enable);
  }
}

// The unreachable-in-normal-use fallback (an out-of-range/corrupted Cmd,
// e.g. from a bad cast or uninitialized read) must fail off completely,
// not leave ENABLE asserted -- this file's whole purpose is structurally
// enforcing fail-off, so its own "shouldn't happen" path can't be the one
// spot that doesn't.
void test_out_of_range_cmd_fails_off_completely() {
  Cmd corrupted = static_cast<Cmd>(99);
  OutputLevels levels = ComputeOutputLevels(true, corrupted);
  TEST_ASSERT_FALSE(levels.enable);
  TEST_ASSERT_FALSE(levels.port);
  TEST_ASSERT_FALSE(levels.stbd);
}

}  // namespace

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_matches_spec_output_map_table);
  RUN_TEST(test_never_both_directions_for_any_combination);
  RUN_TEST(test_directions_forced_low_when_disarmed_for_any_cmd);
  RUN_TEST(test_out_of_range_cmd_fails_off_completely);
  return UNITY_END();
}
