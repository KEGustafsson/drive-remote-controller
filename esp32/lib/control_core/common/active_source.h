#pragma once

// Which command input is currently authoritative. Shared by BOTH machines'
// arbitration modules -- drive/arbitration.h (port/stbd drives) and
// heading/thruster_arbitration.h (bow thruster) -- because both resolve the
// same three-way question with the same precedence shape: an unconditional
// local input, then the physical TX station, then the plugin. Living in
// common/ keeps the two families from having to include each other just to
// name a source.
//
// Both machines publish this to Signal K as a string (`local`/`tx`/`plugin`/
// `none`) so an operator can always see WHICH station is in command of a given
// output -- the honesty rule that makes "my press did nothing" have a visible
// reason instead of looking like a bug.

#include <cstdint>

namespace control_core {

enum class ActiveSource : uint8_t {
  kNone,  // nothing is authoritative -> the output is at its safe value
  kLocal,
  kTx,
  kPlugin,
};

// Stable SK string form. Shared so RX and HH can never drift apart on the
// spelling of a path value RX/HH consumers switch on.
inline const char* ActiveSourceName(ActiveSource s) {
  switch (s) {
    case ActiveSource::kLocal:
      return "local";
    case ActiveSource::kTx:
      return "tx";
    case ActiveSource::kPlugin:
      return "plugin";
    case ActiveSource::kNone:
      return "none";
  }
  return "none";
}

}  // namespace control_core
