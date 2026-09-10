#include "heading/rvc_parse.h"

namespace control_core {

bool RvcParser::ParseByte(uint8_t byte, uint32_t now_ms, RvcSample* out) {
  if (buffer_len_ == kFrameLen) {
    // Already holding a full window that didn't check out (bad header or
    // checksum) -- slide by one byte and re-test.
    for (size_t i = 0; i < kFrameLen - 1; ++i) {
      buffer_[i] = buffer_[i + 1];
    }
    buffer_[kFrameLen - 1] = byte;
  } else {
    buffer_[buffer_len_++] = byte;
    if (buffer_len_ < kFrameLen) {
      return false;
    }
  }

  if (buffer_[0] != 0xAA || buffer_[1] != 0xAA) {
    return false;
  }

  uint8_t sum = 0;
  for (size_t i = kChecksumStart; i < kChecksumEnd; ++i) {
    sum = static_cast<uint8_t>(sum + buffer_[i]);
  }
  if (sum != buffer_[kFrameLen - 1]) {
    return false;
  }

  auto decode_raw = [this](size_t lo_index) -> int16_t {
    return static_cast<int16_t>(
        static_cast<uint16_t>(buffer_[lo_index]) |
        (static_cast<uint16_t>(buffer_[lo_index + 1]) << 8));
  };

  int16_t raw_yaw = decode_raw(3);
  // Frame spec: yaw in raw units is bounded to ±18000
  // (0.01 deg/LSB -> ±180.00 deg). The 1-byte checksum only has 256
  // possible values, so a corrupted UART frame can collide and still look
  // "valid-looking" -- reject an out-of-domain yaw exactly like a bad
  // checksum (slide the window one byte, don't touch last_valid_ms_ /
  // has_valid_frame_) rather than accepting nonsense as proof the BNO is
  // healthy.
  if (raw_yaw < -18000 || raw_yaw > 18000) {
    return false;
  }

  out->yaw_deg = raw_yaw * 0.01f;
  out->pitch_deg = decode_raw(5) * 0.01f;
  out->roll_deg = decode_raw(7) * 0.01f;

  last_valid_ms_ = now_ms;
  has_valid_frame_ = true;
  buffer_len_ = 0;  // frames are contiguous; start the next one fresh
  return true;
}

bool RvcParser::TimedOut(uint32_t now_ms, uint32_t timeout_ms) {
  if (!has_valid_frame_) {
    return true;
  }
  if ((now_ms - last_valid_ms_) < timeout_ms) {
    return false;
  }
  has_valid_frame_ = false;  // stale: latch, so a clock wrap cannot revive it
  return true;
}

}  // namespace control_core
