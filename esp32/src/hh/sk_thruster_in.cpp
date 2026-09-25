#include "sk_thruster_in.h"

#include <Arduino.h>

#include "common/elapsed_ms.h"
#include "heading/setpoint.h"  // control_core::ClampTrimDeg
#include "sensesp/system/lambda_consumer.h"
#include "strict_sk_listeners.h"

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

  // StrictFloatListener, not the stock SKValueListener<float>: ArduinoJson's
  // as<float>() reads a JSON `true` as 1.0f, which ClampTrimDeg would keep as a
  // one-degree trim and the watchdog below would count as a live command. A
  // wrong-typed delta is dropped instead, exactly as for `enabled` further
  // down. See include/strict_sk_listeners.h.
  trim_listener_ = std::make_shared<StrictFloatListener>(
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

  // StrictBoolListener, not the stock SKValueListener<bool>: ArduinoJson's
  // as<bool>() reads any string/object/array as true, so `"false"` or `{}` on
  // this path would read as a station reporting itself ARMED -- the unsafe
  // direction, for the flag that decides whether a remote may command the
  // thruster at all. A wrong-typed delta is dropped, which withholds the
  // watchdog feed below too, so the source goes stale and the FSM disarms.
  // See include/strict_sk_listeners.h.
  enabled_listener_ = std::make_shared<StrictBoolListener>(
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

bool SkThrusterIn::Snapshot(uint32_t now_ms,
                            const control_core::ThrusterStaleness& staleness,
                            control_core::ThrusterRemote* out) {
  if (mutex_ == nullptr) return false;
  if (xSemaphoreTake(mutex_, 0) != pdTRUE) {
    contended_.fetch_add(1, std::memory_order_relaxed);
    return false;
  }

  // The window this source is judged on depends on the mode it is publishing,
  // and `mode_` is read here under the same acquisition as the watchdogs below
  // -- the tuple and the rule applied to it are the same coherent read. A mode
  // delta that arrives between two ticks therefore tightens or loosens the
  // window on the very next one, never a tick late, and a stale kManual value
  // (the listener stopped updating it) keeps the SHORTER window rather than
  // inheriting HOLD's.
  const uint32_t timeout_ms =
      control_core::ThrusterStalenessMsFor(mode_, staleness);

  // Every member of the tuple must be current in its own right -- see
  // SkCommandIn::Snapshot for why that is the entire rule and why there is no
  // additional "arrived close together" window. The four listeners update
  // under four separate mutex acquisitions, so a snapshot landing between them
  // sees a torn read of a healthy publisher; treating that as a dead source
  // dropped HOLD for a tick and made the next complete tuple look like a fresh
  // engage, silently recapturing the heading and abandoning the setpoint.
  //
  // A callback that completed after this tick read its clock has stamped an
  // update LATER than now_ms. That is fresh evidence, and every age below is
  // taken with ElapsedMs so it reads as such (common/elapsed_ms.h) -- plain
  // subtraction called it ~49 days old and ended engaged holds.
  using control_core::ElapsedMs;
  using control_core::IsFutureTimestamp;
  if ((command_watchdog_.HasEverUpdated() &&
       IsFutureTimestamp(now_ms, command_watchdog_.LastUpdateMs())) ||
      (mode_watchdog_.HasEverUpdated() &&
       IsFutureTimestamp(now_ms, mode_watchdog_.LastUpdateMs())) ||
      (trim_watchdog_.HasEverUpdated() &&
       IsFutureTimestamp(now_ms, trim_watchdog_.LastUpdateMs())) ||
      (enabled_watchdog_.HasEverUpdated() &&
       IsFutureTimestamp(now_ms, enabled_watchdog_.LastUpdateMs()))) {
    future_stamps_.fetch_add(1, std::memory_order_relaxed);
  }

  // AllLive, not a chain of `&&`: every member must be polled every tick so a
  // stale one latches (control_core::AllLive).
  out->live = control_core::AllLive(now_ms, timeout_ms, command_watchdog_,
                                    mode_watchdog_, trim_watchdog_,
                                    enabled_watchdog_);
  const uint32_t command_age =
      ElapsedMs(now_ms, command_watchdog_.LastUpdateMs());
  const uint32_t mode_age = ElapsedMs(now_ms, mode_watchdog_.LastUpdateMs());
  const uint32_t trim_age = ElapsedMs(now_ms, trim_watchdog_.LastUpdateMs());
  const uint32_t enabled_age =
      ElapsedMs(now_ms, enabled_watchdog_.LastUpdateMs());
  const uint32_t oldest =
      max(max(command_age, mode_age), max(trim_age, enabled_age));
  if (out->live) {
    out->last_update_ms = now_ms - oldest;
  } else if (was_live_) {
    stale_transitions_.fetch_add(1, std::memory_order_relaxed);
    last_stale_age_ms_.store(oldest, std::memory_order_relaxed);
  }
  was_live_ = out->live;
  out->enabled = enabled_;
  out->mode = mode_;
  out->manual_cmd = command_;
  out->trim_deg = trim_deg_;

  xSemaphoreGive(mutex_);
  return true;
}

SkThrusterIn::Diagnostics SkThrusterIn::diagnostics() const {
  return {future_stamps_.load(std::memory_order_relaxed),
          stale_transitions_.load(std::memory_order_relaxed),
          last_stale_age_ms_.load(std::memory_order_relaxed),
          contended_.load(std::memory_order_relaxed)};
}
