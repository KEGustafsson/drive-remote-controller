#include <unity.h>

#include <cmath>
#include <limits>

#include "heading/heading_nudge.h"
#include "heading/setpoint.h"

using control_core::ClampTrimDeg;
using control_core::HeadingNudge;
using control_core::kMaxTrimDeg;
using control_core::SlewSetpointDeg;
using control_core::TrimHoldAllowed;

void setUp() {}
void tearDown() {}

// ============================ SlewSetpointDeg ============================
// SlewSetpointDeg still slews the setpoint toward an ABSOLUTE effective target
// (the captured heading + a clamped trim); only the source of that target
// changed. These behaviours are unchanged.

void test_slew_moves_at_most_one_step_per_tick() {
  // 5 deg/s for 100 ms = 0.5 deg per tick, whatever the target asks for.
  float s = SlewSetpointDeg(0.0f, 90.0f, 0.1f, 5.0f);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.5f, s);
}

void test_slew_lands_exactly_on_target_within_one_step() {
  float s = SlewSetpointDeg(89.9f, 90.0f, 0.1f, 5.0f);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 90.0f, s);
}

void test_slew_takes_the_short_way_across_the_wrap() {
  // From 170 to -170 is +20 the short way, not -340.
  float s = SlewSetpointDeg(170.0f, -170.0f, 1.0f, 5.0f);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 175.0f, s);
}

void test_slew_result_stays_wrapped() {
  float s = SlewSetpointDeg(179.0f, -175.0f, 1.0f, 5.0f);
  TEST_ASSERT_TRUE(s > -180.0f && s <= 180.0f);
}

void test_slew_converges_over_many_ticks() {
  float s = 0.0f;
  for (int i = 0; i < 400; ++i) s = SlewSetpointDeg(s, 45.0f, 0.02f, 10.0f);
  TEST_ASSERT_FLOAT_WITHIN(0.01f, 45.0f, s);
}

// A big change (a full +/-kMaxTrimDeg trim from the captured heading) must
// arrive gradually -- this is the whole point of the rate limit.
void test_large_change_cannot_arrive_instantly() {
  float s = 0.0f;
  for (int i = 0; i < 20; ++i) s = SlewSetpointDeg(s, 45.0f, 0.02f, 10.0f);
  TEST_ASSERT_TRUE(s < 12.0f);  // ~0.4 s of travel, not 45 degrees
}

// ---- Non-finite target boundary (defence in depth; production always
// passes a finite captured-heading + clamped-trim) ----

void test_nan_target_leaves_setpoint_untouched() {
  const float nan_v = std::numeric_limits<float>::quiet_NaN();
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 10.0f,
                           SlewSetpointDeg(10.0f, nan_v, 0.02f, 10.0f));
}

void test_infinite_target_leaves_setpoint_untouched() {
  const float inf = std::numeric_limits<float>::infinity();
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 10.0f,
                           SlewSetpointDeg(10.0f, inf, 0.02f, 10.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 10.0f,
                           SlewSetpointDeg(10.0f, -inf, 0.02f, 10.0f));
}

void test_bad_rate_or_dt_leaves_setpoint_untouched() {
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 10.0f,
                           SlewSetpointDeg(10.0f, 90.0f, 0.02f, 0.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 10.0f,
                           SlewSetpointDeg(10.0f, 90.0f, 0.0f, 10.0f));
  // An absurd dt (a stalled tick, a clock jump) must not teleport the setpoint.
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 10.0f,
                           SlewSetpointDeg(10.0f, 90.0f, 60.0f, 10.0f));
}

// ============================== ClampTrimDeg ==============================
// The relative-trim boundary. Unlike the old absolute IsPlausibleCommandedHeading
// (which REJECTED bad values, because 0 was a bogus heading), here 0 is the
// safe rest, so garbage collapses to 0 = no trim and finite values are clamped.

void test_clamp_passes_a_value_within_range() {
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 15.0f, ClampTrimDeg(15.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, -30.0f, ClampTrimDeg(-30.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, ClampTrimDeg(0.0f));
}

void test_clamp_bounds_to_the_trim_limit() {
  TEST_ASSERT_FLOAT_WITHIN(0.001f, kMaxTrimDeg, ClampTrimDeg(90.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, -kMaxTrimDeg, ClampTrimDeg(-90.0f));
  // A huge finite value (e.g. an old 999 sentinel, or corruption) clamps, not
  // hangs and not rejected -- it just saturates the trim.
  TEST_ASSERT_FLOAT_WITHIN(0.001f, kMaxTrimDeg, ClampTrimDeg(999.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, kMaxTrimDeg, ClampTrimDeg(1.0e30f));
}

void test_clamp_maps_non_finite_to_zero_no_trim() {
  // NaN and Inf -- including a JSON null read as 0.0f, which is already 0 --
  // all mean "no trim", the safe rest. (An absolute target could not do this:
  // 0.0f would have been a real "hold north" command.)
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f,
                           ClampTrimDeg(std::numeric_limits<float>::quiet_NaN()));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f,
                           ClampTrimDeg(std::numeric_limits<float>::infinity()));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f,
                           ClampTrimDeg(-std::numeric_limits<float>::infinity()));
}

