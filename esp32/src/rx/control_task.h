#pragma once

// RX safe core (ARCHITECTURE.md, CLAUDE.md "Architecture"): a FreeRTOS task
// pinned to core 1, higher priority than the Arduino loop task, fixed
// period. Owns the local switches, the RX master enable switch, the two
// lever-neutral sensors, the actuator-engage (ARM) output and the two servo
// outputs. Every tick: read the switches and neutral sensors (all debounced)
// -> control_core::ArmGate (one shared decision) -> write the ARM output, then
// independently for port and starboard:
//   read cached SK command-in snapshots -> control_core::Arbitrate()
//   -> control_core::ServoPulseUs() -> servo.
// Never blocks on WiFi/SK -- SkCommandIn::Snapshot() is non-blocking and a
// momentary miss just reuses the previous tick's cached RemoteSource.
//
// One-way boundary (SAFETY.md drive invariant 6, never block): the SensESP loop
// telemetry/config, and the link-OK LED) reads a mutex-protected
// TelemetrySnapshot this task writes every tick; it never waits on it.

#include <cstdint>

#include "drive/arbitration.h"
#include "drive/arm_gate.h"
#include "config.h"
#include "common/debounce.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "drive/drive_command.h"
#include "drive/output_map.h"
#include "sk_command_in.h"

// Forward-declared to avoid pulling ESP32Servo.h (and Arduino.h through
// it) into this header -- only control_task.cpp needs the concrete type.
class Servo;

class ControlTask {
 public:
  struct TelemetrySnapshot {
    control_core::DrivePosition port_command =
        control_core::DrivePosition::kNeutral;
    control_core::DrivePosition stbd_command =
        control_core::DrivePosition::kNeutral;
    control_core::ActiveSource port_source = control_core::ActiveSource::kNone;
    control_core::ActiveSource stbd_source = control_core::ActiveSource::kNone;
    // The RAW master enable switch. Necessary for arming but not sufficient --
    // read `armed` below for what actually reaches the servos and the relay.
    bool rx_master_enable = false;
    // ArmGate's verdict: RX is permitting remote command AND the
    // actuator-engage relay is energised. This is the bool that gated
    // Arbitrate() this tick.
    bool armed = false;
    // Debounced lever-neutral sensors, published so a refusal to arm names the
    // lever that is holding it back instead of looking like a fault.
    bool port_lever_neutral = false;
    bool stbd_lever_neutral = false;
    control_core::ArmInhibit arm_inhibit = control_core::ArmInhibit::kMasterEnableOff;
    // "At least one remote source is currently live+enabled" -- see
    // §3's RX link-OK LED definition. Independent of whether a LOCAL
    // switch happens to be overriding either drive right now. Drives the
    // RX board's status LED (main.cpp) and the published rx.linkOk path.
    bool link_ok = false;
    // "At least one remote source is currently LIVE" (fresh data), regardless
    // of whether it reports itself enabled/armed. This is the pure
    // link-health signal published to rx.linkUp for the phone UI's "RX link"
    // row; it does NOT drive the LED. link_ok implies link_up, never the
    // reverse.
    bool link_up = false;
  };

  // sk_tx/sk_plugin: the two SkCommandIn instances owned by
  // main.cpp: Snapshot() -- see SAFETY.md drive invariant 6's mutex boundary --
  // must outlive this task. Creates and starts the pinned FreeRTOS task;
  // call once from setup(), after both SkCommandIn::begin() calls.
  void begin(SkCommandIn* sk_tx, SkCommandIn* sk_plugin);

  // Non-blocking, same convention as SkCommandIn::Snapshot(): returns
  // false (leaving *out untouched) only on the vanishingly rare contended
  // case.
  bool latestTelemetry(TelemetrySnapshot* out) const;

  // For logging/telemetry. DrivePosition already has
  // control_core::ToSkString() (drive_command.h) -- no need to duplicate
  // that here, only ActiveSource lacks a string form.
  static const char* SourceName(control_core::ActiveSource source);

  // Live-tunable calibration hooks for the web-UI ConfigItem callbacks.
  // Defaults to config::kServo*UsDefault until then (see control_task.cpp).
  void SetPortCalibration(const control_core::ServoCalibration& cal);
  void SetStbdCalibration(const control_core::ServoCalibration& cal);

 private:
  static void TaskEntry(void* pv);
  [[noreturn]] void Run();

  SkCommandIn* sk_tx_ = nullptr;
  SkCommandIn* sk_plugin_ = nullptr;

  control_core::Debounce local_port_forward_db_{config::kSwitchAssertStableMs,
                                                  config::kSwitchReleaseStableMs};
  control_core::Debounce local_port_reverse_db_{config::kSwitchAssertStableMs,
                                                  config::kSwitchReleaseStableMs};
  control_core::Debounce local_stbd_forward_db_{config::kSwitchAssertStableMs,
                                                  config::kSwitchReleaseStableMs};
  control_core::Debounce local_stbd_reverse_db_{config::kSwitchAssertStableMs,
                                                  config::kSwitchReleaseStableMs};
  control_core::Debounce master_enable_db_{config::kSwitchAssertStableMs,
                                             config::kSwitchReleaseStableMs};
  // Both start false = "not in neutral" = arming refused, so a boot with the
  // sensor lines still settling (or missing their external pull-ups entirely)
  // can never arm on the first tick.
  control_core::Debounce port_neutral_db_{config::kSwitchAssertStableMs,
                                            config::kSwitchReleaseStableMs};
  control_core::Debounce stbd_neutral_db_{config::kSwitchAssertStableMs,
                                            config::kSwitchReleaseStableMs};

  control_core::ArmGate arm_gate_;

  Servo* port_servo_ = nullptr;
  Servo* stbd_servo_ = nullptr;

  // Written by ConfigItem callbacks on the Arduino task and read by the
  // control task. A critical section makes each three-value calibration an
  // indivisible snapshot: mixing neutral from one edit with forward/reverse
  // from another can move an actuator unexpectedly.
  mutable portMUX_TYPE calibration_mux_ = portMUX_INITIALIZER_UNLOCKED;
  control_core::ServoCalibration port_cal_{config::kServoForwardUsDefault,
                                            config::kServoNeutralUsDefault,
                                            config::kServoReverseUsDefault};
  control_core::ServoCalibration stbd_cal_{config::kServoForwardUsDefault,
                                            config::kServoNeutralUsDefault,
                                            config::kServoReverseUsDefault};

  // Last-known-good remote-source snapshots, reused on the rare tick where
  // SkCommandIn::Snapshot()'s mutex is momentarily contended -- never left
  // default-constructed mid-run, which would read as "never updated" and
  // could momentarily look like a fresh stale source instead of just a
  // skipped read.
  control_core::RemoteSource last_tx_port_;
  control_core::RemoteSource last_tx_stbd_;
  control_core::RemoteSource last_plugin_port_;
  control_core::RemoteSource last_plugin_stbd_;

  TaskHandle_t task_handle_ = nullptr;
  SemaphoreHandle_t telemetry_mutex_ = nullptr;
  TelemetrySnapshot telemetry_{};
};
