#include "outputs.h"

#include <Arduino.h>
#include <driver/gpio.h>

#include "config.h"
#include "heading/output_map.h"

namespace {

// gpio_set_level(), NOT digitalWrite(). Two independent reasons, both of which
// this class actually hits:
//
//  1. arduino-esp32 3.x gates digitalWrite() behind its peripheral manager:
//     on a pin that has not yet had pinMode() called it writes NOTHING and
//     only emits log_e("IO %u is not set as GPIO") (esp32-hal-gpio.c). That
//     would silently defeat the level-before-driver latch in begin().
//
//  2. Every write in this class runs inside portENTER_CRITICAL, i.e. with
//     interrupts disabled. The log path above ends in fwrite() -> the newlib
//     stdio lock -> lock_acquire_generic(), which abort()s when it cannot
//     yield. That is not hypothetical: it panic-looped HH at boot.
//
//  gpio_set_level() writes the output register directly, takes no lock, logs
//  nothing for a valid output pin, and is IRAM-safe -- so it is correct both
//  before the driver is enabled and inside a critical section.
void WritePin(uint8_t pin, bool logical_high) {
  bool electrical_high = config::kOutputActiveHigh ? logical_high : !logical_high;
  gpio_set_level(static_cast<gpio_num_t>(pin), electrical_high ? 1 : 0);
}

}  // namespace

void Outputs::begin() {
  armed_ = false;
  dir_ = control_core::Cmd::kOff;

  // Latch the safe levels into the GPIO output register BEFORE enabling the
  // drivers, so pinMode(OUTPUT) starts driving the level already chosen. The
  // other order enables the driver at the pin's reset default (LOW) first,
  // which is only ACCIDENTALLY the safe level: with config::kOutputActiveHigh
  // false, LOW is assert, and that window would pulse a thruster contactor on
  // every boot. The flag exists precisely because the output stage's polarity
  // is not to be trusted sight-unseen (config.h), so this must not depend on
  // which way it is set.
  //
  // The write survives the pinMode() that follows: __pinMode() only calls
  // gpio_config(), which sets direction, pulls and IO_MUX function and leaves
  // the output data register alone (esp32-hal-gpio.c). See WritePin above for
  // why this cannot be digitalWrite().
  portENTER_CRITICAL(&mux_);
  ApplyLocked();
  portEXIT_CRITICAL(&mux_);

  pinMode(config::kThrusterEnablePin, OUTPUT);
  pinMode(config::kThrusterPortPin, OUTPUT);
  pinMode(config::kThrusterStbdPin, OUTPUT);

  // Re-assert with the drivers live, so none of the above rests on an
  // assumption about what pinMode() does to the output register.
  portENTER_CRITICAL(&mux_);
  ApplyLocked();
  portEXIT_CRITICAL(&mux_);
}

void Outputs::apply(bool armed, control_core::Cmd dir) {
  portENTER_CRITICAL(&mux_);
  armed_ = armed;
  dir_ = armed ? dir : control_core::Cmd::kOff;
  ApplyLocked();
  portEXIT_CRITICAL(&mux_);
}

void Outputs::allOff() {
  portENTER_CRITICAL(&mux_);
  armed_ = false;
  dir_ = control_core::Cmd::kOff;
  ApplyLocked();
  portEXIT_CRITICAL(&mux_);
}

// Callers hold mux_, so interrupts are disabled here: nothing in this function
// or below it may log, allocate, or take a lock. See WritePin.
void Outputs::ApplyLocked() {
  control_core::OutputLevels levels = control_core::ComputeOutputLevels(armed_, dir_);
  WritePin(config::kThrusterEnablePin, levels.enable);
  WritePin(config::kThrusterPortPin, levels.port);
  WritePin(config::kThrusterStbdPin, levels.stbd);
}
