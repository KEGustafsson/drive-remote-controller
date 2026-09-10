#pragma once

// Pure C++17, SAFETY-CRITICAL. No Arduino.h -- host-testable under env:native.
// RX's arm permission: may a remote source command the drives at all, and may
// the actuator-engage relay be energised? Implements SAFETY.md drive invariant
// 7 / ARCHITECTURE.md §5.1.
//
// THE ONE THING TO UNDERSTAND HERE: the neutral condition is a permission to
// ARM, not a condition of staying armed. RX's own servos move the shift levers,
// so the neutral sensors open the instant an armed remote commands gear. A
// continuously-enforced "both levers neutral" rule would disarm RX on its first
// shift -- it would be a machine that fights itself. This is the same shape as
// a car's neutral-safety switch: it gates the starter, not the running engine.
//
// The rule (owner decision 2026-07-25 -- level-based, no enable edge required):
//
//   master enable OFF                     -> DISARMED, always, immediately
//   master enable ON + all clear          -> ARMED
//   anything else                         -> hold the current state
//
// "All clear" is all three of:
//   - the port lever reads NEUTRAL
//   - the starboard lever reads NEUTRAL
//   - RX is not itself commanding a gear from a local switch
//
// That third condition is not decoration. While DISARMED the engage relay is
// released, so the servos are mechanically disconnected from the levers -- and
// a local shift switch still drives its servo unconditionally (drive invariant
// 3), because the firmware has no way to know the linkage is out. So a servo
// can be parked at FORWARD while the lever it is not connected to reads
// NEUTRAL. Energising the clutch in that state would slam the lever straight
// into gear at the moment of arming. Requiring both local switches at neutral
// means the servos are at their neutral pulse too, matching the levers the
// clutch is about to grab.
//
// Deliberately NOT here: liveness, TX/plugin enable state, servo calibration,
// anything time-based. This gate answers only "is RX permitted to be in
// command"; drive/arbitration.h then answers "and who commands what". Feeding
// this class's `armed` into Arbitrate()'s rx_armed parameter is what makes the
// two compose.

#include <cstdint>

namespace control_core {

// Why the gate is refusing, so a refusal is never silent. Reported only while
// DISARMED -- once armed, levers leaving neutral is ordinary shifting and the
// inhibit reads kNone.
enum class ArmInhibit : uint8_t {
  kNone,  // armed, or (defensively) nothing is actually blocking
  kMasterEnableOff,
  kPortLeverNotNeutral,
  kStbdLeverNotNeutral,
  kBothLeversNotNeutral,
  kLocalCommandActive,  // RX's own shift switch is off neutral
};

// Stable SK string form for kSkRxArmInhibitPath. Empty string = nothing is
// blocking, matching the project's "the resting value is the safe/quiet one"
// convention for retained SK paths.
inline const char* ArmInhibitName(ArmInhibit inhibit) {
  switch (inhibit) {
    case ArmInhibit::kMasterEnableOff:
      return "masterEnableOff";
    case ArmInhibit::kPortLeverNotNeutral:
      return "portLeverNotNeutral";
    case ArmInhibit::kStbdLeverNotNeutral:
      return "stbdLeverNotNeutral";
    case ArmInhibit::kBothLeversNotNeutral:
      return "bothLeversNotNeutral";
    case ArmInhibit::kLocalCommandActive:
      return "localCommandActive";
    case ArmInhibit::kNone:
      return "";
  }
  return "";
}

// All four inputs are already debounced by the caller -- the same "the caller
// already judged it" boundary arbitration.h and heading/safety_fsm.h use.
struct ArmGateInputs {
  bool master_enable = false;        // RX's latching master enable switch
  bool port_lever_neutral = false;   // port neutral sensor asserted
  bool stbd_lever_neutral = false;   // starboard neutral sensor asserted
  bool local_command_active = false; // either local shift switch off neutral
};

struct ArmGateStatus {
  bool armed = false;
  ArmInhibit inhibit = ArmInhibit::kMasterEnableOff;
};

class ArmGate {
 public:
  // Call once per control tick. Starts DISARMED and never self-arms without
  // the master enable switch being ON, so a reboot always lands in the safe
  // state (the relay is written released before this class is ever consulted).
  ArmGateStatus Update(const ArmGateInputs& in);

  ArmGateStatus status() const { return status_; }
  bool armed() const { return status_.armed; }

 private:
  ArmGateStatus status_{};
};

}  // namespace control_core
