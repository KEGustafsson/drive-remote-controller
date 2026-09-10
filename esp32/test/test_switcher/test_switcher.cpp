#include <unity.h>

#include <cmath>

#include "heading/switcher.h"

using control_core::Cmd;
using control_core::SwitchCfg;
using control_core::Switcher;

namespace {

// Timing gates are non-blocking (near zero) so hysteresis/duty behavior
// can be tested in isolation from min-on/min-off/dwell timing.
SwitchCfg NoTimingCfg() {
  SwitchCfg cfg;
  cfg.on_thr_deg = 3.0f;
  cfg.off_thr_deg = 1.0f;
  cfg.lead_time_s = 0.0f;
  cfg.min_on_s = 0.0f;
  // The pulse cap is a timing gate like any other, so "no timing" disables it
  // too. Its own behaviour is covered by the max_on_* cases below, which build
  // their own cfg rather than leaning on this one.
  cfg.max_on_s = 1000.0f;
  cfg.min_off_s = 0.0f;
  cfg.reversal_dwell_s = 0.0f;
  cfg.duty_warn = 0.5f;
  cfg.duty_max = 0.8f;
  cfg.duty_window_s = 1.0f;
  return cfg;
}

// Real timing gates, duty effectively disabled (huge window / never
// reached) so timing behavior can be tested in isolation from duty.
SwitchCfg TimingCfg() {
  SwitchCfg cfg;
  cfg.on_thr_deg = 3.0f;
  cfg.off_thr_deg = 1.0f;
  cfg.lead_time_s = 1.0f;
  cfg.min_on_s = 0.2f;
  cfg.max_on_s = 1000.0f;  // see NoTimingCfg
  cfg.min_off_s = 0.1f;
  cfg.reversal_dwell_s = 0.5f;
  cfg.duty_warn = 0.9f;
  cfg.duty_max = 0.95f;
  cfg.duty_window_s = 1000.0f;
  return cfg;
}

}  // namespace

void setUp() {}
void tearDown() {}

// ==========================================================================
// Structural invariant: never a direct reversal, only via OFF
// ==========================================================================

void test_opposite_sign_overshoot_goes_off_before_reversing() {
  Switcher sw(TimingCfg());
  uint32_t t = 10000;

  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));  // s=10 -> STBD

  // A huge swing to the opposite sign must release the now-wrong direction,
  // but min-on still applies and no direct reversal is possible.
  t += 250;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(-100.0f, 0.0f, t));
  TEST_ASSERT_TRUE(sw.current_cmd() != Cmd::kPort);

  // The reversal dwell then withholds the opposite direction.
  t += 100;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(-100.0f, 0.0f, t));
  t += 500;
  TEST_ASSERT_EQUAL(Cmd::kPort, sw.Update(-100.0f, 0.0f, t));
}

// ==========================================================================
// Lead term prevents overshoot (ARCHITECTURE.md §7 worked example)
// ==========================================================================

void test_lead_term_stops_early_when_closing_fast() {
  Switcher sw(TimingCfg());
  uint32_t t = 10000;

  // e=10, r=0 -> s=10 > on_thr(3) -> engage STBD.
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));

  // Past min_on (200 ms). Error has closed to 5 deg but rate is now
  // 5 deg/s: s = 5 - 1*5 = 0 -> below off_thr while error is still
  // nonzero. This is the anticipation that prevents overshoot.
  t += 250;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(5.0f, 5.0f, t));
}

void test_without_lead_same_error_would_not_stop() {
  // Same nonzero error, but no closing rate: without the lead term the
  // switcher must keep thrusting (s = e = 5, above off_thr).
  Switcher sw(TimingCfg());
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  t += 250;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(5.0f, 0.0f, t));
}

// ==========================================================================
// Hysteresis: off_thr < on_thr, no chatter across the deadband
// ==========================================================================

void test_no_chatter_below_on_threshold() {
  Switcher sw(NoTimingCfg());
  uint32_t t = 10000;
  for (int i = 0; i < 5; ++i) {
    TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(2.9f, 0.0f, t));  // just under on_thr
    t += 10;
  }
}

