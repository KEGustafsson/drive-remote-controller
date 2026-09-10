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

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_fresh_cache_stays_live);
  RUN_TEST(test_expires_one_ms_past_the_timeout);
  RUN_TEST(test_repeated_ageing_cannot_revive);
  RUN_TEST(test_dead_stays_dead);
  RUN_TEST(test_survives_millis_rollover);
  RUN_TEST(test_thruster_snapshot_ages_the_same_way);
  return UNITY_END();
}
