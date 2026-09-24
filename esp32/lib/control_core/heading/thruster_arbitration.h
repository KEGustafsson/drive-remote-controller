#pragma once

// Pure C++17, SAFETY-CRITICAL command arbitration for the BOW THRUSTER --
// the heading-hold unit's analogue of drive/arbitration.h. No Arduino.h,
// host-testable under env:native. See ARCHITECTURE.md §6 and SAFETY.md.
//
// It answers one question per tick: WHO is commanding the thruster right now,
// and in which MODE (direct human control, or heading hold)?
//
// Priority, exactly -- the same shape as the drives, so an operator only has
// to learn one rule for the whole boat:
//   1. The HH unit's own local ENGAGE input is UNCONDITIONAL. Asserted, it
//      requests heading hold and the remote sources are not consulted at all.
//      This is SAFETY.md thruster invariant 6 ("manual authority dominates") preserved
//      unchanged by the addition of remote control: someone standing at the
//      unit can always take it, and a phone can never wrestle it back.
//   2. Otherwise TX outranks the plugin by FIXED precedence (never recency),
//      exactly as for the drives -- "TX wins both motion and bow thruster
//      controls whenever it is activated" (owner requirement). A source must
//      be both live() (time-since-last-update) and enabled() (its own reported
//      ARMED state) to qualify.
//   3. Nothing qualifying -> no engage request at all: the FSM disarms and the
//      outputs go off. Fail-to-off, never fail-to-last-command, matching the
//      drives' fail-to-NEUTRAL rule.
//
// Live/enabled are judged by the CALLER and handed in already evaluated --
// the same "caller already judged it" boundary SafetyFsm and drive/
// arbitration.h both use, keeping this decoupled from LinkWatchdog and from
// wall-clock specifics.
//
// NOTE ON WHAT THIS DOES *NOT* DECIDE: it never touches the outputs and never
// bypasses the safety FSM. Its `engage_request` is fed to SafetyFsm exactly
// where the local ENGAGE level used to go, so BNO timeouts, the deadman, the
// fail-off watchdog and the ENABLE/PORT/STBD invariants all still apply to a
// remotely-commanded thrust exactly as they do to a locally-engaged hold.

#include <cstdint>

#include "common/active_source.h"
#include "heading/switcher.h"  // control_core::Cmd

