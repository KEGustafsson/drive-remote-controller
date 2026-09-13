#pragma once

// Age of a millis() timestamp -- the one subtraction every liveness judgement
// in this project makes, written once.
//
// WHY NOT JUST `now_ms - then_ms`. Unsigned subtraction is correct across the
// ~49.7-day millis() rollover, and that is why it is used everywhere. But it
// has a second wrap nobody wanted: a timestamp LATER than now_ms reads as an
// age of about 2^32 ms. On both units that is not hypothetical. The control
// task samples its clock once at the top of a tick and uses it for the whole
// tick, while the SK listener callbacks stamp their own millis() on the loop
// task when a delta is processed. A callback that completes between the tick's
// clock read and its snapshot of that source records an update a millisecond
// or two "in the future" -- and the plain subtraction then calls the freshest
// evidence the unit holds 49 days old. LinkWatchdog latches that verdict until
// the path's next delta, so a healthy station read as not-live for one refresh
// period, and on HH the re-engage latch turned that blip into the end of the
// hold (JOURNAL.md, 2026-09-13).
//
// THE RULE. A difference in the upper half of the uint32_t range cannot be a
// real age here: every caller polls at tens of Hz and judges against timeouts
// of seconds, so a genuinely silent source is found stale -- and latched --
// some 24.8 days before its true age could get that large. What lands there is
// a timestamp from after now_ms, and the honest age of one of those is zero.
// Rollover is unaffected: an age that straddles the wrap is still small.

#include <cstdint>

namespace control_core {

// Ages at or above this are timestamps from after `now_ms`, not real ages.
constexpr uint32_t kFutureTimestampAgeMs = 0x80000000u;

// True when `then_ms` is later than `now_ms` (modulo the rollover) -- the
// clock-ordering race described above. Exposed so the units can count it.
constexpr bool IsFutureTimestamp(uint32_t now_ms, uint32_t then_ms) {
  return static_cast<uint32_t>(now_ms - then_ms) >= kFutureTimestampAgeMs;
}

// Milliseconds from `then_ms` to `now_ms`, rollover-safe, and 0 for a
// timestamp later than `now_ms`.
constexpr uint32_t ElapsedMs(uint32_t now_ms, uint32_t then_ms) {
  return IsFutureTimestamp(now_ms, then_ms)
             ? 0u
             : static_cast<uint32_t>(now_ms - then_ms);
}

}  // namespace control_core
