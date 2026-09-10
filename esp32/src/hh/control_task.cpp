#include "control_task.h"

#include <Arduino.h>
#include <esp_task_wdt.h>
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include "common/cached_snapshot.h"
#include "config.h"
#include "sensesp/ui/config_item.h"

control_core::ControlStep::Cfg ControlTask::MakeStepCfg() {
  control_core::ControlStep::Cfg cfg;
  // Heading fusion is now driven from config.h rather than left to in-class
  // defaults: all three of these were measured on the water (2026-08-20) and
  // AGENTS.md puts measured tunables in config.h, not buried in a header's
  // default member initialiser. Fsm keeps its in-class defaults; SwitchCfg
  // keeps its ARCHITECTURE.md §11 "typical start" in-class defaults with only
  // the measured Item 2 reversal dead time overridden.
  cfg.heading.tau_corr_s = config::kHeadingCorrTauS;
  cfg.heading.t_fresh_s = config::kHeadingFreshS;
  cfg.yaw_lpf_tau_s = config::kYawRateLpfTauS;
  cfg.switcher.reversal_dwell_s = config::kReversalDwellS;
  cfg.engage_assert_stable_ms = config::kEngageAssertStableMs;
  cfg.engage_release_stable_ms = config::kEngageReleaseStableMs;
  // Manual (direct human) thruster control has its OWN reversal dwell knob
  // (config.h), independent of the HOLD value above and deliberately
  // different from it: HOLD waits out the measured ~1.75 s control-box
  // interlock in full because nobody is watching the tunnel, MANUAL ships at
  // 0 because a human is (owner decision -- see config::kManualReversalDwellS
  // and manual_thrust.h). Both gates measure their dwell against the same
  // shared last-thrust history, so a mode change cannot bypass either.
  cfg.manual_reversal_dwell_s = config::kManualReversalDwellS;
  cfg.setpoint_slew_dps = config::kSetpointSlewDps;
  return cfg;
}

const char* ControlTask::StateName(control_core::FsmState s) {
  switch (s) {
    case control_core::FsmState::kDisarmed:
      return "DISARMED";
    case control_core::FsmState::kArmedIdle:
      return "ARMED_IDLE";
    case control_core::FsmState::kHolding:
      return "HOLDING";
    case control_core::FsmState::kFault:
      return "FAULT";
  }
  return "?";
}

const char* ControlTask::ModeName(control_core::ThrusterMode m) {
  return control_core::ThrusterModeName(m);
}

const char* ControlTask::SourceName(control_core::ActiveSource s) {
  return control_core::ActiveSourceName(s);
}

const char* ControlTask::CmdName(control_core::Cmd c) {
  switch (c) {
    case control_core::Cmd::kOff:
      return "OFF";
    case control_core::Cmd::kPort:
      return "PORT";
    case control_core::Cmd::kStbd:
      return "STBD";
  }
  return "?";
}

