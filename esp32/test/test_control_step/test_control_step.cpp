#include <unity.h>

#include "heading/control_step.h"
#include "heading/output_map.h"
#include "heading/setpoint.h"  // control_core::kMaxTrimDeg

// Integration tests for the wired-together control pipeline: yaw rate ->
// heading fusion -> engage debounce -> arbitration -> safety FSM -> setpoint
// capture -> the mode gate. This is where the riskiest behaviour lives -- not
// in any one module, but in how they are wired together each tick. Every
// Step() in these tests passes through AssertOutputsSafe(), so the
// output-safety invariants are checked across every state transition each
// scenario walks through, not just at its final state.

using control_core::ActiveSource;
using control_core::Cmd;
using control_core::ComputeOutputLevels;
using control_core::ControlStep;
using control_core::FsmState;
using control_core::GnssHeading;
using control_core::OutputLevels;
using control_core::ThrusterMode;
using control_core::ThrusterRemote;

void setUp() {}
void tearDown() {}

namespace {

constexpr uint32_t kTickMs = 10;  // 100 Hz, same as the real control task
constexpr uint32_t kDebounceMs = 50;

ControlStep::Cfg TestCfg() {
  ControlStep::Cfg cfg;  // HeadingFilter/Fsm/Switch in-class defaults:
                         // t_fresh 1.5 s, coast_max 30 s, on_thr 3 deg...
  cfg.switcher.lead_time_s = 0.0f;  // s = e: no rate anticipation, so the
                                    // scenarios below control engagement
                                    // purely through the heading error
  cfg.switcher.min_on_s = 0.0f;
  cfg.switcher.min_off_s = 0.0f;
  cfg.switcher.reversal_dwell_s = 0.5f;
  // Same shortened interlock for the manual gate, so the reversal scenarios
  // below stay quick. The firmware passes config::kReversalDwellS (the
  // measured 1.85 s) to both.
  cfg.manual_reversal_dwell_s = 0.5f;
  cfg.switcher.duty_warn = 0.9f;  // duty out of the way (short scenarios)
  cfg.switcher.duty_max = 0.95f;
  cfg.switcher.duty_window_s = 1000.0f;
  cfg.engage_assert_stable_ms = kDebounceMs;
  cfg.engage_release_stable_ms = kDebounceMs;
  return cfg;
}

// SAFETY.md thruster invariants 1-3, checked on EVERY tick of every scenario: a
// direction only while HOLDING, nothing asserted unless armed, and the
// (armed, dir) pair must map to safe GPIO levels (never both directions,
// directions inert without ENABLE).
void AssertOutputsSafe(const ControlStep::Outputs& out) {
  if (!out.armed) {
    TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);
  }
  if (out.dir != Cmd::kOff) {
    TEST_ASSERT_EQUAL(FsmState::kHolding, out.state);
  }
  OutputLevels lv = ComputeOutputLevels(out.armed, out.dir);
  TEST_ASSERT_FALSE(lv.port && lv.stbd);
  if (!lv.enable) {
    TEST_ASSERT_FALSE(lv.port);
    TEST_ASSERT_FALSE(lv.stbd);
  }
}

// Minimal boat/sensor simulator around ControlStep. Each Tick() advances
// 10 ms, synthesizes one BNO frame (unless the BNO is "dead"), refreshes
// the GNSS sample at its own 200 ms cadence (unless GNSS is "lost"), and
// safety-checks the outputs.
struct Sim {
  Sim() = default;
  // Scenarios that need to vary one tunable (e.g. the per-mode reversal
  // dwells) build a Cfg from TestCfg() and pass it in.
  explicit Sim(const ControlStep::Cfg& cfg) : step(cfg) {}

  ControlStep step{TestCfg()};
  uint32_t t = 1000;
  float bno_yaw_deg = 0.0f;   // what the gyro reports
  float gnss_heading_deg = 0.0f;
  bool engage = false;
  bool deadman_ok = true;
  bool bno_alive = true;
  bool gnss_alive = true;
  GnssHeading gnss{};         // latest sample, kept like SkHeadingIn's cache
  uint32_t next_gnss_ms = 0;
  // Remote command sources, as SkThrusterCommandIn would present them.
  // Default-constructed = not live, not enabled: the pre-remote-control world.
  ThrusterRemote tx{};
  ThrusterRemote plugin{};

  ControlStep::Outputs last{};

  ControlStep::Outputs Tick() {
    t += kTickMs;
    if (gnss_alive && t >= next_gnss_ms) {
      gnss.heading_deg = gnss_heading_deg;
      gnss.t_ms = t;
      gnss.valid = true;
      next_gnss_ms = t + 200;
    }
    ControlStep::Inputs in;
    in.now_ms = t;
    in.dt_s = kTickMs / 1000.0f;
    in.new_bno_frame = bno_alive;
    in.bno_yaw_deg = bno_yaw_deg;
    in.bno_ok = bno_alive;
    in.engage_raw = engage;
    in.deadman_ok = deadman_ok;
    in.gnss = gnss;  // stale-but-cached sample when gnss_alive == false,
                     // exactly like the real SkHeadingIn cache
    in.tx = tx;
    in.plugin = plugin;
    last = step.Step(in);
    AssertOutputsSafe(last);
    return last;
  }

