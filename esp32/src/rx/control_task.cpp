#include "control_task.h"

#include <Arduino.h>
#include <driver/gpio.h>
#include <ESP32Servo.h>
#include <esp_task_wdt.h>

#include "common/cached_snapshot.h"

namespace {
constexpr const char* kTag = "rx_control";

using control_core::ActiveSource;
using control_core::Arbitrate;
using control_core::ArmGateInputs;
using control_core::FromSwitch;
using control_core::DrivePosition;
using control_core::RemoteSource;
using control_core::ServoPulseUs;

// The GPIO level that asserts / releases the actuator-engage relay, honouring
// config::kRxArmOutputActiveHigh (the opto stage may invert -- see config.h).
int ArmPinLevel(bool armed) {
  return (armed == config::kRxArmOutputActiveHigh) ? HIGH : LOW;
}

// Raw pin level -> "this lever is in NEUTRAL", honouring
// config::kRxNeutralActiveLow. The sensor pulls the pin to ground while the
// lever sits in neutral, so LOW = neutral by default; an open, broken or
// unplugged sensor reads HIGH = not neutral = arming refused.
bool NeutralAsserted(int raw_level) {
  return config::kRxNeutralActiveLow ? (raw_level == LOW) : (raw_level == HIGH);
}
}  // namespace

void ControlTask::begin(SkCommandIn* sk_tx, SkCommandIn* sk_plugin) {
  sk_tx_ = sk_tx;
  sk_plugin_ = sk_plugin;

  // FIRST hardware action of the whole firmware: release the actuator-engage
  // relay. Everything below this line -- including attaching the servos and
  // driving them to neutral -- must happen with the linkage disconnected, so
  // that whatever position the servos were left in cannot be transmitted to a
  // lever. (The pin is a floating input from reset until this runs; the relay
  // driver's own pull-down covers that window -- see config.h.)
  // Level first, THEN the driver: the released level goes into the GPIO output
  // register while the pin is still an input, so pinMode(OUTPUT) starts driving
  // that rather than the pin's reset default. That default is LOW, which is
  // only accidentally "released" -- with kRxArmOutputActiveHigh false it is
  // ENGAGED, and this pin drives a clutch onto a shift lever. The final write
  // re-asserts with the driver live so nothing here depends on what pinMode()
  // does to the output register (it does nothing: __pinMode() calls
  // gpio_config(), which leaves the output data register alone).
  //
  // The first write MUST be gpio_set_level(), not digitalWrite():
  // arduino-esp32 3.x gates digitalWrite() behind its peripheral manager, so
  // on a pin that has not yet had pinMode() called it writes nothing at all
  // and merely logs "IO 33 is not set as GPIO" (esp32-hal-gpio.c). Using it
  // here would leave this whole level-before-driver ordering doing nothing.
  gpio_set_level(static_cast<gpio_num_t>(config::kRxArmOutputPin),
                 ArmPinLevel(false) == HIGH ? 1 : 0);
  pinMode(config::kRxArmOutputPin, OUTPUT);
  digitalWrite(config::kRxArmOutputPin, ArmPinLevel(false));

  telemetry_mutex_ = xSemaphoreCreateMutex();
  if (telemetry_mutex_ == nullptr) {
    // Out of memory this early is fatal for the safe core -- without the
    // telemetry mutex the control loop can't publish its snapshot. Log
    // loudly rather than dereference a null handle later. (Servos are still
    // driven to neutral below, so the fail-safe output state holds.)
    ESP_LOGE(kTag, "telemetry mutex alloc failed -- control task not started");
  }

  pinMode(config::kRxPortForwardPin, INPUT_PULLDOWN);
  pinMode(config::kRxPortReversePin, INPUT_PULLDOWN);
  pinMode(config::kRxStbdForwardPin, INPUT_PULLDOWN);
  pinMode(config::kRxStbdReversePin, INPUT_PULLDOWN);
  pinMode(config::kRxMasterEnablePin, INPUT_PULLDOWN);
  // Plain INPUT, NOT INPUT_PULLUP: GPIO36/39 are input-only and have no
  // internal pull of either kind, so INPUT_PULLUP would compile, run, and
  // silently do nothing. Each line needs its EXTERNAL 10 k pull-up to 3V3 --
  // see the block comment on kRxPortNeutralPin in config.h and the
  // unplug-the-sensor commissioning check in SAFETY.md.
  pinMode(config::kRxPortNeutralPin, INPUT);
  pinMode(config::kRxStbdNeutralPin, INPUT);

  ESP32PWM::allocateTimer(0);
  ESP32PWM::allocateTimer(1);
  port_servo_ = new Servo();
  stbd_servo_ = new Servo();
  port_servo_->setPeriodHertz(config::kServoPeriodHz);
  stbd_servo_->setPeriodHertz(config::kServoPeriodHz);
  port_servo_->attach(config::kRxPortServoPin, config::kServoAttachMinUs,
                       config::kServoAttachMaxUs);
  stbd_servo_->attach(config::kRxStbdServoPin, config::kServoAttachMinUs,
                       config::kServoAttachMaxUs);
  // Drive both to neutral immediately on boot -- never start in an
  // arbitrary/last-flashed position before the control loop has run a
  // single tick (SAFETY.md drive invariant 5: fail to NEUTRAL, never hold a
  // stale/undefined command).
  port_servo_->writeMicroseconds(port_cal_.neutral_us);
  stbd_servo_->writeMicroseconds(stbd_cal_.neutral_us);

  // Don't start the loop if its telemetry mutex failed to allocate -- Run()
  // takes it every tick. Servos stay at the neutral written above and the
  // engage relay stays released, so nothing can reach a lever (fail-safe).
  if (telemetry_mutex_ == nullptr) {
    return;
  }

  BaseType_t rc = xTaskCreatePinnedToCore(
      &ControlTask::TaskEntry, "rx_control", config::kControlTaskStackBytes,
      this, config::kControlTaskPriority, &task_handle_,
      config::kControlTaskCore);
  if (rc != pdPASS) {
    // The safe core never came up. Servos remain at the boot-neutral written
    // above, the engage relay stays released, and nothing ever moves --
    // fail-safe, but silent otherwise, so log it.
    task_handle_ = nullptr;
    ESP_LOGE(kTag,
             "control task create failed (rc=%d) -- servos held neutral, "
             "engage relay released",
             static_cast<int>(rc));
  }
}

