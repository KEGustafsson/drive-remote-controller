# Drive Remote Controller

Remote control for a motor yacht's **port and starboard drives** and its **bow
thruster**, from a handheld unit or a phone, with hardwired local controls that
always take precedence.

Three ESP32 firmwares, a Signal K plugin and a native Android app are built from
this one project:

| Part | Build env | Source | Role |
|---|---|---|---|
| **TX** — Handheld Controller | `tx_shesp32` | `esp32/src/tx/` | Portable station: physical switches → Signal K |
| **RX** — Motion Controller | `rx_shesp32` | `esp32/src/rx/` | Two servos that shift the drives |
| **HH** — Heading Hold Controller | `hh_shesp32` | `esp32/src/hh/` | Bow thruster: holds a heading, or direct manual thrust |
| **Plugin Controller** | npm | `sk-plugin/` | Phone/tablet web UI plus its server-side authority |
| **Android Controller** | Gradle | `android/` | Native phone station; a client of the plugin's authority, not a second one |

![System overview](docs/diagrams/system-overview.png)

## Documentation

| Document | What it answers |
|---|---|
| **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** | How it is built: the parts, the runtime structure, every module, the control flows, the Signal K contract, the pin maps |
| **[SAFETY.md](docs/SAFETY.md)** | The invariants, and the commissioning checklists to work through before connecting to real machinery |
| **[BUILDING.md](docs/BUILDING.md)** | Every procedure: toolchain setup, the three firmwares, flashing over OTA and over USB, the plugin, the Android station, CI, and what to do when a build stops |
| **[SECURITY.md](docs/SECURITY.md)** | The threat model, the known weaknesses stated plainly, and where the system stands against the EU Cyber Resilience Act's essential requirements |
| **[MEASUREMENTS.md](docs/MEASUREMENTS.md)** | The measured and confirmed values, and where each one lands in `config.h` |
| **[sk-plugin/README.md](sk-plugin/README.md)** | The Signal K plugin in detail |
| **[android/README.md](android/README.md)** | The Android station: toolchain setup, building, and the connection/token model |
| **[AGENTS.md](AGENTS.md)** | Working rules for AI agents on this codebase |
| **[JOURNAL.md](docs/JOURNAL.md)** | Development log |

