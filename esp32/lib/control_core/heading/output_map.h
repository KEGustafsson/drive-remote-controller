#pragma once

// Pure C++17 decision logic for the thruster output driver. No Arduino.h
// -- host-testable under env:native.
//
// SAFETY-CRITICAL: this is where SAFETY.md thruster invariants 1-2 are structurally
// enforced -- ComputeOutputLevels() cannot express {port=1, stbd=1}, and
// forces both direction lines low whenever !armed, regardless of `cmd`.
// src/outputs.cpp is a thin Arduino wrapper that calls this and writes the
// three GPIOs (applying the polarity flag); it adds no logic of its own.

#include "heading/switcher.h"  // control_core::Cmd

namespace control_core {

// Levels are in the *logical* (active-high) sense -- true means "assert".
// src/outputs.cpp maps these to electrical levels via the polarity flag.
struct OutputLevels {
  bool enable = false;
  bool port = false;
  bool stbd = false;
};

inline OutputLevels ComputeOutputLevels(bool armed, Cmd cmd) {
  if (!armed) {
    return OutputLevels{false, false, false};
  }
  switch (cmd) {
    case Cmd::kPort:
      return OutputLevels{true, true, false};
    case Cmd::kStbd:
      return OutputLevels{true, false, true};
    case Cmd::kOff:
      return OutputLevels{true, false, false};
  }
  // Unreachable via any named Cmd value (the switch above is exhaustive
  // over the enum's 3 enumerators) -- only reachable via a corrupted/
  // out-of-range `cmd` (bad cast, memory corruption, uninitialized read).
  // Fail off completely rather than leaving ENABLE asserted: we don't know
  // what was actually intended, so don't energize the thruster at all.
  return OutputLevels{false, false, false};
}

}  // namespace control_core
