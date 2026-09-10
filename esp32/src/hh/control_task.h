#pragma once

// Pins the safe-core pipeline to a dedicated FreeRTOS task on
// core 1, fixed period, higher priority than the Arduino loop task -- see
// CLAUDE.md "Architecture." Once begin() is called, this task owns the BNO
// UART, the ENGAGE GPIO, and the thruster output GPIOs exclusively --
// main.cpp/SensESP code must not touch them directly anymore. The only
// cross-task boundaries are SkHeadingIn's internal mutex (read-only from
// here) and this class's own telemetry_ snapshot (write-only from here,
// read-only from main.cpp -- see ARCHITECTURE.md §3, the one-way boundary).
//
// The per-tick DECISION pipeline (yaw rate -> heading fusion -> engage
// debounce -> safety FSM -> setpoint capture -> switcher) lives in the
// pure, host-tested control_core::ControlStep (lib/control_core/
// control_step.h + test/test_control_step) -- this class is only the
// hardware glue around it: sample UART/GPIO/config, call Step(), hand the
// resulting (armed, dir) pair atomically to Outputs::apply(), publish
// telemetry. Keep it that way: any new decision logic belongs in
// ControlStep where the integration tests can see it, not here.
//
// FAIL-OFF WATCHDOG (independent of this task): begin() also starts a
// periodic esp_timer callback -- dispatched from the esp_timer service
// task on core 0, entirely outside this control task's scheduling -- that
// forces all thruster outputs off if this task stops refreshing its
// heartbeat (config::kOutputFailoffTimeoutMs). A control task that stalls
// or crashes after asserting a direction can therefore not leave the
// GPIOs latched until reboot. See config.h for the timing rationale and
// the accompanying hardware fail-off recommendation.
//
// Also owns the IMU mount tare (roll/pitch, degrees) and
// yaw-rate-sign (MEASUREMENTS.md Item 6) as live, web-UI-configurable,
// flash-persisted values. Roll/pitch tare is applied to the published
// attitude only -- nothing internal consumes raw roll/pitch, so it is not
// fed back into the control law (unlike yaw-rate sign, below). Yaw-rate
// sign is NOT deferred the same way: it is passed into ControlStep and
// applied to the rate BEFORE it reaches HeadingFilter::Predict() --
// fused_deg() is already a live consumer (published as
// navigation.attitude and sensors.headingHold.fusedHeading), so an
// uncorrected mount would make the fused heading itself drift the wrong
// way, not just the displayed rateOfTurn. The ARCHITECTURE.md §11 sea-trial
// Switcher knobs get the same live/persisted treatment and are pushed
// through ControlStep::SetSwitchTunables() each tick, where
// Switcher::SetTunables validates/clamps them (untrusted-boundary rules --
// see switcher.h).

#include <atomic>
#include <cstdint>
#include <memory>

#include <esp_timer.h>

#include "heading/control_step.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "outputs.h"
#include "rvc_reader.h"
#include "sensesp/system/observablevalue.h"
#include "sk_heading_in.h"
#include "sk_thruster_in.h"

class ControlTask {
 public:
  // A read-only, downsampled view of the control task's state for
  // publishing (ARCHITECTURE.md §9). Angles already have mount tare/sign
  // applied and are in radians (SK boundary conversion happens here, not
  // in main.cpp). Refreshed once per control tick; consumed at 10-20 Hz.
  struct TelemetrySnapshot {
    float roll_rad = 0.0f;
    float pitch_rad = 0.0f;
    float yaw_rad = 0.0f;  // fused heading (gyro+GNSS), NOT raw BNO yaw
    float rate_of_turn_rad_s = 0.0f;
    control_core::FsmState state = control_core::FsmState::kDisarmed;
    bool bno_ok = false;
    bool gnss_valid = false;
    uint32_t gnss_age_ms = 0;
    // ARCHITECTURE.md §9 tuning signals. setpoint/error are meaningful in every
    // state: the Switcher drives them while HOLDING in HOLD mode, and everywhere
    // else the setpoint mirrors the fused heading (control_step.cpp), so the
    // error reads a truthful zero rather than a stale one. switch_cmd reads
    // kOff outside HOLDING. duty is the Switcher's own 0-1
    // sliding-window fraction (ARCHITECTURE.md §7), not reset on exit from HOLDING
    // (S2 thermal memory persists across a release/re-engage, same as
    // Switcher::Reset's own contract).
    float setpoint_rad = 0.0f;
    float error_rad = 0.0f;
    control_core::Cmd switch_cmd = control_core::Cmd::kOff;
    float duty = 0.0f;

