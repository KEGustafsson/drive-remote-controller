#pragma once

// Shared angle helper for lib/control_core. Per CLAUDE.md coding
// conventions: angles are degrees internally, wrapped to (-180, 180].

#include <cmath>

namespace control_core {

// Wrap to (-180, 180] in O(1). Deliberately fmod-based rather than the
// obvious subtract-360-in-a-loop: a loop never terminates for +/-Inf and
// takes an unbounded number of iterations for a huge finite value, so a
// single bad number arriving in a WebSocket callback could hang the whole
// SensESP task while it holds a mutex. fmod is constant-time for any
// magnitude and yields NaN for NaN/Inf input -- garbage stays garbage
// (callers validate with isfinite at the boundary), but it can never stall
// the caller.
inline float WrapDeg180(float deg) {
  deg = std::fmod(deg, 360.0f);
  if (deg > 180.0f) {
    deg -= 360.0f;
  } else if (deg <= -180.0f) {
    deg += 360.0f;
  }
  return deg;
}

// Wrap to [0, 360), the compass range. For PRESENTATION only -- publishing a
// heading the way Signal K's own navigation.headingTrue reads (0..2pi), so the
// two can be compared by eye. The control maths stays in WrapDeg180. Same
// fmod-based O(1) contract: non-finite in, non-finite out, never a hang.
inline float WrapDeg360(float deg) {
  deg = std::fmod(deg, 360.0f);
  if (deg < 0.0f) deg += 360.0f;
  // A tiny negative input (-1e-6) plus 360 rounds to exactly 360.0f in float,
  // which is outside the half-open range: north must read 0, not 360.
  if (deg >= 360.0f) deg = 0.0f;
  return deg;
}

}  // namespace control_core
