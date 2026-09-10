// Drive Remote Controller -- RX ("drive controller") firmware.
//
// Boots SensESP v3 (WiFi/SK/OTA/web config -- "smart periphery," may
// jitter, never touches the servo outputs or the fast loop) and starts the
// pinned FreeRTOS safe-core task (control_task.{h,cpp}) that owns the
// local switches, RX master enable switch, the lever-neutral sensors, the
// actuator-engage (ARM) output and the servo outputs -- see
// CLAUDE.md "Architecture." Also starts the two Signal K command-in
// subscriptions (TX-via-SK, plugin-via-SK -- ARCHITECTURE.md §9 path contract)
// that feed the control task's arbitration.

#include <memory>
#include <vector>

// WiFi/OTA credentials live in include/secrets.h (committed in this private
// repo by explicit owner decision -- see the note in secrets.h itself). On a
// machine without secrets.h this falls back to include/secrets.example.h's
// placeholders -- the firmware still builds, it just won't join WiFi until
// you create secrets.h or configure WiFi via the setup portal.
#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.example.h"
#endif

#include "config.h"
#include "control_task.h"
#include "drive/arm_gate.h"
#include "drive/drive_command.h"
#include "sensesp.h"
#include "sensesp/sensors/sensor.h"
#include "sensesp/signalk/signalk_output.h"
#include "sensesp/system/lambda_consumer.h"
#include "sensesp/system/observablevalue.h"
#include "sensesp/ui/config_item.h"
#include "sensesp_app_builder.h"
#include "sk_command_in.h"

using namespace sensesp;

namespace {
// One live-tunable, flash-persisted ConfigItem per servo position
// per side (MEASUREMENTS.md Item 1: "make it live-tunable, not fixed").
// Small helper so the 6 near-identical declarations in setup() don't
// repeat the ConfigItem wiring boilerplate.
std::shared_ptr<PersistingObservableValue<int>> MakeServoConfigItem(
    int default_us, const char* config_path, const char* title,
    const char* description) {
  auto value = std::make_shared<PersistingObservableValue<int>>(default_us,
                                                                  config_path);
  ConfigItem(value)
      ->set_title(title)
      ->set_description(description)
      ->set_sort_order(300);
  return value;
}

// Range-clamp a web-UI calibration value to the servo's attach() pulse
// bounds BEFORE it is narrowed to uint16_t. This is defense-in-depth for a
// safety actuator: the ESP32Servo library already clamps writeMicroseconds()
// to [attach min, attach max], but a fat-fingered negative or >65535 entry
// from the web UI would otherwise silently wrap in the uint16_t cast first
// (e.g. -1 -> 65535) before that clamp ever saw a sane number. Clamping the
// int here means what the user sees, what gets stored, and what the servo
// receives can never diverge into a wrapped value. Note: this only bounds
// the RANGE -- it deliberately does NOT enforce forward/neutral/reverse
// ordering, since which direction is the larger pulse depends on the real
// linkage geometry (MEASUREMENTS.md Item 1) and neutral need not sit exactly
// between them.
uint16_t ClampServoUs(int us) {
  if (us < config::kServoAttachMinUs) return config::kServoAttachMinUs;
  if (us > config::kServoAttachMaxUs) return config::kServoAttachMaxUs;
  return static_cast<uint16_t>(us);
}
}  // namespace

namespace {
// Keeps the shared_ptr SensESP objects created in setup() alive after
// setup() returns, same pattern as heading-hold's main.cpp.
std::vector<std::shared_ptr<void>> retained_objects;

// Fed by SensESP's WS client on the Arduino loop task; read by ControlTask
// on its own pinned task through each instance's internal mutex (CLAUDE.md
// invariant 7). One per remote source, per ARCHITECTURE.md §9.
SkCommandIn tx_command_in;
SkCommandIn plugin_command_in;
ControlTask control_task;
}  // namespace

