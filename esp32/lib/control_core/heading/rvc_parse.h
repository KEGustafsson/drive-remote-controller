#pragma once

// Pure C++17 parser for BNO08x UART-RVC frames. No Arduino.h, no
// HardwareSerial -- host-testable under env:native. The hardware glue
// (UART2 reading, feeding bytes in as they arrive) lives in
// src/rvc_reader.{h,cpp}.
//
// Frame layout (19 bytes):
//   [0]     0xAA (sync 1)
//   [1]     0xAA (sync 2)
//   [2]     index
//   [3-4]   yaw   int16 LSB-first, 0.01 deg units
//   [5-6]   pitch int16 LSB-first, 0.01 deg units
//   [7-8]   roll  int16 LSB-first, 0.01 deg units
//   [9-14]  accX/Y/Z int16 LSB-first, mg (unused downstream, part of frame)
//   [15-17] reserved
//   [18]    checksum = (sum of bytes[2..17]) mod 256

#include <cstddef>
#include <cstdint>

namespace control_core {

struct RvcSample {
  float yaw_deg = 0.0f;
  float pitch_deg = 0.0f;
  float roll_deg = 0.0f;
};

class RvcParser {
 public:
  // Feed one received byte at timestamp `now_ms`. Returns true and fills
  // `out` when a complete, checksum-valid frame was just parsed. On a
  // missing header or bad checksum, the parser slides its window by one
  // byte and keeps looking -- no explicit resync call is needed.
  bool ParseByte(uint8_t byte, uint32_t now_ms, RvcSample* out);

  // True once `timeout_ms` have elapsed since the last valid frame. If no
  // valid frame has EVER been received, this returns true immediately
  // (zero grace period) -- deliberate fail-safe: BNO health is unproven
  // until the first frame arrives, and unproven must read the same as
  // unhealthy, not healthy-by-default. Locked in by
  // test_timed_out_before_any_frame.
  //
  // A timeout is LATCHED: once the last valid frame has been found stale, it
  // is forgotten, and only a fresh valid frame can make the BNO read healthy
  // again. Same reason as LinkWatchdog::IsLive -- the unsigned age wraps after
  // 2^32 ms (~49.7 days), and without the latch a BNO silent that long would
  // read healthy for one timeout window, which is one FAULT->ARMED transition
  // the operator did not ask for. Polled every control tick, so the latch
  // lands within one timeout of the last frame. Not const for that reason.
  bool TimedOut(uint32_t now_ms, uint32_t timeout_ms);

 private:
  static constexpr size_t kFrameLen = 19;
  static constexpr size_t kChecksumStart = 2;
  static constexpr size_t kChecksumEnd = 18;  // exclusive

  uint8_t buffer_[kFrameLen] = {};
  size_t buffer_len_ = 0;

  uint32_t last_valid_ms_ = 0;
  bool has_valid_frame_ = false;
};

}  // namespace control_core