void ControlTask::begin(SkHeadingIn* sk_heading_in, SkThrusterIn* tx_thruster,
                        SkThrusterIn* plugin_thruster) {
  sk_heading_in_ = sk_heading_in;
  tx_thruster_ = tx_thruster;
  plugin_thruster_ = plugin_thruster;

  rvc_reader_.begin(Serial2, config::kBnoUartRxPin);
  // Plain INPUT: GPIO35 is input-only and has no internal pull of either
  // kind, so INPUT_PULLUP/INPUT_PULLDOWN would compile and silently do
  // nothing here. The resting level is whatever the board's opto stage
  // presents -- which is exactly why the polarity is a config flag and not a
  // hardcoded HIGH (config::kEngageActiveHigh).
  pinMode(config::kEngagePin, INPUT);
  if (config::kDeadmanWired) {
    // INPUT_PULLDOWN, which actually works now that the deadman is on GPIO19
    // (tier A) rather than the pull-less input-only GPIO39: unwired or broken
    // reads LOW = not-held = safe. Fit the external 10 k anyway for cable-run
    // noise immunity -- see config::kDeadmanPin.
    pinMode(config::kDeadmanPin, INPUT_PULLDOWN);
  }
  outputs_.begin();

  telemetry_mutex_ = xSemaphoreCreateMutex();
  if (telemetry_mutex_ == nullptr) {
    // Same degrade-not-crash rule as SkCommandIn/SkThrusterIn: passing a
    // null handle to xSemaphoreTake is a FreeRTOS assert (crash-loop under
    // boot heap pressure). Telemetry publishing goes quiet; control and the
    // fail-off watchdog are unaffected.
    ESP_LOGE("control", "telemetry mutex creation failed -- telemetry disabled");
  }

  // MEASUREMENTS.md Item 6: live-configurable IMU mount tare, persisted to
  // flash. Defaults are identity/no-op (see config.h) until measured.
  imu_roll_tare_deg_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kImuRollTareDegDefault, config::kImuRollTareConfigPath);
  imu_pitch_tare_deg_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kImuPitchTareDegDefault, config::kImuPitchTareConfigPath);
  imu_yaw_rate_invert_ = std::make_shared<sensesp::PersistingObservableValue<bool>>(
      config::kImuYawRateInvertDefault, config::kImuYawRateInvertConfigPath);

  ConfigItem(imu_roll_tare_deg_)
      ->set_title("IMU Roll Tare")
      ->set_description(
          "Subtracted from published/measured roll so level reads zero "
          "(MEASUREMENTS.md Item 6)")
      ->set_sort_order(100);
  ConfigItem(imu_pitch_tare_deg_)
      ->set_title("IMU Pitch Tare")
      ->set_description(
          "Subtracted from published/measured pitch so level reads zero "
          "(MEASUREMENTS.md Item 6)")
      ->set_sort_order(101);
  ConfigItem(imu_yaw_rate_invert_)
      ->set_title("Invert Yaw Rate")
      ->set_description(
          "Flip if starboard turns don't read as increasing heading rate "
          "(MEASUREMENTS.md Item 6)")
      ->set_sort_order(102);

  // ARCHITECTURE.md §11 sea-trial tuning knobs. reversal_dwell_s is NOT here -- it
  // stays fixed at the measured MEASUREMENTS.md Item 2 value
  // (config::kReversalDwellS), never live-editable (see
  // Switcher::SetTunables).
  switch_on_thr_deg_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kOnThrDegDefault, config::kOnThrConfigPath);
  switch_off_thr_deg_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kOffThrDegDefault, config::kOffThrConfigPath);
  switch_lead_time_s_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kLeadTimeSDefault, config::kLeadTimeConfigPath);
  switch_min_on_s_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kMinOnSDefault, config::kMinOnConfigPath);
  switch_max_on_s_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kMaxOnSDefault, config::kMaxOnConfigPath);
  switch_min_off_s_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kMinOffSDefault, config::kMinOffConfigPath);
  switch_duty_warn_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kDutyWarnDefault, config::kDutyWarnConfigPath);
  switch_duty_max_ = std::make_shared<sensesp::PersistingObservableValue<float>>(
      config::kDutyMaxDefault, config::kDutyMaxConfigPath);

  ConfigItem(switch_on_thr_deg_)
      ->set_title("Switcher On Threshold (deg)")
      ->set_description("Lead-variable magnitude that engages a direction (ARCHITECTURE.md §11)")
      ->set_sort_order(110);
  ConfigItem(switch_off_thr_deg_)
      ->set_title("Switcher Off Threshold (deg)")
      ->set_description("Must stay below the On Threshold (Schmitt deadband)")
      ->set_sort_order(111);
  ConfigItem(switch_lead_time_s_)
      ->set_title("Switcher Lead Time Td (s)")
      ->set_description("Rate-term weight in the lead variable s = e - Td*r")
      ->set_sort_order(112);
  ConfigItem(switch_min_on_s_)
      ->set_title("Switcher Min On Time (s)")
      ->set_description("Minimum time a direction stays engaged once it bites")
      ->set_sort_order(113);
  ConfigItem(switch_max_on_s_)
      ->set_title("Switcher Max On Time (s)")
      ->set_description("Hard cap on one continuous thrust; bounds the case where the boat does not answer")
      ->set_sort_order(114);
  ConfigItem(switch_min_off_s_)
      ->set_title("Switcher Min Off Time (s)")
      ->set_description("Minimum time off before re-engaging the same direction")
      ->set_sort_order(115);
  ConfigItem(switch_duty_warn_)
      ->set_title("Switcher Duty Warn (0-1)")
      ->set_description("Duty fraction above which the deadband widens (S2 protection)")
      ->set_sort_order(116);
  ConfigItem(switch_duty_max_)
      ->set_title("Switcher Duty Max (0-1)")
      ->set_description("Duty fraction above which new engagement is inhibited")
      ->set_sort_order(117);

  // Independent fail-off watchdog (see class comment): seed the heartbeat
  // so the timer measures "since begin()" until the task's first tick,
  // then start the periodic check. Started BEFORE the control task so
  // there is no window where a task that fails to start goes unwatched --
  // a trip while outputs are already safe is a loud no-op.
  heartbeat_ms_.store(millis(), std::memory_order_relaxed);
  esp_timer_create_args_t failoff_args = {};
  failoff_args.callback = &ControlTask::FailoffWatchdogTrampoline;
  failoff_args.arg = this;
  failoff_args.dispatch_method = ESP_TIMER_TASK;
  failoff_args.name = "output_failoff";
  failoff_args.skip_unhandled_events = true;
  ESP_ERROR_CHECK(esp_timer_create(&failoff_args, &failoff_timer_));
  ESP_ERROR_CHECK(esp_timer_start_periodic(
      failoff_timer_, config::kOutputFailoffCheckPeriodMs * 1000ULL));

  xTaskCreatePinnedToCore(&ControlTask::TaskTrampoline, "control_task",
                           config::kControlTaskStackBytes, this,
                           config::kControlTaskPriority, nullptr,
                           config::kControlTaskCore);
}

