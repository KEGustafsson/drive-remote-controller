#pragma once

// Pure C++17 autopilot-style heading-TRIM accumulator. No Arduino.h --
// host-testable under env:native. Runs on the COMMANDING station (TX's two
// thruster buttons while the mode switch is in HOLD; the plugin's nudge
// buttons), not on the heading-hold unit itself.
//
// WHAT IT ACCUMULATES: a RELATIVE trim offset, starting at 0, that HH applies
// on top of whatever heading it captured when hold engaged (ARCHITECTURE.md
// §6.4). It is NOT an absolute target, and needs no seed: pressing STBD before
// the station knows anything about HH is well-defined -- it means "+1 degree
// off wherever hold captures". 0 is the resting value ("no trim"), so the wire
// value needs no sentinel and a lost/duplicated message costs at most a stale
// clamped offset, never an arbitrary heading. Published as a LEVEL (the whole
// offset, not per-press events), so the last message is the whole truth and a
// repeat is harmless -- the same self-correcting property the absolute design
// had, without the seed or the sentinel.
//
// BOUND: the offset is clamped to +/-kMaxTrimDeg (setpoint.h) so a stuck
// button cannot wind it past the structural trim limit; HH's own rate limiter
// then bounds how fast the boat actually swings.
//
// Press semantics (owner-chosen): a tap trims by the fine step; holding the
// button past a short delay repeats by the coarse step, so small corrections
// are precise and big ones are quick.

#include <cstdint>

#include "heading/setpoint.h"  // control_core::ClampTrimDeg, kMaxTrimDeg

namespace control_core {

class HeadingNudge {
 public:
  struct Cfg {
    float fine_step_deg = 1.0f;    // one tap
    float coarse_step_deg = 10.0f; // each auto-repeat while held
    uint32_t repeat_delay_ms = 750;   // hold this long before repeating
    uint32_t repeat_period_ms = 400;  // then one coarse step this often
  };

  explicit HeadingNudge(const Cfg& cfg) : cfg_(cfg) {}

  // port_pressed / stbd_pressed: DEBOUNCED button levels for this tick.
  //
  // Port trims anticlockwise (decreasing heading), starboard clockwise
  // (increasing) -- matching the convention that starboard thrust increases
  // heading, so the button that would push the bow starboard also asks for a
  // more-starboard held heading.
  void Update(bool port_pressed, bool stbd_pressed, uint32_t now_ms) {
    // Both pressed is treated as no request at all -- the same fail-safe
    // resolution the drives use when a shift switch reads both ways at once.
    const bool port = port_pressed && !stbd_pressed;
    const bool stbd = stbd_pressed && !port_pressed;

    if (!port && !stbd) {
      held_since_ms_ = 0;
      last_repeat_ms_ = 0;
      prev_direction_ = 0;
      return;
    }

    const int direction = stbd ? +1 : -1;

    // A press edge, or the operator sliding from one button to the other
    // without releasing: treat as a fresh press so the repeat timer restarts
    // and the first trim is a fine step, not a coarse one.
    if (direction != prev_direction_) {
      prev_direction_ = direction;
      held_since_ms_ = now_ms;
      last_repeat_ms_ = 0;
      ApplyStep(direction * cfg_.fine_step_deg);
      return;
    }

    // Held. Nothing more until the repeat delay has passed.
    if ((now_ms - held_since_ms_) < cfg_.repeat_delay_ms) return;

    const uint32_t since_repeat =
        last_repeat_ms_ == 0 ? cfg_.repeat_period_ms : now_ms - last_repeat_ms_;
    if (since_repeat < cfg_.repeat_period_ms) return;

    last_repeat_ms_ = now_ms;
    ApplyStep(direction * cfg_.coarse_step_deg);
  }

  // Reset the trim to 0 -- leaving HOLD mode, losing the HH unit, or losing
  // authority. Named Invalidate() for continuity with the callers; there is no
  // longer a "no target" state, just a zeroed offset (which IS the safe rest).
  void Invalidate() {
    trim_deg_ = 0.0f;
    held_since_ms_ = 0;
    last_repeat_ms_ = 0;
    prev_direction_ = 0;
  }

  float trim_deg() const { return trim_deg_; }

 private:
  void ApplyStep(float step_deg) {
    trim_deg_ = ClampTrimDeg(trim_deg_ + step_deg);
  }

  const Cfg cfg_;

  float trim_deg_ = 0.0f;

  int prev_direction_ = 0;  // -1 port, +1 stbd, 0 released
  uint32_t held_since_ms_ = 0;
  uint32_t last_repeat_ms_ = 0;
};

// May a commanding station KEEP the trim it has accumulated, or must it drop
// back to 0 (HeadingNudge::Invalidate)?
//
// ARCHITECTURE.md §6.4's "arm-first, then trim": arming holds the heading HH
// captures AT THE ARM, and trimming is a deliberate action taken afterwards. An
// offset dialled in while the station could not command anything must therefore
// never survive to be applied the instant it can -- arming must not swing the
// boat to a number somebody entered earlier.
//
// All three stations owe this rule, and it is stated here so it is written (and
// tested) once instead of three times in three languages. It is also written as
// a predicate rather than a comment because the term TX originally forgot --
// the station's own enable switch -- is invisible in prose and unmissable in a
// signature: TX's buttons accumulated trim with its enable switch OFF, and on
// TX flipping that switch IS the arm, so HOLD engaged and immediately slewed
// off the heading it had just captured.
//
// hold_mode:       the station's mode selector is on HOLD. In MANUAL the same
//                  buttons ARE the direction and a trim means nothing.
// station_enabled: this station is itself enabled/armed -- it has authority.
// hh_live:         the thruster unit's telemetry is currently ARRIVING. Judged
//                  on arrival, never on the value of an hh.* path, which Signal
//                  K retains long after HH loses power (link_indicator.h).
constexpr bool TrimHoldAllowed(bool hold_mode, bool station_enabled,
                               bool hh_live) {
  return hold_mode && station_enabled && hh_live;
}

}  // namespace control_core