void test_no_chatter_in_deadband_while_thrusting() {
  Switcher sw(NoTimingCfg());
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  t += 10;
  // Between off_thr(1) and on_thr(3): must stay thrusting, not toggle.
  for (int i = 0; i < 5; ++i) {
    TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(2.0f, 0.0f, t));
    t += 10;
  }
}

// ==========================================================================
// Timing gates: min_on, min_off, reversal_dwell
// ==========================================================================

void test_min_on_enforced() {
  Switcher sw(TimingCfg());
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));

  // Off-condition satisfied (s=0) but min_on (200ms) hasn't elapsed yet.
  t += 50;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(0.0f, 0.0f, t));

  // Now min_on has elapsed -- OFF is honored.
  t += 200;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.0f, 0.0f, t));
}

void test_min_off_enforced() {
  Switcher sw(TimingCfg());
  uint32_t t = 10000;
  sw.Update(10.0f, 0.0f, t);
  t += 250;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.0f, 0.0f, t));

  // Requesting ON again before min_off (100ms) has elapsed is vetoed.
  t += 50;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(10.0f, 0.0f, t));

  // After min_off elapses, same-direction re-engagement is honored.
  t += 100;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
}

void test_reversal_dwell_blocks_fast_reverse_but_not_same_direction() {
  Switcher sw(TimingCfg());  // min_off=100ms, reversal_dwell=500ms
  uint32_t t = 10000;
  sw.Update(10.0f, 0.0f, t);  // engage STBD
  t += 250;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.0f, 0.0f, t));  // leave STBD -> OFF

  // Past min_off (100ms) but well short of reversal_dwell (500ms):
  // opposite direction (PORT) must still be blocked.
  t += 150;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(-10.0f, 0.0f, t));

  // Same direction (STBD) only needs min_off, already satisfied -- must
  // be allowed even though dwell hasn't elapsed.
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
}

void test_reversal_allowed_once_dwell_elapses() {
  Switcher sw(TimingCfg());
  uint32_t t = 10000;
  sw.Update(10.0f, 0.0f, t);
  t += 250;
  sw.Update(0.0f, 0.0f, t);  // -> OFF

  t += 600;  // past reversal_dwell (500ms) and min_off
  TEST_ASSERT_EQUAL(Cmd::kPort, sw.Update(-10.0f, 0.0f, t));
}

// ==========================================================================
// Duty accounting, deadband widening, and inhibition
// ==========================================================================

void test_duty_rises_while_thrusting_continuously() {
  Switcher sw(NoTimingCfg());  // duty_window_s = 1.0
  uint32_t t = 10000;
  sw.Update(10.0f, 0.0f, t);  // engage, s always 10 (never crosses off_thr)
  for (int i = 0; i < 30; ++i) {
    t += 100;
    sw.Update(10.0f, 0.0f, t);
  }
  TEST_ASSERT_TRUE(sw.duty() > 0.8f);
}

void test_duty_warn_widens_effective_thresholds() {
  Switcher sw(NoTimingCfg());  // on_thr=3, duty_warn=0.5, duty_max=0.8
  uint32_t t = 10000;
  sw.Update(10.0f, 0.0f, t);
  // Enough ticks to push duty comfortably past duty_warn but not to
  // duty_max.
  for (int i = 0; i < 10; ++i) {
    t += 100;
    sw.Update(10.0f, 0.0f, t);
  }
  TEST_ASSERT_TRUE(sw.duty() > 0.5f);
  TEST_ASSERT_TRUE(sw.duty() < 0.8f);

  // Leave thrusting (s=0 -> OFF; NoTimingCfg has no min_on gate).
  t += 100;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.0f, 0.0f, t));

  // A marginal error that would engage at the nominal on_thr(3) must be
  // rejected now that the deadband is widened by the elevated duty.
  t += 10;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(3.5f, 0.0f, t));

  // A comfortably larger error still gets through -- duty widens the
  // deadband, it doesn't disable the switcher outright.
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
}