  ControlStep::Outputs TickFor(uint32_t duration_ms) {
    for (uint32_t i = 0; i < duration_ms / kTickMs; ++i) Tick();
    return last;
  }
};

// Boot-to-holding preamble shared by the scenarios: sensors healthy,
// GNSS agreeing with the gyro at heading 0, engage pressed and held.
void EngageToHolding(Sim& sim) {
  sim.TickFor(500);  // sensors settle; first corrections accepted
  sim.engage = true;
  // Debounce (50 ms) + the arming tick.
  sim.TickFor(kDebounceMs + 2 * kTickMs);
  TEST_ASSERT_EQUAL(FsmState::kHolding, sim.last.state);
}

}  // namespace

// --------------------------------------------------------------------------
// Scenario: first valid heading + engage -> setpoint captured exactly once.
// --------------------------------------------------------------------------

void test_engage_with_valid_heading_captures_setpoint_once() {
  Sim sim;
  sim.bno_yaw_deg = 0.0f;
  sim.gnss_heading_deg = 10.0f;

  EngageToHolding(sim);
  float captured = sim.last.setpoint_deg;
  // The first accepted fix seeds the fused estimate to the absolute GNSS
  // heading (HeadingFilter bootstrap), so the setpoint captured at entry
  // is that heading -- not the construction-default 0.
  TEST_ASSERT_FLOAT_WITHIN(1e-3f, 10.0f, captured);

  // Keep holding while the GNSS reference drifts slowly upward (0.5 deg/s,
  // well inside the plausibility gate): the fused heading must follow the
  // corrections, but the setpoint must NOT -- captured once at entry,
  // never re-captured while the hold continues.
  ControlStep::Outputs out{};
  for (int i = 0; i < 200; ++i) {
    sim.gnss_heading_deg += 0.005f;  // 0.5 deg/s at 100 Hz
    out = sim.Tick();
  }
  TEST_ASSERT_EQUAL(FsmState::kHolding, out.state);
  TEST_ASSERT_TRUE(out.fused_deg > captured + 0.1f);  // fusion did move
  TEST_ASSERT_FLOAT_WITHIN(1e-3f, captured, out.setpoint_deg);
}

// Engage held while heading is NOT yet available: waits in ARMED_IDLE, then
// auto-promotes (first-arm exception) when the first fix lands -- and only
// then captures the setpoint.
void test_engage_before_first_heading_waits_then_promotes() {
  Sim sim;
  sim.gnss_alive = false;  // no fix yet -> never a valid heading
  sim.TickFor(200);
  sim.engage = true;
  ControlStep::Outputs out = sim.TickFor(1000);
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, out.state);
  TEST_ASSERT_TRUE(out.armed);
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);

  sim.gnss_alive = true;  // first fix arrives while still engaged
  out = sim.TickFor(300);
  TEST_ASSERT_EQUAL(FsmState::kHolding, out.state);
}

// --------------------------------------------------------------------------
// Scenario: GNSS goes quiet mid-hold -> coast on gyro, then clean disengage
// to ARMED_IDLE at coast_max; held engage does NOT resume hold.
// --------------------------------------------------------------------------

void test_gnss_loss_coasts_then_disengages_cleanly() {
  Sim sim;
  EngageToHolding(sim);

  sim.gnss_alive = false;  // SK cache keeps returning the same stale sample

  // Within coast_max (30 s): still HOLDING on gyro dead reckoning, even
  // though the heading is no longer "valid" (age > t_fresh).
  ControlStep::Outputs out = sim.TickFor(10000);
  TEST_ASSERT_EQUAL(FsmState::kHolding, out.state);
  TEST_ASSERT_FALSE(out.heading_valid);

  // Past coast_max: graceful disengage to ARMED_IDLE, direction off.
  out = sim.TickFor(25000);
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, out.state);
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);

  // GNSS comes back and the engage is STILL held from before: hold must
  // not silently resume -- the operator watched it give up and must
  // consciously re-arm with a fresh press.
  sim.gnss_alive = true;
  out = sim.TickFor(2000);
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, out.state);
  TEST_ASSERT_TRUE(out.heading_valid);  // reference is good again...
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);  // ...but nothing resumes

  // A deliberate release + fresh press re-arms.
  sim.engage = false;
  sim.TickFor(kDebounceMs + 2 * kTickMs);
  sim.engage = true;
  out = sim.TickFor(kDebounceMs + 2 * kTickMs);
  TEST_ASSERT_EQUAL(FsmState::kHolding, out.state);
}

// --------------------------------------------------------------------------
// Scenario: BNO dies mid-hold, thrusting -> outputs drop the SAME tick.
// --------------------------------------------------------------------------

