// Drive Remote Controller -- TX ("remote") firmware.
//
// Reads the 2 shift switches (port, stbd -- each a momentary,
// spring-return 3-position switch per MEASUREMENTS.md Item 2) plus the
// "Enable control" kill switch, debounces them, maps the shift switches to
// control_core::DrivePosition, and publishes to Signal K per
// §5's path contract on change + a periodic refresh (so RX's
// link_watchdog always has something recent to check). Drives the link-OK
// LED from TX's own WiFi/SK connection state. TX has no safety-critical
// local output, so it doesn't need a FreeRTOS task -- see CLAUDE.md
// "Architecture."

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
#include "common/debounce.h"
#include "drive/drive_command.h"
#include "drive/link_indicator.h"
#include "drive/link_watchdog.h"
#include "heading/heading_nudge.h"
#include "heading/thruster_arbitration.h"
#include "sensesp.h"
#include "sensesp/sensors/sensor.h"
#include "sensesp/signalk/signalk_output.h"
#include "sensesp/signalk/signalk_value_listener.h"
#include "sensesp/system/lambda_consumer.h"
#include "sensesp_app_builder.h"

using namespace sensesp;
using control_core::EvaluateLinkIndicator;
using control_core::FromSwitch;
using control_core::DrivePosition;
using control_core::LinkIndicator;
using control_core::LinkIndicatorLedOn;
using control_core::Cmd;
using control_core::HeadingNudge;
using control_core::LinkWatchdog;
using control_core::ThrusterCmdToSkString;
using control_core::ThrusterMode;
using control_core::ThrusterModeName;
using control_core::ToSkString;
using control_core::TrimHoldAllowed;

// Not "using control_core::Debounce" -- SensESP also has a
// sensesp::Debounce transform template, and "using namespace sensesp"
// above makes an unqualified "Debounce" ambiguous between the two.
using ShiftDebounce = control_core::Debounce;