void test_duty_max_inhibits_new_on_until_recovery() {
  Switcher sw(NoTimingCfg());
  uint32_t t = 10000;
  sw.Update(10.0f, 0.0f, t);
  for (int i = 0; i < 30; ++i) {
    t += 100;
    sw.Update(10.0f, 0.0f, t);
  }
  TEST_ASSERT_TRUE(sw.duty() >= 0.8f);  // at/above duty_max

  t += 100;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.0f, 0.0f, t));  // leave thrusting

  // Even a huge error must NOT re-engage while duty_max-inhibited.
  t += 10;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(100.0f, 0.0f, t));

  // Let duty decay while OFF until it drops back below duty_warn, then
  // the same large error must engage again.
  Cmd cmd = Cmd::kOff;
  for (int i = 0; i < 100 && cmd == Cmd::kOff; ++i) {
    t += 100;
    cmd = sw.Update(100.0f, 0.0f, t);
  }
  TEST_ASSERT_EQUAL(Cmd::kStbd, cmd);
  TEST_ASSERT_TRUE(sw.duty() < 0.8f);
}

// TrackDuty(): the duty model follows the ACTUAL thrust state on every tick,
// not just HOLD-mode Update() calls (2026-07-23 review finding: the EMA was
// frozen outside HOLDING, so an inhibition acquired at the end of a hold
// stood forever across an arbitrarily long disarmed rest -- reading as a
// stuck fault for a motor that had genuinely cooled).
void test_track_duty_decays_while_idle_and_releases_inhibition() {
  Switcher sw(NoTimingCfg());
  uint32_t t = 10000;
  // Push duty to inhibition exactly as test_duty_max_inhibits... does.
  sw.Update(10.0f, 0.0f, t);
  for (int i = 0; i < 30; ++i) {
    t += 100;
    sw.Update(10.0f, 0.0f, t);
  }
  TEST_ASSERT_TRUE(sw.duty() >= 0.8f);
  t += 100;
  sw.Update(0.0f, 0.0f, t);  // leave thrusting

  // Operator disengages: no more Update() calls at all -- only the per-tick
  // TrackDuty(off) the control step now performs. The rest must decay the
  // EMA just as staying in HOLD-but-off would have.
  for (int i = 0; i < 100; ++i) {
    t += 100;
    sw.TrackDuty(false, t);
  }
  TEST_ASSERT_TRUE(sw.duty() < 0.5f);  // decayed well below duty_warn

  // Re-engaging after the rest works immediately: the inhibition released.
  t += 100;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(100.0f, 0.0f, t));
}

void test_track_duty_accumulates_during_manual_thrust() {
  Switcher sw(NoTimingCfg());
  uint32_t t = 10000;
  sw.TrackDuty(false, t);  // seed the clock
  // A long continuous MANUAL thrust (the switcher itself never commanded
  // it) must heat the duty model exactly as a held thrust would, so a
  // following HOLD session starts from the true figure.
  for (int i = 0; i < 30; ++i) {
    t += 100;
    sw.TrackDuty(true, t);
  }
  TEST_ASSERT_TRUE(sw.duty() >= 0.8f);
  // And the inhibition it caused gates the next HOLD engagement.
  t += 10;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(100.0f, 0.0f, t));
}

void test_track_duty_same_tick_as_update_is_a_noop() {
  Switcher sw(NoTimingCfg());
  uint32_t t = 10000;
  sw.Update(10.0f, 0.0f, t);
  for (int i = 0; i < 5; ++i) {
    t += 100;
    sw.Update(10.0f, 0.0f, t);
    float duty_after_update = sw.duty();
    // The control step calls TrackDuty after Update in the same tick; dt is
    // zero there, so the sample must not be double-counted.
    sw.TrackDuty(true, t);
    TEST_ASSERT_FLOAT_WITHIN(1e-7f, duty_after_update, sw.duty());
  }
}

// ==========================================================================
// Reset(): a new hold session starts from a clean OFF, no stale timing leak,
// but duty (S2 thermal) memory survives.
// ==========================================================================

