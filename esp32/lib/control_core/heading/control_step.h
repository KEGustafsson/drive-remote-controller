#pragma once

// Pure C++17 "control step": the complete per-tick decision pipeline
// (yaw-rate estimate -> heading fusion -> engage debounce -> safety FSM ->
// setpoint capture -> bang-bang switcher) with every hardware read/write
// stripped out. No Arduino.h -- host-testable under env:native.
//
// WHY THIS LAYER EXISTS: the individual pure modules (YawRate,
// HeadingFilter, SafetyFsm, Switcher, output_map) were each host-tested,
// but the riskiest behavior is how they are WIRED TOGETHER each tick --
// setpoint captured exactly once on entering HOLDING, stale GNSS coasting
// into a clean disengage, BNO silence dropping outputs the same tick, a
// held engage not silently resuming hold after a fault. That wiring used
// to live only inside ControlTask::Tick() (Arduino/FreeRTOS, untestable on
// host). It now lives here; src/control_task.cpp is reduced to hardware
// glue: read UART/GPIO -> Step() -> write GPIOs/telemetry. See
// test/test_control_step for the integration scenarios locked in.
//
// The (armed, dir) pair returned by Step() is designed to be handed
// verbatim to Outputs::apply(armed, dir) / ComputeOutputLevels(): dir is
// kOff unless state is kHolding, and armed is true only in
// kArmedIdle/kHolding, so all SAFETY.md thruster output invariants survive the
// trip through this layer unchanged.

#include <cstdint>

#include "common/debounce.h"
#include "heading/heading_filter.h"
#include "heading/manual_thrust.h"
#include "heading/safety_fsm.h"
#include "heading/switcher.h"
#include "heading/thruster_arbitration.h"
#include "heading/yaw_rate.h"

namespace control_core {

class ControlStep {
 public:
  struct Cfg {
    HeadingFilter::Cfg heading{};
    // Yaw-rate estimator (yaw_rate.h). Surfaced here rather than left to
    // YawRate's own ctor defaults because yaw_lpf_tau_s is the single
    // largest contributor to fused-heading lag, so it is a tuning knob the
    // firmware and the tests must be able to set and a reviewer must be able
    // to find -- not something buried in a default argument. The values
    // below intentionally mirror those defaults; see yaw_rate.h for what
    // each one costs.
    float yaw_spike_threshold_deg_per_tick = 2.0f;
    float yaw_lpf_tau_s = 0.1f;
    int yaw_max_consecutive_rejects = 3;
    FsmCfg fsm{};
    SwitchCfg switcher{};
    // Two-sided ENGAGE debounce (see debounce.h): stable-assert before a
    // rising edge can request hold, stable-release before a drop reads.
    uint32_t engage_assert_stable_ms = 50;
    uint32_t engage_release_stable_ms = 50;
    // Reversal dwell for MANUAL mode (manual_thrust.h), independent of
    // switcher.reversal_dwell_s. The firmware feeds config::kManualReversalDwellS
    // here and config::kReversalDwellS to the switcher, and the two are
    // deliberately DIFFERENT: HOLD waits out the measured control-box interlock
    // in full because nobody is watching the tunnel, MANUAL ships at 0 because a
    // human is (owner decision -- see config.h and manual_thrust.h). Whatever
    // value each gate carries, both now measure it against the same shared
    // last-thrust history, so a mode change cannot walk past either one.
    float manual_reversal_dwell_s = 1.85f;
    // Ceiling on how fast a REMOTELY COMMANDED hold target may drag the
    // setpoint (deg/s). Bounds a mis-tap or a bad delta to a slow correction
    // instead of a hard swing (setpoint.h).
    float setpoint_slew_dps = 10.0f;
  };

  // One tick's worth of already-sampled inputs. The caller owns all
  // hardware access; nothing here blocks or reads a clock.
  struct Inputs {
    uint32_t now_ms = 0;  // monotonic; same snapshot used for every check
    float dt_s = 0.0f;    // measured elapsed time since the previous Step()
    // A fresh, checksum-valid BNO RVC frame completed this tick (not every
    // tick has one); bno_yaw_deg is only meaningful when true.
    bool new_bno_frame = false;
    float bno_yaw_deg = 0.0f;
    // MEASUREMENTS.md Item 6 mount sign, applied to the yaw rate BEFORE it
    // reaches Predict() -- see src/control_task.h for why it can't wait
    // until publish time.
    bool yaw_rate_invert = false;
    bool bno_ok = false;      // !RvcReader::timedOut() (CLAUDE.md inv. 4)
    bool engage_raw = false;  // RAW local opto level; debounced internally
    bool deadman_ok = true;   // true if no deadman wired, or held
    GnssHeading gnss{};       // latest SK sample; any age, already
                              // quality-judged by the producer (valid flag)
    // Remote thruster command sources (TX, plugin), already judged live +
    // enabled by the caller. Arbitrated against the LOCAL engage input below
    // by thruster_arbitration.h -- local always wins (SAFETY.md thruster invariant 6).
    // Leaving both default-constructed reproduces the pre-remote-control
    // behaviour exactly: local engage or nothing.
    ThrusterRemote tx{};
    ThrusterRemote plugin{};
  };