void ControlTask::TaskEntry(void* pv) {
  static_cast<ControlTask*>(pv)->Run();
}

void ControlTask::Run() {
  TickType_t last_wake = xTaskGetTickCount();
  const TickType_t period = pdMS_TO_TICKS(config::kRxControlPeriodMs);

  // Subscribe the safe core to the Task Watchdog: this task owns the servo
  // outputs and the engage relay, so if it ever stalls (it has no blocking
  // calls, so this is defense-in-depth) the TWDT reboots the board -- which
  // re-runs begin(), releasing the relay and driving both servos back to
  // neutral, the fail-safe state. A failure
  // here (TWDT not initialised on this core) is non-fatal: the loop still
  // runs, just unwatched, so we don't treat it as an error.
  esp_task_wdt_add(nullptr);

  for (;;) {
    esp_task_wdt_reset();
    uint32_t now = millis();

    bool local_port_forward = local_port_forward_db_.Update(
        digitalRead(config::kRxPortForwardPin) == HIGH, now);
    bool local_port_reverse = local_port_reverse_db_.Update(
        digitalRead(config::kRxPortReversePin) == HIGH, now);
    bool local_stbd_forward = local_stbd_forward_db_.Update(
        digitalRead(config::kRxStbdForwardPin) == HIGH, now);
    bool local_stbd_reverse = local_stbd_reverse_db_.Update(
        digitalRead(config::kRxStbdReversePin) == HIGH, now);
    bool rx_master_enable = master_enable_db_.Update(
        digitalRead(config::kRxMasterEnablePin) == HIGH, now);
    bool port_lever_neutral = port_neutral_db_.Update(
        NeutralAsserted(digitalRead(config::kRxPortNeutralPin)), now);
    bool stbd_lever_neutral = stbd_neutral_db_.Update(
        NeutralAsserted(digitalRead(config::kRxStbdNeutralPin)), now);

    DrivePosition local_port =
        FromSwitch(local_port_forward, local_port_reverse);
    DrivePosition local_stbd =
        FromSwitch(local_stbd_forward, local_stbd_reverse);

    // Arm permission (drive/arm_gate.h), one decision shared by both drives:
    // may a remote source command at all, and may the actuator-engage relay be
    // energised? The local switches feed in because while disarmed the linkage
    // is disconnected and a held local switch can park a servo off neutral --
    // engaging the clutch there would slam that lever into gear. Note this
    // reads the LOCAL positions, not the arbitrated ones: they are the same
    // thing whenever the gate is disarmed (remote sources are gated off), and
    // using them keeps the arm decision ahead of arbitration in the tick with
    // no stale-by-one-tick feedback loop.
    ArmGateInputs arm_in;
    arm_in.master_enable = rx_master_enable;
    arm_in.port_lever_neutral = port_lever_neutral;
    arm_in.stbd_lever_neutral = stbd_lever_neutral;
    arm_in.local_command_active = local_port != DrivePosition::kNeutral ||
                                   local_stbd != DrivePosition::kNeutral;
    auto arm = arm_gate_.Update(arm_in);
    // The relay is written BEFORE the servos below, so a disarm releases the
    // linkage on the same tick the commands fall to NEUTRAL rather than one
    // tick later. Consequence to know at the helm: disarming with a drive in
    // gear releases the clutch where it stands -- the servo returns to neutral
    // connected to nothing, and the LEVER stays in gear for the operator to
    // move by hand. That is the "output follows ARMED exactly" contract the
    // owner asked for; see SAFETY.md drive invariant 7 for the alternative
    // (shift to neutral first, then release) and why it isn't the default.
    digitalWrite(config::kRxArmOutputPin, ArmPinLevel(arm.armed));

    // Non-blocking; on the vanishingly rare contended case age the last
    // coherent snapshot locally rather than stalling or extending authority.
    if (!sk_tx_->Snapshot(now, config::kSkStalenessTimeoutMs, &last_tx_port_,
                          &last_tx_stbd_)) {
      // Age the last coherent copy locally. A brief callback collision does
      // not create a false link loss, but cached authority cannot outlive the
      // normal source timeout. The rule lives in the pure core so RX, HH and
      // both source families cannot drift apart -- see common/cached_snapshot.h.
      control_core::AgeCachedSnapshot(last_tx_port_, now,
                                      config::kSkStalenessTimeoutMs);
      control_core::AgeCachedSnapshot(last_tx_stbd_, now,
                                      config::kSkStalenessTimeoutMs);
    }
    if (!sk_plugin_->Snapshot(now, config::kSkStalenessTimeoutMs,
                              &last_plugin_port_, &last_plugin_stbd_)) {
      control_core::AgeCachedSnapshot(last_plugin_port_, now,
                                      config::kSkStalenessTimeoutMs);
      control_core::AgeCachedSnapshot(last_plugin_stbd_, now,
                                      config::kSkStalenessTimeoutMs);
    }

    // arm.armed, NOT rx_master_enable: the switch being ON is necessary but
    // not sufficient (SAFETY.md drive invariant 7 -- the levers must have been
    // proven neutral). Local override is unaffected either way; Arbitrate()
    // resolves it before it ever looks at this argument.
    auto port_result = Arbitrate(local_port, arm.armed, last_tx_port_,
                                  last_plugin_port_);
    auto stbd_result = Arbitrate(local_stbd, arm.armed, last_tx_stbd_,
                                  last_plugin_stbd_);

    control_core::ServoCalibration port_cal;
    control_core::ServoCalibration stbd_cal;
    portENTER_CRITICAL(&calibration_mux_);
    port_cal = port_cal_;
    stbd_cal = stbd_cal_;
    portEXIT_CRITICAL(&calibration_mux_);
    port_servo_->writeMicroseconds(ServoPulseUs(port_result.command, port_cal));
    stbd_servo_->writeMicroseconds(ServoPulseUs(stbd_result.command, stbd_cal));

    // ARCHITECTURE.md: RX's link-OK LED means "at least one remote source is
    // currently live+enabled" -- independent of which drive (if any) a
    // LOCAL switch happens to be overriding right now, so read it off the
    // remote sources' own live/enabled flags rather than port_result/
    // stbd_result.source (which would read kLocal whenever a local switch
    // is active, masking whether the remote link is actually healthy).
    // Reading only the *_port_ snapshots is exact, not a shortcut: each
    // SkCommandIn evaluates one coherent tuple, so a source's .live/.enabled
    // are identical across its port and stbd snapshots (see
    // SkCommandIn::Snapshot). If that ever became per-drive, this would
    // need to OR in the stbd snapshots too.
    bool link_ok = (last_tx_port_.live && last_tx_port_.enabled) ||
                   (last_plugin_port_.live && last_plugin_port_.enabled);

    // Pure link-health: is any remote source LIVE at all, regardless of
    // whether it reports itself enabled/armed? This is what "RX link is up
    // and ready" means for the phone UI (rx.linkUp) -- a source that is live
    // but disarmed (e.g. the plugin publishing its steady disarmed heartbeat)
    // still counts here, but not for link_ok/the LED above.
    bool link_up = last_tx_port_.live || last_plugin_port_.live;

    if (xSemaphoreTake(telemetry_mutex_, 0) == pdTRUE) {
      telemetry_.port_command = port_result.command;
      telemetry_.stbd_command = stbd_result.command;
      telemetry_.port_source = port_result.source;
      telemetry_.stbd_source = stbd_result.source;
      telemetry_.rx_master_enable = rx_master_enable;
      telemetry_.armed = arm.armed;
      telemetry_.port_lever_neutral = port_lever_neutral;
      telemetry_.stbd_lever_neutral = stbd_lever_neutral;
      telemetry_.arm_inhibit = arm.inhibit;
      telemetry_.link_ok = link_ok;
      telemetry_.link_up = link_up;
      xSemaphoreGive(telemetry_mutex_);
    }

    vTaskDelayUntil(&last_wake, period);
  }
}