namespace control_core {

// What the thruster is being asked to do. This is a MODE, published and
// displayed, not an implicit consequence of which button was last pressed --
// the operator (and the telemetry) can always state which one is in force.
enum class ThrusterMode : uint8_t {
  // Heading hold runs the thruster: the bang-bang switcher decides direction
  // from heading error and yaw rate. Manual direction requests are inert.
  kHold,
  // Direct human control: the operator's PORT/STBD button IS the direction.
  // The switcher does not run at all in this mode.
  kManual,
};

inline const char* ThrusterModeName(ThrusterMode m) {
  return m == ThrusterMode::kManual ? "manual" : "hold";
}

// Parses the SK string form. Anything unrecognised -- including a missing,
// empty or malformed value -- reads as kHold rather than guessing at manual
// control of a thruster; same defensive default as the drives' FromSkString()
// treating garbage as NEUTRAL.
inline ThrusterMode ThrusterModeFromSkString(const char* s) {
  if (s == nullptr) return ThrusterMode::kHold;
  if (s[0] == 'm' && s[1] == 'a' && s[2] == 'n' && s[3] == 'u' && s[4] == 'a' &&
      s[5] == 'l' && s[6] == '\0') {
    return ThrusterMode::kManual;
  }
  return ThrusterMode::kHold;
}

// SK string form of a thruster direction. Lives here, in the pure core, so the
// publisher (TX, in src/tx/) and the parser (HH, in src/hh/) share one
// definition -- the two are compiled into different firmwares and could
// otherwise drift apart on spelling with nothing to catch it. "off" rather
// than "neutral": a thruster coasts, it does not sit in a gear.
inline const char* ThrusterCmdToSkString(Cmd cmd) {
  switch (cmd) {
    case Cmd::kPort:
      return "port";
    case Cmd::kStbd:
      return "stbd";
    case Cmd::kOff:
      return "off";
  }
  return "off";
}

// Anything unrecognised -- missing, empty, malformed, or a corrupted value --
// reads as kOff rather than guessing at a direction for a thruster. Same
// defensive default as the drives' FromSkString() treating garbage as NEUTRAL.
inline Cmd ThrusterCmdFromSkString(const char* s) {
  if (s == nullptr) return Cmd::kOff;
  if (s[0] == 'p' && s[1] == 'o' && s[2] == 'r' && s[3] == 't' &&
      s[4] == '\0') {
    return Cmd::kPort;
  }
  if (s[0] == 's' && s[1] == 't' && s[2] == 'b' && s[3] == 'd' &&
      s[4] == '\0') {
    return Cmd::kStbd;
  }
  return Cmd::kOff;
}

// How long a remote thruster source may go unheard before it stops counting as
// live -- ONE WINDOW PER MODE, and the two numbers are deliberately different.
// Do not harmonise them, and do not harmonise either with the drives' single
// kSkStalenessTimeoutMs.
//
// The two gates pay completely different prices for a source wrongly judged
// gone. MANUAL is a momentary button with the operator's finger on it and their
// eyes on the boat: the shorter window is the whole safety property there --
// a station that vanishes mid-press must stop the thrust, promptly, and a false
// trip costs one 250 ms gap in a command the operator is already watching.
// HOLD is the opposite: an autonomous loop against a setpoint HH captured for
// itself, with nobody at a button. Dropping it costs the whole hold, because a
// source that goes stale while it is armed in HOLD arms the re-engage latch
// (control_step.h, SAFETY.md thruster invariant 9) -- permanent until that
// station is seen live and DISARMED. So HOLD buys headroom against
// transport jitter and MANUAL buys promptness, and neither number is the other
// one's default.
//
// The MODE that picks the window is the one in the same coherent read of the
// source's tuple, so the stricter window applies from the first tick the source
// reads kManual. That ordering is what keeps the loosening from leaking into
// manual control: a source cannot carry HOLD's tolerance into a manual command.
struct ThrusterStaleness {
  uint32_t hold_ms = 0;
  uint32_t manual_ms = 0;
};

// The window in force for `mode`. kHold is the fallback for anything that is
// not explicitly manual, matching ThrusterModeFromSkString's defensive default.
inline uint32_t ThrusterStalenessMsFor(ThrusterMode mode,
                                       const ThrusterStaleness& windows) {
  return mode == ThrusterMode::kManual ? windows.manual_ms : windows.hold_ms;
}

// One remote command source (TX, or the plugin) as evaluated by the caller
// immediately before calling ArbitrateThruster().
struct ThrusterRemote {
  bool live = false;     // LinkWatchdog::IsLive() result
  bool enabled = false;  // the source's own reported ARMED state
  // Oldest member arrival in the coherent tuple. Hardware glue uses this to
  // age a cached snapshot safely on a rare zero-timeout mutex miss.
  uint32_t last_update_ms = 0;
  ThrusterMode mode = ThrusterMode::kHold;
  // Direction the operator is asking for right now. Only consulted in
  // kManual. kOff is the resting value -- releasing the button is what
  // commands off, exactly like releasing a drive shift switch commands
  // NEUTRAL.
  Cmd manual_cmd = Cmd::kOff;
  // A commanded heading-hold TRIM, in degrees, RELATIVE to the heading HH
  // captured when it entered hold (ARCHITECTURE.md §6.4). Only consulted in
  // kHold. 0 is the resting value ("no trim, hold the captured heading"), so
  // unlike an absolute target it needs no sentinel -- a source that is not
  // trimming simply publishes 0. Always finite and clamped to +/-kMaxTrimDeg
  // by the caller (see ClampTrimDeg); the arbiter treats it as data, not a
  // command that can be "absent".
  float trim_deg = 0.0f;
};

struct ThrusterArbitrationResult {
  ActiveSource source = ActiveSource::kNone;
  // Feeds SafetyFsm's `engage` input. True whenever SOMETHING authoritative
  // is asking the thruster to be active -- local engage, or a qualifying
  // remote source in either mode.
  bool engage_request = false;
  ThrusterMode mode = ThrusterMode::kHold;
  // Forced to kOff whenever mode is kHold, so a caller cannot accidentally
  // act on a stale manual request while the switcher owns the direction.
  Cmd manual_cmd = Cmd::kOff;
  // Commanded heading trim (deg, relative to the captured heading). Forced to
  // 0 whenever mode is kManual -- a trim cannot survive into manual mode, the
  // same structural field-clearing as manual_cmd cannot survive into hold. A
  // local engage also yields 0: the unit's own ENGAGE holds the current
  // heading with no trim of its own.
  float trim_deg = 0.0f;
};

ThrusterArbitrationResult ArbitrateThruster(bool local_engage,
                                            const ThrusterRemote& tx,
                                            const ThrusterRemote& plugin);

}  // namespace control_core