void ControlTask::TaskTrampoline(void* arg) {
  static_cast<ControlTask*>(arg)->Run();
}

void ControlTask::FailoffWatchdogTrampoline(void* arg) {
  static_cast<ControlTask*>(arg)->CheckFailoff();
}

// Runs in the esp_timer service task (core 0), NOT the control task -- the
// whole point is that it keeps running when the control task doesn't.
void ControlTask::CheckFailoff() {
  uint32_t now_ms = millis();
  uint32_t age_ms = now_ms - heartbeat_ms_.load(std::memory_order_relaxed);
  if (age_ms <= config::kOutputFailoffTimeoutMs) {
    return;
  }
  // Control loop stale: force everything off. Idempotent, and repeated on
  // every check period until the heartbeat resumes -- if the control task
  // recovers it will simply reassert its own (safe, FSM-derived) state.
  // The cross-task write to outputs_ is deliberate and confined to this one
  // call site. A stale heartbeat is strong evidence the owning task is not
  // running, but not proof it cannot resume mid-callback; see Outputs::allOff
  // for why an interleaving cannot break the never-both-directions invariant,
  // and why the repeat interval bounds the consequence either way.
  outputs_.allOff();
  if (now_ms - last_failoff_log_ms_ >= 1000) {
    last_failoff_log_ms_ = now_ms;
    ESP_LOGE("control",
             "FAIL-OFF: control task heartbeat stale (%u ms) -- outputs "
             "forced off",
             static_cast<unsigned>(age_ms));
  }
}

void ControlTask::Run() {
  TickType_t last_wake = xTaskGetTickCount();
  last_loop_us_ = esp_timer_get_time();

  // Subscribe the safe core to the Task Watchdog, exactly as RX's control task
  // does. The esp_timer fail-off watchdog above already forces the outputs OFF
  // if this task stalls, but it leaves the board wedged with a dead control
  // loop until someone power-cycles it; the TWDT (5 s, panic) additionally
  // reboots, which re-runs begin() and brings the thruster back into a
  // usable, fail-safe state. It has no blocking calls, so this is
  // defence-in-depth. A failure here (TWDT not initialised on this core) is
  // non-fatal: the loop still runs, just unwatched.
  esp_task_wdt_add(nullptr);

  while (true) {
    vTaskDelayUntil(&last_wake, pdMS_TO_TICKS(config::kHhControlPeriodMs));
    esp_task_wdt_reset();

    int64_t now_us = esp_timer_get_time();
    int64_t period_us = now_us - last_loop_us_;
    last_loop_us_ = now_us;
    float dt_s = period_us / 1.0e6f;
    uint32_t now_ms = millis();

    RecordJitterAndLogIfDue(period_us, now_ms);
    Tick(now_ms, dt_s);

    // Refresh the fail-off watchdog only after a fully completed tick --
    // a task that wedges mid-tick must look stale, not alive.
    heartbeat_ms_.store(now_ms, std::memory_order_relaxed);
  }
}

