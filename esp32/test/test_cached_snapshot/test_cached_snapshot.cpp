#include <unity.h>

#include "common/cached_snapshot.h"
#include "drive/arbitration.h"
#include "heading/thruster_arbitration.h"

using control_core::AgeCachedSnapshot;
using control_core::RemoteSource;
using control_core::ThrusterRemote;

void setUp() {}
void tearDown() {}

namespace {
constexpr uint32_t kTimeoutMs = 1000;
}  // namespace

// A copy taken inside the timeout still speaks for its source. This is the
// normal contended-tick case: a callback collision is not a link loss.
void test_fresh_cache_stays_live() {
  RemoteSource c{};
  c.live = true;
  c.last_update_ms = 5000;
  AgeCachedSnapshot(c, 5000 + kTimeoutMs, kTimeoutMs);
  TEST_ASSERT_TRUE(c.live);
}

// Exactly at the timeout is still live; one millisecond past it is not. Pinned
// because an off-by-one here is the difference between a source that expires on
// its own budget and one that gets an extra tick of authority.
void test_expires_one_ms_past_the_timeout() {
  RemoteSource c{};
  c.live = true;
  c.last_update_ms = 5000;
  AgeCachedSnapshot(c, 5000 + kTimeoutMs + 1, kTimeoutMs);
  TEST_ASSERT_FALSE(c.live);
}

// Cached authority cannot outlive the source timeout, however many ticks are
// contended in a row.
void test_repeated_ageing_cannot_revive() {
  RemoteSource c{};
  c.live = true;
  c.last_update_ms = 5000;
  AgeCachedSnapshot(c, 9000, kTimeoutMs);
  TEST_ASSERT_FALSE(c.live);
  // A later call with a timestamp that WOULD be inside the window must not
  // bring it back: only a fresh snapshot may do that, and this function has no
  // new evidence to offer.
  AgeCachedSnapshot(c, 5000, kTimeoutMs);
  TEST_ASSERT_FALSE(c.live);
}

// A source that was already dead stays dead.
void test_dead_stays_dead() {
  RemoteSource c{};
  c.live = false;
  c.last_update_ms = 5000;
  AgeCachedSnapshot(c, 5000, kTimeoutMs);
  TEST_ASSERT_FALSE(c.live);
}

// The ~49.7-day millis() rollover. Unsigned subtraction gives the true elapsed
// time across it; comparing the timestamps directly would read a wrapped clock
// as an enormous age and drop a perfectly healthy source mid-manoeuvre.
void test_survives_millis_rollover() {
  RemoteSource c{};
  c.live = true;
  c.last_update_ms = 0xFFFFFF00u;  // shortly before wrap
  const uint32_t now = 0x00000100u;  // shortly after wrap; 512 ms elapsed
  AgeCachedSnapshot(c, now, kTimeoutMs);
  TEST_ASSERT_TRUE(c.live);

  // And still expires correctly on the far side of the wrap.
  AgeCachedSnapshot(c, 0x00000500u, kTimeoutMs);
  TEST_ASSERT_FALSE(c.live);
}

// The thruster family carries a different type with the same two fields, and
// must age identically -- the drive and thruster paths drifting apart is the
// specific failure this shared helper exists to prevent.
void test_thruster_snapshot_ages_the_same_way() {
  ThrusterRemote t{};
  t.live = true;
  t.last_update_ms = 5000;
  AgeCachedSnapshot(t, 5000 + kTimeoutMs, kTimeoutMs);
  TEST_ASSERT_TRUE(t.live);
  AgeCachedSnapshot(t, 5000 + kTimeoutMs + 1, kTimeoutMs);
  TEST_ASSERT_FALSE(t.live);
}

// A contended tick ages the cached copy locally, and it must age on the window
// the copy's OWN mode earns -- the same rule Snapshot() applies when it can take
// the mutex. A cached hold at 1.5 s is still commanding; the identical cache in
// manual is not. Without this the ageing path would quietly re-impose one window
// on both gates on every contended tick.
void test_thruster_cache_ages_on_the_window_its_mode_earns() {
  using control_core::ThrusterMode;
  using control_core::ThrusterStaleness;
  using control_core::ThrusterStalenessMsFor;
  constexpr ThrusterStaleness kWindows{2000, 1000};

  ThrusterRemote holding{};
  holding.live = true;
  holding.last_update_ms = 5000;
  holding.mode = ThrusterMode::kHold;
  AgeCachedSnapshot(holding, 6500,
                    ThrusterStalenessMsFor(holding.mode, kWindows));
  TEST_ASSERT_TRUE(holding.live);

  ThrusterRemote manual{};
  manual.live = true;
  manual.last_update_ms = 5000;
  manual.mode = ThrusterMode::kManual;
  AgeCachedSnapshot(manual, 6500,
                    ThrusterStalenessMsFor(manual.mode, kWindows));
  TEST_ASSERT_FALSE(manual.live);

  // And the hold's extra tolerance is bounded, not unlimited.
  AgeCachedSnapshot(holding, 7001,
                    ThrusterStalenessMsFor(holding.mode, kWindows));
  TEST_ASSERT_FALSE(holding.live);
}

// A cached copy whose newest member was stamped after the tick's clock (the
// ordering race in common/elapsed_ms.h) is fresh, not ~49 days old -- the
// contended-tick path must not drop a source the uncontended path keeps.
void test_cache_stamped_after_now_stays_live() {
  ThrusterRemote t{};
  t.live = true;
  t.last_update_ms = 5002;
  AgeCachedSnapshot(t, 5000, 2000);
  TEST_ASSERT_TRUE(t.live);
  AgeCachedSnapshot(t, 7002, 2000);
  TEST_ASSERT_TRUE(t.live);
  AgeCachedSnapshot(t, 7003, 2000);
  TEST_ASSERT_FALSE(t.live);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_fresh_cache_stays_live);
  RUN_TEST(test_expires_one_ms_past_the_timeout);
  RUN_TEST(test_repeated_ageing_cannot_revive);
  RUN_TEST(test_dead_stays_dead);
  RUN_TEST(test_survives_millis_rollover);
  RUN_TEST(test_thruster_snapshot_ages_the_same_way);
  RUN_TEST(test_thruster_cache_ages_on_the_window_its_mode_earns);
  RUN_TEST(test_cache_stamped_after_now_stays_live);
  return UNITY_END();
}
