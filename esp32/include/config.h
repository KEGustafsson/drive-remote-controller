#pragma once

// Drive Remote Controller -- shared pin map and tunable defaults for all
// THREE firmwares built from this project:
//
//   env:tx_shesp32  (src/tx/)  Handheld Controller  -- shift switches -> SK
//   env:rx_shesp32  (src/rx/)  Motion Controller    -- SK/local -> drive servos
//   env:hh_shesp32  (src/hh/)  Heading Hold Controller -- BNO086 -> bow thruster
//
// See CLAUDE.md and MEASUREMENTS.md for where each value came from. All pins,
// SK paths, and tunable defaults live here -- never hard-code a GPIO or path
// string elsewhere.
//
// LAYOUT: values shared by all three units come first, then a per-unit
// section. The three units are separate boards, so a GPIO number reused
// across sections (e.g. 23 is TX's and RX's port-FORWARD switch AND HH's
// BNO086 UART line) is not a conflict -- they are never compiled into the
// same firmware. Names must still be unique within namespace config, which is
// why the control-task period is spelled kRxControlPeriodMs /
// kHhControlPeriodMs rather than a single kControlPeriodMs (the two loops
// legitimately run at different rates).
//
//////////////////////////////////////////////////////////////////////////
// PIN SELECTION RULES for this board (Hat Labs SH-ESP32, ESP32-WROOM-32E).
// Read this before moving any GPIO -- several pins that LOOK free are traps.
//
// TIER A -- clean pins on the free header. Internal pull-up AND pull-down,
// not strapping, no boot-time output, LEDC-capable. Prefer these always:
//     13, 18, 19, 21, 22, 23, 25, 26, 27
//
// TIER B -- clean pins on the board's peripheral headers, free ONLY because
// this project uses neither peripheral (no <Wire.h>, no OneWire anywhere in
// src/, and neither library in platformio.ini). Electrically as good as tier
// A, and they carry GND + 3V3 on the same connector, which is better for a
// marine harness than the raw pin header:
//     4        1-Wire header  (3-pin: GND, 3V3, DQ)   -- full pulls, ADC2, RTC
//     16, 17   I2C header     (4-pin: GND, 3V3, SDA, SCL) -- full pulls
// Spending these costs 1-Wire / I2C on that board and nothing else. N2K is
// untouched on all three units.
//
// TIER C -- on the header, but with a catch. Only with the stated mitigation:
//     36, 39   INPUT ONLY, and NO internal pull of either kind. Fine for
//              external-pull sensing (see kRxPortNeutralPin), never outputs.
//     14       outputs a signal at boot -- see the BNO/servo history below.
//     5, 15    strapping (must be HIGH at boot) and output at boot.
//     12       MTDI strapping: THE BOARD WILL NOT BOOT if this is high at
//              reset. Never put an active-high switch here.
//
// TIER D -- not available. 32/34 are wired to the optoisolated CAN
// transceiver and are NOT brought out to the free header (reclaiming them
// means cutting traces); 0 is the boot button; 1/3 are UART0 and the boot
// log; 6-11 are the SPI flash; 2 is the onboard LED, used below.
//
// WHY 14 AND 15 ARE NOW UNUSED BY EVERY FIRMWARE (2026-08-15 pin review).
// The ESP32 drives a signal on GPIO14 and GPIO15 during boot, before setup()
// runs. That bit us in two different ways and both are now designed out
// rather than mitigated:
//   - HH read the BNO086's UART on 14. The ESP32 drove that pin as an output
//     at boot while the BNO's push-pull TX drove it too -- real driver
//     contention on every reset. Now on 23, so no series resistor is needed.
//   - RX drove its starboard servo from 14, so the servo could twitch on a
//     glitch before attach() ran. Both servos are now on the I2C header
//     (16/17), which also frees 13 as the first spare tier-A pin in the
//     shared TX/RX diagram.
//   - GPIO15 was HH's reserved BNO reset line. It is a strapping pin that
//     must be HIGH at boot, so an active-low reset wired there is a boot
//     hazard the moment anyone actually connects it. Now on 22.
// Do not move anything back onto 14 or 15 without re-reading this block.
//////////////////////////////////////////////////////////////////////////

#include <cstdint>

// Site configuration -- the boat's own addresses -- lives in secrets.h, which is
// gitignored, so this repository describes no particular network. On a machine
// without one this falls back to secrets.example.h's placeholders and still
// builds; the unit then looks for a Signal K server at the template address
// until it is pointed somewhere else through SensESP's web config UI.
//
// The three mains include this same pair before config.h. Both files are
// `#pragma once`, so including them here as well costs nothing and means
// config.h is self-contained rather than relying on its includer's order.
#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.example.h"
#endif