void test_bno_timeout_drops_outputs_same_tick() {
  Sim sim;
  EngageToHolding(sim);

  // Turn the boat: gyro yaw ramps at 5 deg/s, so the fused heading walks
  // away from the setpoint until the switcher engages a direction.
  bool engaged_thrust = false;
  for (int i = 0; i < 500 && !engaged_thrust; ++i) {
    sim.bno_yaw_deg += 0.05f;  // 5 deg/s at 100 Hz
    sim.gnss_heading_deg = sim.bno_yaw_deg;  // GNSS agrees: real rotation
    engaged_thrust = sim.Tick().dir != Cmd::kOff;
  }
  TEST_ASSERT_TRUE_MESSAGE(engaged_thrust,
                            "scenario never engaged a direction");

  // BNO goes silent while a direction is asserted: FAULT, armed false,
  // direction off -- on that very tick (SAFETY.md thruster invariant 4).
  sim.bno_alive = false;
  ControlStep::Outputs out = sim.Tick();
  TEST_ASSERT_EQUAL(FsmState::kFault, out.state);
  TEST_ASSERT_FALSE(out.armed);
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);
}

// --------------------------------------------------------------------------
// Scenario: held engage across a fault does NOT resume hold on recovery.
// --------------------------------------------------------------------------

void test_held_engage_after_fault_recovery_does_not_resume_hold() {
  Sim sim;
  EngageToHolding(sim);

  sim.bno_alive = false;
  ControlStep::Outputs out = sim.Tick();
  TEST_ASSERT_EQUAL(FsmState::kFault, out.state);

  // BNO recovers; engage never released. ARMED_IDLE only -- a hold that
  // was interrupted by a fault must not restart without a fresh press.
  sim.bno_alive = true;
  out = sim.TickFor(2000);
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, out.state);
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);

  // Fresh press -> holding again, with a NEWLY captured setpoint.
  sim.engage = false;
  sim.TickFor(kDebounceMs + 2 * kTickMs);
  sim.engage = true;
  out = sim.TickFor(kDebounceMs + 2 * kTickMs);
  TEST_ASSERT_EQUAL(FsmState::kHolding, out.state);
  TEST_ASSERT_FLOAT_WITHIN(1e-3f, out.fused_deg, out.setpoint_deg);
}

// --------------------------------------------------------------------------
// Scenario: engage contact bounce never requests hold (review item: raw
// opto line used to feed the FSM's edge detector directly).
// --------------------------------------------------------------------------

void test_engage_bounce_never_arms() {
  Sim sim;
  sim.TickFor(500);  // heading available, would arm if engage read

  // 30 ms bursts of "pressed" (below the 50 ms stable-assert requirement)
  // separated by releases -- a chattering contact, not a press.
  for (int burst = 0; burst < 10; ++burst) {
    sim.engage = true;
    ControlStep::Outputs out = sim.TickFor(30);
    TEST_ASSERT_EQUAL(FsmState::kDisarmed, out.state);
    TEST_ASSERT_FALSE(out.armed);
    sim.engage = false;
    sim.TickFor(30);
  }
  TEST_ASSERT_EQUAL(FsmState::kDisarmed, sim.last.state);
}

// Release-side symmetry: while HOLDING, brief release glitches (below the
// stable-release requirement) must neither disarm NOR re-capture the
// setpoint via a phantom re-press.
void test_engage_release_glitch_while_holding_changes_nothing() {
  Sim sim;
  sim.gnss_heading_deg = 10.0f;
  EngageToHolding(sim);
  float captured = sim.last.setpoint_deg;

  for (int glitch = 0; glitch < 5; ++glitch) {
    sim.engage = false;
    sim.TickFor(20);  // 20 ms drop: below the 50 ms release requirement
    sim.engage = true;
    ControlStep::Outputs out = sim.TickFor(100);
    TEST_ASSERT_EQUAL(FsmState::kHolding, out.state);
    // Still the ORIGINAL setpoint: no phantom re-capture happened.
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, captured, out.setpoint_deg);
  }
}

// --------------------------------------------------------------------------
// Scenario: releasing engage while thrusting -> DISARMED, outputs off,
// same tick the debounced release lands (invariant 6).
// --------------------------------------------------------------------------

void test_release_while_thrusting_disarms() {
  Sim sim;
  EngageToHolding(sim);

  bool engaged_thrust = false;
  for (int i = 0; i < 500 && !engaged_thrust; ++i) {
    sim.bno_yaw_deg += 0.05f;
    sim.gnss_heading_deg = sim.bno_yaw_deg;
    engaged_thrust = sim.Tick().dir != Cmd::kOff;
  }
  TEST_ASSERT_TRUE(engaged_thrust);

  sim.engage = false;
  ControlStep::Outputs out = sim.TickFor(kDebounceMs + 2 * kTickMs);
  TEST_ASSERT_EQUAL(FsmState::kDisarmed, out.state);
  TEST_ASSERT_FALSE(out.armed);
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);
}

// ==========================================================================
// Remote thruster control (ARCHITECTURE.md §6). Every scenario below still runs
// AssertOutputsSafe() on every tick, so a remotely-commanded thrust is held to
// exactly the same output invariants as a locally-engaged hold.
// ==========================================================================

