#include "drive/arbitration.h"

namespace control_core {

namespace {
bool Qualifies(const RemoteSource& s) { return s.live && s.enabled; }
}  // namespace

ArbitrationResult Arbitrate(DrivePosition local_position, bool rx_armed,
                             const RemoteSource& tx,
                             const RemoteSource& plugin) {
  // 1. Local override -- unconditional, ignores everything else.
  if (local_position != DrivePosition::kNeutral) {
    return {local_position, ActiveSource::kLocal};
  }

  // 2. RX's arm state (master enable AND the neutral interlock -- see
  // drive/arm_gate.h) gates whether remote sources are consulted at all.
  if (!rx_armed) {
    return {DrivePosition::kNeutral, ActiveSource::kNone};
  }

  // 3. Fixed precedence among qualifying (live + enabled) remote sources:
  // TX -- the physical remote station -- always outranks the plugin (phone
  // UI). Recency is deliberately NOT consulted: an earlier "most recently
  // updated wins" rule made authority flip on every periodic refresh when
  // the two disagreed, oscillating the servo forward<->reverse. Fixed
  // precedence is deterministic and cannot oscillate. The plugin only
  // commands when TX is not qualifying (offline, stale, or its own enable
  // off); the marine "station-in-command" convention (chosen by the user,
  // 2026-07-20). last_update_ms is therefore no longer read here.
  if (Qualifies(tx)) {
    return {tx.command, ActiveSource::kTx};
  }
  if (Qualifies(plugin)) {
    return {plugin.command, ActiveSource::kPlugin};
  }

  // Neither remote source is live+enabled -- fail to NEUTRAL, never hold a
  // stale command (SAFETY.md drive invariant 5).
  return {DrivePosition::kNeutral, ActiveSource::kNone};
}

}  // namespace control_core