void test_reset_clears_switching_state_but_keeps_duty() {
  Switcher sw(TimingCfg());  // reversal_dwell=500ms, min_off=100ms
  uint32_t t = 10000;

  // Drive STBD, then push duty up while continuously thrusting.
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  for (int i = 0; i < 20; ++i) {
    t += 100;
    sw.Update(10.0f, 0.0f, t);
  }
  float duty_before = sw.duty();
  TEST_ASSERT_TRUE(duty_before > 0.0f);
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.current_cmd());

  // A new hold begins: reset. Switching state is now OFF; duty is preserved.
  t += 50;
  sw.Reset(t);
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.current_cmd());
  TEST_ASSERT_FLOAT_WITHIN(1e-6f, duty_before, sw.duty());

  // Because last_thrust_dir_ was cleared by Reset(), the FIRST engagement of
  // the new session is treated as a fresh ON (only min_off gates it), NOT a
  // reversal of the pre-reset STBD -- so PORT can engage after just min_off,
  // without waiting the full reversal_dwell that a real reversal would need.
  t += 150;  // > min_off (100ms), < reversal_dwell (500ms)
  TEST_ASSERT_EQUAL(Cmd::kPort, sw.Update(-10.0f, 0.0f, t));
}

// ==========================================================================
// SetTunables(): live web-UI tuning (ARCHITECTURE.md §11) that
// must not weaken the safety-relevant knobs it deliberately doesn't touch.
// ==========================================================================

void test_set_tunables_applies_new_thresholds() {
  Switcher sw(NoTimingCfg());  // starts on_thr=3, off_thr=1
  // Tighten the deadband live, as a sea-trial tuning step would.
  sw.SetTunables(/*on_thr_deg=*/1.0f, /*off_thr_deg=*/0.3f,
                  /*lead_time_s=*/0.0f, /*min_on_s=*/0.0f,
                  /*max_on_s=*/10.0f,
                  /*min_off_s=*/0.0f, /*duty_warn=*/0.5f, /*duty_max=*/0.8f);
  uint32_t t = 10000;
  // Would have stayed OFF under the old on_thr(3); must engage under the
  // new, tighter on_thr(1).
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(1.5f, 0.0f, t));
}

void test_set_tunables_clamps_off_thr_below_on_thr() {
  Switcher sw(NoTimingCfg());
  // Bad live value: off_thr_deg(5) >= on_thr_deg(3) -- must be clamped to
  // on_thr_deg * 0.5, never accepted as-is (would break the Schmitt
  // no-chatter guarantee off_thr < on_thr).
  sw.SetTunables(/*on_thr_deg=*/3.0f, /*off_thr_deg=*/5.0f,
                  /*lead_time_s=*/0.0f, /*min_on_s=*/0.0f,
                  /*max_on_s=*/10.0f,
                  /*min_off_s=*/0.0f, /*duty_warn=*/0.5f, /*duty_max=*/0.8f);
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));  // s=10 > on_thr(3)
  t += 10;
  // s=2 is above the clamped off_thr (1.5) -> must still be thrusting. The
  // unclamped bad off_thr(5) would have forced OFF here instead.
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(2.0f, 0.0f, t));
}

void test_set_tunables_does_not_weaken_reversal_dwell() {
  Switcher sw(TimingCfg());  // reversal_dwell=500ms, min_off=100ms
  // A live tuning update -- must not shorten the measured reversal dead
  // time (MEASUREMENTS.md Item 2), even though everything else changes.
  sw.SetTunables(/*on_thr_deg=*/3.0f, /*off_thr_deg=*/1.0f,
                  /*lead_time_s=*/1.0f, /*min_on_s=*/0.2f,
                  /*max_on_s=*/10.0f,
                  /*min_off_s=*/0.1f, /*duty_warn=*/0.9f, /*duty_max=*/0.95f);
  uint32_t t = 10000;
  sw.Update(10.0f, 0.0f, t);  // engage STBD
  t += 250;
  sw.Update(0.0f, 0.0f, t);  // -> OFF

  t += 150;  // past min_off(100ms), short of reversal_dwell(500ms)
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(-10.0f, 0.0f, t));  // still blocked

  t += 450;  // now past reversal_dwell too
  TEST_ASSERT_EQUAL(Cmd::kPort, sw.Update(-10.0f, 0.0f, t));
}

