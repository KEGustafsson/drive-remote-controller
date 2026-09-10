#include "heading/thruster_arbitration.h"

#include "heading/setpoint.h"  // control_core::ClampTrimDeg

namespace control_core {
namespace {

// Builds the result for a qualifying remote source, applying the two
// structural field-clearing rules from the header: no manual direction can
// survive into kHold, no commanded target can survive into kManual. Doing it
// here (rather than trusting every caller to check `mode` first) is the same
// defence-in-depth as the drives' output map refusing to express both
// directions at once.
ThrusterArbitrationResult FromRemote(ActiveSource source,
                                     const ThrusterRemote& r) {
  ThrusterArbitrationResult out;
  out.source = source;
  out.engage_request = true;
  out.mode = r.mode;
  if (r.mode == ThrusterMode::kManual) {
    out.manual_cmd = r.manual_cmd;
    out.trim_deg = 0.0f;  // no trim survives into manual
  } else {
    out.manual_cmd = Cmd::kOff;
    out.trim_deg = ClampTrimDeg(r.trim_deg);  // clamp again, defence in depth
  }
  return out;
}

}  // namespace

ThrusterArbitrationResult ArbitrateThruster(bool local_engage,
                                            const ThrusterRemote& tx,
                                            const ThrusterRemote& plugin) {
  // Step 1: the unit's own ENGAGE input is unconditional and always means
  // "hold this heading" -- it is a single physical contact with no mode of its
  // own. Nothing about which remote is live, enabled, or shouting is consulted
  // here. SAFETY.md thruster invariant 6.
  if (local_engage) {
    ThrusterArbitrationResult out;
    out.source = ActiveSource::kLocal;
    out.engage_request = true;
    out.mode = ThrusterMode::kHold;
    return out;
  }

  // Step 2: fixed precedence TX > plugin among sources that are both live and
  // enabled. Fixed, not recency-based: when two stations disagree, a recency
  // tie-break flips authority on every periodic refresh and oscillates the
  // output. A thruster oscillating port<->stbd would additionally hammer
  // straight into the control box's reversal interlock, so the deterministic
  // rule matters even more here than it does for the drives.
  if (tx.live && tx.enabled) return FromRemote(ActiveSource::kTx, tx);
  if (plugin.live && plugin.enabled) {
    return FromRemote(ActiveSource::kPlugin, plugin);
  }

  // Step 3: nobody qualifies. No engage request -> the FSM disarms -> ENABLE
  // drops and both direction lines go low. Fail to off, never to the last
  // thing anyone said.
  return ThrusterArbitrationResult{};
}

}  // namespace control_core