namespace {

// A live+enabled remote in manual mode, pushing `dir`.
ThrusterRemote RemoteManual(Cmd dir) {
  ThrusterRemote r;
  r.live = true;
  r.enabled = true;
  r.mode = ThrusterMode::kManual;
  r.manual_cmd = dir;
  return r;
}

// A live+enabled remote in hold mode, optionally commanding a trim offset
// (degrees, relative to HH's captured heading; 0 = no trim).
ThrusterRemote RemoteHold(float trim_deg = 0.0f) {
  ThrusterRemote r;
  r.live = true;
  r.enabled = true;
  r.mode = ThrusterMode::kHold;
  r.trim_deg = trim_deg;
  return r;
}

// Bring a remote-manual session up to HOLDING (the FSM's "actively
// commanding" state, whichever mode is in force).
void ArmRemoteManual(Sim& sim, Cmd dir) {
  sim.TickFor(500);
  sim.tx = RemoteManual(dir);
  sim.TickFor(3 * kTickMs);
}

}  // namespace

// --------------------------------------------------------------------------
// A phone/handheld in MANUAL mode drives the thruster directly, and releasing
// the button stops it on the next tick -- no minimum-on time anywhere.
// --------------------------------------------------------------------------
void test_remote_manual_thrusts_and_releases_immediately() {
  Sim sim;
  ArmRemoteManual(sim, Cmd::kStbd);
  TEST_ASSERT_EQUAL(FsmState::kHolding, sim.last.state);
  TEST_ASSERT_EQUAL(Cmd::kStbd, sim.last.dir);
  TEST_ASSERT_TRUE(sim.last.mode == ThrusterMode::kManual);
  TEST_ASSERT_TRUE(sim.last.source == ActiveSource::kTx);

  sim.tx.manual_cmd = Cmd::kOff;  // operator lets go
  TEST_ASSERT_EQUAL(Cmd::kOff, sim.Tick().dir);
}

// Manual mode needs no GNSS heading at all: the operator is the reference.
// (Hold mode still refuses to arm without one -- next test.)
void test_remote_manual_arms_without_any_gnss_heading() {
  Sim sim;
  sim.gnss_alive = false;  // never a single valid fix
  sim.TickFor(500);
  sim.tx = RemoteManual(Cmd::kPort);
  sim.TickFor(3 * kTickMs);
  TEST_ASSERT_EQUAL(FsmState::kHolding, sim.last.state);
  TEST_ASSERT_EQUAL(Cmd::kPort, sim.last.dir);
}

void test_remote_hold_still_refuses_to_arm_without_a_good_heading() {
  Sim sim;
  sim.gnss_alive = false;
  sim.TickFor(500);
  sim.tx = RemoteHold();
  sim.TickFor(10 * kTickMs);
  // Armed-idle at most: the hold gate is unchanged by remote control.
  TEST_ASSERT_NOT_EQUAL(FsmState::kHolding, sim.last.state);
  TEST_ASSERT_EQUAL(Cmd::kOff, sim.last.dir);
}

// SAFETY.md thruster invariant 4 is NOT relaxed for manual mode: BNO silence still faults and
// still drops the outputs, even mid-press.
void test_bno_loss_kills_remote_manual_thrust_too() {
  Sim sim;
  ArmRemoteManual(sim, Cmd::kStbd);
  TEST_ASSERT_EQUAL(Cmd::kStbd, sim.last.dir);
  sim.bno_alive = false;
  ControlStep::Outputs out = sim.TickFor(10 * kTickMs);
  TEST_ASSERT_EQUAL(FsmState::kFault, out.state);
  TEST_ASSERT_FALSE(out.armed);
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);
}

// SAFETY.md thruster invariant 6 is NOT relaxed either: the deadman still dominates a remote.
void test_deadman_dominates_remote_manual_thrust() {
  Sim sim;
  ArmRemoteManual(sim, Cmd::kStbd);
  sim.deadman_ok = false;
  ControlStep::Outputs out = sim.TickFor(3 * kTickMs);
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);
  TEST_ASSERT_FALSE(out.armed);
}

// The source going away (WiFi drop, tab closed, TX switched off) drops the
// thrust -- fail to off, never hold the last command.
void test_remote_going_stale_drops_thrust_and_disarms() {
  Sim sim;
  ArmRemoteManual(sim, Cmd::kStbd);
  TEST_ASSERT_EQUAL(Cmd::kStbd, sim.last.dir);
  sim.tx.live = false;
  ControlStep::Outputs out = sim.TickFor(3 * kTickMs);
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);
  TEST_ASSERT_FALSE(out.armed);
  TEST_ASSERT_TRUE(out.source == ActiveSource::kNone);
}

// The local ENGAGE input outranks a remote that is actively thrusting: taking
// the unit locally must never require the remote's cooperation.
void test_local_engage_takes_over_from_a_remote_manual_thrust() {
  Sim sim;
  ArmRemoteManual(sim, Cmd::kStbd);
  TEST_ASSERT_TRUE(sim.last.source == ActiveSource::kTx);

  sim.engage = true;  // someone at the unit takes it
  sim.TickFor(kDebounceMs + 3 * kTickMs);
  TEST_ASSERT_TRUE(sim.last.source == ActiveSource::kLocal);
  TEST_ASSERT_TRUE(sim.last.mode == ThrusterMode::kHold);
  // The remote's manual direction must not survive the takeover.
  TEST_ASSERT_TRUE(sim.tx.manual_cmd == Cmd::kStbd);  // still shouting
  TEST_ASSERT_NOT_EQUAL(FsmState::kDisarmed, sim.last.state);
}