namespace {
// Keeps the shared_ptr SensESP objects created in setup() alive after
// setup() returns, same pattern as heading-hold's main.cpp.
std::vector<std::shared_ptr<void>> retained_objects;

// One Debounce instance per switch contact -- forward/reverse read
// independently, then combined through FromSwitch() (drive_command.h)
// into a single DrivePosition per drive. See config.h for why the
// stable periods are short (contact-bounce settling, not a dwell).
ShiftDebounce port_forward_db(config::kSwitchAssertStableMs,
                               config::kSwitchReleaseStableMs);
ShiftDebounce port_reverse_db(config::kSwitchAssertStableMs,
                               config::kSwitchReleaseStableMs);
ShiftDebounce stbd_forward_db(config::kSwitchAssertStableMs,
                               config::kSwitchReleaseStableMs);
ShiftDebounce stbd_reverse_db(config::kSwitchAssertStableMs,
                               config::kSwitchReleaseStableMs);
ShiftDebounce enable_db(config::kSwitchAssertStableMs,
                         config::kSwitchReleaseStableMs);
// Bow-thruster controls (ARCHITECTURE.md §6). The two direction buttons are
// momentary like the shift switches; the mode switch is latching like the
// enable switch. Same debounce for all three -- contact-bounce settling only.
ShiftDebounce thruster_port_db(config::kSwitchAssertStableMs,
                                config::kSwitchReleaseStableMs);
ShiftDebounce thruster_stbd_db(config::kSwitchAssertStableMs,
                                config::kSwitchReleaseStableMs);
ShiftDebounce thruster_mode_db(config::kSwitchAssertStableMs,
                                config::kSwitchReleaseStableMs);

DrivePosition last_published_port = DrivePosition::kNeutral;
DrivePosition last_published_stbd = DrivePosition::kNeutral;
bool last_published_enabled = false;
Cmd last_published_thruster = Cmd::kOff;
ThrusterMode last_published_mode = ThrusterMode::kHold;
float last_published_trim_deg = 0.0f;
uint32_t last_publish_ms = 0;
bool have_published_once = false;

// TX's current (debounced) enable/kill switch state, refreshed every publish
// tick and read by the status-LED loop to choose "ready to be armed" (slow
// blink) vs the armed liveness indication. Single-threaded reactesp event
// loop, so a plain bool is safe. Defaults false (disarmed) before the first
// tick, which is the safe reading for the LED.
bool tx_armed = false;

// Heading-trim accumulator (heading_nudge.h). In HOLD mode the same two
// thruster buttons trim the held heading instead of thrusting, and TX owns a
// RELATIVE trim offset (0 = no trim) -- publishing a level rather than nudge
// events, so a dropped or repeated message can never desynchronise TX and HH,
// and needing no seed and no sentinel because 0 is the well-defined rest.
HeadingNudge heading_nudge([]() {
  HeadingNudge::Cfg cfg;
  cfg.fine_step_deg = config::kHeadingNudgeFineStepDeg;
  cfg.coarse_step_deg = config::kHeadingNudgeCoarseStepDeg;
  cfg.repeat_delay_ms = config::kHeadingNudgeRepeatDelayMs;
  cfg.repeat_period_ms = config::kHeadingNudgeRepeatPeriodMs;
  return cfg;
}());

// Liveness of the RX unit, as seen from TX. Fed by every rx.linkUp delta that
// ARRIVES (the value is ignored -- only that something arrived matters), so a
// powered-down RX is detected by the deltas stopping, not by any frozen value.
// Same pure watchdog RX uses for its own command sources, and the direct
// analogue of the plugin's RX-arrival gate (SAFETY.md). Drives the status
// LED via link_indicator.h so TX never shows a solid "ready" light for an RX
// that isn't actually there.
LinkWatchdog rx_watchdog;
// The same treatment for the heading-hold unit, now that TX commands it too:
// judged on hh.linkUp deltas ARRIVING, never on their value.
LinkWatchdog hh_watchdog;
}  // namespace

