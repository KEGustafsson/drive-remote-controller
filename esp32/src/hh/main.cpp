// Heading Hold Controller.
//
// Boots SensESP v3 (WiFi/SK/OTA/web config -- "smart periphery," may jitter,
// never touches outputs or the fast loop) and starts the pinned FreeRTOS
// safe-core task (control_task.{h,cpp}) that owns the BNO UART, ENGAGE
// input, and thruster outputs -- see CLAUDE.md "Architecture."

#include <memory>
#include <vector>

// WiFi/OTA credentials live in include/secrets.h (committed in this private
// repo by explicit owner decision -- see the note in secrets.h itself). On a
// machine without secrets.h this falls back to include/secrets.example.h's
// placeholders -- the firmware still builds, it just won't join WiFi until you
// create secrets.h or configure WiFi via the setup portal.
#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.example.h"
#endif

#include "config.h"
#include "control_task.h"
#include "sensesp.h"
#include "sensesp/sensors/sensor.h"
#include "sensesp/signalk/signalk_output.h"
#include "sensesp/signalk/signalk_types.h"
#include "sensesp_app_builder.h"
#include "sk_heading_in.h"
#include "sk_thruster_in.h"

using namespace sensesp;

namespace {
// Fed by SensESP's WS client on the Arduino loop task; read by ControlTask
// on its own pinned task through its internal mutex.
SkHeadingIn sk_heading_in;
// Remote bow-thruster command sources (ARCHITECTURE.md §6), one per station.
// Same one-way, mutex-protected boundary as sk_heading_in: written by WS
// callbacks on the loop task, read by the control task, never blocking it.
SkThrusterIn tx_thruster_in;
SkThrusterIn plugin_thruster_in;
ControlTask control_task;

// Keeps the shared_ptr SensESP objects created in setup() (sensors, SK
// outputs) alive after setup() returns. Static ownership instead of the
// old `while (true) { loop(); }` at the end of setup(): the framework's
// normal setup-returns-then-loop flow stays intact (task watchdog feeding,
// serial event handling), and object lifetime is explicit rather than a
// side effect of never returning.
std::vector<std::shared_ptr<void>> retained_objects;
}  // namespace