// Changing MANUAL -> HOLD does not create a new FSM engage edge: the FSM is
// already in kHolding. The mode boundary itself must therefore capture the
// current heading, rather than reusing the default or an earlier hold's base.
void test_manual_to_hold_recaptures_current_heading() {
  Sim sim;
  sim.bno_yaw_deg = 72.0f;
  sim.gnss_heading_deg = 72.0f;
  ArmRemoteManual(sim, Cmd::kOff);
  TEST_ASSERT_EQUAL(FsmState::kHolding, sim.last.state);

  sim.tx = RemoteHold();
  sim.TickFor(3 * kTickMs);
  TEST_ASSERT_EQUAL(FsmState::kHolding, sim.last.state);
  TEST_ASSERT_TRUE(sim.last.mode == ThrusterMode::kHold);
  TEST_ASSERT_FLOAT_WITHIN(1.0f, sim.last.fused_deg, sim.last.setpoint_deg);
  TEST_ASSERT_EQUAL(Cmd::kOff, sim.last.dir);
}

// TX outranks the plugin for the thruster exactly as it does for the drives.
void test_tx_outranks_plugin_for_the_thruster() {
  Sim sim;
  sim.TickFor(500);
  sim.tx = RemoteManual(Cmd::kPort);
  sim.plugin = RemoteManual(Cmd::kStbd);
  sim.TickFor(3 * kTickMs);
  TEST_ASSERT_TRUE(sim.last.source == ActiveSource::kTx);
  TEST_ASSERT_EQUAL(Cmd::kPort, sim.last.dir);
}

// The control box's reversal interlock still applies in manual mode -- an
// operator flicking straight across gets OFF until the dwell expires.
void test_manual_reversal_waits_out_the_interlock() {
  Sim sim;
  ArmRemoteManual(sim, Cmd::kStbd);
  TEST_ASSERT_EQUAL(Cmd::kStbd, sim.last.dir);

  sim.tx.manual_cmd = Cmd::kPort;  // flick across
  ControlStep::Outputs out = sim.Tick();
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);
  TEST_ASSERT_TRUE(out.reversal_pending);

  // Still withheld well inside the dwell (TestCfg uses 0.5 s).
  out = sim.TickFor(300);
  TEST_ASSERT_EQUAL(Cmd::kOff, out.dir);

  out = sim.TickFor(300);  // past it
  TEST_ASSERT_EQUAL(Cmd::kPort, out.dir);
  TEST_ASSERT_FALSE(out.reversal_pending);
}

// --------------------------------------------------------------------------
// Remotely commanded hold TRIM: the setpoint slews off the captured heading by
// the trim offset; the ESTIMATE never moves. With the captured base heading at
// ~0 (yaw/gnss 0), a +30 trim is a +30 effective target -- same numbers the
// old absolute design produced, reached via base(0)+trim(30).
// --------------------------------------------------------------------------
void test_commanded_trim_slews_the_setpoint_not_the_estimate() {
  Sim sim;
  sim.bno_yaw_deg = 0.0f;
  sim.gnss_heading_deg = 0.0f;
  sim.TickFor(500);
  sim.tx = RemoteHold(/*trim_deg=*/30.0f);
  sim.TickFor(5 * kTickMs);
  TEST_ASSERT_EQUAL(FsmState::kHolding, sim.last.state);
  TEST_ASSERT_TRUE(sim.last.setpoint_commanded);  // a non-zero trim is applied

  const float fused_before = sim.last.fused_deg;
  ControlStep::Outputs out = sim.TickFor(500);
  // Setpoint has moved toward base+trim but nowhere near jumped to it
  // (default slew 10 deg/s: ~0.5 s of travel is ~5 deg).
  TEST_ASSERT_TRUE(out.setpoint_deg > 0.5f);
  TEST_ASSERT_TRUE(out.setpoint_deg < 30.0f);
  // The state estimate was not snapped by the command.
  TEST_ASSERT_FLOAT_WITHIN(2.0f, fused_before, out.fused_deg);
}

void test_commanded_trim_eventually_reaches_base_plus_trim() {
  Sim sim;
  sim.TickFor(500);
  sim.tx = RemoteHold(20.0f);
  sim.TickFor(4000);  // 4 s at 10 deg/s is ample for 20 deg
  TEST_ASSERT_FLOAT_WITHIN(0.5f, 20.0f, sim.last.setpoint_deg);
}