// =========================== HeadingNudge (relative) ==========================
// The station's trim accumulator. Now a RELATIVE offset starting at 0, needing
// no seed and no "valid" flag: 0 is a well-defined command (hold the captured
// heading), so pressing is always meaningful.

namespace {
HeadingNudge::Cfg NudgeCfg() {
  HeadingNudge::Cfg c;
  c.fine_step_deg = 1.0f;
  c.coarse_step_deg = 10.0f;
  c.repeat_delay_ms = 750;
  c.repeat_period_ms = 400;
  return c;
}
}  // namespace

void test_nudge_starts_at_zero_no_trim() {
  HeadingNudge n(NudgeCfg());
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, n.trim_deg());
}

void test_first_press_trims_from_zero_no_seed_needed() {
  HeadingNudge n(NudgeCfg());
  n.Update(false, true, 0);  // stbd tap -- well-defined with no HH knowledge
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 1.0f, n.trim_deg());
}

void test_port_decreases_stbd_increases() {
  HeadingNudge n(NudgeCfg());
  n.Update(true, false, 0);  // port -> -1
  TEST_ASSERT_FLOAT_WITHIN(0.001f, -1.0f, n.trim_deg());
  n.Update(false, false, 10);  // release
  n.Update(false, true, 20);   // stbd tap -> 0
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, n.trim_deg());
}

void test_tap_trims_once_not_every_tick() {
  HeadingNudge n(NudgeCfg());
  n.Update(false, true, 0);
  for (uint32_t t = 10; t < 750; t += 10) n.Update(false, true, t);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 1.0f, n.trim_deg());
}

void test_holding_repeats_by_the_coarse_step() {
  HeadingNudge n(NudgeCfg());
  n.Update(false, true, 0);    // 1
  n.Update(false, true, 750);  // first coarse repeat -> 11
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 11.0f, n.trim_deg());
  n.Update(false, true, 900);   // too soon
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 11.0f, n.trim_deg());
  n.Update(false, true, 1150);  // next period -> 21
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 21.0f, n.trim_deg());
}

void test_release_and_repress_starts_from_a_fine_step_again() {
  HeadingNudge n(NudgeCfg());
  n.Update(false, true, 0);
  n.Update(false, true, 750);   // coarse -> 11
  n.Update(false, false, 760);  // release
  n.Update(false, true, 770);   // fresh tap -> 12
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 12.0f, n.trim_deg());
}

void test_both_buttons_pressed_does_nothing() {
  HeadingNudge n(NudgeCfg());
  n.Update(true, true, 0);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, n.trim_deg());
}

void test_sliding_between_buttons_restarts_as_a_fine_step() {
  HeadingNudge n(NudgeCfg());
  n.Update(false, true, 0);    // stbd -> 1
  n.Update(false, true, 750);  // coarse -> 11
  n.Update(true, false, 760);  // slide to port, no release -> 10
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 10.0f, n.trim_deg());
}

void test_trim_is_clamped_to_the_limit_however_long_held() {
  HeadingNudge n(NudgeCfg());
  // Hold starboard for a minute: the offset saturates at kMaxTrimDeg, it does
  // not wind around past it.
  for (uint32_t t = 0; t <= 60000; t += 100) n.Update(false, true, t);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, kMaxTrimDeg, n.trim_deg());
}

void test_invalidate_resets_the_trim_to_zero() {
  HeadingNudge n(NudgeCfg());
  n.Update(false, true, 0);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 1.0f, n.trim_deg());
  n.Invalidate();
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, n.trim_deg());
  // A stale offset must not come back; the next press starts fresh from 0.
  n.Update(false, true, 100);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 1.0f, n.trim_deg());
}

