#include "drive/arm_gate.h"

namespace control_core {

namespace {

// The one blocker to report while DISARMED, in the order an operator would fix
// them: the switch in their hand first, then the levers, then their own local
// override. Only ever called when the gate is not armed, so it always finds
// something -- kNone at the end is defensive, not a reachable state.
ArmInhibit Blocker(const ArmGateInputs& in) {
  if (!in.master_enable) return ArmInhibit::kMasterEnableOff;
  if (!in.port_lever_neutral && !in.stbd_lever_neutral) {
    return ArmInhibit::kBothLeversNotNeutral;
  }
  if (!in.port_lever_neutral) return ArmInhibit::kPortLeverNotNeutral;
  if (!in.stbd_lever_neutral) return ArmInhibit::kStbdLeverNotNeutral;
  if (in.local_command_active) return ArmInhibit::kLocalCommandActive;
  return ArmInhibit::kNone;
}

}  // namespace

ArmGateStatus ArmGate::Update(const ArmGateInputs& in) {
  if (!in.master_enable) {
    // Disarm is never gated on anything (SAFETY.md, cross-cutting rules):
    // whoever is nearest the machinery must always be able to stop it, so the
    // master enable going OFF releases the relay on the very next tick with no
    // condition attached.
    status_.armed = false;
  } else if (in.port_lever_neutral && in.stbd_lever_neutral &&
             !in.local_command_active) {
    status_.armed = true;
  }
  // Otherwise HOLD the current state. This is the latch: while ARMED, a lever
  // leaving neutral is the drives being shifted -- exactly what arming was for
  // -- and must not disarm. While DISARMED, a partial condition changes
  // nothing until all of them line up.

  status_.inhibit = status_.armed ? ArmInhibit::kNone : Blocker(in);
  return status_;
}

}  // namespace control_core
