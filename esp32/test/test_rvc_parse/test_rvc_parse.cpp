#include <unity.h>

#include <array>
#include <cstdint>

#include "heading/rvc_parse.h"

using control_core::RvcParser;
using control_core::RvcSample;

namespace {

// Builds a valid 19-byte RVC frame for the given angles (in 0.01 deg raw
// units) with acc/reserved bytes fixed at zero and a correct checksum.
std::array<uint8_t, 19> BuildFrame(int16_t yaw_raw, int16_t pitch_raw,
                                    int16_t roll_raw) {
  std::array<uint8_t, 19> frame{};
  frame[0] = 0xAA;
  frame[1] = 0xAA;
  frame[2] = 0x00;  // index
  frame[3] = static_cast<uint8_t>(yaw_raw & 0xFF);
  frame[4] = static_cast<uint8_t>((yaw_raw >> 8) & 0xFF);
  frame[5] = static_cast<uint8_t>(pitch_raw & 0xFF);
  frame[6] = static_cast<uint8_t>((pitch_raw >> 8) & 0xFF);
  frame[7] = static_cast<uint8_t>(roll_raw & 0xFF);
  frame[8] = static_cast<uint8_t>((roll_raw >> 8) & 0xFF);
  // frame[9..17] (accX/Y/Z + reserved) left at zero.
  uint8_t sum = 0;
  for (size_t i = 2; i < 18; ++i) sum = static_cast<uint8_t>(sum + frame[i]);
  frame[18] = sum;
  return frame;
}

// Feeds every byte of `frame` at time `now_ms`; returns whether the final
// byte produced a valid parsed sample.
bool FeedFrame(RvcParser* parser, const std::array<uint8_t, 19>& frame,
               uint32_t now_ms, RvcSample* out) {
  bool result = false;
  for (uint8_t byte : frame) {
    result = parser->ParseByte(byte, now_ms, out);
  }
  return result;
}

}  // namespace

void setUp() {}
void tearDown() {}

// ==========================================================================
// Valid frame decoding
// ==========================================================================

void test_valid_frame_decodes_angles() {
  RvcParser parser;
  auto frame = BuildFrame(9000, -1500, 250);  // 90.00, -15.00, 2.50 deg
  RvcSample sample;
  TEST_ASSERT_TRUE(FeedFrame(&parser, frame, 100, &sample));
  TEST_ASSERT_FLOAT_WITHIN(0.001, 90.0f, sample.yaw_deg);
  TEST_ASSERT_FLOAT_WITHIN(0.001, -15.0f, sample.pitch_deg);
  TEST_ASSERT_FLOAT_WITHIN(0.001, 2.5f, sample.roll_deg);
}

void test_negative_yaw_sign_and_endianness() {
  RvcParser parser;
  auto frame = BuildFrame(-9000, 0, 0);  // -90.00 deg
  RvcSample sample;
  TEST_ASSERT_TRUE(FeedFrame(&parser, frame, 100, &sample));
  TEST_ASSERT_FLOAT_WITHIN(0.001, -90.0f, sample.yaw_deg);
}

void test_yaw_wraps_at_plus_180() {
  RvcParser parser;
  auto frame = BuildFrame(18000, 0, 0);  // +180.00 deg
  RvcSample sample;
  TEST_ASSERT_TRUE(FeedFrame(&parser, frame, 100, &sample));
  TEST_ASSERT_FLOAT_WITHIN(0.001, 180.0f, sample.yaw_deg);
}

void test_yaw_wraps_at_minus_180() {
  RvcParser parser;
  auto frame = BuildFrame(-18000, 0, 0);  // -180.00 deg
  RvcSample sample;
  TEST_ASSERT_TRUE(FeedFrame(&parser, frame, 100, &sample));
  TEST_ASSERT_FLOAT_WITHIN(0.001, -180.0f, sample.yaw_deg);
}

// ==========================================================================
// Checksum / resync
// ==========================================================================

void test_bad_checksum_rejected() {
  RvcParser parser;
  auto frame = BuildFrame(4500, 0, 0);
  frame[18] = static_cast<uint8_t>(frame[18] + 1);  // corrupt checksum
  RvcSample sample;
  TEST_ASSERT_FALSE(FeedFrame(&parser, frame, 100, &sample));
}

void test_resyncs_after_bad_frame() {
  RvcParser parser;
  RvcSample sample;

  auto bad_frame = BuildFrame(1000, 0, 0);
  bad_frame[18] = static_cast<uint8_t>(bad_frame[18] + 1);
  TEST_ASSERT_FALSE(FeedFrame(&parser, bad_frame, 100, &sample));

  auto good_frame = BuildFrame(4500, 0, 0);
  TEST_ASSERT_TRUE(FeedFrame(&parser, good_frame, 200, &sample));
  TEST_ASSERT_FLOAT_WITHIN(0.001, 45.0f, sample.yaw_deg);
}

