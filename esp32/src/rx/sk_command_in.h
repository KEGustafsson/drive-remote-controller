#pragma once

// Signal K inbound command subscription (ARCHITECTURE.md §9). One
// instance per remote source (TX-via-SK, plugin-via-SK): subscribes to
// that source's port command, stbd command, and enabled paths, and caches
// the latest values as control_core::RemoteSource for arbitration.h to
// consume -- same pure/glue split as heading-hold's SkHeadingIn
// around HeadingFilter's GnssHeading.
//
// Thread-safety (SAFETY.md drive invariant 6, never block): the SensESP WS client delivers
// deltas on the Arduino loop task; the control task reads this
// from its own pinned FreeRTOS task. State is protected by a mutex, and
// Snapshot() takes it with a zero timeout -- on the (vanishingly rare)
// contended case it just leaves the outputs unchanged and returns false,
// so the control task reuses its previous cycle's copy rather than
// stalling. The control task must never block on this.
//
// Liveness is deliberately stricter than "any path arrived": every member of
// the command tuple must have arrived recently. Signal K retains each path
// independently, so accepting one fresh member alongside two retained members
// could otherwise resurrect an old command after a publisher restart.
//
// Per-path freshness is the ENTIRE rule. There is no additional "and they
// arrived within N ms of each other" window: the listeners update under
// separate mutex acquisitions, so a snapshot between them sees a torn read of
// a healthy publisher, and failing that read invents a link loss instead of
// detecting one. See SkCommandIn::Snapshot.

#include <memory>

#include "drive/arbitration.h"  // control_core::RemoteSource
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "drive/drive_command.h"  // control_core::DrivePosition
#include "drive/link_watchdog.h"
#include "sensesp/signalk/signalk_value_listener.h"

class SkCommandIn {
 public:
  // port_path/stbd_path/enabled_path: full SK paths for this source, e.g.
  // config::kSkTxPortCommandPath etc. Call once from setup(), one instance
  // per remote source.
  void begin(const char* port_path, const char* stbd_path,
             const char* enabled_path, int listen_delay_ms);

  // Non-blocking. Fills *port_out and *stbd_out (both built from the same
  // cached enabled/liveness state, per-drive command) and returns true
  // if the mutex was free; returns false (leaving both untouched) if it
  // was momentarily held by a listener callback -- caller must treat the
  // source as not-live for that cycle. now_ms/timeout_ms govern each field.
  //
  // Not const: judging liveness latches a stale source as never-updated
  // (LinkWatchdog::IsLive), so the millis() wrap ~49.7 days into a silence
  // cannot briefly resurrect its last retained command.
  bool Snapshot(uint32_t now_ms, uint32_t timeout_ms,
                control_core::RemoteSource* port_out,
                control_core::RemoteSource* stbd_out);

 private:
  std::shared_ptr<sensesp::SKValueListener<String>> port_listener_;
  std::shared_ptr<sensesp::SKValueListener<String>> stbd_listener_;
  std::shared_ptr<sensesp::SKValueListener<bool>> enabled_listener_;

  control_core::DrivePosition port_ = control_core::DrivePosition::kNeutral;
  control_core::DrivePosition stbd_ = control_core::DrivePosition::kNeutral;
  bool enabled_ = false;
  control_core::LinkWatchdog port_watchdog_;
  control_core::LinkWatchdog stbd_watchdog_;
  control_core::LinkWatchdog enabled_watchdog_;
  SemaphoreHandle_t mutex_ = nullptr;
};