void setup() {
  SetupLogging(ESP_LOG_INFO);

  SensESPAppBuilder builder;
  sensesp_app = (&builder)
                    ->set_hostname("sensesp-remote-rx")
                    ->set_sk_server(config::kSkServerAddress,
                                    config::kSkServerPort)
                    ->set_wifi_clients({
                        {SECRET_WIFI_SSID, SECRET_WIFI_PASSWORD},
                        {SECRET_WIFI_SSID_2, SECRET_WIFI_PASSWORD_2},
                        {SECRET_WIFI_SSID_3, SECRET_WIFI_PASSWORD_3},
                    })
                    ->enable_ota(SECRET_OTA_PASSWORD)
                    ->set_wifi_access_point("", "")
                    ->enable_ip_address_sensor()
                    ->enable_uptime_sensor()
                    ->enable_wifi_signal_sensor()
                    ->get_app();

  // Status LED, four states -- the same blink vocabulary as HH and TX so one
  // pattern reads the same across the boat:
  //   OFF        -> not reachable to be armed: TX/plugin cannot command RX
  //                 because RX's own SK socket is down, or no telemetry
  //                 snapshot exists yet. Dark now means "cannot be armed",
  //                 not merely "idle".
  //
  //                 That second condition is load-bearing, not just a boot
  //                 detail: if the control task never started (its telemetry
  //                 mutex or the task itself failed to allocate -- see
  //                 ControlTask::begin) latestTelemetry() returns false
  //                 forever, and the flags below would sit at their
  //                 initialisers and show the calm slow "ready to be armed"
  //                 blink for a unit that can never arm anything. A confident
  //                 light for a path that cannot reach a live actuator is the
  //                 exact failure SAFETY.md's honesty rule names.
  //   FAST BLINK -> arm REFUSED: the master enable switch is ON but the
  //                 neutral interlock is holding the arm back (SAFETY.md drive
  //                 invariant 7). Same ~5 Hz "something is wrong, look at me"
  //                 rate HH uses for FAULT, and shown only once the operator
  //                 has actually asked to arm -- so flipping the switch and
  //                 getting a fast blink is the panel telling you to check the
  //                 levers, not a fault. rx.armInhibit names which one.
  //   SLOW BLINK -> ready to be armed, or armed with nothing commanding.
  //   SOLID      -> armed AND a remote source is live+enabled, i.e. a remote
  //                 station is actually in command through RX.
  // All three flags are held across a contended telemetry read (static), the
  // same flicker-free pattern HH's LED uses. Local switches still drive the
  // servos regardless of this LED -- it reports remote-arm readiness, not
  // local override, which is directly visible anyway.
  pinMode(config::kLedStatusPin, OUTPUT);
  event_loop()->onRepeat(50, []() {
    static bool have_snapshot = false;
    static bool link_ok = false;
    static bool armed = false;
    static bool arm_refused = false;
    ControlTask::TelemetrySnapshot snap;
    if (control_task.latestTelemetry(&snap)) {
      have_snapshot = true;
      link_ok = snap.link_ok;
      armed = snap.armed;
      arm_refused = snap.rx_master_enable && !snap.armed;
    }
    bool connected = sensesp_app->get_ws_client()->is_connected();
    bool on;
    if (!connected || !have_snapshot) {
      on = false;  // cannot be armed remotely, or state unknown -> off
    } else if (arm_refused) {
      on = (millis() / config::kLinkFaultBlinkHalfPeriodMs) % 2 == 0;  // refused
    } else if (armed && link_ok) {
      on = true;  // a remote station is in command -> solid
    } else {
      on = (millis() / config::kLinkReadyBlinkHalfPeriodMs) % 2 == 0;  // ready
    }
    digitalWrite(config::kLedStatusPin, on ? HIGH : LOW);
  });

  // Heartbeat counter so the device is visible in the SK data browser
  // before any real command/telemetry data exists.
  auto heartbeat = std::make_shared<RepeatSensor<int>>(1000, []() {
    static int count = 0;
    return count++;
  });

  auto heartbeat_sk_output = std::make_shared<SKOutputInt>(
      "sensors.remoteControllerRx.heartbeat",
      "/Sensors/Remote Controller RX/Heartbeat");

  heartbeat->connect_to(heartbeat_sk_output);

  tx_command_in.begin(config::kSkTxPortCommandPath,
                       config::kSkTxStbdCommandPath, config::kSkTxEnabledPath,
                       config::kSkCommandListenDelayMs);
  plugin_command_in.begin(config::kSkPluginPortCommandPath,
                           config::kSkPluginStbdCommandPath,
                           config::kSkPluginEnabledPath,
                           config::kSkCommandListenDelayMs);

  control_task.begin(&tx_command_in, &plugin_command_in);

  // Live-tunable servo calibration (MEASUREMENTS.md Item 1).
  // Each of the 3-per-side ConfigItems reads ALL THREE of its side's
  // current values (not just the one that just changed) and pushes a
  // complete ServoCalibration into the control task -- so it's correct
  // regardless of which one the user edits, or the order they load from
  // flash in.
  auto port_forward =
      MakeServoConfigItem(config::kServoForwardUsDefault,
                           config::kPortForwardConfigPath,
                           "Port Forward Position (us)",
                           "Servo pulse width for port drive FORWARD.");
  auto port_neutral =
      MakeServoConfigItem(config::kServoNeutralUsDefault,
                           config::kPortNeutralConfigPath,
                           "Port Neutral Position (us)",
                           "Servo pulse width for port drive NEUTRAL.");
  auto port_reverse =
      MakeServoConfigItem(config::kServoReverseUsDefault,
                           config::kPortReverseConfigPath,
                           "Port Reverse Position (us)",
                           "Servo pulse width for port drive REVERSE.");
  auto stbd_forward = MakeServoConfigItem(
      config::kServoForwardUsDefault, config::kStbdForwardConfigPath,
      "Starboard Forward Position (us)",
      "Servo pulse width for starboard drive FORWARD.");
  auto stbd_neutral = MakeServoConfigItem(
      config::kServoNeutralUsDefault, config::kStbdNeutralConfigPath,
      "Starboard Neutral Position (us)",
      "Servo pulse width for starboard drive NEUTRAL.");
  auto stbd_reverse = MakeServoConfigItem(
      config::kServoReverseUsDefault, config::kStbdReverseConfigPath,
      "Starboard Reverse Position (us)",
      "Servo pulse width for starboard drive REVERSE.");

  auto push_port_cal = [=]() {
    control_task.SetPortCalibration({ClampServoUs(port_forward->get()),
                                     ClampServoUs(port_neutral->get()),
                                     ClampServoUs(port_reverse->get())});
  };
  auto push_stbd_cal = [=]() {
    control_task.SetStbdCalibration({ClampServoUs(stbd_forward->get()),
                                     ClampServoUs(stbd_neutral->get()),
                                     ClampServoUs(stbd_reverse->get())});
  };
  port_forward->connect_to(new LambdaConsumer<int>([=](int) { push_port_cal(); }));
  port_neutral->connect_to(new LambdaConsumer<int>([=](int) { push_port_cal(); }));
  port_reverse->connect_to(new LambdaConsumer<int>([=](int) { push_port_cal(); }));
  stbd_forward->connect_to(new LambdaConsumer<int>([=](int) { push_stbd_cal(); }));
  stbd_neutral->connect_to(new LambdaConsumer<int>([=](int) { push_stbd_cal(); }));
  stbd_reverse->connect_to(new LambdaConsumer<int>([=](int) { push_stbd_cal(); }));
  // Also push once immediately with whatever was loaded from flash (or the
  // compiled defaults on first boot), rather than waiting for the
  // PersistingObservableValues' deferred onDelay(0, ...) emit.
  push_port_cal();
  push_stbd_cal();

  // Telemetry (ARCHITECTURE.md §9): reads the control task's mutex-protected
  // snapshot non-blockingly. A momentary miss just skips this cycle's
  // publish -- never blocks/paces the control loop.
  auto port_state_output =
      std::make_shared<SKOutputString>(config::kSkRxPortStatePath, "");
  auto stbd_state_output =
      std::make_shared<SKOutputString>(config::kSkRxStbdStatePath, "");
  auto port_source_output =
      std::make_shared<SKOutputString>(config::kSkRxPortSourcePath, "");
  auto stbd_source_output =
      std::make_shared<SKOutputString>(config::kSkRxStbdSourcePath, "");
  auto master_enable_output =
      std::make_shared<SKOutputBool>(config::kSkRxMasterEnablePath, "");
  // The arm interlock (ARCHITECTURE.md §5.1). rx.armed is what a station
  // should believe, not rx.masterEnable: the switch can be ON while the
  // neutral interlock refuses. The other three make a refusal legible instead
  // of leaving an operator's presses silently doing nothing.
  auto armed_output = std::make_shared<SKOutputBool>(config::kSkRxArmedPath, "");
  auto port_lever_neutral_output =
      std::make_shared<SKOutputBool>(config::kSkRxPortLeverNeutralPath, "");
  auto stbd_lever_neutral_output =
      std::make_shared<SKOutputBool>(config::kSkRxStbdLeverNeutralPath, "");
  auto arm_inhibit_output =
      std::make_shared<SKOutputString>(config::kSkRxArmInhibitPath, "");
  auto link_ok_output =
      std::make_shared<SKOutputBool>(config::kSkRxLinkOkPath, "");
  // Separate from link_ok/the LED: "RX link up and ready" = any remote source
  // is live, armed or not (see control_task's link_up). Feeds the phone UI's
  // "RX link" row.
  auto link_up_output =
      std::make_shared<SKOutputBool>(config::kSkRxLinkUpPath, "");

  event_loop()->onRepeat(config::kSkPeriodicRefreshMs, [=]() {
    ControlTask::TelemetrySnapshot snap;
    if (!control_task.latestTelemetry(&snap)) return;

    port_state_output->set(control_core::ToSkString(snap.port_command));
    stbd_state_output->set(control_core::ToSkString(snap.stbd_command));
    port_source_output->set(ControlTask::SourceName(snap.port_source));
    stbd_source_output->set(ControlTask::SourceName(snap.stbd_source));
    master_enable_output->set(snap.rx_master_enable);
    armed_output->set(snap.armed);
    port_lever_neutral_output->set(snap.port_lever_neutral);
    stbd_lever_neutral_output->set(snap.stbd_lever_neutral);
    arm_inhibit_output->set(control_core::ArmInhibitName(snap.arm_inhibit));
    link_ok_output->set(snap.link_ok);
    link_up_output->set(snap.link_up);
  });

  retained_objects = {heartbeat,
                      heartbeat_sk_output,
                      port_forward,
                      port_neutral,
                      port_reverse,
                      stbd_forward,
                      stbd_neutral,
                      stbd_reverse,
                      port_state_output,
                      stbd_state_output,
                      port_source_output,
                      stbd_source_output,
                      master_enable_output,
                      armed_output,
                      port_lever_neutral_output,
                      stbd_lever_neutral_output,
                      arm_inhibit_output,
                      link_ok_output,
                      link_up_output};
}

void loop() { event_loop()->tick(); }
