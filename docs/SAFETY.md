# Safety

This system moves machinery near people: two engine drives and a bow thruster.
The invariants below are properties the code must guarantee, not preferences.
If a change would require breaking one, stop and raise it.

[ARCHITECTURE.md](ARCHITECTURE.md) describes the structure that implements these.

- [Two machines, two rule sets](#two-machines-two-rule-sets)
- [Drive invariants](#drive-invariants)
- [Thruster invariants](#thruster-invariants)
- [Cross-cutting rules](#cross-cutting-rules)
- [Commissioning checklists](#commissioning-checklists)
  - [Before touching the drives](#before-touching-the-drives)
  - [Before touching the bow thruster](#before-touching-the-bow-thruster)
  - [Multi-station and plugin behaviour](#multi-station-and-plugin-behaviour)
  - [Before trusting the Android station](#before-trusting-the-android-station)

---

## Two machines, two rule sets

The drives and the bow thruster share a repository and command stations. **They
do not share safety rules**, and the rules genuinely conflict in one place:

| | Drives | Bow thruster |
|---|---|---|
| Dwell / dead time | **None.** A commanded shift reaches the servo as fast as the loop runs. | **HOLD only.** The automatic loop waits out the control box's measured ~1.75 s interlock; MANUAL hands the request straight through and lets the box enforce it (thruster invariant 7). |
| Safe resting value | NEUTRAL | OFF (coasting) |
| Local override | RX's own switches, unconditional | HH's own ENGAGE input, unconditional |
| Arm precondition | **Both shift levers in NEUTRAL** (drive invariant 7) | A fresh ENGAGE edge on a trustworthy heading |
| Arm output | Actuator-engage relay, GPIO33 | Thruster ENABLE, GPIO33 |

Carrying either rule into the other machine is a defect, not a simplification.
Debounce is contact-bounce settling and is **not** a dwell — it applies to both.

---

## Drive invariants

1. **Never assert FORWARD and REVERSE together on the same drive.** Port and
   starboard commanding *different* directions from each other is normal
   operation (pivot-turning); do not couple them.
2. **No imposed timing on shifts.** No reversal delays, no minimum-hold timers.
3. **RX's local switch for a drive is unconditional.** Off NEUTRAL it drives
   that drive directly — regardless of RX's arm state, TX's enable, or any
   link state. This must work with TX dead and the network down.

   *Scope, stated honestly:* this is a guarantee about the **servo**, not about
   the boat. The actuator-engage relay (invariant 7) is released while
   disarmed, so on the real installation the linkage is disconnected and a
   local switch moves a servo that is coupled to nothing. Firmware keeps the
   command path unconditional because it has no way to know the state of the
   clutch; the operator should know that "local override always works" means
   *once RX is armed*. Disarming is still a full stop — it releases the clutch,
   which is a stronger stop than commanding NEUTRAL.
4. **Remote sources may command only when** the local switch is NEUTRAL, RX is
   ARMED (invariant 7), and that source is both live and reporting itself
   enabled.
5. **Fail to NEUTRAL, never to the last remote value.**
6. **The RX control task never blocks on the network.**
7. **RX arms only with both shift levers proven in NEUTRAL, and the
   actuator-engage relay follows the arm state exactly.** Each lever's sensor
   pulls its GPIO to ground while in neutral, so any sensor or wiring failure
   reads "not neutral" and refuses the arm. Three consequences that are all
   deliberate:

   - **Neutral gates arming, not staying armed.** RX's servos move the levers,
     so the sensors open on the first commanded shift. A continuously-enforced
     rule would disarm RX the instant it did its job.
   - **Arming additionally requires RX's own local switches at neutral**, so
     the clutch never engages onto a servo parked in gear against a lever that
     is not.
   - **Disarm is unconditional and immediate**, master enable OFF on any tick,
     regardless of lever position — the general stop rule below. Boot lands
     DISARMED with the relay released before the servos are even attached.

   The relay's own safe state is the operator's to provide: the GPIO floats
   from reset until `setup()` runs, so the driver needs a pull-down.

   **Open question for the bench, and the one place this invariant is not yet
   settled:** the relay follows the arm state *exactly*, which is what was
   asked for — so disarming with a drive in gear releases the linkage where it
   stands. The servo returns to NEUTRAL connected to nothing and **the lever
   stays in gear** until someone moves it. For a helm-takeover clutch that is
   correct and conventional. If, on this boat's mechanism, a kill-switch press
   should instead leave the engines in neutral, the fix is a disarm *sequence*
   — hold the relay until both commanded positions have reached neutral, then
   release — which costs the servo's travel time (a few hundred ms) during
   which a disarmed unit is still moving machinery. That trade is a decision
   for the owner after the mechanism is seen working, not a firmware default to
   assume. Until then, treat "disarm" as *release the drives to the helm*, not
   as *shift to neutral*.

---

## Thruster invariants

1. **Never assert PORT and STBD together.** The output driver must
   *structurally* refuse it.
2. **Directions are inert unless armed.** Both direction lines are forced low
   whenever ENABLE is low.
3. **ENABLE is the arm/active state, not a per-pulse line.** High while armed
   and healthy, low on disarm or fault. Dropping ENABLE is the single fail-off.
4. **IMU silence → outputs OFF, FAULT.** This holds in MANUAL mode too, even
   though manual control consults no IMU data: a frame timeout means the unit's
   own health is unknown, and the fail-off path stays uniform across modes.
5. **The control loop never blocks on the network.** Network loss means coast on
   the gyro, then disengage cleanly.
6. **Manual authority dominates.** Engage or deadman released → DISARMED,
   outputs OFF. HH's own ENGAGE input outranks every remote station,
   unconditionally, and always means HOLD. *Current hardware status:* no
   deadman switch is installed and its read is compile-time disabled
   (`config::kDeadmanWired = false` — no switch is fitted), so this
   invariant's deadman half is provided by ENGAGE alone until the switch is
   wired, the flag enabled, and the release bench-verified.
7. **HOLD respects the full measured reversal dwell; MANUAL deliberately does
   not.** The thruster's control box enforces its own ~1.75 s anti-reversal
   interlock in hardware, and it applies no matter what the firmware asks for.
   The question each mode answers is only whether the firmware *also* waits:

   - **HOLD** (`kReversalDwellS`, 1.85 s) waits in full, on top of min-on and
     min-off. The bang-bang `Switcher` is an automatic loop and nobody is
     watching the tunnel, so it must not lean on the box as its only guard.
     This value has a hard floor at the ~1.75 s measurement, enforced by a
     `static_assert` in `config.h`, and is not web-tunable.
   - **MANUAL** (`kManualReversalDwellS`, **0.00 s** — owner decision
     2026-07-24) does not wait. A human is holding the button and watching the
     boat, the box protects the contactor regardless, and a firmware-imposed
     wait made the control feel dead during docking. The operator judges when
     to reverse.

   The asymmetry is the point, and it is a *policy* difference, not a claim
   that the hardware interlock differs between modes. What both modes share is
   the **history** the dwell is measured against: `ControlStep` tracks the last
   direction the thruster was physically driven in across both gates, so a mode
   change cannot be used to walk past whichever dwell applies. Each gate then
   applies its own value to that shared history. Setting `kManualReversalDwellS`
   above 0 re-enables the manual wait, including across a mode change, with no
   other change needed.

   Consequence worth knowing at the helm: with MANUAL at 0, `reversalPending`
   never publishes in manual mode, so the UI's "reversing — waiting for the
   thruster" note never appears. A manual reversal that visibly coasts is the
   control box's interlock, not ours.
8. **A remote command enters through the same gate as a local one.** Never add a
   path that reaches the outputs without passing the safety FSM.
9. **Leaving HOLDING requires a fresh engage edge to resume.** Thrust never
   restarts silently because a sensor recovered.

---

## Cross-cutting rules

**Liveness is time-since-last-update, never a counter comparison.** A source
that restarts must be recognised as live again on its very next message. A
"counter must be increasing" test does not recover promptly from a reboot.

**Liveness is judged on message arrival, never on a message's value.** Signal K
retains a path's last value indefinitely, so a value published by a unit stands
at its last reading forever once that unit loses power. A reading can never
reveal its own publisher's absence — only the deltas stopping can. This applies
to every consumer: RX, TX, the plugin server and the plugin UI.

> **This rests on one assumption that no host test can reach, and it must be
> confirmed on hardware.** For arrival-timing to work, a unit republishing an
> *unchanged* value has to keep producing deltas: `SKOutput::set()` must emit
> every time it is called, and the Signal K server must forward each one. The
> server half was checked against `streambundle.js` (`getSelfStream(path).push()`
> per delta, no duplicate suppression); the SensESP half is unverified here.
> It matters for both `rx.linkUp` and `hh.linkUp`, whose values sit constant
> for long stretches — `true` while any station keeps publishing its disarmed
> heartbeat, `false` when none is. If either layer deduplicates, a healthy
> powered unit stops looking live: TX blinks fault and the plugin refuses or
> releases the arm. That fails in the cautious direction, but it
> would make the system unusable, and it is invisible until real hardware —
> see the commissioning item below.

**Never show a confident indication for a path that cannot reach a live unit.**
A status light or a UI row that presents last-known data as live is a safety
defect, not a cosmetic one. When a unit stops answering or a socket drops,
derived indications must visibly degrade rather than freeze.

**Arming is edge-triggered and exclusive.** At most one remote controller holds
authority; a returning unit or a reconnecting client never silently re-arms.
**Disarm is never gated on anything** — whoever is nearest the machinery must
always be able to stop it.

**Untrusted input is validated at the boundary.** Anything arriving from the
network or the web UI — commands, modes, headings, tuning values — is screened
where it enters: unrecognised command strings read as the safe value, non-finite
or implausible numbers are rejected rather than clamped into something
plausible-looking.

**Status indications never gate commands.** An indicator is operator feedback.
Suppressing an authoritative station's commands because of a one-directional
return-path glitch would be worse than a briefly inaccurate light; the receiving
units fail safe on their own. The same split cuts the other way for the stop
path: the plugin UI's disarm travels over HTTP and must stay live even while
the read-side WebSocket is down — a transport's health never gates a stop.

**A station's access token is a key to the machinery, and is treated as one.**
It grants exactly what an operator has: the ability to arm and command. The
Android station therefore refuses plain `http` to anything not on a private
network, decided in tested code (`PrivateAddress.isPrivateHost`) rather than in
Android's network-security XML, which cannot express it — see
[ARCHITECTURE.md §11](ARCHITECTURE.md#11-the-android-station). Anything the
parser does not recognise is refused rather than trusted, and the refusal
happens before anything is stored and before any socket opens, so a token
cannot leave over a link that would expose it. `https://` is allowed
unconditionally.

The other half of that rule is recovering when a token stops being valid: a
station whose authority has been withdrawn must **stop presenting itself as
able to command** and go back to asking for it, not keep displaying a control
panel that no longer reaches anything. A 401 alone is not proof of withdrawal —
the auth-scheme probe produces them by design — so the verdict is taken on
consecutive rejections, and a transport failure is never counted as one.
Losing the network is not losing authority.

**The remote-authority model is contingent on Signal K server security being
enabled.** The `/plugins/*` intent route and delta writes are protected by the
SK server's own auth; with server security set to "none", any device on the
LAN can post intents or publish `plugin.*` deltas directly, bypassing the
arbiter. The arbiter being "sole writer" of `plugin.*` is likewise a
convention Signal K cannot enforce per-path. Run the boat's SK server with
security enabled; the physical local controls and each unit's own fail-safes
do not depend on this, but remote arming discipline does.

---

## Commissioning checklists

**Partly verified, and the parts matter.** As of 2026-07-25 RX and HH have both
been flashed and run on the bench: the plugin's browser UI was exercised
against each end to end. RX's servos were seen moving through forward /
neutral / reverse; HH's bow thruster was exercised through MANUAL and HOLD,
including the reversal-interlock scope checks in the thruster checklist below.
**TX is still entirely host-verified** — it has not been flashed to a board at
all.

**RX's bench session predates the gear-neutral arm interlock** (drive invariant
7), so none of it — the arm gate, the lever-neutral sensors, the engage relay,
the fourth LED state — has been on hardware. Treat the arm-interlock block below
as untested code, not as a re-check of something already working.

**The Android station has commanded both machines (2026-07-26).** On the
owner's phone against the live server, with the hardware confirmed safe: armed,
port FORWARD commanded and released back to NEUTRAL, bow thruster driven PORT
in MANUAL, HOLD engaged and trimmed +10° off a real 096° heading, then
disarmed. Its own list is at the end of this section, and it is additional to,
not instead of, the multi-station list.

**What that session did NOT cover**, and what the list below therefore still
matters for: two-fingered simultaneous drive commands (the presses were
injected one pointer at a time by `adb`, which cannot demonstrate multi-touch),
fail-safe on backgrounding or screen lock, exclusive arm against the browser
UI, and token revocation.

Work through the relevant list before connecting to anything that can move.

### Before touching the drives

Servos here move a shift lever and can put an engine in gear.

1. Test servo movement and switch wiring with the **engine off and ideally
   isolated** until throw and calibration are confirmed.
2. First live-engine test: dock lines on, crew aware, clear of the prop, engine
   at idle, brief bursts only. Confirm neutral is truly neutral — no creep —
   before trusting any remote command near people or other boats.

**Arm interlock — do these before the engage relay is connected to anything
mechanical, with a meter or a scope on the GPIO33 line.**

- [ ] **Prove the external pull-ups exist.** Unplug each lever-neutral sensor in turn: `rx.port.leverNeutral` / `rx.stbd.leverNeutral` must read **false** and arming must be refused. A pin that still reads "neutral" unplugged is floating — the resistor is missing or open, and the whole interlock is decorative until it is fitted.
- [ ] Check the opto output's polarity before the relay is wired: disarmed must read as relay-**released** at the relay driver, not merely at the GPIO. Flip `kRxArmOutputActiveHigh` if the opto stage inverts.
- [ ] Confirm the relay driver's own pull-down: hold the board in reset and verify the relay stays released for the whole reset and the boot delay.
- [ ] **Servo header sanity (GPIO16/17, the board's I2C header).** Meter the two signal pins for pull-up resistors to 3V3 and confirm the I2C jumper really isolates them. Pull-ups are *fine* here — a push-pull GPIO sinks well under a milliamp through a 4k7, and the line then idles HIGH rather than floating before `attach()`, which is better defined than the raw header was. You are checking that nothing unexpected is loading a servo signal line, not hunting a fault.
- [ ] **Scope both servo signals through a power-cycle**, before the linkage is connected. Nothing valid should appear until `setup()` attaches them. This is the check the 13/14 → 16/17 move was made for: GPIO14 emitted a boot-time signal that could land in the 1–2 ms pulse window, and on the bench — where the engage relay does not yet exist — nothing else stands between that glitch and a servo horn.
- [ ] One lever out of neutral → arming refused, `rx.armInhibit` names that lever, LED blinks fast. Repeat for the other lever, and for both.
- [ ] Both levers neutral + master enable ON → `rx.armed` true, relay energises.
- [ ] Hold a local shift switch off neutral with both levers neutral → arming refused (`localCommandActive`). Release → arms. This is the one that stops the clutch grabbing a lever with the servo already parked in gear.
- [ ] **Armed, then shift:** command gear from a remote → the lever leaves neutral and RX **stays armed**. It must not drop out on its own first shift.
- [ ] Master enable OFF mid-shift, levers in gear → relay releases immediately; re-arming is refused until both levers are returned to neutral.
- [ ] **Watch what the lever does on that disarm** (engine off, linkage connected, boat secured). Expected: the clutch lets go and the lever stays in gear. Confirm that is what this mechanism should do — if a kill-switch press must leave the engines in neutral instead, that is the disarm-sequence change described under invariant 7, and it needs deciding here rather than at sea.
- [ ] Power-cycle RX with a lever in gear → boots disarmed, relay released, arming refused until the levers are returned to neutral.
- [ ] Local switches override any remote command instantly, armed and disarmed.
- [ ] TX's enable switch OFF stops TX commanding immediately, not after a timeout.
- [ ] RX's master enable OFF stops all remote sources; local switch commands still reach the servos (the linkage is released, so nothing moves — that is expected, see invariant 3).
- [ ] Kill WiFi on TX → RX's TX source goes stale → NEUTRAL (unless local or plugin is live).
- [ ] Kill WiFi on RX → both remote sources unreachable → NEUTRAL.
- [ ] Restart TX mid-session → recognised as live again immediately on reconnect.
- [ ] Port and starboard commanded to opposite directions at once — no cross-effect.
- [ ] Servo positions verified against the real lever throw, engine off, before any live test.

### Before touching the bow thruster

A bow thruster can move several tonnes of boat and amputate fingers.

1. **Isolate the thruster motor power** (pull its fuse / open its isolator)
   before asserting any control input, then verify the prop does not move when a
   helm button is pressed before trusting that isolation.
2. If a step genuinely requires the motor live, do it with the boat secured, the
   tunnel clear of people and lines, crew warned, and only in brief bursts.
   Best of all, do it with the boat out of the water.
3. **Verify the ENABLE/PORT/STBD logic levels on a scope before the outputs are
   connected to the thruster's remote at all.**

- [ ] **Settle the ENGAGE opto polarity before anything else — this one is fail-dangerous if it is wrong.** With the ENGAGE connector **unwired**, power HH up and read `control.remoteController.hh.armed` and `.mode`: it must sit **disarmed**. Then apply 12 V to the opto input → it must arm and read `hold`. If those are backwards, the board's opto stage inverts: set `config::kEngageActiveHigh = false`, rebuild, and repeat until unwired reads disarmed. Hat Labs' public documentation does not state this stage's sense and GPIO35 is input-only with no internal pull, so firmware has no backstop — an inverted stage with the wrong constant means HH arms itself into HOLD on an empty connector. Meter the pin directly if the telemetry is ambiguous.
- [ ] **Confirm the ENABLE output stage's own pull-down:** hold the board in reset and scope GPIO33 — it must stay low for the whole reset *and* the boot delay. GPIO33 is a floating input from reset until `setup()` runs, and firmware cannot cover that window. Do the same for PORT/STBD (13/27); they are inert while ENABLE is low, but only once something actually holds ENABLE low.
- [ ] Both-direction state never appears for any command sequence; dropping ENABLE kills thrust instantly.
- [ ] Engage released cuts outputs instantly, every time. (Deadman: only after the switch is installed and `kDeadmanWired` enabled — then verify its release the same way. It is on GPIO19, which has a real internal pull-down, so an unwired or broken line reads not-held; fit the external 10 kΩ anyway for cable-run noise immunity.)
- [ ] **Independent fail-off watchdog fires:** wedge the control task (e.g. a debug-build infinite loop in `Tick()`), scope ENABLE/PORT/STBD dropping within ~`kOutputFailoffTimeoutMs + kOutputFailoffCheckPeriodMs` (~125 ms). This is the last line of defence against a latched direction and must be seen working once on real hardware.
- [ ] MANUAL: press → thrust within one control tick; release → off immediately, from every station.
- [ ] **MANUAL reversal (firmware asks immediately by design — invariant 7):** flick port→stbd on the bench, motor power isolated, and scope it. Expect ONE tick of OFF (the structural interlock) and then the opposite line asserting. `reversalPending` will **not** publish. Then confirm on the real box that it absorbs this without the prop reversing early — this is the step that validates the whole 0.00 s decision, and it must be seen on a scope before the outputs go anywhere near the thruster.
- [ ] With `kManualReversalDwellS` temporarily set non-zero, re-scope the same flick: OFF is held for the configured time, `reversalPending` publishes. Confirms the knob still works if the bench test above says it is needed.
- [ ] **HOLD reversal:** drive a heading error that reverses the switcher and scope it — the opposite direction must not assert for the full 1.85 s.
- [ ] **Cross-mode reversal:** thrust PORT in MANUAL, release, tap HOLD with a starboard-ward error. The switcher must still wait its full dwell measured from when the manual thrust stopped (~1.85 s), not min-off. Repeat via disarm/re-arm into HOLD.
- [ ] Release during a dwell (HOLD, or MANUAL with a non-zero dwell) → the thruster does **not** fire when it expires.
- [ ] Unplug the IMU mid-thrust → outputs off, FAULT — in MANUAL mode as well as HOLD.
- [ ] Local ENGAGE asserted while a remote is thrusting → local takes over, mode reads `hold`, source reads `local`.
- [ ] HOLD: trim from a station → the setpoint slews at the rate limit and never jumps; the fused heading is not snapped.
- [ ] Leave HOLD and return → the setpoint is re-captured from the current heading, not the earlier session's target.
- [ ] **Arming straight into HOLD from a station.** The plugin and phone let the operator pick MANUAL or HOLD while disarmed, so the arm itself is what engages the hold — no thruster press is involved. With motor power isolated and a scope on the outputs: select HOLD with nothing armed (nothing may appear at the outputs, and `plugin.thruster.mode` must not follow a non-holder), then arm and confirm the hold engages against the heading captured **at the arm**, and that a preceding manual thrust still buys its full 1.85 s dwell across that arm. Then repeat with MANUAL selected: arming alone must produce no thrust at all.
- [ ] WiFi off → holds briefly on the gyro, then disengages cleanly; never slams to full deflection.
- [ ] Arm refused on a bad or float-quality heading.
- [ ] Duty limiting observed under repeated use.

### Multi-station and plugin behaviour

- [ ] **Do this one first — everything below depends on it.** With nothing armed, watch `control.remoteController.rx.linkUp` and `.hh.linkUp` in the SK data browser. Deltas must keep arriving (RX ~every 250 ms, HH ~every 66 ms) even though the values sit unchanged. If they arrive only when the value changes, the arrival-based liveness model above is broken on this SensESP build and TX's LED, the plugin's arm gate and the UI's liveness rows are all unreliable — stop and fix that before commissioning anything else.
- [ ] A thruster mode selected on one disarmed station must not change what another station commands: pick HOLD on the phone while the plugin holds the token, and the thruster must keep following the plugin's mode until the phone actually takes the token.
- [ ] TX and the plugin both armed and disagreeing → TX commands; the plugin's presses do nothing and the UI says so.
- [ ] Two devices open → the second shows IN USE and cannot arm; either device's kill switch disarms the holder; the two-tap handoff transfers control.
- [ ] Holder's tab closed or WiFi dropped → the token auto-releases within the staleness timeout; outputs go safe.
- [ ] Kill the UI's WebSocket only (HTTP still up, e.g. restart the stream endpoint or firewall the WS port) while armed → the kill switch shows OFFLINE, stays tappable, and the tap disarms via the intent POST.
- [ ] `plugin.enabled` observed in the SK data browser with two UIs open and disagreeing — it must be stable, never oscillating.
- [ ] Power one unit off → its controls grey out and the arm control names it, while the other unit still arms and works.
- [ ] Power both off → arming refused, reason stated.
- [ ] TX LED, one blink vocabulary across the boat: disarmed with a unit reachable → slow ~1 Hz "ready to be armed" blink; armed with both units on → solid; armed with one off → ~2 Hz warn blink within ~1.5 s; no unit reachable → ~5 Hz fault blink; TX's own WiFi off → LED off. A solid or slow "ready" light must never appear with no unit reachable.
- [ ] RX LED: SK connected but nothing armed → slow ~1 Hz "ready to be armed" blink; RX armed with a remote source live+enabled → solid; master enable ON but the neutral interlock refusing → fast ~5 Hz blink; RX's own WiFi off → LED off. (Local switches still drive the servos regardless.)
- [ ] HH LED: SK connected, disarmed or armed-idle → slow ~1 Hz blink; `HOLDING` → solid; IMU frame timeout → fast ~5 Hz blink; HH's own WiFi off → LED off. Dark must mean the same thing on all three units — *cannot be armed right now*.
- [ ] Plugin UI armed while RX refuses to arm → confirm what the UI shows. **Known gap:** the plugin does not yet read `rx.armed`, so a phone can hold the arm token and show live controls while RX is refusing on a lever. RX fails safe, but the UI is optimistic — see the follow-up in JOURNAL.md.

### Before trusting the Android station

Do these with the drives mechanically disconnected, or the thruster's motor
power isolated, or the boat otherwise safe to move. The app is a client of the
arbiter, so it inherits the arbiter's guarantees — what these checks cover is
the wiring between `core/` and a real phone, which no host test can reach.

Run them **on the phone that will actually be used**. Multi-touch, background
policy and doze behaviour are per-device, and a check passed on a different
handset is not evidence about this one.

**The three that cannot be inferred from a green test run:**

- [ ] **Two-handed.** Port FWD and stbd REV held by two fingers at the same time → both `plugin.port.command` and `plugin.stbd.command` hold their values simultaneously in the SK data browser. This is what `ui/Momentary.kt` exists for and the one thing an ordinary `clickable` would silently break. Repeat with the two thruster buttons, and with a drive and the thruster together.
- [ ] **Fail-safe on losing the foreground.** Armed, holding a drive button: press Home, then repeat with the screen locked, then with the app switcher open, then with an incoming call. Each must drive the command path to `neutral` — **check the path value in the data browser, not that the button looks released.**
- [ ] **Exclusive arm across station types.** Arm on the phone → the browser UI shows IN USE and cannot arm. Disarm from the browser → the phone drops to disarmed and says so. Then the reverse. The two-tap handoff must work in both directions.

**Loss of link, which is the phone's most likely failure:**

- [ ] Armed and commanding, then WiFi off on the phone → the arbiter stale-evicts within the staleness timeout and the outputs go safe. The app **stays on the control screen** throughout (it must: its disarm has to keep working when it cannot see the boat).
- [ ] WiFi back on → the stream reconnects on its own and the app does not silently re-arm. Re-arming must take a deliberate press.
- [ ] Walk the phone out of WiFi range while armed, then back in. Same expectations, over a real dropout rather than an airplane-mode toggle.
- [ ] Power one output unit off → its controls grey out and the arm control names it, while the other still arms and works. **Known gap:** with one unit missing but the other still armable, the Android kill switch says only "tap to arm" and names nothing — the collapsed telemetry summary is currently the only place that state is stated. Confirm the operator can actually tell.

**Authority — none of this has been exercised against a real server:**

- [ ] **Revoke the token** in the Signal K admin UI while the app sits on the control screen → within about a second (past the auth-scheme probe) the session ends, the app returns to the server screen and says why. It must **not** keep presenting a control panel. This is the check the token-recovery work was written for and it has never been run.
- [ ] Revoke the token **while armed** → same, and the arbiter releases the arm token rather than leaving it held.
- [ ] Tap Disconnect while armed → refused, with "Disarm before changing server". Disarm, then Disconnect → a final all-neutral intent is sent and the session ends cleanly.
- [ ] Point the app at a second Signal K server → it asks for a new access request rather than carrying the first server's token into a 401.
- [ ] Type a public address (e.g. `example.com:3000`) → Connect stays greyed out and the reason is given. Tap a discovered service advertising a non-private host, if you can produce one → refused the same way, from the ViewModel rather than the text field.
- [ ] `https://` to the same server → allowed unconditionally, and works.

**Before relying on it at the helm:**

- [ ] **Remeasure controls with telemetry expanded.** The measured 4.7 dp collapse is fixed in code by reserving at least 280 dp for the drive bank and scrolling the full panel on short screens, and `./gradlew :app:testDebugUnitTest` now asserts all of it on every CI run — including that the kill switch never scrolls away. That is a model of the reference phone, not the phone: Robolectric knows its density but not its system bars, display cutout, gesture insets or the operator's font-scale setting, any of which can take room the test believes is there. So still, on the actual phone: expand telemetry and confirm every FWD/REV contact remains at least 88 dp high and scrolls without overlapping another live control.
- [ ] Screen brightness, glove use and direct sunlight on the actual helm, with the boat's motion. A control that cannot be hit reliably is a safety problem, not an ergonomics one.