// A zero trim (the resting value, or a NaN/null read as 0) holds the captured
// heading exactly: the boat does not swing, and nothing reads as commanded.
void test_zero_trim_holds_the_captured_heading() {
  Sim sim;
  sim.TickFor(500);
  sim.tx = RemoteHold(0.0f);
  sim.TickFor(1000);
  TEST_ASSERT_EQUAL(FsmState::kHolding, sim.last.state);
  TEST_ASSERT_FALSE(sim.last.setpoint_commanded);
  TEST_ASSERT_FLOAT_WITHIN(0.5f, 0.0f, sim.last.setpoint_deg);
}

// An out-of-range trim (corruption, an old 999 sentinel) is CLAMPED, not
// ignored: the swing saturates at kMaxTrimDeg and goes no further -- a bounded
// error, never an arbitrary heading.
void test_out_of_range_trim_is_clamped_to_the_limit() {
  Sim sim;
  sim.TickFor(500);
  sim.tx = RemoteHold(1.0e30f);
  sim.TickFor(8000);  // ample time to slew to the clamped target
  // base ~0 + clamped trim = kMaxTrimDeg; the setpoint lands there, not beyond.
  TEST_ASSERT_FLOAT_WITHIN(0.5f, control_core::kMaxTrimDeg,
                           sim.last.setpoint_deg);
}

// Losing the commanding source: local engage takes over the hold, with no trim
// of its own, and nothing reads as commanded (ARCHITECTURE.md §6.4).
void test_losing_the_commanding_source_hands_to_local_untrimmed() {
  Sim sim;
  sim.TickFor(500);
  sim.tx = RemoteHold(20.0f);
  sim.TickFor(1000);
  const float commanded = sim.last.setpoint_deg;
  TEST_ASSERT_TRUE(commanded > 1.0f);

  sim.tx.live = false;      // source vanishes
  sim.engage = true;        // local operator takes over the hold
  sim.TickFor(kDebounceMs + 3 * kTickMs);
  TEST_ASSERT_TRUE(sim.last.source == ActiveSource::kLocal);
  TEST_ASSERT_FALSE(sim.last.setpoint_commanded);
}

// Switching mode mid-session resets the incoming gate's own control state
// (min-on/min-off/deadband), so one mode's control law cannot leak into the
// other. What it must NOT reset is the shared last-thrust history: each gate
// still measures its OWN reversal dwell against what the motor physically did.
// TestCfg gives BOTH gates a 0.5 s dwell, so here the reversal is withheld
// across the change; the firmware's MANUAL dwell is 0 by owner decision, which
// the dedicated case below covers.
void test_mode_switch_resets_the_gate_but_not_the_thrust_history() {
  Sim sim;
  ArmRemoteManual(sim, Cmd::kStbd);
  TEST_ASSERT_EQUAL(Cmd::kStbd, sim.last.dir);

  sim.tx = RemoteHold();       // -> hold mode
  sim.TickFor(3 * kTickMs);
  sim.tx = RemoteManual(Cmd::kPort);  // -> back to manual, opposite direction
  sim.TickFor(2 * kTickMs);
  // ~50 ms since the stbd thrust stopped, well inside the 0.5 s dwell.
  TEST_ASSERT_EQUAL(Cmd::kOff, sim.last.dir);
  TEST_ASSERT_TRUE(sim.last.reversal_pending);

  sim.TickFor(600);  // past the dwell
  TEST_ASSERT_EQUAL(Cmd::kPort, sim.last.dir);
}

// A gate with NO dwell of its own is not made to wait by history it inherited.
// This is the shipped MANUAL configuration (config::kManualReversalDwellS = 0,
// an owner decision -- the control box enforces its own interlock and a human
// is watching), and it must survive the history-sharing above: the seeding
// carries the FACTS, each gate applies its OWN policy to them.
void test_zero_manual_dwell_still_reverses_immediately_across_a_mode_change() {
  ControlStep::Cfg cfg = TestCfg();
  cfg.manual_reversal_dwell_s = 0.0f;  // as shipped
  Sim sim{cfg};
  sim.TickFor(500);
  sim.tx = RemoteManual(Cmd::kStbd);
  sim.TickFor(200);
  TEST_ASSERT_EQUAL(Cmd::kStbd, sim.last.dir);

  sim.tx = RemoteHold();
  sim.TickFor(3 * kTickMs);
  sim.tx = RemoteManual(Cmd::kPort);
  sim.TickFor(2 * kTickMs);
  TEST_ASSERT_EQUAL(Cmd::kPort, sim.last.dir);
  TEST_ASSERT_FALSE(sim.last.reversal_pending);
}