void setup() {
  SetupLogging(ESP_LOG_INFO);

  SensESPAppBuilder builder;
  sensesp_app = (&builder)
                    ->set_hostname("sensesp-heading-hold")
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

  // Single status LED (onboard, GPIO2): one LED conveys the whole state via
  // its blink pattern, read from the control task's telemetry snapshot. Same
  // blink vocabulary and the same link gate as RX and TX, so one pattern reads
  // the same across the boat:
  //   OFF        -> HH's own SK socket is down.
  //                 Dark means "cannot hold": the heading reference arrives
  //                 over Signal K, so with the socket down there is nothing to
  //                 arm on and no station can command the thruster either.
  //                 Matching RX here matters more than showing FAULT while
  //                 offline -- an operator glancing at a dark LED must read it
  //                 the same way on every unit.
  //   FAST BLINK -> FAULT (something is wrong), or the control task's state is
  //                 not readable at all. The second case is deliberately NOT
  //                 dark and NOT the calm slow blink: if the telemetry mutex
  //                 failed to allocate (ControlTask::begin logs it and starts
  //                 the task anyway) HH still runs and can still be armed from
  //                 its own ENGAGE input, so dark would lie about that -- but
  //                 nothing here can confirm the state either, and presenting
  //                 unconfirmable state as "powered & normal" is the failure
  //                 SAFETY.md's honesty rule names. Say "something is wrong."
  //   SOLID      -> HOLDING, actively holding heading
  //   SLOW BLINK -> DISARMED / ARMED_IDLE, powered & normal
  // Driven here on the SensESP loop (status/periphery, not a safety output);
  // latestTelemetry() is non-blocking -- on a busy tick we just reuse the
  // last known state.
  pinMode(config::kLedStatusPin, OUTPUT);
  event_loop()->onRepeat(50, []() {
    static bool have_snapshot = false;
    static control_core::FsmState led_state = control_core::FsmState::kDisarmed;
    ControlTask::TelemetrySnapshot snap;
    if (control_task.latestTelemetry(&snap)) {
      have_snapshot = true;
      led_state = snap.state;
    }
    bool connected = sensesp_app->get_ws_client()->is_connected();
    bool on;
    if (!connected) {
      on = false;
    } else if (!have_snapshot) {
      // State unreadable -- see the FAST BLINK note above.
      on = (millis() / config::kLinkFaultBlinkHalfPeriodMs) % 2 == 0;
    } else {
      switch (led_state) {
        case control_core::FsmState::kFault:
          on = (millis() / config::kLinkFaultBlinkHalfPeriodMs) % 2 == 0;
          break;
        case control_core::FsmState::kHolding:
          on = true;
          break;
        default:
          on = (millis() / config::kLinkReadyBlinkHalfPeriodMs) % 2 == 0;
          break;
      }
    }
    digitalWrite(config::kLedStatusPin, on ? HIGH : LOW);
  });

  // Heartbeat counter so the device is visible in the SK data browser.
  auto heartbeat = std::make_shared<RepeatSensor<int>>(1000, []() {
    static int count = 0;
    return count++;
  });

  auto heartbeat_sk_output = std::make_shared<SKOutputInt>(
      "sensors.headingHold.heartbeat", "/Sensors/Heading Hold/Heartbeat");

  heartbeat->connect_to(heartbeat_sk_output);

  sk_heading_in.begin();

  // Remote thruster command-in (ARCHITECTURE.md §6): one subscriber per
  // station, both reading the SAME enabled/ARM flag that station already
  // publishes for the drives -- one ARM covers both machines, so there is no
  // separate thruster arm to get out of step with the drive arm.
  tx_thruster_in.begin(config::kSkTxThrusterCommandPath,
                       config::kSkTxThrusterModePath,
                       config::kSkTxThrusterTrimPath,
                       config::kSkTxEnabledPath,
                       config::kSkCommandListenDelayMs);
  plugin_thruster_in.begin(config::kSkPluginThrusterCommandPath,
                           config::kSkPluginThrusterModePath,
                           config::kSkPluginThrusterTrimPath,
                           config::kSkPluginEnabledPath,
                           config::kSkCommandListenDelayMs);

  control_task.begin(&sk_heading_in, &tx_thruster_in, &plugin_thruster_in);

  // Attitude/telemetry publish (ARCHITECTURE.md §9): reads the control task's
  // mutex-protected snapshot at 10-20 Hz (never 100 Hz -- would flood
  // SK/WiFi for no benefit). If the mutex is momentarily busy this cycle
  // just skips -- never blocks/paces the control loop, and one stale
  // publish tick is harmless.
  auto attitude_sk_output =
      std::make_shared<SKOutputAttitudeVector>("navigation.attitude", "");
  // Signal K angles are RADIANS and durations are SECONDS (SI). Publish the
  // units as SK metadata too (SKOutputNumeric's 3rd ctor arg) so consumers /
  // the SK server know how to interpret + display these custom paths -- don't
  // bake units into the path names. fusedHeading = rad, rateOfTurn = rad/s,
  // gnssAge = s.
  auto rate_of_turn_sk_output =
      std::make_shared<SKOutputFloat>("navigation.rateOfTurn", "", "rad/s");
  auto fused_heading_sk_output = std::make_shared<SKOutputFloat>(
      "sensors.headingHold.fusedHeading", "", "rad");
  auto fsm_state_sk_output =
      std::make_shared<SKOutputString>("sensors.headingHold.fsmState", "");
  auto bno_ok_sk_output =
      std::make_shared<SKOutputBool>("sensors.headingHold.bnoOk", "");
  auto gnss_valid_sk_output =
      std::make_shared<SKOutputBool>("sensors.headingHold.gnssValid", "");
  auto gnss_age_sk_output =
      std::make_shared<SKOutputFloat>("sensors.headingHold.gnssAge", "", "s");
  // ARCHITECTURE.md §9: e, setpoint, switching command, duty -- the signals tuned
  // against real archipelago wind (MEASUREMENTS.md playbook). The Switcher drives
  // setpoint/error while HOLDING in HOLD mode; everywhere else the setpoint
  // mirrors the fused heading, so error reads a truthful zero (see
  // TelemetrySnapshot comment). switchCmd reads "OFF" outside HOLDING.
  auto setpoint_sk_output = std::make_shared<SKOutputFloat>(
      "sensors.headingHold.setpoint", "", "rad");
  auto error_sk_output =
      std::make_shared<SKOutputFloat>("sensors.headingHold.error", "", "rad");
  auto switch_cmd_sk_output =
      std::make_shared<SKOutputString>("sensors.headingHold.switchCmd", "");
  auto duty_sk_output =
      std::make_shared<SKOutputFloat>("sensors.headingHold.duty", "", "ratio");

  // Remote-control telemetry (ARCHITECTURE.md §6), published under the shared
  // control.remoteController.hh.* tree so the plugin, TX and the phone UI can
  // treat this unit exactly like RX. hh.linkUp is the load-bearing one: TX's
  // status LED and the plugin's arm gate time its ARRIVAL, never its value --
  // a value published by HH freezes at its last reading the instant HH loses
  // power, so only the deltas stopping can reveal that (the same trap
  // SAFETY.md documents for RX). Which means this must keep republishing on
  // every cycle whether or not anything changed -- see the publish loop below.
  auto hh_thruster_state_output =
      std::make_shared<SKOutputString>(config::kSkHhThrusterStatePath, "");
  auto hh_mode_output =
      std::make_shared<SKOutputString>(config::kSkHhModePath, "");
  auto hh_source_output =
      std::make_shared<SKOutputString>(config::kSkHhSourcePath, "");
  auto hh_setpoint_deg_output = std::make_shared<SKOutputFloat>(
      config::kSkHhSetpointPath, "", "deg");
  auto hh_armed_output =
      std::make_shared<SKOutputBool>(config::kSkHhArmedPath, "");
  auto hh_link_up_output =
      std::make_shared<SKOutputBool>(config::kSkHhLinkUpPath, "");
  auto hh_reversal_pending_output =
      std::make_shared<SKOutputBool>(config::kSkHhReversalPendingPath, "");

  event_loop()->onRepeat(config::kTelemetryPublishPeriodMs, [=]() {
    ControlTask::TelemetrySnapshot snap;
    if (!control_task.latestTelemetry(&snap)) return;

    attitude_sk_output->set(
        sensesp::AttitudeVector(snap.roll_rad, snap.pitch_rad, snap.yaw_rad));
    rate_of_turn_sk_output->set(snap.rate_of_turn_rad_s);
    fused_heading_sk_output->set(snap.yaw_rad);  // SK angle: radians
    fsm_state_sk_output->set(ControlTask::StateName(snap.state));
    bno_ok_sk_output->set(snap.bno_ok);
    gnss_valid_sk_output->set(snap.gnss_valid);
    gnss_age_sk_output->set(snap.gnss_age_ms / 1000.0f);
    setpoint_sk_output->set(snap.setpoint_rad);
    error_sk_output->set(snap.error_rad);
    switch_cmd_sk_output->set(ControlTask::CmdName(snap.switch_cmd));
    duty_sk_output->set(snap.duty);

    hh_thruster_state_output->set(ControlTask::CmdName(snap.switch_cmd));
    hh_mode_output->set(ControlTask::ModeName(snap.mode));
    hh_source_output->set(ControlTask::SourceName(snap.source));
    hh_setpoint_deg_output->set(snap.setpoint_deg);
    hh_armed_output->set(snap.armed);
    hh_reversal_pending_output->set(snap.reversal_pending);
    // "This unit is present and a remote station is talking to it." Published
    // unconditionally every cycle -- consumers judge HH's presence by these
    // deltas still ARRIVING, never by reading the value. The value itself is
    // "at least one remote source is live", armed or not (ARCHITECTURE.md
    // §9) -- the same definition as rx.linkUp, so a station's steady disarmed
    // heartbeat reads as "link up" on both units. It is false only when no
    // station is publishing to HH at all, which distinguishes "HH is here but
    // nobody is talking to it" from "HH is here and in touch with a station."
    hh_link_up_output->set(snap.link_up);
  });

  // Hand ownership of everything created above to static storage so it
  // survives setup() returning (the telemetry lambda holds its own copies,
  // but the heartbeat sensor and its output are only reachable here).
  retained_objects = {heartbeat,        heartbeat_sk_output,
                      attitude_sk_output, rate_of_turn_sk_output,
                      fused_heading_sk_output, fsm_state_sk_output,
                      bno_ok_sk_output, gnss_valid_sk_output,
                      gnss_age_sk_output, setpoint_sk_output,
                      error_sk_output,  switch_cmd_sk_output,
                      duty_sk_output,   hh_thruster_state_output,
                      hh_mode_output,   hh_source_output,
                      hh_setpoint_deg_output, hh_armed_output,
                      hh_link_up_output, hh_reversal_pending_output};
}

void loop() { event_loop()->tick(); }
