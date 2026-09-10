#include <unity.h>

#include "drive/output_map.h"

using control_core::DrivePosition;
using control_core::ServoCalibration;
using control_core::ServoPulseUs;

void setUp() {}
void tearDown() {}

namespace {
constexpr ServoCalibration kDefaultCal{/*forward_us=*/1000,
                                        /*neutral_us=*/1500,
                                        /*reverse_us=*/2000};
}  // namespace

void test_forward_maps_to_forward_us() {
  TEST_ASSERT_EQUAL_UINT16(1000,
                            ServoPulseUs(DrivePosition::kForward, kDefaultCal));
}

void test_neutral_maps_to_neutral_us() {
  TEST_ASSERT_EQUAL_UINT16(1500,
                            ServoPulseUs(DrivePosition::kNeutral, kDefaultCal));
}

void test_reverse_maps_to_reverse_us() {
  TEST_ASSERT_EQUAL_UINT16(2000,
                            ServoPulseUs(DrivePosition::kReverse, kDefaultCal));
}

// Per-side calibration is independent -- port and stbd can be trimmed
// apart (ARCHITECTURE.md §11) without affecting each other.
void test_independent_calibrations() {
  ServoCalibration port_cal{900, 1450, 1950};
  ServoCalibration stbd_cal{1050, 1550, 2050};
  TEST_ASSERT_EQUAL_UINT16(900,
                            ServoPulseUs(DrivePosition::kForward, port_cal));
  TEST_ASSERT_EQUAL_UINT16(
      1050, ServoPulseUs(DrivePosition::kForward, stbd_cal));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_forward_maps_to_forward_us);
  RUN_TEST(test_neutral_maps_to_neutral_us);
  RUN_TEST(test_reverse_maps_to_reverse_us);
  RUN_TEST(test_independent_calibrations);
  return UNITY_END();
}