// The one that actually bites on the boat: MANUAL thrust, then the operator
// taps HOLD. The automatic switcher must wait out ITS full dwell against that
// manual thrust -- the control box's interlock is a property of the box and
// knows nothing about which gate commanded the previous thrust. Before the
// history was shared this reversed after min_off (0.5 s in TestCfg, and only
// 0.5 s in the firmware too) instead of the measured dwell.
void test_hold_honours_its_dwell_against_a_manual_thrust() {
  ControlStep::Cfg cfg = TestCfg();
  cfg.manual_reversal_dwell_s = 0.0f;  // as shipped -- manual itself never waits
  cfg.switcher.min_off_s = 0.1f;       // well below the dwell, so the dwell is
                                       // demonstrably what gates the reversal
  cfg.switcher.reversal_dwell_s = 0.5f;
  Sim sim{cfg};
  sim.TickFor(500);
  sim.tx = RemoteManual(Cmd::kPort);   // manual thrust to port
  sim.TickFor(200);
  TEST_ASSERT_EQUAL(Cmd::kPort, sim.last.dir);

  // Tap HOLD with a starboard-ward error: the switcher wants stbd at once.
  sim.tx = RemoteHold(/*trim_deg=*/30.0f);
  sim.TickFor(200);  // past min_off, still inside the reversal dwell
  TEST_ASSERT_EQUAL(Cmd::kOff, sim.last.dir);

  sim.TickFor(500);  // past the dwell
  TEST_ASSERT_EQUAL(Cmd::kStbd, sim.last.dir);
}

// ==========================================================================
// Setpoint mirroring: whenever the Switcher is not driving the setpoint, it
// follows the fused heading, so the published error is truthfully zero rather
// than a stale number (or a fabricated one). The complement -- the setpoint
// standing still once HOLDING has captured it -- is covered by
// test_engage_with_valid_heading_captures_setpoint_once above.
// ==========================================================================

// Disarmed: the boat is swung by hand through 40 deg. The setpoint tracks it
// the whole way, so an operator reading the telemetry sees "engage now and
// you will hold THIS", never a leftover from the last session.
void test_setpoint_mirrors_fused_heading_while_disarmed() {
  Sim sim;
  sim.bno_yaw_deg = 0.0f;
  sim.gnss_heading_deg = 0.0f;
  sim.TickFor(500);  // first fix accepted, filter bootstrapped

  for (int i = 0; i < 400; ++i) {
    sim.bno_yaw_deg += 0.1f;  // 10 deg/s at 100 Hz
    sim.gnss_heading_deg += 0.1f;
    ControlStep::Outputs out = sim.Tick();
    TEST_ASSERT_EQUAL(FsmState::kDisarmed, out.state);
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, out.fused_deg, out.setpoint_deg);
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, 0.0f, out.error_deg);
  }
  TEST_ASSERT_TRUE(sim.last.fused_deg > 30.0f);  // it really did swing
}

// ARMED_IDLE -- armed, but nothing is being held yet. Same rule: the Switcher
// is not running, so the setpoint mirrors. Reached the way the FSM actually
// reaches it: a fault that interrupts a hold, then recovers with engage still
// held (a hold must not silently resume, so it parks in ARMED_IDLE).
void test_setpoint_mirrors_fused_heading_while_armed_idle() {
  Sim sim;
  EngageToHolding(sim);

  sim.bno_alive = false;
  sim.TickFor(2 * kTickMs);
  sim.bno_alive = true;
  ControlStep::Outputs out = sim.TickFor(2000);
  TEST_ASSERT_EQUAL(FsmState::kArmedIdle, out.state);

  for (int i = 0; i < 200; ++i) {
    sim.bno_yaw_deg += 0.1f;
    sim.gnss_heading_deg += 0.1f;
    out = sim.Tick();
    TEST_ASSERT_EQUAL(FsmState::kArmedIdle, out.state);
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, out.fused_deg, out.setpoint_deg);
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, 0.0f, out.error_deg);
  }
}

// MANUAL while HOLDING: the operator's button is the direction and the
// Switcher does not run, so a frozen setpoint would sit at a stale heading
// while they steer away from it, reporting a large and meaningless error.
// It mirrors instead -- "what you would hold if you flipped back to HOLD".
void test_setpoint_mirrors_fused_heading_in_manual_mode() {
  Sim sim;
  ArmRemoteManual(sim, Cmd::kStbd);
  TEST_ASSERT_EQUAL(FsmState::kHolding, sim.last.state);
  TEST_ASSERT_TRUE(sim.last.mode == ThrusterMode::kManual);

  for (int i = 0; i < 200; ++i) {
    sim.bno_yaw_deg += 0.1f;  // the manual thrust is swinging the bow
    sim.gnss_heading_deg += 0.1f;
    ControlStep::Outputs out = sim.Tick();
    TEST_ASSERT_EQUAL(Cmd::kStbd, out.dir);  // still hand-steering
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, out.fused_deg, out.setpoint_deg);
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, 0.0f, out.error_deg);
  }
}

// The mirror must stop the instant HOLD takes over, or a trim would have
// nothing fixed to be measured against. Swinging the bow after the MANUAL ->
// HOLD switch moves the fused heading and the ERROR, never the setpoint.
void test_mirroring_stops_once_hold_captures_the_setpoint() {
  Sim sim;
  ArmRemoteManual(sim, Cmd::kOff);
  sim.tx = RemoteHold();  // same FSM state, switcher now in charge
  sim.TickFor(2 * kTickMs);
  const float captured = sim.last.setpoint_deg;
  TEST_ASSERT_FLOAT_WITHIN(1e-3f, sim.last.fused_deg, captured);

  for (int i = 0; i < 100; ++i) {
    sim.bno_yaw_deg += 0.02f;  // 2 deg/s, inside the switcher deadband a while
    sim.gnss_heading_deg += 0.02f;
    sim.Tick();
  }
  TEST_ASSERT_EQUAL(FsmState::kHolding, sim.last.state);
  TEST_ASSERT_FLOAT_WITHIN(1e-3f, captured, sim.last.setpoint_deg);
  TEST_ASSERT_TRUE(sim.last.error_deg < -0.5f);  // heading ran off, error grew
}

