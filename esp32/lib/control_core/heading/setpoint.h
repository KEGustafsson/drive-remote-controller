#pragma once

// Pure C++17 rate-limited slew of the held heading setpoint, plus the trim
// clamp for a RELATIVE heading-trim offset commanded from a remote station
// (ARCHITECTURE.md §6.4). No Arduino.h, host-testable under env:native.
//
// WHY A RELATIVE TRIM, NOT AN ABSOLUTE TARGET: a station never asks for an
// absolute heading. It arms (HH captures whatever heading it is on) and then
// NUDGES that by a few degrees. Representing the command as an offset from the
// captured heading -- rather than an absolute number -- means the resting
// value is a plain 0 ("no trim, hold what you captured"), so no out-of-band
// sentinel is needed to say "not commanding", and a JSON null coerced to 0.0f
// by the firmware's float listener means exactly the safe thing instead of a
// bogus 0-degree/north command. It also fails softer: a corrupted offset is a
// few degrees of trim, clamped, never an arbitrary heading.
//
// WHY A SLEW AND NOT AN ASSIGNMENT: the effective target (captured heading +
// trim) can jump by up to a coarse step per press. Assigning it straight into
// the setpoint would let the switcher translate that into full thruster
// deflection. Clamping how fast the setpoint may move bounds it to something
// the boat can actually do, so a change costs a few degrees of slow correction
// rather than a hard swing.
//
// WHAT THIS DOES NOT TOUCH: the state estimate. Only the setpoint moves. The
// fused heading (what the boat is actually doing) is never snapped on a mode
// change or a new command -- see ARCHITECTURE.md §6.4.
//
// UNTRUSTED INPUT: the trim crosses the network, so it is treated as hostile
// at this boundary in the same spirit as Switcher::SetTunables. A non-finite
// trim reads as 0 (no trim) and any finite value is CLAMPED into +/-kMaxTrimDeg
// rather than rejected, because 0 is a safe, meaningful resting value here (it
// is not for an absolute heading, which is why that one was rejected instead).

#include <cmath>

#include "heading/angle_math.h"

namespace control_core {

// Largest heading-trim offset a station may command, in degrees. A structural
// safety clamp, not a tuning knob: it bounds what a corrupted or fat-fingered
// value can do to a captured heading. The plugin mirrors this as MAX_TRIM_DEG
// in sk-plugin/src/config.ts.
inline constexpr float kMaxTrimDeg = 45.0f;

// Largest tick length the slew will act on (seconds).
inline constexpr float kMaxSlewDtS = 2.0f;

// Coerce a commanded trim to a safe, bounded offset: non-finite (NaN/Inf,
// including a JSON null read as 0.0f -- which is already safe) reads as 0 = no
// trim; anything else is clamped into [-kMaxTrimDeg, kMaxTrimDeg]. There is no
// "invalid" outcome: the offset is always defined, and 0 is the safe rest.
inline float ClampTrimDeg(float trim_deg) {
  if (!std::isfinite(trim_deg)) return 0.0f;
  if (trim_deg > kMaxTrimDeg) return kMaxTrimDeg;
  if (trim_deg < -kMaxTrimDeg) return -kMaxTrimDeg;
  return trim_deg;
}

// Moves current_deg toward target_deg by at most max_rate_dps * dt_s,
// travelling the short way round. Returns the new setpoint, wrapped to
// (-180, 180]. target_deg is an ABSOLUTE effective heading (a captured heading
// plus a clamped trim), so it is always finite by construction; the guard is
// defence in depth. On a non-finite target or a non-positive rate the setpoint
// is returned unchanged -- a rejected command must never move the boat.
inline float SlewSetpointDeg(float current_deg, float target_deg, float dt_s,
                             float max_rate_dps) {
  if (!std::isfinite(target_deg)) return current_deg;
  if (!(max_rate_dps > 0.0f)) return current_deg;  // also catches NaN
  // Reject an absurd dt (a stalled tick, a clock jump, a NaN) rather than
  // letting it multiply into one huge step. The ceiling is deliberately well
  // above any real control period -- it exists to catch a broken clock, not to
  // second-guess a slow loop.
  if (!(dt_s > 0.0f) || !(dt_s <= kMaxSlewDtS)) return current_deg;

  const float delta = WrapDeg180(target_deg - current_deg);
  const float step = max_rate_dps * dt_s;
  if (delta > step) return WrapDeg180(current_deg + step);
  if (delta < -step) return WrapDeg180(current_deg - step);
  return WrapDeg180(target_deg);  // within one step: land exactly on it
}

}  // namespace control_core