    // Remote thruster control (ARCHITECTURE.md §6). Who is commanding, in
    // which mode, and whether a reversal is currently being held off by the
    // control box's interlock -- all published so an operator can see WHY the
    // thruster is or isn't doing what they asked.
    control_core::ActiveSource source = control_core::ActiveSource::kNone;
    control_core::ThrusterMode mode = control_core::ThrusterMode::kHold;
    bool reversal_pending = false;
    bool armed = false;
    // "At least one remote source is currently LIVE" (fresh data), whether or
    // not it reports itself enabled -- the same definition RX publishes on
    // rx.linkUp (ARCHITECTURE.md §9), so the two units' link rows read alike.
    // Published on hh.linkUp; consumers time its ARRIVAL, not its value.
    bool link_up = false;
    // The live setpoint in DEGREES as well as radians. Nothing seeds off it --
    // the heading trim has been a seedless relative offset since 2026-07-24
    // (heading_nudge.h) -- but the operator-facing consumers (plugin, phone UI)
    // display it, and they work in degrees. Publishing it in the unit its
    // consumer needs avoids a rad->deg->rad round trip through three codebases.
    float setpoint_deg = 0.0f;
  };

  static const char* StateName(control_core::FsmState s);
  static const char* CmdName(control_core::Cmd c);

  static const char* ModeName(control_core::ThrusterMode m);
  static const char* SourceName(control_core::ActiveSource s);

  // sk_heading_in and both thruster command sources must outlive this task
  // (owned by main.cpp, fed by SensESP's WS client on the Arduino loop task).
  // Passing them in rather than owning them keeps this task free of any
  // network object it might be tempted to block on (SAFETY.md thruster invariant 5).
  void begin(SkHeadingIn* sk_heading_in, SkThrusterIn* tx_thruster,
             SkThrusterIn* plugin_thruster);

  // Non-blocking, same contract as SkHeadingIn::latest(): true and *out
  // refreshed if the mutex was free, false (and *out untouched) on the
  // rare contended case -- callers should just reuse their last copy.
  bool latestTelemetry(TelemetrySnapshot* out) const;

 private:
  // Builds the ControlStep config: HeadingFilter/Fsm defaults, SwitchCfg's
  // in-class "typical start" defaults with only reversal_dwell_s
  // overridden by the measured remote dead time (config::kReversalDwellS),
  // and the ENGAGE debounce periods. Defined in the .cpp so config.h stays
  // out of this header.
  static control_core::ControlStep::Cfg MakeStepCfg();

  static void TaskTrampoline(void* arg);
  static void FailoffWatchdogTrampoline(void* arg);
  void Run();
  void Tick(uint32_t now_ms, float dt_s);
  void CheckFailoff();
  void PublishTelemetrySnapshot(const control_core::ControlStep::Outputs& step,
                                 bool bno_ok, bool link_up);
  void LogStatusIfDue(uint32_t now_ms);
  void RecordJitterAndLogIfDue(int64_t period_us, uint32_t now_ms);

  SkHeadingIn* sk_heading_in_ = nullptr;
  SkThrusterIn* tx_thruster_ = nullptr;
  SkThrusterIn* plugin_thruster_ = nullptr;

