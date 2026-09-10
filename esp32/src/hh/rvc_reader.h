#pragma once

// Hardware glue around control_core::RvcParser: feeds bytes from a
// HardwareSerial UART into the pure parser. See
// lib/control_core/rvc_parse.h for the frame format.

#include <HardwareSerial.h>

#include "heading/rvc_parse.h"

class RvcReader {
 public:
  // uart must already correspond to a free ESP32 UART; this only
  // configures it for RVC's fixed 115200 8N1, RX-only (BNO is TX-only).
  void begin(HardwareSerial& uart, int rx_pin);

  // Drains all bytes currently available and feeds them to the parser,
  // timestamping with the caller-supplied now_ms (not an internal
  // millis() call -- the caller's now_ms must be the same value it later
  // passes to timedOut(), or the two can drift enough for unsigned
  // subtraction in RvcParser::TimedOut to underflow into a false
  // timeout). Returns true and fills *out if a valid frame completed
  // during this call (if more than one frame arrived, *out holds the
  // most recent).
  bool poll(control_core::RvcSample* out, uint32_t now_ms);

  // True once no valid frame has arrived within config::kBnoRvcTimeoutMs.
  // Latches (see RvcParser::TimedOut), hence not const.
  bool timedOut(uint32_t now_ms);

 private:
  HardwareSerial* uart_ = nullptr;
  control_core::RvcParser parser_;
};
