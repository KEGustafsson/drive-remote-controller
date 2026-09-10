#pragma once

// Pure C++17, SAFETY-CRITICAL command arbitration. No Arduino.h --
// host-testable under env:native. Implements ARCHITECTURE.md §5 / CLAUDE.md
// invariants 3-5 exactly, for a single drive -- call Arbitrate() once
// per drive (port, starboard), each with its own independent inputs.
// There is no shared state between calls, so port and starboard are
// naturally arbitrated completely independently (SAFETY.md drive invariant 1:
// they may command different directions from each other at the same time,
// that's normal operation, not a fault).
//
// Priority, exactly:
//   1. local_position != kNeutral -> local wins, unconditionally (ignores
//      rx_armed and both remote sources entirely).
//   2. rx_armed == false -> kNeutral (remote sources are not consulted at all).
//   3. Otherwise, among the remote sources that are both live() and
//      enabled(), TX outranks the plugin by FIXED precedence (not recency):
//      TX wins whenever it qualifies, the plugin wins only when TX does not.
//      Neither qualifying -> kNeutral. Fixed precedence is deterministic and
//      cannot oscillate the output, unlike a recency-based tie-break when the
//      two sources disagree (user decision 2026-07-20; see arbitration.cpp).
// "Live" (time-since-last-update) and "enabled" (the source's own reported
// switch/UI state) are supplied by the caller, already evaluated -- same
// "caller already judged it" boundary heading-hold uses throughout
// (see e.g. its SafetyFsm::FsmInputs). This keeps Arbitrate() decoupled
// from LinkWatchdog and from wall-clock specifics.

#include <cstdint>

#include "common/active_source.h"
#include "drive/drive_command.h"

namespace control_core {

// One remote command source's current state, as evaluated by the caller
// immediately before calling Arbitrate().
struct RemoteSource {
  DrivePosition command = DrivePosition::kNeutral;
  bool enabled = false;       // the source's own reported enable state
  bool live = false;          // LinkWatchdog::IsLive() result
  // LinkWatchdog::LastUpdateMs(). No longer consulted by Arbitrate() since
  // TX/plugin resolve by fixed precedence, not recency -- kept because it's
  // still populated by SkCommandIn and is useful diagnostic/telemetry state.
  uint32_t last_update_ms = 0;
};

// Which input is currently authoritative is reported alongside the resolved
// command so RX can publish it (ARCHITECTURE.md §9:
// control.remoteController.rx.{port,stbd}.source). The enum itself lives in
// common/active_source.h -- the bow thruster's arbitration reports the same
// four possibilities and must spell them identically.

struct ArbitrationResult {
  DrivePosition command = DrivePosition::kNeutral;
  ActiveSource source = ActiveSource::kNone;
};

// rx_armed is drive/arm_gate.h's verdict, NOT the raw master enable switch:
// the switch being ON is necessary but not sufficient (the levers must have
// been proven neutral to arm). Passing the raw switch here would walk straight
// past the neutral interlock, so there is deliberately no second parameter to
// get them confused with -- ArmGate::armed() is the only thing that belongs
// in this argument.
ArbitrationResult Arbitrate(DrivePosition local_position, bool rx_armed,
                             const RemoteSource& tx,
                             const RemoteSource& plugin);

}  // namespace control_core
