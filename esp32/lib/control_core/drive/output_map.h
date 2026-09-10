#pragma once

// Pure C++17, servo output mapping. No Arduino.h -- host-testable under
// env:native. Translates an arbitrated DrivePosition (arbitration.h) to
// a servo pulse width in microseconds. The
// calibration is a plain value struct (not compile-time constants) because
// MEASUREMENTS.md Item 1 made it explicitly live-tunable from the RX web
// UI (ConfigItem) rather than fixed -- config::kServo*UsDefault in
// config.h are only the starting values a ServoCalibration is constructed
// with.

#include <cstdint>

#include "drive/drive_command.h"

namespace control_core {

struct ServoCalibration {
  uint16_t forward_us;
  uint16_t neutral_us;
  uint16_t reverse_us;
};

constexpr uint16_t ServoPulseUs(DrivePosition pos,
                                 const ServoCalibration& cal) {
  switch (pos) {
    case DrivePosition::kForward:
      return cal.forward_us;
    case DrivePosition::kReverse:
      return cal.reverse_us;
    case DrivePosition::kNeutral:
    default:
      return cal.neutral_us;
  }
}

}  // namespace control_core
