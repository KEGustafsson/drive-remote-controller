#include <unity.h>

#include "heading/setpoint.h"  // control_core::kMaxTrimDeg
#include "heading/thruster_arbitration.h"

using control_core::ActiveSource;
using control_core::ArbitrateThruster;
using control_core::Cmd;
using control_core::ThrusterMode;
using control_core::ThrusterModeFromSkString;
using control_core::ThrusterRemote;

void setUp() {}
void tearDown() {}

namespace {

// A live+enabled source in manual mode, pushing `dir`.
ThrusterRemote Manual(Cmd dir, bool live = true, bool enabled = true) {
  ThrusterRemote r;
  r.live = live;
  r.enabled = enabled;
  r.mode = ThrusterMode::kManual;
  r.manual_cmd = dir;
  return r;
}

// A live+enabled source in hold mode, optionally commanding a trim offset.
ThrusterRemote Hold(float trim_deg = 0.0f, bool live = true,
                    bool enabled = true) {
  ThrusterRemote r;
  r.live = live;
  r.enabled = enabled;
  r.mode = ThrusterMode::kHold;
  r.trim_deg = trim_deg;
  return r;
}

ThrusterRemote None() { return ThrusterRemote{}; }

}  // namespace

// ---- SAFETY.md thruster invariant 6: the unit's own ENGAGE is unconditional ----

void test_local_engage_wins_over_live_enabled_tx_manual() {
  auto r = ArbitrateThruster(/*local_engage=*/true, Manual(Cmd::kStbd),
                             Manual(Cmd::kPort));
  TEST_ASSERT_TRUE(r.source == ActiveSource::kLocal);
  TEST_ASSERT_TRUE(r.engage_request);
  // Local engage is a single contact and always means hold -- a remote's
  // manual request must not leak through it.
  TEST_ASSERT_TRUE(r.mode == ThrusterMode::kHold);
  TEST_ASSERT_TRUE(r.manual_cmd == Cmd::kOff);
}

void test_local_engage_ignores_remote_commanded_trim() {
  auto r = ArbitrateThruster(true, Hold(30.0f), Hold(-45.0f));
  TEST_ASSERT_TRUE(r.source == ActiveSource::kLocal);
  // Local ENGAGE holds the current heading with no trim of its own.
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, r.trim_deg);
}

// ---- Fixed precedence TX > plugin (owner requirement) ----

void test_tx_outranks_plugin_when_both_qualify() {
  auto r = ArbitrateThruster(false, Manual(Cmd::kPort), Manual(Cmd::kStbd));
  TEST_ASSERT_TRUE(r.source == ActiveSource::kTx);
  TEST_ASSERT_TRUE(r.manual_cmd == Cmd::kPort);
}

void test_plugin_commands_only_in_tx_absence() {
  auto r = ArbitrateThruster(false, None(), Manual(Cmd::kStbd));
  TEST_ASSERT_TRUE(r.source == ActiveSource::kPlugin);
  TEST_ASSERT_TRUE(r.manual_cmd == Cmd::kStbd);
}

void test_tx_stale_hands_over_to_plugin() {
  auto r = ArbitrateThruster(false, Manual(Cmd::kPort, /*live=*/false),
                             Manual(Cmd::kStbd));
  TEST_ASSERT_TRUE(r.source == ActiveSource::kPlugin);
  TEST_ASSERT_TRUE(r.manual_cmd == Cmd::kStbd);
}

void test_tx_disarmed_hands_over_to_plugin() {
  auto r = ArbitrateThruster(
      false, Manual(Cmd::kPort, /*live=*/true, /*enabled=*/false),
      Manual(Cmd::kStbd));
  TEST_ASSERT_TRUE(r.source == ActiveSource::kPlugin);
}

// The regression this rule exists for: authority must not depend on which
// source spoke most recently, so repeatedly re-evaluating with both sources
// disagreeing can never flip the commanded direction. On a thruster a flip
// would also drive straight into the control box's reversal interlock.
void test_result_independent_of_recency_no_oscillation() {
  ThrusterRemote tx = Manual(Cmd::kPort);
  ThrusterRemote plugin = Manual(Cmd::kStbd);
  for (int i = 0; i < 20; ++i) {
    auto r = ArbitrateThruster(false, tx, plugin);
    TEST_ASSERT_TRUE(r.source == ActiveSource::kTx);
    TEST_ASSERT_TRUE(r.manual_cmd == Cmd::kPort);
  }
}