// ==========================================================================
// SetTunables boundary validation: these values come from flash-persisted,
// web-editable settings, so garbage (NaN/inf/negative/absurd) must be
// rejected or clamped at this boundary, never written into cfg_.
// ==========================================================================

// Non-finite values are rejected outright: the previous (last-good /
// constructed-default) tunables keep governing behavior.
void test_set_tunables_rejects_non_finite_values() {
  Switcher sw(NoTimingCfg());  // on_thr=3, off_thr=1, lead=0
  sw.SetTunables(/*on_thr_deg=*/NAN, /*off_thr_deg=*/NAN,
                  /*lead_time_s=*/INFINITY, /*min_on_s=*/NAN,
                  /*max_on_s=*/NAN,
                  /*min_off_s=*/-INFINITY, /*duty_warn=*/NAN,
                  /*duty_max=*/NAN);
  uint32_t t = 10000;
  // Still the original Schmitt thresholds: 2.5 deg is below on_thr(3)...
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(2.5f, 0.0f, t));
  t += 10;
  // ...4 deg engages (a clamped-to-max on_thr of 45 would NOT engage here,
  // proving NaN kept the previous value rather than being range-clamped)...
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(4.0f, 0.0f, t));
  t += 10;
  // ...and 0.5 deg is inside the original off_thr(1) deadband -> OFF.
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.5f, 0.0f, t));
}

// A negative on-threshold would make s > on_thr true at ZERO error --
// spurious thrust with the boat already on heading. Must clamp positive.
void test_set_tunables_negative_on_thr_cannot_cause_spurious_thrust() {
  Switcher sw(NoTimingCfg());
  sw.SetTunables(/*on_thr_deg=*/-5.0f, /*off_thr_deg=*/0.05f,
                  /*lead_time_s=*/0.0f, /*min_on_s=*/0.0f,
                  /*max_on_s=*/10.0f,
                  /*min_off_s=*/0.0f, /*duty_warn=*/0.5f, /*duty_max=*/0.8f);
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.0f, 0.0f, t));   // on heading
  t += 10;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.05f, 0.0f, t));  // noise-level e
  t += 10;
  // A real error beyond the clamped minimum threshold still engages.
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(1.0f, 0.0f, t));
}

// A finite but absurd on-threshold is range-clamped (to kOnThrMaxDeg), not
// accepted -- otherwise the switcher could never engage at all.
void test_set_tunables_clamps_out_of_range_on_thr() {
  Switcher sw(NoTimingCfg());
  sw.SetTunables(/*on_thr_deg=*/1.0e9f, /*off_thr_deg=*/1.0f,
                  /*lead_time_s=*/0.0f, /*min_on_s=*/0.0f,
                  /*max_on_s=*/10.0f,
                  /*min_off_s=*/0.0f, /*duty_warn=*/0.5f, /*duty_max=*/0.8f);
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(40.0f, 0.0f, t));  // < clamp (45)
  t += 10;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(50.0f, 0.0f, t));  // > clamp
}

// A huge min_on must not weld the thruster on: it is bounded (to
// kMinOnOffMaxS), so the pulse still ends.
void test_set_tunables_bounds_huge_min_on() {
  Switcher sw(NoTimingCfg());
  sw.SetTunables(/*on_thr_deg=*/3.0f, /*off_thr_deg=*/1.0f,
                  /*lead_time_s=*/0.0f, /*min_on_s=*/1.0e9f,
                  /*max_on_s=*/10.0f,
                  /*min_off_s=*/0.0f, /*duty_warn=*/0.5f, /*duty_max=*/0.8f);
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  // Error closed: wants OFF, but min_on (clamped to 30 s) still blocks...
  t += 29000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(0.0f, 0.0f, t));
  // ...and past the 30 s bound the pulse ends -- unbounded it never would.
  //
  // The cap is not what ended it: kMaxOnMaxS (10 s) is below min_on's bound
  // (30 s), and min_on gates every exit from thrusting, so a 10 s cap simply
  // cannot fire first. The cross-field rule raises the stored cap to the
  // sanitized min_on for exactly that reason -- an unreachable cap protects
  // nothing. This case therefore still pins min_on's bound specifically.
  t += 2000;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.0f, 0.0f, t));
}

