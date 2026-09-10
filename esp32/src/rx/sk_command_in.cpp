#include "sk_command_in.h"

#include <Arduino.h>

#include "sensesp/system/lambda_consumer.h"

void SkCommandIn::begin(const char* port_path, const char* stbd_path,
                         const char* enabled_path, int listen_delay_ms) {
  mutex_ = xSemaphoreCreateMutex();
  if (mutex_ == nullptr) {
    // Same failure posture as ControlTask::begin(): without the mutex this
    // source can never be read, so its watchdog never goes live and
    // arbitration treats it as absent (fail-safe). The callbacks below all
    // no-op on a null mutex rather than configASSERT-crashing in
    // xSemaphoreTake.
    ESP_LOGE("sk_command_in",
             "mutex alloc failed -- source '%s' will stay not-live", port_path);
  }

  port_listener_ = std::make_shared<sensesp::SKValueListener<String>>(
      port_path, listen_delay_ms);
  port_listener_->connect_to(new sensesp::LambdaConsumer<String>(
      [this](String value) {
        if (mutex_ == nullptr) return;
        xSemaphoreTake(mutex_, portMAX_DELAY);
        port_ = control_core::FromSkString(value.c_str());
        port_watchdog_.Update(millis());
        xSemaphoreGive(mutex_);
      }));

  stbd_listener_ = std::make_shared<sensesp::SKValueListener<String>>(
      stbd_path, listen_delay_ms);
  stbd_listener_->connect_to(new sensesp::LambdaConsumer<String>(
      [this](String value) {
        if (mutex_ == nullptr) return;
        xSemaphoreTake(mutex_, portMAX_DELAY);
        stbd_ = control_core::FromSkString(value.c_str());
        stbd_watchdog_.Update(millis());
        xSemaphoreGive(mutex_);
      }));

  enabled_listener_ = std::make_shared<sensesp::SKValueListener<bool>>(
      enabled_path, listen_delay_ms);
  enabled_listener_->connect_to(new sensesp::LambdaConsumer<bool>(
      [this](bool value) {
        if (mutex_ == nullptr) return;
        xSemaphoreTake(mutex_, portMAX_DELAY);
        enabled_ = value;
        enabled_watchdog_.Update(millis());
        xSemaphoreGive(mutex_);
      }));
}

bool SkCommandIn::Snapshot(uint32_t now_ms, uint32_t timeout_ms,
                            control_core::RemoteSource* port_out,
                            control_core::RemoteSource* stbd_out) {
  // Null mutex (allocation failed in begin()) reads as "no snapshot
  // available" -- the caller keeps its previous (default: not-live) copy,
  // never crashes in xSemaphoreTake.
  if (mutex_ == nullptr) {
    return false;
  }
  if (xSemaphoreTake(mutex_, 0) != pdTRUE) {
    return false;
  }
  // A tuple is usable only when EVERY one of its independently-retained SK
  // paths is current. That is the whole rule, and it is what stops a fresh
  // heartbeat combining with a retained old direction after a partial
  // reconnect: the stale member fails its own watchdog.
  //
  // There is deliberately no tighter "all three arrived within N ms of each
  // other" test on top. The three listeners update under three separate mutex
  // acquisitions, so a snapshot taken between them legitimately sees one field
  // a refresh newer than the others -- a torn read of a healthy publisher, not
  // an incoherent source. Judging that as not-live dropped the drives to
  // neutral for a tick every time the control task interleaved with the
  // callbacks, which is a failure this code invents rather than detects.
  const bool live = port_watchdog_.IsLive(now_ms, timeout_ms) &&
                    stbd_watchdog_.IsLive(now_ms, timeout_ms) &&
                    enabled_watchdog_.IsLive(now_ms, timeout_ms);
  const uint32_t last_update_ms = now_ms -
      max(now_ms - port_watchdog_.LastUpdateMs(),
          max(now_ms - stbd_watchdog_.LastUpdateMs(),
              now_ms - enabled_watchdog_.LastUpdateMs()));

  port_out->command = port_;
  port_out->enabled = enabled_;
  port_out->live = live;
  port_out->last_update_ms = last_update_ms;

  stbd_out->command = stbd_;
  stbd_out->enabled = enabled_;
  stbd_out->live = live;
  stbd_out->last_update_ms = last_update_ms;

  xSemaphoreGive(mutex_);
  return true;
}