// ---- Fail to off when nothing qualifies ----

void test_no_source_means_no_engage_request() {
  auto r = ArbitrateThruster(false, None(), None());
  TEST_ASSERT_TRUE(r.source == ActiveSource::kNone);
  TEST_ASSERT_FALSE(r.engage_request);
  TEST_ASSERT_TRUE(r.manual_cmd == Cmd::kOff);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, r.trim_deg);
}

void test_live_but_disarmed_sources_do_not_engage() {
  auto r = ArbitrateThruster(
      false, Manual(Cmd::kPort, true, false), Manual(Cmd::kStbd, true, false));
  TEST_ASSERT_FALSE(r.engage_request);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kNone);
}

void test_armed_but_stale_sources_do_not_engage() {
  auto r = ArbitrateThruster(false, Manual(Cmd::kPort, false, true),
                             Manual(Cmd::kStbd, false, true));
  TEST_ASSERT_FALSE(r.engage_request);
}

// A source that goes away mid-thrust stops commanding immediately -- the
// direction it was holding is not retained (fail to off, not to last value).
void test_source_loss_drops_the_manual_direction() {
  ThrusterRemote tx = Manual(Cmd::kStbd);
  TEST_ASSERT_TRUE(ArbitrateThruster(false, tx, None()).manual_cmd == Cmd::kStbd);
  tx.live = false;
  auto r = ArbitrateThruster(false, tx, None());
  TEST_ASSERT_TRUE(r.manual_cmd == Cmd::kOff);
  TEST_ASSERT_FALSE(r.engage_request);
}

// ---- Structural mode/field separation ----

void test_hold_mode_clears_any_manual_direction() {
  ThrusterRemote tx = Hold();
  tx.manual_cmd = Cmd::kStbd;  // stale field a caller must never act on
  auto r = ArbitrateThruster(false, tx, None());
  TEST_ASSERT_TRUE(r.mode == ThrusterMode::kHold);
  TEST_ASSERT_TRUE(r.manual_cmd == Cmd::kOff);
}

void test_manual_mode_clears_any_commanded_trim() {
  ThrusterRemote tx = Manual(Cmd::kPort);
  tx.trim_deg = 30.0f;  // stale field a caller must never act on in manual
  auto r = ArbitrateThruster(false, tx, None());
  TEST_ASSERT_TRUE(r.mode == ThrusterMode::kManual);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, r.trim_deg);
}

void test_hold_mode_passes_commanded_trim_through() {
  auto r = ArbitrateThruster(false, Hold(15.0f), None());
  TEST_ASSERT_TRUE(r.mode == ThrusterMode::kHold);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 15.0f, r.trim_deg);
}

void test_hold_mode_clamps_an_out_of_range_trim() {
  // The arbiter re-clamps as defence in depth even if a boundary let a huge
  // value through.
  auto r = ArbitrateThruster(false, Hold(999.0f), None());
  TEST_ASSERT_FLOAT_WITHIN(0.001f, control_core::kMaxTrimDeg, r.trim_deg);
}

void test_hold_without_trim_holds_the_captured_heading() {
  auto r = ArbitrateThruster(false, Hold(0.0f), None());
  TEST_ASSERT_TRUE(r.engage_request);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, r.trim_deg);
}

// Each source carries its own mode: the plugin holding while TX is manual
// must not blend.
void test_mode_follows_the_winning_source_only() {
  auto r = ArbitrateThruster(false, Manual(Cmd::kPort), Hold(10.0f));
  TEST_ASSERT_TRUE(r.mode == ThrusterMode::kManual);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, r.trim_deg);

  auto r2 = ArbitrateThruster(false, Hold(10.0f), Manual(Cmd::kPort));
  TEST_ASSERT_TRUE(r2.mode == ThrusterMode::kHold);
  TEST_ASSERT_TRUE(r2.manual_cmd == Cmd::kOff);
}

// ---- SK string parsing defaults to the safe mode ----