// Hardware glue only -- the decision pipeline lives in the pure,
// host-tested control_core::ControlStep (see control_task.h class
// comment). Sample the hardware, run one Step, apply the result.
void ControlTask::Tick(uint32_t now_ms, float dt_s) {
  control_core::ControlStep::Inputs in;
  in.now_ms = now_ms;
  in.dt_s = dt_s;

  // BNO frame poll. poll() and timedOut() both use this same now_ms
  // snapshot (not an internal millis() call) so the two can never drift
  // enough to underflow RvcParser's unsigned timeout subtraction -- see
  // rvc_reader.h.
  control_core::RvcSample sample;
  if (rvc_reader_.poll(&sample, now_ms)) {
    in.new_bno_frame = true;
    in.bno_yaw_deg = sample.yaw_deg;
    last_roll_deg_ = sample.roll_deg;
    last_pitch_deg_ = sample.pitch_deg;
  }
  in.bno_ok = !rvc_reader_.timedOut(now_ms);
  in.yaw_rate_invert = imu_yaw_rate_invert_->get();

  // ENGAGE polarity is config::kEngageActiveHigh, NOT hardcoded: the sense of
  // the board's opto input stage is an assumption until it is metered, and if
  // it inverts, the wrong constant here arms the thruster on an empty
  // connector. See that flag's comment and SAFETY.md's thruster checklist.
  const int engage_level = digitalRead(config::kEngagePin);
  in.engage_raw = config::kEngageActiveHigh ? (engage_level == HIGH)
                                            : (engage_level == LOW);
  // Deadman: gated on config::kDeadmanWired (see its comment). The gate is
  // now about honesty, not safety -- GPIO19 has an internal pull-down, so an
  // unwired read would be a definite LOW rather than the float GPIO39 gave --
  // but reporting "held" from a switch nobody installed is a lie the FSM acts
  // on. Held/closed = HIGH = ok once wired.
  in.deadman_ok =
      config::kDeadmanWired ? digitalRead(config::kDeadmanPin) == HIGH : true;

  sk_heading_in_->latest(&cached_gnss_);
  in.gnss = cached_gnss_;

  // Remote thruster command sources (ARCHITECTURE.md §6). Both snapshots are
  // non-blocking: on a contended tick the previous coherent snapshot is aged
  // locally rather than stalling the safe core (SAFETY.md thruster invariant
  // 5). A stale cache cannot outlive the source timeout, and the local ENGAGE
  // input outranks both regardless (invariant 6).
  if (tx_thruster_ != nullptr) {
    if (!tx_thruster_->Snapshot(now_ms, config::kThrusterSourceStalenessMs,
                                &cached_tx_)) {
      // Shared with RX's drive path -- see common/cached_snapshot.h for why the
      // rule is not written out here.
      control_core::AgeCachedSnapshot(cached_tx_, now_ms,
                                      config::kThrusterSourceStalenessMs);
    }
  }
  if (plugin_thruster_ != nullptr) {
    if (!plugin_thruster_->Snapshot(now_ms,
                                    config::kThrusterSourceStalenessMs,
                                    &cached_plugin_)) {
      control_core::AgeCachedSnapshot(cached_plugin_, now_ms,
                                      config::kThrusterSourceStalenessMs);
    }
  }
  in.tx = cached_tx_;
  in.plugin = cached_plugin_;
  // Pure link-health for hh.linkUp: any remote source LIVE, enabled or not --
  // the definition RX uses for rx.linkUp, so a disarmed plugin heartbeat reads
  // the same on both units. Not the arbitrated source (which is kNone for a
  // live-but-disarmed station, and kLocal with no remote at all).
  const bool link_up = cached_tx_.live || cached_plugin_.live;

  // ARCHITECTURE.md §11 sea-trial tuning: push the live/persisted, web-UI-editable
  // knobs into the Switcher every tick (cheap field copies, no
  // allocation); Switcher::SetTunables validates/clamps every value at
  // this untrusted boundary. reversal_dwell_s/duty_window_s are untouched
  // -- see its header comment.
  control_step_.SetSwitchTunables(
      switch_on_thr_deg_->get(), switch_off_thr_deg_->get(),
      switch_lead_time_s_->get(), switch_min_on_s_->get(),
      switch_max_on_s_->get(), switch_min_off_s_->get(),
      switch_duty_warn_->get(), switch_duty_max_->get());

  last_step_ = control_step_.Step(in);

  // Single atomic output write (SAFETY.md thruster invariants 1-3): armed and
  // direction land together, no intermediate state between two calls.
  outputs_.apply(last_step_.armed, last_step_.dir);

  PublishTelemetrySnapshot(last_step_, in.bno_ok, link_up);
  LogStatusIfDue(now_ms);
}