// ==========================================================================
// max_on_s: the hard cap on one continuous thrust. The control law normally
// releases long before it -- these cases are about the situation where it
// never would, because the boat is not answering the helm at all (pinned by
// wind or current, fouled thruster, dead or wrong-signed yaw rate). There the
// lead variable sits at e forever, and before the cap existed the only other
// brake was duty inhibition, which is consulted solely when LEAVING off and so
// could never interrupt a thrust already running.
// ==========================================================================

// A cfg where a thrust can never release on the threshold: with lead_time 0
// and a constant error, s stays pinned above off_thr no matter how long the
// motor runs. Only the cap can end the pulse.
SwitchCfg CapCfg() {
  SwitchCfg cfg;
  cfg.on_thr_deg = 3.0f;
  cfg.off_thr_deg = 1.0f;
  cfg.lead_time_s = 0.0f;
  cfg.min_on_s = 0.1f;
  cfg.max_on_s = 2.0f;
  cfg.min_off_s = 0.5f;
  cfg.reversal_dwell_s = 0.0f;
  cfg.duty_warn = 0.5f;
  cfg.duty_max = 0.7f;
  cfg.duty_window_s = 2.0f;
  return cfg;
}

void test_max_on_ends_a_thrust_that_would_never_release() {
  Switcher sw(CapCfg());
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));  // s=10, engages

  // Just short of the 2 s allowance: still driving, because s(10) is still
  // far above off_thr(1) and nothing else would ever stop it.
  t += 1990;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));

  // The allowance is spent. The error has NOT changed -- the control law
  // still wants thrust -- and the pulse ends anyway.
  t += 10;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(10.0f, 0.0f, t));
}

// The cap must not interfere with a pulse the control law ends on its own.
void test_max_on_does_not_shorten_a_normal_pulse() {
  Switcher sw(CapCfg());
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));

  // Well inside the allowance, the boat comes onto heading: the normal
  // threshold release fires, exactly as it did before the cap existed.
  t += 300;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.5f, 0.0f, t));
}

// The payoff: under a load the boat cannot answer, the cap converts what was
// one unbounded burn into a pulse train, and the OFF windows it creates are
// what finally let the S2 duty limiter inhibit further thrust.
void test_max_on_lets_duty_inhibition_bite_on_a_stuck_boat() {
  Switcher sw(CapCfg());
  uint32_t t = 10000;

  uint32_t longest_on_ms = 0;
  uint32_t longest_off_ms = 0;
  uint32_t current_on_ms = 0;
  uint32_t current_off_ms = 0;
  float peak_duty = 0.0f;
  // 15 s of a boat that will not turn: constant 10 deg error, zero yaw rate.
  for (int i = 0; i < 1500; ++i) {
    t += 10;
    if (sw.Update(10.0f, 0.0f, t) == Cmd::kOff) {
      current_on_ms = 0;
      current_off_ms += 10;
      if (current_off_ms > longest_off_ms) longest_off_ms = current_off_ms;
    } else {
      current_off_ms = 0;
      current_on_ms += 10;
      if (current_on_ms > longest_on_ms) longest_on_ms = current_on_ms;
    }
    if (sw.duty() > peak_duty) peak_duty = sw.duty();
  }

  // No single burn outran the allowance (one tick of tolerance for the
  // sampling above).
  TEST_ASSERT_TRUE(longest_on_ms <= 2010);
  // Duty reached duty_max, so the S2 limiter latched at least once -- which it
  // can only do from the OFF windows the cap created.
  TEST_ASSERT_TRUE(peak_duty >= 0.7f);
  // ...and it did more than latch: some OFF window ran materially longer than
  // min_off (0.5 s), so something beyond the ordinary timing gate was refusing
  // to re-engage. That refusal is the protection working. Duty is NOT asserted
  // at the end of the run: once inhibited the motor stops, duty decays, the
  // latch clears at duty_warn and the cycle repeats, so the final sample lands
  // anywhere in [duty_warn, duty_max] depending only on where the loop stopped.
  TEST_ASSERT_TRUE(longest_off_ms > 600);
}