void test_stuck_button_cannot_outrun_the_slew_limiter() {
  // Even a saturated trim only ever asks for kMaxTrimDeg off the base; the
  // slew limiter is what governs the boat. Confirm the two compose: applied to
  // a base of 0, the setpoint reaches the trim only at bounded speed.
  HeadingNudge n(NudgeCfg());
  for (uint32_t t = 0; t <= 60000; t += 100) n.Update(false, true, t);
  float s = 0.0f;
  for (int i = 0; i < 50; ++i) {  // 1 s of ticks at 10 deg/s
    s = SlewSetpointDeg(s, n.trim_deg(), 0.02f, 10.0f);
  }
  TEST_ASSERT_TRUE(s <= 10.5f);  // 1 s at 10 deg/s, nowhere near the full 45
}

// ==================== TrimHoldAllowed: arm-first, then trim ==================
// ARCHITECTURE.md §6.4. The gate every station applies before letting an
// accumulated trim stand: arming holds the heading captured AT THE ARM, never
// an offset dialled in beforehand.

void test_trim_is_kept_only_in_hold_with_authority_and_a_live_unit() {
  TEST_ASSERT_TRUE(TrimHoldAllowed(true, true, true));
}

void test_trim_is_dropped_in_manual_mode() {
  // The buttons are the direction there; an offset means nothing.
  TEST_ASSERT_FALSE(TrimHoldAllowed(false, true, true));
}

void test_trim_is_dropped_while_the_station_has_no_authority() {
  // The term TX originally forgot. On TX the enable switch IS the arm, so a
  // trim banked while it was off would be applied by the act of arming.
  TEST_ASSERT_FALSE(TrimHoldAllowed(true, false, true));
}

void test_trim_is_dropped_when_the_thruster_unit_stops_answering() {
  TEST_ASSERT_FALSE(TrimHoldAllowed(true, true, false));
}

void test_a_station_without_authority_cannot_bank_a_trim_for_its_next_arm() {
  // The whole rule, driven through the accumulator the way a station's sample
  // loop drives it: hold the stbd button for five seconds while disarmed, and
  // the offset on the wire is still 0 -- so the arm that follows holds the
  // heading HH captures, not +45 deg off it.
  HeadingNudge n(NudgeCfg());
  for (uint32_t t = 0; t <= 5000; t += 20) {
    if (TrimHoldAllowed(/*hold_mode=*/true, /*station_enabled=*/false,
                        /*hh_live=*/true)) {
      n.Update(false, true, t);
    } else {
      n.Invalidate();
    }
  }
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, n.trim_deg());

  // ...and trimming still works normally once the station does have authority,
  // starting from 0 rather than from anything held over.
  n.Update(false, true, 5020);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 1.0f, n.trim_deg());
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_slew_moves_at_most_one_step_per_tick);
  RUN_TEST(test_slew_lands_exactly_on_target_within_one_step);
  RUN_TEST(test_slew_takes_the_short_way_across_the_wrap);
  RUN_TEST(test_slew_result_stays_wrapped);
  RUN_TEST(test_slew_converges_over_many_ticks);
  RUN_TEST(test_large_change_cannot_arrive_instantly);
  RUN_TEST(test_nan_target_leaves_setpoint_untouched);
  RUN_TEST(test_infinite_target_leaves_setpoint_untouched);
  RUN_TEST(test_bad_rate_or_dt_leaves_setpoint_untouched);
  RUN_TEST(test_clamp_passes_a_value_within_range);
  RUN_TEST(test_clamp_bounds_to_the_trim_limit);
  RUN_TEST(test_clamp_maps_non_finite_to_zero_no_trim);
  RUN_TEST(test_nudge_starts_at_zero_no_trim);
  RUN_TEST(test_first_press_trims_from_zero_no_seed_needed);
  RUN_TEST(test_port_decreases_stbd_increases);
  RUN_TEST(test_tap_trims_once_not_every_tick);
  RUN_TEST(test_holding_repeats_by_the_coarse_step);
  RUN_TEST(test_release_and_repress_starts_from_a_fine_step_again);
  RUN_TEST(test_both_buttons_pressed_does_nothing);
  RUN_TEST(test_sliding_between_buttons_restarts_as_a_fine_step);
  RUN_TEST(test_trim_is_clamped_to_the_limit_however_long_held);
  RUN_TEST(test_invalidate_resets_the_trim_to_zero);
  RUN_TEST(test_stuck_button_cannot_outrun_the_slew_limiter);
  RUN_TEST(test_trim_is_kept_only_in_hold_with_authority_and_a_live_unit);
  RUN_TEST(test_trim_is_dropped_in_manual_mode);
  RUN_TEST(test_trim_is_dropped_while_the_station_has_no_authority);
  RUN_TEST(test_trim_is_dropped_when_the_thruster_unit_stops_answering);
  RUN_TEST(test_a_station_without_authority_cannot_bank_a_trim_for_its_next_arm);
  return UNITY_END();
}
