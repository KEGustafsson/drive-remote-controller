---
name: build-flash
description: >
  Build firmware, flash to device, and monitor serial output. Use when
  user wants to test, upload, flash, or "try it on the device".
argument-hint: "[optional: tx, rx or hh]"
---

# Build, Flash, and Monitor

Build the firmware, upload it to the connected device, and read serial output to verify it's working.

## Phase 0: Pick the unit

This repository builds three firmwares from `esp32/`, and they drive different
machinery. Ask which one if it was not given:

| Unit | env | Source | Drives |
|---|---|---|---|
| TX — Handheld Controller | `tx_shesp32` | `esp32/src/tx/` | Nothing; publishes switch positions |
| RX — Motion Controller | `rx_shesp32` | `esp32/src/rx/` | The drive servos and the engage relay |
| HH — Heading Hold Controller | `hh_shesp32` | `esp32/src/hh/` | The bow thruster contactor |

**Read [SAFETY.md](../../../docs/SAFETY.md) before flashing RX or HH.** Both
command machinery, and its commissioning checklists are the gate — not this
skill. `esp32/platformio.ini` has the board and the env; each unit's OTA address
comes from `SECRET_OTA_HOST_TX|RX|HH` in the gitignored `include/secrets.h`.

## Phase 1: Build

1. Run `pio run -e <env>` from `esp32/`.
2. **If it succeeds**: Report the binary size and move to Phase 2.
3. **If it fails**: Read the error output carefully.
   - Translate the error into plain language: "The code has a typo on line 42" not "undefined reference to `foo`."
   - Fix the issue and rebuild.
   - Repeat until the build succeeds.

## Phase 2: Flash

**`-t upload` goes over the air here, not over USB.** `[env]` sets
`upload_protocol = espota` and each env carries its board's address, so
`pio run -e <env> -t upload` reflashes a board already on the boat's WiFi. See
[BUILDING.md §4](../../../docs/BUILDING.md#4-flashing) — §4.2 covers the serial
route for a virgin board, which needs `upload_protocol` overridden.

1. Confirm the board is powered and reachable (`ping` the address in
   `SECRET_OTA_HOST_<UNIT>`).
2. Run `pio run -e <env> -t upload` from `esp32/`.
3. **If upload fails**:
   - `ota_auth: … does not exist` → there is no `include/secrets.h`; create it
     from `secrets.example.h`.
   - "No response from device" → The board is off, off the network, or on a
     different address than `SECRET_OTA_HOST_<UNIT>` names. Override for one run
     with `--upload-port <address>`.
   - "Authentication Failed" → the board was flashed with a different
     `SECRET_OTA_PASSWORD` than the one in `secrets.h` now; BUILDING.md §8.
   - Explain what's happening in plain terms.

## Phase 3: Monitor and Configure

1. Run `pio device monitor` from `esp32/` to watch device output.
2. Read the output and interpret it:
   - **SensESP boot messages**: WiFi connection status, Signal K server connection, sensor initialization.
   - **Sensor readings**: Are values plausible? (temperature in expected range, pressure not zero, etc.)
   - **Errors**: Stack traces, assertion failures, watchdog resets.
   Opening the serial port resets the board. To watch a *running* unit instead,
   read `curl -N http://<host>/api/log` over HTTP, which resets nothing.
3. **On a board that has never joined WiFi**: connect to the access point
   SensESP raises on first boot, open the web UI and configure credentials.
4. **End-to-end check**: the paths are in `esp32/include/config.h`. Ask the user
   to confirm the unit's own paths appear on the Signal K server, and that the
   plugin's web UI shows the unit as live rather than greyed out.
5. Present a summary in plain language: which unit is running, whether it joined
   WiFi, whether it is publishing, and what it is *not* yet proven to do.

## Phase 4: Evaluate

Based on the serial output and user feedback:

- **It boots and publishes**: say so plainly, and say what that does *not* prove.
  A unit that talks to Signal K has not been shown to move an actuator the right
  way; SAFETY.md's checklists are what proves that, on real hardware.
- **Readings look wrong**: check wiring, pull-ups and scaling. Note the GPIO
  34–39 gotcha in AGENTS.md — no internal pull-up or pull-down there.
- **Device crashes or reboots**: `monitor_filters = esp32_exception_decoder` is
  set, so the backtrace is symbolised. Fix and rebuild (Phase 1).
- **WiFi not connecting**: walk through the SensESP configuration portal.
- **Nothing on the Signal K server**: check the paths against
  `esp32/include/config.h`, and remember the server retains a path's last value
  forever — a stale reading is not proof the unit is alive.

If changes are needed, fix the code and loop back to Phase 1.