  struct Outputs {
    // Hand these two straight to Outputs::apply(armed, dir).
    bool armed = false;
    Cmd dir = Cmd::kOff;

    FsmState state = FsmState::kDisarmed;
    float fused_deg = 0.0f;
    float r_dps = 0.0f;
    // The heading hold would take if it engaged right now; once engaged, the
    // heading it is holding. Driven by the Switcher only while HOLDING in HOLD
    // mode; everywhere else (disarmed, idle, faulted, MANUAL) it MIRRORS
    // fused_deg, so error_deg is truthfully zero rather than stale.
    float setpoint_deg = 0.0f;
    float error_deg = 0.0f;     // wrap(setpoint - fused)
    float duty = 0.0f;
    uint32_t heading_age_ms = 0;  // age of last ACCEPTED GNSS correction
    bool heading_valid = false;   // age <= t_fresh_s right now
    // The unit's OWN engage input after debounce, and only that -- a remote
    // source asking for thrust does not set it. What actually reached the FSM
    // is the arbitrated request; read `source`/`armed` for that.
    bool engage_debounced = false;

    // Who is commanding the thruster and in which mode, for telemetry and for
    // the operator's display. `source` is kLocal whenever the unit's own
    // ENGAGE input is asserted, regardless of what any remote is saying.
    ActiveSource source = ActiveSource::kNone;
    ThrusterMode mode = ThrusterMode::kHold;
    // True while a manual direction is being withheld solely because the
    // control box's reversal interlock has not expired (manual_thrust.h), so
    // the UI can say "reversing" rather than looking unresponsive.
    bool reversal_pending = false;
    // True while a NON-ZERO heading trim is being applied on top of the
    // on-engage captured heading (i.e. a station is actively trimming).
    bool setpoint_commanded = false;
  };

  explicit ControlStep(const Cfg& cfg);

  // Run one control tick. Call at the fixed control period with a
  // monotonic, strictly consistent now_ms (the same value the caller used
  // to judge bno_ok/gnss freshness).
  Outputs Step(const Inputs& in);

  // Live sea-trial tunables passthrough (ARCHITECTURE.md §11);
  // validated/clamped by
  // Switcher::SetTunables -- see that header for the boundary rules.
  void SetSwitchTunables(float on_thr_deg, float off_thr_deg,
                          float lead_time_s, float min_on_s, float max_on_s,
                          float min_off_s, float duty_warn, float duty_max);

 private:
  const Cfg cfg_;

  YawRate yaw_rate_;
  HeadingFilter heading_filter_;
  SafetyFsm safety_fsm_;
  Switcher switcher_;
  ManualThrust manual_thrust_;
  Debounce engage_debounce_;

  // Mode in force on the previous tick, so entering kManual can reset the
  // manual gate (and leaving it can reset the switcher) exactly once.
  ThrusterMode prev_mode_ = ThrusterMode::kHold;

  // The direction the THRUSTER was last actually driven in, and when that
  // thrust last stopped -- tracked from the emitted output, so it spans both
  // gates and every mode change. This is the hardware's own history: the
  // control box's anti-reversal interlock is a property of the box and knows
  // nothing about which software gate produced the previous thrust. Whichever
  // gate takes over is seeded from these (see Step()), so its reversal dwell is
  // measured against what the motor actually did, not against a fresh zero.
  Cmd last_thrust_dir_ = Cmd::kOff;
  uint32_t last_thrust_end_ms_ = 0;

  uint32_t last_applied_gnss_t_ms_ = 0;
  uint32_t last_yaw_update_ms_ = 0;
  float r_dps_ = 0.0f;  // held between BNO frames, sign already applied

  // The heading HH captured when it entered HOLDING, including a MANUAL->HOLD
  // mode transition that leaves the FSM continuously in HOLDING -- the fixed
  // anchor a commanded trim is measured from. Re-captured on each fresh
  // entry, so a trim always means
  // "this many degrees off whatever we were pointing when hold engaged", never
  // an absolute number carried over from an earlier session.
  float base_heading_deg_ = 0.0f;

  // Heading-hold setpoint (deg): while HOLDING in HOLD mode it slews toward
  // base_heading_deg_ + commanded trim at the rate limit; captured to base on
  // entry. In every other state it mirrors the fused heading (control_step.cpp),
  // so it is never stale and the reported error is never fictional.
  float setpoint_deg_ = 0.0f;
};

}  // namespace control_core