// --------------------------------------------------------------------------
// Scenario: the millis() wrap during a long GNSS outage. An HH left powered
// through a multi-week outage must not find its heading "valid" again -- and
// so become armable in HOLD -- at the instant the unsigned age wraps back to a
// small number, 2^32 ms after the last accepted fix. The pipeline expires the
// reference every tick (HeadingFilter::kReferenceMaxAgeMs), so by the wrap
// there is nothing left to read as fresh; and the fix that eventually returns
// is seeded from, not rejected as out-of-order.
// --------------------------------------------------------------------------

void test_heading_does_not_read_valid_again_at_the_clock_wrap() {
  Sim sim;
  sim.TickFor(500);
  TEST_ASSERT_TRUE(sim.last.heading_valid);
  const uint32_t last_fix_ms = sim.gnss.t_ms;

  // GNSS lost. One tick past the reference expiry is enough to run it; the
  // hour in between does not need walking.
  sim.gnss_alive = false;
  sim.t += control_core::HeadingFilter::kReferenceMaxAgeMs;
  sim.Tick();
  TEST_ASSERT_FALSE(sim.last.heading_valid);
  TEST_ASSERT_EQUAL_UINT32(UINT32_MAX, sim.last.heading_age_ms);

  // The wrap: now_ms comes back round to the last accepted fix's timestamp.
  // Without the per-tick expiry the age would read 0 and the heading valid.
  sim.t = last_fix_ms - kTickMs;
  sim.Tick();
  TEST_ASSERT_FALSE(sim.last.heading_valid);
  TEST_ASSERT_EQUAL_UINT32(UINT32_MAX, sim.last.heading_age_ms);

  // GNSS returns on a different heading: accepted as a fresh seed.
  sim.gnss_alive = true;
  sim.gnss_heading_deg = 90.0f;
  sim.next_gnss_ms = 0;
  sim.Tick();
  TEST_ASSERT_TRUE(sim.last.heading_valid);
  TEST_ASSERT_FLOAT_WITHIN(0.5f, 90.0f, sim.last.fused_deg);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_engage_with_valid_heading_captures_setpoint_once);
  RUN_TEST(test_engage_before_first_heading_waits_then_promotes);
  RUN_TEST(test_gnss_loss_coasts_then_disengages_cleanly);
  RUN_TEST(test_bno_timeout_drops_outputs_same_tick);
  RUN_TEST(test_held_engage_after_fault_recovery_does_not_resume_hold);
  RUN_TEST(test_engage_bounce_never_arms);
  RUN_TEST(test_engage_release_glitch_while_holding_changes_nothing);
  RUN_TEST(test_release_while_thrusting_disarms);
  RUN_TEST(test_remote_manual_thrusts_and_releases_immediately);
  RUN_TEST(test_remote_manual_arms_without_any_gnss_heading);
  RUN_TEST(test_remote_hold_still_refuses_to_arm_without_a_good_heading);
  RUN_TEST(test_bno_loss_kills_remote_manual_thrust_too);
  RUN_TEST(test_deadman_dominates_remote_manual_thrust);
  RUN_TEST(test_remote_going_stale_drops_thrust_and_disarms);
  RUN_TEST(test_local_engage_takes_over_from_a_remote_manual_thrust);
  RUN_TEST(test_manual_to_hold_recaptures_current_heading);
  RUN_TEST(test_tx_outranks_plugin_for_the_thruster);
  RUN_TEST(test_manual_reversal_waits_out_the_interlock);
  RUN_TEST(test_commanded_trim_slews_the_setpoint_not_the_estimate);
  RUN_TEST(test_commanded_trim_eventually_reaches_base_plus_trim);
  RUN_TEST(test_zero_trim_holds_the_captured_heading);
  RUN_TEST(test_out_of_range_trim_is_clamped_to_the_limit);
  RUN_TEST(test_losing_the_commanding_source_hands_to_local_untrimmed);
  RUN_TEST(test_mode_switch_resets_the_gate_but_not_the_thrust_history);
  RUN_TEST(test_zero_manual_dwell_still_reverses_immediately_across_a_mode_change);
  RUN_TEST(test_hold_honours_its_dwell_against_a_manual_thrust);
  RUN_TEST(test_setpoint_mirrors_fused_heading_while_disarmed);
  RUN_TEST(test_setpoint_mirrors_fused_heading_while_armed_idle);
  RUN_TEST(test_setpoint_mirrors_fused_heading_in_manual_mode);
  RUN_TEST(test_mirroring_stops_once_hold_captures_the_setpoint);
  RUN_TEST(test_heading_does_not_read_valid_again_at_the_clock_wrap);
  return UNITY_END();
}