void test_mode_string_parsing() {
  TEST_ASSERT_TRUE(ThrusterModeFromSkString("manual") == ThrusterMode::kManual);
  TEST_ASSERT_TRUE(ThrusterModeFromSkString("hold") == ThrusterMode::kHold);
}

void test_unknown_mode_string_reads_as_hold_not_manual() {
  // Garbage must never be interpreted as "a human is directly driving the
  // thruster" -- same defensive default as the drives treating a malformed
  // command as NEUTRAL.
  TEST_ASSERT_TRUE(ThrusterModeFromSkString("") == ThrusterMode::kHold);
  TEST_ASSERT_TRUE(ThrusterModeFromSkString("MANUAL") == ThrusterMode::kHold);
  TEST_ASSERT_TRUE(ThrusterModeFromSkString("manua") == ThrusterMode::kHold);
  TEST_ASSERT_TRUE(ThrusterModeFromSkString("manually") == ThrusterMode::kHold);
  TEST_ASSERT_TRUE(ThrusterModeFromSkString(nullptr) == ThrusterMode::kHold);
}

// ---- Direction string round-trip (TX publishes, HH parses) ----

void test_thruster_cmd_string_round_trip() {
  using control_core::ThrusterCmdFromSkString;
  using control_core::ThrusterCmdToSkString;
  TEST_ASSERT_TRUE(ThrusterCmdFromSkString(ThrusterCmdToSkString(Cmd::kPort)) ==
                   Cmd::kPort);
  TEST_ASSERT_TRUE(ThrusterCmdFromSkString(ThrusterCmdToSkString(Cmd::kStbd)) ==
                   Cmd::kStbd);
  TEST_ASSERT_TRUE(ThrusterCmdFromSkString(ThrusterCmdToSkString(Cmd::kOff)) ==
                   Cmd::kOff);
}

void test_unknown_thruster_cmd_string_reads_as_off() {
  using control_core::ThrusterCmdFromSkString;
  TEST_ASSERT_TRUE(ThrusterCmdFromSkString("") == Cmd::kOff);
  TEST_ASSERT_TRUE(ThrusterCmdFromSkString("PORT") == Cmd::kOff);
  TEST_ASSERT_TRUE(ThrusterCmdFromSkString("por") == Cmd::kOff);
  TEST_ASSERT_TRUE(ThrusterCmdFromSkString("portside") == Cmd::kOff);
  TEST_ASSERT_TRUE(ThrusterCmdFromSkString("starboard") == Cmd::kOff);
  TEST_ASSERT_TRUE(ThrusterCmdFromSkString(nullptr) == Cmd::kOff);
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_local_engage_wins_over_live_enabled_tx_manual);
  RUN_TEST(test_local_engage_ignores_remote_commanded_trim);
  RUN_TEST(test_tx_outranks_plugin_when_both_qualify);
  RUN_TEST(test_plugin_commands_only_in_tx_absence);
  RUN_TEST(test_tx_stale_hands_over_to_plugin);
  RUN_TEST(test_tx_disarmed_hands_over_to_plugin);
  RUN_TEST(test_result_independent_of_recency_no_oscillation);
  RUN_TEST(test_no_source_means_no_engage_request);
  RUN_TEST(test_live_but_disarmed_sources_do_not_engage);
  RUN_TEST(test_armed_but_stale_sources_do_not_engage);
  RUN_TEST(test_source_loss_drops_the_manual_direction);
  RUN_TEST(test_hold_mode_clears_any_manual_direction);
  RUN_TEST(test_manual_mode_clears_any_commanded_trim);
  RUN_TEST(test_hold_mode_passes_commanded_trim_through);
  RUN_TEST(test_hold_mode_clamps_an_out_of_range_trim);
  RUN_TEST(test_hold_without_trim_holds_the_captured_heading);
  RUN_TEST(test_mode_follows_the_winning_source_only);
  RUN_TEST(test_mode_string_parsing);
  RUN_TEST(test_unknown_mode_string_reads_as_hold_not_manual);
  RUN_TEST(test_thruster_cmd_string_round_trip);
  RUN_TEST(test_unknown_thruster_cmd_string_reads_as_off);
  return UNITY_END();
}