// Same stuck boat, cap effectively disabled: the motor runs continuously and
// duty inhibition never gets a chance to act. This is the behaviour the cap
// exists to remove, pinned here so it cannot quietly come back.
void test_without_cap_a_stuck_boat_thrusts_continuously() {
  SwitchCfg cfg = CapCfg();
  cfg.max_on_s = 1000.0f;
  Switcher sw(cfg);
  uint32_t t = 10000;

  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  for (int i = 0; i < 1500; ++i) {
    t += 10;
    TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  }
  // Duty is saturated and the latch is set, yet the thrust continues --
  // duty_inhibited_ is only ever consulted when leaving OFF.
  TEST_ASSERT_TRUE(sw.duty() > 0.7f);
}

// A cap below min_on could never fire, since min_on gates every exit from
// thrusting. It must be raised to min_on rather than stored unreachable.
void test_set_tunables_raises_max_on_to_min_on() {
  Switcher sw(NoTimingCfg());
  sw.SetTunables(/*on_thr_deg=*/3.0f, /*off_thr_deg=*/1.0f,
                  /*lead_time_s=*/0.0f, /*min_on_s=*/1.0f,
                  /*max_on_s=*/0.2f,  // bad: below min_on
                  /*min_off_s=*/0.0f, /*duty_warn=*/0.5f, /*duty_max=*/0.8f);
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  // Held demand, so only the cap can end this. At 0.9 s neither min_on (1 s)
  // nor the raised cap (also 1 s) has elapsed.
  t += 900;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  // Past 1 s the raised cap fires. Stored at 0.2 s it would have been dead
  // weight and this pulse would run forever.
  t += 200;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(10.0f, 0.0f, t));
}

// Consequence of the rule above that is worth pinning because it surprises:
// a long min_on drags the cap ABOVE its own kMaxOnMaxS ceiling. That is
// inherent -- a cap below the floor that gates every exit from thrusting
// cannot fire, so min_on always wins -- and it is not a regression: min_on's
// own bound (kMinOnOffMaxS, 30 s) already permitted a 30 s weld before the cap
// existed (see test_set_tunables_bounds_huge_min_on). The cap can only ever
// shorten a pulse, never lengthen one beyond what min_on already allowed.
void test_max_on_follows_min_on_above_its_own_ceiling() {
  Switcher sw(NoTimingCfg());
  sw.SetTunables(/*on_thr_deg=*/3.0f, /*off_thr_deg=*/1.0f,
                  /*lead_time_s=*/0.0f, /*min_on_s=*/20.0f,
                  /*max_on_s=*/2.0f,  // asked for well under kMaxOnMaxS...
                  /*min_off_s=*/0.0f, /*duty_warn=*/0.5f, /*duty_max=*/0.8f);
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  // ...but min_on(20 s) governs, so the pulse outlasts kMaxOnMaxS(10 s).
  t += 15000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  // It still ends at min_on rather than running forever.
  t += 5100;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(10.0f, 0.0f, t));
}

// An absurd persisted cap is clamped to kMaxOnMaxS, not accepted -- otherwise
// a corrupt flash value would disable the protection entirely.
void test_set_tunables_clamps_absurd_max_on() {
  Switcher sw(NoTimingCfg());
  sw.SetTunables(/*on_thr_deg=*/3.0f, /*off_thr_deg=*/1.0f,
                  /*lead_time_s=*/0.0f, /*min_on_s=*/0.0f,
                  /*max_on_s=*/1.0e9f,
                  /*min_off_s=*/0.0f, /*duty_warn=*/0.5f, /*duty_max=*/0.8f);
  uint32_t t = 10000;
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  t += 9990;  // just inside the 10 s ceiling
  TEST_ASSERT_EQUAL(Cmd::kStbd, sw.Update(10.0f, 0.0f, t));
  t += 20;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(10.0f, 0.0f, t));
}

