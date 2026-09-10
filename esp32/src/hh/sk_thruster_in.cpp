#include "sk_thruster_in.h"

#include <Arduino.h>

#include "heading/setpoint.h"  // control_core::ClampTrimDeg
#include "sensesp/system/lambda_consumer.h"

void SkThrusterIn::begin(const char* command_path, const char* mode_path,
                         const char* trim_path, const char* enabled_path,
                         int listen_delay_ms) {
  mutex_ = xSemaphoreCreateMutex();
  if (mutex_ == nullptr) {
    // Same failure posture as SkCommandIn/ControlTask: without the mutex this
    // source can never be read, so its watchdog never goes live and
    // arbitration treats it as absent -- fail-safe. The callbacks below all
    // no-op on a null mutex rather than configASSERT-crashing.
    ESP_LOGE("sk_thruster_in",
             "mutex alloc failed -- source '%s' will stay not-live",
             command_path);
  }

  command_listener_ = std::make_shared<sensesp::SKValueListener<String>>(
      command_path, listen_delay_ms);
  command_listener_->connect_to(
      new sensesp::LambdaConsumer<String>([this](String value) {
        if (mutex_ == nullptr) return;
        xSemaphoreTake(mutex_, portMAX_DELAY);
        command_ = control_core::ThrusterCmdFromSkString(value.c_str());
        command_watchdog_.Update(millis());
        xSemaphoreGive(mutex_);
      }));

  mode_listener_ = std::make_shared<sensesp::SKValueListener<String>>(
      mode_path, listen_delay_ms);
  mode_listener_->connect_to(
      new sensesp::LambdaConsumer<String>([this](String value) {
        if (mutex_ == nullptr) return;
        xSemaphoreTake(mutex_, portMAX_DELAY);
        mode_ = control_core::ThrusterModeFromSkString(value.c_str());
        mode_watchdog_.Update(millis());
        xSemaphoreGive(mutex_);
      }));

  trim_listener_ = std::make_shared<sensesp::SKValueListener<float>>(
      trim_path, listen_delay_ms);
  trim_listener_->connect_to(
      new sensesp::LambdaConsumer<float>([this](float value) {
        if (mutex_ == nullptr) return;
        xSemaphoreTake(mutex_, portMAX_DELAY);
        // Screened at the boundary, not deep in the control law. The trim is
        // RELATIVE (0 = no trim), so there is no "no command" case to detect
        // and no sentinel: any value simply becomes a bounded offset. NaN or
        // Inf -- including a JSON null that ArduinoJson's float listener reads
        // as 0.0f -- collapses to 0, which is exactly the safe rest here (it
        // would have been a bogus "hold 0/north" for the old absolute target,
        // which is why THAT one needed an out-of-range sentinel). ControlStep
        // re-clamps as defence in depth.
        trim_deg_ = control_core::ClampTrimDeg(value);
        trim_watchdog_.Update(millis());
        xSemaphoreGive(mutex_);
      }));

  enabled_listener_ = std::make_shared<sensesp::SKValueListener<bool>>(
      enabled_path, listen_delay_ms);
  enabled_listener_->connect_to(
      new sensesp::LambdaConsumer<bool>([this](bool value) {
        if (mutex_ == nullptr) return;
        xSemaphoreTake(mutex_, portMAX_DELAY);
        enabled_ = value;
        enabled_watchdog_.Update(millis());
        xSemaphoreGive(mutex_);
      }));
}

bool SkThrusterIn::Snapshot(uint32_t now_ms, uint32_t timeout_ms,
                            control_core::ThrusterRemote* out) {
  if (mutex_ == nullptr) return false;
  if (xSemaphoreTake(mutex_, 0) != pdTRUE) return false;

  // Every member of the tuple must be current in its own right -- see
  // SkCommandIn::Snapshot for why that is the entire rule and why there is no
  // additional "arrived close together" window. The four listeners update
  // under four separate mutex acquisitions, so a snapshot landing between them
  // sees a torn read of a healthy publisher; treating that as a dead source
  // dropped HOLD for a tick and made the next complete tuple look like a fresh
  // engage, silently recapturing the heading and abandoning the setpoint.
  out->live = command_watchdog_.IsLive(now_ms, timeout_ms) &&
              mode_watchdog_.IsLive(now_ms, timeout_ms) &&
              trim_watchdog_.IsLive(now_ms, timeout_ms) &&
              enabled_watchdog_.IsLive(now_ms, timeout_ms);
  if (out->live) {
    const uint32_t command_age = now_ms - command_watchdog_.LastUpdateMs();
    const uint32_t mode_age = now_ms - mode_watchdog_.LastUpdateMs();
    const uint32_t trim_age = now_ms - trim_watchdog_.LastUpdateMs();
    const uint32_t enabled_age = now_ms - enabled_watchdog_.LastUpdateMs();
    const uint32_t oldest =
        max(max(command_age, mode_age), max(trim_age, enabled_age));
    out->last_update_ms = now_ms - oldest;
  }
  out->enabled = enabled_;
  out->mode = mode_;
  out->manual_cmd = command_;
  out->trim_deg = trim_deg_;

  xSemaphoreGive(mutex_);
  return true;
}
