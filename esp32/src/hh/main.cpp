// Heading Hold Controller.
//
// Boots SensESP v3 (WiFi/SK/OTA/web config -- "smart periphery," may jitter,
// never touches outputs or the fast loop) and starts the pinned FreeRTOS
// safe-core task (control_task.{h,cpp}) that owns the BNO UART, ENGAGE
// input, and thruster outputs -- see ARCHITECTURE.md §3.

#include <memory>
#include <vector>

// WiFi/OTA credentials live in include/secrets.h, which is GITIGNORED and
// never committed -- create it from include/secrets.example.h (AGENTS.md). On
// a machine without secrets.h this falls back to the example's placeholders --
// the firmware still builds, it just won't join WiFi until you create
// secrets.h or configure WiFi via the setup portal.
#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.example.h"
#endif

#include "config.h"
#include "control_task.h"
#include "heading/thruster_arbitration.h"  // control_core::ThrusterCmdToSkString
#include "sensesp.h"
#include "sensesp/sensors/sensor.h"
#include "sensesp/signalk/signalk_output.h"
#include "sensesp/signalk/signalk_types.h"
#include "sensesp/ui/status_page_item.h"
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
  // FIRST statement of the whole firmware, ahead of the SensESP builder below:
  // drive ENABLE/PORT/STBD to their inactive levels. The builder mounts a
  // filesystem and brings up WiFi before returning, and until these pins are
  // driven they are floating inputs held safe only by the output stage's own
  // pull-downs (SAFETY.md's thruster checklist -- firmware cannot cover the
  // window from reset, but it can make it as short as code allows).
  // Outputs::begin() repeats this inside control_task.begin(); both are
  // idempotent.
  ControlTask::DriveOutputsSafeEarly();

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

  // Plugin-source link diagnostics on the web status page (/api/info), NOT on
  // Signal K: the telemetry loop below is already two short of SensESP's
  // outbound queue, and this is for whoever is judging the link over WiFi on a
  // unit with no serial console (see SkThrusterIn::Diagnostics). Allocated
  // once and never freed -- the status page registry holds raw pointers.
  static constexpr const char* kLinkGroup = "Thruster link (plugin)";
  auto* future_stamps_item =
      new StatusPageItem<int>("Future-stamped updates", 0, kLinkGroup, 5000);
  auto* stale_transitions_item =
      new StatusPageItem<int>("Live -> stale verdicts", 0, kLinkGroup, 5010);
  auto* last_stale_age_item =
      new StatusPageItem<int>("Age at last stale (ms)", 0, kLinkGroup, 5020);
  auto* contended_item =
      new StatusPageItem<int>("Contended snapshots", 0, kLinkGroup, 5030);
  event_loop()->onRepeat(1000, [=]() {
    const SkThrusterIn::Diagnostics d = plugin_thruster_in.diagnostics();
    future_stamps_item->set(static_cast<int>(d.future_stamps));
    stale_transitions_item->set(static_cast<int>(d.stale_transitions));
    last_stale_age_item->set(static_cast<int>(d.last_stale_age_ms));
    contended_item->set(static_cast<int>(d.contended));
  });

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
      std::make_shared<SKOutputString>(config::kSkHhFsmStatePath, "");
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

  // BUDGET, before adding anything to this loop: SensESP's outbound delta
  // queue is 20 deep and drops the OLDEST entry on overflow. This loop appends
  // 18 deltas per cycle, so it already sits two short of that ceiling -- add a
  // few paths and every cycle starts silently discarding whatever was queued
  // first. hh.linkUp is therefore set LAST, deliberately: it is the survivor,
  // and it is the one path whose ARRIVAL other units time to decide whether HH
  // is alive at all. Add a path here only after deciding what it displaces.
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

    // Lower-case ("off"/"port"/"stbd"), from the same pure helper TX publishes
    // with and HH parses with -- every other string in the SK contract is
    // lower-case, and a consumer switching on this one should not have to know
    // that HH's log spelling leaked into a path value. CmdName's upper-case
    // form stays for the logs and for sensors.headingHold.switchCmd, which is
    // a human-facing tuning signal rather than part of the command contract.
    hh_thruster_state_output->set(
        control_core::ThrusterCmdToSkString(snap.switch_cmd));
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
