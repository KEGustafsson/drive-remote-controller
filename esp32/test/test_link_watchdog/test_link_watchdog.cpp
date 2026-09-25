#include <unity.h>

#include "common/elapsed_ms.h"
#include "drive/link_watchdog.h"

using control_core::AllLive;
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

// The clock-ordering race (common/elapsed_ms.h). The control task reads its
// clock at the top of a tick; an SK callback that completes before the tick
// reaches its snapshot stamps a LATER millis(). That update is the freshest
// evidence the unit has, and it must read live -- plain unsigned subtraction
// read it as ~49 days old, latched the source stale until its next delta, and
// on HH ended an engaged hold through the re-engage latch.
void test_update_stamped_after_now_is_live() {
  LinkWatchdog w;
  w.Update(5001);
  TEST_ASSERT_TRUE(w.IsLive(5000, kTimeoutMs));
  TEST_ASSERT_TRUE(w.HasEverUpdated());  // not latched stale
  w.Update(5007);
  TEST_ASSERT_TRUE(w.IsLive(5000, kTimeoutMs));
  // And it still expires on its own budget once the clock passes it.
  TEST_ASSERT_TRUE(w.IsLive(5007 + kTimeoutMs, kTimeoutMs));
  TEST_ASSERT_FALSE(w.IsLive(5007 + kTimeoutMs + 1, kTimeoutMs));
}

// The same race across the millis() rollover: stamp just after the wrap, tick
// clock just before it.
void test_update_stamped_after_now_across_rollover_is_live() {
  LinkWatchdog w;
  w.Update(0x00000002u);
  TEST_ASSERT_TRUE(w.IsLive(0xFFFFFFFEu, kTimeoutMs));
}

// ElapsedMs itself: ordinary ages, ages straddling the rollover, and the
// boundary where a difference stops being an age and becomes a future stamp.
void test_elapsed_ms() {
  using control_core::ElapsedMs;
  using control_core::IsFutureTimestamp;
  TEST_ASSERT_EQUAL_UINT32(0, ElapsedMs(5000, 5000));
  TEST_ASSERT_EQUAL_UINT32(250, ElapsedMs(5250, 5000));
  TEST_ASSERT_EQUAL_UINT32(512, ElapsedMs(0x00000100u, 0xFFFFFF00u));
  TEST_ASSERT_FALSE(IsFutureTimestamp(5250, 5000));
  // Later than now: age 0, and flagged.
  TEST_ASSERT_EQUAL_UINT32(0, ElapsedMs(5000, 5001));
  TEST_ASSERT_TRUE(IsFutureTimestamp(5000, 5001));
  TEST_ASSERT_EQUAL_UINT32(0, ElapsedMs(0xFFFFFFFEu, 0x00000002u));
  // The largest real age (just under 24.8 days) is still an age.
  TEST_ASSERT_EQUAL_UINT32(0x7FFFFFFFu, ElapsedMs(0x7FFFFFFFu, 0));
  TEST_ASSERT_FALSE(IsFutureTimestamp(0x7FFFFFFFu, 0));
  TEST_ASSERT_TRUE(IsFutureTimestamp(0x80000000u, 0));
}

// A source that genuinely goes silent is still found stale, however the clock
// race is handled -- the fix must not hand a dead station extra authority.
void test_silent_source_still_goes_stale_at_its_timeout() {
  LinkWatchdog w;
  w.Update(5000);
  for (uint32_t t = 5000; t <= 5000 + kTimeoutMs; t += 10) {
    TEST_ASSERT_TRUE(w.IsLive(t, kTimeoutMs));
  }
  TEST_ASSERT_FALSE(w.IsLive(5000 + kTimeoutMs + 10, kTimeoutMs));
  TEST_ASSERT_FALSE(w.HasEverUpdated());
}

// Independent instances don't share state.
void test_instances_are_independent() {
  LinkWatchdog a;
  LinkWatchdog b;
  a.Update(100);
  TEST_ASSERT_TRUE(a.IsLive(100, kTimeoutMs));
  TEST_ASSERT_FALSE(b.IsLive(100, kTimeoutMs));
}

// AllLive polls every member even once one is stale, so every stale member
// latches. With a short-circuit the later members were never polled: after
// 2^31 ms of silence ElapsedMs read their old stamps as age 0, and a fresh
// update on the first member alone made the whole month-old tuple live.
void test_all_live_latches_every_member_even_after_one_is_stale() {
  LinkWatchdog port;
  LinkWatchdog stbd;
  LinkWatchdog enabled;
  port.Update(1000);
  stbd.Update(1000);
  enabled.Update(1000);
  TEST_ASSERT_TRUE(AllLive(1000, kTimeoutMs, port, stbd, enabled));

  // Silence: the first poll after the timeout must latch all three.
  TEST_ASSERT_FALSE(AllLive(1000 + kTimeoutMs + 20, kTimeoutMs, port, stbd,
                            enabled));
  TEST_ASSERT_FALSE(port.HasEverUpdated());
  TEST_ASSERT_FALSE(stbd.HasEverUpdated());
  TEST_ASSERT_FALSE(enabled.HasEverUpdated());

  // 25 days later only the first member's path updates. The others' stamps
  // are now in the upper half of the range, which ElapsedMs reads as age 0 --
  // the latch is the only thing keeping them out.
  const uint32_t later = 1000u + 25u * 24u * 3600u * 1000u;
  port.Update(later);
  TEST_ASSERT_FALSE(AllLive(later, kTimeoutMs, port, stbd, enabled));
}

// Every member is polled on every call, not just until the first false.
void test_all_live_needs_every_member() {
  LinkWatchdog a;
  LinkWatchdog b;
  a.Update(100);
  TEST_ASSERT_FALSE(AllLive(100, kTimeoutMs, a, b));
  b.Update(100);
  TEST_ASSERT_TRUE(AllLive(100, kTimeoutMs, a, b));
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
  RUN_TEST(test_update_stamped_after_now_is_live);
  RUN_TEST(test_update_stamped_after_now_across_rollover_is_live);
  RUN_TEST(test_elapsed_ms);
  RUN_TEST(test_silent_source_still_goes_stale_at_its_timeout);
  RUN_TEST(test_all_live_latches_every_member_even_after_one_is_stale);
  RUN_TEST(test_all_live_needs_every_member);
  return UNITY_END();
}
