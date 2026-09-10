# AGENTS.md — working rules for this project

> This file is the working contract for the repository: the SensESP idioms it
> needs are in *SensESP v3 API rules* below, and everything else is the
> actuator-control discipline that framework guidance does not encode.

**Read [SAFETY.md](docs/SAFETY.md) before changing anything that reaches an output.**
[ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the system is built;
[MEASUREMENTS.md](docs/MEASUREMENTS.md) holds the measured values.

## What this is

Three SH-ESP32 firmwares plus a Signal K plugin, controlling **two independent
machines**: the port/starboard drives (TX + RX) and the bow thruster (HH). They
share this repository, `esp32/include/config.h`, the pure core, and their command
stations — but no hardware, no outputs, and **no safety rules**.

| Firmware | env | src |
|---|---|---|
| TX — Handheld Controller | `tx_shesp32` | `esp32/src/tx/` |
| RX — Motion Controller | `rx_shesp32` | `esp32/src/rx/` |
| HH — Heading Hold Controller | `hh_shesp32` | `esp32/src/hh/` |
| Plugin Controller | npm | `sk-plugin/` |
| Android Controller | Gradle | `android/` |

## Non-negotiables

The full invariant lists are in SAFETY.md. The ones most often mistaken for
removable complexity:

1. **The drives have no dwell; the thruster's HOLD dwell is mandatory and its
   MANUAL dwell is deliberately zero.** Three different answers, all on
   purpose — do not harmonise them. HOLD's 1.85 s is the measured control-box
   interlock and has a `static_assert` floor; MANUAL's 0.00 s is an owner
   decision (a human is watching, and the box enforces its own interlock
   regardless). What both thruster gates share is the last-thrust history the
   dwell is measured against, so a mode change cannot bypass either value.
   SAFETY.md thruster invariant 7 has the full reasoning.
2. **Liveness is time-since-last-update, and is judged on message *arrival*,
   never on a message's value.** Signal K retains a path's last value forever, so
   a published value cannot reveal its own publisher's absence.
3. **Station precedence is fixed (local > TX > plugin), never recency-based.**
   A recency tie-break oscillates the output when two stations disagree.
4. **A remote command must enter through the same safety gate as a local one.**
   Never add a path that reaches an output without passing the FSM/arbitration.
5. **Fail to the safe value** — neutral for drives, off for the thruster — never
   to the last commanded value.
6. **Never present unconfirmable data as live.** Degrade the indication instead.

If a task would require breaking one of these, stop and flag it.

## Where code goes

- **`esp32/lib/control_core/` — pure C++17.** No `Arduino.h`, no `WiFi`, no
  `HardwareSerial`. Anything that makes a decision belongs here, with a Unity
  suite under `esp32/test/`. Include with the family prefix: `"common/x.h"`,
  `"drive/x.h"`, `"heading/x.h"` — two headers are named `output_map.h` and the
  prefix is what disambiguates them.
- **`esp32/src/` — hardware glue only.** Read pins, call the core, write pins.
- **`sk-plugin/` — its own npm project.** Safety logic in the pure `arbiter.cjs`;
  `index.cjs` is a thin I/O shell. The browser WebSocket is **read-only** — a
  regression test asserts the UI sends only `subscribe` and never `updates`.
- **`android/` — its own Gradle project.** Same split again: decisions in the
  pure `core/` module (JVM-tested, no Android imports), platform glue in `app/`.
  `:app` is only configured when an Android SDK is present, so `:core:test`
  runs anywhere.
- **`esp32/include/config.h`** holds every pin, Signal K path and tunable. Never
  hard-code one elsewhere. The one thing it does *not* hold is site
  configuration: the boat's own addresses live in the gitignored `secrets.h`,
  and `config.h` reads `SECRET_SK_SERVER_ADDRESS` / `_PORT` from it. Mirror what the plugin needs in
  `sk-plugin/src/config.ts`, and what the Android station needs in
  `android/core/.../SkContract.kt`. Three hand-synced copies; no shared build
  step links them.
- **The intent wire format is hand-synced too**, between `arbiter.cjs`,
  `sk-plugin/src/clientIntent.ts` and `android/core/.../ClientIntent.kt`. Note
  the asymmetry: only the Android station sends `session`, a persisted
  strictly-increasing LAUNCH GENERATION, because only it persists `clientId`
  (the Signal K token is issued to that device id). The browser mints a fresh
  `clientId` per page load, so a reload already reads as a new station.
  Generations are ordered, not just unique -- that is what lets the arbiter close
  an older session permanently instead of remembering dead ids and eventually
  forgetting one. The full verdict table is in `arbiter.cjs` at the session
  check; a missing generation is handled, so deploy order does not matter.

## Commands

```bash
cd esp32 && pio test -e native                          # pure core, all three firmwares (268 cases)
cd esp32 && pio run -e tx_shesp32|rx_shesp32|hh_shesp32 # build
cd esp32 && pio run -e <env> -t upload && pio device monitor
cd sk-plugin && npm test                    # 268 cases
cd sk-plugin && npm run build               # -> public/
cd android && ./gradlew :core:test          # pure Kotlin core, 171 cases
cd android && ./gradlew :app:testDebugUnitTest  # layout + fail-safe UI, 59 cases (needs SDK)
cd android && ./gradlew :app:assembleDebug  # needs an Android SDK
```

A change is not done until the relevant suite passes. Test directory names must
be unique across the project.

The Android toolchain is not assumed to be installed. `:core:test` needs only a
JDK 17+; `:app` additionally needs an Android SDK (platform-35, build-tools
35.0.0). Install steps for Windows, Linux and macOS, how to point Gradle at the
SDK, and how to install the resulting APK are in
[android/README.md](android/README.md#building-and-testing) — follow those
rather than improvising, and do not install Gradle itself: the wrapper fetches
the version the build expects.

CI (`.github/workflows/ci.yml`) runs all of the above on every push to `main`
and every pull request — the native suite, all three firmware builds as a
matrix, the plugin's typecheck/tests/build, and both Android suites — plus a
self-test of the release credential guard. Run them locally anyway: CI is the
backstop, not the first place to find out.

## SensESP v3 API rules

- Build envs extend `[shesp32_common]`: pioarduino platform fork, `board =
  esp32dev`, `min_spiffs.csv`, SensESP from a git dependency. Don't change the
  platform line.
- **Event loop:** SensESP owns its `reactesp::EventLoop`. Do **not** create a
  second `reactesp::ReactESP` or call `app.tick()`. Use `event_loop()->tick()`
  in `loop()` and `event_loop()->onRepeat(...)` for scheduling. A pinned
  FreeRTOS control task is a legitimate separate mechanism, not a second
  ReactESP.
- Use `SensESPAppBuilder`. `sensesp_app->start()` and `SetupSerialDebug()` do
  not exist.
- `SKValueListener<T>` for inbound subscriptions, `LambdaConsumer<T>` for glue,
  `PersistingObservableValue<T>` + `ConfigItem(...)` for flash-persisted,
  web-editable values.
- Logging is `ESP_LOGI/W/E(tag, ...)`.
- Credentials live in `esp32/include/secrets.h`, which is **gitignored and never
  committed** — create it from `secrets.example.h`. That is the *only* copy: the
  OTA password used to be duplicated in `platformio.ini` as
  `upload_flags = --auth=…`, and `scripts/ota_auth.py` now injects it from
  `secrets.h` at upload time instead. Never write a credential into
  `platformio.ini`. Rotation of the burned values is still open (BUILDING.md §8).
- If unsure of an API, read the working source in `esp32/src/` first, then the
  fetched SensESP source under `esp32/.pio/libdeps/<env>/SensESP/` (present after
  any `pio run`). Do not invent function names.

## Gotchas

- **`sensesp::Debounce` name collision.** SensESP has its own `Debounce<T>`
  transform template. In a file with `using namespace sensesp;`, use the
  qualified `control_core::Debounce` or a type alias — an unqualified `Debounce`
  is ambiguous and fails to compile.
- **GPIO 34–39 have no internal pull-up or pull-down**, so `INPUT_PULLDOWN` is
  silently a no-op there. Do not pick them for switch inputs without external
  resistors.
- **Signal K retains the last value of a path forever.** "Stop publishing" never
  withdraws a command, so a command's resting value must be a safe one that is
  fine to leave standing — e.g. the thruster trim is a relative offset whose 0
  means "no trim" (ARCHITECTURE.md §6.4), so there is nothing to withdraw. Avoid
  designs where silence, or a retained last value, would be read as an active
  command.
- **Untrusted boundaries:** anything from the network or the web UI is screened
  where it enters. Unrecognised command strings read as the safe value;
  non-finite or implausible numbers are rejected, not clamped into something
  plausible-looking.

## Hardware status

**RX and HH have run on the bench; TX has not.** As of 2026-07-25 the owner has
flashed both RX and HH to real SH-ESP32 boards and exercised the plugin and
phone UI against each end to end: RX's servos were watched moving through
forward / neutral / reverse, and HH's bow thruster was exercised through
MANUAL and HOLD, including the reversal-interlock scope checks in SAFETY.md's
thruster checklist. That validates the drive path and the thruster path — but
read the next paragraph before treating RX's result as cover for the current
tree.

**RX's bench test predates the gear-neutral arm interlock.** The arm gate, the
lever-neutral sensors, the actuator-engage output and RX's fourth LED state have
never been on hardware. Nothing in that commit is bench-proven, and the pull-up
and opto-polarity assumptions behind it are still unverified — work through the
arm-interlock block in SAFETY.md's drive checklist before connecting the engage
relay to anything mechanical.

**TX remains entirely host-verified.** It has not been flashed to a board at
all — work through SAFETY.md's checklists before connecting any TX-sourced
command to a drive or the thruster.

**The Android station has commanded both machines from a phone (2026-07-26).**
Armed against the live server with RX and HH answering: port FORWARD commanded
and released to NEUTRAL, thruster driven PORT in MANUAL, HOLD engaged and
trimmed off a real heading, then disarmed. Not proven: two-fingered multi-touch
(the presses were `adb`-injected one pointer at a time), fail-safe on
backgrounding, exclusive arm against the browser UI, and token revocation — see
SAFETY.md's *Before trusting the Android station*. Note the `readwrite` route
registration the whole token path depends on must actually be **deployed** on
the server; an older plugin build returns 401 to every token station, TX/RX/HH
included.

**Resolved layout defect, now guarded by a suite, still awaiting phone
remeasurement:** expanding Android telemetry formerly compressed each drive
button from 131 dp to 4.7 dp. The drive bank now reserves 280 dp and is
unweighted, and **only the telemetry panel scrolls**. No live control sits in a
scrolling gesture region: a momentary contact claims the pointer on touch down,
so a drag beginning on one is a press, and a scrolling ancestor would give the
operator a scroll *and* a live command for the whole drag. A STOP that can be
scrolled off-screen is likewise a worse defect than the one originally being
fixed.

**The same screen now scales rather than only holding its floors.** A floor is
half a rule: the bank was pinned at its 280 dp minimum on every screen while
telemetry — which commands nothing — held the `weight(1f)` and took every spare
pixel, so a 10" tablet ran 88 dp buttons under a metre of lamps. Sizes come from
`ui/HelmScale.kt`, the Kotlin twin of the browser UI's `--u`, and the leftover
height goes to the drives (185 dp on the reference phone, 348 on a 10" tablet).
The arrangement follows the window's shape: upright stacks (kill switch,
thruster, drives side by side, telemetry under), landscape with room to stack
moves telemetry into a sidebar like the browser UI's wide layout, and a wide but
short window falls back to the drives at the outside edges. The suite asserts the *absence* of waste as well as the floors —
a contact still at 88 dp on a big screen fails. **Do not reintroduce
`weight(1f)` on telemetry, and note that `weight(...)` in the same modifier
chain as `requiredHeightIn(min = ...)` grows nothing at all**: the required form
discards the exact minimum the weight handed down.

Since 2026-07-26 `:app:testDebugUnitTest` measures all of that under
Robolectric and CI runs it, which also means `app/` is compiled by CI at all.
The suite walks the semantics tree to assert no live control has a scrolling
ancestor, goes red on both reconstructed pre-fix layouts, and found on its
first run that the thruster contacts were 80 dp rather than 88
(`.height().padding()` chained the wrong way round). **Geometry is a safety
property on that screen — if you change the control layout, run that suite.**

Two things it cannot prove. The reference phone is modelled, not used:
Robolectric knows the S25's density, not its system bars or cutout. And it
injects no pointers, so *a drag started on a contact neither scrolls nor
commands* is still a hand-on-glass check.

Font scale IS now covered, and it mattered: screen height alone was never the
problem, screen height **times** system font scale was, because the kill switch
and readouts size off `sp` while the contacts are `dp` and cannot compensate. At
512 dp and 2.0x the starboard REV contact measured **0 dp** — a live button with
no height, reachable on the reference phone in split-screen. Both the contacts
and the drive bank's 280 dp reservation are therefore `requiredHeightIn`, which
cannot be negotiated away: a window too short now pushes the bank off the bottom
and **says so on screen** rather than quietly shrinking it. Details in
`android/README.md` under *Known gaps*.

## Journal

Keep `docs/JOURNAL.md` up to date: add an entry for each meaningful piece of work,
and read it at session start to resume correctly. It is the one place history
belongs — the other documents describe only the current system.