// duty_warn >= duty_max would let the inhibit latch clear the tick after it
// set (ema < warn is immediately true again), gutting the S2 duty limiter.
// warn must be clamped below max so inhibition holds until real recovery.
void test_set_tunables_clamps_duty_warn_below_duty_max() {
  Switcher sw(NoTimingCfg());  // duty_window_s = 1.0
  sw.SetTunables(/*on_thr_deg=*/3.0f, /*off_thr_deg=*/1.0f,
                  /*lead_time_s=*/0.0f, /*min_on_s=*/0.0f,
                  /*max_on_s=*/10.0f,
                  /*min_off_s=*/0.0f, /*duty_warn=*/0.9f,  // bad: >= max
                  /*duty_max=*/0.3f);
  uint32_t t = 10000;
  // Thrust continuously until duty crosses duty_max(0.3) -> inhibited.
  for (int i = 0; i < 40; ++i) {
    t += 10;
    sw.Update(10.0f, 0.0f, t);
  }
  TEST_ASSERT_TRUE(sw.duty() > 0.3f);
  t += 10;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(0.0f, 0.0f, t));  // error closed
  // Immediately demand thrust again: duty (~0.3) is still far above the
  // clamped warn (0.15), so inhibition must hold. With the bad warn(0.9)
  // accepted, it would clear instantly and re-engage here.
  t += 10;
  TEST_ASSERT_EQUAL(Cmd::kOff, sw.Update(10.0f, 0.0f, t));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_opposite_sign_overshoot_goes_off_before_reversing);
  RUN_TEST(test_lead_term_stops_early_when_closing_fast);
  RUN_TEST(test_without_lead_same_error_would_not_stop);
  RUN_TEST(test_no_chatter_below_on_threshold);
  RUN_TEST(test_no_chatter_in_deadband_while_thrusting);
  RUN_TEST(test_min_on_enforced);
  RUN_TEST(test_min_off_enforced);
  RUN_TEST(test_reversal_dwell_blocks_fast_reverse_but_not_same_direction);
  RUN_TEST(test_reversal_allowed_once_dwell_elapses);
  RUN_TEST(test_duty_rises_while_thrusting_continuously);
  RUN_TEST(test_duty_warn_widens_effective_thresholds);
  RUN_TEST(test_duty_max_inhibits_new_on_until_recovery);
  RUN_TEST(test_track_duty_decays_while_idle_and_releases_inhibition);
  RUN_TEST(test_track_duty_accumulates_during_manual_thrust);
  RUN_TEST(test_track_duty_same_tick_as_update_is_a_noop);
  RUN_TEST(test_reset_clears_switching_state_but_keeps_duty);
  RUN_TEST(test_set_tunables_applies_new_thresholds);
  RUN_TEST(test_set_tunables_clamps_off_thr_below_on_thr);
  RUN_TEST(test_set_tunables_does_not_weaken_reversal_dwell);
  RUN_TEST(test_set_tunables_rejects_non_finite_values);
  RUN_TEST(test_set_tunables_negative_on_thr_cannot_cause_spurious_thrust);
  RUN_TEST(test_set_tunables_clamps_out_of_range_on_thr);
  RUN_TEST(test_set_tunables_bounds_huge_min_on);
  RUN_TEST(test_set_tunables_clamps_duty_warn_below_duty_max);
  RUN_TEST(test_max_on_ends_a_thrust_that_would_never_release);
  RUN_TEST(test_max_on_does_not_shorten_a_normal_pulse);
  RUN_TEST(test_max_on_lets_duty_inhibition_bite_on_a_stuck_boat);
  RUN_TEST(test_without_cap_a_stuck_boat_thrusts_continuously);
  RUN_TEST(test_set_tunables_raises_max_on_to_min_on);
  RUN_TEST(test_max_on_follows_min_on_above_its_own_ceiling);
  RUN_TEST(test_set_tunables_clamps_absurd_max_on);
  return UNITY_END();
}
