#include <unity.h>

#include "drive/link_watchdog.h"

using control_core::LinkWatchdog;

void setUp() {}
void tearDown() {}

namespace {
constexpr uint32_t kTimeoutMs = 1000;
}  // namespace

// A source that has never sent an update at all must never read as live --
// distinct from "stale" (it updated once, long ago). "No data yet" must not
// read as live under any circumstances.
void test_never_updated_is_not_live() {
  LinkWatchdog w;
  TEST_ASSERT_FALSE(w.HasEverUpdated());
  TEST_ASSERT_FALSE(w.IsLive(0, kTimeoutMs));
  TEST_ASSERT_FALSE(w.IsLive(1000000, kTimeoutMs));
}

void test_live_immediately_after_update() {
  LinkWatchdog w;
  w.Update(5000);
  TEST_ASSERT_TRUE(w.HasEverUpdated());
  TEST_ASSERT_TRUE(w.IsLive(5000, kTimeoutMs));
}

// Exactly at the timeout boundary is still live (<=); one tick past is not.
void test_timeout_boundary() {
  LinkWatchdog w;
  w.Update(5000);
  TEST_ASSERT_TRUE(w.IsLive(5000 + kTimeoutMs, kTimeoutMs));
  TEST_ASSERT_FALSE(w.IsLive(5000 + kTimeoutMs + 1, kTimeoutMs));
}

void test_stale_after_gap_with_no_update() {
  LinkWatchdog w;
  w.Update(0);
  TEST_ASSERT_TRUE(w.IsLive(500, kTimeoutMs));
  TEST_ASSERT_FALSE(w.IsLive(2000, kTimeoutMs));
}

// The reason liveness is time-based rather than a counter comparison: a source
// that goes stale for far longer than any plausible counter wrap, then
// sends a single fresh update, must be recognized as live on that very next
// call -- no dependency on catching up to a previous value.
void test_source_restart_is_live_on_next_update_regardless_of_gap() {
  LinkWatchdog w;
  w.Update(1000);
  TEST_ASSERT_TRUE(w.IsLive(1000, kTimeoutMs));

  // Long gap -- source went stale (e.g. it rebooted).
  uint32_t restart_time = 1000 + kTimeoutMs * 1000;  // far beyond any timeout
  TEST_ASSERT_FALSE(w.IsLive(restart_time, kTimeoutMs));

  // A single update after the restart -- live immediately, same call.
  w.Update(restart_time);
  TEST_ASSERT_TRUE(w.IsLive(restart_time, kTimeoutMs));
}

// Repeated updates keep pushing the live window forward.
void test_repeated_updates_keep_it_live() {
  LinkWatchdog w;
  for (uint32_t t = 0; t <= 5000; t += 200) {
    w.Update(t);
    TEST_ASSERT_TRUE(w.IsLive(t, kTimeoutMs));
  }
}

// LastUpdateMs() reports exactly what was recorded, for callers that need
// to compare "most recently updated" across multiple live sources
// (arbitration's tie-break, ARCHITECTURE.md §5).
void test_last_update_ms_reports_recorded_time() {
  LinkWatchdog w;
  w.Update(4242);
  TEST_ASSERT_EQUAL_UINT32(4242, w.LastUpdateMs());
  w.Update(9999);
  TEST_ASSERT_EQUAL_UINT32(9999, w.LastUpdateMs());
}

// The unsigned age arithmetic wraps every 2^32 ms (~49.7 days). Without a
// latch, a source silent that long -- a TX switched off and left in a drawer
// while RX stays powered -- reads live again for one whole timeout window when
// the wrap brings its age back to a small number, resurrecting its last
// retained command and enable flag. Once found stale, it must stay stale until
// something new actually arrives.
void test_stale_verdict_is_latched_across_the_clock_wrap() {
  LinkWatchdog w;
  w.Update(1000);
  TEST_ASSERT_FALSE(w.IsLive(1000 + kTimeoutMs + 1, kTimeoutMs));  // latched
  TEST_ASSERT_FALSE(w.HasEverUpdated());
  // The clock has wrapped all the way round: now_ms equals the last update
  // again. Without the latch the age would read 0 and this would be live.
  TEST_ASSERT_FALSE(w.IsLive(1000, kTimeoutMs));
  TEST_ASSERT_FALSE(w.IsLive(1000 + kTimeoutMs, kTimeoutMs));
  // LastUpdateMs() is diagnostic and survives the latch.
  TEST_ASSERT_EQUAL_UINT32(1000, w.LastUpdateMs());
}

void test_update_after_a_latched_stale_is_live_again() {
  LinkWatchdog w;
  w.Update(1000);
  TEST_ASSERT_FALSE(w.IsLive(5000, kTimeoutMs));
  w.Update(5000);
  TEST_ASSERT_TRUE(w.HasEverUpdated());
  TEST_ASSERT_TRUE(w.IsLive(5000, kTimeoutMs));
}

// Independent instances don't share state.
void test_instances_are_independent() {
  LinkWatchdog a;
  LinkWatchdog b;
  a.Update(100);
  TEST_ASSERT_TRUE(a.IsLive(100, kTimeoutMs));
  TEST_ASSERT_FALSE(b.IsLive(100, kTimeoutMs));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_never_updated_is_not_live);
  RUN_TEST(test_live_immediately_after_update);
  RUN_TEST(test_timeout_boundary);
  RUN_TEST(test_stale_after_gap_with_no_update);
  RUN_TEST(test_source_restart_is_live_on_next_update_regardless_of_gap);
  RUN_TEST(test_repeated_updates_keep_it_live);
  RUN_TEST(test_last_update_ms_reports_recorded_time);
  RUN_TEST(test_stale_verdict_is_latched_across_the_clock_wrap);
  RUN_TEST(test_update_after_a_latched_stale_is_live_again);
  RUN_TEST(test_instances_are_independent);
  return UNITY_END();
}
