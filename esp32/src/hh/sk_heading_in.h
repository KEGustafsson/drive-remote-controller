#pragma once

// Signal K inbound heading subscription. Wraps SensESP's
// SKValueListener<float> on navigation.headingTrue and caches the latest
// sample as control_core::GnssHeading, so heading_filter and the
// control task never touch SensESP/WebSocket types directly --
// same pure/glue split as RvcReader around RvcParser.
//
// Thread-safety (SAFETY.md thruster invariant 5, never block): the SensESP WS client delivers
// deltas on the Arduino loop task; the control task reads this
// from its own pinned FreeRTOS task. `latest_` is protected by a mutex.
// The control task must never block on it -- latest() takes the mutex
// with a zero timeout, and on the (vanishingly rare) contended case just
// leaves *out unchanged and returns false so the caller reuses its last
// cycle's copy rather than stalling.

#include <memory>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "heading/heading_filter.h"  // control_core::GnssHeading
#include "sensesp/signalk/signalk_value_listener.h"

class SkHeadingIn {
 public:
  // Subscribes to config::kSkHeadingTruePath. Call once from setup().
  void begin();

  // Non-blocking. Returns true and refreshes *out if the mutex was free;
  // returns false (leaving *out untouched) if it was momentarily held by
  // the writer -- callers should just keep their previous copy and try
  // again next tick. out->valid indicates whether a real sample has EVER
  // arrived, independent of this call's own success. Staleness is the
  // caller's job (compare out->t_ms against now_ms).
  bool latest(control_core::GnssHeading* out) const;

 private:
  std::shared_ptr<sensesp::SKValueListener<float>> listener_;
  control_core::GnssHeading latest_{};
  SemaphoreHandle_t mutex_ = nullptr;
};