> **Status:** all three firmwares build clean and every host suite passes
> (`pio test -e native`: 268 cases; plugin: 268; Android core: 171; Android
> app: 59).
>
> **RX and HH have run on the bench** (2026-07-25): both flashed to real
> boards, driven from the plugin's web UI end to end. RX's servos were
> seen moving through forward / neutral / reverse; HH's bow thruster was
> exercised through MANUAL and HOLD, including the reversal-interlock scope
> checks in SAFETY.md's thruster checklist. **TX has not been flashed at
> all**, and RX's gear-neutral arm interlock landed *after* its bench session,
> so it is untested on hardware.
>
> **The Android station has commanded both machines** (2026-07-26): armed from
> a phone against the live server, port FORWARD commanded and released to
> NEUTRAL, the bow thruster driven in MANUAL, and HOLD engaged and trimmed off
> a real heading. Two-fingered operation, fail-safe on backgrounding and token
> revocation remain untested, and it carries a measured drive-button defect —
> see [android/README.md](android/README.md#known-gaps). Read SAFETY.md's
> commissioning checklists before connecting any output to a drive or a
> thruster.

---

## What it does

**Drives.** Momentary forward/reverse controls per side, from the handheld unit
or the phone. Releasing a control commands neutral — there is no latch, which
suits the docking manoeuvres this is built for. Port and starboard are fully
independent, so pivot-turning with one ahead and one astern is normal.

The drive unit will only **take** control with **both shift levers in neutral**:
a sensor on each lever has to prove it before the actuator linkage engages, and
the panel LED blinks fast to say which one is in the way. Once it has control,
shifting is of course what it does — the neutral check gates taking over, not
staying in charge.

**Bow thruster.** Two explicitly selected modes:

- **MANUAL** — the button is the direction. Press and it thrusts, release and it
  stops, immediately. The only delay is the thruster control box's own
  anti-reversal interlock, which is shown on screen when it applies.
- **HOLD** — the unit holds a heading, and the stations trim it in ±1° / ±10°
  steps.

**Authority is one rule for the whole boat:** a unit's own local controls win
unconditionally, then the handheld unit, then the phone. Any loss of link or
authority falls back to the safe value — neutral for the drives, off for the
thruster — never to whatever was last commanded.

"The phone" is one tier, not two, even though there are two phone stations —
the browser UI and the native Android app. Both are *clients* of the same
server-side authority in `sk-plugin/`, which is the sole writer of the
`plugin.*` paths and hands the arm token to one client at a time. Adding a
station adds no new authority, which is the whole reason the Android app could
stay thin.

**One arm covers both machines.** It grants control of whichever units are
actually answering, and names any that are not.

---

## Quick start

```bash
# Host tests for the pure core -- all three firmwares, no hardware needed
cd esp32 && pio test -e native

# Build
cd esp32 && pio run -e tx_shesp32
cd esp32 && pio run -e rx_shesp32
cd esp32 && pio run -e hh_shesp32

# Flash + monitor
cd esp32 && pio run -e <env> -t upload && pio device monitor

# Signal K plugin (separate npm toolchain)
cd sk-plugin && npm install && npm test && npm run build

# Android station (separate Gradle toolchain)
cd android && ./gradlew :core:test          # pure logic -- JDK only, no Android SDK
cd android && ./gradlew :app:assembleDebug  # the APK -- needs an Android SDK
```

The Android toolchain is not assumed to be installed;
[android/README.md](android/README.md#building-and-testing) has the one-time
setup for Windows, Linux and macOS. Do not install Gradle itself — the wrapper
fetches the version the build expects.

**[BUILDING.md](docs/BUILDING.md) is the full version of the above** — toolchain
setup for each of the three, what `-t upload` actually does (it goes over the
air, not over USB), how to flash a virgin board, installing the plugin into a
Signal K server, what CI does and does not prove, and a table of what each build
failure means.

### Releases

A release carries the Android station only: the release-signed APK, a
debug-signed APK, R8's mapping file and a CycloneDX SBOM, with a SHA-256 for
each binary and a build-provenance attestation when the repository is entitled
to one. The firmwares and the plugin are built and checked by CI on every push
but deliberately not published — they reach the boat over OTA and `npm pack`
from a working tree, and this system has not been through commissioning.

It is run **by hand**, never on a merge: *Actions › Release › Run workflow*.
[BUILDING.md §10](docs/BUILDING.md#10-releases) has the procedure and the
one-time signing setup.

**No firmware is published, and none could carry a credential if it were.**
`secrets.h` is gitignored, so a clean checkout builds from `secrets.example.h`;
CI self-tests the guard (`esp32/scripts/check_no_secrets.py`) that would refuse
an image carrying a committed credential, ready for the day firmware is
released again. A locally built binary has had no such check.

All three units are Hat Labs **SH-ESP32** boards. WiFi and OTA credentials live
in `esp32/include/secrets.h` (template: `esp32/include/secrets.example.h`) and are never
hard-coded in source files.

> `secrets.h` is **gitignored and not in this repository** — copy
> `secrets.example.h` to it and fill in your own values. It is the only place the
> OTA password lives: `esp32/scripts/ota_auth.py` reads it from there at upload
> time, so `platformio.ini` names no credential and nothing committed here does.
>
> Nothing has been published: this repository's whole history has been searched
> and holds none of these values. The remaining copies are in two private
> repositories that stay private, so rotation is closed rather than pending
> (owner decision). [BUILDING.md
> §8](docs/BUILDING.md#8-credentials-and-what-must-be-rotated) is the procedure
> if either is ever made public or shared.

---

## Working on it

**The pure core is where decisions live.** `esp32/lib/control_core/` is plain C++17
with no Arduino, WiFi or serial dependency, so every arbitration rule, control
law and state machine is exercised on the host in milliseconds. `esp32/src/` is
hardware glue: read pins, call the core, write pins. Keep it that way — logic
that lands in `esp32/src/` is logic no test can see.

**Adding a module:** write the Unity suite under `esp32/test/test_<module>/` with it,
red then green. Test directory names must be unique across the project.

**Pins, Signal K paths and tunables live in `esp32/include/config.h`** and nowhere
else. The plugin mirrors the ones it needs in `sk-plugin/src/config.ts`; there is
no shared build step between the two toolchains, so a change to a path or a
timing constant has to be applied in both.

**Before changing anything that reaches an output**, read SAFETY.md. Several
rules there look like removable complexity and are not — in particular the
thruster's HOLD reversal dwell (and the shared last-thrust history both thruster
gates measure their dwell against), the arrival-based liveness checks, and the
fixed (never recency-based) precedence between stations. Note the thruster's
MANUAL dwell is deliberately 0 while HOLD's is mandatory; that asymmetry is
also documented there, and is not an oversight to tidy up.

### Test suites

| Command | Covers |
|---|---|
| `cd esp32 && pio test -e native` | The whole pure core: arbitration, control law, filters, parsers, state machines, and the wired heading-hold pipeline as scenarios |
| `cd sk-plugin && npm test` | The arming authority, the pure UI logic, the React components, and a full operator session against a real WebSocket server driving the real arbiter |
| `cd android && ./gradlew :core:test` | The Android station's pure core — the same drive truth table, trim clamp and arrival-based liveness, with the vectors ported alongside the code |

### Continuous integration

`.github/workflows/ci.yml` runs on every push to `main` and every pull request:

| Job | What it runs |
|---|---|
| **Pure core** | `pio test -e native` |
| **Build** ×3 | `pio run -e {tx,rx,hh}_shesp32`, plus a flash/RAM report — a matrix, so a break points at one unit |
| **Signal K plugin** | `npm ci`, `tsc -b`, `npm test`, `npm run build` |
| **Android pure core** | `./gradlew :core:test`, **with no Android SDK installed on purpose** — if `:core` ever stops building without one, something Android-specific has leaked into the pure layer |
| **Android layout floors** | `./gradlew :app:testDebugUnitTest`, then `:app:assembleDebug` — geometry is a safety property on that screen |
| **Release scripts** | Byte-compiles `esp32/scripts/`, and self-tests the credential guard in both directions |

None of this proves the firmware *works* — that still needs SAFETY.md's
commissioning checklists on real boards. What it does catch is the class of
problem that has actually bitten here: a change to the pure core that breaks an
arbitration or timing rule, a firmware that stops compiling against its
configured dependencies, and documentation drifting from the code it describes.

### Regenerating documentation assets

```bash
cd sk-plugin
npm run build                        # screenshots capture the built app
npm i --no-save playwright           # ~120 MB, docs-only tooling
npx playwright install chromium
node scripts/screenshots.cjs         # -> sk-plugin/docs/screenshots/*.png
```

Diagrams in `docs/diagrams/` are **draw.io-editable PNGs**. Each `.png` carries
its own diagram source in a PNG text chunk, so opening it in [draw.io
Desktop](https://www.drawio.com/) (or app.diagrams.net, or the VS Code *Draw.io
Integration* extension) gives you the editable diagram directly — no separate
source file to hunt for. The matching `.drawio` files are the same content in
plain XML, kept alongside so diagram changes show up as a readable diff in git.

After editing, re-export so the PNG and the XML stay in step:

```bash
cd docs/diagrams
for f in *.drawio; do
  drawio --export -f png --embed-diagram --scale 2 -b 16 -o "${f%.drawio}.png" "$f"
done
```

`--embed-diagram` is what keeps the PNG editable; `--scale 2` is what keeps it
legible on a phone and in print. On Windows the binary is
`"/c/Program Files/draw.io/draw.io.exe"`.

---

## Licence

[EUPL-1.2](LICENSE). Note what that licence does *not* cover: this software
commands a clutch and a bow-thruster contactor, it has not been through
[SAFETY.md](docs/SAFETY.md)'s commissioning checklists on every path, and it is
provided without warranty of any kind. Anyone connecting it to machinery is
responsible for proving it safe on their own boat first.
