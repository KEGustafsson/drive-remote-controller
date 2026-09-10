#pragma once

// Signal K inbound BOW-THRUSTER command subscription (ARCHITECTURE.md §6).
// The heading-hold unit's direct analogue of RX's src/rx/sk_command_in.h --
// deliberately the same shape, because the two units consume the same command
// contract and anyone who has read one should recognise the other.
//
// One instance per remote source (TX-via-SK, plugin-via-SK). Each subscribes
// to that source's four paths -- direction command, mode, commanded heading
// TRIM (relative, degrees), and the source's own enabled/ARMED flag -- and
// caches them as a control_core::ThrusterRemote for thruster_arbitration.h to
// consume.
//
// Thread-safety (SAFETY.md thruster invariant 5): the SensESP WS client delivers deltas on the
// Arduino loop task; the control task reads this from its own pinned FreeRTOS
// task. State is mutex-protected, and Snapshot() takes the mutex with a ZERO
// timeout -- on the (vanishingly rare) contended tick it leaves the output
// untouched and returns false, so the control task marks the source not-live
// rather than stalling. The control task must never block on the network.
//
// Liveness requires every path in the tuple to be recent. Signal K retains
// paths independently; one shared watchdog would permit a fresh heartbeat to
// combine with an old direction/mode/trim after a partial reconnect.
//
// That per-path rule is sufficient on its own, and is not narrowed by any
// "arrived close together" window -- a snapshot taken between the four
// listener callbacks is a torn read of a live source, not a dead one. See
// SkThrusterIn::Snapshot.

#include <memory>

#include "drive/link_watchdog.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "heading/thruster_arbitration.h"  // control_core::ThrusterRemote
#include "sensesp/signalk/signalk_value_listener.h"

class SkThrusterIn {
 public:
  // command_path/mode_path/trim_path/enabled_path: this source's full SK
  // paths (e.g. config::kSkTxThrusterCommandPath and friends). The enabled
  // path is the source's EXISTING drive enable/ARM flag -- one ARM covers both
  // machines, so there is no separate thruster-arm flag to publish.
  void begin(const char* command_path, const char* mode_path,
             const char* trim_path, const char* enabled_path,
             int listen_delay_ms);

  // Non-blocking; see the header note. now_ms/timeout_ms go straight to the
  // internal LinkWatchdog::IsLive(), which latches a stale source (so the
  // millis() wrap cannot revive it) -- hence not const.
  bool Snapshot(uint32_t now_ms, uint32_t timeout_ms,
                control_core::ThrusterRemote* out);

 private:
  std::shared_ptr<sensesp::SKValueListener<String>> command_listener_;
  std::shared_ptr<sensesp::SKValueListener<String>> mode_listener_;
  std::shared_ptr<sensesp::SKValueListener<float>> trim_listener_;
  std::shared_ptr<sensesp::SKValueListener<bool>> enabled_listener_;

  control_core::Cmd command_ = control_core::Cmd::kOff;
  control_core::ThrusterMode mode_ = control_core::ThrusterMode::kHold;
  float trim_deg_ = 0.0f;
  bool enabled_ = false;
  control_core::LinkWatchdog command_watchdog_;
  control_core::LinkWatchdog mode_watchdog_;
  control_core::LinkWatchdog trim_watchdog_;
  control_core::LinkWatchdog enabled_watchdog_;
  SemaphoreHandle_t mutex_ = nullptr;
};