bool ControlTask::latestTelemetry(TelemetrySnapshot* out) const {
  // If the mutex never allocated (begin() logged it and the control task was
  // not started), report "no snapshot" instead of passing nullptr to
  // xSemaphoreTake, which would configASSERT into a reboot loop -- turning a
  // degraded-but-neutral state into a crash loop.
  if (telemetry_mutex_ == nullptr) {
    return false;
  }
  if (xSemaphoreTake(telemetry_mutex_, 0) != pdTRUE) {
    return false;
  }
  *out = telemetry_;
  xSemaphoreGive(telemetry_mutex_);
  return true;
}

void ControlTask::SetPortCalibration(const control_core::ServoCalibration& cal) {
  portENTER_CRITICAL(&calibration_mux_);
  port_cal_ = cal;
  portEXIT_CRITICAL(&calibration_mux_);
}

void ControlTask::SetStbdCalibration(const control_core::ServoCalibration& cal) {
  portENTER_CRITICAL(&calibration_mux_);
  stbd_cal_ = cal;
  portEXIT_CRITICAL(&calibration_mux_);
}

const char* ControlTask::SourceName(ActiveSource source) {
  switch (source) {
    case ActiveSource::kLocal:
      return "local";
    case ActiveSource::kTx:
      return "tx";
    case ActiveSource::kPlugin:
      return "plugin";
    case ActiveSource::kNone:
    default:
      return "none";
  }
}
