#include <unity.h>

#include <initializer_list>

#include "drive/drive_command.h"

using control_core::FromSkString;
using control_core::FromSwitch;
using control_core::DrivePosition;
using control_core::ToSkString;

void setUp() {}
void tearDown() {}

// ---- FromSwitch ----

void test_from_switch_forward_only() {
  TEST_ASSERT_TRUE(FromSwitch(true, false) == DrivePosition::kForward);
}

void test_from_switch_reverse_only() {
  TEST_ASSERT_TRUE(FromSwitch(false, true) == DrivePosition::kReverse);
}

void test_from_switch_neither_is_neutral() {
  TEST_ASSERT_TRUE(FromSwitch(false, false) == DrivePosition::kNeutral);
}

// SAFETY.md drive invariant 1, defense-in-depth: a glitched/miswired switch that
// reads both contacts active must fail to neutral, never pick a direction.
void test_from_switch_both_active_is_neutral_not_a_direction() {
  TEST_ASSERT_TRUE(FromSwitch(true, true) == DrivePosition::kNeutral);
}

// ---- ToSkString ----

void test_to_sk_string() {
  TEST_ASSERT_EQUAL_STRING("forward", ToSkString(DrivePosition::kForward));
  TEST_ASSERT_EQUAL_STRING("reverse", ToSkString(DrivePosition::kReverse));
  TEST_ASSERT_EQUAL_STRING("neutral", ToSkString(DrivePosition::kNeutral));
}

// ---- FromSkString ----

void test_from_sk_string_valid_values() {
  TEST_ASSERT_TRUE(FromSkString("forward") == DrivePosition::kForward);
  TEST_ASSERT_TRUE(FromSkString("reverse") == DrivePosition::kReverse);
  TEST_ASSERT_TRUE(FromSkString("neutral") == DrivePosition::kNeutral);
}

// Fail-safe default: null, empty, and garbage/unexpected values must all
// map to kNeutral, never silently produce a direction.
void test_from_sk_string_null_is_neutral() {
  TEST_ASSERT_TRUE(FromSkString(nullptr) == DrivePosition::kNeutral);
}

void test_from_sk_string_empty_is_neutral() {
  TEST_ASSERT_TRUE(FromSkString("") == DrivePosition::kNeutral);
}

void test_from_sk_string_garbage_is_neutral() {
  TEST_ASSERT_TRUE(FromSkString("FORWARD") == DrivePosition::kNeutral);
  TEST_ASSERT_TRUE(FromSkString("fwd") == DrivePosition::kNeutral);
  TEST_ASSERT_TRUE(FromSkString("garbage") == DrivePosition::kNeutral);
}

// ---- Round trip ----

void test_round_trip_all_positions() {
  for (DrivePosition pos : {DrivePosition::kForward,
                               DrivePosition::kReverse,
                               DrivePosition::kNeutral}) {
    TEST_ASSERT_TRUE(FromSkString(ToSkString(pos)) == pos);
  }
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_from_switch_forward_only);
  RUN_TEST(test_from_switch_reverse_only);
  RUN_TEST(test_from_switch_neither_is_neutral);
  RUN_TEST(test_from_switch_both_active_is_neutral_not_a_direction);
  RUN_TEST(test_to_sk_string);
  RUN_TEST(test_from_sk_string_valid_values);
  RUN_TEST(test_from_sk_string_null_is_neutral);
  RUN_TEST(test_from_sk_string_empty_is_neutral);
  RUN_TEST(test_from_sk_string_garbage_is_neutral);
  RUN_TEST(test_round_trip_all_positions);
  return UNITY_END();
}
