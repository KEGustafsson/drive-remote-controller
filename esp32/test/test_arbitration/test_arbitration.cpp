#include <unity.h>

#include "drive/arbitration.h"

using control_core::ActiveSource;
using control_core::Arbitrate;
using control_core::DrivePosition;
using control_core::RemoteSource;

void setUp() {}
void tearDown() {}

namespace {
RemoteSource Source(DrivePosition cmd, bool enabled, bool live,
                     uint32_t last_update_ms) {
  RemoteSource s;
  s.command = cmd;
  s.enabled = enabled;
  s.live = live;
  s.last_update_ms = last_update_ms;
  return s;
}

RemoteSource NoSource() { return RemoteSource{}; }
}  // namespace

// ---- Invariant 3: local override is unconditional ----

void test_local_wins_over_live_enabled_remote() {
  RemoteSource tx = Source(DrivePosition::kReverse, true, true, 1000);
  RemoteSource plugin = NoSource();
  auto r = Arbitrate(DrivePosition::kForward, /*rx_armed=*/true, tx,
                      plugin);
  TEST_ASSERT_TRUE(r.command == DrivePosition::kForward);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kLocal);
}

void test_local_wins_even_when_disarmed() {
  RemoteSource tx = Source(DrivePosition::kReverse, true, true, 1000);
  auto r = Arbitrate(DrivePosition::kReverse, /*rx_armed=*/false,
                      tx, NoSource());
  TEST_ASSERT_TRUE(r.command == DrivePosition::kReverse);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kLocal);
}

void test_local_neutral_defers_to_remote() {
  RemoteSource tx = Source(DrivePosition::kForward, true, true, 1000);
  auto r = Arbitrate(DrivePosition::kNeutral, /*rx_armed=*/true, tx,
                      NoSource());
  TEST_ASSERT_TRUE(r.command == DrivePosition::kForward);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kTx);
}

// ---- Invariant 4: RX's arm state gates remote sources only ----
// rx_armed is drive/arm_gate.h's verdict -- master enable ON *and* the
// gear-neutral interlock satisfied (invariant 7). test_arm_gate/ owns the
// rules that produce it; here it is just the gate bool.

void test_disarmed_forces_neutral_even_with_live_remote() {
  RemoteSource tx = Source(DrivePosition::kForward, true, true, 1000);
  RemoteSource plugin = Source(DrivePosition::kReverse, true, true, 2000);
  auto r = Arbitrate(DrivePosition::kNeutral, /*rx_armed=*/false,
                      tx, plugin);
  TEST_ASSERT_TRUE(r.command == DrivePosition::kNeutral);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kNone);
}

// ---- Single-remote-qualifies cases ----

void test_tx_wins_when_plugin_stale() {
  RemoteSource tx = Source(DrivePosition::kForward, true, true, 1000);
  RemoteSource plugin = Source(DrivePosition::kReverse, true, false, 2000);
  auto r = Arbitrate(DrivePosition::kNeutral, true, tx, plugin);
  TEST_ASSERT_TRUE(r.command == DrivePosition::kForward);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kTx);
}

void test_plugin_wins_when_tx_stale() {
  RemoteSource tx = Source(DrivePosition::kForward, true, false, 1000);
  RemoteSource plugin = Source(DrivePosition::kReverse, true, true, 2000);
  auto r = Arbitrate(DrivePosition::kNeutral, true, tx, plugin);
  TEST_ASSERT_TRUE(r.command == DrivePosition::kReverse);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kPlugin);
}

void test_tx_wins_when_plugin_disabled_but_live() {
  RemoteSource tx = Source(DrivePosition::kForward, true, true, 1000);
  // Plugin is live (fresh updates) but its own enable/active flag is off --
  // must not be treated as authoritative just because it's live.
  RemoteSource plugin = Source(DrivePosition::kReverse, false, true, 2000);
  auto r = Arbitrate(DrivePosition::kNeutral, true, tx, plugin);
  TEST_ASSERT_TRUE(r.command == DrivePosition::kForward);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kTx);
}

void test_neither_live_nor_enabled_is_neutral() {
  auto r = Arbitrate(DrivePosition::kNeutral, true, NoSource(),
                      NoSource());
  TEST_ASSERT_TRUE(r.command == DrivePosition::kNeutral);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kNone);
}

void test_both_stale_or_disabled_is_neutral() {
  RemoteSource tx = Source(DrivePosition::kForward, true, false, 1000);
  RemoteSource plugin = Source(DrivePosition::kReverse, false, true, 2000);
  auto r = Arbitrate(DrivePosition::kNeutral, true, tx, plugin);
  TEST_ASSERT_TRUE(r.command == DrivePosition::kNeutral);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kNone);
}

// ---- Both remotes qualify: FIXED precedence, TX always outranks plugin ----

