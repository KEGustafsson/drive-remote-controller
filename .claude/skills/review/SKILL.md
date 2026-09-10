---
name: review
description: >
  Review firmware code for correctness, hardware compatibility, and best
  practices. Use when user says "review", "check my code", or before
  finalizing a project.
argument-hint: "[optional: tx, rx, hh, plugin, android, or a specific concern]"
---

# Firmware Code Review

Review the project's firmware for correctness, hardware compatibility, and SensESP best practices.

## Phase 0: Scope

- Identify what to review: one firmware (`esp32/src/tx|rx|hh/`), the shared pure
  core (`esp32/lib/control_core/`), the plugin (`sk-plugin/`), or the Android
  station (`android/`). Ask if it was not given.
- Read [SAFETY.md](../../../docs/SAFETY.md) first. It holds the invariants, and
  several of them look like removable complexity and are not.
- Read [ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) for what the code is
  meant to do, and `esp32/platformio.ini` for the build configuration.
- Read [AGENTS.md](../../../AGENTS.md) for the rules this codebase is held to.

## Phase 1: Review Perspectives

Evaluate the code from each of these perspectives. Only report genuine issues -- don't flag things that aren't problems.

### Correctness
- Logic errors, off-by-one, uninitialized variables
- Interrupt safety (shared state accessed from ISRs)
- Memory leaks or unbounded allocations
- Error handling for sensor communication failures
- Does the code match what ARCHITECTURE.md describes, and does it hold every
  invariant in SAFETY.md?

### Hardware Compatibility
- Pin numbers match `esp32/include/config.h`, which is the only place any pin,
  path or tunable is allowed to live. All three units are Hat Labs SH-ESP32.
- GPIO 34–39 have no internal pull-up or pull-down, so `INPUT_PULLDOWN` is a
  silent no-op there.
- No pin conflicts (same pin used for two purposes)
- Voltage and current within board limits
- I2C addresses don't collide
- Partition table appropriate for flash size
- Build flags correct for the target MCU

### SensESP Patterns
- Correct use of SensESPAppBuilder
- Proper sensor → transform → output pipelines
- Signal K paths come from `esp32/include/config.h`, and are hand-synced into
  `sk-plugin/src/config.ts` and `android/core/.../SkContract.kt`. A path changed
  in one place and not the others builds clean and silently stops commanding.
- WiFi/connectivity handled by framework, not custom code
- No hardcoded WiFi credentials

### Power and Performance
- No busy-wait loops (use ReactESP timers instead)
- Sensor polling intervals are reasonable (not too fast)
- WiFi traffic not excessive
- RAM usage within limits (ESP32 has ~320KB usable)

## Phase 2: Synthesize

Group findings by severity:

- **Must fix**: Will cause crashes, hardware damage, or an unsafe output — a
  machine that moves when it should not, or that cannot be stopped. Anything
  that breaks a SAFETY.md invariant belongs here regardless of how unlikely it
  looks.
- **Should fix**: Works but incorrectly, or has a reliability problem that will surface eventually.
- **Suggestions**: Style improvements, minor optimizations, better Signal K path naming.

Present findings in plain language. For each issue, explain what's wrong, why it matters, and what the fix looks like.

## Phase 3: Fix

Offer to fix all "must fix" and "should fix" issues. Apply fixes, then re-read the code to verify nothing was introduced. Commit the fixes.

Re-run the suite that covers what changed before claiming anything is fixed:
`cd esp32 && pio test -e native`, `cd sk-plugin && npm test`, `cd android &&
./gradlew :core:test`, and `:app:testDebugUnitTest` for a control-layout change.
A change is not done until the relevant suite passes.

Then suggest next steps, without overstating what a green suite means:
- "Want to flash it to a board?" (→ build-flash) — and note that only SAFETY.md's
  commissioning checklists prove an output is safe.
- "Anything else you'd like to change?"
