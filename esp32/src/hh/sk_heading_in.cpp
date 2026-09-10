#include "sk_heading_in.h"

#include <Arduino.h>

#include <cmath>

#include "heading/angle_math.h"  // control_core::WrapDeg180
#include "config.h"
#include "sensesp/system/lambda_consumer.h"

namespace {

// MEASUREMENTS.md Item 3: the UM982's Signal K plugin (signalk-um982-plugin,
// uniheadingAParser) already judges RTK quality at the source -- it sets
// navigation.headingTrue to JSON null whenever solutionStatus != SOL_COMPUTED
// or positionType == NONE, rather than publishing an untrustworthy heading.
// SKValueListener<float>'s default parse_value does json["value"].as<float>(),
// and ArduinoJson coerces a null JsonVariant to 0.0f -- silently turning the
// plugin's explicit "don't trust this" into a fake, VALID 0-degree heading
// that would then be treated as fresh and (on the first fix) seeded directly
// into HeadingFilter. Override parse_value to drop null deltas instead of
// emitting a bogus 0.
class HeadingTrueListener : public sensesp::SKValueListener<float> {
 public:
  using sensesp::SKValueListener<float>::SKValueListener;

  void parse_value(const JsonObject& json) override {
    auto value = json["value"];
    if (value.isNull()) {
      return;
    }
    this->emit(value.as<float>());
  }
};

}  // namespace

void SkHeadingIn::begin() {
  mutex_ = xSemaphoreCreateMutex();

  listener_ = std::make_shared<HeadingTrueListener>(
      config::kSkHeadingTruePath, config::kSkHeadingListenDelayMs);
  // Signal K serves navigation.headingTrue in RADIANS (per the SK spec).
  // The pure control core works in DEGREES throughout (CLAUDE.md coding
  // convention: convert to radians only at the SK boundary). This inbound
  // WS callback IS that boundary -- convert rad->deg here and wrap to the
  // canonical (-180, 180]. Skipping this made the fused heading track the
  // radian number (~1.5 "deg") instead of the real ~86 deg -- caught once
  // live UM982 data was flowing, not in host tests.
  listener_->connect_to(new sensesp::LambdaConsumer<float>([this](float heading_rad) {
    // Boundary validation (SAFETY.md: untrusted input is screened where it
    // enters): a non-finite heading is dropped exactly like the null case
    // above. HeadingFilter::Correct also rejects non-finite defensively,
    // but it must never be REACHED by one -- NaN would propagate through
    // latest_ and Inf, multiplied up, is exactly the magnitude of garbage
    // this gate exists to stop.
    if (!std::isfinite(heading_rad)) {
      return;
    }
    if (mutex_ == nullptr) {
      return;  // creation failed at begin(); stay not-valid, never crash
    }
    xSemaphoreTake(mutex_, portMAX_DELAY);
    latest_.heading_deg =
        control_core::WrapDeg180(heading_rad * 57.29577951308232f);
    latest_.t_ms = millis();
    latest_.valid = true;
    xSemaphoreGive(mutex_);
  }));
}

bool SkHeadingIn::latest(control_core::GnssHeading* out) const {
  if (mutex_ == nullptr) {
    return false;  // creation failed at begin(); heading reads not-valid
  }
  if (xSemaphoreTake(mutex_, 0) != pdTRUE) {
    return false;
  }
  *out = latest_;
  xSemaphoreGive(mutex_);
  return true;
}
