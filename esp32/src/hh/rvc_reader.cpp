#include "rvc_reader.h"

#include "config.h"

void RvcReader::begin(HardwareSerial& uart, int rx_pin) {
  uart_ = &uart;
  // RVC is BNO-TX-only -> ESP-RX-only; pass -1 for the (unused) TX pin.
  uart_->begin(115200, SERIAL_8N1, rx_pin, -1);
}

bool RvcReader::poll(control_core::RvcSample* out, uint32_t now_ms) {
  bool got_frame = false;
  while (uart_->available() > 0) {
    uint8_t byte = static_cast<uint8_t>(uart_->read());
    if (parser_.ParseByte(byte, now_ms, out)) {
      got_frame = true;
    }
  }
  return got_frame;
}

bool RvcReader::timedOut(uint32_t now_ms) {
  return parser_.TimedOut(now_ms, config::kBnoRvcTimeoutMs);
}