// TX qualifies and is also the more recently updated: it wins (as it always
// does when it qualifies).
void test_tx_beats_plugin_when_tx_more_recent() {
  RemoteSource tx = Source(DrivePosition::kForward, true, true, 5000);
  RemoteSource plugin = Source(DrivePosition::kReverse, true, true, 3000);
  auto r = Arbitrate(DrivePosition::kNeutral, true, tx, plugin);
  TEST_ASSERT_TRUE(r.command == DrivePosition::kForward);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kTx);
}

// The key anti-oscillation guarantee: even when the plugin is MORE recently
// updated than TX, TX still wins because precedence is fixed, not recency.
// Under the old most-recent-wins rule this returned the plugin -- the flip
// that produced servo oscillation when the two disagreed.
void test_tx_beats_plugin_even_when_plugin_more_recent() {
  RemoteSource tx = Source(DrivePosition::kForward, true, true, 3000);
  RemoteSource plugin = Source(DrivePosition::kReverse, true, true, 5000);
  auto r = Arbitrate(DrivePosition::kNeutral, true, tx, plugin);
  TEST_ASSERT_TRUE(r.command == DrivePosition::kForward);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kTx);
}

// Result must be independent of last_update_ms: sweeping the plugin's update
// time across (older than, equal to, newer than) TX yields the same TX win
// every time -- i.e. authority cannot flip as refreshes arrive.
void test_result_independent_of_recency_no_oscillation() {
  const uint32_t plugin_times[] = {1000, 4000, 4000, 9000};
  for (uint32_t t : plugin_times) {
    RemoteSource tx = Source(DrivePosition::kForward, true, true, 4000);
    RemoteSource plugin = Source(DrivePosition::kReverse, true, true, t);
    auto r = Arbitrate(DrivePosition::kNeutral, true, tx, plugin);
    TEST_ASSERT_TRUE(r.command == DrivePosition::kForward);
    TEST_ASSERT_TRUE(r.source == ActiveSource::kTx);
  }
}

// Plugin commands only when TX does NOT qualify (here: TX disabled). Confirms
// fixed precedence still lets the plugin through in TX's absence.
void test_plugin_commands_when_tx_not_qualifying() {
  RemoteSource tx = Source(DrivePosition::kForward, false, true, 5000);
  RemoteSource plugin = Source(DrivePosition::kReverse, true, true, 3000);
  auto r = Arbitrate(DrivePosition::kNeutral, true, tx, plugin);
  TEST_ASSERT_TRUE(r.command == DrivePosition::kReverse);
  TEST_ASSERT_TRUE(r.source == ActiveSource::kPlugin);
}

// ---- SAFETY.md drive invariant 1: port and starboard are fully
// independent -- exercised here as two independent Arbitrate() calls with
// opposite results, confirming no shared/global state leaks between them.
void test_two_drives_arbitrated_independently_opposite_results() {
  // "Port" call: local override to FORWARD.
  RemoteSource port_tx = Source(DrivePosition::kReverse, true, true, 1000);
  auto port = Arbitrate(DrivePosition::kForward, true, port_tx,
                         NoSource());

  // "Starboard" call: local neutral, remote commands REVERSE.
  RemoteSource stbd_tx = Source(DrivePosition::kReverse, true, true, 1000);
  auto stbd = Arbitrate(DrivePosition::kNeutral, true, stbd_tx, NoSource());

  TEST_ASSERT_TRUE(port.command == DrivePosition::kForward);
  TEST_ASSERT_TRUE(port.source == ActiveSource::kLocal);
  TEST_ASSERT_TRUE(stbd.command == DrivePosition::kReverse);
  TEST_ASSERT_TRUE(stbd.source == ActiveSource::kTx);

  // Calling Arbitrate() again for "port" with entirely different inputs
  // must not be affected by having just computed "starboard" (no hidden
  // static/global state).
  auto port_again = Arbitrate(DrivePosition::kForward, true, port_tx,
                               NoSource());
  TEST_ASSERT_TRUE(port_again.command == DrivePosition::kForward);
  TEST_ASSERT_TRUE(port_again.source == ActiveSource::kLocal);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_local_wins_over_live_enabled_remote);
  RUN_TEST(test_local_wins_even_when_disarmed);
  RUN_TEST(test_local_neutral_defers_to_remote);
  RUN_TEST(test_disarmed_forces_neutral_even_with_live_remote);
  RUN_TEST(test_tx_wins_when_plugin_stale);
  RUN_TEST(test_plugin_wins_when_tx_stale);
  RUN_TEST(test_tx_wins_when_plugin_disabled_but_live);
  RUN_TEST(test_neither_live_nor_enabled_is_neutral);
  RUN_TEST(test_both_stale_or_disabled_is_neutral);
  RUN_TEST(test_tx_beats_plugin_when_tx_more_recent);
  RUN_TEST(test_tx_beats_plugin_even_when_plugin_more_recent);
  RUN_TEST(test_result_independent_of_recency_no_oscillation);
  RUN_TEST(test_plugin_commands_when_tx_not_qualifying);
  RUN_TEST(test_two_drives_arbitrated_independently_opposite_results);
  return UNITY_END();
}
