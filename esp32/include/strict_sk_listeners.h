#pragma once

// Type-strict Signal K inbound listeners, shared by RX and HH.
//
// SensESP's SKValueListener<T>::parse_value() is one line --
// `emit(json["value"].as<T>())` -- and ArduinoJson's as<T>() is a COERCION,
// not a check. That is the wrong default for a command input on this boat:
//
//   * as<bool>() reads any string, object or array as TRUE. `"false"`,
//     `"off"`, `{}` and `[]` all arrive as `enabled = true` -- a station
//     publishing garbage would read as ARMED. That is the unsafe direction,
//     and SAFETY.md's boundary rule says unrecognised input must read as the
//     SAFE value, never the active one.
//   * as<float>() reads a string or an object as 0.0 and `true` as 1.0, so a
//     malformed heading arrives as a perfectly plausible 0 rad (due north) or
//     1 rad and is then treated as a fresh, trustworthy fix.
//
// Both listeners below DROP a delta whose value is not of the expected JSON
// type, rather than substituting a safe value. Dropping is deliberately the
// stronger answer: a dropped delta never feeds the path's LinkWatchdog, so a
// source publishing the wrong type goes STALE and arbitration treats it as
// absent -- fail to off / fail to neutral. Emitting a safe-looking `false` or
// `0` would instead keep the source looking alive and healthy while its data
// was nonsense.
//
// These are boundary types only; they make no decisions, so they live in
// include/ (on every env's include path) rather than in the pure core, which
// cannot see ArduinoJson or SensESP at all.

#include <ArduinoJson.h>

#include "sensesp/signalk/signalk_value_listener.h"

// Emits only for a real JSON boolean. A string, number, object, array or null
// is dropped.
class StrictBoolListener : public sensesp::SKValueListener<bool> {
 public:
  using sensesp::SKValueListener<bool>::SKValueListener;

  void parse_value(const JsonObject& json) override {
    auto value = json["value"];
    if (!value.is<bool>()) {
      return;
    }
    this->emit(value.as<bool>());
  }
};

// Emits only for a real JSON number (ArduinoJson's is<float>() is true for
// integers and floats, and for nothing else). A string, bool, object, array or
// null is dropped -- which also covers the explicit JSON null the UM982's SK
// plugin publishes to say "this heading is not trustworthy" (MEASUREMENTS.md
// Item 3): coerced, that null would become a fake but VALID 0-degree heading.
// A finite-value check is still the consumer's job -- JSON cannot carry NaN or
// Inf, but a huge exponent parses to Inf as a float.
class StrictFloatListener : public sensesp::SKValueListener<float> {
 public:
  using sensesp::SKValueListener<float>::SKValueListener;

  void parse_value(const JsonObject& json) override {
    auto value = json["value"];
    if (!value.is<float>()) {
      return;
    }
    this->emit(value.as<float>());
  }
};