void ControlTask::PublishTelemetrySnapshot(
    const control_core::ControlStep::Outputs& step, bool bno_ok,
    bool link_up) {
  // ARCHITECTURE.md §9: mount tare applied here (SK boundary), not inside the
  // pure modules -- see class comment in control_task.h. Yaw-rate sign is
  // already applied inside ControlStep, before the rate reached
  // HeadingFilter::Predict(), so no further correction is needed here.
  TelemetrySnapshot snap;
  snap.roll_rad =
      (last_roll_deg_ - imu_roll_tare_deg_->get()) * DEG_TO_RAD;
  snap.pitch_rad =
      (last_pitch_deg_ - imu_pitch_tare_deg_->get()) * DEG_TO_RAD;
  snap.yaw_rad = step.fused_deg * DEG_TO_RAD;
  snap.rate_of_turn_rad_s = step.r_dps * DEG_TO_RAD;
  snap.state = step.state;
  snap.bno_ok = bno_ok;
  // Whether the fused heading is CURRENTLY being trusted/corrected (age
  // since last accepted fix within t_fresh_s) -- not "has SkHeadingIn ever
  // seen a real delta," which is SkHeadingIn::latest()'s own, permanently-
  // latching notion of valid (see sk_heading_in.h). An operator watching
  // this in the SK data browser needs "is this good right now," which is
  // this bool, not that latch.
  snap.gnss_valid = step.heading_valid;
  snap.gnss_age_ms = step.heading_age_ms;

  // ARCHITECTURE.md §9 tuning signals. The Switcher drives setpoint/error
  // while HOLDING in HOLD mode; everywhere else the setpoint mirrors the
  // fused heading (control_step.cpp), so error reads a truthful zero rather
  // than a stale one. switch_cmd reads kOff outside HOLDING.
  snap.setpoint_rad = step.setpoint_deg * DEG_TO_RAD;
  snap.error_rad = step.error_deg * DEG_TO_RAD;
  snap.switch_cmd = step.dir;
  snap.duty = step.duty;

  // Remote-control state (ARCHITECTURE.md §6).
  snap.source = step.source;
  snap.mode = step.mode;
  snap.reversal_pending = step.reversal_pending;
  snap.armed = step.armed;
  snap.setpoint_deg = step.setpoint_deg;
  snap.link_up = link_up;

  // Non-blocking write, mirroring SkHeadingIn's contract: the control
  // loop must never wait on this. On the vanishingly rare contended tick,
  // just skip -- one stale tick is harmless for a 10-20 Hz consumer.
  if (telemetry_mutex_ == nullptr) {
    return;  // creation failed at begin() -- degrade, never crash
  }
  if (xSemaphoreTake(telemetry_mutex_, 0) == pdTRUE) {
    telemetry_ = snap;
    xSemaphoreGive(telemetry_mutex_);
  }
}

bool ControlTask::latestTelemetry(TelemetrySnapshot* out) const {
  if (telemetry_mutex_ == nullptr) {
    return false;  // creation failed at begin() -- degrade, never crash
  }
  if (xSemaphoreTake(telemetry_mutex_, 0) != pdTRUE) {
    return false;
  }
  *out = telemetry_;
  xSemaphoreGive(telemetry_mutex_);
  return true;
}

void ControlTask::LogStatusIfDue(uint32_t now_ms) {
  constexpr uint32_t kLogPeriodMs = 2000;
  if (now_ms - last_status_log_ms_ < kLogPeriodMs) {
    return;
  }
  last_status_log_ms_ = now_ms;

  ESP_LOGI("control",
           "state=%s r=%.2f fused=%.2f setpoint=%.2f e=%.2f dir=%s duty=%.2f "
           "last_correction_age_ms=%u",
           StateName(last_step_.state), last_step_.r_dps, last_step_.fused_deg,
           last_step_.setpoint_deg, last_step_.error_deg,
           CmdName(last_step_.dir), last_step_.duty,
           static_cast<unsigned>(last_step_.heading_age_ms));
}

void ControlTask::RecordJitterAndLogIfDue(int64_t period_us, uint32_t now_ms) {
  if (period_count_ == 0) {
    period_min_us_ = period_us;
    period_max_us_ = period_us;
  } else {
    if (period_us < period_min_us_) period_min_us_ = period_us;
    if (period_us > period_max_us_) period_max_us_ = period_us;
  }
  period_sum_us_ += period_us;
  period_count_++;

  constexpr uint32_t kJitterLogPeriodMs = 5000;
  if (now_ms - last_jitter_log_ms_ < kJitterLogPeriodMs) {
    return;
  }
  last_jitter_log_ms_ = now_ms;

  float avg_ms = (period_sum_us_ / static_cast<float>(period_count_)) / 1000.0f;
  ESP_LOGI("control", "period target=%ums avg=%.2fms min=%.2fms max=%.2fms n=%u",
            static_cast<unsigned>(config::kHhControlPeriodMs), avg_ms,
            period_min_us_ / 1000.0f, period_max_us_ / 1000.0f, period_count_);

  period_count_ = 0;
  period_sum_us_ = 0;
}