  RvcReader rvc_reader_;
  control_core::ControlStep control_step_{MakeStepCfg()};
  Outputs outputs_;

  control_core::GnssHeading cached_gnss_{};
  // Last successful snapshots of the two remote sources. Reused verbatim on a
  // contended tick (Snapshot() returning false), exactly like cached_gnss_ --
  // the control loop never waits on a listener callback.
  control_core::ThrusterRemote cached_tx_{};
  control_core::ThrusterRemote cached_plugin_{};
  float last_roll_deg_ = 0.0f;   // most recent BNO roll/pitch; held between
  float last_pitch_deg_ = 0.0f;  // frames
  // Last Step() outputs, for the periodic status log (this task only).
  control_core::ControlStep::Outputs last_step_{};

  // Fail-off watchdog heartbeat: refreshed by Run() after every completed
  // tick; read by the esp_timer callback on core 0. Plain 32-bit atomic --
  // no lock, the watchdog must work precisely when this task is wedged.
  std::atomic<uint32_t> heartbeat_ms_{0};
  esp_timer_handle_t failoff_timer_ = nullptr;
  uint32_t last_failoff_log_ms_ = 0;

  // MEASUREMENTS.md Item 6: live-configurable (web UI, persisted to
  // flash) -- see class comment above for where each is applied (roll/
  // pitch tare: publish time only; yaw-rate sign: inside ControlStep,
  // before Predict()). Read via ->get() from this task; written via the
  // web UI on the Arduino loop task. Deliberately not mutex-protected like
  // telemetry_/SkHeadingIn: these are simple scalars that change rarely (a
  // human nudging a calibration value), not safety-critical, and a torn
  // read would at worst show one tick of a slightly-stale tare/sign.
  std::shared_ptr<sensesp::PersistingObservableValue<float>> imu_roll_tare_deg_;
  std::shared_ptr<sensesp::PersistingObservableValue<float>> imu_pitch_tare_deg_;
  std::shared_ptr<sensesp::PersistingObservableValue<bool>> imu_yaw_rate_invert_;

  // ARCHITECTURE.md §11 sea-trial tuning knobs, same live/persisted/not-mutex-
  // protected treatment as the IMU tare values above -- read via ->get()
  // each tick and pushed into the ControlStep's Switcher via
  // SetSwitchTunables(), which validates/clamps every value (see
  // Switcher::SetTunables for which knobs are deliberately excluded).
  std::shared_ptr<sensesp::PersistingObservableValue<float>> switch_on_thr_deg_;
  std::shared_ptr<sensesp::PersistingObservableValue<float>> switch_off_thr_deg_;
  std::shared_ptr<sensesp::PersistingObservableValue<float>> switch_lead_time_s_;
  std::shared_ptr<sensesp::PersistingObservableValue<float>> switch_min_on_s_;
  std::shared_ptr<sensesp::PersistingObservableValue<float>> switch_max_on_s_;
  std::shared_ptr<sensesp::PersistingObservableValue<float>> switch_min_off_s_;
  std::shared_ptr<sensesp::PersistingObservableValue<float>> switch_duty_warn_;
  std::shared_ptr<sensesp::PersistingObservableValue<float>> switch_duty_max_;

  // One-way boundary (CLAUDE.md): written only by this task, read only by
  // main.cpp's telemetry-publish loop via latestTelemetry().
  mutable SemaphoreHandle_t telemetry_mutex_ = nullptr;
  TelemetrySnapshot telemetry_{};

  // Loop period/jitter measurement (target: "deterministic
  // timing measured"). Reset each logging window.
  int64_t last_loop_us_ = 0;
  int64_t period_min_us_ = 0;
  int64_t period_max_us_ = 0;
  int64_t period_sum_us_ = 0;
  uint32_t period_count_ = 0;
  uint32_t last_jitter_log_ms_ = 0;
  uint32_t last_status_log_ms_ = 0;
};
