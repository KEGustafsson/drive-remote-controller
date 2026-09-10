#pragma once

// Pure C++17 decision for TX's status LED -- the firmware/hardware analogue of
// the plugin's pure src/pure/rxLiveness.ts. No Arduino.h, host-testable under
// env:native.
//
// WHAT IT ANSWERS: "can a press on TX actually reach a live unit?" -- NOT the
// weaker "can TX reach the Signal K server?". Those two differ exactly when a
// unit is switched off, has crashed, or is out of WiFi range: TX stays happily
// connected to the server while nothing it commands can move. An operator must
// never be shown a confident READY light for a path that cannot reach a live
// unit (SAFETY.md).
//
// Liveness is therefore judged on each unit's telemetry ARRIVING (a
// control_core::LinkWatchdog fed by every rx.linkUp / hh.linkUp delta), never
// on the VALUE of any rx.*/hh.* path: Signal K retains a path's last value
// indefinitely, so a value a unit published stands at its last reading forever
// once that unit loses power. Only the deltas stopping can reveal it.

#include <cstdint>

namespace control_core {

// TX commands two independent machines -- RX (the drives) and HH (the bow
// thruster) -- so one LED has to answer "can what I press actually reach
// something?" for both. The states are ordered by decreasing confidence, and
// the rule is: never show the confident light unless every path TX can command
// is verified live. This is the LED analogue of the plugin greying out its
// derived lamps instead of presenting last-known readings as live.
enum class LinkIndicator {
  // TX's own SK socket is down: TX cannot reach the server at all, so it has
  // no basis to claim anything about either unit, and cannot be armed. LED off.
  kOff,
  // Socket up, but NEITHER unit is answering -- no rx.linkUp or hh.linkUp
  // deltas are arriving. Nothing TX presses can move anything, so it cannot be
  // usefully armed. A fast blink, visibly the worst state.
  kFault,
  // Socket up, at least one commandable unit is live, but TX is NOT ARMED yet
  // (its enable switch is off). Ready to be armed -- a calm slow blink, the
  // same "powered and ready" signal HH and RX use. This is the state that says
  // "flip the enable switch and go", and it never appears unless something is
  // actually reachable (so it is never a false "ready" for units that are off).
  kReadyToArm,
  // ARMED, socket up, and exactly ONE of the two units is live: e.g. the drives
  // answer but the thruster board is switched off. Half of what you are
  // commanding is unreachable, and the operator must be told. A ~2 Hz warning
  // blink. (Only meaningful while armed -- while disarmed a single live unit is
  // simply "ready to be armed".)
  kWarn,
  // ARMED, socket up, AND both units' telemetry is currently arriving:
  // everything TX is commanding can actually be reached. The only solid state.
  kReady,
};

// socket_connected: TX's own WS-client connection state
//   (sensesp_app->get_ws_client()->is_connected()).
// rx_live / hh_live: control_core::LinkWatchdog::IsLive() over each unit's
//   telemetry ARRIVAL times (never the value of any rx.*/hh.* path -- see the
//   header note; a value a unit published freezes at its last reading the
//   instant that unit loses power).
// armed: TX's own enable/kill switch is on (tx.enabled) -- TX is actively
//   authorising commands, not merely powered and ready.
//
// Socket-down dominates: if TX can't reach the server, both liveness verdicts
// are necessarily stale/last-known anyway, so reporting a unit fault there
// would blame the units for TX's own disconnection (same reasoning as
// rxLiveness.ts returning 'offline' before it looks at telemetry age).
//
// The false-confidence rule is preserved: a solid or slow-"ready" light NEVER
// appears unless a commandable unit is actually live -- with nothing reachable
// the state is always kFault, armed or not. The arm dimension only chooses
// between "ready to be armed" (disarmed, slow blink) and the armed liveness
// detail (solid / warn), it can never manufacture a ready light for a dead unit.
inline LinkIndicator EvaluateLinkIndicator(bool socket_connected, bool rx_live,
                                           bool hh_live, bool armed) {
  if (!socket_connected) return LinkIndicator::kOff;
  const bool any_live = rx_live || hh_live;
  if (!any_live) return LinkIndicator::kFault;
  if (!armed) return LinkIndicator::kReadyToArm;
  if (rx_live && hh_live) return LinkIndicator::kReady;
  return LinkIndicator::kWarn;  // armed, exactly one unit live
}

// Whether the LED should be driven HIGH at this instant, given the indicator
// state and the three blink phases (true = the "on" half of that cycle; the
// caller derives each from millis(), keeping this function pure). Three separate
// rates -- ready (~1 Hz), warn (~2 Hz), fault (~5 Hz) -- so "ready to arm",
// "one unit missing while armed", and "nothing reachable" are all tellable
// apart on a single LED.
inline bool LinkIndicatorLedOn(LinkIndicator indicator, bool ready_phase_on,
                               bool warn_phase_on, bool fault_phase_on) {
  switch (indicator) {
    case LinkIndicator::kOff:
      return false;
    case LinkIndicator::kReady:
      return true;
    case LinkIndicator::kReadyToArm:
      return ready_phase_on;
    case LinkIndicator::kWarn:
      return warn_phase_on;
    case LinkIndicator::kFault:
      return fault_phase_on;
  }
  return false;  // unreachable; keeps the compiler happy about the enum switch
}

}  // namespace control_core
