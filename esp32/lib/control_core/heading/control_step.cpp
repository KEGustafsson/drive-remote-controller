#include "heading/control_step.h"

#include "heading/angle_math.h"
#include "heading/setpoint.h"

namespace control_core {

ControlStep::ControlStep(const Cfg& cfg)
    : cfg_(cfg),
      yaw_rate_(cfg_.yaw_spike_threshold_deg_per_tick, cfg_.yaw_lpf_tau_s,
                cfg_.yaw_max_consecutive_rejects),
      heading_filter_(cfg_.heading),
      safety_fsm_(cfg_.fsm),
      switcher_(cfg_.switcher),
      manual_thrust_(cfg_.manual_reversal_dwell_s),
      engage_debounce_(cfg_.engage_assert_stable_ms,
                       cfg_.engage_release_stable_ms) {}

void ControlStep::SetSwitchTunables(float on_thr_deg, float off_thr_deg,
                                     float lead_time_s, float min_on_s,
                                     float max_on_s, float min_off_s,
                                     float duty_warn, float duty_max) {
  switcher_.SetTunables(on_thr_deg, off_thr_deg, lead_time_s, min_on_s,
                         max_on_s, min_off_s, duty_warn, duty_max);
}

ControlStep::Outputs ControlStep::Step(const Inputs& in) {
  // 1. BNO frame -> rate filter. Only feed YawRate on an actual new frame,
  // using the real elapsed time since the last one -- not every control
  // tick necessarily has a fresh frame waiting.
  if (in.new_bno_frame) {
    float yaw_dt_s = (in.now_ms - last_yaw_update_ms_) / 1000.0f;
    float raw_r_dps = yaw_rate_.Update(in.bno_yaw_deg, yaw_dt_s);
    // Mount sign applied here, before Predict() -- the fused heading is a
    // live consumer, so an uncorrected sign would drift the estimate the
    // wrong way, not just flip a displayed rate.
    r_dps_ = in.yaw_rate_invert ? -raw_r_dps : raw_r_dps;
    last_yaw_update_ms_ = in.now_ms;
  }

  // 2. Heading fusion: fast path every tick, slow path once per new GNSS
  // sample (avoid reapplying the same fix's correction gain repeatedly
  // between server pushes).
  heading_filter_.Predict(r_dps_, in.dt_s);
  if (in.gnss.valid && in.gnss.t_ms != last_applied_gnss_t_ms_) {
    heading_filter_.Correct(in.gnss, in.now_ms);
    last_applied_gnss_t_ms_ = in.gnss.t_ms;
  }
  // Every tick, not only when a fix arrives: this is what stops the accepted-
  // correction age below from wrapping back to "fresh" ~49.7 days into an
  // outage (HeadingFilter::kReferenceMaxAgeMs).
  heading_filter_.ExpireStaleReference(in.now_ms);

  // 3. Safety FSM. heading_age_ms is the age of the last ACCEPTED
  // correction, not mere sample arrival -- a stream of fresh-but-rejected
  // (stale or implausible) GNSS samples must not look "current" to the
  // arm-gate or coast-timeout, or the FSM could keep arming/holding
  // indefinitely on pure gyro dead reckoning while every incoming fix is
  // silently being thrown out. See HeadingFilter::
  // age_since_accepted_correction_ms.
  uint32_t t_fresh_ms =
      static_cast<uint32_t>(cfg_.heading.t_fresh_s * 1000.0f);
  uint32_t heading_age_ms =
      heading_filter_.age_since_accepted_correction_ms(in.now_ms);
  bool heading_currently_valid = heading_age_ms <= t_fresh_ms;

  // 3a. Who is commanding the thruster, and in which mode? The LOCAL engage
  // input (debounced -- FsmInputs::engage is contractually "debounced by
  // caller", and this is that debounce: contact bounce can neither fabricate
  // the rising edge that requests hold nor a release/re-assert pair that
  // would silently re-arm it) is arbitrated against the two remote sources.
  // Local always wins and always means hold (SAFETY.md thruster invariant 6); otherwise TX
  // outranks the plugin by fixed precedence. See thruster_arbitration.h.
  bool local_engage = engage_debounce_.Update(in.engage_raw, in.now_ms);
  ThrusterArbitrationResult cmd_in =
      ArbitrateThruster(local_engage, in.tx, in.plugin);
  const bool manual_mode = cmd_in.mode == ThrusterMode::kManual;

  // 3b. Safety FSM -- fed the ARBITRATED engage request, so a remote command
  // enters through exactly the same gate the local switch always did. Every
  // existing protection therefore still applies to remotely-commanded thrust:
  // BNO silence still faults (SAFETY.md thruster invariant 4), the deadman still dominates
  // (invariant 6), ENABLE still follows the arm state (invariant 3), and the
  // independent fail-off watchdog in the control task is untouched.
  FsmInputs fsm_in;
  fsm_in.engage = cmd_in.engage_request;
  fsm_in.deadman_ok = in.deadman_ok;
  fsm_in.bno_ok = in.bno_ok;
  // The good-heading arm gate is a HOLD precondition: it exists so the loop
  // never steers against a heading reference it cannot trust (SAFETY.md
  // rule 4). Manual mode consults no heading at all -- the operator's eyes are
  // the reference -- so requiring a valid GNSS heading there would refuse to
  // let someone push the bow off a dock in a spot with no sky view, for no
  // safety gain. The BNO/deadman/ENABLE gates above are NOT relaxed.
  fsm_in.heading_ok_to_arm = manual_mode ? true : heading_currently_valid;
  // Likewise the coast timers, which exist to disengage a hold that has lost
  // its reference. There is nothing to coast in manual mode.
  fsm_in.heading_age_ms = manual_mode ? 0 : heading_age_ms;

  FsmState state = safety_fsm_.Update(fsm_in);

  // 4. Direction control. ENABLE follows the FSM arm state; PORT/STBD come
  // from ONE of two gates depending on the arbitrated mode, and only while
  // HOLDING -- outside HOLDING the direction is forced OFF in both modes.
  //
  //   HOLD   -> the bang-bang Switcher (ARCHITECTURE.md §7-4.4): lead variable,
  //             Schmitt deadband, min-on/min-off, reversal dwell, duty.
  //   MANUAL -> ManualThrust (manual_thrust.h): the operator's button IS the
  //             direction, untimed except for the control box's measured
  //             reversal interlock.
  //
  // Both gates share the same structural guarantee that lets the output driver
  // trust them: each emits at most one direction, and neither can transition
  // from one direction to the opposite without first passing through OFF.
  // output_map re-enforces "never both" and "inert unless armed" downstream
  // regardless (SAFETY.md thruster invariants 1-2). r_dps_ is already sign-corrected, so the
  // lead term anticipates in the same frame as the fused heading.
  bool armed =
      state == FsmState::kArmedIdle || state == FsmState::kHolding;

  // A mode change hands the thruster to the other gate. The incoming gate is
  // reset (so the outgoing gate's min-on/min-off/deadband state cannot leak
  // across) but is SEEDED with the shared last-thrust history, so its reversal
  // dwell is still measured against what the motor physically did. Done exactly
  // once, on the transition tick.
  //
  // This seeding is the fix for a real hole: clearing the history here let a
  // two-tap mode flip walk straight past the dwell -- thrust PORT in MANUAL,
  // tap HOLD, and the switcher would command STBD after min_off (0.5 s)
  // instead of the measured ~1.75 s interlock. The control box does not care
  // which gate asked. Each gate still applies its OWN dwell to this shared
  // history, which is what preserves the deliberate HOLD/MANUAL asymmetry
  // (config.h): MANUAL's dwell is 0, so it still reverses immediately.
  const bool entering_hold_mode =
      cmd_in.mode == ThrusterMode::kHold && prev_mode_ == ThrusterMode::kManual;
  if (cmd_in.mode != prev_mode_) {
    if (manual_mode) {
      manual_thrust_.Reset(in.now_ms, last_thrust_dir_, last_thrust_end_ms_);
    } else {
      switcher_.Reset(in.now_ms, last_thrust_dir_, last_thrust_end_ms_);
    }
    prev_mode_ = cmd_in.mode;
  }

  Cmd dir = Cmd::kOff;
  bool setpoint_commanded = false;

  // Whenever the Switcher is not the thing driving the setpoint -- disarmed,
  // armed-but-idle, faulted, or hand-steering in MANUAL -- the setpoint MIRRORS
  // the fused heading. That gives the published setpoint one meaning in every
  // state: "the heading hold would take if it engaged right now; once engaged,
  // the heading it is holding." The error is then genuinely zero while nothing
  // is being held, because the setpoint genuinely IS the current heading -- not
  // because a placeholder was stamped over it. That distinction is the whole
  // point: a fabricated 0 would be indistinguishable from a perfect hold, which
  // is the one reading a consumer is least likely to question.
  //
  // Safe by construction: entering HOLDING re-captures base_heading_deg_ and
  // seeds setpoint_deg_ from the fused heading anyway (below), so mirroring can
  // only ever pre-position the setpoint where that capture was about to put it.
  // Nothing here can influence a direction -- the Switcher does not run outside
  // HOLD, and there is no integral term to wind up.
  //
  // MANUAL is included deliberately. The operator's button is the direction
  // there, so a frozen setpoint would sit at a stale heading while they steer
  // away from it, accumulating a large and meaningless error. Mirroring keeps
  // the sentence above true in MANUAL too ("what you would hold if you flipped
  // back to HOLD"), and matches entering_hold_mode already re-capturing from
  // the fused heading on the way back into HOLD.
  //
  // Before the first accepted GNSS fix the fused heading is not yet meaningful,
  // and neither is this -- heading_valid / gnssValid is published alongside to
  // say so. That is no worse than the previous behaviour (a flat 0).
  const bool switcher_drives_setpoint =
      state == FsmState::kHolding && !manual_mode;
  if (!switcher_drives_setpoint) {
    setpoint_deg_ = heading_filter_.fused_deg();
  }

  if (state == FsmState::kHolding && manual_mode) {
    // MANUAL: the operator's button is the direction, passed through the
    // untimed gate (no min-on/min-off/deadband) with only the control box's
    // measured reversal interlock enforced. See manual_thrust.h for why that
    // one timing rule survives "no dwell needed, a human is driving".
    dir = manual_thrust_.Update(cmd_in.manual_cmd, in.now_ms);
  } else if (state == FsmState::kHolding) {
    // HOLD: bang-bang Switcher (ARCHITECTURE.md §7-4.4). On the tick we enter
    // HOLDING, or switch from MANUAL to HOLD while the FSM remains in HOLDING,
    // capture the fused heading as the fixed BASE that a commanded trim is
    // measured from, seed the setpoint to it, and reset the Switcher so stale
    // timing from a previous hold can't leak in.
    if (safety_fsm_.JustEnteredHolding() || entering_hold_mode) {
      base_heading_deg_ = heading_filter_.fused_deg();
      setpoint_deg_ = base_heading_deg_;
      // Seeded for the same reason as the mode-change reset above: a hold
      // entered shortly after a manual thrust (disarm, re-arm in HOLD) must
      // still wait out the interlock against that thrust.
      switcher_.Reset(in.now_ms, last_thrust_dir_, last_thrust_end_ms_);
    }
    // A remotely commanded TRIM offsets the setpoint from the captured base
    // and the setpoint slews toward it at a bounded rate. The trim is relative
    // (0 = hold the captured heading), always finite and clamped, so there is
    // no "no command" case to special-case: 0 simply holds base. Only the
    // SETPOINT moves -- the fused-heading estimate is never snapped, so a trim
    // changes where we are going, never what we believe we are doing
    // (ARCHITECTURE.md §6.4).
    const float trim_deg = ClampTrimDeg(cmd_in.trim_deg);
    const float effective_target = WrapDeg180(base_heading_deg_ + trim_deg);
    setpoint_deg_ = SlewSetpointDeg(setpoint_deg_, effective_target, in.dt_s,
                                    cfg_.setpoint_slew_dps);
    setpoint_commanded = (trim_deg != 0.0f);
    float e_deg = WrapDeg180(setpoint_deg_ - heading_filter_.fused_deg());
    dir = switcher_.Update(e_deg, r_dps_, in.now_ms);
  } else if (manual_mode) {
    // Not holding (disarmed/idle/fault) but manual mode is selected: keep the
    // gate's own view of the world at OFF so it never believes a direction is
    // still running across a disarm, and so the reversal interlock is timed
    // from the moment thrust actually stopped.
    manual_thrust_.Update(Cmd::kOff, in.now_ms);
  }

  // S2 duty accounting runs on every tick, from the ACTUAL output --
  // whichever gate produced it. Inside a HOLD tick the Switcher already
  // advanced it (same now_ms makes this a no-op there); everywhere else this
  // is what lets the duty decay while the thruster sits off (disarmed, idle,
  // faulted) and accumulate truthfully during MANUAL thrust, so a duty_max
  // inhibition releases once the motor has genuinely rested and a heavy
  // manual session is not invisible to the next hold.
  switcher_.TrackDuty(dir != Cmd::kOff, in.now_ms);

  // Shared last-thrust history, taken from the ACTUAL emitted direction so it
  // spans both gates, every mode change and every disarm. While a direction is
  // being driven this keeps advancing, so the instant thrust stops it holds the
  // moment it stopped -- which is what the next gate's reversal dwell is
  // measured from. Recorded AFTER the gates have run, so the seeding above
  // always sees the previous tick's state, never this tick's.
  if (dir != Cmd::kOff) {
    last_thrust_dir_ = dir;
    last_thrust_end_ms_ = in.now_ms;
  }

  Outputs out;
  out.source = cmd_in.source;
  out.mode = cmd_in.mode;
  out.reversal_pending = manual_mode && manual_thrust_.reversal_pending();
  out.setpoint_commanded = setpoint_commanded;
  out.armed = armed;
  out.dir = dir;
  out.state = state;
  out.fused_deg = heading_filter_.fused_deg();
  out.r_dps = r_dps_;
  out.setpoint_deg = setpoint_deg_;
  out.error_deg = WrapDeg180(setpoint_deg_ - heading_filter_.fused_deg());
  out.duty = switcher_.duty();
  out.heading_age_ms = heading_age_ms;
  out.heading_valid = heading_currently_valid;
  // The LOCAL input specifically, as the field's name says -- NOT fsm_in.engage,
  // which is the ARBITRATED request and reads true for a qualifying remote with
  // nobody at the unit. Nothing consumes this today; a telemetry field whose
  // name and value disagree is a trap laid for whoever first does.
  out.engage_debounced = local_engage;
  return out;
}

}  // namespace control_core
