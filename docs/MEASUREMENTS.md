# Measurements

The values this system needs from the real boat, and where each one lands in
`esp32/include/config.h`. **Nothing here is guessed** — a value stays marked as
unresolved until it has been measured or confirmed.

Read [SAFETY.md](SAFETY.md)'s commissioning checklists before performing any
procedure on this page.

- [Resolved values](#resolved-values)
- [Outstanding](#outstanding)
- [Procedures](#procedures)

---

## Resolved values

### Drive remote (TX + RX)

| Value | Setting | `config.h` |
|---|---|---|
| TX shift switch pins | port fwd 23 / rev 25, stbd fwd 27 / rev 26 | `kTxPort*Pin`, `kTxStbd*Pin` |
| TX enable (kill) switch | GPIO21, latching toggle | `kTxEnablePin` |
| RX local shift switches | Mirrors TX's numbering exactly | `kRxPort*Pin`, `kRxStbd*Pin` |
| RX master enable | GPIO21, latching toggle | `kRxMasterEnablePin` |
| RX servo outputs | GPIO16 (port), GPIO17 (stbd) — the board's I2C header, which also carries GND | `kRxPortServoPin`, `kRxStbdServoPin` |
| RX lever-neutral sensors | GPIO36 (port), GPIO39 (stbd), **active-low**, external 10 kΩ pull-up to 3V3 each | `kRxPortNeutralPin`, `kRxStbdNeutralPin`, `kRxNeutralActiveLow` |
| RX actuator-engage (ARM) output | GPIO33, the board's isolated Opto OUT | `kRxArmOutputPin`, `kRxArmOutputActiveHigh` |
| Status LEDs | GPIO2 (onboard) on every unit | `kLedStatusPin` |
| Switch electrical convention | Active-high, `INPUT_PULLDOWN`; shift switches momentary/spring-return, enables latching | — |
| Servo pulse widths | 1000 / 1500 / 2000 µs forward/neutral/reverse | `kServo*UsDefault` |
| Servo PWM frequency | 200 Hz | `kServoPeriodHz` |
| TX power | 9 V battery with its own physical on/off switch | not a firmware concern |
| Source staleness timeout | 1000 ms, against a 250 ms refresh (4× headroom) | `kSkStalenessTimeoutMs` |
| Unit-absent timeout | 1500 ms (six refreshes) | `kRxTelemetryStaleMs`, `kHhTelemetryStaleMs` |

**Servo calibration is live-tunable**, not fixed at compile time: the values
above are starting defaults, and each of the six positions (three per side) is a
flash-persisted `ConfigItem` on RX's web UI. Trim them against the real linkage
once mounted — a bench step, not a firmware change.

**Switch behaviour.** Forward and reverse are momentary contacts with no
dedicated neutral contact: pressed toward forward is forward, pressed toward
reverse is reverse, neither pressed is neutral. Releasing is the only way to
command neutral, and there is no latch.

**The two lever-neutral sensors are wired the opposite way to every other
contact** — they pull their pin to **ground** while the lever is in neutral, and
GPIO36/39 are input-only with no internal pull, so **each needs an external
10 kΩ pull-up to 3V3 in the proto area.** That resistor is what makes a failed
or unplugged sensor read "not neutral" instead of floating; SAFETY.md's
unplug-the-sensor check is what proves it is fitted.

### Heading hold (HH)

| Value | Setting | `config.h` |
|---|---|---|
| Output logic polarity | Active-high — the output FETs were bench-tested high-active, low-off | `kOutputActiveHigh` |
| Reversal dead time (HOLD) | **~1.75 s measured** on the thruster's control box → 1.85 s with margin | `kReversalDwellS` |
| Reversal dead time (MANUAL) | **0.00 s** — the firmware adds no wait; the control box's own interlock is the only delay (owner decision 2026-07-24) | `kManualReversalDwellS` |
| Signal K heading path | `navigation.headingTrue`, confirmed as dual-antenna true heading, not COG or magnetic | `kSkHeadingTruePath` |
| Heading quality gating | Resolved at the source: the GNSS plugin publishes JSON null when its own fix-quality check fails, and that null is dropped rather than coerced to a valid 0° | — |
| IMU interface | UART-RVC, 115200 8N1, 100 Hz, 19-byte frames | `kBnoUartRxPin` |
| GNSS heading update rate | **Was 1 Hz measured** (not the ~5 Hz the filter assumed); receiver reconfigured 2026-08-20 to **10 Hz** (`UNIHEADINGA 0.1` + `SAVECONFIG`). Verified at the server: 10.24 Hz, max inter-fix gap 0.300 s | `kSkHeadingListenDelayMs` |
| GNSS heading correction | Time constant **2.0 s**, derived per-fix from the actual interval, so it no longer depends on the fix rate | `kHeadingCorrTauS` |
| Heading trust window | **2.0 s** since the last accepted correction. Measured worst-case age 0.403 s at 10 Hz, 1.03 s at 1 Hz | `kHeadingFreshS` |
| Yaw-rate low-pass | **0.1 s.** At the previous 0.3 s the fused heading measured 0.24–0.30 s behind `navigation.headingTrue`; noise floor at rest is 0.034 °/s rms, so 0.3 s was guarding against noise that is not there | `kYawRateLpfTauS` |
| Fastest manual bow swing | **~3.9 °/s** (owner swinging lock to lock as hard as possible) — 2.5× margin under the 10 °/s GNSS plausibility bound, which therefore never trips in manual handling | `max_rate_dps` |
| Fused-heading lag (verified) | **0.145 s** after the 2026-08-20 changes, down from 0.295 s / 0.244 s. Same method, same bow, harder swing (4.33 °/s): p95 error 1.14–1.57° → **0.53°**, and settling after a stop 15–20 s → **0 s** | — |
| Setpoint slew limit | 10 °/s | `kSetpointSlewDps` |
| Heading trim steps | 1° fine, 10° coarse | `kHeadingNudge*Deg` |

**The ~1.75 s measurement is the control box's own anti-reversal interlock.**
During it the box will not accept the opposite direction, so commanding through
it cannot make the thruster reverse sooner. Same-direction re-pulsing was
observed to have no delay, which is why it applies only to a reversal.

The firmware then chooses, per mode, whether to *also* wait — the measurement
does not decide that, and the two modes answer differently on purpose:

- **HOLD** (`kReversalDwellS` = 1.85 s) waits in full and **must not be
  reduced** below the measurement. A `static_assert` in `config.h` enforces
  that floor, because nothing else would: the test suites build their own
  `SwitchCfg` and never read `config::`.
- **MANUAL** (`kManualReversalDwellS` = 0.00 s) does not wait. A human is on
  the button watching the boat and the box still protects the contactor, so the
  firmware hands the request straight through. There is no floor on this one
  and no `static_assert`; it is a policy knob, not a measurement.

Both are measured against the same shared last-thrust history, so a mode change
cannot bypass whichever value applies (ARCHITECTURE.md §6.3). Confirm the MANUAL
choice on a scope, motor power isolated, before the outputs reach the thruster —
SAFETY.md's thruster checklist has the step.

---

## Outstanding

| # | Value | Needed for | Blocking? |
|---|---|---|---|
| 1 | **TX thruster GPIOs** — proposed 18 (port), 19 (stbd), 22 (mode) | TX wiring | No — confirm at wiring, change `config.h` if the panel differs |
| 2 | GNSS antenna baseline (phase-centre separation) | Expected heading accuracy | No — the *update rate* half of this item is resolved above (2026-08-20); only the baseline is still unmeasured |
| 3 | Thruster S2 rating and motor current | Duty window and limits | No — tunes duty protection |
| 4 | IMU mount orientation and roll/pitch tare | Published attitude, yaw-rate sign | No — live-tunable from the web UI |
| 5 | Servo mechanism geometry | Real forward/neutral/reverse pulse widths | No — live-tunable from the web UI |
| 6 | **Servo power supply and ground** | Wiring the servos | **Yes, for RX bench work** — the servos need a supply the board does not provide |
| 7 | Deadman switch installation (GPIO19 + external 10 kΩ pull-down) | Second local cut-out channel for the thruster (SAFETY.md thruster invariant 6) | No — the read is compile-time disabled (`kDeadmanWired = false`) until installed; ENGAGE provides the invariant alone meanwhile |
| 8 | HH control-task timing on real hardware — loop jitter and stack high-water mark with logging active | Confidence that the 10 ms loop and its 4 KB stack hold up outside the host simulator | No — read the task's own jitter log lines and `uxTaskGetStackHighWaterMark` during bench work |
| 9 | **RX lever-neutral sensor type and its neutral window** — mechanical switch or hall, and how many mm/degrees of lever travel still read "neutral" | Whether "proven neutral" means the lever is close enough for the clutch to engage without a jerk | **Yes, before the engage relay is connected.** A sensor that reads neutral across a wide band would let the clutch grab a partly-shifted lever; firmware cannot detect this |
| 10 | **RX engage-relay coil voltage, current, and the opto output's real polarity** | Sizing the relay driver and setting `kRxArmOutputActiveHigh` | **Yes, before the relay is wired** — an inverting opto stage would energise the clutch while RX reads disarmed |
| 11 | **HH ENGAGE opto INPUT's real sense** (GPIO35) — does applying 12 V drive the pin high or low? | Setting `kEngageActiveHigh` | **Yes, before HH is trusted at all.** Hat Labs' public docs do not state it, and optocoupler input stages commonly invert. GPIO35 is input-only with no internal pull, so there is no firmware backstop: if the stage inverts and the flag says active-high, an *unwired* ENGAGE reads asserted and HH arms itself into HOLD. The unwired-reads-disarmed check heads SAFETY.md's thruster list |
| 12 | RX servo header loading (GPIO16/17) — are there pull-ups to 3V3 on the I2C header, and does its jumper isolate the pins? | Confidence that nothing unexpected loads a servo signal line | No — pull-ups here are harmless and arguably helpful (the line idles HIGH instead of floating pre-`attach()`). Confirm during RX bench work |

**On the proposed TX pins (1).** Chosen from the SH-ESP32 free-GPIO list
(5, 12, 13, 14, 15, 18, 19, 21, 22, 23, 25, 26, 27, 36, 39) so that they:

1. avoid the boot-strapping pins (0, 2, 5, 12, 15), so they read inactive at
   boot;
2. avoid 36 and 39 — those are input-only **and** have no internal pull-up or
   pull-down on ESP32, so `INPUT_PULLDOWN` does nothing on them and the input
   floats without external resistors;
3. avoid 13 and 14, which are free on TX but are RX's servo pins — both panels
   are wired to one diagram, and reusing those numbers for a different function
   on the sister unit would be a trap for whoever wires the second box.

Confirm the two direction buttons are momentary and the mode switch is latching
and active-high (HIGH = MANUAL).

**On RX taking 36 and 39 anyway (9).** Rule 2 above rules those pins out for a
*switch* — but RX's neutral sensors get external 10 kΩ pull-ups, which is what
rule 2 says is missing, and an open-collector hall sensor wants one regardless.
Spending the two input-only pins on the two pure inputs is also what keeps
18/19/22 free, so rule 3 keeps holding: no GPIO number means two different
things across the shared TX/RX diagram.

**On the servo mechanism (5).** Whether the servo presses an existing panel
button or moves a shift cable directly is not something the firmware needs to
know — it emits a pulse width. What matters is that the real three positions are
trimmed in from the web UI once mounted.

**On servo power (6).** The SH-ESP32 drives the servo *signal* only. The servos
need their own supply of adequate current, with its ground bonded to the board's
ground so the PWM signal has a return path. Decide the supply and its fusing
before the first bench test — this is the one outstanding item that blocks
moving a servo at all.

---

## Procedures

### Tools

Multimeter (DC volts, continuity, mA). A two-channel oscilloscope, logic
analyser, or two LEDs with resistors for timing work. A servo tester or spare
ESP32 for bench-sweeping a servo. The thruster control box and helm control
documentation. Access to the Signal K server's web UI. Spirit level or
inclinometer for the IMU tare; tape measure for the antenna baseline.

### Servo calibration

With the servo **disconnected from the linkage**, sweep it on the bench and note
its usable range. Mount it to the real lever with the engine off, find the three
positions, and confirm neutral is unambiguous with no partial engagement. Record
each side separately — do not assume symmetry. Enter the values on RX's web UI.

### Reversal dead time

The interlock is often stated in the thruster control box's manual; use that as
the starting value and verify by measurement if you can.

With the thruster **motor power isolated** (verify the prop cannot move first),
put probes on the port and starboard solenoid or contactor coil drives. Command
port, then rapidly command starboard, and measure the gap from port-coil release
to starboard-coil pickup. Repeat several times and take the **maximum**. Set
`kReversalDwellS` to that value plus 50–100 ms of margin.

If the coils are not accessible, the same gap can be measured on the motor leads
with a current clamp — but that requires the motor live, so the full thruster
safety preamble in SAFETY.md applies.

### GNSS heading rate and baseline

**Do not infer the rate from `kSkHeadingListenDelayMs`.** That constant is a
subscribe throttle — an upper bound on how often the server may push — and
reading it as the receiver's output rate is precisely how the filter came to be
tuned for 5 Hz while the receiver delivered 1 Hz.

Measure it at the wire instead: subscribe to `navigation.headingTrue` on the
server's delta stream (TCP 8375 or the websocket) with `policy: "instant"` and
`minPeriod: 0`, log local arrival times for 30–60 s, and take the inter-arrival
distribution — the *worst* gap matters as much as the mean, because
`kHeadingFreshS` is sized against it. Note that the `@tkurki/um982` plugin maps
**two** sentences (`$GNHPR` and `#UNIHEADINGA`) onto this one path, so raw delta
counts double-count; de-duplicate by timestamp before computing a rate.

The receiver's own rate is set with `UNIHEADINGA <seconds>` (e.g. `0.1` for
10 Hz) followed by `SAVECONFIG`. Above ~5 Hz, check for capture loss: other
periodic logs share the port, and at 115200 a 10 Hz heading collides with the
1 Hz satellite-status log. Raising the COM baud to 460800 is the fix.

Measure the straight-line distance between the two antenna phase centres
separately; heading accuracy scales roughly inversely with that baseline.

### Thruster S2 rating

From the thruster's nameplate or datasheet: the S2 short-time rating (continuous
run time from cold before mandatory cool-down), the motor current, and whether it
has a built-in thermal cutout. Set the duty window to about the S2 time and keep
the warn and max fractions conservative, so the firmware backs off well before
the box's own thermal protection would act.

### IMU mount and tare

Mount the IMU rigidly with its axes roughly aligned to the boat (X forward, Z
vertical), away from the thruster motor and heavy DC cabling. With the boat level
and at rest, read the roll and pitch values — those are the tare offsets. Rotate
the bow to starboard and confirm the derived yaw rate is positive; if it is not,
flip the yaw-rate sign. All three are web-UI values, so no reflash is needed.