namespace config {

//////////////////////////////////////////////////////////////////////////
// Shared by all three units
//////////////////////////////////////////////////////////////////////////

// Onboard blue LED (GPIO2 on every SH-ESP32) -- each unit's single status
// LED. TX: link indicator (solid/warn-blink/off, see link_indicator.h). RX:
// link-OK (a remote source is live+enabled). HH: FSM state by blink pattern
// (fault = fast blink, holding = solid, otherwise slow blink).
constexpr uint8_t kLedStatusPin = 2;

// The boat's signalk-server-node instance -- compiled-in default for all
// three units. SensESP's web config UI can still override it per device.
//
// Both are SITE configuration, not tunables, so they live in the gitignored
// secrets.h (SECRET_SK_SERVER_ADDRESS / SECRET_SK_SERVER_PORT) rather than
// here -- this file is committed, and a public repository should not carry the
// boat's network. secrets.example.h carries signalk-server's defaults.
constexpr const char* kSkServerAddress = SECRET_SK_SERVER_ADDRESS;
constexpr uint16_t kSkServerPort = SECRET_SK_SERVER_PORT;

// FreeRTOS safe-core task parameters, identical for RX's and HH's control
// tasks (only the period differs -- see kRxControlPeriodMs /
// kHhControlPeriodMs in the per-unit sections). Arduino's loopTask runs at
// priority 1 on core 1 by default, so the priority here must be strictly
// higher for the safe core to always preempt it on schedule.
constexpr uint32_t kControlTaskStackBytes = 4096;
constexpr uint32_t kControlTaskPriority = 2;
constexpr int kControlTaskCore = 1;

//////////////////////////////////////////////////////////////////////////
// Drive Remote Controller -- TX (src/tx/) and RX (src/rx/)
//////////////////////////////////////////////////////////////////////////

// ---- TX shift switches ----
// MEASUREMENTS.md Item 2: spring-return momentary switches, pulled HIGH when
// active (INPUT_PULLDOWN, active-high). Releasing the switch is the only way
// this project commands NEUTRAL -- when neither side is pressed, the drive is
// neutral. There is no latching; FromSwitch() in drive/drive_command.h maps
// this directly.
constexpr uint8_t kTxPortForwardPin = 23;
constexpr uint8_t kTxPortReversePin = 25;
constexpr uint8_t kTxStbdForwardPin = 27;
constexpr uint8_t kTxStbdReversePin = 26;

// MEASUREMENTS.md Item 2, confirmed: TX's "Enable control" kill switch --
// a distinct logical gate from the 9V battery's own physical power ON/OFF
// switch (Item 4, not a GPIO concern). Active-high like the shift switches,
// but -- confirmed with the user -- a LATCHING (maintained) ON/OFF toggle,
// NOT spring-return: its enabled state persists on its own, unlike the
// momentary shift switches which only assert while physically held. The
// debounce applies identically either way (contact-bounce settle only).
constexpr uint8_t kTxEnablePin = 21;

// ---- TX bow-thruster controls (added with remote thruster control) ----
// Two momentary, spring-return contacts (same active-high INPUT_PULLDOWN
// convention and same 30 ms debounce as the shift switches) plus a latching
// MANUAL/HOLD mode toggle. Claude-proposed from docs/hardware/SH-ESP32.md's
// free-GPIO list, user to confirm at wiring time -- MEASUREMENTS.md Item 7.
//
// Why these three: 18/19/22 are tier A (see the pin-selection rules at the
// top) -- free on the header, not strapping pins, not the input-only 34-39
// which have NO internal pull at all and so cannot be used with
// INPUT_PULLDOWN without external resistors.
//
// Note that TX + RX between them now occupy the ENTIRE tier-A block except
// 13, because the one-wiring-diagram rule means a number used on either panel
// is spent on both. TX is therefore full: its only remaining expansion room
// is 13 and the tier-B pins (4/16/17, minus 16/17 which RX's servos took).
//
// The SAME two buttons serve both modes, which is why only two were needed:
//   MANUAL -> press = thrust that way, release = stop (no latch).
//   HOLD   -> tap = trim the held heading 1 deg that way, hold = repeat by
//             10 deg (see kHeadingNudge* below and heading_nudge.h).
constexpr uint8_t kTxThrusterPortPin = 18;
constexpr uint8_t kTxThrusterStbdPin = 19;
constexpr uint8_t kTxThrusterModePin = 22;

// ---- RX local switches, master enable, servo outputs ----
// MEASUREMENTS.md Item 3, confirmed. Local shift switches mirror TX's
// exact GPIO numbers (same active-high, momentary/spring-return
// convention) so both panels are wired identically -- one wiring diagram
// covers both units. Master enable also mirrors TX's enable pin number,
// and like TX's enable is a LATCHING (maintained) ON/OFF toggle (confirmed
// with the user), not spring-return -- so "remote enabled" persists rather
// than needing to be held.
constexpr uint8_t kRxPortForwardPin = 23;
constexpr uint8_t kRxPortReversePin = 25;
constexpr uint8_t kRxStbdForwardPin = 27;
constexpr uint8_t kRxStbdReversePin = 26;
constexpr uint8_t kRxMasterEnablePin = 21;

// Servo outputs, on the board's I2C header (tier B -- see the pin-selection
// rules at the top). PWM via LEDC (ESP32Servo); any GPIO 0-33 can be an LEDC
// output, and these are driven as outputs and never analogRead, so no ADC
// caveat applies.
//
// MOVED OFF 13/14 in the 2026-08-15 pin review, for three reasons:
//   1. GPIO14 outputs a signal at boot, before setup() attaches the servo --
//      a glitch that could land in the 1-2 ms pulse window and twitch the
//      starboard servo. The engage relay being released covers that on the
//      finished installation, but only if its external pull-down is really
//      fitted, and it does NOT cover bench work before the clutch exists.
//   2. The I2C header carries GND on the same 4-pin connector, so each servo
//      lead takes signal + ground off one plug instead of the raw header.
//   3. It frees 13 and 14 on RX, which dissolves the cross-board number
//      reuse (13 was RX's port servo AND HH's thruster PORT line) and leaves
//      13 as the first spare tier-A pin in the shared TX/RX diagram.
// Cost: RX gives up I2C -- no display or I2C sensor later without moving
// these back. It has neither today. 1-Wire (4) and N2K stay free.
//
// COMMISSIONING: check with a meter whether the I2C header carries pull-up
// resistors to 3V3. If it does, that is harmless here -- a push-pull GPIO
// sinks well under a milliamp through a 4k7 -- and it actually makes the line
// idle HIGH rather than floating before attach(), which is a better-defined
// resting state than the raw header gave. Also confirm the board's I2C jumper
// really isolates these pins as the hardware doc implies.
constexpr uint8_t kRxPortServoPin = 16;
constexpr uint8_t kRxStbdServoPin = 17;

// ---- RX gear-neutral arm interlock (SAFETY.md drive invariant 7) ----
// RX may only take command of the drives when BOTH shift levers are proven to
// be sitting in NEUTRAL. Each lever carries a switch or hall sensor that PULLS
// ITS PIN TO GROUND while the lever is in neutral -- active-LOW, the opposite
// convention from every other contact on this board (the shift switches are
// active-high INPUT_PULLDOWN). Two reasons, and the second is the important
// one: it is what an open-collector hall sensor gives you, and it fails in the
// safe direction. A broken wire, an unplugged sensor or a dead sensor reads
// HIGH = "not neutral" = arming refused.
//
// GPIO36 and GPIO39 are the SH-ESP32's last two free header pins, and both are
// INPUT-ONLY with NO internal pull-up or pull-down (board doc "Gotchas") -- so
// INPUT_PULLUP is silently a no-op here and control_task.cpp deliberately
// configures them as plain INPUT.
//
//   *** EACH PIN NEEDS AN EXTERNAL 10 k PULL-UP TO 3V3, fitted in the board's
//   proto area. ***
//
// Without it the pin floats and can read LOW at random -- the one failure
// direction that is NOT safe, because a floating input would grant exactly the
// permission this interlock exists to withhold. SAFETY.md's commissioning list
// carries the unplug-the-sensor check that proves the resistor is really
// there. A 10 k is also the right value on its own merits: stiffer than the
// ESP32's ~45 k internal pull would have been, over a lever-to-panel cable run
// in an engine space.
//
// Why not 18/19, which do have internal pulls: those are TX's thruster
// buttons, and TX and RX share ONE wiring diagram -- the same GPIO number
// meaning two different things on the two panels is precisely the trap the
// kTxThruster*Pin comment above refuses to set.
//
// Why these DID NOT move in the 2026-08-15 pin review, even though the servo
// move freed 13 and 14: an internal pull-up would be a second line of defence
// if the external 10 k were missing or open -- but it would also MASK that
// fault, and the unplug-the-sensor check in SAFETY.md's drive checklist is
// the only way anyone ever finds out. On the pin that grants arm permission,
// a fault you can prove is worth more than one you have quietly padded. (13
// alone would not have been enough anyway: two sensors need two pins.)
//
// ESP32 erratum note: GPIO36/39 can show a brief spurious LOW pulse when the
// SAR ADC / ULP is used. RX uses neither, and these pins are polled every
// 20 ms through the same 30 ms two-sided debounce as every other contact, so a
// sub-microsecond glitch cannot reach the arm gate regardless.
constexpr uint8_t kRxPortNeutralPin = 36;
constexpr uint8_t kRxStbdNeutralPin = 39;
// Pin level that means "this lever IS in neutral". True = LOW means neutral.
constexpr bool kRxNeutralActiveLow = true;

// ---- RX ARM output (the actuator-engage relay) ----
// The SH-ESP32's optoisolated Opto OUT, driving the user's actuator-engage
// relay (servo clutch / servo power). ARMED = asserted = linkage engaged;
// DISARMED = released, which is both the boot state and the fail-safe state.
// Same pin number and same role as HH's thruster ENABLE, on purpose: on every
// board in this project GPIO33 is "the isolated line asserted only while this
// unit is armed."
//
// HARDWARE NOTE: the ESP32 leaves this pin a floating input from reset until
// setup() runs (~hundreds of ms), and holds it there for the whole of a reset
// or a failed boot. Firmware cannot cover that window -- GIVE THE RELAY DRIVER
// ITS OWN PULL-DOWN so the clutch is released, not floating, whenever the
// board is not actively asserting it.
constexpr uint8_t kRxArmOutputPin = 33;
// GPIO level that means ARMED. The opto stage between the GPIO and the relay
// may INVERT (an open-collector optocoupler pulls its output low when its LED
// is lit), so confirm with a meter -- outputs disconnected -- before the relay
// is wired, and flip this if the relay energises while RX reads disarmed.
// Mirrors HH's kOutputActiveHigh.
constexpr bool kRxArmOutputActiveHigh = true;

// Two-sided debounce for all shift switches (both units) and for RX's two
// lever-neutral sensors -- contact-bounce settling only, NOT the dwell/dead-time
// this project deliberately doesn't have (SAFETY.md drive invariant 2). Short
// on purpose: "as soon as possible." The neutral sensors share these rather
// than getting their own knob because they are the same kind of contact and
// neither side of their debounce is safety-timed: the ASSERT side only delays
// permission to arm by 30 ms, and the RELEASE side is not in any safety path at
// all (once armed, a lever leaving neutral is normal shifting, not a disarm).
constexpr uint32_t kSwitchAssertStableMs = 30;
constexpr uint32_t kSwitchReleaseStableMs = 30;

// ---- Signal K path contract (ARCHITECTURE.md §9) ----
constexpr const char* kSkTxPortCommandPath = "control.remoteController.tx.port.command";
constexpr const char* kSkTxStbdCommandPath = "control.remoteController.tx.stbd.command";
constexpr const char* kSkTxEnabledPath = "control.remoteController.tx.enabled";
// These three plugin.* paths are written by the plugin's SERVER-SIDE
// authority (sk-plugin/index.cjs + arbiter.cjs), never by a web-UI instance
// directly: the UIs send per-client intents to the plugin over HTTP, so they
// add no paths here, and the plugin arbitrates a single armed controller
// among them (ARCHITECTURE.md §10). RX subscribes to exactly these three and
// therefore sees one stable source. The plugin also publishes
// control.remoteController.plugin.activeClient (which UI holds the arm
// token) -- informational, not read by RX.
constexpr const char* kSkPluginPortCommandPath = "control.remoteController.plugin.port.command";
constexpr const char* kSkPluginStbdCommandPath = "control.remoteController.plugin.stbd.command";
constexpr const char* kSkPluginEnabledPath = "control.remoteController.plugin.enabled";
constexpr const char* kSkRxPortStatePath = "control.remoteController.rx.port.state";
constexpr const char* kSkRxStbdStatePath = "control.remoteController.rx.stbd.state";
constexpr const char* kSkRxPortSourcePath = "control.remoteController.rx.port.source";
constexpr const char* kSkRxStbdSourcePath = "control.remoteController.rx.stbd.source";
constexpr const char* kSkRxMasterEnablePath = "control.remoteController.rx.masterEnable";
// The arm interlock's four telemetry paths (ARCHITECTURE.md §5.1). rx.armed is
// the load-bearing one: it is the difference between "the master enable switch
// is ON" (rx.masterEnable, the raw switch) and "RX will actually let a remote
// source reach a servo". The other three exist so a refusal is never silent --
// an operator whose presses do nothing can see WHICH lever is holding the
// permission back instead of hunting a fault that isn't there.
constexpr const char* kSkRxArmedPath = "control.remoteController.rx.armed";
constexpr const char* kSkRxPortLeverNeutralPath =
    "control.remoteController.rx.port.leverNeutral";
constexpr const char* kSkRxStbdLeverNeutralPath =
    "control.remoteController.rx.stbd.leverNeutral";
// "" while armed, else the one blocker to fix -- see ArmInhibitName() in
// drive/arm_gate.h for the exact vocabulary.
constexpr const char* kSkRxArmInhibitPath = "control.remoteController.rx.armInhibit";
constexpr const char* kSkRxLinkOkPath = "control.remoteController.rx.linkOk";
// "RX link is up and ready" -- at least one remote source is LIVE (fresh data
// within kSkStalenessTimeoutMs), independent of whether it reports itself
// enabled/armed. This is the pure link-health signal the phone UI's "RX link"
// row shows, deliberately separate from kSkRxLinkOkPath (live+enabled), which
// tracks the RX board's status LED / "a remote is armed and in command."
constexpr const char* kSkRxLinkUpPath = "control.remoteController.rx.linkUp";

// ---- Bow-thruster command contract (ARCHITECTURE.md §6) ----
// TX and the plugin publish these in exactly the same shape as the drive
// commands above, just under a .thruster child, so HH's command-in code is a
// direct analogue of RX's and an operator only has to learn one contract.
//
// .command : "port" | "off" | "stbd"  -- the direction being asked for right
//            now in MANUAL mode. "off" is the resting value; releasing the
//            button is what commands off, exactly as releasing a shift switch
//            commands neutral.
// .mode    : "manual" | "hold"        -- which gate owns the thruster. Anything
//            unrecognised reads as "hold" (never as manual control of a
//            thruster) -- see ThrusterModeFromSkString.
// .trimDeg : commanded heading-hold TRIM, DEGREES, RELATIVE to the heading HH
//            captured when hold engaged (heading_nudge.h / setpoint.h). 0 = no
//            trim, hold the captured heading. Relative, not absolute, so the
//            resting value is a plain 0 and NO out-of-band sentinel is needed
//            to say "not commanding": a station that is not trimming publishes
//            0, and a JSON null coerced to 0.0f by HH's float listener means
//            exactly that safe rest (an absolute target would have read it as a
//            bogus 0-degree/north command -- the reason the old design needed a
//            999 sentinel, now gone). It is still a self-correcting LEVEL over a
//            lossy link (the whole offset, not per-press events). Clamped to
//            +/-control_core::kMaxTrimDeg. Degrees rather than SI radians
//            because this whole custom control.* tree is operator-facing; the
//            navigation.* paths HH publishes stay strict SI radians.
constexpr const char* kSkTxThrusterCommandPath =
    "control.remoteController.tx.thruster.command";
constexpr const char* kSkTxThrusterModePath =
    "control.remoteController.tx.thruster.mode";
constexpr const char* kSkTxThrusterTrimPath =
    "control.remoteController.tx.thruster.trimDeg";
constexpr const char* kSkPluginThrusterCommandPath =
    "control.remoteController.plugin.thruster.command";
constexpr const char* kSkPluginThrusterModePath =
    "control.remoteController.plugin.thruster.mode";
constexpr const char* kSkPluginThrusterTrimPath =
    "control.remoteController.plugin.thruster.trimDeg";

// ---- HH telemetry contract (the thruster unit's half of ARCHITECTURE.md §6) --
// Mirrors RX's rx.* telemetry so the plugin, TX and the phone UI can treat the
// two units identically. hh.linkUp is the one that matters structurally: it is
// what TX's status LED and the plugin's arm gate time the ARRIVAL of, never
// the value (a value HH published freezes at its last reading the instant HH
// loses power -- the same trap SAFETY.md documents for RX).
constexpr const char* kSkHhThrusterStatePath =
    "control.remoteController.hh.thruster.state";
constexpr const char* kSkHhModePath = "control.remoteController.hh.mode";
constexpr const char* kSkHhSourcePath = "control.remoteController.hh.source";
constexpr const char* kSkHhSetpointPath =
    "control.remoteController.hh.setpointDeg";
constexpr const char* kSkHhArmedPath = "control.remoteController.hh.armed";
constexpr const char* kSkHhLinkUpPath = "control.remoteController.hh.linkUp";
constexpr const char* kSkHhReversalPendingPath =
    "control.remoteController.hh.reversalPending";

// Both TX and the plugin (sk-plugin/) publish on value-change plus this periodic
// refresh, so RX's per-source link_watchdog always has something recent to
// check even when a switch hasn't moved. Must stay comfortably under
// kSkStalenessTimeoutMs below.
constexpr uint32_t kSkPeriodicRefreshMs = 250;

// RX's SKValueListener "minimum interval between updates" for the command-
// in subscriptions. Kept short relative to kSkPeriodicRefreshMs
// so an on-change publish from TX/the plugin isn't perceptibly delayed --
// matches ARCHITECTURE.md §5's "as soon as possible."
constexpr int kSkCommandListenDelayMs = 50;

// ---- SK-source staleness timeout ----
// MEASUREMENTS.md Item 5, confirmed: "max 1s." A remote source (TX-via-SK
// or plugin-via-SK) with no accepted update for longer than this is
// treated as not live -> excluded from arbitration (SAFETY.md drive
// invariant 4/5). Comfortably above kSkPeriodicRefreshMs so ordinary WiFi/SK
// jitter between refreshes doesn't false-trip it.
constexpr uint32_t kSkStalenessTimeoutMs = 1000;

// ---- TX-side RX-liveness (SAFETY.md, the TX half) ----
// How long TX will wait, with no rx.linkUp delta ARRIVING, before it treats RX
// as not live and drops its status LED out of the solid "ready" state. Judged
// purely on arrival time (a control_core::LinkWatchdog fed by every rx.linkUp
// delta), NEVER on the value of any rx.* path -- a value RX published freezes
// at its last reading the instant RX loses power, so it can never reveal RX
// vanishing (see drive/link_indicator.h / plugin's rxLiveness.ts).
//
// 1500 ms = six of RX's 250 ms telemetry refreshes. Deliberately looser than
// kSkStalenessTimeoutMs and matched to the plugin's own RX gate
// (sk-plugin/index.cjs' RX_STALE_TIMEOUT_MS / arbiter's rxStaleTimeoutMs = 1500)
// so TX and the plugin judge "RX gone" on the same clock: this signal crosses
// WiFi *and* the SK server, and a false "RX gone" would needlessly warn a live
// operator mid-manoeuvre.
constexpr uint32_t kRxTelemetryStaleMs = 1500;

// Same staleness window applied to HH's telemetry, so TX judges "the thruster
// board is gone" on the same clock it judges RX by.
constexpr uint32_t kHhTelemetryStaleMs = 1500;

// Half-periods of the TX status LED's two blink patterns. kWarn (exactly one
// of the two units answering) blinks at ~2 Hz -- fast enough to be
// unmistakably NOT the solid "ready" light, slow enough to read as a
// deliberate warning. kFault (connected but NEITHER unit answering) blinks at
// ~5 Hz so the two are tellable apart at a glance on one LED. The LED poll
// samples every 50 ms, giving a clean square wave at both half-periods.
constexpr uint32_t kLinkWarnBlinkHalfPeriodMs = 250;
constexpr uint32_t kLinkFaultBlinkHalfPeriodMs = 100;
// kReadyToArm (connected, a commandable unit is live, but TX is not armed yet)
// blinks at ~1 Hz -- a calm "powered and ready, flip the switch" signal, the
// SAME slow rate HH uses for its powered-and-normal blink and RX uses for its
// ready-to-be-armed blink, so one blink vocabulary reads the same on all three
// units. Deliberately slower than the ~2 Hz warn so "ready" never looks like a
// warning.
constexpr uint32_t kLinkReadyBlinkHalfPeriodMs = 500;

// ---- Servo calibration ----
// MEASUREMENTS.md Item 1, confirmed: standard RC pulse-width range,
// live-tunable from the web UI (ConfigItem), not fixed at compile
// time -- these are only the starting defaults. Applied identically to
// port and stbd by default; the web UI exposes each side's three positions
// as independent ConfigItems so they can be trimmed apart if the real
// linkage geometry differs side to side (ARCHITECTURE.md §11).
constexpr uint16_t kServoForwardUsDefault = 1000;
constexpr uint16_t kServoNeutralUsDefault = 1500;
constexpr uint16_t kServoReverseUsDefault = 2000;

// Web-UI ConfigItem paths (flash-persisted PersistingObservableValue,
// SensESP's ConfigItem system) -- one per position, per side, so port/stbd
// can be trimmed apart in the field with no reflash (ARCHITECTURE.md §11).
constexpr const char* kPortForwardConfigPath = "/remoteController/servo/port_forward_us";
constexpr const char* kPortNeutralConfigPath = "/remoteController/servo/port_neutral_us";
constexpr const char* kPortReverseConfigPath = "/remoteController/servo/port_reverse_us";
constexpr const char* kStbdForwardConfigPath = "/remoteController/servo/stbd_forward_us";
constexpr const char* kStbdNeutralConfigPath = "/remoteController/servo/stbd_neutral_us";
constexpr const char* kStbdReverseConfigPath = "/remoteController/servo/stbd_reverse_us";

// ESP32Servo attach() pulse-width bounds. Matches the default calibration
// range above; if the live-tunable calibration is ever set outside
// [1000, 2000] the attach() bounds here need widening too, or the servo
// will clamp to whichever bound it's closer to.
constexpr uint16_t kServoAttachMinUs = 800;
constexpr uint16_t kServoAttachMaxUs = 2200;
// 200 Hz, not the RC-standard 50 Hz: the ESP32Servo fork used here drives the
// [1000, 2000] us pulse range at 200 Hz. Change to 50 if a servo needs it.
constexpr uint16_t kServoPeriodHz = 200;

// ---- RX FreeRTOS control task ----
// Same fixed-period pinned-task pattern as the heading-hold control task
// (CLAUDE.md "Architecture"). 20 ms matches the tick rate TX samples/
// publishes its switches at (ARCHITECTURE.md §5: "as soon as possible") --
// no reason for RX's own local-switch/arbitration loop to run any slower
// than the fastest input source it's reacting to.
constexpr uint32_t kRxControlPeriodMs = 20;

//////////////////////////////////////////////////////////////////////////
// Heading Hold Controller -- HH (src/hh/)
//////////////////////////////////////////////////////////////////////////

// ---- Pin map (Hat Labs SH-ESP32) ----
// HH is a physically separate box with its own wiring diagram, so it may and
// does reuse numbers that mean something else on the shared TX/RX diagram
// (13, 19, 22, 23, 27). That is unavoidable rather than sloppy: every tier-A
// pin HH could take is one TX already uses, and HH's diagram is the only one
// its installer reads.
//
// BNO086 TX -> ESP RX, custom UART2. MOVED OFF 14 in the 2026-08-15 pin
// review: the ESP32 drives GPIO14 as an output during boot while the BNO's
// push-pull TX is also driving it, which is real driver contention on every
// single reset. The alternative was a 330R-1k series resistor; moving to a
// tier-A pin costs nothing and removes the failure instead of limiting it.
constexpr uint8_t kBnoUartRxPin = 23;
// RESERVED, NOT DRIVEN: no firmware code reads or asserts this pin today --
// there is no IMU reset/recovery path (a wedged BNO is only ever observed as
// an RVC timeout -> FAULT). Kept so the wiring diagram and a future recovery
// feature agree on the number.
//
// MOVED OFF 15 in the 2026-08-15 pin review. GPIO15 is a strapping pin that
// must be HIGH at boot, so an ACTIVE-LOW reset line there is a boot hazard
// the moment someone actually wires it -- and hh-wiring.drawio was inviting
// exactly that ("RST <- GPIO15 (optional)"). GPIO22 is tier A and has a real
// internal pull-up. Leave the pin as Hi-Z INPUT (which is what "not driven"
// means here) and the BNO breakout's own pull-up holds RST released; if a
// recovery path is ever written, drive it LOW only deliberately.
constexpr uint8_t kBnoResetPin = 22;   // BNO086 RST (reserved, active low)

// No valid RVC frame within this window -> stale/timed-out. RVC frames
// arrive at 100 Hz (10 ms period) when healthy; this is a bench-testing
// default with margin, not yet tuned against real link behavior -- revisit
// before wiring it to the FAULT transition on real hardware.
constexpr uint32_t kBnoRvcTimeoutMs = 250;

// ENABLE is the board's optoisolated Opto OUT, same pin and same role as RX's
// actuator-engage relay: on every board in this project GPIO33 is "the
// isolated line asserted only while this unit is armed."
//
// HARDWARE NOTE, the same one kRxArmOutputPin carries and for the same
// reason -- it belongs on BOTH copies because this one gates a bow-thruster
// contactor: the ESP32 leaves this pin a floating input from reset until
// setup() runs (~hundreds of ms), and holds it there for the whole of a reset
// or a failed boot. Firmware cannot cover that window -- GIVE THE OUTPUT
// STAGE ITS OWN PULL-DOWN so the thruster is disabled, not floating, whenever
// the board is not actively asserting ENABLE. PORT/STBD (13/27) want the same
// treatment; they are inert while ENABLE is low, but only once something
// holds ENABLE low.
constexpr uint8_t kThrusterEnablePin = 33;  // ENABLE, active-high logic
constexpr uint8_t kThrusterPortPin = 13;    // PORT (left), active-high logic
constexpr uint8_t kThrusterStbdPin = 27;    // STBD (right), active-high logic

constexpr uint8_t kEngagePin = 35;   // isolated opto IN (2.5-18V), input only
// GPIO level that means ENGAGE IS ASSERTED. Every other opto crossing in this
// project carries a polarity flag (kRxArmOutputActiveHigh, kOutputActiveHigh)
// because the stage between the connector and the GPIO is not to be trusted
// sight-unseen; ENGAGE -- the UNCONDITIONAL local arming authority for the
// thruster, SAFETY.md thruster invariant 6 -- was the one that did not have
// one, and the polarity was hardcoded at its digitalRead.
//
// *** THIS DEFAULT IS AN ASSUMPTION, NOT A MEASUREMENT. *** Hat Labs' public
// hardware documentation does not state the opto input's sense, and
// optocoupler input stages very commonly INVERT (phototransistor collector
// with a pull-up to 3V3 gives LED-lit = pin LOW). If this board inverts, the
// default below is fail-DANGEROUS: GPIO35 is input-only with no internal pull
// and therefore no firmware backstop, so an unwired or unpowered ENGAGE would
// read asserted and HH would arm itself into HOLD on an empty connector.
// SAFETY.md's thruster checklist carries the unwired-reads-disarmed check
// that settles this. Meter the pin before trusting either value.
constexpr bool kEngageActiveHigh = true;
// Deadman. MOVED OFF 39 in the 2026-08-15 pin review: 39 is input-only with
// NO internal pull, so an unwired deadman floated and the external pull-down
// was load-bearing -- which is why the read had to be compile-time disabled
// (kDeadmanWired below) rather than simply left on. GPIO19 is tier A and has
// a real internal pull-down, so unwired now reads LOW = not-held = safe. Fit
// the external 10 k anyway for noise immunity over an engine-space cable run;
// the difference is that it is now belt-and-braces rather than the only thing
// standing between a floating pin and a false "deadman held".
constexpr uint8_t kDeadmanPin = 19;

// Deadman input enable. NO PHYSICAL DEADMAN SWITCH IS INSTALLED YET, so this
// stays false and deadman_ok is hardcoded true: SAFETY.md thruster invariant
// 6's deadman half is provided by the ENGAGE input alone (releasing ENGAGE
// still disarms).
//
// The reason this flag had to exist is now GONE. It was a guard against
// GPIO39 floating when unwired; since the 2026-08-15 pin review the deadman
// is on GPIO19, whose internal pull-down makes an unwired read a definite LOW
// = not-held = safe, so leaving the read enabled would no longer trip
// randomly. The flag is kept only because it is still HONEST -- reporting
// "deadman held" from a switch nobody has installed would be a lie the FSM
// acts on. When the switch is wired (MEASUREMENTS.md HH outstanding item):
// fit its external 10 k pull-down, closed/held = HIGH = ok, set this true,
// and bench-verify the release drops ENABLE before trusting it.
constexpr bool kDeadmanWired = false;

// Two-sided ENGAGE debounce (control_core::Debounce): the raw opto level
// must be stably asserted this long before the FSM sees a rising edge, and
// stably released this long before it sees a drop. 50 ms = 5 control ticks
// -- bridges contact bounce, negligible against human reaction time (the
// release side delays disarm by exactly this much; keep it tens of ms).
constexpr uint32_t kEngageAssertStableMs = 50;
constexpr uint32_t kEngageReleaseStableMs = 50;

// ---- Independent output fail-off watchdog ----
// The control task refreshes a heartbeat every tick; a periodic esp_timer
// callback (dispatched from the high-priority esp_timer task on core 0 --
// a separate task, separate core from the control loop) forces
// ENABLE/PORT/STBD off if the heartbeat goes stale. This bounds how long a
// latched direction can persist after the control task stalls/crashes to
// roughly kOutputFailoffTimeoutMs + kOutputFailoffCheckPeriodMs, instead
// of "until reboot." Timeout = 10 control periods: far above any real
// scheduling jitter (measured avg is ~10 ms), far below a dangerous latch.
// NOTE (review follow-up): this is still firmware. A true hardware
// fail-off -- an external enable-heartbeat circuit that drops ENABLE when
// the MCU stops toggling a safety line -- is strongly recommended for the
// physical interface build (ARCHITECTURE.md §12, user-provided); firmware
// cannot protect against its own total lockup with interrupts disabled.
constexpr uint32_t kOutputFailoffTimeoutMs = 100;
constexpr uint32_t kOutputFailoffCheckPeriodMs = 25;

// ---- SK heading-in ----
// Confirmed 2026-07-01: this path is the UM982 dual-antenna true heading,
// not COG/magnetic (see JOURNAL.md). MEASUREMENTS.md HH Item 3 (RTK quality
// gating) is resolved at the source: signalk-um982-plugin's uniheadingAParser
// already publishes this path as JSON null whenever its own solutionStatus/
// positionType check rejects the fix -- SkHeadingIn's HeadingTrueListener
// drops those nulls instead of letting them coerce to a fake 0-degree
// "valid" reading (see sk_heading_in.cpp). HeadingFilter's freshness +
// plausibility gates remain the protection against a stale or implausible
// (but nonetheless quality-passed) fix.
constexpr const char* kSkHeadingTruePath = "navigation.headingTrue";
// Minimum interval between server pushes for this subscription. This is a
// THROTTLE CEILING, not a statement of how fast fixes arrive -- reading it as
// the latter is exactly the mistake that left the heading filter mistuned (see
// kHeadingCorrTauS). MEASUREMENTS.md HH Item 4 is now measured: on 2026-08-20
// the UM982 was found delivering 1 Hz, and was then reconfigured to 10 Hz
// (UNIHEADINGA 0.1 + SAVECONFIG). 50 ms so this throttle sits clear of a 10 Hz
// stream rather than silently decimating it back down to 5 Hz.
constexpr int kSkHeadingListenDelayMs = 50;

// ---- Heading fusion (heading_filter.h, yaw_rate.h) ----
// All three were measured on the water 2026-08-20; see MEASUREMENTS.md HH
// Item 4 and docs/JOURNAL.md for the captures behind them.

// Convergence TIME CONSTANT for the GNSS correction, in seconds. HeadingFilter
// derives the per-fix gain from this and the actual interval between accepted
// fixes, so this value means the same thing whatever rate the receiver runs at
// -- which is the whole point, given the rate has already been wrong once.
constexpr float kHeadingCorrTauS = 2.0f;

// Age of the last ACCEPTED correction beyond which the fused heading stops
// counting as trustworthy (gates arming, coasts a hold). ~20 fixes of margin at
// the measured 10 Hz, and still ~1 s of margin if the receiver ever drops back
// to 1 Hz -- where the previous 1.5 s left only 0.47 s against a measured
// 1.03 s worst-case age.
constexpr float kHeadingFreshS = 2.0f;

// Low-pass time constant on the BNO yaw rate. This is the dominant source of
// fused-heading lag: integrating a rate filtered with time constant tau makes
// the heading lag by exactly tau. At the previous 0.3 s the fused heading was
// measured sitting 0.24-0.30 s behind navigation.headingTrue. Lowered to 0.1 s
// because the measured noise floor (0.034 deg/s rms at rest) is far below what
// 0.3 s was guarding against. Re-measure that floor before raising it.
constexpr float kYawRateLpfTauS = 0.1f;

// ---- Output polarity (MEASUREMENTS.md HH Item 1) ----
// active-high: high = assert, low = disable. Confirmed 2026-07-10 -- the
// user's output control FETs were bench-tested and behave high-active /
// low-off, matching this default. Flip to false only if a future physical
// interface needs active-low.
constexpr bool kOutputActiveHigh = true;

// ---- Direction control / Switcher (ARCHITECTURE.md §7, §6) ----
// Reversal dead time (MEASUREMENTS.md HH Item 2). Measured 2026-07-10 on the
// real bow-thruster control box: ~1.75 s of enforced OFF between commanding
// one direction and the box accepting the OPPOSITE (its built-in anti-
// reversal interlock). Per that item, reversal_dwell = measured + ~50-100 ms
// margin so we never even ASK for a reverse before the remote is ready.
// This dwell applies ONLY to a reversal (port<->stbd); same-direction
// re-pulsing waits only min_off -- matching the observed "no delay when
// pulsing the same way." See control_core::Switcher::CanLeaveOff. The rest
// of SwitchCfg keeps its in-class "typical start" defaults
// (on_thr/off_thr/Td/min_on/min_off/duty) -- see ControlTask::MakeSwitchCfg.
//
// NOTE: this deliberate dwell is specific to the bow thruster. The drive
// remote (TX/RX above) has NO such timing by design -- see SAFETY.md's two
// separate invariant lists; do not carry one project's rule into the other.
//
// THE TWO DWELLS ARE DELIBERATELY ASYMMETRIC (owner decision 2026-07-24).
// Both gates model the same physical control-box interlock, but they are two
// independent knobs because the two modes have different operators:
//
//   HOLD (kReversalDwellS, below) is driven by the bang-bang Switcher -- an
//   automatic loop with nobody watching the tunnel. It waits out the measured
//   interlock in full, and this value must NOT be lowered below the ~1.75 s
//   measurement (enforced by the static_assert below).
//
//   MANUAL (kManualReversalDwellS) is driven by a human holding a button and
//   watching the boat. The owner has set it to 0: the control box enforces its
//   own interlock regardless, so the firmware asking early costs nothing the
//   box does not already refuse, and a firmware-imposed wait made the control
//   feel unresponsive at exactly the moment (docking) it is needed most. The
//   operator, not the firmware, judges when to reverse.
//
// Consequence to be aware of: with MANUAL at 0, `reversalPending` never
// publishes in manual mode and the "reversing -- waiting for the thruster"
// note never appears; the coast the operator sees is the control box's own
// interlock, not ours. Raising this value re-enables both, and (see
// control_step.cpp) the dwell is then honoured across a mode change too.
constexpr float kReversalDwellS = 1.85f;  // 1.75 s measured + 0.10 s margin
// MANUAL-mode reversal dwell (manual_thrust.h). 0 = ask for the reversal as
// soon as the operator does; the control box's own interlock still applies.
// See the block comment above for why this one has no floor and HOLD does.
constexpr float kManualReversalDwellS = 0.00f;  // owner decision: no added dwell

// The HOLD dwell is a MEASURED safety value, not a tuning knob (it is also
// deliberately absent from the web-editable Switcher tunables -- see
// Switcher::SetTunables). Nothing else in the build would catch it being
// edited below the measurement, and `pio test -e native` would not either:
// the test suites construct their own SwitchCfg and never read config::.
static_assert(kReversalDwellS >= 1.75f,
              "kReversalDwellS is below the measured ~1.75 s control-box "
              "anti-reversal interlock (MEASUREMENTS.md HH Item 2): the "
              "automatic HOLD loop would command a contactor into a "
              "still-spinning motor with nobody watching the tunnel.");

// Live, web-UI-tunable, flash-persisted Switcher knobs (ARCHITECTURE.md §11
// sea-trial playbook). Defaults are the control law's "typical start"
// values; they are duplicated here (rather than read from SwitchCfg) because
// ConfigItem needs a named default+path per value. The engage/release pair
// deliberately DIVERGES from SwitchCfg's in-class 3.0/1.0. Both halves were
// moved for a reason found on the water: a 3 deg engage threshold let the
// swing develop too far before the thruster bit, and a 1 deg release ran each
// pulse long enough to overshoot and set up an oscillation. The shipped
// default is therefore a tighter, NARROWER band -- 2.0 deg in, 1.5 deg out --
// which bites earlier and lets go sooner. Note this leans on the timing gates
// rather than the threshold gap for anti-chatter: 0.5 deg of hysteresis alone
// would permit fast toggling, but min_on/min_off bound a full cycle to
// >= 0.8 s regardless. Keep that in mind before shortening either time.
// The pure core keeps its own conservative numbers -- the tests construct
// their own SwitchCfg and are unaffected by these values. reversal_dwell_s and
// duty_window_s are deliberately NOT here: see Switcher::SetTunables in
// heading/switcher.h for why (measured safety value / duty-history
// reinterpretation risk).
constexpr float kOnThrDegDefault = 2.0f;
constexpr float kOffThrDegDefault = 1.5f;
constexpr float kLeadTimeSDefault = 1.0f;
constexpr float kMinOnSDefault = 0.3f;
// Hard ceiling on a single continuous thrust (switcher.h max_on_s). Owner
// value: 2 s. This is not a tuning knob for how the hold FEELS -- the control
// law normally releases long before it, on the lead variable -- it is the
// bound on the case where the boat does not answer at all (pinned by wind or
// current, fouled thruster, dead yaw rate), where nothing else would ever end
// the pulse. Under that sustained load it turns a continuous burn into a
// 2 s on / 0.5 s off pulse train, whose ~80% duty then crosses duty_max and
// hands the thruster to the S2 limiter -- which is the intended outcome.
constexpr float kMaxOnSDefault = 2.0f;
constexpr float kMinOffSDefault = 0.5f;
constexpr float kDutyWarnDefault = 0.5f;
constexpr float kDutyMaxDefault = 0.7f;
constexpr const char* kOnThrConfigPath = "/heading_hold/switcher/on_thr_deg";
constexpr const char* kOffThrConfigPath = "/heading_hold/switcher/off_thr_deg";
constexpr const char* kLeadTimeConfigPath = "/heading_hold/switcher/lead_time_s";
constexpr const char* kMinOnConfigPath = "/heading_hold/switcher/min_on_s";
constexpr const char* kMaxOnConfigPath = "/heading_hold/switcher/max_on_s";
constexpr const char* kMinOffConfigPath = "/heading_hold/switcher/min_off_s";
constexpr const char* kDutyWarnConfigPath = "/heading_hold/switcher/duty_warn";
constexpr const char* kDutyMaxConfigPath = "/heading_hold/switcher/duty_max";

// ---- Remote thruster control (ARCHITECTURE.md §6) ----
// How stale a remote thruster source may get before HH stops honouring it.
// Same value and same reasoning as the drives' kSkStalenessTimeoutMs (both TX
// and the plugin refresh every kSkPeriodicRefreshMs = 250 ms, giving 4x
// headroom), kept as its own name so the thruster's tolerance can be tightened
// independently of the drives' if bench testing ever calls for it.
constexpr uint32_t kThrusterSourceStalenessMs = 1000;

// Ceiling on how fast a commanded trim may drag the setpoint off the captured
// heading. A bow thruster on a displacement hull turns slowly; 10 deg/s is well
// beyond what it can actually deliver, so this is a bound on the COMMAND, not a
// performance target -- its job is to keep one fat-fingered tap (or a corrupt
// delta) from demanding an instant large swing. See setpoint.h. The trim
// MAGNITUDE is separately clamped to control_core::kMaxTrimDeg (setpoint.h).
constexpr float kSetpointSlewDps = 10.0f;

// Heading-trim button feel on the commanding station (heading_nudge.h): a tap
// trims one fine step, holding past the delay repeats coarse steps. The slew
// clamp above still governs how fast the boat follows, so these only affect
// how quickly the OPERATOR can dial the trim offset in.
constexpr float kHeadingNudgeFineStepDeg = 1.0f;
constexpr float kHeadingNudgeCoarseStepDeg = 10.0f;
constexpr uint32_t kHeadingNudgeRepeatDelayMs = 750;
constexpr uint32_t kHeadingNudgeRepeatPeriodMs = 400;

// ---- HH control task ----
// Fixed period for the pinned safe-core task (CLAUDE.md: "50-100 Hz").
// 10 ms (100 Hz) matches the BNO's own RVC frame rate. Stack/priority/core
// are the shared kControlTask* values at the top of this file.
constexpr uint32_t kHhControlPeriodMs = 10;

// ---- HH telemetry / attitude output ----
// ARCHITECTURE.md §9: "downsample to 10-20 Hz, never 100 Hz."
constexpr uint32_t kTelemetryPublishPeriodMs = 1000 / 15;  // ~15 Hz

// MEASUREMENTS.md HH Item 6 (mount tare) is unmeasured -- these are the
// *initial* values for the live-configurable (web UI, persisted to flash)
// roll/pitch tare and yaw-rate sign, not guesses at the real tare. Default
// is identity/no-op (0 deg, not inverted) until measured on the boat;
// nudge them from the web UI once a spirit-level reading is available.
constexpr float kImuRollTareDegDefault = 0.0f;
constexpr float kImuPitchTareDegDefault = 0.0f;
constexpr bool kImuYawRateInvertDefault = false;
constexpr const char* kImuRollTareConfigPath = "/heading_hold/imu/roll_tare_deg";
constexpr const char* kImuPitchTareConfigPath = "/heading_hold/imu/pitch_tare_deg";
constexpr const char* kImuYawRateInvertConfigPath =
    "/heading_hold/imu/yaw_rate_invert";

// ---- HH values that still need a human -- do NOT guess; fill in from
// MEASUREMENTS.md's results sheet before the phase that needs them. ----
// HH Item 2: reversal dead time -- MEASURED, see kReversalDwellS above.
// HH Item 3: SK quality/RTK path for UM982 heading, if published.
// constexpr const char* kHeadingQualityPath = "TODO";
// HH Item 4: UM982 heading update rate / antenna baseline -> filter tau and
// coast budget.
// HH Item 5: thruster S2 rating -> duty_warn / duty_max / window.
// HH Item 6: IMU mount offset/tare -- see kImuRollTareDegDefault etc. above;
// live-tunable via web UI once boat measurements exist.

//////////////////////////////////////////////////////////////////////////
// Compile-time pin-collision guard
//////////////////////////////////////////////////////////////////////////
// No firmware may use one GPIO for two things. Collisions ACROSS the three
// groups below are fine and deliberate -- the units are separate boards, so
// 23 is legitimately a TX/RX shift switch AND HH's BNO UART line. A collision
// WITHIN a group is always a bug, and nothing else in the build would catch
// one: these constants are only ever handed to pinMode()/attach() at runtime,
// where a duplicate silently reconfigures the earlier pin instead of failing.
// Added 2026-08-15 alongside the pin move that reshuffled six of them.
namespace pin_check {

constexpr bool AllDistinct(const uint8_t* pins, int n) {
  for (int i = 0; i < n; ++i) {
    for (int j = i + 1; j < n; ++j) {
      if (pins[i] == pins[j]) return false;
    }
  }
  return true;
}

constexpr uint8_t kTxPins[] = {
    kLedStatusPin,      kTxPortForwardPin,   kTxPortReversePin,
    kTxStbdForwardPin,  kTxStbdReversePin,   kTxEnablePin,
    kTxThrusterPortPin, kTxThrusterStbdPin,  kTxThrusterModePin};

constexpr uint8_t kRxPins[] = {
    kLedStatusPin,     kRxPortForwardPin, kRxPortReversePin,
    kRxStbdForwardPin, kRxStbdReversePin, kRxMasterEnablePin,
    kRxPortServoPin,   kRxStbdServoPin,   kRxPortNeutralPin,
    kRxStbdNeutralPin, kRxArmOutputPin};

// kBnoResetPin is included although no code drives it: a reserved pin is
// still an allocated one, and the whole point of keeping the constant is that
// the wiring diagram and firmware agree on the number.
constexpr uint8_t kHhPins[] = {
    kLedStatusPin,       kBnoUartRxPin,     kBnoResetPin,
    kThrusterEnablePin,  kThrusterPortPin,  kThrusterStbdPin,
    kEngagePin,          kDeadmanPin};

static_assert(AllDistinct(kTxPins, sizeof(kTxPins)),
              "TX assigns one GPIO to two functions -- see the pin-selection "
              "rules at the top of config.h before picking a replacement.");
static_assert(AllDistinct(kRxPins, sizeof(kRxPins)),
              "RX assigns one GPIO to two functions -- see the pin-selection "
              "rules at the top of config.h before picking a replacement.");
static_assert(AllDistinct(kHhPins, sizeof(kHhPins)),
              "HH assigns one GPIO to two functions -- see the pin-selection "
              "rules at the top of config.h before picking a replacement.");

}  // namespace pin_check

}  // namespace config