void test_resyncs_after_misalignment() {
  RvcParser parser;
  RvcSample sample;

  // Junk bytes, including a lone 0xAA that must not false-trigger sync.
  const uint8_t junk[] = {0x01, 0x02, 0xAA, 0x03, 0x04, 0x05};
  bool triggered = false;
  for (uint8_t byte : junk) {
    if (parser.ParseByte(byte, 50, &sample)) triggered = true;
  }
  TEST_ASSERT_FALSE(triggered);

  auto frame = BuildFrame(3000, 0, 0);
  TEST_ASSERT_TRUE(FeedFrame(&parser, frame, 150, &sample));
  TEST_ASSERT_FLOAT_WITHIN(0.001, 30.0f, sample.yaw_deg);
}

void test_out_of_range_yaw_rejected() {
  RvcParser parser;
  // 180.01 deg -- one raw unit past the documented ±18000 domain.
  // A checksum collision on corrupted bytes could otherwise produce this.
  auto frame = BuildFrame(18001, 0, 0);
  RvcSample sample;
  TEST_ASSERT_FALSE(FeedFrame(&parser, frame, 100, &sample));
  // Must resync afterward, same as a bad-checksum frame.
  TEST_ASSERT_TRUE(parser.TimedOut(100, 200));  // no valid frame accepted yet
  auto good_frame = BuildFrame(4500, 0, 0);
  TEST_ASSERT_TRUE(FeedFrame(&parser, good_frame, 200, &sample));
  TEST_ASSERT_FLOAT_WITHIN(0.001, 45.0f, sample.yaw_deg);
}

// ==========================================================================
// Timeout
// ==========================================================================

void test_timed_out_before_any_frame() {
  RvcParser parser;
  TEST_ASSERT_TRUE(parser.TimedOut(0, 200));
}

void test_not_timed_out_soon_after_valid_frame() {
  RvcParser parser;
  RvcSample sample;
  auto frame = BuildFrame(0, 0, 0);
  FeedFrame(&parser, frame, 1000, &sample);
  TEST_ASSERT_FALSE(parser.TimedOut(1100, 200));
}

void test_times_out_after_silence() {
  RvcParser parser;
  RvcSample sample;
  auto frame = BuildFrame(0, 0, 0);
  FeedFrame(&parser, frame, 1000, &sample);
  TEST_ASSERT_TRUE(parser.TimedOut(1250, 200));
}

// The unsigned age wraps every 2^32 ms (~49.7 days). A BNO silent that long
// must not read healthy for one timeout window when the wrap brings its age
// back to zero -- that would be a FAULT -> ARMED transition nobody asked for.
// A timeout, once found, is latched until a fresh valid frame arrives.
void test_timeout_is_latched_across_the_clock_wrap() {
  RvcParser parser;
  RvcSample sample;
  auto frame = BuildFrame(0, 0, 0);
  FeedFrame(&parser, frame, 1000, &sample);
  TEST_ASSERT_TRUE(parser.TimedOut(1250, 200));  // stale: latched
  // The clock has wrapped round to the frame's own timestamp: the age would
  // read 0 without the latch.
  TEST_ASSERT_TRUE(parser.TimedOut(1000, 200));
  TEST_ASSERT_TRUE(parser.TimedOut(1100, 200));
  // A fresh valid frame makes it healthy again immediately.
  TEST_ASSERT_TRUE(FeedFrame(&parser, frame, 1100, &sample));
  TEST_ASSERT_FALSE(parser.TimedOut(1200, 200));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_valid_frame_decodes_angles);
  RUN_TEST(test_negative_yaw_sign_and_endianness);
  RUN_TEST(test_yaw_wraps_at_plus_180);
  RUN_TEST(test_yaw_wraps_at_minus_180);
  RUN_TEST(test_bad_checksum_rejected);
  RUN_TEST(test_out_of_range_yaw_rejected);
  RUN_TEST(test_resyncs_after_bad_frame);
  RUN_TEST(test_resyncs_after_misalignment);
  RUN_TEST(test_timed_out_before_any_frame);
  RUN_TEST(test_not_timed_out_soon_after_valid_frame);
  RUN_TEST(test_times_out_after_silence);
  RUN_TEST(test_timeout_is_latched_across_the_clock_wrap);
  return UNITY_END();
}