void setup() {
  SetupLogging(ESP_LOG_INFO);

  SensESPAppBuilder builder;
  sensesp_app = (&builder)
                    ->set_hostname("sensesp-remote-tx")
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

  pinMode(config::kTxPortForwardPin, INPUT_PULLDOWN);
  pinMode(config::kTxPortReversePin, INPUT_PULLDOWN);
  pinMode(config::kTxStbdForwardPin, INPUT_PULLDOWN);
  pinMode(config::kTxStbdReversePin, INPUT_PULLDOWN);
  pinMode(config::kTxEnablePin, INPUT_PULLDOWN);
  pinMode(config::kTxThrusterPortPin, INPUT_PULLDOWN);
  pinMode(config::kTxThrusterStbdPin, INPUT_PULLDOWN);
  pinMode(config::kTxThrusterModePin, INPUT_PULLDOWN);

  // RX-liveness in: subscribe to RX's own link-health telemetry (rx.linkUp)
  // purely to time its ARRIVALS. The value is deliberately discarded -- reading
  // it could never detect RX vanishing, because Signal K retains a path's last
  // value forever, so rx.linkUp sits at whatever RX last said long after RX has
  // lost power. Only the deltas stopping reveals that. This is the TX-side
  // analogue of the plugin server watching rx.linkUp arrivals (SAFETY.md).
  auto rx_link_up_listener = std::make_shared<SKValueListener<bool>>(
      config::kSkRxLinkUpPath, config::kSkCommandListenDelayMs);
  rx_link_up_listener->connect_to(
      new LambdaConsumer<bool>([](bool) { rx_watchdog.Update(millis()); }));

  // Same for the heading-hold unit, for the same reason.
  auto hh_link_up_listener = std::make_shared<SKValueListener<bool>>(
      config::kSkHhLinkUpPath, config::kSkCommandListenDelayMs);
  hh_link_up_listener->connect_to(
      new LambdaConsumer<bool>([](bool) { hh_watchdog.Update(millis()); }));

  // Status LED: solid ONLY when TX is connected to the server AND BOTH units
  // it can command are proven live (their linkUp deltas currently arriving);
  // a warning blink when exactly one is answering, a faster fault blink when
  // neither is, off when TX itself is disconnected. This replaces the earlier
  // "solid whenever TX's own socket is up" logic, which showed a confident
  // "ready" light even with RX switched off -- the TX half of the §5.2
  // false-confidence bug the plugin already fixed -- and now covers the
  // thruster unit on the same terms. The decision is pure and host-tested
  // (link_indicator.h / test_link_indicator).
  pinMode(config::kLedStatusPin, OUTPUT);
  event_loop()->onRepeat(50, []() {
    uint32_t now = millis();
    bool connected = sensesp_app->get_ws_client()->is_connected();
    bool rx_live = rx_watchdog.IsLive(now, config::kRxTelemetryStaleMs);
    bool hh_live = hh_watchdog.IsLive(now, config::kHhTelemetryStaleMs);
    // tx_armed is TX's own enable/kill switch, updated every publish tick.
    // Disarmed-but-reachable -> a slow "ready to be armed" blink; armed keeps
    // the per-unit liveness detail (solid / warn / fault).
    LinkIndicator indicator =
        EvaluateLinkIndicator(connected, rx_live, hh_live, tx_armed);
    bool ready_phase_on = (now / config::kLinkReadyBlinkHalfPeriodMs) % 2 == 0;
    bool warn_phase_on = (now / config::kLinkWarnBlinkHalfPeriodMs) % 2 == 0;
    bool fault_phase_on = (now / config::kLinkFaultBlinkHalfPeriodMs) % 2 == 0;
    digitalWrite(config::kLedStatusPin,
                 LinkIndicatorLedOn(indicator, ready_phase_on, warn_phase_on,
                                    fault_phase_on)
                     ? HIGH
                     : LOW);
  });

  auto port_command_output =
      std::make_shared<SKOutputString>(config::kSkTxPortCommandPath, "");
  auto stbd_command_output =
      std::make_shared<SKOutputString>(config::kSkTxStbdCommandPath, "");
  auto enabled_output =
      std::make_shared<SKOutputBool>(config::kSkTxEnabledPath, "");
  auto thruster_command_output =
      std::make_shared<SKOutputString>(config::kSkTxThrusterCommandPath, "");
  auto thruster_mode_output =
      std::make_shared<SKOutputString>(config::kSkTxThrusterModePath, "");
  auto thruster_trim_output = std::make_shared<SKOutputFloat>(
      config::kSkTxThrusterTrimPath, "", "deg");

  // Sample + debounce + publish, fast enough to feel immediate ("as soon
  // as possible" -- ARCHITECTURE.md §5) while still giving the debounce its full
  // stable window (kSwitchAssertStableMs/kSwitchReleaseStableMs).
  event_loop()->onRepeat(20, [=]() {
    uint32_t now = millis();

    bool port_forward = port_forward_db.Update(
        digitalRead(config::kTxPortForwardPin) == HIGH, now);
    bool port_reverse = port_reverse_db.Update(
        digitalRead(config::kTxPortReversePin) == HIGH, now);
    bool stbd_forward = stbd_forward_db.Update(
        digitalRead(config::kTxStbdForwardPin) == HIGH, now);
    bool stbd_reverse = stbd_reverse_db.Update(
        digitalRead(config::kTxStbdReversePin) == HIGH, now);
    bool enabled = enable_db.Update(
        digitalRead(config::kTxEnablePin) == HIGH, now);
    tx_armed = enabled;  // expose to the status-LED loop

    DrivePosition port = FromSwitch(port_forward, port_reverse);
    DrivePosition stbd = FromSwitch(stbd_forward, stbd_reverse);

    // ---- Bow thruster (ARCHITECTURE.md §6) ----
    bool thruster_port = thruster_port_db.Update(
        digitalRead(config::kTxThrusterPortPin) == HIGH, now);
    bool thruster_stbd = thruster_stbd_db.Update(
        digitalRead(config::kTxThrusterStbdPin) == HIGH, now);
    ThrusterMode mode =
        thruster_mode_db.Update(digitalRead(config::kTxThrusterModePin) == HIGH,
                                now)
            ? ThrusterMode::kManual
            : ThrusterMode::kHold;

    // One pair of buttons, two meanings, decided by the mode switch. Both
    // pressed at once resolves to no request in either mode -- the same
    // fail-safe resolution FromSwitch() uses for a shift switch reading both
    // ways at once.
    Cmd thruster_cmd = Cmd::kOff;
    if (mode == ThrusterMode::kManual) {
      if (thruster_port && !thruster_stbd) thruster_cmd = Cmd::kPort;
      if (thruster_stbd && !thruster_port) thruster_cmd = Cmd::kStbd;
    }

    // The trim accumulates ONLY while TX may actually keep one -- in HOLD, with
    // TX's own enable switch on, and with HH answering. Every other case zeroes
    // it. The rule and the reasoning live in control_core::TrimHoldAllowed
    // (ARCHITECTURE.md §6.4, "arm-first, then trim"); the three cases it gates
    // are exactly the three heading_nudge.h names for Invalidate(): leaving
    // HOLD mode, losing authority, and losing the HH unit.
    const bool hh_live = hh_watchdog.IsLive(now, config::kHhTelemetryStaleMs);
    if (TrimHoldAllowed(mode == ThrusterMode::kHold, enabled, hh_live)) {
      heading_nudge.Update(thruster_port, thruster_stbd, now);
    } else {
      heading_nudge.Invalidate();
    }

    // Relative trim offset (0 = no trim). No sentinel: 0 is the well-defined
    // rest, and it is a self-correcting level like the rest of the contract.
    float trim_deg = heading_nudge.trim_deg();

    bool changed = !have_published_once || port != last_published_port ||
                   stbd != last_published_stbd ||
                   enabled != last_published_enabled ||
                   thruster_cmd != last_published_thruster ||
                   mode != last_published_mode ||
                   trim_deg != last_published_trim_deg;
    bool refresh_due =
        (now - last_publish_ms) >= config::kSkPeriodicRefreshMs;

    if (changed || refresh_due) {
      port_command_output->set(ToSkString(port));
      stbd_command_output->set(ToSkString(stbd));
      enabled_output->set(enabled);
      thruster_command_output->set(ThrusterCmdToSkString(thruster_cmd));
      thruster_mode_output->set(ThrusterModeName(mode));
      thruster_trim_output->set(trim_deg);

      last_published_port = port;
      last_published_stbd = stbd;
      last_published_enabled = enabled;
      last_published_thruster = thruster_cmd;
      last_published_mode = mode;
      last_published_trim_deg = trim_deg;
      last_publish_ms = now;
      have_published_once = true;
    }
  });

  // Heartbeat counter so the device is visible in the SK data browser
  // independent of switch activity.
  auto heartbeat = std::make_shared<RepeatSensor<int>>(1000, []() {
    static int count = 0;
    return count++;
  });

  auto heartbeat_sk_output = std::make_shared<SKOutputInt>(
      "sensors.remoteControllerTx.heartbeat",
      "/Sensors/Remote Controller TX/Heartbeat");

  heartbeat->connect_to(heartbeat_sk_output);

  retained_objects = {heartbeat,
                      heartbeat_sk_output,
                      port_command_output,
                      stbd_command_output,
                      enabled_output,
                      thruster_command_output,
                      thruster_mode_output,
                      thruster_trim_output,
                      rx_link_up_listener,
                      hh_link_up_listener};
}

void loop() { event_loop()->tick(); }
