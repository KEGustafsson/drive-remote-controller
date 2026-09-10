#pragma once

// Pure C++17 drive command representation + mapping. No Arduino.h --
// host-testable under env:native.
//
// Stateless on purpose (ARCHITECTURE.md §5, SAFETY.md drive invariant 2): this project
// has NO dwell/dead-time, unlike heading-hold's Switcher. A
// switch/command reading maps to a DrivePosition on every call, with no
// memory of the previous position and no timing gate -- whatever
// arbitration (arbitration.h) decides is authoritative reaches the servo
// as fast as the control loop runs.
//
// DrivePosition is the single shared representation used throughout
// lib/control_core/ and the RX control task. Conversion to/from the Signal K
// string representation
// happens only at the SK boundary (src/tx/main.cpp's publish,
// src/rx/sk_command_in.cpp's parse) -- the ToSkString/FromSkString
// functions below exist here (not duplicated in src/) so that boundary
// conversion is itself pure and host-testable.

#include <cstdint>
#include <cstring>

namespace control_core {

enum class DrivePosition : uint8_t {
  kNeutral,
  kForward,
  kReverse,
};

// Maps a 3-position switch's two non-neutral contacts to a position.
// Structurally cannot produce "both directions at once" (CLAUDE.md
// invariant 1): if both contacts read active simultaneously -- which a
// correctly wired 3-position switch should never do, but a miswired
// switch or a glitched read could -- this returns kNeutral rather than
// picking one, matching the project-wide fail-to-neutral rule.
constexpr DrivePosition FromSwitch(bool forward_active,
                                      bool reverse_active) {
  if (forward_active && reverse_active) return DrivePosition::kNeutral;
  if (forward_active) return DrivePosition::kForward;
  if (reverse_active) return DrivePosition::kReverse;
  return DrivePosition::kNeutral;
}

// SK boundary: outbound. constexpr-friendly, no allocation.
constexpr const char* ToSkString(DrivePosition pos) {
  switch (pos) {
    case DrivePosition::kForward:
      return "forward";
    case DrivePosition::kReverse:
      return "reverse";
    case DrivePosition::kNeutral:
    default:
      return "neutral";
  }
}

// SK boundary: inbound. Anything that isn't exactly "forward" or "reverse"
// -- including null, empty, or a malformed/garbage delta value -- maps to
// kNeutral. This is a deliberate fail-safe default, not just a parsing
// convenience: a corrupted or unexpected inbound value must never be
// silently interpreted as a direction (SAFETY.md drive invariant 5).
inline DrivePosition FromSkString(const char* s) {
  if (s == nullptr) return DrivePosition::kNeutral;
  if (std::strcmp(s, "forward") == 0) return DrivePosition::kForward;
  if (std::strcmp(s, "reverse") == 0) return DrivePosition::kReverse;
  return DrivePosition::kNeutral;
}

}  // namespace control_core
