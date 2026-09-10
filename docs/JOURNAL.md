# Work Journal

## 2026-09-10 — The version number the spinoff broke, found on a real phone

Installing the rewritten token store on hardware turned up a defect that no
build, suite or workflow here could have caught.

**`versionCode` went backwards.** It is the commit count. The predecessor
repository reached 156 and phones carry versionCode 153 from it; this repository
is at 8. Android refuses a lower versionCode outright, so every existing station
would have been unable to update — and the only way through would have been an
uninstall, which clears the Signal K token. The release pipeline had no defence:
`release.yml` and `app/build.gradle.kts` both derive the version from the commit
count, so they agreed with each other and were both wrong. The consistency check
between them proves they match, not that they are sane.

Fixed with an offset of 200 in both places, documented as not removable —
lowering it later would re-break any phone that installed a build made while it
was there. Version is now 0.208.

**Then the migration was proven.** The signed, R8-shrunk 0.208 was installed over
0.1 on a Galaxy S25 (Android 16, SDK 36) as an in-place upgrade — same release
key, so app data survived. It logged `SecureStoreMigration: moved 4 value(s) out
of the legacy secure store` and came up on the control screen with `LINK
connected`: the token moved from Tink's EncryptedSharedPreferences into the
Keystore-backed store and still authenticated against the live server. No crash,
no decrypt failure, no dropped writes. That is the part 12 JVM tests could not
reach, because Robolectric has no AndroidKeyStore.

Worth recording how the phone was found at all: it was advertising over mDNS but
not connected, and `adb devices` lists only connected devices. `adb mdns
services` showed it, and `adb connect` brought it up. Two other devices were
already attached and neither was the S25 — a Nokia 7.2 sitting exactly on the new
minSdk 30 floor, and a Galaxy S20 FE.

## 2026-09-10 — Spinoff: every reference re-pointed, and a credential the move made public

Session: reference audit of the new standalone repository

> **Redaction note.** This file is committed and this repository is public. Every
> literal WiFi SSID, WiFi password, OTA password and boat network address that
> appeared in entries below — written when the predecessor repository was private —
> now reads `<redacted-ssid>`, `<redacted-ota-password>` or `<redacted-sk-server>`.
> Generic example addresses (192.168.0.100, SensESP's 192.168.4.1 setup AP) are
> left as they are. Nothing else was altered. The
> values themselves are burned regardless; see BUILDING.md §8.

`drive-remote-controller` was split out of `SensESP-remote-controller`, taking
only `projects/drive-remote-controller/` plus the workspace's `LICENSE`,
`SECURITY.md` and `.gitignore`. The code came across intact — `pio test -e
native` 268/268, the plugin 268/268 and `:core:test` 171/171 all pass at exactly
the documented counts, and the Signal K path contract is still in step across
`config.h`, `config.ts`, `SkContract.kt` and `index.cjs` — but every document
was still describing the monorepo it left.

**The finding that is not a documentation problem.** The repository was made
public on GitHub the same day, and `esp32/platformio.ini` still carries
`upload_flags = --auth=<redacted-ota-password>` — the OTA password, which is *the same
string as* `SECRET_WIFI_PASSWORD`. That file is not gitignored, so the first
push publishes the password that authorises reflashing boards wired to a clutch
and a thruster contactor, and the boat's WiFi password with it. BUILDING.md §8
had this exactly right as a precondition — "before this repository is made
public… rotate the OTA password" — and the order was reversed. §8 is rewritten
as overdue work rather than a precaution. **Nothing has been pushed; the
rotation is still owed.**

**The premise that flipped.** `.gitignore` gained `secrets.h`, so six documents
plus the template's own header still said it was committed "on the basis that
the repository is private". Worse, the release pipeline *depended* on that:
`check_no_secrets.py --ref HEAD:…/secrets.h` reads its reference values out of
git, and against an untracked file it exits 2 and fails closed — every release
blocked. The guard now reads `platformio.ini`, which is the one credential still
committed and the file the script's own comments already predicted would be left
behind ("cleaning up secrets.h without also fixing platformio.ini cannot quietly
leave this guard with nothing to look for"). The release job additionally
*asserts* no `secrets.h` reached the runner rather than copying the template over
it. Self-tested both directions locally: placeholder binary passes, a binary
carrying the real `--auth=` value is rejected.

Two CRA claims got better rather than worse, because they were gated on privacy:
code scanning is free on a public repository, and so are artifact attestations.

**Everything else, mechanically.** All four workflows carried
`projects/drive-remote-controller/` in every `working-directory`, `hashFiles`
and cache key; `release.yml` had it once more as `PROJECT_DIR`. Seven markdown
links pointed outside the repository (`../../../SECURITY.md`,
`projects/drive-remote-controller/docs/…`). BUILDING.md told the reader to `cd
../..` to a workspace root that no longer exists and to run `./run init`. The
`run`, `serial_monitor.py` and `web_log_monitor.py` helpers were dropped from
the spinoff on purpose, so their CI job, the shellcheck paragraph and every
invocation are gone — `pio device monitor` and `curl -N .../api/log` replace
them, and the CI job now covers `esp32/scripts/` and self-tests the credential
guard. ARCHITECTURE.md's board reference pointed at the workspace's
`docs/hardware/SH-ESP32.md` and now points at Hat Labs. `esp32/.vscode/` held
absolute paths into the old checkout.

The EUPL-1.2 `LICENCE` was referenced by nothing at all: README gained a licence
section and `sk-plugin/package.json` gained `license`, `repository`, `bugs` and
`homepage`, having previously declared none while being packed for publication.

Three carried-over artifacts were then cleaned out by the owner: the stale
`sk-plugin/signalk-drive-remote-controller-1.0.0.tgz` (`npm pack` output, and
already covered by `sk-plugin/.gitignore`'s `*.tgz` — it was clutter rather than
a leak); `.claude/skills/new-project/`, workspace
scaffolding that does not apply to a single-project repository; and
`esp32/.pio/`, 1.2 GB of build cache still holding absolute paths into the old
checkout. `android/app/build/` and `android/.gradle/` still hold 50 such paths,
which is harmless — they are gitignored and Gradle relocated without complaint.

**Then the duplicate credential was removed rather than only documented.** The
owner asked whether `platformio.ini` could take the OTA password from
`include/secrets.h` instead of carrying its own copy. It can:
`platformio/builder/main.py` runs `pre:` extra scripts, then the platform builder,
then `if "UPLOAD_FLAGS" in env: env.Prepend(UPLOADERFLAGS=["$UPLOAD_FLAGS"])` —
so a pre-script that sets `UPLOAD_FLAGS` reaches the uploader by exactly the path
the `upload_flags` option uses. `esp32/scripts/ota_auth.py` does that: it reads
the one `SECRET_OTA_PASSWORD` in the gitignored `secrets.h` — the same `#define`
`enable_ota()` compiles into the board — and injects `--auth=`. It is inert
unless an upload target was named, so CI still builds all three firmwares on a
checkout with no secrets file, and it stops with an explanation rather than
attempting an upload the board would reject. Verified against a stub SCons
environment on all four paths, and the password is never printed.

`platformio.ini` now names no credential, so **nothing committed to this
repository holds one.** The release guard degrades honestly rather than breaking:
with nothing to look for it says so and passes, and the guarantee moves to the
release job's assertion that no `secrets.h` reached the runner. The guard is kept
as the backstop, and CI's self-test now uses a synthetic fixture plus a positive
assertion that `platformio.ini` still holds nothing — so writing a credential
back into it goes red.

That fixes the leak forward and fixes nothing behind. The value in use was
committed to the predecessor repository, and the same string was the WiFi
password; rotation is still owed.

**The board addresses followed it**, on the same reasoning: the three
`upload_port` lines were the boat's network written out in a public file. They
are now `SECRET_OTA_HOST_TX|RX|HH` in secrets.h, chosen by `$PIOENV`. Doing that
exposed a defect in the first version of the script, which the tests caught
before hardware did: it appended `--auth=` unconditionally, and BUILDING.md §4.2's
serial procedure — the first thing a newcomer with a virgin board follows — works
by commenting `upload_protocol = espota` out, which leaves **esptool**, which
rejects an unknown flag. The script now gates on the protocol, mirroring the
auto-switch to espota that platform-espressif32 performs when an unset protocol
meets an IP or `.local` port, so it agrees with whichever uploader actually runs.
Fifteen cases now cover it, including both serial paths and each unit's address.

**And then `config.h` followed.** `kSkServerAddress` — and `kSkServerPort` with
it — was the last piece of the boat's network in a committed file. Unlike the
upload settings this one is *compiled in* and used at runtime, so the build
script could not carry it: `config.h` now does the same
`#if __has_include("secrets.h")` dance the three mains do and reads
`SECRET_SK_SERVER_ADDRESS` / `SECRET_SK_SERVER_PORT`. It includes the pair
itself rather than trusting its includer's order — both files are `#pragma
once`, so the mains including them first costs nothing. The pure core never
includes `config.h`, so `pio test -e native` is untouched.

Verified both ways round, because the fresh-clone path is the one that breaks
silently: `rx_shesp32` builds with a real `secrets.h`, and builds again with it
moved aside, falling back to `secrets.example.h`'s `signalk.local:3000`. That
second case is what CI does on every push.

**Nothing committed to this repository now holds a credential or names the
boat's network.** The journal's own historical entries were the last holdout and
are redacted; `192.168.0.100` and SensESP's `192.168.4.1` setup AP stay, being
generic examples rather than this boat.

**Android release and R8, documented from a run rather than from memory.** The
owner asked for fuller release-build steps and for what enabling R8 would take,
in both `android/README.md` and BUILDING.md section 6. The release procedure is
now six steps ending in *verify it is actually signed by the key you meant*,
since `BUILD SUCCESSFUL` proves neither half of that.

Two things were wrong before checking them. The README's example test count said
`core: 147 tests`; it is **171**. And the keep-rule guidance claimed
`androidx.security:security-crypto` ships its own R8 rules — unzipping the cached
artifacts says it ships **no `proguard.txt` at all**, and `tink-android-1.8.0`
carries only protobuf rules. That is the row that matters, because `SettingsStore`
keeps the Signal K token in `EncryptedSharedPreferences`: a stripped Tink key
manager reads as a server-side auth failure, in the release build only.

Then R8 was actually turned on, built, and reverted (AGP 8.7.3, 2026-09-10;
`build.gradle.kts` verified byte-identical afterwards). It builds clean with the
documented three-line change plus a blunt Tink keep block, no warnings, and the
APK drops from 8,239,654 to 1,947,599 bytes — 76% smaller, which is a larger
saving than the section previously implied. But **20,682 of the 21,560 kept
entries are Tink**: 96% of what survives is held by that one guessed rule, so
almost all the remaining shrinking depends on narrowing exactly the rule whose
failure mode is silent and release-only. The APK was never launched, and
`:app:testDebugUnitTest` measures the *debug* variant, so the layout floors —
the check standing between an operator and a 4.7 dp STOP button — still would not
see a shrunk build. `mapping.txt` is 30 MB and would have to ship with every
release for a crash report to be readable. **R8 is now on, and that is an owner decision made on hardware.** The owner
enabled it, hit the failure, and then tested the result: the shrunk APK
fresh-installed on a phone, a **new Signal K access request** granted, armed
against the live server, and a drive and the thruster commanded. That is exactly
the path the Tink risk threatens, so the objection the section was built around —
"shrinking an unverified codebase" — stopped applying by ceasing to be true.

The failure on the way there is worth recording, because it costs an afternoon if
you meet it cold. `proguard-rules.pro` did not exist while `buildTypes.release`
named it; that is only a warning, several screens above the real error. The real
error is that **Tink carries references R8 cannot resolve, each a hard ERROR, in
three families that R8 reports ONE PER BUILD**: `com.google.errorprone.annotations.*`,
then `com.google.api.client.http.*`, then `org.joda.time.Instant` — the last two
from `KeysDownloader`, which this app never uses and which survives only because
the blunt `-keep class com.google.crypto.tink.**` keeps it. Naming the missing
packages one at a time is rebuild-and-see; `-dontwarn com.google.crypto.tink.**`
covers all three at once, because `-dontwarn` suppresses unresolved references
*originating in* the named classes.

That single line is also what my own first experiment had, and I removed it when
writing the rules file for the owner — inferring the package to silence from the
first error message instead of keeping the line already proven to work. It cost a
second failing build. Both documents carried the same wrong rule set and now
carry the right one, with the three families tabulated.

`mapping.txt` (30 MB) is now published with each release as
`DriveRemoteController-<v>.mapping.txt.gz`, with a checksum, and the release job
fails if R8 did not produce one. Without the map matching that exact APK a crash
report off the boat is unreadable, and the runner's build directory does not
survive the run.

**Still open, and now the only objection left:** `:app:testDebugUnitTest`
measures the DEBUG variant, so the layout floors have never seen a shrunk build.
Recorded in the README's *Known gaps* rather than left implicit.

**Release signing wired to GitHub, and the whole CI matrix rehearsed locally.**
The release workflow already supported signing and simply had no secrets. All
four, plus the `DRIVEREMOTE_CERT_SHA256` variable, are now set from
`~/.driveremote/`. The runner's path was rehearsed rather than assumed: base64 →
decode → byte-identical to the keystore, opens with the stored password, and its
certificate fingerprint equals the variable — so `apksigner`'s check at the end
of the job passes rather than failing after a ten-minute build.

Every CI job was then run locally under CI's own conditions, which for the
firmware means **with `secrets.h` hidden**, since `config.h` now includes it:
all three envs build against `secrets.example.h`; plugin 268/268 through
`npm ci`/`tsc -b`/build; `:core:test` green with `ANDROID_HOME` blanked *and*
`local.properties` hidden; `:app` 59/59 plus `assembleDebug`; the release-script
job both directions.

**That rehearsal found a real hole, in `.gitignore`.** Simulating a clean
checkout meant renaming `secrets.h` to `secrets.h.ci-sim` — and the pattern
`secrets.h` does not match a renamed copy, so the boat's live credentials became
stageable in a public repository. `git add -An` caught it before anything was
committed. This is not an artefact of the test: `secrets.h.bak` is what anyone
makes before editing the file. The pattern is now `secrets.h*`, with
`*.keystore`, `*.jks`, `keystore.properties` and `local.properties*` added as
repository-wide backstops beneath `android/.gitignore`'s copies.

`codeql.yml`'s header still described the repository as private and said going
public required the credential rotation first; corrected. Code scanning is free
on a public repository but still has to be switched on in the settings, so the
analysis stays gated behind `ENABLE_CODE_SCANNING` and CI stays green.

The two skills that remain, `build-flash` and `review`, were carrying the same
monorepo assumptions and are re-pointed: they looked for `projects/<name>/`, read
a `SPEC.md`, a `docs/hardware/<board>.md` and a `system-profile.md`, none of
which exist here. `build-flash` was also wrong about this repository twice over —
it walked the user through USB flashing, when `-t upload` is `espota` by default
here, and closed with "Celebrate!" on a system that commands a clutch. Both now
open on SAFETY.md and say what a green result does not prove.

## 2026-09-02 — Review: the 49.7-day wrap, and a rest value that was a live command

Session: code review (all five parts)

An owner-requested review of the whole tree, no defect reported. The three
firmwares, the plugin and the Android station were read end to end; the
findings below are the ones that survived being traced through the code
rather than pattern-matched, ranked by what they could do to the boat.

**Firmware: three timers that came back to life after ~49.7 days.** Every
liveness check in the pure core is `now_ms - last_ms <= timeout` on `uint32_t`,
which is the right idiom for the millis() rollover — and wrong for a *gap*
longer than 2^32 ms. `LinkWatchdog` documented the limit as "not a real
constraint for this project's timeouts (seconds, not days)", but the timeout is
not the quantity that grows: the gap since the last update is, and a TX left
switched off in a drawer while RX sits powered on the house bank is exactly
that. At the wrap its retained tuple read LIVE for one whole timeout window —
with `enabled: true` still standing if the kill switch was left on when the
battery was pulled, which on HH is an engage request. `RvcParser::TimedOut` had
the same window (a dead BNO reading healthy for 250 ms, one FAULT→ARMED
transition nobody asked for), and `HeadingFilter` had two: past ~24.8 days the
signed fix-to-fix interval went negative and **every returning GNSS fix was
rejected as out-of-order until reboot** — an HH left powered through a winter's
outage never accepted a heading again — and at exactly 49.7 days the
accepted-correction age wrapped to ~0, so `heading_valid` came true on a
seven-week-old reference for one `t_fresh` window: armable in HOLD on pure dead
reckoning. All three now latch: a stale verdict forgets the update it was
judged on (`IsLive`/`TimedOut` are no longer const, and neither are the
`Snapshot()` methods that call them), and the heading reference expires after
an hour (`HeadingFilter::kReferenceMaxAgeMs`, checked every tick from
`ControlStep::Step` and again inside `Correct`). Behaviour inside the first
hour is byte-for-byte what it was; the gain at a one-hour gap was already ~1,
so a re-seed is what a correction would have done anyway.

**Firmware, smaller:** HH's `hh.linkUp` published `source != kNone`, i.e.
*live and enabled* — a disarmed station's heartbeat read as "link down", while
`rx.linkUp` (and ARCHITECTURE.md's own table) mean *any source live*. Now the
same definition on both units, computed in `Tick()` from the cached snapshots.
HH's control task is subscribed to the Task Watchdog exactly as RX's is (the
esp_timer fail-off already forced the outputs off on a stall, but left the
board wedged until a power cycle). Two comments in `hh/control_task.cpp` still
described the world before the MANUAL dwell went to 0 and before the setpoint
mirrored the fused heading; both corrected.

**Plugin, HIGH: the quarantine's "safe" thruster mode was a live command.**
While a holder existed but HH was not commandable (absent, or quarantined after
an absence) the arbiter published `{enabled: true, thrusterMode: 'hold',
thruster: 'off'}`. On HH that tuple is `engage_request = true, mode = kHold`,
which enters HOLDING the moment the heading is good — automatic thrust pulses
off a returning telemetry frame, with the operator's UI showing MANUAL. The
everyday path needs no dropout: arm with HH switched off (allowed, §6.5), power
HH on, and it engages HOLD on boot for the few hundred ms until the holder's
mode is stored again. `'hold'` is the right default for *garbage* (it can never
become a direct thrust) and for the no-holder tuple (enabled=false makes it
inert); it is not a rest value beside `enabled: true`. The rest mode while armed
is now MANUAL with direction OFF (`REST_MODE_WHILE_ARMED`), which HH turns into
ENABLE asserted and both direction lines low — an armed MANUAL operator with no
finger down. Vectored in `arbiter.test.ts`.

**Plugin, HIGH: a held thruster contact survived MANUAL→HOLD→MANUAL.** The
PORT/STBD contacts unmount in HOLD but their `useMomentaryButton` hooks live at
component level, so a finger still down on PORT when the other hand tapped
HOLD never delivered its pointerup (React dispatches nothing to an unmounted
element). Back in MANUAL the contact remounted already pressed and the effect
re-commanded port with nobody touching it; the only exits were blur, tab-hide
or the kill switch. The hook now exposes `release()`, and leaving MANUAL calls
it for both contacts — the same rule as the disabled force-release. Pinned in
`ThrusterControl.test.tsx`.

**Plugin, MEDIUM: the Commands lamp reported a proxy.** It was driven only by
`/skServer/loginStatus`, a statement about the session made before a command
was sent, and every intent POST rejection was swallowed. A readwrite login on
a server whose plugin route fell back to admin-only (every POST 403), a
disabled plugin (503) or a cookie expiring mid-session (401) all read
"reaching boat" while a STOP tap did nothing. The POST outcome now drives the
lamp (`src/pure/intentStatus.ts`), outranking the proxy in both directions,
and the request carries a 2 s timeout so a server that stops answering cannot
queue an unbounded backlog of stale intents with a STOP at the back of it.
Also: `react`/`react-dom` moved to devDependencies (Vite inlines them; the
shipped closure is the three `.cjs` files), and four places where the README
or a comment described code that had since changed.

**Android:** an address OkHttp cannot build a URL from ("my boat", a `%` zone
id) crashed the app on *Request access* — and again on every relaunch, since
it had already been persisted. `ServerAddress` now refuses such hosts in the
pure core, so `parse()` returns null and the Connect button never enables; the
three `Request.Builder().url()` calls are inside their `runCatching` as
belt-and-braces. A pending access request is now cancelled on `useServer()`
and `teardown()`, and `onApproved()` applies only to the server and stage the
operator is still waiting on — a late approval for server A after switching to
B used to open the stream to A while every intent went to B. `changeServer()`
refused while "armed" even after a token revocation had ended the session
(the retained `activeClient` still named this station; the refusal was not
even rendered on that screen), so the gate is now `stage == Ready &&
armed`. `sendIntent()` refuses outside `Stage.Ready` and uses the
server-bound token, so a press queued behind a stalled POST can neither end
the session twice nor carry the old server's token to the new one.
`parseRequestResponse` resolved a duration-style expiry against epoch 0 on a
server that approves immediately; it takes `nowMs` like the poll path.

**Left as findings, not changes:** the firmware's "unrecognised mode reads as
`hold`" rule (`ThrusterModeFromSkString`) has the same shape as the plugin
defect above — with a live, enabled source a garbage mode engages an automatic
hold rather than the inert MANUAL/OFF — but it is a documented cross-codebase
contract (config.h, ARCHITECTURE.md §6) and changing it is the owner's call.
Android taps while paused-but-visible advance counters without a send
(bounded by the arbiter's re-registration baseline, so at worst a tap is
swallowed).

**Verification.** `pio test -e native` 268 (was 262: two watchdog latch cases,
one RVC latch, two heading-filter expiry cases, one ControlStep scenario that
walks the clock through the wrap). Plugin 268 (was 258): `tsc -b`, `npm test`,
`npm run build` all clean; act() noise in the App suite is *lower* than before.
Android `:core:test` 171 — two new cases, and the count the documents carried
(160) was already stale before this session. `hh_shesp32` built locally once
the container's re-signing egress proxy was trusted by pioarduino's own penv
certifi (it overrides `REQUESTS_CA_BUNDLE`, which is why the usual CA settings
did nothing); all three firmware builds, `:app`'s layout suite and the rest
are green on CI for the pull request. Nothing flashed; the HH watchdog
subscription in particular wants a bench check before the next trial.

---

## 2026-09-02 — The tablet launched letterboxed and then grew, at every start

Session: tablet layout

- **Owner report:** "on landscape mode, very shortly before loading control
  screen it shows old size of app landscape application area in black and then
  enlarges to full screen". And then, exactly right: "when application is
  starting it knows is it portrait or landscape mode, and application should
  adapt to that right away".

Self-inflicted, and I had written down the risk without taking it seriously
enough: "on a phone the activity starts portrait and immediately switches to
USER — a brief flip at cold start. Minor." It is not minor on a tablet, because
there the flip is not a rotation but a **resize out of a letterbox**.

The manifest kept `screenOrientation="portrait"` as the launch value, on the
reasoning that a phone must never come up sideways even for a frame.
`MainActivity` then lifted it to `SCREEN_ORIENTATION_USER` on tablets. So on a
landscape tablet the activity's window — including the splash — was *created*
portrait-shaped and letterboxed, and the unlock in `onCreate` resized it a
moment later. A small black rectangle that visibly grew to fill the glass, every
launch.

**The fix is to declare nothing.** `android:screenOrientation` is one value for
every device and the answer here depends on the device, so the manifest says
nothing at all and `lockOrientationForFormFactor()` says it instead — PORTRAIT
below 600 dp, USER at or above, measured from `maximumWindowMetrics`. The window
is then created at the size the device is already at, and the call that follows
agrees with it rather than correcting it.

That is the owner's sentence implemented literally: the system already knows the
orientation at launch, so stop overriding it and then changing our mind.

**Measured, with a temporary probe on `onConfigurationChanged`:**

```
landscape cold start   onCreate bounds=Rect(0, 0 - 1920, 1200)    no resize
portrait  cold start   onCreate bounds=Rect(0, 0 - 1200, 1920)    no resize
rotate the running app                                            RESIZE 1200x1920 -> 1920x1200
```

Full size from the first frame in both orientations, and the only resize left is
the one a deliberate rotation should produce — handled without recreation, since
`configChanges` covers `orientation|screenSize`. Probe removed, clean build
reinstalled and re-checked, auto-rotate restored on the device.

**What it costs, stated plainly.** A phone whose owner has auto-rotate on and is
holding it sideways at launch will now come up landscape for a moment before
snapping upright — the artefact moves from tablets to that one case. It is the
right trade: a tablet in landscape was hitting it on *every* launch, and the
phone case ends in the correct portrait lock either way. Phones are still locked
upright once running, which is what was asked for.

**This is the fourth spelling of this decision**, and the three dead ends are
each recorded in android/README.md so nobody re-walks them: a `values-sw600dp`
resource on the manifest attribute (never consulted — the package manager
resolves the manifest without a device configuration), a runtime
`configuration.smallestScreenWidthDp` check (describes the letterboxed pane, not
the display, so it reads "phone" exactly when it must not), and launch-locked
with a runtime unlock (this entry).

## 2026-09-02 — The OFFLINE flash again: the rule was right, the number feeding it was zero

Session: tablet layout

- **Owner report, after the LinkPhase fix shipped:** "still very short period
  offline with yellow colour and then text changed to DISARMED". And, on the
  blank start-up screen: "if you added some delay there it is not working at
  all. It delays start but does not remove OFFLINE banner."

Both observations were right, and the second one is the more useful: the delay
did not help *because the flash was not a timing problem at all*.

**`LinkPhase` was correct and was being fed a zero.** `SkStream.targetSetAtMs`
— the moment this station started trying, which the grace window is measured
from — was initialised to `0L` and only set inside `connect()`. But there is a
real gap between the ViewModel being built and `connect()` being called: the
stored server has to come out of DataStore first, and `startTicker()` begins
recomputing the view immediately. Every derivation inside that gap computed

```
connectingForMs = elapsedRealtime() - 0
```

which is the device's entire uptime — hours — and therefore far past the 4 s
grace. The phase came out **OFFLINE**, and the control screen's first frame
rendered the stale amber view before the next tick corrected it.

So the blank `Stage.Starting` screen delayed the launch and then handed over to
a screen that had already decided it was offline. Exactly what was reported.

The fix is one initialiser: the clock starts when the *station* starts, not when
the socket is first told to open. That is also the more honest reading of "how
long has this station been without a link".

Two supporting changes, both of which stand on their own:

- `refreshView()` immediately after `stream.connect()`, so `Stage.Ready`'s first
  frame is not up to one ticker period stale.
- `stream.connectionState` is now collected and refreshes the view, like
  `revision` already was. The link changes without a delta, so it was only ever
  picked up on the next 250 ms tick. This matters far more in the other
  direction than at startup: **a stream that drops mid-manoeuvre now reports it
  immediately instead of up to a quarter-second late.**

### Measured, not asserted

A temporary `LinkProbe` build logged every view the UI was handed. One cold
start, the whole timeline:

```
t+0     stage=Starting  phase=CONNECTING  conn=CLOSED       (nothing drawn)
t+157   stage=Ready     phase=CONNECTING  conn=CONNECTING   first control frame
t+691   stage=Ready     phase=ONLINE      conn=OPEN         DISARMED
```

OFFLINE does not occur. The control panel shows the grey CONNECTING for ~530 ms
and then the grey DISARMED — a change of wording, not of colour. The probe was
removed and the clean build reinstalled and re-checked.

**The test needed a positive control too, and failed to be one at first.**
`SkStreamLinkClockTest` asserts the pre-connect state. Written naively it passed
against the bug: Robolectric starts `elapsedRealtime` at zero, so the broken
initialiser — also zero — reads as "connecting for 0 ms" and looks perfectly
healthy. It only reproduces on a device that has been up a while, which is every
real device. Winding the shadow clock forward six hours first is what makes it
catch anything; with the bug restored it now fails, and it is the reason that
line is in the test.

### The start-up delay, and a correction

I had twice named `SettingsStore`'s keystore work as the suspect for the slow
start. **Measured, it is not.** Instrumenting the constructor:

```
clientId                 158 ms
nextSessionGeneration     17 ms
ViewModel ctor total     176 ms
Displayed             +1s438ms
```

Against the absolute log timeline, ~1.11 s of that 1.44 s is gone *before our
code reaches the ViewModel at all* — process start, class loading, Compose
init — and most of that is a debug build: no R8, no baseline profile,
`debuggable=true`. 176 ms of main-thread keystore is worth moving off the main
thread eventually, but it is not what makes the launch feel slow, and rewriting
the session-generation ordering to chase it would have been effort spent on the
wrong 12%.

Deliberately NOT done: holding the control screen until the link answers, which
the owner offered as an option. It would remove the 530 ms of CONNECTING, at the
cost of 530 ms more blank screen and a later STOP button — and a longer blank
start is the thing they had just objected to. A calm panel that says what it is
doing beats a dark rectangle that says nothing.

### Still open

**`sk-plugin` has the same defect** — `KillSwitch.tsx` reports "connection lost"
before it has ever had a connection. Unreported and untouched; `LinkPhase`
should be ported to `pure/` properly rather than patched in.

## 2026-09-02 — The real flicker was the STOP button crying wolf at every launch

Session: tablet layout

- **Owner report, after the previous fix landed:** "it is shortly saying
  'Offline' and yellow banner there so that was my flicker cause. App might be
  starting before it has data available that shows it is really online, not
  offline."

Right on both counts, and the second sentence is the diagnosis. The previous
entry removed the setup-screen flash; underneath it was a second one, and this
was the one being seen.

`KillSwitch` branched on `!view.connected`, which is `connectionState != OPEN`.
For the few hundred milliseconds the first WebSocket takes to open, that is
true — so every launch drew the full amber **OFFLINE** panel and then flipped to
DISARMED. The same instant lit the LINK lamp amber then red, and named both
units in red as "not responding", which they cannot be said to be before there
is a stream to hear them on.

**Both readings were true and both were wrong.** The socket really was not open.
But "not open yet" and "was open and is not now" are the same fact about
completely different events, and only the second is a warning. A warning that
fires at every single start is one the operator learns to look past — which
costs exactly the times it means something, and this one was attached to the
STOP button.

**The fix is a type, `LinkPhase`, in `:core`.**

```
ONLINE      the stream is open
CONNECTING  never yet open this session, within LINK_STARTUP_GRACE_MS
OFFLINE     was open and is not now, or has been trying long enough to be a fault
```

`ConnectionState` stays exactly what it was — the fact, and still the thing every
command gate reads. `LinkPhase` is the reading given to the operator, and the
two differ in precisely one place.

**Presenting CONNECTING calmly is safe, and provably rather than by assertion.**
Arming requires `canArm`, which requires unit liveness, which requires an OPEN
socket — so this station cannot be armed or commanding before its first open.
The only other way a session starts connecting afresh is `changeServer`, which
refuses outright while armed. There is no state in which CONNECTING can hide a
live command. That argument is what makes this a presentation change rather than
a softened warning, and it is why the grace is keyed on **everConnected** rather
than on a timer alone.

Three details that are each a defect avoided:

- **The grace is keyed on "never yet open", not on `ConnectionState.CONNECTING`.**
  A failed first attempt sits in CLOSED between retries, and keying on
  CONNECTING would flash the alarm through every gap on the way.
- **The clock runs from when the target was set, not from the latest attempt.**
  Restarting it per attempt would keep an unreachable server in "connecting…"
  for as long as the backoff kept trying, which is forever.
- **A drop after a session has been open gets no grace whatsoever.** That is the
  case the OFFLINE state was written for: the heartbeat keeps POSTing over HTTP,
  so the station may still hold the token and still be commanding while unable
  to see the boat.

The colour matters as much as the words: CONNECTING uses `DriveColors.disarmed`,
the same grey DISARMED uses. The transition a moment later is then a change of
wording rather than a change of colour, so the launch no longer reads as an
alarm going off and clearing.

**Verified on the tablet, both halves.** Sampling a 300 ms window over adb is
not possible — `uiautomator dump` round-trips in ~3.5 s here — so the window was
made long instead: Wi-Fi off, the socket cannot open, and the phases last
seconds.

```
t ≈ 1.5 s   CONNECTING. reaching the boat — disarm always works
t ≈ 8 s     OFFLINE. tap to STOP — disarm always works
```

Grace held, grace expired, and the fault still arrives for a boat that really is
unreachable. Screenshotted at t ≈ 2 s: grey panel, neutral "connecting…" lamp,
no red anywhere. Wi-Fi re-enabled and the reconnection confirmed before
finishing.

`LinkPhaseTest` pins the rule in `:core` — nine cases, including the asymmetry
that a drop gets no grace, and that the new `deriveStationView` parameters
default to the old cautious behaviour so an un-updated caller cannot silently
acquire a grace period.

### Still open

**`sk-plugin` has the same defect.** `KillSwitch.tsx` renders "connection lost —
reconnecting…" whenever the socket is not open, so the browser station shows a
*lost connection* before it has ever had one. Not touched: it is a different
station, nobody has reported it, and `LinkPhase` would want porting to
`pure/` properly rather than patched in. Worth doing, since these three stations
are meant to read as one system.

**And the main-thread startup work is now measurable.** `am start -W` reports
**~1.35 s** to first frame on this tablet. *(Measured properly in the entry
above: the keystore work is only 176 ms of it, not the cause. The suspicion
recorded here was wrong.)*

## 2026-09-02 — The setup screen flashed at every launch, for 377 ms

Session: tablet layout

- **Owner report:** "when android application is started and setup to connect sk
  server, it flickers something during start before control screen is loaded."

The something was the **setup screen**. `UiState.stage` defaulted to
`Stage.NeedsServer`, which is a *guess* made before the stored server has been
read — reading it is a suspending DataStore call. On a station that has been set
up the guess is wrong every single launch: `ServerScreen` composed, started an
mDNS browse, took a multicast lock, and was replaced by the control panel a few
frames later.

**Measured on the tablet, not estimated.** The setup screen has an observable
side effect — `MdnsDiscovery` takes a `MulticastLock` — so a cold start under
`logcat` dates the flash exactly:

```
19:46:56.968 WifiService: acquireMulticastLock uid=10323
19:46:57.345 WifiService: releaseMulticastLock uid=10323
```

**377 ms** of a screen the operator was never meant to see. After the fix, three
cold starts produce zero NSD or multicast lines at all.

That measurement is worth the detour it took. The fix produces an *absence* —
no flash — and an absence is exactly what a verification cannot prove by
looking. So the old default was put back, built, installed, and measured (the
lines above), then the fix restored and measured again. Without the positive
control, "I saw no NSD lines" would have been equally consistent with a grep
that matches nothing.

**The fix is a stage that shows nothing.** `Stage.Starting` is where a launch
begins, and `MainActivity` renders `Unit` for it. Not a spinner and not a
placeholder: the window background, the Compose surface and this state are all
the same near-black, so the whole start is one continuous dark screen that the
controls appear on. A spinner would only be a second thing to flash.

Two exits from it, because a blank screen with no way out is a worse defect than
the flash:

- **A 1 s reveal timer.** If reading settings is somehow slow enough to be
  noticed, the setup screen appears after all — a wrong guess beats a dark
  rectangle with nothing to tap. The read is deliberately *not* cancelled, so a
  configured station still reaches its controls when it lands.
- **A caught read failure.** The reveal timer is a child coroutine, so a thrown
  DataStore error would cancel it on the way past and strand the app on the
  blank stage — the one outcome `Stage.Starting` must never produce. Caught with
  `try`, not `runCatching`: the latter also swallows the `CancellationException`
  that means the ViewModel is being torn down, and would turn a normal shutdown
  into a "settings could not be read" notice.

`StartupStageTest` pins the default itself, since the whole defect was one. Two
assertions, no Android needed.

**Also verified on the tablet, at last** (adb was authorised this session): the
Lenovo TB328FU is 1200 x 1920 at density 240, so 800 x 1280 dp — exactly the
`TabletPortrait` qualifier the layout tests use, and 1280 x 800 turned. Both
arrangements confirmed on the glass: upright stacks with a full-width kill
switch, and landscape now **fills the screen** with the sidebar layout instead
of the letterboxed portrait pane. The orientation unlock works.

### Still open

**`SettingsStore` does keystore work on the main thread at startup, and this
was not touched.** `StationViewModel` initialises `clientId` and `session` as
property initialisers, so they run during ViewModel construction — inside
composition, on the main thread. Both touch the lazy `EncryptedSharedPreferences`
(Android Keystore `MasterKey` + Tink keyset read, and key *generation* on first
launch), and `nextSessionGeneration()` then does a synchronous
`SharedPreferences.commit()` — a blocking disk write.

That is a main-thread stall on every launch, before the first frame. It is not
the flicker the owner reported and it was left alone deliberately: the session
generation's ordering guarantee is safety-relevant (it must be on disk before
the first intent carries it — see the KDoc, and the bricked-station failure it
already fixed once), and making it asynchronous changes *when* the value is
available to the heartbeat. Worth doing, worth doing carefully, and worth its
own session rather than being smuggled into a flicker fix.

## 2026-09-02 — The tablet layout was choosing on width, and had it backwards

Session: tablet layout

- **Owner report:** two screenshots off a tablet, and one sentence. "Vertical
  view should look like now horizontal and horizontal more like sk-plugin webui
  view."

Both screenshots were wrong, and for two unrelated reasons.

**Upright, the tablet was getting the landscape arrangement.** `ControlScreen`
chose on width alone — `maxWidth >= 600.dp` — and a 10" tablet in portrait is
800 dp wide. So it got the drives-at-the-edges arrangement with a ~250 dp middle
column, in which "BOW THRUSTER" wrapped **one letter per line** down the left
edge and the fourth trim button (`+10°`) fell off the right. The reasoning in
the comment ("a 10" tablet held in portrait is 800 dp wide and is better served
by the three-column arrangement") was simply wrong: a tablet held upright is the
same *shape* as a phone held upright, and wants the same screen.

The fix is to choose on shape. `maxWidth > maxHeight` picks a landscape
arrangement; anything upright stacks, at any width. Which is also what the
browser UI has always done — its breakpoint is `min-width: 860px`, and a
portrait tablet does not reach it either.

**On its side, the tablet was letterboxed.** The second screenshot is the app
drawn into a portrait-shaped pane in the middle of a landscape screen, wallpaper
down both sides — `android:screenOrientation="portrait"` doing exactly what it
was asked to. The lock was kept deliberately, for phones, and that part stands:
a rotation mid-manoeuvre moves every control out from under the thumb that was
on it, and the owner confirmed unprompted that phones should not change. But it
was never doing anything useful on a tablet. It does not keep one upright, it
just makes the control panel smaller on the bigger screen — and from targetSdk
36 Android 16 ignores it at sw600dp anyway.

**The unlock took three attempts, and the first two failures are the
interesting part.**

*Attempt 1: a `values-sw600dp` integer resource on
`android:screenOrientation`.* The attribute does take a resource reference, and
the reference resolved — verified in the APK, `() 1` and `(sw600dp) 2`, exactly
as intended. The tablet stayed letterboxed anyway. The manifest is read by the
**package manager**, which resolves references without a device configuration,
so the qualified value is never consulted. A resource that is present, correct
and never read.

*Attempt 2: `resources.configuration.smallestScreenWidthDp >= 600` in
`onCreate`.* Caught before it shipped, by asking what that value actually is on
the device in the state being fixed. While an activity is letterboxed its
Configuration describes the **letterboxed pane**, not the display: roughly
355 dp on this 1920 x 1200 tablet. The check would have read "phone" and
declined to lift the lock — false precisely because of the condition it exists
to remove.

*What is there now:* `windowManager.maximumWindowMetrics.bounds`, which is the
whole display whatever shape the activity's own window has been squeezed into,
divided by density and compared against 600. The manifest keeps
`screenOrientation="portrait"` as the launch value so a phone never comes up
sideways even for a frame, and `MainActivity.allowRotationOnTablets()` lifts it
to `SCREEN_ORIENTATION_USER` — USER rather than SENSOR, because somebody at a
helm may have their rotation lock on deliberately.

The general lesson, which is not about orientation: *a device-shape check
evaluated from inside the window whose shape is the bug will tell you what the
bug wants to hear.* Ask the display, not the window.

**The landscape arrangement is now the browser UI's, not a third design.**
`SidebarControlScreen` is the Compose twin of the `min-width: 860px` grid in
`sk-plugin/src/index.css`: the same stack in the main column — kill switch,
thruster, both drives side by side holding the `1fr`/`weight(1f)` — with
telemetry lifted out into a 300 dp sidebar down the right. Telemetry goes
sideways rather than being squeezed because a landscape window is short on
height and long on width, which is the axis with room to give.

**The old edges arrangement survives as the short-landscape fallback.** It has
to: stacked, a 780 × 360 dp window cannot hold a kill switch, a thruster block
and two contacts at their floor — that comes to roughly 500 dp — and it would
push the contacts off the bottom and say so. Each drive column in the edges
arrangement carries nothing but its own two contacts, which is the only way
360 dp of height fits them. So the choice is three-way now: upright → stack,
landscape ≥ 500 dp tall → sidebar, landscape shorter → edges.

Measured (throwaway probe again, deleted once the assertions were written):

| window | arrangement | drive contact | telemetry |
|---|---|---|---|
| reference phone 360x780 | stack | 185 dp | below, 64 dp |
| 10" tablet 800x1280 | stack | **348 dp** | below, 137 dp |
| 10" tablet 1280x800 | sidebar | 229 dp | 297 dp column, full height |
| shortest sidebar 900x500 | sidebar | 88 dp | 278 dp column, full height |
| phone landscape 780x360 | edges | 93 dp | centre, below thruster |

**The two new assertions are structural, not dimensional.**
`assertStackedArrangement` compares *horizontal* spans: stacked, the thruster
block runs the full width and so overlaps both drives; in the edges arrangement
it is a middle column between drives pushed outward and overlaps neither. That
is the assertion that would have caught the original defect, and unlike a
vertical comparison it cannot be faked by centring. `assertTelemetryBesideTheDrives`
pins the other half of the sidebar layout. Both are cheap and neither depends on
a measured number that will drift.

`the shortest sidebar window keeps every contact on screen` runs at exactly
900 × 500 — on the threshold, deliberately, because that is the only case that
tells you whether `SidebarLayoutMinHeight` is set right. It measures 88.0 dp
contacts: on the floor with nothing to spare, which is what a boundary test
should look like.

26 layout cases, all green; `:core:test` and `:app:assembleDebug` green too.

**Verified on the tablet** — both orientations, later the same session once adb
was authorised; see the flicker entry above. Upright stacks, landscape fills the
screen with the sidebar layout. **Still not verified:** that the armed state and
the socket survive a rotation (`configChanges` covers `orientation|screenSize`,
so nothing should be recreated, but nothing was armed during the check), that a
phone still refuses to rotate at all, and the thing no test can answer — whether
it reads well at arm's length on a moving boat.

## 2026-09-02 — The boot-glitch fix bricked HH: digitalWrite() before pinMode() writes nothing

Session: HH boot loop

- **Owner report:** "esp32 hh on reboot loop in com5 due to your latest code
  changes." Correct on both counts. It was mine, from yesterday's review pass.

HH panic-looped at ~1.97 s every boot, just after SensESP setup. The ELF SHA256
on the wire matched `.pio/build/hh_shesp32/firmware.elf` exactly, so the
backtrace decoded straight against the local build:

```
abort  <- lock_acquire_generic (newlib locks.c:145)
       <- __retarget_lock_acquire_recursive <- fwrite
       <- sensesp::LogBuffer::vprintf_trampoline <- esp_log
       <- __digitalWrite (esp32-hal-gpio.c:185)
       <- Outputs::ApplyLocked <- Outputs::begin <- ControlTask::begin <- setup
```

**The premise I wrote the fix on was false.** Yesterday's change reordered
`Outputs::begin()` to latch the safe levels *before* `pinMode(OUTPUT)`, on the
stated grounds that "digitalWrite() sets the output register even while a pin is
still an input." On arduino-esp32 3.x it does not. `__digitalWrite()` is gated
behind the peripheral manager:

```c
if (perimanGetPinBus(pin, ESP32_BUS_TYPE_GPIO) != NULL) {
  gpio_set_level((gpio_num_t)pin, val);
} else {
  log_e("IO %u is not set as GPIO. Execute digitalMode(%u, OUTPUT) first.", ...);
}
```

Before `pinMode()` there is no GPIO bus on the pin, so the write is discarded
and the call becomes a log statement. The reordering did nothing it claimed to
do.

**Then the second error turned a no-op into a brick.** Those writes run inside
`portENTER_CRITICAL(&mux_)` — interrupts disabled. The `log_e` path ends in
`fwrite()`, which takes the newlib stdio lock, and `lock_acquire_generic()`
`abort()`s when it cannot yield. So the dead write was not merely useless, it
was fatal, and only because it was dead: had `digitalWrite()` behaved the way
the comment asserted, nothing would have logged and nothing would have crashed.

RX had the identical reordering on its actuator-engage relay, and it is worth
being clear that RX was *not* saved by being correct. It was saved by not being
inside a critical section: the same discarded write, the same error log, no
abort. Its clutch-release-before-driver ordering had simply been silently doing
nothing since yesterday.

**Fix.** `gpio_set_level()` instead of `digitalWrite()` at both sites. It writes
the output data register directly, takes no lock, logs nothing for a valid
output pin, and is IRAM-safe — so it is correct both before the driver is
enabled and inside a critical section. In HH this replaces `WritePin()`'s
implementation outright, which also removes the latent version of the same trap
from `apply()` and `allOff()`: every write in that class runs with interrupts
off, and any of them reaching a log would have aborted the same way.

The original safety intent survives and is now actually delivered. `__pinMode()`
only calls `gpio_config()`, which sets direction, pulls and IO_MUX function and
leaves the output data register alone — so a level latched first really is what
the driver starts driving. That matters for exactly the reason claimed
yesterday: with `kOutputActiveHigh`/`kRxArmOutputActiveHigh` false, the reset
default LOW is *assert*, and the window would pulse a thruster contactor or a
clutch on every boot.

**Two lessons worth carrying.** First, "level first, then driver" is a real and
correct pattern, but on this core it must be spelled with the IDF call, not the
Arduino one. Second — the one that actually cost the boot loop — this codebase
puts GPIO writes inside `portENTER_CRITICAL` to serialise the control task
against the fail-off watchdog, and *nothing* reachable from inside those
sections may log, allocate, or take a lock. Both facts are now comments at the
call sites rather than tribal knowledge.

Native suite 262/262, unchanged — it does not compile `src/`, which is precisely
why this got through. HH and RX both build. **Verified on hardware this time:**
HH reflashed, 66 s of continuous uptime and climbing, no resets, control task
holding its 10 ms period, `state=DISARMED dir=OFF`, and no "is not set as GPIO"
error in the log. That last absence is the direct evidence the write now lands.

## 2026-09-01 — A review, and the two ways a command outlived the hand that made it

Session: (current session)

- **Owner ask:** "Review codes", then "Fix all".

A full read of the firmware, the plugin and the Android station against
SAFETY.md's invariants. The firmware came through clean on the parts that
decide anything — arbitration, the arm gate, the shared last-thrust history,
the switcher's structural never-both property, the NaN and wraparound handling
— so the interesting findings were all in the stations, and two of them were
the same defect wearing different clothes: **a command that outlived the
operator's intent to make it.**

**The arbiter could re-arm itself with nobody touching anything.** Every guard
against a replayed intent — the seq gate, the monotonic clamps — lives in the
client record, and stale eviction destroys the record. Only the counters
survived, and only the disarm one, so the arm baseline was rebuilt from
whichever packet arrived first after a station came back. Across a WiFi
recovery that can be a *delayed pre-tap heartbeat*, which rebuilds the baseline
below the arm total the operator had already spent — and the next ordinary
heartbeat, carrying no new press, reads as a fresh tap. Two POSTs delivered out
of order was the whole exploit. Reproduced against the real arbiter before
fixing, and the new test was confirmed red against the pre-fix code.

The fix is not symmetric with the disarm memory, and that asymmetry is the
safety property: disarm may move backward (that is what tracks a restarted
session, and a spurious STOP is the safe direction), arm may only move forward,
because a lowered arm baseline is precisely what hands a replay an edge to fire
against. Only *ordered* evidence of a relaunch — a session generation above the
watermark — drops the remembered totals. That last part was not in the original
plan: clamping arm forward unconditionally would have swallowed a relaunched
Android station's first real tap, since the phone persists its clientId while
its counters restart with the process.

**The Android station latched a thruster direction across a mode change.** The
MANUAL/HOLD chips stay live while a contact is held — correct, they command
nothing — so "hold PORT with one thumb, tap HOLD with the other" is an ordinary
two-fingered thing to do. Selecting HOLD removes the contacts from the screen:
the button's release fires, but the effect that *reports* it is disposed in the
same pass and never runs. The release was real and the report of it was lost,
which is the one shape a momentary control must never have. The held PORT then
sat in the ViewModel republishing on every heartbeat, and tapping MANUAL again
replayed it as a live thrust — which HH acts on at once, MANUAL having no
firmware dwell by design.

Every other release path was already covered (the enabled flip, backgrounding,
token loss). This seam was not, and no test looked at it. The direction, mode
and trim are now one `ThrusterCommand` in `:core`, so the rule is visible to a
JVM test rather than only to a phone with two thumbs on it. Worth recording one
judgement call: a *same-mode* re-tap is deliberately a no-op rather than a
release, because releasing there would cut the thrust out from under a finger
still held down with nothing to restore it — a new latch in the safe direction
is still a new latch.

**TX was banking heading trim while disarmed.** ARCHITECTURE.md §6.4 says
arming holds the heading captured *at the arm*, and both phone stations zero
their trim whenever the thruster is not commandable. TX only reset on entering
MANUAL, so its buttons went on accumulating degrees with the enable switch OFF
— and on TX that switch *is* the arm. Flipping it engaged HOLD and immediately
slewed up to the full ±45° off the heading just captured.

The rule now lives in `control_core::TrimHoldAllowed(hold_mode,
station_enabled, hh_live)` rather than a fourth inline copy. Writing it as a
predicate was the point: the term TX omitted — the station's own authority — is
invisible in prose and unmissable in a signature.

**Smaller things found reading the same paths.** `engage_debounced` is
documented as the local input but was assigned the arbitrated request (nothing
consumes it yet — a field whose name and value disagree is a trap for whoever
first does). HH's outputs and RX's engage relay latched their safe level only
*after* `pinMode(OUTPUT)` enabled the driver, so the pin briefly drove its reset
default; that default is LOW, which is only *accidentally* safe — both
polarities are config flags precisely because the opto stages are unverified,
and with either flag false that window asserts a contactor or a clutch on every
boot. RX's LED showed the calm "ready to be armed" blink when its control task
had never started; HH has the same gap and the opposite correct answer, since
it can still be armed locally, so dark would lie and it now fast-blinks
instead. And the OTA password sits in `platformio.ini`, outside the
rotate-before-sharing note that covers `secrets.h`, while being the credential
that flashes firmware onto these boards.

**Also fixed in the plugin:** a disabled plugin still commanded things (the
router is mounted at registration and outlived `stop()`, so for up to
`rxStaleTimeoutMs` an arm tap was still *granted*); an unrecognised
`thrusterMode` degraded to `hold` but kept the packet's trim, synthesising a
commanded swing out of corruption. **And on Android:** the offline kill switch
promised "disarm always works" while choosing its action from last-known
telemetry, so with a browser holding the arm and this phone's stream down the
tap did nothing at all.

**Verification.** `pio test -e native` 262 (was 257); all three firmwares build
clean, which is what covers the `src/` edits since the native suite never
compiles `src/`. Plugin 257 (was 252), tsc and build clean. Android `:core` 160
(was 150) and `:app` 50 (was 43), re-run with `--rerun-tasks` rather than
trusted from cache. Both must-fixes were checked red-then-green against the
pre-fix code rather than assumed.

**Unchanged on purpose.** `sanitizeTrimDeg` still clamps a finite implausible
trim rather than rejecting it — it mirrors `control_core::ClampTrimDeg` and the
Android core across three hand-synced copies, and changing one of them would
put the stations and the units into disagreement, which is worse than the
bounded value being accepted. It now says so in the code. Also left alone:
`$source` pinning on the liveness heartbeats (consistent with the documented
trust model, where SK server auth is the boundary), and `MdnsDiscovery`'s
threading race (setup-screen convenience, manual entry is the fallback).

**Still unproven, and still on the checklists.** Nothing here was on hardware.
The two-fingered case that motivated the Android fix is now pinned by a
Robolectric test, but Robolectric injects no pointers — a real two-thumb press,
fail-safe on backgrounding, and token revocation remain hand-on-glass checks in
SAFETY.md's *Before trusting the Android station*.

## 2026-08-22 — The floors were half a rule: the leftover was going to the lamps

Session: (current session)

- **Owner ask:** "Android app ui scaling when using different resolution phones
  and tablets. Can you check how to manage this best way to maximise screen
  usage for buttons and rest of the ui components."
- **The first thing to get straight was that resolution is not the variable.**
  Compose cancels density out — a 1080p and a 1440p phone of the same physical
  size render identical buttons, and neither needs anything doing. What varies
  is the *window in dp*: 360x780 on the reference phone, 800x1280 on a 10"
  tablet, 360x390 in split-screen. Everything below reads that from the actual
  constraints, never from the device, so a split-screen pane is treated as the
  small window it is rather than as the big phone it is on.

**The answer was already in this repository, in the browser UI.** `index.css`
has had `--u: clamp(0.72px, calc(100dvh / 780), 1.45px)` and a `1fr` drives row
since it was written: a proportional unit normalised to the S25, and a layout
that gives the leftover height to the shift buttons. The Android station was the
outlier, with every dimension hard-coded at its reference-phone value. So this
is a port rather than an invention, which also keeps the three stations reading
as one system — the thing `Theme.kt` says the shared palette is for.

`ui/HelmScale.kt` is that unit. One divergence from the web, deliberately: the
lower clamp is **1.0**, not 0.72. The browser has to fit inside desktop windows
so it shrinks; this app holds its floors and lets a control overflow visibly
instead, and that overflow is the entire failure model the layout suite is built
around. A scale that could go below 1 would quietly shrink `ContactButtonMinHeight`
from 88 dp to 63 and defeat it. Scaling only ever upward also means the reference
phone is byte-for-byte what it was, so nothing that was measured on the boat
moved.

**The defect underneath the ask.** The 280 dp bank reservation was doing its job
and hiding the other half of the problem: the bank was *unweighted at its
minimum* while telemetry held `weight(1f)`. Weights hand the leftover to whoever
holds them, so on every screen bigger than the reference phone the surplus went
to the panel that commands nothing. On a 10" tablet that is ~700 dp of lamps
above an 88 dp drive button. The floors were all green throughout, because a
floor cannot see waste.

**Why `ControlSurface` is a measure policy and not a `Column`.** What is wanted
is a priority order — bank floor first, then telemetry's natural height, then
the bank up to its ceiling, then the remainder back to telemetry — and Compose
weights cannot express it: `weight(1f)` on telemetry is the bug being fixed,
`weight(1f)` on the bank starves telemetry on a tall screen, and two weighted
siblings split the leftover 50/50 regardless of what either needs. Measuring the
three regions directly is ~20 lines and says exactly what it means. Intrinsics
rather than a trial measure, because a `Measurable` may only be measured once
per pass.

**A Compose trap worth writing down, because it silently produces the old
behaviour.** `Modifier.weight(1f).requiredHeightIn(min = X)` keeps the floor and
**never grows**. `requiredHeightIn` sets `enforceIncoming = false`, and its
`resolvedMinHeight` takes the target's minimum whenever one is specified — the
exact minimum a `weight(fill = true)` parent handed down is discarded, so the
child measures its content and the surplus becomes dead space. The order that
works is floor, then ceiling, then fill: `requiredHeightIn(min).heightIn(max).fillMaxHeight()`,
which reads as `clamp(offered, floor, ceiling)`. That is `Modifier.contactHeight`
in `Controls.kt`, and it is checked at both ends by the suite.

**A ceiling is as necessary as a floor.** Unbounded, the leftover on a 1280 dp
tablet makes a single ~500 dp contact, which is not an easier target, just a
longer reach. 240 dp at the reference, scaled like the floor, so a 10" tablet
stops at 348. Past the ceiling the column centres the contacts and leaves the
slack around them — FWD and REV stay adjacent under one thumb rather than being
stretched to opposite edges of the glass.

**Wide windows get a three-column arrangement** (kill switch across the top,
port and starboard down the outside edges, thruster and telemetry between), at
the 600 dp Android already uses as its own breakpoint. The ergonomic argument is
the whole point: on a tablet held by its sides the two contacts held through a
manoeuvre belong under the two thumbs, not side by side in the middle. Its
600 dp floor is deliberately *not* the portrait bank's 280 dp — that number
budgets for a label, a readout and an override notice too, and imposing it on a
360 dp-tall landscape window would report an overflow that is not real. The
contacts' own floor still holds there; measured, they come out at 93 dp.

Measured across the envelope (drive contact, and what it was before):

| window | scale | drive contact | was | thruster |
|---|---|---|---|---|
| reference phone 360x780 | 1.00 | **185 dp** | 88 | 88 |
| budget phone 360x640 | 1.00 | 115 dp | 88 | 88 |
| supported minimum 360x512 | 1.00 | 110 dp | 88 | 88 |
| split-screen 360x390 @1.5x | 1.00 | 97 dp | 88 | 88 |
| phone landscape 780x360 | 1.00 | 93 dp | — | 88 |
| 7" tablet 600x960 | 1.23 | 295 dp | 88 | 108 |
| 10" tablet 1280x800 | 1.03 | 246 dp | 88 | 90 |
| 10" tablet 800x1280 | 1.45 | **348 dp** | 88 | 128 |

Those numbers came out of a throwaway probe test that printed measurements at
each qualifier, not out of my head; the assertions were then written against
what it reported, with margin. The probe was deleted once the real tests
existed.

**The suite now asserts the absence of waste, not only the presence of floors.**
`the drive contacts take the leftover space, not telemetry` fails if a contact
on the reference phone is at or near 88 dp — which is exactly what the old
layout did while passing everything else. 43 cases now (was 28, was 19 before
the font-scale work): tablets both ways up, a 7" at exactly the breakpoint, a
landscape phone, the ceiling, and the scrolling-ancestor walk repeated in the
wide arrangement, where telemetry is no longer below the drives but *between*
them.

**On orientation, and a date worth knowing.** The manifest keeps
`screenOrientation="portrait"`, because a phone rotating mid-manoeuvre moves
every control out from under the thumb that was on it. But from **targetSdk 36,
Android 16 ignores `screenOrientation` on any display of sw600dp or more**, and
the `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` opt-out stops working at
targetSdk 37. A tablet will rotate whatever the manifest says, on a schedule
that is not ours. That is why the wide arrangement exists now rather than when
somebody first props a tablet at the helm.

Setup and access screens scale too, but cap their content width at 600 dp and
centre it. Width on those screens buys readability, not controls; a line of text
1280 dp wide is not good use of a tablet.

`:app` 43/43, `:core` 150/150. **Not verified on glass:** no tablet has ever run
this, and the README screenshots are all of the 88 dp layout — every control in
them is still there and still in the same order, but the proportions are wrong.
Both are noted under *Known gaps*.

*Toolchain note:* this container had a JDK but no Android SDK, so `:app` could
not be built at first. Installed command-line tools plus platform-35 and
build-tools 35.0.0 under `~/android-sdk` and pointed Gradle at it with
`ANDROID_HOME`; the wrapper fetched Gradle itself, as android/README.md says it
should. Worth the ~15 minutes: geometry on this screen is a safety property, and
shipping it on a compile alone would have been exactly the "it compiled is not
it works" the workspace rules warn about.


## 2026-08-20 — MANUAL/HOLD is chosen before arming, not after

Session: (current session)

- **Owner ask:** "manual and hold selection must be able to select even not
  armed. End user need to be able to select which mode will be enable, in plugin
  and Android app." Both stations greyed the mode chooser out with everything
  else in the thruster panel, so the mode could only be changed once armed — the
  operator armed into whatever mode happened to be showing and switched
  afterwards.
- **What actually had to change was the UI gate, and nothing else.** Both
  stations already SEND `thrusterMode` in every intent whether armed or not, and
  the arbiter already stores it per client. A non-holder's mode reaches no
  machine: `state()` reads the mode from the holder's record only, and masks it
  to `hold` whenever HH is not commandable. So this is a two-line authority
  question that was already answered — the chooser was greyed by association
  with the controls next to it, not because anything downstream needed it.
- **The distinction the change rests on:** the chooser commands nothing. It says
  which gate the NEXT arm opens. Everything that does reach the thruster — the
  PORT/STBD contacts, the trim steps — is still inert unless the station holds
  the token, the socket is up and HH is answering. That boundary is what the new
  tests pin, on both stations.
- **The consequence worth stating out loud: with HOLD selected, arming alone
  starts the hold.** No further press is involved — HH captures a heading and
  works the thruster off the arm. That was already reachable (arm, then tap
  HOLD) and SAFETY.md's cross-mode reversal item already said "repeat via
  disarm/re-arm into HOLD", so no firmware gate changes: an arm straight into
  HOLD still measures both dwells against the same shared last-thrust history.
  What changed is that it is now one tap, so the disarmed panel says it rather
  than leaving it to be discovered.
- **Two display lies found while making the panel reachable disarmed.** First,
  HOLD's readout was labelled "HOLDING" in a state where HH mirrors the setpoint
  to the fused heading (the 2026-08-21 change below) — nothing is held there, so
  it now reads "current heading". Second, and only found by looking at the
  regenerated screenshot: the new note said "arm to thrust" on the ARMED screen
  where HH is the missing piece, pointing the operator at the one control
  already doing its job. The note is gated on holding the token, not on
  commandability — a separate `holdsControl` prop in the plugin, `view.armed` in
  the phone — and both stations have a test for that case now.
- **Verification:** plugin `npm test` **252/252** (4 new: the chooser is live
  while the trim is not, the disarmed MANUAL and HOLD notes, the armed-with-no-HH
  case) plus a new end-to-end case against the real arbiter — arm from MANUAL →
  published mode `manual`, disarm, pick HOLD disarmed → still `enabled:false`
  and `thruster:off`, arm → `hold` with no thruster press anywhere. `npx tsc -b`
  and `npm run build` clean. Android `:core:test` **150/150**, and
  `:app:testDebugUnitTest` **28/28** with 9 new in `ThrusterModeSelectionTest`,
  including a contact-floor measurement on the 512 dp screen because the note is
  an extra line in that panel. **The Android suite was checked against the OLD
  behaviour too**: re-gating the chips turns exactly the two "can be selected
  with nothing armed" cases red, so the tests are testing something.
- **Not run:** `pio test -e native`. No firmware file changed; the esp32
  toolchain is not installed in this session's container. CI runs it.
- **Screenshots regenerated** for the two states whose content changed
  (`phone-disarmed`, `phone-unit-missing`). The other five were re-rendered by a
  different chromium build with identical content and were reverted rather than
  committed as churn.

## 2026-08-21 — Setpoint mirrors the fused heading whenever hold is not driving it

Session: (current session)

- **Owner trigger:** "if not ARMED should setpoint follow fusedHeading to keep
  error 0?" The published `error` sat at a stale value outside HOLDING, which
  made the tuning graphs and the plugin readout misleading.
- **What was checked before changing anything:** a stale setpoint has *zero*
  control effect. The Switcher only runs in HOLDING, entering HOLDING
  re-captures `base_heading_deg_` and seeds `setpoint_deg_` from the fused
  heading, and the law is bang-bang with no integral term to wind up. Nothing
  seeds off `hh.setpointDeg` either — the trim has been relative and seedless
  since 2026-07-24. So this was a telemetry-truthfulness change, not a control
  change.
- **One option was considered and REJECTED:** publishing a literal `0` for the
  error while not armed. Zero is the best possible error value, so using it to
  mean "not applicable" makes disarmed indistinguishable from a perfect hold —
  fail-quiet, and the one reading nobody questions. Same shape as the 999-vs-null
  trap from 2026-07-24, mirrored: there `0` was a legitimate *command* value,
  here it is a legitimate *error* value.
- **What was done instead:** the setpoint MIRRORS the fused heading whenever the
  Switcher is not driving it, so the error is truthfully zero because the
  setpoint genuinely equals the heading — nothing is fabricated. The published
  value now has one meaning in every state: *the heading hold would take if it
  engaged right now; once engaged, the heading it is holding.*
- **The condition is `!(HOLDING && !manual)`, not `!armed`.** MANUAL is included
  deliberately: the operator's button is the direction there and the Switcher
  does not run, so a frozen setpoint would sit at a stale heading while they
  hand-steer away from it, reporting a large meaningless error — the exact
  complaint that started this. ARMED_IDLE is included for the same reason.
- **Verification:** `pio test -e native` **254/254** (4 new: mirroring while
  disarmed, while ARMED_IDLE after a fault recovery, during MANUAL, and that it
  STOPS the instant HOLD captures). All three firmwares build SUCCESS
  (hh/tx/rx). A throwaway host harness compiled against the real
  `lib/control_core` printed the behaviour end to end: follows at error 0.00
  while disarmed, locks at 24.28° on engage while the error grows to -18.30°,
  re-attaches at 0.00 on release. **Nothing flashed** — the real proof is
  `sensors.headingHold.error` sitting at 0 on the boat.
- **Deliberately NOT changed: the SK plugin.** Its HOLD-mode readout captions
  the value with a hard-coded `'holding'` that is not gated on HH actually
  holding, so it will now show a live-tracking heading labelled "holding" while
  disarmed. A `holding` prop fed from `hh.armed` + `hh.mode` was written and
  green (250/250) but the owner did not want the plugin touched in this change,
  and it was reverted. **Open item** if that caption matters later.

## 2026-07-25 — Android station: pure core done and tested, platform layer drafted

Built out the native Android command station planned earlier the same day.
Owner's requirement was explicit: **fully standalone, no embedded web viewer**,
adjustable Signal K address and port with mDNS search preferred, and a token
obtained the same way HH/TX/RX obtain theirs.

- **Split it the way the rest of the project is split.** `android/core/` is
  pure Kotlin with no Android imports — everything that *decides* something —
  and `android/app/` is Compose/NsdManager/OkHttp glue. Same rule as
  `lib/control_core/` vs `src/`, and `arbiter.cjs` vs `index.cjs`. This was not
  only tidiness: it is what let the safety logic be tested here at all, since
  this environment has no Android SDK.
- **`core/` is complete: 125 tests, no warnings.** Ported with their vectors
  from the vitest suite — `fromSwitch`, the trim accumulator, arrival-based
  liveness, fixed source precedence — plus the new pieces a standalone app
  needs and the browser has no counterpart for: the access-request flow, and
  URL construction from a host and port.
- **`deriveStationView` pulls the UI's derived state into the tested layer.**
  The Compose code renders a `StationView` and computes nothing itself, so the
  scenarios `App.test.tsx` exercises through a rendered UI — thruster board
  switched off, TX holding precedence, another station armed, socket down — are
  unit tests here instead.
- **Decision recorded: intents still go over HTTP to the arbiter.** The app
  reads over a token-authorised WebSocket but does *not* write deltas like TX.
  Doing so would bypass `arbiter.cjs` entirely (no exclusive arm, no universal
  disarm, no cross-station stale eviction), need a new precedence slot in RX and
  HH firmware, and reproduce the oscillating merged-value bug the arbiter exists
  to prevent. The firmware is the model for **token acquisition**, not for the
  command path.
- **One thing a native client can do that a browser cannot:** set an
  `Authorization` header on the WebSocket upgrade. That is precisely why the web
  UI is stuck on same-origin cookies and this app can be pointed anywhere. Both
  `Bearer` and `JWT` prefixes are tried, since signalk-server has historically
  accepted only the latter on some versions (issue #715).
- **Deliberately no foreground service.** If the app is not in front of the
  operator it must not be commanding machinery: the heartbeat stops with the
  app, the arbiter stale-evicts within a second, and the system falls back to
  disarmed. `ON_PAUSE` additionally forces every control to its safe value at
  once rather than waiting for that.
- **`FLAG_KEEP_SCREEN_ON`** — something the web UI cannot do at all, since the
  Wake Lock API is secure-context-gated and the server is plain HTTP.
- **CI runs `:core:test`** with no Android SDK installed, on purpose: if the
  pure core ever stops building without one, something Android-specific has
  leaked into it and the job failing is the alarm.
- **`app/` HAS NEVER BEEN COMPILED.** No Android SDK in this environment. It is
  a reviewed first draft of the platform layer, not working software — expect
  ordinary build errors on the first Android Studio open. Nothing has run
  against a real Signal K server and nothing has been installed on a device.
  **The Phase 0 commissioning `curl` (ARCHITECTURE.md §10) is still the first
  thing to do**, since the whole design assumes a token station can POST an
  intent at `readwrite`.
- **Verified:** `./gradlew :core:test` → 125/125. `npm test` → 203/203 still
  green. `app/` unverified by construction.

## 2026-07-25 — Android station, Phase 0: the intent route was admin-only

Owner asked how hard a **native Android app** with parity to the plugin UI
would be: adjustable Signal K address/port, mDNS discovery preferred, fully
standalone (explicitly *not* a WebView shell), authenticating with a token
obtained the same way HH/TX/RX do.

The answer is "easier than it looks, for one reason": the plugin's browser half
is already a thin client. Its whole contract is a read-only WebSocket
(`skClient.ts`) plus a 250 ms intent POST (`clientIntent.ts`) — every
safety-critical decision is server-side in `arbiter.cjs` and stays there. An
Android station is a new *client* of that authority, not a new authority. Plan
written for a native Kotlin/Compose app in five phases; this entry is Phase 0.

- **Found while planning: `/intent` was registered admin-only.** `index.cjs`
  called `router.post('/intent', ...)` directly. In `signalk-server`, a route
  registered directly on a plugin router keeps the `/plugins` gate's
  **admin-only** default; only routes going through the access-scoped registrar
  (`router.access(level)`, see `asPluginRouter()` in the server's
  `src/interfaces/plugins.ts`) admit readwrite clients. It worked *only* because
  a browser opened from the SK admin UI carries an admin session cookie — which
  is exactly what the plugin README tells the operator to do, so nobody had
  reason to notice.
- **This would have blocked the whole Android plan.** A device holding a Signal
  K access-request token authenticates as readwrite, not admin, so its intent
  POST would have been rejected — and the failure would have looked like a
  broken app rather than a route permission. Better found now than in Phase 3.
- **Now registered at `readwrite`, feature-detected.** `router.access` is a
  recent server API, so the code falls back to the old direct registration if it
  is absent. The browser is unaffected either way; on such a server a token
  station needs its access request approved at **admin** level instead. Noted
  via `app.debug`, deliberately not `app.error` — on a browser-only install this
  is a non-event and flagging the plugin as errored would be a false alarm on a
  working system.
- **Security posture is unchanged for the browser and strictly better
  elsewhere:** a phone can now command the drives without being handed admin
  rights over the whole server.
- **`test/pluginRoute.test.ts` (new, 4 cases).** First test to cover
  `index.cjs` at all. It asserts the *registration* — readwrite level, no
  admin-only duplicate, the documented fallback — because the level is invisible
  to every test that only drives the handler, and it is the difference between a
  token station working and being rejected.
- **Not yet verified against a real server.** The `router.access` shape was read
  from the signalk-server source, not exercised here; there is no SK server in
  this environment. ARCHITECTURE.md §10 now carries the commissioning `curl`
  that settles which mode a given server is in, including the known
  `Bearer` vs `JWT` prefix wrinkle (signalk-server issue #715). **Run it before
  trusting Phase 1 onward.**
- **Verification:** `npm test` **203/203 passed** (was 199), `npm run build`
  clean.

## 2026-07-25 — HH's status LED now gates on the SK link, like RX and TX

Owner noticed on the bench that HH starts blinking the moment it powers up,
while RX stays dark until its own Signal K socket is up. The one-blink-vocabulary
work of 2026-07-24 gave all three units the same *patterns* but left HH without
the *link gate*, so "off" did not mean the same thing on HH as on the other two.

- **HH's LED loop now checks `get_ws_client()->is_connected()` first** and drives
  the LED off when the socket is down, exactly as RX and TX do. Below that, the
  existing FAULT / HOLDING / normal patterns are unchanged, but now use the
  shared `config::kLinkFaultBlinkHalfPeriodMs` / `kLinkReadyBlinkHalfPeriodMs`
  constants instead of the hard-coded 100/500 they were duplicating.
- **Socket-down deliberately outranks FAULT on HH.** Consistency was what was
  asked for, but it is also the truthful indication: HH's heading reference
  arrives over Signal K, so with the socket down there is nothing to arm on and
  no station can command the thruster either. A dark LED now reads the same on
  every unit — *this one cannot be armed right now*.
- **RX was left alone, on the owner's challenge.** I had also added a
  `have_telemetry` guard to RX, to make its code match a comment claiming the LED
  is off "before the first telemetry snapshot exists" — RX actually slow-blinks
  in that window. The owner asked why bench-proven RX firmware was being touched
  for an HH request, and they were right: the control task publishes telemetry
  within milliseconds of `begin()`, while `is_connected()` only goes true after
  WiFi association and the SK handshake seconds later, so a snapshot always
  exists by the time that branch is reachable. The guard was unreachable code
  fixing a comment, not a behaviour. Reverted on RX and dropped from HH, leaving
  both gated purely on the socket. RX's comment is still slightly optimistic
  about a window that cannot occur; that is a comment to correct if it ever
  misleads, not a reason to edit flashed firmware.
- Docs updated: ARCHITECTURE.md §8's HH paragraph and the HH pin table row, plus
  a per-unit HH LED line in SAFETY.md's commissioning checklist, which had one
  for TX and RX but not HH.
- 234 native cases pass; both `rx_shesp32` and `hh_shesp32` build. The LED lives
  in the SensESP glue, not the pure core, so there is no host test to add — it is
  a checklist item. **Note this change postdates today's HH bench session**, so
  the new dark-when-disconnected behaviour has not itself been on hardware.

## 2026-07-25 — HH bench-tested too, including the reversal-interlock scope checks

Session: (current session, continued)

- **Bench result from the owner:** HH has now also been flashed and run on real
  hardware, alongside RX. Plugin and phone UI were exercised against it end to
  end; MANUAL and HOLD both behaved as designed, and the owner worked through
  SAFETY.md's thruster checklist including the reversal-interlock scope checks
  (ENABLE/PORT/STBD scoped through a commanded reversal in both modes). **TX
  has still never been flashed** — it remains the one unverified unit.
- **Docs corrected accordingly.** README, AGENTS.md and SAFETY.md previously
  said the bow thruster was "entirely host-verified"; that's now out of date.
  Rewritten alongside the existing RX status so all three docs say the same
  thing: RX and HH have both run on the bench, TX has not, and RX's own
  gear-neutral arm interlock still postdates its bench session (unchanged from
  the note above — HH bench work doesn't touch that caveat).

## 2026-07-25 — RX + plugin bench-tested; diagrams converted to draw.io PNGs

Session: (current session, continued)

- **Bench result from the owner:** RX has been flashed and run on real hardware —
  the plugin and phone UI were driven against it end to end, and the servos were
  seen moving through forward / neutral / reverse. **First hardware session, and
  it worked.** TX and HH have still never been flashed.
- **Docs corrected accordingly.** README, AGENTS.md and SAFETY.md all claimed
  "nothing has been flashed", which was now false. Rewritten to say exactly what
  has run and what has not — including the part that matters most: **the bench
  session predates the gear-neutral arm interlock**, so the arm gate, the
  lever-neutral sensors, the engage relay and the fourth LED state are untested
  on hardware. "RX works on the bench" must not be read as cover for code that
  landed afterwards.
- **Diagrams: 7 hand-authored SVGs → draw.io-editable PNGs.** Owner asked for
  PNG specifically; I recommended `.drawio.svg` instead (same edit workflow,
  stays vector, diffable, ~10× smaller) and they reaffirmed PNG, so PNG it is.
  Each `.png` carries its diagram XML in a `zTXt`/`mxGraphModel` chunk — verified
  by round-tripping one back out through the CLI before authoring the rest —
  exported at `--scale 2` so a fixed-resolution image still reads on a phone and
  in print. The `.drawio` XML twin is committed next to each PNG so diagram
  changes remain a readable diff rather than an opaque binary blob.
- **Layouts were transcribed, not reinvented:** the old SVGs used explicit
  coordinates, so boxes kept their positions. Edge labels are standalone text
  cells rather than draw.io edge labels — draw.io's automatic label placement
  put them on top of the boxes the first time round.
- **Three diagrams were factually out of date and are now fixed**, not just
  reformatted:
  - `rx-arbitration-flow` still asked "RX master enable ON?" with no arm gate.
  - `hh-control-flow` said MANUAL had a "reversal dwell enforced"; it has been
    0.00 s since 2026-07-24.
  - `tx-signal-path` was missing the ~1 Hz ready-to-arm LED state.
  `rx-architecture`, `system-overview` and `hardware-wiring` gained the arm gate,
  the engage relay and the lever-neutral sensors, including a second schematic
  drawing the active-LOW / external-pull-up convention next to the existing
  active-HIGH one.
- **Also fixed a pre-existing bug:** every image link in `docs/ARCHITECTURE.md`
  pointed at `docs/diagrams/…`, but that file already lives in `docs/`, so all
  seven images rendered broken on GitHub. Now `diagrams/…`.
- **Verification:** every exported PNG re-opened in draw.io and re-rendered on
  screen before being committed; all 7 confirmed to carry the `mxGraphModel`
  chunk. Regeneration command documented in README under "Regenerating
  documentation assets".

## 2026-07-25 — RX gear-neutral arm interlock + actuator-engage output

Session: (current session)

- **Owner ask:** RX must not be able to take control unless both shift levers
  are in neutral. Two inputs (port, stbd) from switches or hall sensors that
  pull the pin down when the lever is in position; both required before RX may
  be ARMED. Plus one output, asserted while ARMED and released while DISARMED.
  Propose pins, plan, implement.
- **The thing I flagged before writing anything:** the interlock cannot be a
  continuous condition. RX's servos move the levers, so the neutral sensors open
  the moment an armed remote commands gear — a continuously-enforced rule would
  disarm RX on its first shift. It has to be a *permission to arm* that latches,
  like a car's neutral-safety switch gating the starter and not the running
  engine. Owner chose **level-based latching** (no fresh master-enable edge
  required: return the levers to neutral with the switch still ON and it re-arms
  next tick), over the edge-triggered variant I recommended.
- **What I added that wasn't asked for, and why.** Arming also requires RX's own
  local shift switches to be at neutral. While disarmed the clutch is released,
  so a held local switch parks a servo at FORWARD against a lever that reads
  NEUTRAL — and energising the clutch there slams that lever into gear at the
  instant of arming. It is the same hazard the feature exists to prevent, one
  step further in. Reported as `armInhibit: localCommandActive`.
- **Pins** (owner picked the recommended option): **GPIO36 / GPIO39** for the
  port/stbd neutral sensors, **GPIO33** (the board's isolated Opto OUT) for the
  engage relay. 36/39 are input-only with no internal pull, so each needs an
  **external 10 kΩ pull-up to 3V3** — which is also what makes an unplugged or
  dead sensor read "not neutral" rather than float, i.e. the fail-safe direction.
  Spending the two input-only pins on the two pure inputs keeps 18/19/22 free, so
  no GPIO number means two different things across the shared TX/RX diagram
  (the rule `config.h` set for the TX thruster pins). GPIO33 also matches HH's
  ENABLE: on every board here, 33 is "the isolated line asserted only while
  armed."
- **Implementation.** New pure module `drive/arm_gate.{h,cpp}` — the only
  stateful module on the drive side, because it latches. `Arbitrate()`'s second
  parameter renamed `rx_master_enable` → `rx_armed`, and RX now feeds it
  `ArmGate::armed()` rather than the raw switch; the rename is the point, since
  passing the raw switch would walk straight past the interlock. `begin()`
  releases the relay as the firmware's first hardware action, before the servos
  are attached. New telemetry: `rx.armed`, `rx.{port,stbd}.leverNeutral`,
  `rx.armInhibit`. RX's LED gained a fourth state — fast ~5 Hz blink when the
  master enable is ON but the interlock refuses, so the panel says "check the
  levers" instead of going quietly dark.
- **Verification:** `pio test -e native` **234/234 PASSED** (was 211; +23 in the
  new `test_arm_gate/`, covering the latch both ways, the fail-safe sensor
  reading, disarm-is-unconditional, re-arm requiring neutral again, and
  composition with `Arbitrate()`). `pio run -e rx_shesp32` SUCCESS.
  **Nothing flashed** — hardware status is unchanged.
- **Follow-up, deliberately not done:** the plugin does not read `rx.armed`, so a
  phone UI can hold the arm token and show live controls while RX is refusing to
  arm on a lever. RX fails safe, but the UI is optimistic about it, which is the
  kind of thing SAFETY.md's "never present unconfirmable data as live" rule
  exists to catch. Noted in the multi-station commissioning list. Wiring it up
  means `SK_RX_ARMED_PATH` in `sk-plugin/src/config.ts`, the arbiter's arm gate,
  and the DriveControl greying rule.
- **Left open on purpose:** the relay follows ARMED exactly, as asked — so
  disarming with a drive in gear releases the clutch where it stands and the
  lever stays in gear (the servo returns to neutral attached to nothing). That
  is conventional for a helm-takeover clutch, but if a kill-switch press should
  leave the engines in *neutral* it needs a disarm sequence (hold the relay
  until the servos reach neutral, then release), which costs a few hundred ms of
  machinery moving while disarmed. Written up under SAFETY.md drive invariant 7
  with a commissioning step to decide it on the bench, not at sea.
- **Two new MEASUREMENTS items (9, 10)**, both blocking bench work: the neutral
  sensor's actual neutral *window* (a wide band would let the clutch grab a
  partly-shifted lever, and firmware cannot see that), and the engage relay's
  coil rating plus the opto stage's real polarity (an inverting stage would
  energise the clutch while RX reads disarmed).

## 2026-07-24 — Review sweep: document the zero MANUAL dwell, close the cross-mode dwell hole

Session: (current session)

- **Owner ask:** full review of code, flows, safety, security and docs. Then:
  `kManualReversalDwellS = 0.00` is **intentional** — document it as such — and
  fix everything else the review found.
- **What the review found first.** The previous session's commit shipped
  `kManualReversalDwellS = 0.00f` while its own commit message, this journal,
  `config.h`'s adjacent comment, `manual_thrust.h`, SAFETY.md, ARCHITECTURE.md
  and MEASUREMENTS.md all said 1.85 s / "never below the measured 1.75 s floor."
  I read that as an accidental edit and flagged it as critical. **It was
  deliberate** — the owner's reasoning being that the thruster's control box
  enforces its own interlock regardless, a human is watching the boat in MANUAL,
  and a firmware-imposed wait made the control feel dead while docking. So the
  defect was never the value: it was that **every document contradicted it.**
  Fixed by rewriting the documentation to state the asymmetry and its reasoning,
  rather than by changing the constant.
- **Why nothing caught the contradiction:** no test reads a `config::` constant
  (`test_manual_thrust` defines its own local `kDwellS = 1.85f`), so 209/209
  passed against a firmware whose manual dwell was zero. Added a
  `static_assert` for `kReversalDwellS` only — HOLD still has a hard measured
  floor, and it is now enforced at compile time. MANUAL deliberately gets no
  floor and no assert; it is a policy knob, not a measurement.
- **The real bug, found by probing the actual pipeline (both dwells set to
  1.85 s to isolate it from the above):** the reversal dwell was bypassed
  entirely across a mode change, in both directions. `ControlStep` reset the
  incoming gate on a mode change and both `ManualThrust::Reset()` and
  `Switcher::Reset()` cleared `last_thrust_dir_` to `kOff` = "nothing to reverse
  from." Measured: HOLD(STBD) → tap MANUAL → PORT asserted on the *very next
  tick*; MANUAL(PORT) → tap HOLD → STBD after **510 ms** (min_off) instead of
  1850 ms. Two taps on the phone, or the TX mode toggle, reached it.
  - This was **test-locked as correct** by
    `test_mode_switch_resets_the_incoming_gate` ("a dwell it never earned").
    That rationale is wrong on the physics: the interlock lives in the control
    box, which has no idea which software gate commanded the previous thrust.
  - **Fix:** `ControlStep` now tracks the last direction the thruster was
    physically driven in (from the emitted output, so it spans both gates and
    every mode change) plus when it stopped, and *seeds* the incoming gate with
    it instead of clearing. Both `Reset()`s take the history; the one-argument
    form still means "nothing to reverse from."
  - **This is what makes the owner's asymmetry work cleanly.** Seeding carries
    the *facts*; each gate applies its *own* dwell to them. So MANUAL (0.00 s)
    still reverses immediately in every path, and HOLD (1.85 s) now honours its
    dwell even against a thrust the manual gate produced. Verified against the
    shipped constants: MANUAL flick immediate; HOLD→MANUAL immediate;
    MANUAL→HOLD 1850 ms; disarm/re-arm→HOLD 1850 ms; same-direction re-entry
    500 ms (min_off — correctly not treated as a reversal).
  - Replaced the old test with three: the gate-reset-but-not-history case, the
    zero-manual-dwell case (pins the shipped behaviour), and HOLD honouring its
    dwell against a manual thrust.
- **Flagged, not fixed — needs hardware.** The whole arrival-based liveness
  model assumes `SKOutput::set()` emits a delta even when the value is
  unchanged. The server half was verified previously against `streambundle.js`;
  the SensESP half is unverifiable here (`ref/` is gitignored and absent). It
  bites hardest on `hh.linkUp`, whose value is genuinely constant `false` while
  nothing is armed. Documented in SAFETY.md and added as the *first* item on
  the multi-station commissioning checklist.
- **Smaller fixes.** `wrapDeg180` in `trimOffset.ts` used a subtract-360 loop —
  the exact construct `angle_math.h` rejects for hanging on ±Infinity; in a
  browser that hangs the tab including the kill switch. Dead code today, but
  exported; made it modulo-based and pinned it with a test. Capped the arbiter's
  per-client Map at 32 (bounds the window between stale-eviction ticks; refuses
  new records only, never disturbs the holder). Corrected the fail-off
  watchdog's "provably not running" comment to state what actually survives a
  race with the control task. Removed a dead `age_s < 0` branch in
  `HeadingFilter::Correct` (unsigned, can't be negative). Fixed `hh/main.cpp`'s
  claim that `secrets.h` is gitignored — it is committed, as the other two
  mains correctly say.
- **Security review:** no defects. Confirmed via the GitHub API that the repo is
  private, so the committed `secrets.h` matches the documented owner decision.
  Standing risk unchanged and already documented: the credentials are in git
  history permanently, so rotation before any sharing is not optional, and the
  OTA password is the only thing between a LAN peer and reflashing a board wired
  to engine shift levers. Remote authority still depends on SK server security
  being enabled (SAFETY.md already states this).
- **Files:** `include/config.h`, `lib/control_core/heading/{control_step.h,
  control_step.cpp,manual_thrust.h,manual_thrust.cpp,switcher.h,switcher.cpp,
  heading_filter.cpp}`, `src/hh/{control_task.cpp,outputs.h,main.cpp}`,
  `test/test_control_step/`, `sk-plugin/{arbiter.cjs,src/pure/trimOffset.ts,
  src/pure/trimOffset.test.ts,test/arbiter.test.ts}`, `README.md`, `AGENTS.md`,
  `docs/{SAFETY.md,ARCHITECTURE.md,MEASUREMENTS.md}`.
- **Verification:** native core **211/211** (was 209: one test replaced by
  three); plugin `npm test` **199/199** (was 196) and `tsc -b` clean. Note the
  native suites were run with a minimal local Unity shim under g++ —
  PlatformIO is not available in this environment, so **`pio test -e native`
  and the three `pio run` builds still need to be run on a machine that has
  it** before this is trusted.

## 2026-07-24 — Split MANUAL and HOLD thruster reversal-dwell into independent config knobs

Session: (current session)

- **Owner ask:** give MANUAL and HOLD their own reversal-dwell variables so
  MANUAL can be re-tuned while HOLD stays as it is now.
- **What was already there.** `ControlStep::Cfg` already carried a separate
  `manual_reversal_dwell_s` field (constructed apart from `SwitchCfg`); the
  firmware just fed the single `config::kReversalDwellS` to both. So this was a
  config-layer split, not a core rewrite.
- **Change:** added `config::kManualReversalDwellS = 1.85f` alongside
  `kReversalDwellS`; `MakeStepCfg()` now feeds the new constant to
  `cfg.manual_reversal_dwell_s` and leaves HOLD on `kReversalDwellS`. Defaults
  are equal so device behaviour is unchanged until MANUAL is deliberately
  re-tuned. Refreshed the now-stale comments in `control_step.h` /
  `manual_thrust.h` that claimed both fields always get the same constant.
- **Safety framing I flagged.** The dwell models the thruster control box's
  physical anti-reversal interlock — the SAME hardware for both modes — so the
  two variables are a per-mode *margin* knob, not a claim the interlock differs.
  Documented in config.h that neither may go BELOW the measured ~1.75 s floor
  (commanding a contactor into a spinning motor can't reverse faster). No runtime
  clamp added, matching how `kReversalDwellS` already works; still not web-editable.
- **Files:** `include/config.h`, `src/hh/control_task.cpp`,
  `lib/control_core/heading/control_step.h`, `.../manual_thrust.h`.
- **Verification:** `pio test -e native` **209/209**; `pio run -e hh_shesp32`
  SUCCESS.

## 2026-07-24 — Plugin UI: grey out + disable the command widgets when they can't command

Session: (current session)

- **Owner ask:** should the Bow Thruster and Gear (drive) controls be
  grey/not manipulable when disarmed, or when the Head-Hold / RX units are
  offline/off? Plus a colour-scheme change and actually disabling control.
- **The trade-off I flagged first.** The previous design deliberately kept the
  command buttons live at all times "in parity with TX's physical switches"
  (which always show their true position), with the kill switch as the single
  disarmed indicator (commits a4de13d / 435e708). That parity argument doesn't
  hold for glass: a physical switch reveals its state by its position, but a
  touch button that looks pressable yet commands nothing is just misleading.
  SAFETY.md's multi-station checklist already anticipated the new behaviour
  ("Power one unit off → its controls grey out"). So this is a considered
  design reversal, not a safety regression — with three guardrails kept intact.
- **What I did NOT touch (the guardrails):**
  1. The **kill switch stays always-live** in every state (disarmed, offline,
     unit-missing). Its disarm travels over HTTP and is never gated — stopping
     must always work. Only the command widgets grey; the stop path does not.
  2. **No stranded press.** `useMomentaryButton(disabled)` force-releases any
     in-flight press the instant authority is withdrawn (same "fail to the safe
     value" as the tab-hidden/blur backstops), and refuses new presses — so a
     finger held across the disarm/offline edge can never leave a latched
     command. New unit test covers both.
  3. **Per-machine gating.** Drives gate on RX liveness, the thruster on HH
     liveness, each `= armed && connected && unit-live`. A dead thruster board
     greys only the thruster; the gears stay live, and vice versa (the two are
     independent machines). App's old single `commandable` (either-unit) became
     `driveCommandable` (RX-specific); `thrusterCommandable` was already
     HH-specific.
- **Files:** `useMomentaryButton.ts` (+`disabled` param), `DriveControl.tsx`
  (+`disabled` prop), `ThrusterControl.tsx` (disable mode/thrust/trim when not
  commandable), `App.tsx` (per-machine `driveCommandable`), `index.css` (greyed
  `:disabled` palette + `is-disabled` widget dimming). Offline no longer reads
  as "safely disarmed": the kill switch shows a distinct OFFLINE state, and the
  greyed command buttons honestly say "can't confirm a command lands right now".
- **Verification:** `npm test` **196/196** (was 191; rewrote the two tests that
  asserted the old disarmed-interactivity, added disabled coverage for the hook,
  the thruster widget, and an RX-offline-mid-session per-machine case).
  `npm run build` clean. Regenerated the six README screenshots — `phone-
  disarmed` now shows the whole panel greyed, `phone-unit-missing` shows the
  thruster greyed while the drives stay live and coloured.

## 2026-07-24 — One LED vocabulary across all three units: slow blink = "ready to be armed"

Session: (current session)

- **Owner ask:** should RX's LED work like HH's? Then, after I laid out the
  options: RX should **slow-blink when ready to be armed**, and TX should do the
  **same**.
- **The gap.** RX's LED was solid = "a remote source is live+enabled", else
  OFF. So a powered, healthy RX sitting disarmed looked identical (dark) to one
  switched off or crashed. HH already slow-blinks whenever powered-and-normal,
  so it never had that ambiguity. The owner wants that "I'm powered and ready"
  slow blink on RX and TX too.
- **What I did NOT blindly copy.** HH shows *solid* for "actively holding" — a
  state that is otherwise invisible. RX/TX actuation is visible, so I kept
  solid meaning "armed / in use" and added the slow blink for "ready but not
  armed", rather than a literal HH clone. And I preserved TX's existing per-unit
  liveness detail instead of throwing it away for a simpler scheme.
- **Unified vocabulary (all three units):** off = not reachable to be armed
  (own link down / unpowered); ~1 Hz slow blink = ready to be armed (powered,
  connected, not armed); solid = armed / in use; plus TX-only ~2 Hz warn (armed,
  one unit missing) and ~5 Hz fault (nothing reachable / HH IMU timeout). One
  new `kLinkReadyBlinkHalfPeriodMs = 500` (matches HH's slow rate).
- **TX** (`drive/link_indicator.h`, the pure host-tested module): added a
  `kReadyToArm` state and an `armed` input. **The §5.2 false-confidence rule is
  preserved through the arm dimension** — a solid or slow "ready" light can
  never appear unless a commandable unit is actually live (nothing reachable is
  always `kFault`, armed or not). While disarmed the LED just says "ready to be
  armed"; the "which unit is missing" warn/solid detail returns the moment TX is
  armed, which is when the operator is actually commanding and needs it. TX now
  feeds its debounced enable-switch state (`tx_armed`) into the LED.
- **RX** (inline in `src/rx/main.cpp`, no pure module — one link verdict, not
  two): off when its own SK socket is down (cannot be armed remotely), slow
  blink when connected + running but nothing armed, solid when a remote source
  is live+enabled. Uses the same flicker-free static-last-known pattern HH's LED
  uses. Local switches still drive the servos regardless; the LED reports
  remote-arm readiness, which is the ambiguous thing (local override is
  directly visible).
- **HH** left unchanged — it already slow-blinks when disarmed (ready to be
  armed), so it was already consistent with the requested convention.
- **Note on RX "off":** "ready to be armed" requires the SK link, so a powered
  RX with WiFi down is dark (like unpowered) — dark now means "cannot be armed
  right now", and the slow blink resolves the powered-but-idle ambiguity the
  owner flagged.
- **Verification:** `pio test -e native` **209/209** (test_link_indicator
  rewritten for the 4-arg evaluate + 3-phase LED, ready-to-arm and
  never-ready-when-nothing-reachable cases). TX + RX firmwares build SUCCESS.
  Docs: ARCHITECTURE §8 (new shared-vocabulary + per-unit tables) and §12 LED
  rows, SAFETY.md commissioning checks for both LEDs. Nothing flashed.

## 2026-07-24 — Thruster hold command reworked from absolute target to relative trim

Session: (current session)

- **Owner trigger:** two points on `control.remoteController.plugin.thruster.targetDeg`.
  (1) "select HOLD and a value, then ARM, and it is not used" — a real workflow
  failure. (2) "999 is a bad value — it should be null or something, not a real
  value." Investigated both before changing anything.
- **What was found, honestly:** the arbiter DID forward a set target on arm
  (reproduced live), and the review commit had not touched this logic — so it
  was not a regression from that. The "not used" came from the pre-arm SEED:
  while disarmed HH publishes `setpointDeg = 0`, so trimming before arming was
  relative to a bogus 0 baseline. And on the sentinel: `null` naively would be
  *more* dangerous than 999, because the firmware's `SKValueListener<float>`
  coerces JSON null → `0.0`, which `IsPlausibleCommandedHeading` accepted as a
  valid "hold 0°/north" command (the same coercion trap already fixed for
  `navigation.headingTrue`). 999 existed precisely to survive that coercion as
  an obviously-invalid value.
- **Decision, taken with the owner:** stop modelling the command as an absolute
  target with a sentinel. Model it as a **relative trim offset** where `0` = no
  trim (hold the captured heading), clamped to **±45°**, path renamed
  `thruster.targetDeg` → **`thruster.trimDeg`**. This kills the sentinel (0 is a
  real, safe resting value), makes `null`/`0` coercion harmless-and-correct,
  removes the seed entirely (a relative press is well-defined with no knowledge
  of HH), and fails softer (a corrupt offset is ≤45° of trim, never an arbitrary
  heading). Owner also chose **arm-first-then-trim** as the workflow, so the
  plugin resets its trim to 0 whenever the thruster is not commandable — arming
  never swings the boat to a pre-dialled number.
- **The control law change (HH):** on entering HOLDING, capture the fused
  heading as a fixed `base_heading_deg_`; each tick slew the setpoint toward
  `WrapDeg180(base + ClampTrimDeg(trim))`. `base` is re-captured on every fresh
  engage, so a trim always means "this far off whatever we were pointing when
  hold engaged", never an absolute carried over. `setpoint_commanded` now means
  "a non-zero trim is applied". The estimate is still never snapped.
- **New boundary primitive:** `control_core::ClampTrimDeg` / `kMaxTrimDeg` (45)
  in `setpoint.h`, replacing `IsPlausibleCommandedHeading`/`kMaxCommandedHeadingDeg`.
  Unlike the old one it CLAMPS rather than rejects, because 0 is a safe default
  for a relative value (it was not for an absolute heading). Mirrored in the
  plugin as `MAX_TRIM_DEG` and `pure/trimOffset.ts`'s `clampTrim`.
- **Everything renamed/retyped consistently, both stacks:** firmware
  `ThrusterRemote::trim_deg`, `HeadingNudge` (now a seedless relative
  accumulator, `Update(port, stbd, now)`), `SkThrusterIn` trim listener (the
  plain float listener is now SAFE: null→0.0→no-trim), `config::kSk*ThrusterTrimPath`,
  `kNoCommandedTargetDeg` deleted; TX publishes `trimDeg` and no longer
  subscribes to `hh.setpointDeg` (no seed needed). Plugin: `arbiter.cjs`
  `sanitizeTrimDeg` + `trimDeg` on the wire (no 999), `clientIntent.trimDeg`,
  `pure/headingTarget.ts` → `pure/trimOffset.ts`, App holds a numeric
  `trimOffset` reset on non-commandable, `ThrusterControl` shows the held
  heading with a signed "trim +N°" beside it. Docs: ARCHITECTURE §6.4 rewritten,
  §9 path tables, `tx-signal-path.svg` label, `plugin/README.md`, config.h and
  CLAUDE.md notes.
- **Verification:** `pio test -e native` **205/205** (test_setpoint,
  test_thruster_arbitration, test_control_step reworked for relative trim —
  including NaN/null→0, out-of-range→clamp, zero-holds-captured,
  base+trim-reached); plugin **191/191** (was 190; new `trimOffset.test.ts`,
  arbiter trim vectors, ThrusterControl trim display), `tsc -b` + production
  build clean; all three firmwares build. Nothing flashed — the SAFETY.md
  thruster commissioning checklist (trim slews, re-capture on re-engage) still
  applies and now describes a relative trim.

## 2026-07-23 — Five-domain deep review, and fixing everything it found

Session: (current session)

- **Owner ask:** review all code, documentation, diagrams, operations, corner
  cases, safety and cyber-safety, as deeply and widely as possible — then
  "fix all". Five parallel review agents covered the pure core, the firmware
  glue, the plugin server (security), the web UI, and docs/diagram
  consistency; every critical/high finding was re-verified against the code
  before being accepted, and the arbiter finding was reproduced live.
- **Three criticals found and fixed:**
  1. **Arbiter replay re-arm (`arbiter.cjs`).** The edge baselines
     (`lastArmReq`/`lastDisarmReq`) were assigned from whatever value arrived
     last — including a LOWER one — so a delayed out-of-order heartbeat (or a
     malicious LAN client) could lower its baseline and then replay an
     already-consumed `armReq` to silently regain the token after a universal
     disarm nobody answered. `seq` was documented as the defence but never
     read anywhere. Fixed with two independent layers: per-client `seq`
     ordering (stale intents discarded whole — they also no longer refresh
     liveness or revert commands) and monotonic clamps on both baselines
     (they can never move backward, protecting even seq-less clients). Plus a
     64-char `clientId` cap. Six new arbiter vectors pin all of it.
  2. **Kill switch dead exactly when needed (`KillSwitch.tsx`).** While the
     read-side WebSocket was down the button rendered `disabled` and labelled
     itself DISARMED — but disarm travels a separate HTTP POST, and the 250 ms
     intent heartbeat keeps POSTing regardless of the socket. In a
     WS-down/HTTP-up split the arbiter therefore kept the tab armed and
     commanding while the UI claimed DISARMED and the only stop control was
     inert; the code comment's justification ("the arbiter stale-evicts us")
     was factually wrong in that case. Now the offline state is labelled
     OFFLINE (claiming neither armed nor disarmed), the tap stays live, and it
     always means STOP. The old tests had pinned the broken behaviour and were
     rewritten; a bench check for the WS-only-down case was added to
     SAFETY.md's plugin checklist.
  3. **Non-finite heading could hang HH or wedge the thruster ON.**
     `sk_heading_in.cpp` had no `isfinite` gate, and `WrapDeg180`'s
     subtract-360 loop never terminates for ±Inf — one overflowing JSON number
     would hang the SensESP task inside the WS callback while holding the
     mutex. Worse: NaN passes `HeadingFilter::Correct`'s plausibility gate
     (NaN fails every comparison), poisons the fused heading permanently,
     keeps `Correct()` returning true (so the 30 s coast-timeout never fires),
     and makes the Switcher's off-threshold comparison false — a thruster
     already thrusting could never be commanded off by the control law.
     Fixed at the boundary (`isfinite` before accepting), in the filter
     (explicit non-finite reject, same pattern as `setpoint.h`), and
     structurally (`WrapDeg180` is now fmod-based: O(1) for any magnitude,
     NaN-for-garbage, cannot hang). `YawRate` got the same guard as
     defense-in-depth. Nine new native cases.
- **Also fixed (high/medium):** out-of-order GNSS timestamps no longer defeat
  the jump gate via unsigned wraparound (backward/duplicate time is rejected);
  duty accounting now runs every tick from the ACTUAL output
  (`Switcher::TrackDuty`) so a `duty_max` inhibition decays during a real
  rest instead of standing forever, and a heavy MANUAL session heats the model
  truthfully; HH's telemetry/heading mutexes got the null-creation guards the
  rest of the codebase already had; `skClient.publish()` was deleted outright
  (the read-only-WebSocket invariant is now structural, not conventional — a
  test asserts the client exposes no write capability at all);
  `useMomentaryButton` releases on `pointerleave`-without-capture and
  `lostpointercapture`, closing the stuck-press path when `setPointerCapture`
  is refused.
- **Deadman honesty.** `deadman_ok` was hardcoded `true` while SAFETY.md, the
  wiring diagram and §12 presented the input as live. No switch is installed,
  and GPIO39 floats unwired (input-only, no internal pull), so the read is now
  gated on a new `config::kDeadmanWired` (false until installed) and every
  document says so plainly — invariant 6's deadman half is provided by ENGAGE
  alone until then. Recorded as MEASUREMENTS.md item 7. `kBnoResetPin` is
  likewise now labelled reserved/not-driven everywhere (no reset path exists).
- **Docs/diagrams corrected:** ARCHITECTURE §3 (TX has three inbound
  subscriptions, not one; RX publishes telemetry at 4 Hz, not 10–20), §10
  (seq is enforced; the offline kill switch is interactive), §12 (deadman and
  IMU-reset caveats, TX thruster pins marked proposed-not-confirmed as
  MEASUREMENTS already said); SAFETY.md gained the fail-off-watchdog bench
  item the journal had asked for but the checklist never got, the
  WS-only-down disarm check, and a blunt note that the remote-authority model
  is contingent on SK server security being enabled (per-path "sole writer"
  is a convention Signal K cannot enforce); the orphaned "GPIO" label in
  `hardware-wiring.svg` was re-anchored to its node; `hh-wiring.svg`'s
  deadman box says "not enabled yet".
- **Supply-chain:** both git `lib_deps` (the SensESP multi-wifi fork,
  ESP32Servo) are now pinned to the exact commits that were reviewed and
  tested, so a branch push can't silently change what a fresh build flashes.
  `npm audit`'s five advisories were checked: all in dev-only tooling
  (vitest/vite/esbuild); the deployed plugin's runtime has zero npm deps and
  the built bundle ships only react/react-dom. Left for a deliberate
  toolchain bump, not urgent.
- **Verification:** `pio test -e native` **205/205** (196 + 9 new); plugin
  **190/190** (181 + 9 net new), `tsc -b` clean, production build clean; all
  three firmwares build SUCCESS on the pinned deps. Nothing flashed — the
  commissioning checklists stand, now two items longer.

## 2026-07-23 — Documentation restructure for a developer audience

Session: (current session)

- **Owner ask:** are the documents, graphs, screenshots and flows all updated;
  are the structure, sections and locations well organised; is it easy to read;
  delete old references to what happened and why something was altered;
  document how it works and what parts it is made of, mainly from a developer's
  perspective.
- **Answered the first question honestly before starting: no.** The five SVG
  diagrams and the three screenshots were all pre-thruster — none showed the HH
  unit, the mode selector, TX's new inputs, the two-unit arm or the lamp panel.
  The prose was accurate but layered with archaeology.
- **Decisions taken with the owner:** history stays in this journal and is
  stripped from everything else (README included, per the owner's wording);
  documentation restructured as README + ARCHITECTURE + SAFETY + MEASUREMENTS;
  screenshots regenerated for real (Playwright downloaded).
- **New structure**, each document answering one question:
  - `README.md` — what it is, what it does, build/flash/test, how to work on it.
  - `ARCHITECTURE.md` — 13 numbered sections: parts, repo layout, runtime
    structure, a per-module reference table for the whole pure core, both
    control flows, the control law, liveness, the SK contract, the plugin,
    configuration, hardware pin maps, and how to extend it.
  - `SAFETY.md` — the invariants (split per machine, since they conflict), the
    cross-cutting rules, and the commissioning checklists.
  - `MEASUREMENTS.md` — resolved values as tables, outstanding items, and the
    procedures to obtain them.
  - `CLAUDE.md` — agent rules, pointing at the above rather than restating them.
- **SPEC.md retired.** Its Part I / Part II split with restarting section
  numbers was the main structural problem. That meant rewiring **164
  cross-references across 55 files** — done by scripted mapping from the old
  `SPEC.md Part N §M` forms to the new named/numbered targets, then a cleanup
  pass for the awkward possessives and over-long comment lines the substitution
  produced. Citations are now compact (`ARCHITECTURE.md §6`) so they fit inside
  the 80-column comment style.
- **Diagrams.** `system-overview` and `tx-signal-path` rewritten for two
  machines; `hardware-wiring` extended with TX's three new inputs and its
  four-state LED; two new diagrams added — `hh-wiring` (the third unit, its
  IMU, the ENGAGE/deadman inputs, and where firmware responsibility ends at the
  logic pins) and `hh-control-flow` (one tick of the safe core: arbitration →
  FSM → the two gates → output map, with the independent fail-off watchdog).
  `rx-architecture` and `rx-arbitration-flow` were already correct for the
  drives and were left alone.
- **Worth recording:** the diagrams were *rendered* to PNG through Chromium and
  looked at, not just written. That caught four real defects nothing else
  would have: overlapping labels in the system overview, a text block
  overflowing its box in the control flow, a legend running off the canvas,
  and — the significant one — a bulk y-coordinate shift in `hardware-wiring`
  that had moved two container boxes **twice**, leaving the bottom third of the
  diagram scrambled. Valid XML, correct-looking source, visibly broken output.
- **Screenshots.** Extended `scripts/screenshots.cjs` to simulate the HH unit
  on its own timer (so it can be "switched off" independently) and to mirror
  thruster commands back as `hh.*` telemetry. Six shots now, up from three:
  disarmed, two-handed drive manoeuvre, thruster MANUAL, thruster HOLD with a
  trimmed heading, **the thruster unit switched off** (drives still armed and
  working, missing unit named on the button and red in the panel), and the
  TX-precedence case. Two script bugs surfaced and were fixed: the connection
  lamp's text changed to lower case, and the TX-override scenario never waited
  for the arm token to be released, so the next scenario could not arm.
- **One inconsistency found and closed:** the wiring diagram cited a
  MEASUREMENTS item for servo power that the rewritten file had dropped. Servo
  power and ground are back as an outstanding item — and flagged as the one
  that actually blocks bench-testing a servo, since the board supplies only the
  signal.
- **A small UI fix fell out of the screenshots:** "THRUSTER UNIT" was being
  truncated to "THRUSTER UN…" in the lamp grid. Title font and column width
  adjusted.
- **Verification:** `pio test -e native` 196/196, all three firmwares build,
  plugin 181/181 plus `tsc` and a production build — re-run after the
  comment-rewiring sweep, since it touched 55 source files. Nothing flashed.

## 2026-07-23 — Remote bow-thruster control from TX and the plugin

Session: (current session)

- **Owner ask:** direct thruster control from TX and the plugin; remote control
  of the heading-hold direction; two new TX pins for it; plugin buttons for
  port/stbd ("no dwell time needed here, as human controlling it"); a
  manual-vs-heading-hold selector; existing HH safety functions retained; one
  ARMED button taking all controls with both units active and live; buttons kept
  easy to operate; more compact traffic-light telemetry; TX always winning both
  motion and thruster control.
- **Four things were asked before building**, because each changed the design
  materially. All four came back as the recommended option:
  1. **Mode selector: explicit, on TX + plugin.** The alternative (a manual press
     silently pre-empting the hold) leaves the thruster's mode never actually
     stated, and creates an unanswerable question about whether a resumed hold
     should re-capture the heading or return to the old one.
  2. **"Heading hold direction" = autopilot-style ±1°/±10° trim** of the held
     heading (the SPEC §10d design), not a separate absolute-entry UI.
  3. **ARM grants whatever is reachable**, rather than the strict both-live rule
     as literally worded — see the flagged trade-off below.
  4. **I proposed the TX pins** (18/19/22) rather than leaving TODOs.
- **The one place I pushed back on the request as written.** "Both units must be
  active and live" for a single ARM would mean an unpowered or faulty thruster
  board silently disables *gear* control — the primary docking tool, at the dock,
  in the moment it is needed. The thruster board is also the likelier of the two
  to be switched off. So arming grants whatever is answering and **names what is
  missing** (on the ARM button, in the plugin status line, and as its own lamp);
  it is refused only when neither unit is reachable, and an existing arm releases
  only when both are gone. The false-confidence rule is untouched: per-machine
  gating means arming with one unit present never lights up controls for the
  absent one.
- **The other place the request meets physics: "no dwell needed, a human is
  controlling it."** Honoured for everything the firmware chooses — no min-on, no
  min-off, no anti-chatter, no deadband; press thrusts on that tick, release
  stops on that tick. But the **reversal dwell stays**, because it is not a
  control-law preference: MEASUREMENTS.md HH Item 2 measured ~1.75 s of enforced
  interlock in the thruster's own control box, during which it will not accept
  the opposite direction anyway. Commanding through it cannot make the thruster
  reverse sooner — it only asks a contactor to switch into a spinning motor. So a
  port→stbd flick holds at OFF until the dwell expires, and the UI *says*
  "reversing — waiting for the thruster interlock" instead of looking broken.
- **The safety argument for remote actuation, in one line:** the arbitrated
  `engage_request` is fed to `SafetyFsm` at *exactly* the point the local ENGAGE
  level used to be. A remotely commanded thrust therefore travels the same path
  as a locally engaged hold and inherits every existing gate — BNO timeout →
  FAULT, deadman, ENABLE master gate, never-both-directions, the independent
  `esp_timer` fail-off on the other core — with none of them re-implemented and
  none relaxed. Only two HOLD-specific preconditions are adapted for MANUAL: the
  good-heading arm gate and the GNSS coast timers, because neither means anything
  when no heading is being held. Notably **invariant 4 was NOT relaxed** even
  though manual mode uses no IMU data: a frame timeout means the unit's own
  health is unknown, and keeping the fail-off path uniform across modes is worth
  more than being able to thrust with a dead sensor.
- **Two retained-value traps found and closed while building** — both instances
  of the same lesson as the 2026-07-22 dead-RX bug (Signal K keeps a path's last
  value forever, so silence withdraws nothing):
  1. A station that simply stopped publishing `targetDeg` would leave a stale
     heading standing, and HH would slew to a number somebody asked for minutes
     ago the next time hold engaged. Fixed with an explicit out-of-range
     sentinel (`kNoCommandedTargetDeg = 999`) that positively says "not
     commanding"; any implausible value clears the target at HH's end.
  2. The commanded target is published as an **absolute level**, not accumulated
     nudge events. An event stream has to arrive exactly once to stay correct;
     a level is self-correcting and survives a reboot. Same reasoning as
     liveness-by-time rather than by counter.
- **TX pin choice, and the trap worth remembering:** 18/19/22 from the SH-ESP32
  free list. Avoided 36/39 — input-only *and* with no internal pull-up/pull-down
  on ESP32, so `INPUT_PULLDOWN` does nothing there and the input floats without
  external resistors. Avoided 13/14 (free on TX) because they are RX's servo
  numbers and both panels are wired to one diagram. Recorded as MEASUREMENTS.md
  Item 7, marked **proposed, not confirmed** — veto at wiring time.
- **Telemetry panel** rebuilt as traffic-light lamps (coloured lamp + small
  fixed title + reading) in an auto-fitting grid. The old panel had grown to
  nine full-width prose rows, which on a phone pushed the drive controls off
  screen and made the row that mattered most — "is the board answering?" — no
  more prominent than the rest. The greying rule is unchanged and still
  load-bearing: a reading whose publisher has gone quiet is dimmed and unlit,
  never shown as a live verdict.
- **Verification (numbers, not "it compiled"):**
  - `pio test -e native`: **196/196**, up from 124. New suites:
    `test_thruster_arbitration` (20), `test_manual_thrust` (15),
    `test_setpoint` (21), plus 14 new remote-control integration scenarios in
    `test_control_step` and the two-unit rework of `test_link_indicator`. Every
    new ControlStep scenario runs the existing `AssertOutputsSafe()` on every
    tick, so a remotely-commanded thrust is held to the same output invariants
    as a local hold.
  - `pio run -e tx_shesp32 -e rx_shesp32 -e hh_shesp32`: all three SUCCESS from
    a wiped `.pio`.
  - `plugin/`: **181/181**, up from 132, including the arbiter's two-unit and
    thruster vectors and a new `ThrusterControl` suite. The App-level
    "unit switched off" scenarios were rewritten rather than patched: their
    premise genuinely changed under the arm-what-is-live rule, and the harness
    now simulates the two boards on independent timers so one can be killed
    without the other.
- **Not verified:** nothing was flashed. No part of this has touched real
  hardware — see SPEC.md Part II §14.8 for the bench checklist this feature
  needs, in particular scoping ENABLE/PORT/STBD through a commanded reversal
  before the outputs go anywhere near the thruster's remote.

## 2026-07-23 — Merged the heading-hold controller into this project

Session: (current session)

- **Owner ask:** "include ./projects/heading-hold-controller part of
  drive-remote-controller." Asked which depth was meant (full merge into one
  PlatformIO project / nest it unchanged as a separate build / docs-only
  cross-referencing), since the three read to very different amounts of work.
  Owner chose the **full merge**: one project, three firmware envs.
- **Why it was a real question:** the two projects had four hard name
  collisions — `include/config.h`, `include/secrets.h`, and *two different*
  `lib/control_core/debounce.h` and `lib/control_core/output_map.h`. Nothing
  could just be copied on top.
- **What was done:**
  - `lib/control_core/` split into `common/` (the one shared module),
    `drive/` (Part I's pure core) and `heading/` (Part II's). The library's
    include root stays `lib/control_core/`, so every include now carries its
    family prefix (`"drive/arbitration.h"`, `"heading/switcher.h"`,
    `"common/debounce.h"`). This is what disambiguates the two `output_map.h`.
  - The two `debounce.h` copies were **identical apart from comments** (Part I's
    was adapted from Part II's originally) — collapsed to one in `common/`,
    with a merged comment covering both uses and both timing rationales.
    Likewise the two `test_debounce/` suites differed only in a comment; kept
    one.
  - Heading-hold's hardware glue moved to `src/hh/`; new env `hh_shesp32`
    (`build_src_filter = -<*> +<hh/*>`) extending the existing
    `[shesp32_common]`. TX/RX envs unchanged.
  - `include/config.h` merged: a shared section (status LED pin, SK server
    address/port, FreeRTOS task stack/priority/core — all identical between the
    two) plus a per-machine section each. **One genuine value collision:**
    `kControlPeriodMs` was 20 ms (RX) and 10 ms (HH) — both are correct for
    their loop, so they became `kRxControlPeriodMs` / `kHhControlPeriodMs`
    rather than one being quietly made to lose. Nothing else changed value.
    HH's `main.cpp` also stopped hardcoding the SK server literal and now uses
    `config::kSkServerAddress/Port` like TX and RX.
  - Test dirs must be unique project-wide, so the two output-map suites are now
    `test_servo_output_map` (drive) and `test_thruster_output_map` (heading).
  - Docs folded together: `SPEC.md` is now Part I (drive) + Part II (heading
    hold) with section numbers restarting per part and all cross-references
    rewritten as "Part I §5" / "Part II §4.3"; `MEASUREMENTS.md` likewise, with
    heading-hold's items renamed **HH Item 1–6** (that's how `config.h` cites
    them now); `CLAUDE.md` merged; this journal keeps each part's log intact
    below.
- **The thing worth being careful about, stated plainly:** the two machines do
  **not** share safety rules, and the sharpest conflict is timing — the drives
  deliberately have *no* dwell/dead time, the thruster *must* honour a measured
  ~1.85 s reversal dwell. `CLAUDE.md` therefore keeps **two separate invariant
  lists** and says so explicitly, and `common/debounce.h`'s comment spells out
  that it is contact-bounce settling for both and a dwell for neither. Sharing a
  repo must not turn into sharing a policy.
- **Verification (not "it compiled" — the numbers line up exactly):**
  - Baselines before the merge: drive `pio test -e native` **49/49**,
    heading-hold **82/82**.
  - After: **124/124** in one run = 49 + 82 − 7 (the deduplicated
    `test_debounce` cases). No test was dropped or rewritten.
  - `pio run -e tx_shesp32 -e rx_shesp32 -e hh_shesp32`: all three SUCCESS.
- **Not verified / unchanged by this merge:** nothing was flashed. Both parts
  remain hardware-unverified — see SPEC.md Part I §8 and Part II §10.

---
---

# Part I log — Drive remote (TX / RX / plugin)

## 2026-07-23 — TX: RX-liveness gate on the status LED (the §5.2 fix, TX half)

Session: (current session)

- **Owner ask:** the plugin's safety functions are working (to be confirmed
  with measurements); bring the **TX** firmware up to the same checks/safety.
- **The gap (answered plainly first):** TX had the *exact* bug §5.2 fixed for
  the plugin. Its status LED was driven purely from TX's OWN socket
  (`get_ws_client()->is_connected()`), and TX subscribed to **no** RX
  telemetry at all — so with RX switched off, TX stayed connected to the SK
  server and the LED sat solid "ready" while nothing TX commanded could move a
  drive. "Everything green for a board that's not powered," on the physical
  remote.
- **What maps from the plugin, what doesn't:** RX-liveness-by-arrival (not by
  value) and "never show a confident ready state when RX isn't verified live"
  map directly. Exclusive-arm/universal-disarm token arbitration and
  multi-instance handoff do **not** — TX is a single physical station that
  outranks the plugin, and its "arm" is a physical latching switch, not a
  token.
- **Deliberately NOT gating TX's command/enabled publication on RX-liveness.**
  RX already fails to NEUTRAL on its own when commands stop; if RX is dead
  TX's `enabled=true` reaches nobody anyway. The only case where gating would
  change behaviour is an asymmetric link (TX→RX up, RX→TX telemetry delayed),
  and there it would *silently suppress the authoritative station's commands*
  on a one-directional glitch — a real availability regression on the
  more-authoritative control. The false-confidence risk is instead closed
  where it actually bites: the operator-facing LED. (`tx.enabled` keeps its
  SPEC §5 contract meaning: "TX's master enable switch state.")
- **Implementation:**
  - `lib/control_core/link_indicator.h` — pure, host-tested (the firmware
    analogue of the plugin's pure `rxLiveness.ts`): `{socket up?, RX proven
    live?}` → `kOff` / `kWarn` / `kReady`, plus a blink-phase→LED helper.
  - `test/test_link_indicator/` — 6 Unity cases (socket-down dominates;
    connected-but-RX-not-live warns; ready needs both; off never lights; ready
    solid; warn follows blink phase).
  - TX now subscribes to `rx.linkUp` and times its ARRIVALS through the
    existing pure `LinkWatchdog` (value discarded — a retained value can never
    reveal RX vanishing). LED: **solid** only when TX-connected AND RX live;
    **~2 Hz warning blink** when connected but RX isn't answering; **off** when
    TX's own socket is down.
  - `config.h`: `kRxTelemetryStaleMs = 1500` (matched to the plugin's
    `RX_STALE_TIMEOUT_MS` / arbiter `rxStaleTimeoutMs` so TX and the plugin
    judge "RX gone" on the same clock) and `kLinkWarnBlinkHalfPeriodMs = 250`.
- **Verified:** pure `link_indicator` logic compiled clean under
  `g++ -std=c++17 -Wall -Wextra` and all 10 assertions pass. **Not
  build-verified on the ESP32 toolchain** — PlatformIO isn't installed in this
  session (a full toolchain fetch risks the disk allowance); the SensESP edits
  reuse the exact `SKValueListener<bool>` + `LambdaConsumer` pattern already
  proven in `src/rx/sk_command_in.cpp`. Needs a local `pio test -e native`
  (should read 49/49) and `pio run -e tx_shesp32` before flashing, then the
  bench check below.
- **Bench check to add to SPEC §8:** power TX with RX off → LED must blink
  (warn), never sit solid; power RX on → LED goes solid within ~1.5 s; kill
  RX mid-session → LED drops back to blink within ~1.5 s.

## 2026-07-22 — Docs, diagram and screenshot sweep after the plugin rework

Session: (current session)

- **Trigger (user):** "check all documents, graphs, screenshots and update
  those" — i.e. bring every artifact in line with the four plugin changes
  landed earlier today (server-side arming arbitration, greyed derived rows,
  `rx.linkUp`, the RX-live arm gate). The firmware itself did not change, so
  the four firmware diagrams were re-read and left alone; everything stale was
  on the plugin side.
- **Screenshots were the worst of it — and `scripts/screenshots.cjs` was
  broken, not merely stale.** Its mock server still mirrored `plugin.*` deltas
  back from the UI's WebSocket, but the UI stopped writing deltas when the
  arbiter landed; there was no `/plugins/<id>/intent` route and nothing ever
  published `activeClient`, so nothing could arm. Rewrote it to run the **real
  `arbiter.cjs`** behind the real POST route (the same substitution
  `test/arbiterServer.ts` makes) and to republish RX telemetry on RX's 250 ms
  cadence, since both the arm gate and the UI judge RX by arrival.
- **A latent bug in that script, worth remembering:** `hasText: 'ARMED'` also
  matches **"DISARMED"**, so the "wait until armed" step passed instantly. The
  desktop shot was captured disarmed and looked plausible enough to commit
  (it was, back in July 21's run). Now matched with `/^ARMED$/`. Second catch:
  the arm token is exclusive and outlives the browser context holding it, so
  the next scenario opened to an *IN USE* switch it couldn't arm — the script
  now waits out the staleness eviction between scenarios.
- All three screenshots regenerated: they now show the "RX unit" row, the
  arbiter-driven "Control" row, and (desktop) a genuinely armed session with
  the port side dimmed and annotated "controlled by TX remote".
- **`docs/diagrams/system-overview.svg` redrawn** for the split plugin: the web
  UI box is now explicitly read-only ("writes nothing"), a new purple *Plugin
  Server — arbiter* box sits inside the Signal K server as the sole writer of
  `plugin.*`, and the UI→server arrow is a distinct purple "intent — HTTP POST,
  not a Signal K path" edge with its own legend entry. The other four diagrams
  describe firmware that hasn't changed and were left as they were.
- **Docs:** `README.md` — the "No custom server-side relay" bullet was now
  actively wrong, replaced with the authority/exclusive-arm/RX-gate trio; path
  table gained `plugin.activeClient` + `plugin.rxLive` and marks the plugin
  server as the writer; added the "a value can't tell you the publisher
  vanished" note under the telemetry section; test count 71→132.
  `plugin/README.md` — screenshot captions and the ASCII architecture block.
  `SPEC.md` — a stray paragraph had been inserted *inside* the §5 path table,
  splitting it into two broken tables; moved below, and §8 gained the six
  plugin-side bench checks the 07-22 entries said belonged there but never got.
  `MEASUREMENTS.md` — Item 6 rewritten (it still claimed 71/71 and "never run
  against a live SK server", though the container run is exactly how these
  bugs were found), plus the 1500 ms RX-absent timeout as Item 5b.
  `CLAUDE.md` — repo layout now lists `arbiter.cjs`, `rxLiveness.ts`,
  `useRxLiveness.ts`, the test harness and the screenshot script, and
  `index.cjs` is no longer described as "minimal plugin registration".
- Verified before/after: `npm test` 132/132, `npm run build` clean.
- **Still not verified on hardware.** Nothing in this session touched
  behaviour; the open item remains SPEC §8, now including its plugin half.

## 2026-07-22 — No arming without a verified RX (two linked safety bugs)

Session: (current session)

- **Trigger (user, from the boat):** three reports, all one failure seen from
  different angles — "Plugin can be ARMED even [when] RX link is not up and
  ready"; "If RX device is disconnected or turned off then plugin keeps
  showing `control.remoteController.rx.linkUp` `True`, which is major
  failure"; "Plugin UI shows all green and can be ARMED even [when] RX device
  is OFF". Confirmed both, immediately, by reading the code — this was not a
  misunderstanding on the user's part.
- **Root cause 1 — arming never knew RX existed.** `arbiter.cjs` granted the
  arm token purely on client intents (exclusive arm / universal disarm /
  staleness eviction, all about *clients*). RX's presence was not a condition
  anywhere in the path, so with the RX board unpowered the token was granted
  normally and the UI went to a confident red ARMED.
- **Root cause 2 — `rx.linkUp` cannot expire.** It is published *by* RX;
  Signal K retains a path's last value indefinitely; and `skClient` keeps its
  last-known `values` across disconnects by design. So when RX loses power the
  value just stands at `true` forever. Every RX-derived row in the status
  panel was styled from these frozen values, which is why the whole panel read
  green. **The lesson, and the reason both bugs are really one:** you cannot
  detect a publisher vanishing by looking at what it published. Only the
  *arrival* of data carries that information.
- **Fix, server side (the real gate):** `arbiter.cjs` gained
  `onRxTelemetry(now)` / `rxLive(now)`. `index.cjs` subscribes to
  `streambundle.getSelfStream('control.remoteController.rx.linkUp')` and calls
  it on every delta, **ignoring the value**. RX unseen for 1500 ms (six of
  RX's 250 ms refreshes) ⇒ not live ⇒ arm requests denied, and an existing arm
  released on the next tick (the plugin-side analogue of fail-to-NEUTRAL).
  Disarm stays ungated — a stop must always work. Verified against the real
  signalk-server source (`src/streambundle.js`) that `getSelfStream(path)` is
  pushed on **every** delta with no duplicate suppression: essential here,
  because a healthy RX republishes an *unchanging* `linkUp: true`, so anything
  that keyed on the value changing would call a healthy link dead. Fails
  closed: if the subscription can't be established, nothing ever arms.
- **Fix, UI side:** `skClient` now stamps a per-path arrival time on **every**
  delta (including unchanged values — the subtle part) and exposes
  `getReceivedAt()`; deliberately kept out of the React snapshot, since it
  changes 4×/s in steady state and would re-render the whole app for nothing.
  New pure `rxLiveness.ts` + `useRxLiveness` (which needs its own timer:
  staleness is the *absence* of events, so nothing will ever notify you of
  it). The ARM button is withdrawn with a named reason when RX isn't
  answering, a new "RX unit" row reports responding / NOT RESPONDING / NOT
  SEEN, and every RX-derived row greys out instead of vouching for last-known
  readings. The server also publishes its own verdict as `plugin.rxLive` so
  the button matches the gate exactly.
- **Answered a question raised mid-session** ("must `rx.linkOk` be the trigger
  to allow ARMED?"): **no** — `linkOk` means "a remote source is live *and*
  enabled", i.e. a consequence of something already being armed, so gating
  arming on it would deadlock (nothing could arm first). And `linkUp`'s value
  is exactly what freezes at `true`. Arrival is the only workable signal.
- **Tests:** 132 pass (was 118). New: `arbiter.test.ts` "RX must be present to
  arm" (7 vectors incl. unchanged-value liveness, mid-session release,
  no-silent-re-arm); `rxLiveness.test.ts`; `skClient` arrival-time tests;
  `StatusPanel` / `KillSwitch` RX-off suites; and `App.test.tsx` "App when the
  RX unit is switched off", which drives the whole loop against the real
  arbiter with the harness's simulated RX heartbeat stopped. `arbiterServer.ts`
  now simulates RX's 250 ms telemetry heartbeat with `stopRx()`/`startRx()`.
- **Not verified on hardware.** This is host-tested only; the RX-off path has
  not been exercised against a real signalk-server with a real RX board. That
  remains the top item to check on the boat — see SPEC.md §5.2 and §8.

## 2026-07-22 — Status panel: grey the derived rows out while not connected

Session: (current session)

- **Trigger (user, from a "Connecting…" screenshot):** while the Connection
  row was red ("Connecting…"), every row below it still showed confident
  green/yellow — including a green "Commands: reaching the boat" sitting
  directly under it, which is self-contradictory (if the socket isn't open,
  nothing is reaching the boat).
- **Root cause:** those rows are all derived state. `skClient.setConnectionState`
  swaps only `connectionState` and never clears `values`, so RX link / master
  enable / Port / Starboard render the *last-known* snapshot. And
  `useWriteAccess` only refetches on `open` and never resets `writeStatus`
  when the socket leaves `open`, so "reaching the boat" is a stale carry-over
  from the previous connection. Same discipline `writeAccess.ts` already
  states ("a withheld green check, never a false alarm") wasn't being applied
  to connection liveness.
- **Fix (`StatusPanel.tsx`):** added a `'stale'` RowStatus. When
  `connectionState !== 'open'`, all six derived rows (Commands, Control, RX
  link, RX master enable, Port, Starboard) collapse to `'stale'` — neutral
  left border + 0.45 opacity (`.status-row--stale`) — so the last-known text
  stays readable but unmistakably "was, not is". The Connection row keeps its
  own good/bad status (it's the live signal). The red "another device holds
  the arm" take-over alert is now also gated on `live`, so a stale `other`
  verdict can't raise it while offline.
- Kept last-known values greyed rather than blanking to "—": more useful to
  the operator, and the red Connection header already explains why they're dim.
- Tests: new `src/components/StatusPanel.test.tsx` (5) pins open→live-colours,
  connecting/closed→all-stale-and-no-live-verdict-leaks, and the offline
  alert-suppression both ways. Full suite 93/93, `npm run build` clean.

## 2026-07-22 — Live bug: `plugin.enabled` oscillating; server-side arming arbitration

Session: (current session)

- **Symptom (user, from the boat's Signal K container):** `control.remoteController.plugin.enabled`
  was flipping true↔false constantly even with the plugin's remote device set
  to ARMED.
- **Diagnosis (checked live from the `signalk-server` container, security on):**
  - No source priority configured for the path (`priorities.json`), so the
    server's merged value for it = whichever source wrote most recently.
  - `plugin/src/skClient.ts` stamped **every** UI instance's deltas with one
    hardcoded `$source: 'remoteController.plugin'`, and every instance
    republished `plugin.enabled` (its own React state) every 250 ms
    (`PERIODIC_REFRESH_MS`), defaulting to `false` on each fresh mount.
  - Confirmed **two** LAN client devices connected to `:3000` (host-net mode:
    `ss -tn` on the host showed `192.168.0.35` and `192.168.0.110`, two conns
    each). One armed (publishing `true`), one disarmed (publishing `false`) →
    the single shared-source value flapped at the refresh cadence. A single
    instance alone can't cause it (armed only ever publishes `true`).
  - Safety impact: RX's per-source "plugin armed" gate toggled, intermittently
    qualifying/dropping plugin commands — the exact servo oscillation §4's
    fixed-precedence redesign (2026-07-20) was meant to kill, reintroduced one
    layer up.
- **Design decision (with the user):** exclusive ARM, **universal DISARM**.
  Pushed back on the user's first instinct to also lock out disarm on
  non-holders — a kill must work from any station. Landed on: only one UI
  armed at a time, but any UI can always disarm (global stop), and it must be
  arbitrated by a **single authority**, not peer-to-peer (equal peers can't do
  race-free mutual exclusion on a shared bus).
- **Implementation:**
  - UIs no longer write the RX-facing `plugin.*` paths at all. Each open tab
    publishes only its own intent node `plugin.clients.<clientId>`
    (`{clientId, seq, armReq, disarmReq, port, stbd}`, 250 ms heartbeat).
  - New pure core `plugin/arbiter.cjs` (server-side analogue of
    `arbitration.cpp`; no SK/Node deps) — exclusive arm, universal disarm,
    **edge-triggered** arm/disarm counters (a held request can't silently
    re-arm across a release), stale-holder auto-release (`kSkStalenessTimeoutMs`),
    fail-to-neutral. `test/arbiter.test.ts`: 16 vectors.
  - `plugin/index.cjs` grew from a near-empty stub into the I/O shell: subscribes
    to `plugin.clients.*` (wildcard + `policy:'instant'`, the idiom verified in
    the installed `signalk-alarm-silencer`), feeds the arbiter, and is the sole
    writer of `plugin.enabled|port.command|stbd.command|activeClient` via
    `app.handleMessage` on change + a 250 ms heartbeat. Smoke-tested by loading
    it in real Node with a fake `app`.
  - Webapp: each UI derives ARMED from `activeClient === myClientId`
    (authority-driven, never a local guess). KillSwitch is now three-state
    (DISARMED→arm / ARMED→disarm / **IN USE**→global-stop); StatusPanel shows a
    Control row; new `plugin.activeClient` path + `makeClientId()`.
  - **API surface verified from the container**, not guessed: `@signalk/server-api`
    `serverapi.d.ts`/`streambundle.d.ts`/`subscriptionmanager.d.ts`
    (`handleMessage`, `subscriptionmanager.subscribe(cmd, unsub, err, cb)`,
    wildcard subscribe paths).
  - RX firmware unchanged (still reads the same three paths); added an
    explanatory note in `include/config.h` and SPEC.md §5.1.
- **Deployed to the boat's container** (`docker cp` of index.cjs/arbiter.cjs/public
  into `~/.signalk/node_modules/signalk-drive-remote-controller`, restart):
  loads clean, plugin enabled, new bundle served.
- **Revision, same session — intents moved OFF the SK data model.** On seeing it
  live the owner flagged `plugin.clients.ui-<uuid>` (one node per open browser
  tab) and `plugin.activeClient` as "a bit garbage" cluttering the data tree, and
  asked to keep the logic but stop publishing it. Agreed and reworked:
  - Per-client intents are now **HTTP POSTed** to the plugin's own route
    (`registerWithRouter` → `POST /plugins/<id>/intent`) instead of published as
    deltas, so **zero** per-tab nodes appear in the data model.
  - Security is preserved, not bypassed: verified in the server's
    `tokensecurity.js` that `app.use('/plugins', http_authorize(false))` wraps
    plugin routes — an unauthenticated request gets `401` before reaching the
    handler, the same posture as a dropped unauthenticated delta write. Also
    confirmed the server applies `bodyParser.json()` globally, so `req.body` is
    already parsed.
  - `activeClient` **kept** in SK (owner asked whether that was easier — it is:
    it's one tidy path, and each UI keeps deriving ARMED from
    `activeClient === myClientId` via the subscription it already has, so no new
    feedback channel was needed).
  - The UI's WebSocket is now strictly **read-only**. New regression test
    ("writes NOTHING to the Signal K data model") asserts it only ever sends
    `subscribe`, never `updates`.
  - `postIntent` is injectable, so the App/end-to-end tests still drive the REAL
    arbiter in-process (via `harness.feedIntent`) without needing an HTTP server.
- **Tests/build:** `npx vitest run` 88/88 (was 71 — added the arbiter suite +
  a real-arbiter integration harness `test/arbiterServer.ts` that the App and
  end-to-end tests now run against, so ARM/DISARM is exercised through the true
  intent→arbitrate→echo loop). `npm run build` clean. Not yet exercised against
  the live signalk-server instance end-to-end (next step: reinstall the tgz and
  re-open two devices to confirm the flap is gone).

## 2026-07-20 — Planning and Phase 0 scaffold
Session: (current session)

- User asked to rebuild `projects/drive-remote-controller/` (a remote drive
  shift controller: TX = handheld/second-station remote, RX = ESP32 driving
  servos on the port/stbd drives) using `heading-hold` as the
  structural template, and to read the old prototype in `reference/`
  (`tx11.ccp`, `rx11.ccp`, `platformio.ini`) to understand and improve on it.
- Read the old prototype closely and found two real bugs, not just style
  issues (see CLAUDE.md's "why this project exists" note for detail):
  1. Link liveness via `counter_in > counter_in_prev` doesn't recover
     promptly after a TX reboot (counter resets to 0, stays "less than" the
     old value for a long time).
  2. The old bow-thruster GPIO listeners had no fail-safe tie to link
     health — outputs latch at their last commanded value forever if the
     link dies.
- Entered plan mode. Iterated the plan through several rounds of
  clarification with the user before it was approved:
  - Initially proposed ESP-NOW + Signal K hybrid transport (ESP-NOW for the
    physical remote, SK for a future phone-based plugin). User asked how
    the plan would change with SK-only instead, weighed the trade-off
    (simpler/one transport vs. the physical remote's responsiveness being
    coupled to WiFi/SK-server uptime), and **chose SK-only**.
  - Initial arbitration design assumed a mandatory neutral dwell/reversal
    delay (mirroring heading-hold's `reversal_dwell`) and 3-way
    local/TX/plugin arbitration. **User corrected this significantly:**
    no dwell/dead-time at all (commands should reach RX "as soon as
    possible"); port and starboard commanding *different* directions from
    each other simultaneously is normal, not a fault (was worried this
    might be misread as forbidden); RX keeps *both* local port/stbd
    forward-neutral-reverse switches *and* its own master enable switch
    (not one instead of the other); TX also has its own master "Enable
    control" switch, separate from RX's.
  - Confirmed: this project's port/stbd drives are **fully independent**
    of `heading-hold`'s bow thruster (different hardware, no
    interlock needed between the two projects).
- Final architecture (see SPEC.md/CLAUDE.md for full detail): RX runs a
  FreeRTOS control task (safe core, same split as heading-hold) that
  arbitrates, per drive, between RX's own local switch (unconditional
  override), TX-via-SK, and a future plugin-via-SK, each remote source
  gated by RX's master enable switch and its own live+enabled check.
  Fail-to-NEUTRAL on any loss of link/enable, never fail-to-last-value.
- Plan approved. Scaffolding Phase 0 now: `SPEC.md`, `CLAUDE.md`,
  `MEASUREMENTS.md` (open items: servo calibration per drive, all
  switch/servo/LED GPIOs on both units, TX power source, SK staleness
  timeout, plugin path contract), `platformio.ini` (envs `tx_shesp32`,
  `rx_shesp32`, `native`), `include/config.h` + `include/secrets.example.h`,
  minimal `src/tx/main.cpp` / `src/rx/main.cpp` (SensESP boot + LED
  liveness + heartbeat, no control logic yet). `reference/` (the old
  prototype) kept as-is for history.
- `pio run -e tx_shesp32`: **SUCCESS** (RAM 16.3%, Flash 83.9%, 105 s).
  `pio run -e rx_shesp32`: **SUCCESS** (identical sizes — expected, both
  Phase 0 main.cpp files are near-identical boot scaffolds). Confirms the
  `build_src_filter = -<*> +<tx/*>` / `+<rx/*>` split in `platformio.ini`
  correctly isolates each firmware's `src/` subfolder (a filter mistake
  here would show up immediately as a "multiple definition of setup()"
  linker error, which did not happen).
- **Status: Phase 0 scaffold done, build-verified.** Not yet flashed — no
  device connected this session. Next: Phase 1 (`link_watchdog`,
  pure/test-first).

## 2026-07-20 — Phases 1-4: pure/testable safety core
Session: (current session, continued)

- Implemented all four pure `lib/control_core/` modules per SPEC.md §7 /
  CLAUDE.md build order, each with Unity tests under `test/`, TDD-style:
  - **`link_watchdog`** (Phase 1): `LinkWatchdog::Update(now_ms)` /
    `IsLive(now_ms, timeout_ms)`, purely time-since-last-update. 8 tests,
    including the explicit regression test for the old prototype's
    counter-comparison bug (`test_source_restart_is_live_on_next_update_
    regardless_of_gap`): a source that goes stale far longer than any
    counter could plausibly take to wrap, then sends one fresh update, is
    live again on that exact call.
  - **`debounce`** (Phase 2): copied unchanged from
    `../heading-hold/lib/control_core/debounce.h` (already a
    generic two-sided bool debouncer, no ENGAGE-specific logic to strip
    out) — 7 tests carried over with comments reworded for this project's
    context (shift switches, not ENGAGE).
  - **`drive_command`** (Phase 3): `DrivePosition` enum (`kNeutral`/
    `kForward`/`kReverse`), `FromSwitch(forward_active, reverse_active)`,
    and `ToSkString`/`FromSkString` for the SK string boundary (kept in
    lib/control_core so the boundary conversion itself is host-testable,
    per CLAUDE.md's coding-conventions note). `FromSwitch(true, true)`
    (a glitched/miswired switch reading both contacts at once) resolves to
    `kNeutral`, not a direction — defense-in-depth for invariant 1, on top
    of the enum already making "both directions" structurally
    unrepresentable. `FromSkString` fails to `kNeutral` for null/empty/
    garbage input, not just the two valid strings — a corrupted SK delta
    must never be silently read as a direction (invariant 5). 10 tests.
    Hit one build hiccup: the round-trip test's brace-init `for` loop
    needed an explicit `#include <initializer_list>` under `env:native`'s
    barer native toolchain — fixed immediately, unrelated to the logic.
  - **`arbitration`** (Phase 4, SAFETY-CRITICAL): `Arbitrate(local_position,
    rx_master_enable, tx, plugin) -> ArbitrationResult{command, source}`,
    implementing SPEC.md §4's priority exactly: local override
    unconditional -> RX master-enable gate -> most-recently-updated
    live+enabled remote source -> NEUTRAL if none qualify. Fully stateless
    (no dwell, per this project's explicit "no dead time" requirement,
    unlike heading-hold's `Switcher`) — call it once per drive with
    independent inputs, so port/stbd independence falls directly out of
    having no shared state, rather than needing to be a separate mechanism.
    13 tests: local-wins-even-with-enable-off, enable-off-forces-neutral-
    despite-live-remote, each single-remote-qualifies combination
    (including "live but not enabled" correctly NOT qualifying — a subtle
    one, since it's tempting to conflate the two), the most-recent-wins
    tie-break in both directions plus an exact-tie case (documented
    deterministic: tx wins ties, rather than leaving it as accidental
    behavior), and an explicit two-independent-`Arbitrate()`-calls test
    standing in for "port and starboard don't cross-affect each other."
- `pio test -e native`: **38/38 PASSED** across all four modules.
- **Status: Phases 1-4 complete — the entire pure, safety-critical
  arbitration/command core is implemented and green.** Everything from
  here (Phases 5-9) is hardware/SensESP-coupled and blocked on
  `MEASUREMENTS.md`'s open items (switch/servo/LED GPIOs for both units,
  servo calibration, SK staleness timeout) — stopping here to get those
  from the user rather than guessing pins, per AGENTS.md.

## 2026-07-20 — MEASUREMENTS.md answers + Phases 5-6
Session: (current session, continued)

- User answered most of `MEASUREMENTS.md` (while the IDE had
  `reference/tx11.ccp`'s pin lines 76-79 selected):
  - **Item 1 (servo calibration):** don't hardcode — make it live-tunable
    from the web UI, default 1000/1500/2000 µs (forward/neutral/reverse).
  - **Item 2 (TX pins):** reuses `reference/tx11.ccp`'s exact GPIOs — port
    forward=23, port reverse=25, stbd forward=27, stbd reverse=26,
    active-high ("pin is pulled high to make it active"). Described the
    switches as "3-position... momentary forward(one side)-neutral(middle)
    -reverse(other side)."
  - **Item 4 (TX power):** 9V battery, user's own physical ON/OFF switch.
  - **Item 5 (SK staleness timeout):** "max 1s."
- **"Momentary" needed a real clarification before writing more code** —
  it was genuinely ambiguous whether the drive needed to LATCH (stay in
  the commanded gear after a spring-loaded switch springs back to center,
  the way a real transmission mechanically holds itself in gear) or mirror
  the switch live (release = neutral immediately, like a bump/docking
  control). Getting this wrong would have meant either building an
  unnecessary stateful latch module, or shipping a "stays in gear" design
  when the user actually wanted momentary pulse control. Asked directly
  rather than guessing (AGENTS.md). **Resolved: momentary/live-mirroring
  is exactly right** — "when no forward or reverse is selected, both
  pulled down then you are in neutral." This is a **docking/maneuvering
  remote** (brief pulses), not a cruise gear-shift lever — releasing the
  switch does return to neutral immediately, no latch. The Phase 3
  `FromSwitch()` built *before* this was confirmed already implements
  exactly this (level-based, stateless), so **no rework was needed** —
  worth noting since it could easily have gone the other way.
- Also clarified TX's kill switch: confirmed as a **separate logical
  "Enable control" switch**, distinct from the 9V battery's physical
  power ON/OFF switch (device can be powered on but not control-enabled).
  Its GPIO is still unassigned — MEASUREMENTS.md Item 2 remains partially
  open.
- Applied all confirmed values to `config.h` and `MEASUREMENTS.md`
  (results table updated, resolved items marked ✅). Added the SK path
  constants from SPEC.md §5.
- **Phase 5 (TX firmware):** reads the 4 switch GPIOs (`INPUT_PULLDOWN`),
  debounces each contact (`kSwitchAssertStableMs`/`kSwitchReleaseStableMs`
  = 30 ms), combines via `FromSwitch()`, publishes port/stbd command
  strings + `enabled` to SK on change + a 250 ms periodic refresh. Enable
  switch pin still unknown, so `enabled` is hardcoded `false` for now —
  explicit fail-safe placeholder (RX's arbitration requires `enabled==true`
  to honor a source), not a guess at a pin. Link-OK LED now reflects real
  WiFi/SK state via `sensesp_app->get_ws_client()->is_connected()`
  (found by grepping the fetched SensESP source in `.pio/libdeps/` — no
  `ref/` in this workspace, so read the actual dependency instead of
  guessing the API).
  - Hit a real compile error worth recording: `using namespace sensesp;`
    plus `using control_core::Debounce;` made `Debounce` ambiguous —
    SensESP has its own `sensesp::Debounce<T>` transform. Fixed with a
    local alias (`using ShiftDebounce = control_core::Debounce;`); noted
    in CLAUDE.md's "known thin spots" so Phase 8's RX local switches don't
    hit the same wall.
  - `pio run -e tx_shesp32`: SUCCESS (RAM 16.3%, Flash 84.2%).
- **Phase 6 (RX Signal K command-in):** `src/rx/sk_command_in.{h,cpp}` —
  one `SkCommandIn` instance per remote source (TX, plugin), each
  wrapping 3 `SKValueListener`s (port/stbd command strings + enabled
  bool) that all feed one internal `control_core::LinkWatchdog` (any of
  the 3 updating counts as "this source is live," since TX publishes all
  three together). `Snapshot()` is the non-blocking mutex read, same
  convention as `heading-hold`'s `SkHeadingIn::latest()`. Wired
  both instances into `rx/main.cpp`'s `setup()`.
  - `pio run -e rx_shesp32`: SUCCESS (RAM 16.3%, Flash 84.4%).
- `pio test -e native`: still **38/38** after both phases (no pure-core
  changes) — confirms Phase 5/6 hardware glue didn't have to touch the
  already-tested safety core, as designed.
- **Status: Phases 5-6 done and build-verified, not yet flashed.**
  Blocked again on `MEASUREMENTS.md` Item 3 (RX's own switch/servo/LED
  GPIOs, still fully open) and the rest of Item 2 (TX's enable switch
  pin) before Phase 7 (RX control task) and Phase 8 (RX local switches)
  can be completed for real.

## 2026-07-20 — Remaining pins proposed + Phases 7-9 complete
Session: (current session, continued)

- User confirmed the default onboard LED (GPIO2) for both units' link-OK
  LED, and asked for pin proposals for the rest rather than specifying
  them. Proposed and applied (see MEASUREMENTS.md Items 2/3):
  - TX enable/kill switch: **GPIO21**.
  - RX: local port fwd/rev = **23/25**, local stbd fwd/rev = **27/26**
    (deliberately mirrors TX's own switch numbering -- one wiring diagram
    describes both panels), master enable = **21** (mirrors TX's enable
    pin), port servo = **13**, stbd servo = **14**.
  - Chose these specifically to avoid ESP32's boot-strapping pins
    (0/2/5/12/15) and input-only pins (34/35/36/39), staying inside
    `docs/hardware/SH-ESP32.md`'s documented free-GPIO list.
- Wired TX's real enable switch (`src/tx/main.cpp`): replaced the Phase 5
  fail-safe `enabled=false` stub with a real debounced GPIO21 read,
  published alongside the shift commands.
- **Phase 7 (RX FreeRTOS control task):** `src/rx/control_task.{h,cpp}` --
  pinned core-1 task (`kControlPeriodMs=20`, matching TX's own sample
  rate, `kControlTaskPriority=2` > Arduino loopTask's default 1), same
  fixed-period pattern as `heading-hold`'s `ControlTask`. Every
  tick, independently per drive: read+debounce the local switch ->
  non-blocking `SkCommandIn::Snapshot()` for both remote sources (reusing
  last tick's cached value on the rare contended-mutex miss, never left
  default-constructed) -> `control_core::Arbitrate()` -> `ServoPulseUs()`
  (new pure `lib/control_core/output_map.h`, TDD'd first, 4 tests) ->
  `Servo::writeMicroseconds()`. Drives both servos to NEUTRAL immediately
  in `begin()`, before the task's first tick, rather than whatever
  position they happen to power up in.
  - Deliberately did NOT reuse `ActiveSource`/`DrivePosition` ->
    string conversion inline in two places: `DrivePosition` already had
    `ToSkString()` from Phase 3 (reused directly), only `ActiveSource`
    needed a new `ControlTask::SourceName()`.
  - RX's link-OK LED reads `last_tx_port_.live && enabled` /
    `last_plugin_port_...` directly (not `port_result.source`), since the
    LED's SPEC.md definition is "a remote source is live+enabled" --
    independent of whether a LOCAL switch happens to be overriding a
    drive right now, which `.source == kLocal` would have masked.
- **Phase 8 (local switches + master enable + servos):** wiring was
  built directly into Phase 7's `control_task.cpp` rather than as a
  separate pass, since the control task's whole design already assumed
  real local-switch/servo pins from the start -- the plan's phase split
  was about *availability of the pins* (Item 3), not a natural code
  boundary once they existed.
- **Phase 9 (SensESP config + telemetry):** 6 `PersistingObservableValue
  <int>` + `ConfigItem`s (port/stbd x forward/neutral/reverse pulse
  width), default to `config::kServo*UsDefault`, flash-persisted,
  live-tunable from the web UI per MEASUREMENTS.md Item 1's explicit
  "modified from UI" requirement -- no reflash needed to trim calibration.
  Each of a side's 3 ConfigItems' change callback rebuilds that side's
  whole `ServoCalibration` from all 3 current values (not just the one
  that changed) and pushes it into the control task, so it's correct
  regardless of edit order or flash-load order. Telemetry: RX's `port/
  stbd.state`, `port/stbd.source`, `masterEnable`, `linkOk` published per
  SPEC.md §5 at `kSkPeriodicRefreshMs` (250 ms), non-blocking read of the
  control task's snapshot. TX needed no additional Phase 9 telemetry --
  its Phase 5 command/enabled publish already **is** its telemetry; SPEC's
  path table only defines an RX-side `linkOk`, not a TX one, since TX's
  own reachability is already self-evident from whether its other
  publishes are arriving at the server at all.
- `pio run -e tx_shesp32` / `-e rx_shesp32`: both **SUCCESS** (RX Flash
  85.6%, up from 84.5% after adding the 6 ConfigItems + telemetry
  outputs; TX unchanged at 84.2%). `pio test -e native`: **42/42**
  (38 + 4 new `output_map` tests) -- unaffected by any Phase 7-9 work,
  confirming the hardware glue didn't have to touch the tested safety
  core, as designed.
- **Status: Phases 0-9 all complete and build-verified.** Every phase in
  SPEC.md's build order has been implemented. **Not yet flashed to real
  hardware this session** -- no device connected. Remaining before this
  is genuinely done, per SPEC.md §8's sea-trial/bench checklist and
  MEASUREMENTS.md's safety preamble: bench-verify the servo output
  mapping with a scope/multimeter (engine off, ideally isolated) before
  connecting to a real drive actuator; confirm local-switch override,
  TX-remote control, and the WiFi/SK-drop -> NEUTRAL behavior all work
  on actual hardware, not just in host tests; measure real servo
  positions against the actual lever throw (the 1000/1500/2000 µs
  defaults are RC-standard starting points, not measured against any
  real linkage yet). The future Signal K plugin (phone UI) referenced
  throughout SPEC.md §5 has not been built -- RX's `plugin`-prefixed SK
  paths are implemented and will work the moment something publishes to
  them, but nothing does yet.

## 2026-07-20 -- README.md with vector diagrams
Session: (current session, continued)

- User asked for a README.md with two detailed sections (TX, RX) and
  vector graphics, not ASCII art. Loaded the dataviz skill for the
  categorical/status color palette (used it to keep entity colors
  consistent across all diagrams: local switches/LOCAL-wins = green
  #008300, TX = blue #2a78d6, plugin = orange #eb6834, matching the
  skill's validated categorical order).
- Before diagramming RX's mutex boundary, re-read control_task.h and
  found the servo-calibration write path (SetPortCalibration/
  SetStbdCalibration) was never actually mutex-protected -- true since
  Phase 9, just never called out in a comment the way
  heading-hold explicitly documented the equivalent trade-off
  for its IMU tare ConfigItems. Added that documentation now so the
  diagram's "crosses without a mutex" arrow style is accurate rather than
  glossing over a real asymmetry.
- Built 4 hand-authored SVGs in docs/diagrams/: system-overview.svg,
  tx-signal-path.svg, rx-architecture.svg (safe-core/smart-periphery
  split with the mutex boundary drawn explicitly, write-arrows down /
  read-arrows up), rx-arbitration-flow.svg (the exact priority order
  from arbitration.cpp as a flowchart). Rendered each to PNG with
  cairosvg (installed in a throwaway venv under the scratchpad, not
  system-wide) to actually look at them before calling them done, per the
  dataviz skill's own "render it and look at it" step -- caught and fixed
  three real issues this way: a box clipped off the right edge of the
  canvas, a legend line overflowing the canvas width, and an arrowhead
  terminating inside a box instead of at its edge. Also caught that the
  Unicode arrow character rendered as a missing-glyph box under this
  environment's font set and replaced all instances with plain "->" so
  the diagrams don't depend on the viewer having a particular font.
  Deleted the preview PNGs afterward -- only the SVG sources are checked
  in.
- Wrote README.md: system overview diagram + prose, then the two
  requested sections (Remote Controller / Receiver) each with their own
  diagram(s) and a pin table, a consolidated SK path contract table,
  build/flash commands, and a safety callout pointing back to
  MEASUREMENTS.md's preamble. Cross-checked every timing/pin number
  written into the prose (30 ms debounce, 250 ms telemetry/publish
  refresh, 1000 ms staleness timeout, 20 ms control tick, 50 Hz servo
  PWM, GPIO23/25/26/27/21/13/14/2) against config.h rather than
  re-typing from memory.
- **Status:** README + diagrams complete. Nothing in the project is
  committed to git yet (still fully untracked) -- offered to commit,
  awaiting the user's call on single vs. per-phase commits.
- User asked to use 200 Hz servo PWM instead of 50 Hz, pointing at
  reference/tx11.ccp's setPeriodHertz(200) line. Changed
  config::kServoPeriodHz from 50 to 200 -- same ESP32Servo fork and same
  [1000, 2000] us pulse range as the old prototype already used at 200 Hz
  successfully, so this isn't an untested combination. Updated the
  CLAUDE.md thin-spot note and README's RX pin table to match (both had
  documented 50 Hz as the default). pio run -e rx_shesp32: SUCCESS.
- **User deleted `reference/` (tx11.ccp/rx11.ccp/platformio.ini) from the
  project.** It had already served its purpose -- every fact worth
  keeping from it (the counter-comparison liveness bug, the missing
  fail-safe tie on the old bow-thruster listeners, the original GPIO
  assignments, the 200 Hz servo setting) was already carried into
  `CLAUDE.md`, `SPEC.md`, `MEASUREMENTS.md`, `config.h`, and this journal
  as the rebuild progressed, so nothing was actually lost. Swept
  `README.md`, `CLAUDE.md`, `SPEC.md`, `MEASUREMENTS.md`, `config.h`, and
  `link_watchdog.h` for anything phrased as "see reference/tx11.ccp" (a
  live pointer into a path that no longer exists) and reworded each to
  past tense -- "the original prototype (tx11.ccp, no longer in this
  repo -- see JOURNAL.md)" -- keeping the factual content, dropping the
  broken path. Left this journal's own earlier entries untouched: they're
  an accurate record of what was true in the sessions where they were
  written, and rewriting history here would defeat the point of a
  journal. `pio run -e tx_shesp32 -e rx_shesp32` and `pio test -e native`
  re-run clean after the doc sweep (comment-only changes, no logic
  touched).

## 2026-07-20 — Full code review + fixes (Claude, Opus 4.8)

Reviewed every source file, the pure core, both firmwares, build config, and
the vendored ESP32Servo/SensESP internals. All 42 host tests passed on entry;
both firmwares built clean. Findings and what was done:

- **Arbitration oscillation (HIGH, fixed).** When TX and the (future) plugin
  are both live+enabled and disagree, the old "most-recently-updated wins"
  tie-break flipped authority on every 250 ms refresh -> servo could
  oscillate forward<->reverse. **User chose fixed precedence: local > TX >
  plugin** (marine "station-in-command"). Rewrote `arbitration.cpp` step 3
  (TX wins whenever it qualifies; plugin only in TX's absence; recency no
  longer read), updated `arbitration.h` docs and the `last_update_ms` field
  note. Replaced the 3 recency tie-break tests with 4 fixed-precedence tests,
  including `test_result_independent_of_recency_no_oscillation` that sweeps
  the plugin's update time and asserts TX wins every time. `pio test -e
  native`: **43/43**.

- **Servo 200 Hz vs 50 Hz (HIGH, no change needed).** The session-start
  CLAUDE.md snapshot still said "50 Hz default," but the on-disk CLAUDE.md
  (L80) and `config.h` both already say 200 Hz — the user's 200 Hz decision
  (this journal, earlier today) is correctly reflected. No contradiction on
  disk. Still a bench-verify item: confirm the real drive servo is smooth
  at 200 Hz, else drop to 50 Hz (one-liner in config.h).

- **Liveness depends on refresh reaching RX (HIGH, verify on hardware).**
  Confirmed the producer half is fine: SensESP `SKOutput::set()` emits every
  call, so TX puts a delta on the wire every 250 ms even when values are
  unchanged. The unverified half is server-side: if the SK server dedupes
  unchanged deltas to subscribers, a held switch would stop refreshing RX's
  watchdog and drop to NEUTRAL every ~1 s. Must be checked live. No code fix
  possible on our side.

- **Held last-command up to ~1 s after abrupt TX loss (by design).**
  `kSkStalenessTimeoutMs = 1000` is user-confirmed (MEASUREMENTS Item 5).
  Left unchanged — noted the worst-case hold explicitly.

- **Servo calibration not clamped (MEDIUM, fixed).** Web-UI ConfigItem ints
  went through `static_cast<uint16_t>` before ESP32Servo's own clamp, so a
  negative/huge entry wrapped silently. Added `ClampServoUs()` in
  `src/rx/main.cpp` that range-clamps to [attach min, attach max] before the
  narrowing cast. Deliberately does NOT enforce fwd/neutral/rev ordering
  (linkage geometry may invert which pulse is larger).

- **Safe-core robustness (LOW, fixed).** `control_task.cpp`: null-check the
  telemetry mutex and don't start the loop without it; check the
  `xTaskCreatePinnedToCore` return and `ESP_LOGE` on failure (servos stay at
  boot-neutral); subscribed the control task to the Task WDT
  (`esp_task_wdt_add`/`_reset`) so a stall reboots -> re-inits servos to
  neutral. Added a note that `link_ok` reading only the `*_port_` snapshots
  is exact (shared per-source watchdog), not a shortcut.

- **Master-enable switch (LOW, documented).** User confirmed both TX and RX
  master-enable are LATCHING ON/OFF toggles (not spring-return). Documented
  in `config.h` so the "active-high like the shift switches" note isn't
  misread as momentary.

Both firmwares rebuilt clean after all changes; 43/43 native tests green.
Nothing flashed to hardware this session — bench-verify items above remain.

## 2026-07-21 — Signal K plugin: React 19 web UI as the third remote source

User asked for the Signal K plugin referenced throughout SPEC.md §5 to
actually be built: a React 19 touch UI, laptop- and mobile/tablet-friendly,
supporting genuine simultaneous multi-button presses (port forward + stbd
reverse at once, and any other combination), a kill switch, critical live
statistics on screen, all the same safety/liveness properties as TX, plus
end-to-end tests.

- **Researched the real signalk-server-node plugin/webapp API before
  writing any code**, rather than assuming — `/home/kgustafs/git/
  signalk-server` (v2.30.0) was available locally, so a research pass read
  the actual source (`src/interfaces/plugins.ts`, `src/interfaces/
  webapps.ts`, `src/interfaces/ws.ts`, `src/subscriptionmanager.ts`) and
  docs rather than guessing. Key verified facts that shaped the design:
  - A plugin's `public/` directory is only statically served if the
    package.json `keywords` include `signalk-webapp` (separate from
    `signalk-node-server-plugin`, which only makes it a *plugin*) --
    served at `/<package.json name>/`, not `/plugins/<id>/`.
  - Any connected WS client on `/signalk/v1/stream` -- including an
    unauthenticated browser tab, with the server's default security-off
    posture (`dummysecurity.ts`: `shouldAllowWrite` unconditionally
    `true`) -- can publish deltas through the *exact same*
    `app.handleMessage()` entrypoint a plugin's server-side code uses.
    This meant the whole plugin-side "relay" role I'd have otherwise had
    to build (browser -> plugin's own router -> app.handleMessage) is
    unnecessary: the browser app can talk to Signal K directly, exactly
    like TX's firmware does, just from a different runtime.
  - Exact delta/subscribe message shapes (`context`, `$source`,
    `timestamp`, `updates[].values[]`; `?subscribe=none` + a message-based
    `{context, subscribe:[{path}]}` for path-scoped subscriptions instead
    of the default per-context firehose).
  - `enabledByDefault` is a field on the *returned plugin object*, not a
    package.json key (a real, if minor, sibling-repo example I checked
    used an unverified package.json key for this -- didn't copy it without
    confirming the server actually reads it).
  This let the architecture collapse to: a near-empty `index.cjs` (just
  registers the plugin + satisfies the schema/start/stop interface) plus a
  React app that IS a Signal K WS client, architecturally parallel to how
  TX's firmware is a Signal K WS client -- not a new pattern, the same one
  reimplemented in a browser.

- **Built `plugin/` as its own npm/Vite/TypeScript/React 19 project**
  (separate toolchain from the PlatformIO firmware, `.gitignore`s
  `node_modules/` and the built `public/` the same way `.pio/` is
  gitignored on the firmware side):
  - `src/pure/driveCommand.ts` -- a deliberate line-for-line TS port of
    `lib/control_core/drive_command.h`'s `FromSwitch()`, including its
    fail-to-neutral-on-both-active behavior. Own unit tests, same
    discipline as the firmware's pure core.
  - `src/skClient.ts` -- the WS client (connect, scoped subscribe,
    publish, reconnect with capped exponential backoff), designed as a
    plain external store (`getSnapshot()`/`subscribe()`) rather than a
    hook, specifically so `useSyncExternalStore` could read it and its
    connection lifecycle would be independent of React's render cycle
    (immune to StrictMode's dev-mode double-invoke opening a second
    connection). Hit the classic `useSyncExternalStore` pitfall on the
    first test run: `getSnapshot()` returned a fresh object literal every
    call, which reads as "changed" every render under `Object.is` and
    caused an actual infinite render loop ("Maximum update depth
    exceeded") -- fixed by caching a single snapshot object, replaced only
    when a value actually changes.
  - `src/hooks/useMomentaryButton.ts` -- one hook instance per button,
    tracking its own `pressed` state and the specific `pointerId` that
    pressed it (ignoring releases from unrelated pointers, ignoring a
    second pointer landing on an already-pressed button). This is what
    makes true simultaneous multi-touch across *different* buttons work
    by construction: no shared "currently pressed button" variable
    anywhere for two fingers to contend over. Also force-releases on
    `pointercancel`, the tab going hidden, and window blur/pagehide --
    failure modes a physical spring-return switch doesn't have but a
    touch UI does (a backgrounded tab, an interrupted touch never gets a
    clean pointerup). RX's own 1000 ms staleness watchdog remains the
    backstop if even that somehow doesn't fire.
  - Mutual exclusion *within* one side (port fwd + port rev at once) falls
    directly out of reusing `fromSwitch()` on that side's two button
    states -- no special-case code, same as the firmware.
  - Components: `DriveControl` (per-side fwd/rev pair + live position
    indicator), `KillSwitch` (latching toggle, defaults OFF on every page
    load -- no physical switch position to read the way TX has on boot,
    so the safe default is chosen deliberately), `StatusPanel` (connection
    state, RX link-OK, RX master enable, each drive's state *and* which
    source is authoritative -- called out specifically because TX
    outranks this app by fixed precedence, so a press that visibly does
    nothing needs an honest on-screen reason, not just silence).
  - CSS: single deliberate dark theme (marine helm displays are read in
    bright sun and at night; treated this as a dedicated control panel,
    not a document needing light/dark parity), large touch targets,
    `touch-action: none` on the drive buttons specifically (not
    `manipulation`) so no in-flight press can be hijacked by a
    browser-native scroll/zoom gesture, safe-area insets, a CSS Grid that
    reflows between a stacked mobile layout and a status-sidebar laptop
    layout from one DOM structure. Reused this project's existing
    diagram color language (good/warn/bad from the README's SVGs) for the
    status rows.
  - Kept the buttons genuinely interactive (not HTML `disabled`) when the
    kill switch is off, matching TX's own real behavior: TX always
    publishes its true switch position regardless of its enable switch;
    the enable/kill boolean is what gates whether RX *acts* on it, not
    whether the switch itself still works. Only a dimmed visual + explicit
    "disarmed" label communicates the inert state.

- **Testing, deliberately layered, nothing mocked below the real Signal K
  server itself:**
  - `src/skClient.test.ts` -- wire-protocol correctness against a real
    in-process `ws` server (subscribe scoping, publish shape,
    reconnect-with-backoff, malformed-frame resilience).
  - `src/hooks/useMomentaryButton.test.tsx` -- press/release/cancel/
    unrelated-pointer/second-pointer/tab-hidden/blur, two independent
    instances never affecting each other.
  - `src/App.test.tsx` -- component-level interaction tests against a
    real `ws` server: kill-switch gating, single press/release publish
    shape, both-buttons-on-one-side -> neutral, and the specifically
    load-bearing one -- simultaneous Port-forward + Starboard-reverse via
    two different `pointerId`s both taking effect independently.
  - `test/endToEnd.test.tsx` -- one continuous realistic operator session
    (connect -> arm -> two-handed maneuver -> tab backgrounded mid-press
    or forced fail-safe release -> WS drop and reconnect with armed state
    preserved -> disarm), chaining the above into a single narrative
    rather than only testing behaviors in isolation.
  - Hit and fixed two real environment gaps along the way, not app bugs:
    jsdom has no `PointerEvent` implementation at all (a long-standing,
    known jsdom limitation) -- `fireEvent.pointerDown/Up` silently
    produced events with no usable `pointerId` until a small
    `PointerEvent`-over-`MouseEvent` polyfill was added to `test/
    setup.ts`; and jsdom has no pointer-capture methods, stubbed globally
    the same way. Also set `globalThis.IS_REACT_ACT_ENVIRONMENT = true`
    explicitly, needed for the end-to-end test's `act(async () => {...})`
    wrapping around a real socket-close/reconnect sequence.
  - `npm test`: **42/42 passing** (15 pure + 9 hook + 8 skClient + 9 App +
    1 end-to-end).

- **Build**: hit two TypeScript/tooling issues getting `npm run build`
  (`tsc -b && vite build`) green, both tooling artifacts rather than app
  bugs -- a `Plugin<>` type clash from vitest bundling its own transitive
  copy of vite (resolved by not routing `vite.config.ts` through either
  package's `defineConfig`, since it's purely a type-inference helper at
  runtime, and exporting a plainly-typed config object instead), and
  `tsconfig.node.json` needing `"composite": true` for the project
  reference from `tsconfig.json`. `npm run build`: **SUCCESS** (`public/`
  built, ~201 KB JS + 3.7 KB CSS gzipped to ~63 KB).

- **Docs**: wrote `plugin/README.md` (architecture, build/install/dev
  commands, a TX-parity table, testing-strategy summary). Updated the main
  `README.md` (new "Signal K Plugin" section, system-overview diagram no
  longer shows it dashed/"not yet built"), `CLAUDE.md` (repo layout, the
  "still open" plugin item resolved), `SPEC.md` (§1/§5/§10 no longer say
  "future"), and `MEASUREMENTS.md` Item 6 (confirmed, path contract
  matches SPEC exactly with no deviation).

- **Status: plugin implemented, tested (42/42), and building clean.** Not
  yet installed into or run against a real signalk-server-node instance --
  its tests stand in a real (unmodified) `ws` WebSocket server for that,
  and the server-side plugin/webapp mechanics were verified by reading the
  actual signalk-server-node source rather than assumed, but that's one
  step short of the real thing. Nothing in `plugin/` is committed to git
  yet.

## 2026-07-21 — Full-code review ("review all, verify end to end") + fixes

- **Scope**: every source file in firmware (`lib/control_core/`, `src/tx/`,
  `src/rx/`, `include/`) and plugin (`plugin/src/**`, `index.cjs`, build
  config, all tests) read and reviewed; API usage verified against the
  actual library sources in `.pio/libdeps` (SensESP multi-wifi fork,
  ESP32Servo fork, arduino-esp32 sdkconfig) rather than trusting comments.
  Two cloud review agents were launched but died on a spend limit; the
  review was completed inline instead.

- **Verified green before fixes**: `pio test -e native` 43/43; plugin
  `npm test` 71/71 incl. the end-to-end operator-session test; both
  firmware builds SUCCESS; SH-ESP32 pin map clean (all used GPIOs on the
  free-header list, no CAN/I2C/strap/input-only conflicts, all
  pulldown-capable); TWDT confirmed enabled+init'd (5 s, panic) in this
  core so the safe-core watchdog design works as commented; ESP32Servo
  `writeTicks` confirmed to clamp to attach bounds; empty-SSID
  `set_wifi_access_point("", "")` confirmed to disable the AP;
  `PersistingObservableValue` confirmed to load from flash in its
  constructor (so the immediate calibration push uses stored values);
  committed `plugin/public/` bundle confirmed byte-identical to a fresh
  build (not stale).

- **Finding (was Must-fix, resolved by owner decision)**: `include/
  secrets.h` with real WiFi/OTA credentials is tracked and pushed
  (commit 1494999) — every doc claimed it was gitignored, but the project
  never got its own repo and the workspace .gitignore doesn't cover it.
  **User decision 2026-07-21: the repo is private, credentials stay.**
  All "gitignored, never committed" comments corrected to state the real
  posture (secrets.h, secrets.example.h, both main.cpp headers, CLAUDE.md,
  SPEC.md Phase 0) with a "rotate before ever making the repo
  public/shared" note.

- **Fixes applied** (all re-verified: 43/43 native, 71/71 plugin, both
  firmware builds SUCCESS, vite warning gone, bundle hashes unchanged):
  1. Null-mutex crash guards: `ControlTask::latestTelemetry()` and
     `SkCommandIn` (callbacks + `Snapshot()`) now no-op safely if their
     mutex ever failed to allocate, instead of `xSemaphoreTake(nullptr)`
     configASSERT-ing the board into a reboot loop — degraded-but-neutral
     instead of crash-looping.
  2. SK server address/port moved from duplicated literals in both
     `main.cpp` files to `config::kSkServerAddress/kSkServerPort`
     (config.h's "never hard-code elsewhere" rule now actually holds).
  3. `plugin/src/config.ts` reconnect-backoff comment corrected: the 4 s
     cap is deliberately ABOVE the 1 s staleness timeout (disconnected =
     already failed-to-neutral at RX; backoff only delays recovery) — the
     old comment claimed the opposite relationship.
  4. `plugin/vite.config.ts`: `publicDir: false` (output dir collided
     with Vite's static-passthrough default; feature unused here).

- **Still open (unchanged)**: nothing has been flashed to hardware; the
  SPEC §8 bench/sea-trial checklist and a run against a real
  signalk-server-node instance remain the outstanding verification steps.

## 2026-07-21 — Plugin screenshots for the docs

- The plugin had written docs (plugin/README.md) but no screenshots and had
  never been rendered outside jsdom. Added `plugin/scripts/screenshots.cjs`:
  serves the real built `public/` over HTTP, stands in for signalk-server
  with a real `ws` WebSocket server (same approach as the test suite) that
  mirrors RX's arbitration into rx.* telemetry, serves a dummysecurity-style
  /skServer/loginStatus, and drives the app with headless Chromium
  (playwright, installed `--no-save` — deliberately not a devDependency).
- Three captures committed under `plugin/docs/screenshots/` and embedded in
  plugin/README.md (+ the two-handed one in the main README's plugin
  section): phone disarmed-by-default; phone armed with port FWD + stbd REV
  held by two pointers ("forward — this app" / "reverse — this app" in the
  status panel); desktop with TX holding precedence ("NEUTRAL · controlled
  by TX remote" annotation, "Port: forward — TX remote" attribution).
- Confirmed visually along the way: dark touch UI renders correctly at
  phone (390×844) and desktop (1280×800) viewports; the status panel's
  honest-state rows (Connection / Commands / RX link / master enable /
  per-side source) all show real values from the delta stream, and the
  write-access check goes green against the security-off loginStatus shape.

## 2026-07-22 — "RX link" row: link-up (liveness) vs link-OK (armed)

- **Symptom the owner reported:** the phone UI's "RX link" status row only
  went green when a device was ARMED, showing "no live+enabled remote"
  (orange) while merely connected/disarmed. Expected: green whenever the RX
  link is up and ready.
- **Root cause (not a bug — a naming/semantics mismatch):** that row read
  `rx.linkOk`, which RX computes as `(tx.live && tx.enabled) || (plugin.live
  && plugin.enabled)` (`control_task.cpp`). The plugin only reports
  `enabled=true` when a UI holds the arm token, so while disarmed the app is
  *live* (250 ms heartbeat, `index.cjs`) but not *enabled* — correctly
  failing the `linkOk` test. `linkOk` answers "is a remote armed & in
  command?", not "is the link up?".
- **Owner's decision:** keep the RX board's physical status LED exactly as
  is ("armed link ON" = live+enabled), and make only the phone row a pure
  link-health light.
- **Change:** added a second, weaker signal `rx.linkUp = tx.live ||
  plugin.live` (liveness only, armed or not). Firmware publishes it
  alongside `rx.linkOk`; the LED still reads `link_ok` (unchanged). The phone
  "RX link" row now reads `rx.linkUp` and shows "up — ready" / "no remote
  seen". Armed state stays carried by the ARM button and the "Control" row.
  - Firmware: `config.h` (`kSkRxLinkUpPath`), `control_task.h`/`.cpp`
    (`link_up` telemetry field), `main.cpp` (new `SKOutputBool`). LED code
    untouched.
  - Plugin: `config.ts` (`SK_RX_LINK_UP_PATH` + subscribe), `StatusPanel.tsx`
    (row reads linkUp), `screenshots.cjs` mock, docs (README ×2, SPEC §5).
- **Verified:** plugin suite 95/95 (added StatusPanel cases: row is green
  while disarmed as long as the link is up; warns when no remote is seen;
  App renders `up — ready` from `rx.linkUp`). **Not build-verified on the
  ESP32 toolchain** — PlatformIO isn't installed in this session and a full
  toolchain fetch would risk the disk allowance; the firmware edits mirror
  the existing `link_ok` publish path one-to-one. Needs a local
  `pio run -e rx_shesp32` before flashing. Screenshot PNGs also need
  regenerating (`screenshots.cjs` updated) to reflect the new wording.

## 2026-07-22 — Kill switch showed red ARMED while disconnected (stale)

- **Symptom the owner reported:** when the plugin UI loses its connection and
  the status-panel rows grey out, the kill switch *still* showed red **ARMED**.
- **Root cause:** `armed`/`foreignControl` derive from the arbiter's
  `activeClient` (`values[SK_PLUGIN_ACTIVE_CLIENT_PATH]`), and `skClient`
  never clears `values` on disconnect (line ~153: it flips connectionState to
  `closed` but keeps the last snapshot — by design, so StatusPanel can grey
  rows as "was, not is"). A latched red ARMED is a different beast from a
  greyed row: it asserts "you are commanding the boat right now," which is
  false the instant the socket drops — intents stop reaching the plugin, the
  arbiter stale-evicts us, RX fails to NEUTRAL. Same "vouching for stale data"
  class of bug the StatusPanel already guards against; the kill switch just
  wasn't gated on the connection.
- **Fix:** `KillSwitch` takes a `connected` prop and, while offline, renders a
  distinct non-interactive **offline** state (muted, dashed, disabled;
  "DISARMED — connection lost — reconnecting…") that takes precedence over
  armed/foreign. App gates the effective command state on the socket too:
  `commandable = armed && connected` drives the drive widgets and the
  override annotations, so they present as disarmed while offline. On
  reconnect the server re-sends state and the UI re-syncs (ARMED returns iff
  the arbiter still holds us). `controlState` passed to StatusPanel is left
  raw — it does its own liveness greying.
- **Files:** `components/KillSwitch.tsx`, `App.tsx`, `index.css`
  (`.kill-switch--offline`), SPEC §5.1 (display invariant). New
  `components/KillSwitch.test.tsx` (6 cases) pins that offline overrides a
  stale armed/foreign and that the button is inert; existing reconnect
  coverage in `test/endToEnd.test.tsx` still asserts ARMED returns after the
  blip.
- **Verified:** plugin suite 101/101; `npm run build` (tsc -b + vite) clean.

---
---

# Part II log — Heading hold (HH, bow thruster)

> Kept verbatim from the former `projects/heading-hold-controller/JOURNAL.md`.
> Note this half reads **oldest-first**, the opposite of Part I's log above, and
> its §-references are to what is now SPEC.md **Part II**. Paths mentioned in
> these entries (`lib/control_core/switcher.h`, `src/main.cpp`, …) are now
> `lib/control_core/heading/switcher.h`, `src/hh/main.cpp`, and so on.

## 2026-07-01 — Project scaffold
Session: (current session)

- User brought a pre-written, detailed `SPEC.md` (+ `CLAUDE.md` safety supplement,
  `MEASUREMENTS.md`) for the heading-hold-controller project, dropped
  directly in `projects/` rather than a project subdirectory.
- Read workspace `AGENTS.md`/`CLAUDE.md` and the three project docs.
- `system-profile.md` didn't exist yet. Ran a Phase 0 interview scoped to what
  this project actually needs (not the full generic checklist -- user pushed
  back on engine questions as irrelevant to a bow-thruster controller):
  - Vessel: Bayliner 3288, 1989, motor yacht.
  - Electrical: 12V.
  - Signal K server running; UM982 already publishing `navigation.headingTrue`
    (confirmed true heading, not COG) -- resolves SPEC §11 Item 3's path
    question, quality/RTK path still open.
  - WiFi available on board; credentials to be entered via SensESP's web
    config UI at first boot, not hardcoded.
  - Hardware in hand: Hat Labs SH-ESP32 + BNO086 breakout.
- Scaffolded `projects/heading-hold-controller/`: moved the three docs in,
  added `platformio.ini` (env `shesp32`, pioarduino platform, SensESP
  `^3.2.0`, `min_spiffs.csv`), `include/config.h` (canonical pin map from
  CLAUDE.md, output polarity default active-high, remaining TODOs from
  MEASUREMENTS.md left as comments -- not guessed), and Phase 0 `src/main.cpp`
  (SensESPAppBuilder boot, blue-LED liveness blink, heartbeat SKOutput).
  `lib/control_core/` and `test/` created empty, ready for Phase 1.
- Cross-checked the CLAUDE.md pin map against `docs/hardware/SH-ESP32.md`:
  no conflicts (GPIO33/35 opto pins used as intended, GPIO14/15/13/27/25/26/39
  all free GPIOs).
- **Status: Phase 0 scaffold only.** Not yet built or flashed. Next: build
  `pio run -e shesp32`, flash, verify WiFi join + SK visibility + LED blink
  (Phase 0 "done-when"), then start Phase 1 (`rvc_parse`, pure/test-first).

- First `pio run -e shesp32` failed: PlatformIO's own Python venv (installed
  via `uv tool install platformio`) had no `pip`, breaking its Arduino
  framework dependency check. Fixed with `ensurepip` in that venv (one-time
  host toolchain fix, unrelated to this project's code).
- Rebuilt: **SUCCESS**. RAM 16.0% (52 KB/320 KB), Flash 78.7%
  (1.55 MB/1.97 MB, `min_spiffs.csv`). Not flashed yet -- no device
  connected this session.

## 2026-07-01 — Phase 1: rvc_parse
Session: (current session, continued)

- Implemented `lib/control_core/rvc_parse.{h,cpp}` per SPEC §Phase1: pure
  C++17 (no Arduino.h), 19-byte BNO08x UART-RVC frame parser. `RvcParser`
  takes one byte + a caller-supplied `now_ms` at a time (so timeout logic
  stays host-testable without `millis()`); slides its window by one byte
  on bad header/checksum instead of needing an explicit resync call.
  Decodes yaw/pitch/roll (int16 LSB-first, 0.01 deg units); checksum =
  sum(bytes[2..17]) mod 256, matching the SPEC frame layout.
- Added `test/test_rvc_parse/test_rvc_parse.cpp` (Unity): valid decode,
  negative-yaw sign/endianness, ±180 wrap, bad checksum rejected, resync
  after a bad frame, resync after byte-level misalignment (stray 0xAA),
  and timeout before/soon-after/long-after a valid frame. 10 cases.
- Wired up `[env:native]` in `platformio.ini` (was stubbed out at Phase 0):
  had to override `lib_deps =` (empty) for that env -- it was otherwise
  inheriting `SignalK/SensESP` from the shared `[env]` section and pulling
  in ReactESP/AceButton/etc. for a native build that can't compile them.
  Also `test_build_src = false` + `build_src_filter = -<*>` so `src/`
  (Arduino/SensESP-dependent) is never touched by the native test build.
- **Environment gap found:** this machine had no C/C++ host compiler at
  all (no gcc/g++/clang/cl on PATH) -- `pio test -e native` can't run
  without one. Asked the user; they approved installing one. Installed
  **WinLibs MinGW-w64 GCC 16.1.0 (POSIX threads, UCRT)** via
  `winget install BrechtSanders.WinLibs.POSIX.UCRT`. Winget adds its
  `mingw64\bin` to the **user** PATH env var, but that only takes effect
  in *new* top-level processes -- this session's already-running shells
  don't see it, so `pio test -e native` needs
  `PATH="...WinLibs...\mingw64\bin:$PATH"` prefixed explicitly until a
  fresh terminal/session picks up the updated PATH automatically.
- `pio test -e native`: **10/10 PASSED.**
- **Status: Phase 1 complete** (pure module + host tests green). Not yet
  wired to real hardware -- `src/rvc_reader.{h,cpp}` (UART2 glue calling
  into `RvcParser`) is a later task, done once we get to hardware
  bring-up rather than now. Next per CLAUDE.md build order: Phase 2
  (`yaw_rate` -- wrap-safe diff/LPF/spike-reject, pure).

## 2026-07-01 — Phase 2: yaw_rate
Session: (current session, continued)

- Added `lib/control_core/angle_math.h` (`WrapDeg180`) -- pulled out as a
  tiny shared header rather than duplicated in `yaw_rate.cpp`, since
  CLAUDE.md's coding conventions mandate the same (-180,180] wrap in both
  `yaw_rate` (this phase) and `heading_filter` (Phase 4); one correct
  implementation matters for a safety-relevant computation.
- Implemented `lib/control_core/yaw_rate.{h,cpp}` per SPEC §4.1:
  `YawRate::Update(yaw_deg, dt_s)` does wrap-safe delta -> spike
  rejection -> LPF (exponential moving average sized from `tau_r`).
  Design choice not spelled out in SPEC: on a rejected sample (spike or
  `dt_s <= 0`), the stored "last good yaw" is **not** updated, only the
  filtered rate is held. That way a single-tick glitch doesn't corrupt
  the next real sample's delta -- documented in the header since it's
  not obvious from the SPEC pseudocode alone.
- `test/test_yaw_rate/test_yaw_rate.cpp` (Unity, 6 cases): first-sample
  seeding, convergence to a known constant rate, spike rejected +
  held-rate + stored-yaw-not-corrupted, wrap across the +-180 seam,
  a single noisy-but-sub-threshold sample being damped rather than
  tracked, and zero-dt rejection.
- `pio test -e native`: **16/16 PASSED** (10 from Phase 1 + 6 new).
  `pio run -e shesp32` still green (unaffected, pure-core-only change).
- **Status: Phase 2 complete.** Next per CLAUDE.md build order: Phase 5
  (`switcher` -- bang-bang core, SAFETY-CRITICAL, pure) before Phase 4,
  per the "do 1, 2, 5 first, then 4" sequencing note.

## 2026-07-01 — Phase 5: switcher (SAFETY-CRITICAL)
Session: (current session, continued)

- Implemented `lib/control_core/switcher.{h,cpp}` per SPEC Sec 4.3-4.4:
  `Switcher::Update(e_deg, r_dps, now_ms)` -> `Cmd` (kOff/kPort/kStbd).
  Lead variable `s = e - Td*r`, Schmitt on/off thresholds, min-on/min-off
  timing gates, reversal dwell, and a duty tracker that widens the
  deadband above `duty_warn` and latches new-ON inhibition above
  `duty_max` (clearing again once duty drops below `duty_warn`).
  - **Key structural safety property** (documented in the header, not
    just tested): while thrusting, the *only* transition Update() can
    produce is to `kOff` -- there is no code path from `kPort` straight
    to `kStbd` or vice versa. A new direction can only be requested
    starting from `kOff`. Combined with the timing gates this is what
    lets Phase 6's output driver trust it will never be asked for both
    directions, and that reversals always pass through a dwell-gapped
    OFF. This falls directly out of the SPEC's own Schmitt pseudocode,
    not an extra rule I bolted on.
  - Deviated from the SPEC's illustrative `SwitchCfg` sketch by adding a
    `duty_window_s` field -- the sketch didn't include one, but a duty
    fraction needs a window length. Used an exponential moving average
    (`alpha = dt/(window+dt)`) instead of a literal ring-buffer sliding
    window, to keep the module allocation-free; documented as an
    approximation in the header/cpp comments.
- `test/test_switcher/test_switcher.cpp` (Unity, 12 cases): structural
  no-direct-reversal check, the SPEC's own lead-term worked example
  (stops at s=0 while e is still 5 deg, vs. a no-lead control that
  doesn't), hysteresis/no-chatter at both the on and off thresholds,
  min-on, min-off, reversal-dwell-blocks-opposite-but-not-same-direction,
  dwell-elapsed-allows-reversal, duty rising under continuous thrust,
  duty_warn widening the effective thresholds, and duty_max inhibiting
  new engagement until duty recovers below duty_warn.
- `pio test -e native`: **28/28 PASSED** (10 + 6 + 12). `pio run -e
  shesp32` still green.
- **Status: Phase 5 complete.** Per the "do 1, 2, 5 first, then 4"
  sequencing note, next is Phase 4 (`heading_filter` -- complementary
  fuse of BNO yaw rate + gated Signal K heading, pure).

## 2026-07-01 — Phase 4: heading_filter
Session: (current session, continued)

- Implemented `lib/control_core/heading_filter.{h,cpp}` per SPEC Sec 4.2:
  `Predict(r_dps, dt_s)` integrates the local yaw rate (fast path);
  `Correct(GnssHeading, now_ms)` nudges the fused estimate toward a
  Signal K/UM982 heading through freshness, quality, and plausibility
  gates, gain-limited by `k_corr` (slow path).
  - `GnssHeading` (heading_deg, t_ms, valid) lives in this pure header
    rather than Phase 3's not-yet-built `SkHeadingIn` -- same split as
    Phase 1's `RvcSample`: the data struct is pure, the thing that
    populates it over the network is hardware-coupled and deferred.
    `valid` stands in for "the caller has already judged quality OK"
    (SPEC Sec 11 Item 3's real RTK/stdev path is still unknown --
    MEASUREMENTS.md -- so heading_filter deliberately never interprets
    a quality value itself, only trusts the caller's boolean).
  - **Design decision not fully specified by SPEC's pseudocode:** what
    does "plausible (jump <= max heading rate x age)" compare against?
    Comparing the incoming fix to the *fused* estimate would wrongly
    reject legitimate corrections (the fused estimate is *expected* to
    have drifted -- that's the whole point of correcting it). Instead,
    plausibility compares the new fix to the **previous accepted GNSS
    fix**, rate-limited by the time between the two fixes -- the same
    spike-rejection pattern already used in `YawRate`, just applied to
    the GNSS stream instead of the gyro stream. This catches a single
    corrupted UM982 report without throttling normal drift correction.
    A rejected sample doesn't overwrite the stored "last good" fix,
    same non-corruption property as `YawRate`. The very first-ever
    accepted fix bootstraps with no plausibility check (nothing to
    compare against yet).
- `test/test_heading_filter/test_heading_filter.cpp` (Unity, 10 cases):
  predict integration + wrap, non-positive dt ignored, first-fix
  bootstrap, stale/invalid fixes rejected, implausible/plausible
  consecutive-fix jumps, rejected-spike-doesn't-corrupt-last-good, and
  the SPEC's own "coasts through a gap, converges on return" scenario
  (20 s of gyro-only drift to ~20 deg, then ~50 s of returning GNSS
  fixes pulling the fused estimate back to within 0.5 deg of truth).
- `pio test -e native`: **38/38 PASSED** (10+6+12+10). `pio run -e
  shesp32` still green.
- **Status: Phase 4 complete -- all currently-planned pure modules
  (1, 2, 5, 4) done and host-tested.** Remaining CLAUDE.md phases are
  hardware/SensESP-coupled: Phase 0 already scaffolded but not yet
  flashed/verified on real hardware; Phase 3 (SK heading-IN, WebSocket),
  Phase 6 (output driver, bench-verify against a scope before any live
  thruster), Phase 7 (safety FSM), Phase 8 (FreeRTOS control task
  wiring), Phase 9 (SensESP config/telemetry) all still need the actual
  SH-ESP32 connected to make progress -- not something to keep pushing
  on blind without hardware in the loop.

## 2026-07-01 — .gitignore / repo structure, network config
Session: (current session, continued)

- User asked to change the root workspace `.gitignore` so this project's
  code could go to GitHub. Root `.gitignore` had `projects/` excluded by
  design (AGENTS.md: "each project its own git repo"), and this project
  already had its own separate nested git repo (6 commits). Rather than
  deleting that nested `.git` (losing history) or leaving a broken
  submodule-style reference, merged it into the root SensESP-workspace
  repo with `git subtree add`, preserving full commit history (verified
  byte-identical file contents before/after, only CRLF/LF checkout
  differences). Root repo is `KEGustafsson/SensESP-workspace` on GitHub;
  pushed to branch `heading-hold`.
- Hardcoded WiFi/SK server/OTA settings into `src/main.cpp` at the
  user's explicit request (hostname `sensesp-heading-hold`, SK server
  `<redacted-sk-server>:3000`, WiFi SSID `<redacted-ssid>`). Flagged the standard
  concern first (AGENTS.md pitfall: don't hardcode WiFi creds, since
  they'd land in git history/GitHub permanently) -- user confirmed the
  values given are placeholders, not real credentials, so no live
  secret exposure. **If real credentials go in later, don't commit them
  as-is** -- either keep using SensESP's web config UI (device flash,
  never in source) or take care about git history if hardcoding for
  real. `pio run -e shesp32` still green after the change.

## 2026-07-01 — Track SensESP main branch
Session: (current session, continued)

- User asked to depend on SensESP's `main` branch directly instead of
  the pinned `^3.2.0` registry constraint (which had actually been
  resolving to the 3.4.0 release), and to add `SensESP/NMEA0183` "if
  needed." Checked `ref/SensESP` (already on `main`, commit `d3e8a6f`,
  `VERSION` = `3.4.1-alpha` -- ahead of/unreleased past 3.4.0) and
  `ref/NMEA0183` (`main`, 3.1.2-alpha). Nothing in this project currently
  parses NMEA0183 (BNO086 is binary UART-RVC; UM982 heading comes via
  Signal K WebSocket, not raw NMEA0183 serial) -- did NOT add the
  NMEA0183 lib_dep, since it isn't needed by anything built so far. It
  would become relevant only for the SPEC's optional Phase 10b (a
  dedicated UART NMEA parser for UM982 heading, bypassing WiFi).
- Changed `platformio.ini`'s `[env]` `lib_deps` from
  `SignalK/SensESP @ ^3.2.0` to `https://github.com/SignalK/SensESP.git#main`.
  Documented the tradeoff in a comment: this moves with upstream main,
  less reproducible than a version/commit pin.
- **Gotcha:** running `pio pkg uninstall` (to force-refresh the cached
  dependency before rebuilding) silently reformatted the whole
  `platformio.ini` -- stripped every comment and the section-separator
  bars, collapsed to tabs. Had to rewrite the file from the last-known
  content to restore the comments; kept the (correct) config values
  from the reformatted version. **Lesson: avoid `pio pkg` subcommands on
  a hand-commented `platformio.ini`; `pio pkg uninstall -l <name>`
  followed by a plain `pio run` achieves the same cache-refresh without
  touching the ini file.**
- Rebuilt: `pio run -e shesp32` **SUCCESS** against SensESP 3.4.1-alpha
  (confirmed via `.pio/libdeps/shesp32/SensESP/VERSION`) -- current
  `main.cpp`'s API usage (`SensESPAppBuilder`, `set_hostname`,
  `set_sk_server`, `set_wifi_client`, `enable_ota`, `RepeatSensor`,
  `SKOutputInt`) all still compiles unchanged against main. RAM 16.1%,
  Flash 79.6% (up slightly from 78.7% on the pinned 3.4.0). `pio test -e
  native`: 38/38 still pass (native env's lib_deps is independently
  overridden to empty, unaffected by this change).
- Updated `CLAUDE.md`'s two mentions of the old `^3.2.0` pin to reflect
  tracking `main` now, so the doc doesn't go stale/misleading.

## 2026-07-06 — Phase 0 hardware bring-up + Phase 1 rvc_reader (hardware glue)
Session: (current session)

- Hardware connected for the first time: SH-ESP32 (COM4, CH340) with the
  BNO086 wired per the canonical pin map (TX -> GPIO14). Built + flashed
  Phase 0 scaffold as-is first: booted cleanly, LED blink/heartbeat ran,
  but WiFi failed to join `<redacted-ssid>` (`NO_AP_FOUND`) -- expected, since
  this bench session isn't on the boat's network. User chose to skip WiFi
  for this session and bench-test BNO086/RVC ingest instead; WiFi code
  left as-is (non-blocking, retries in the background) since re-pointing
  it at a throwaway bench SSID wasn't requested.
- Implemented `src/rvc_reader.{h,cpp}` (Phase 1's hardware glue, deferred
  from the pure-module session): `RvcReader::begin(HardwareSerial&,
  rx_pin)` configures `Serial2` for RVC's fixed 115200 8N1 RX-only (TX
  pin -1, since RVC is BNO-TX -> ESP-RX only); `poll()` drains available
  bytes through `control_core::RvcParser`; `timedOut()` wraps `RvcParser`'s
  own timeout against a new `config::kBnoRvcTimeoutMs` (250 ms bench
  default -- not yet tuned, documented as revisit-before-Phase-7 in
  `config.h`).
- Wired a bench-test-only poll loop into `main.cpp` (`event_loop()->
  onRepeat(20, ...)`, decimated to ~2 Hz logging via `ESP_LOGI`/`ESP_LOGW`,
  tag `"rvc"`) -- explicitly commented as bring-up only, superseded by
  Phase 8's pinned FreeRTOS control task reading through the same
  `RvcReader`.
- `pio run -e shesp32`: SUCCESS (RAM 16.1%, Flash 80.3%). `pio test -e
  native`: still 38/38 (config.h's new constant isn't touched by the
  pure/native build).
- **Verified on real hardware, not just compiled:**
  - Live yaw/pitch/roll streamed continuously and matched physical
    handling of the board (values tracked tilts, settled back to stable
    readings at rest) -- confirms GPIO14 wiring and the BNO086's RVC
    mode-pin setup are correct (CLAUDE.md's "known thin spot" resolved).
  - Timeout path: pulled the BNO TX wire mid-session -> `ESP_LOGW`
    "BNO086 RVC timeout" fired ~0.9 s after the last valid frame (inside
    the 250 ms config window plus this test's own reaction-time slack);
    reconnected -> frames resumed cleanly next cycle, no stale/stuck
    values in between. First synchronized attempt was inconclusive (log
    showed handling-induced motion but no timeout or gap -- likely the
    unplug/replug or the capture window weren't actually aligned in
    time); re-ran with an explicit "unplug now" cue and got a clean,
    unambiguous result.
- **Status:** Phase 0 hardware-verified except WiFi join (untested against
  the real boat network this session). Phase 1 now fully done, including
  the hardware glue -- both the live-data and timeout `done-when` criteria
  confirmed on the actual BNO086, not just inferred from host tests. Next:
  either verify WiFi/SK against the boat's actual network when available,
  or continue toward Phase 3 (SK heading-in) / Phase 6 (output driver,
  bench-verify against a scope) -- both still need decisions from the user
  (network access, thruster interface presence) before proceeding.

## 2026-07-06 — Phase 3 (SK heading-in) + Phase 6 (output driver)
Session: (current session, continued)

- **Resolved a CLAUDE.md "known thin spot":** checked `ref/SensESP` (main
  branch) directly rather than assuming -- it already has `SKListener` /
  `SKValueListener<T>` (`FloatSKListener`) for inbound WS subscriptions,
  matching the exact pattern in `ref/SensESP/examples/listener.cpp` and
  `freertos_tasks.cpp` (`new SKValueListener<float>("navigation.headingX")`
  -> `connect_to(new LambdaConsumer<float>(...))`). SensESP is *not*
  actually outbound-only on current main -- that concern was stale.
- Implemented `src/sk_heading_in.{h,cpp}`: `SkHeadingIn::begin()` subscribes
  `config::kSkHeadingTruePath` ("navigation.headingTrue", confirmed
  2026-07-01) via `FloatSKListener`; the callback stores
  `control_core::GnssHeading{heading_deg, t_ms=millis(), valid=true}` --
  reusing Phase 4's existing `GnssHeading` struct instead of inventing a
  parallel one. `latest()` returns whether any delta has ever arrived;
  staleness is left to the caller (`t_ms` vs `now_ms`), matching
  `HeadingFilter::Correct()`'s own freshness gate.
  - MEASUREMENTS.md Item 3 (RTK/quality path) is still unresolved, so
    `valid=true` here means only "a fresh value arrived", not
    "quality-checked" -- documented in the header so this isn't mistaken
    for real quality gating later. `config::kSkHeadingListenDelayMs = 200`
    is a bench default (UM982 update rate, Item 4, still unmeasured).
  - Not thread-safe by design yet -- fine while everything's on the Arduino
    loop task; flagged in the header that Phase 8's control task needs the
    mutex from CLAUDE.md invariant 5 before reading this concurrently.
  - **Not hardware-verified this session** -- device has no reachable WiFi/SK
    server on this bench (see Phase 0 note above). User chose "code review
    only" for now over pointing it at a substitute network. Reviewed the
    implementation against `ref/SensESP`'s listener API and the SPEC
    interface; compiles clean; real data-flow verification deferred to
    when the device is on the boat's network (or another reachable SK
    server, if the user wants to test sooner).
- Implemented Phase 6 output driver, split pure/glue like every other
  safety-relevant phase:
  - `lib/control_core/output_map.h` (new, pure, header-only):
    `ComputeOutputLevels(bool armed, Cmd cmd) -> OutputLevels{enable, port,
    stbd}`. This is where CLAUDE.md invariants 1-2 actually live --
    structurally cannot express `{port=1, stbd=1}`, and every disarmed
    branch returns all-false regardless of `cmd`. Added to `CLAUDE.md`'s
    repo layout list (wasn't anticipated there).
  - `test/test_output_map/test_output_map.cpp` (Unity, 3 cases but
    exhaustive): all 6 (armed x cmd) combinations against the SPEC's own
    output-map table; a loop over all 6 combinations asserting
    `!(port && stbd)`; a loop over all 3 `cmd` values with `armed=false`
    asserting every line forced low. `pio test -e native`: 41/41 (38 + 3
    new).
  - `src/outputs.{h,cpp}` (thin glue): `Outputs` stores the last
    `(armed, dir)`, recomputes through `ComputeOutputLevels()` on every
    `setEnabled`/`setDirection`/`allOff()` call, and writes the three GPIOs
    through `config::kOutputActiveHigh`. `setEnabled(false)` also resets
    the stored direction to `kOff` so re-arming never resurrects a stale
    direction -- not strictly required (the pure function already forces
    output low while disarmed regardless of stored `dir_`), but avoids an
    armed+immediately-thrusting surprise on the next arm.
- Wired a temporary bench-cycle into `main.cpp` (6-step sequence stepping
  DISARMED -> ARMED-coast -> PORT -> coast -> STBD -> coast every 2 s,
  logged via `ESP_LOGI("outputs", ...)`) -- explicitly commented as
  bring-up only, no physical thruster interface connected, superseded by
  Phase 7/8's real FSM + `Switcher`.
- `pio run -e shesp32`: SUCCESS (RAM 16.1%, Flash 80.7%).
- **Pin mix-up caught before it caused a problem:** user said they had
  "spare LEDs wired to GPIO12/13" to watch the bench cycle. Flagged before
  proceeding: GPIO14/15 are already the BNO086 RX/RST lines (not relevant
  here, but the pin range overlaps confusingly), and **GPIO12 specifically
  is an ESP32 boot-strapping pin** (flash voltage select) that SPEC.md/
  CLAUDE.md already avoid for outputs -- an LED there isn't wired to
  anything this firmware drives (our three output pins are 33/13/27), and
  a strong external pull on it at reset can in principle cause boot
  failures (none observed this session, for what it's worth). Recommended
  moving that LED to GPIO27 (STBD) instead of GPIO12.
- **Verified on real hardware:** watched the GPIO13 (PORT) LED against the
  bench-cycle serial log (steps at 3.4s DISARMED, 5.4s coast, 7.4s PORT,
  9.4s coast, 11.4s STBD, 13.4s coast) -- user confirmed the LED lit only
  during the "ARMED PORT" window and stayed off otherwise. ENABLE (33) and
  STBD (27) weren't independently probed this session (share the exact
  same `ComputeOutputLevels()`/`Apply()` code path as PORT, and are
  exhaustively covered by the host tests) -- still worth a meter/LED check
  on those two specifically before ever wiring a real thruster, per SPEC's
  "verify logic levels on a scope before connecting the physical
  interface" instruction.
- **ENABLE (GPIO33) debugging thread:** user then measured ENABLE on the
  board's isolated **Opto OUT** screw terminal (with an external Vext
  supplied to the isolated side) and saw it constant/not toggling. Before
  assuming a firmware bug, checked whether GPIO33 is a plain GPIO on this
  board -- it isn't: `docs/hardware/SH-ESP32.md` documents it as the
  built-in optoisolated output ("can drive slow-speed single-sided NMEA
  0183 or control relays"). Pulled the actual schematic source from
  `github.com/hatlabs/SH-ESP32-hardware` (`optocouplers.kicad_sch`,
  `ESP32.kicad_sch`) and confirmed it's a real EL3H7 optocoupler +
  MMBT5551L driver stage, not a pass-through -- i.e. a genuinely separate
  electrical domain on the isolated side, consistent with needing its own
  supply/common. Could not reliably determine exact polarity/open-collector
  behavior from the raw schematic text alone (would need visual schematic
  tracing) -- said so plainly rather than guessing on an actuator-adjacent
  circuit. Recommended the same direct test that already worked for PORT:
  probe GPIO33 at the ESP32 module pin itself, pre-isolation. **Result:
  GPIO33 toggles correctly there**, matching the bench-cycle log exactly --
  confirms the earlier "constant HIGH" reading was the isolated Opto OUT
  wiring/interfacing (missing return path, no load, or an actual polarity
  inversion through the optocoupler -- still unconfirmed which), not a
  firmware defect. User then fixed the isolated-side wiring/interfacing
  and confirmed **the Opto OUT terminal now tracks ENABLE correctly too**
  -- so the earlier constant-HIGH reading really was just the isolated-side
  hookup, now resolved on the user's end (not a firmware change).
- **Status:** Phase 6 done and hardware-verified end-to-end: ENABLE (33,
  both pre-isolation and through the isolated Opto OUT terminal) and PORT
  (13) directly confirmed against the bench-cycle log; STBD (27) shares
  the identical `Apply()`/`WritePin()` code path and is exhaustively
  covered by the host tests, not independently probed but low-risk given
  the shared path. Phase 3 implemented and code-reviewed but not
  hardware-verified (no reachable SK server this session). Remaining
  work: Phase 7 (safety FSM), Phase 8 (FreeRTOS control task wiring these
  phases together), Phase 9 (SensESP config/telemetry) -- Phase 7 in
  particular needs MEASUREMENTS.md Items 2 (reversal dwell) and 3 (heading
  quality path) as they become available, though neither blocks starting
  the FSM state machine itself (pure, host-testable).

## 2026-07-06 — Phase 7: safety FSM
Session: (current session, continued)

- Implemented `lib/control_core/safety_fsm.{h,cpp}` per SPEC.md Sec 2 /
  Phase 7: `SafetyFsm::Update(FsmInputs) -> FsmState` where `FsmState` is
  `kDisarmed/kArmedIdle/kHolding/kFault`. Owns no sensors/GPIOs -- takes an
  already-evaluated snapshot (`engage`, `deadman_ok`, `bno_ok`,
  `heading_ok_to_arm`, `heading_age_ms`) and decides the state, same
  "caller already judged it" boundary used throughout this project
  (`GnssHeading::valid`, etc).
  - **Flagged a real design gap in the SPEC before implementing:** the
    prose doesn't say whether resuming HOLDING after any forced exit
    (disarm, fault, or a GNSS coast-max disengage) should happen silently
    once conditions recover, or require a fresh ENGAGE press. Given this
    drives an actuator near people, asked the user rather than guessing --
    they confirmed the safer choice: **require a fresh engage edge**
    (release-then-repress) to resume. Implemented via a `hold_requested_`
    latch that's set on an engage rising edge and cleared on every forced
    exit from an active/idle-armed state (disarm, fault, or coast-max).
    The one case that *does* auto-promote without a fresh edge: holding
    `engage` through `ARMED_IDLE` while waiting for the very first heading
    fix, since nothing was ever interrupted there.
  - `T_coast_warn`/`T_coast_max` default to SPEC Sec 6's 10s/30s. Past
    `coast_max` while `HOLDING`, "disengage cleanly to manual" (SPEC Sec 2
    rule 6) means dropping to `ARMED_IDLE` (ENABLE stays high, no active
    steering), not a full `DISARMED` -- matches the FSM's own
    ENABLE-follows-state table (`ARMED_IDLE`/`HOLDING` -> ENABLE high).
  - The SPEC's second fail-off watchdog ("serial/loop stall -> outputs
    OFF") is out of scope here on purpose -- a stalled loop can't even be
    calling `Update()` to detect itself; that needs an external watchdog
    (ESP32 TWDT), which is Phase 8 territory per CLAUDE.md's own
    "known thin spots" list.
- `test/test_safety_fsm/test_safety_fsm.cpp` (Unity, 11 cases) --
  deliberately mirrors SPEC's own fault-injection list line for line: kill
  BNO mid-hold -> FAULT; deadman release mid-hold -> DISARMED; engage
  release mid-hold -> DISARMED; bad-quality heading at arm -> refused
  (stays `ARMED_IDLE` indefinitely); GNSS coast-warn keeps holding
  (telemetry flag only); GNSS coast-max disengages to `ARMED_IDLE`; plus
  the fresh-engage-required-to-resume behavior after both a fault recovery
  and a coast-max disengage, and `JustEnteredHolding()`'s one-shot
  behavior. `pio test -e native`: **52/52** (41 + 11 new).
- Replaced Phase 6's synthetic bench-cycle in `main.cpp` with a real
  Phase 7 bench wiring: reads the actual ENGAGE switch (GPIO35, SPEC's
  documented opto-IN convention: >=2.5V -> HIGH), feeds real
  `bno_ok = !rvc_reader.timedOut()`, forces `heading_ok_to_arm = true` as
  an explicit bench-only stand-in (Phase 3 has no reachable SK server this
  session, so leaving it real would strand the demo in `ARMED_IDLE`
  forever), and drives `outputs.setEnabled()` from the resulting state
  (direction lines stay OFF -- `Switcher`/`control_task` is Phase 8, and
  PORT/STBD were already independently bench-verified in Phase 6).
  `pio run -e shesp32`: SUCCESS (RAM 16.1%, Flash 80.7%).
- **Verified end-to-end on real hardware** (the actual point of this
  phase -- closing the gap found earlier this session where BNO loss
  didn't affect ENABLE):
  - Toggling the real ENGAGE switch produced clean `DISARMED <-> HOLDING`
    log transitions timestamped to the physical flips (single-tick
    Disarmed->Holding jumps are expected/correct here since
    `heading_ok_to_arm` is bench-forced true, so `ARMED_IDLE` is passed
    through invisibly within one `Update()` call -- matches the
    `test_engage_with_good_heading_arms_directly_into_holding` host test).
  - **Pulled the BNO wire while ENGAGE was held high:** RVC timeout fired
    at +3.2s, FSM transitioned to `FAULT` at +3.5s with `engage=1` still
    logged -- i.e. **ENABLE now actually drops on BNO loss even while
    armed**, which is the exact behavior invariant 4 requires and which
    was conspicuously absent before this phase. Reconnecting the BNO
    recovered to `ARMED_IDLE` (not `HOLDING`) at +13.7s, confirming the
    fresh-engage-required design on real hardware, not just in host
    tests. Repeated the full fault/recover cycle a second time
    (+25.2s/+25.3s/+29.7s) with identical behavior. A follow-up capture
    to also confirm the fresh-engage-resumes-holding step on hardware
    caught no activity (timing miss, not a failure) -- left unconfirmed
    on hardware this session since the host test already covers it and
    the higher-risk half (BNO-loss-drops-ENABLE) is solidly confirmed.
- **Status:** Phase 7 done and hardware-verified for the safety-critical
  behaviors that matter most (BNO-loss fail-off, manual engage arm/disarm).
  Not yet wired: deadman (no switch present this session, `deadman_ok`
  hardcoded true), real heading-quality gating (still blocked on
  MEASUREMENTS.md Item 3), and the coast-warn/max timers against a real
  GNSS feed (no reachable SK server this session -- covered by host tests
  only). Next per CLAUDE.md build order: Phase 8 (FreeRTOS control task --
  wires `RvcReader` -> `YawRate` -> `HeadingFilter` -> `SafetyFsm` ->
  `Switcher` -> `Outputs` together on a pinned core-1 task, replacing this
  session's ad hoc bench wiring in `main.cpp`).

## 2026-07-06 — Phase 8: FreeRTOS control task
Session: (current session, continued)

- **Blocker flagged before starting:** a real control loop needs a real
  `SwitchCfg`, and `reversal_dwell_s` is MEASUREMENTS.md Item 2 -- must
  come from measuring the actual thruster control box, explicitly
  "do NOT guess." No thruster interface exists yet. Asked the user rather
  than inventing a placeholder; they chose to wire everything except
  direction pulsing: the full safe-core pipeline runs for real, `Switcher`
  is deliberately not instantiated, direction stays OFF always. The one
  spot to add it later is marked with "Item 2" in `control_task.cpp`.
- Made `SkHeadingIn` thread-safe (CLAUDE.md invariant 5): added a
  `SemaphoreHandle_t` mutex around `latest_`. The writer (SensESP's WS
  callback, Arduino loop task) takes it with `portMAX_DELAY` (fine, it's
  not the control loop); the reader's `latest()` takes it with a **zero
  timeout** and just leaves `*out` unchanged / returns false on the
  (vanishingly rare) contended case, so the control task can never block
  on it. Folded the old separate `have_sample_` flag into
  `GnssHeading::valid` (they were always set together anyway) and
  repurposed the return value to mean "mutex was free," not "ever
  received" -- documented clearly since that's a real API semantics
  change from Phase 3's version.
- Implemented `src/control_task.{h,cpp}`: a task pinned to core 1
  (`xTaskCreatePinnedToCore`, priority 2 -- one above Arduino's loopTask,
  which defaults to priority 1 on the same core), fixed 10 ms period via
  `vTaskDelayUntil` (matches the BNO's own 100 Hz RVC rate). Per tick:
  poll BNO -> feed `YawRate` only on an actual new frame (using elapsed
  time since the last one, not the task's own period) -> `HeadingFilter::
  Predict()` every tick regardless -> refresh the cached SK heading
  (non-blocking) -> `HeadingFilter::Correct()` only when the cached
  sample's `t_ms` actually changed (avoids re-applying `k_corr` ~20x for
  one real fix between SK server pushes, which would over-weight a stale
  correction) -> build `FsmInputs` (real ENGAGE GPIO35, real BNO health,
  heading freshness reusing `HeadingFilter::Cfg::t_fresh_s` so there's one
  `T_fresh` concept, not two) -> `SafetyFsm::Update()` -> `Outputs`
  (direction forced `kOff`, see Item 2 above). Also measures loop
  period/jitter (min/max/avg over rolling 5 s windows) and logs a
  consolidated status line every 2 s -- both done-when requirements from
  SPEC's Phase 8.
  - Config additions: `kControlPeriodMs`/`kControlTaskStackBytes`/
    `kControlTaskPriority`/`kControlTaskCore`. `HeadingFilter::Cfg{}` and
    `FsmCfg{}` reuse their own already-established in-class "typical
    start" defaults rather than duplicating them into `config.h` --
    consistent with how Phases 4/5/7 already did this.
  - Rewrote `main.cpp`: removed the three independent bench-test
    `onRepeat` blocks from Phases 1/3/7 entirely, replaced with
    `sk_heading_in.begin(); control_task.begin(&sk_heading_in);`. SensESP
    now only owns WiFi/SK/OTA/LED-blink/heartbeat -- true "smart
    periphery," never touching BNO UART, ENGAGE, or thruster GPIOs.
  - `pio test -e native`: 52/52 (unchanged -- nothing in `lib/control_core`
    changed behaviorally). `pio run -e shesp32`: SUCCESS (RAM 16.2%,
    Flash 80.7%).
- **Found and fixed a real concurrency/timing bug via hardware testing,
  not just code review:** first flash showed `state=FAULT` continuously
  even with the BNO solidly connected and streaming -- despite loop
  jitter looking perfect (10.00 ms avg even under WiFi reconnect churn,
  which is itself a nice confirmation of invariant 5). Root-caused with
  three escalating diagnostics (raw byte count -> raw hex dump, checksum
  verified by hand against the frame spec -> a poll-call/poll-hit
  counter) before finding it: **`RvcReader::poll()` called `millis()`
  internally** (once per byte) instead of taking the caller's `now_ms`.
  The old bench code happened to work because it called
  `rvc_reader.timedOut(millis())` in a *separate, later* statement after
  `poll()` had already returned -- guaranteeing a fresh, later `millis()`
  reading. `control_task.cpp` instead captures one `now_ms` per tick
  *before* calling `poll()`, so `last_valid_ms_` (stamped by `poll()`'s
  own later, fresher `millis()` call) could end up slightly *ahead* of
  that tick's `now_ms` -- and `RvcParser::TimedOut`'s unsigned
  `now_ms - last_valid_ms_` underflows to a huge value on any negative
  true difference, reporting "timed out" on frames that had in fact just
  arrived. Fixed by changing `RvcReader::poll()` to take `now_ms` as a
  parameter (used for both the parse timestamp and the timeout check),
  removing the internal `millis()` call entirely -- structurally
  impossible to drift now, not just fixed for this call site.
- **Verified on real hardware after the fix:** status log immediately
  showed `state=ARMED_IDLE` (ENGAGE was left on from Phase 7 testing) with
  correct `bno_ok`. Pulled the BNO wire again to confirm the fix didn't
  regress the safety behavior: FAULT at +4.6s, still FAULT at +7s,
  recovered to ARMED_IDLE at +9s once reconnected -- and `fused` heading
  visibly converged and settled (30.37 deg) once the board stopped moving
  from being handled, with `r` (yaw rate) settling to 0.00 -- real,
  correct gyro integration through the whole pipeline, not just log
  plumbing. Jitter remained tight (9-11 ms range around the 10 ms target)
  throughout, including during the BNO fault/recovery cycle.
- **Status:** Phase 8 done and hardware-verified: deterministic timing
  measured (done-when #1), BNO fail-off + recovery + real heading
  integration confirmed under load (done-when #2/#3 partially -- no real
  thruster/Switcher yet, and no live SK server this session so the
  GNSS-coast timers and heading-arm gating are still only host-tested).
  Direction control (Switcher) remains unwired pending MEASUREMENTS.md
  Item 2. Remaining CLAUDE.md phases: Phase 9 (SensESP config/telemetry +
  `navigation.attitude` SK output) is the only one left that doesn't
  depend on a real thruster interface or a live SK server -- worth doing
  next. Wiring `Switcher` in is a small, well-scoped follow-up once Item 2
  is measured (the exact spot is commented in `control_task.cpp`).

## 2026-07-06 — Phase 9: SensESP config + telemetry + attitude output
Session: (current session, continued)

- Confirmed `ref/SensESP`'s main branch already has `AttitudeVector`
  (`sensesp/types/position.h`) and `SKOutputAttitudeVector` (=
  `SKOutput<AttitudeVector>`) with a compound JSON serializer already
  registered -- SPEC.md's "likely needs a small custom SKOutput" (Sec 7.1)
  was, like Phase 3's listener concern, a stale worry; studied
  `ref/Morticia-eCompass` per CLAUDE.md's pointer and used the exact same
  pattern.
- **Scoped down from SPEC's full Sec 6 table on purpose:** SPEC wants
  "all Sec 6 params" exposed to the web UI, but `Switcher` isn't wired in
  yet (Item 2 blocker from Phase 8) -- there's no live object for
  on_thr/off_thr/Td/min-on/off/dwell/duty-warn/max to control, and
  exposing UI sliders with no effect would be actively misleading. Scoped
  this phase to what actually has an effect right now: IMU mount tare
  (roll/pitch, MEASUREMENTS.md Item 6) and yaw-rate-sign, both applied at
  the telemetry-publish boundary in `control_task.cpp` (not fed back into
  the pure `HeadingFilter`/`YawRate`/`SafetyFsm` modules, which stay on
  their Phase 4/5/7 compile-time defaults -- making *those* live-tunable
  would mean restructuring already-tested pure modules for parameters
  nothing downstream currently uses; natural to bundle with the Switcher
  wiring work later instead of doing it partially now). Documented this
  scope choice in `control_task.h`'s class comment.
- Implemented via `sensesp::PersistingObservableValue<T>` +
  `ConfigItem(...)` (found the exact idiom in
  `ref/signalk-halmet-searay-system-monitor`): three new flash-persisted,
  web-UI-editable values -- IMU Roll Tare (deg), IMU Pitch Tare (deg),
  Invert Yaw Rate (bool), all defaulting to identity/no-op per Item 6
  being unmeasured. Read via plain `->get()` from the control task
  (different task than the web UI's writer) -- deliberately NOT
  mutex-protected like `SkHeadingIn`/the telemetry snapshot: documented in
  `control_task.h` as an accepted trade-off for a slowly-changing,
  non-safety-critical calibration scalar, unlike the SK-heading/attitude
  paths CLAUDE.md explicitly calls out.
- Added `ControlTask::TelemetrySnapshot` + a second mutex-protected
  one-way boundary (control task writes, `main.cpp`'s SensESP loop reads
  non-blockingly) alongside `SkHeadingIn`'s existing one -- exactly the
  "one-way boundary" CLAUDE.md's Architecture section describes. Publishes
  at ~15 Hz (`kTelemetryPublishPeriodMs`, SPEC's 10-20 Hz window): tare
  applied roll/pitch, fused yaw, rate-of-turn (all converted deg->rad at
  this SK boundary, matching CLAUDE.md's coding convention), FSM state,
  BNO health, GNSS validity/age.
- `main.cpp`: publishes `navigation.attitude` (`SKOutputAttitudeVector`)
  and `navigation.rateOfTurn` per SPEC Sec 7.1, plus
  `sensors.headingHold.{fusedHeadingDeg,fsmState,bnoOk,gnssValid,gnssAgeS}`
  for human-facing tuning telemetry (SPEC Sec 7) -- explicitly does NOT
  publish `e`/`psi_setpoint`/switching-command/duty, since none exist
  without `Switcher`.
- `pio test -e native`: 52/52 (unchanged). `pio run -e shesp32`: SUCCESS
  (RAM 16.2%, Flash 82.1%).
- **Verified on real hardware, including a real gotcha:** flashed and
  found the config UI unreachable -- WiFi kept retrying the hardcoded
  `<redacted-ssid>` from the boat, still not in range on this bench. User
  switched to their own phone hotspot and edited `main.cpp`'s
  `set_wifi_client()` call directly (their own edit, not mine) to a
  reachable network; after reflashing, the device **still** tried the old
  SSID -- turned out SensESP persists WiFi credentials to flash the same
  way as any other `PersistingObservableValue` (that's how the web UI can
  change them without a reflash), so the compiled default only applies on
  a truly blank flash. Erased flash (`pio run -t erase`, standard/
  reversible for a bench unit) and reflashed -- WiFi joined immediately.
  - With the web UI now reachable at `sensesp-heading-hold.local`: **all
    three new ConfigItems render correctly** (screenshot confirmed) with
    the right titles/descriptions/defaults.
  - **Live-tuning-without-reflash, confirmed conclusively:** set IMU Roll
    Tare to 5 via the web UI (no reflash), then added a temporary debug
    log line and reflashed *without* erasing -- log showed
    `raw_roll=44.97 tare_roll=5.00 published_roll=39.97`, i.e. the exact
    expected `raw - tare` math, using the value set purely through the
    web UI. This also incidentally reproves the persistence mechanism
    from the other direction (a value survives a full reflash, a strictly
    stronger test than "survives a reboot"). Removed the temporary debug
    log afterward; `pio test -e native` still 52/52, `pio run -e shesp32`
    still SUCCESS.
  - The roll tare value (5) and WiFi SSID (`FRHOC-mobile`, the user's
    hotspot) are now what's actually on the device/in source --
    left as-is since the user set them deliberately for this session.
    **Before real deployment:** reset the WiFi credentials back to the
    boat's real network (`<redacted-ssid>`/`<redacted-sk-server>`) and reset/measure
    the real IMU tare (5 was a test value, not a real spirit-level
    reading) -- flagging both here so they aren't mistaken for real
    configuration later.
  - Not verified this session: `navigation.attitude`/`navigation.
    rateOfTurn` actually arriving at a live Signal K server (no reachable
    SK instance on this network either) -- the web UI/ConfigItem/
    persistence half of Phase 9 is hardware-confirmed; the SK-delivery
    half still relies on the same SK-server-reachability gap as Phase 3.
- **Status:** Phase 9 done for what's currently wired (telemetry +
  attitude output + IMU tare live-tuning, hardware-verified end-to-end
  except final SK delivery). Remaining CLAUDE.md phases are the optional
  Phase 10 upgrades and finishing the deferred pieces already called out
  across Phases 3/6/7/8/9: Switcher (Item 2), real heading-quality gating
  (Item 3), UM982 rate/baseline (Item 4), thruster S2 duty limits
  (Item 5), and confirming SK delivery once a reachable server exists.
  Every phase in the original Phase 0-9 build order has now been started;
  what's left is measurement-gated finishing work, not new architecture.

## 2026-07-07 — On the boat network: live SK verified, rad/deg bug found + fixed
Session: (current session)

- **First session with the device on the real boat network** (user set
  `main.cpp` back to `<redacted-ssid>` / SK server `<redacted-sk-server>:3000`). From
  this dev machine both the device (`sensesp-heading-hold.local` ->
  192.168.0.42) and the SK server (<redacted-sk-server>) were reachable, so the
  SK-delivery gap deferred since Phase 3 could finally be verified for real
  by querying the SK REST API directly (no guessing, no proxy).
- **Outbound confirmed working immediately:** `navigation.attitude`,
  `navigation.rateOfTurn`, and the `sensors.headingHold.*` telemetry group
  were all arriving fresh at the server from `$source sensesp-heading-hold`,
  with `fsmState=HOLDING`, `gnssValid=true`, `gnssAgeS~0.9`. Phases 8/9
  delivery half is now hardware-verified end-to-end, not just host-tested.
- **Real bug caught by live data — inbound rad/deg unit mismatch:** the SK
  server served `navigation.headingTrue` = 1.502 rad (~86 deg) from
  `tkurki-um982`, but the device's `fusedHeadingDeg` had converged to
  ~1.50 -- i.e. it was tracking the *radian number*, not 86 deg. Root
  cause in `src/sk_heading_in.cpp`: the `SKValueListener<float>` callback
  stored the incoming value straight into `heading_deg` with no rad->deg
  conversion, even though (a) SK serves that path in radians per spec and
  (b) the entire pure control core works in degrees (CLAUDE.md convention:
  convert only at the SK boundary -- and this inbound WS callback *is* that
  boundary). Effect would have been the heading-hold steering to a wildly
  wrong heading. **Host tests could not catch this** -- they feed the
  filter degrees directly; the bug lives purely in the glue at the network
  boundary, which is exactly why it only surfaced with a live UM982 feed.
- **Fix:** convert `heading_rad * (180/pi)` and `WrapDeg180()` in the
  callback (added `#include "angle_math.h"`), with a comment explaining the
  SK-serves-radians boundary so it isn't "simplified" back out later. Pure
  core / host tests untouched (still 52/52 conceptually -- no lib change).
  `pio run -e shesp32`: SUCCESS (Flash 85.4%).
- **Windows toolchain gotcha (cost ~10 min):** first `pio ... -t upload`
  hung and eventually FAILED with `UnicodeEncodeError: 'charmap' codec
  can't encode` -- PlatformIO crashing while echoing an esptool progress
  line under the cp1252 console code page, NOT a flash problem. Fixed by
  running the upload with `PYTHONIOENCODING=utf-8 PYTHONUTF8=1` set;
  reflash then SUCCESS in 56 s. **Lesson: prefix pio upload/monitor with
  those env vars on this Windows box to avoid the charmap crash.**
- **Verified the fix on live data after reflash:** `fusedHeadingDeg` came
  up ~79 deg and converged over ~25 s to 86.5 deg, tracking the UM982's
  86.2 deg within a few tenths of a degree -- the complementary filter is
  genuinely fusing gyro + live GNSS correctly now, confirmed by watching it
  climb (not just a single lucky sample). Full inbound -> fuse -> outbound
  path verified end-to-end on the real boat for the first time.
- **Not committed this session (flagged for the user):** `main.cpp` now
  holds what may be the *real* boat WiFi password + OTA password
  (`<redacted-ssid>` / `<redacted-ota-password>`). The root repo pushes to GitHub, so
  committing that as-is would publish a live credential. Committed ONLY the
  `sk_heading_in.cpp` fix + this journal entry; left `main.cpp`'s
  credential change unstaged pending the user's decision (revert to
  placeholder, use the web config UI, or accept the exposure). Same concern
  first raised in the 2026-07-01 network-config note.
- **Credentials -- moved to a gitignored secrets.h (supersedes the placeholder
  approach above):** discovered the hard way that the placeholder approach was
  broken. After committing placeholder creds to `main.cpp` and reflashing, the
  device went OFFLINE -- serial showed `Connecting to wifi SSID your-wifi-ssid
  ... Reason: 201 - NO_AP_FOUND` in a loop, falling back to its 192.168.4.1
  setup AP. So the COMPILED-IN credentials are what the device actually uses on
  boot; the earlier assumption that "persisted flash creds win over compiled
  defaults across a reflash" was WRONG in practice (whatever Phase 9 observed,
  a reflash here used the compiled SSID). Fix: `include/secrets.h` (gitignored)
  holds the real `SECRET_WIFI_SSID`/`SECRET_WIFI_PASSWORD`/`SECRET_OTA_PASSWORD`;
  `include/secrets.example.h` (committed template) documents it; `main.cpp` does
  `#if __has_include("secrets.h")` -> real creds, else the placeholder template
  (still builds on a fresh clone, just won't auto-join). Real boat creds
  (`<redacted-ssid>` / `<redacted-ota-password>`, OTA same) now live ONLY in the gitignored file.
  **NOTE:** `<redacted-ota-password>` is already in committed history at d4fff46 (the old
  hotspot password) and that commit is on origin/heading-hold (GitHub) -- if
  that string is a real/reused password, change it at the router; scrubbing
  history is a separate, heavier step. (Confirmed real: the sibling
  `ref/SensESP_engines` project uses the same OTA password.)
- **Two SK-format fixes the user asked for, both live-verified:**
  1. `sensors.headingHold.fusedHeadingDeg` (degrees) -> `sensors.headingHold.
     fusedHeading` published in RADIANS; `gnssAgeS` -> `gnssAge` (seconds). SK
     uses SI units (rad, s) and doesn't bake units into path names. Publish
     `snap.yaw_rad` directly instead of `* RAD_TO_DEG`. (Stale `fusedHeadingDeg`/
     `gnssAgeS` entries still linger in the SK server's full model from before
     the rename -- harmless, they just stop updating.)
  2. **Send units as SK METADATA from the ESP.** Added units to the numeric
     SKOutputs via `SKOutputFloat(path, cfg, "rad"/"rad/s"/"s")` (the
     `SKOutputNumeric` 3rd-arg convenience ctor == `new SKMetadata(units)`, same
     as `ref/SensESP_engines` uses explicitly). At first the units did NOT arrive
     (`meta` absent on the server) though values did. Root cause: SensESP bundles
     ALL paths' metadata into the FIRST delta after each connect as a one-shot;
     our telemetry `onRepeat` `set()`s all 8 paths in one tick, so that first
     delta overflowed the default 1024-byte `SENSESP_SK_WS_BUFFER_SIZE`, was
     dropped, and metadata was NOT re-armed (values then flow in later, smaller
     deltas -- exactly the "values arrive, units missing" symptom). The staggered
     `ref/SensESP_engines` example never hits this because its sensor read-delays
     spread paths across separate small deltas. Fix: `-D SENSESP_SK_WS_BUFFER_SIZE
     =4096` in `platformio.ini`'s shesp32 build_flags. After reflash the server
     shows `fusedHeading` meta.units=`rad`, `gnssAge`=`s`, and `fusedHeading`
     converged to 1.4999 rad = 85.9 deg == the UM982's 85.9 deg (fusion locked on).
- **Windows toolchain note (recurring):** bare `python` sometimes resolves to
  the Microsoft Store stub ("Python was not found"); use the full pio python
  `C:/Users/karl-/.platformio/penv/Scripts/python.exe` for the SK curl-parsing
  one-liners. And keep prefixing pio upload/monitor with `PYTHONIOENCODING=utf-8
  PYTHONUTF8=1` (charmap crash otherwise). Several flashes this session ran
  10+ min or failed on host-side hiccups (encoding, competing build processes),
  not firmware problems -- the user ended up flashing from the IDE.
- **Status:** Inbound heading path now correct and live-verified -- a real
  functional gap (not just an unverified one) is closed. Deferred items
  unchanged: Switcher/direction pulsing (Item 2), real heading-quality
  gating (Item 3, still `valid=true` on any fresh value), UM982 rate/
  baseline (Item 4), thruster S2 duty (Item 5). The heading-quality gap is
  now more visible: with real data flowing, `gnssValid` is currently just
  "a value arrived," so a garbage UM982 fix would still be trusted.

## 2026-07-07 — Bug: yaw-rate spike rejection could LATCH (fused heading "rolls")
Session: (current session, continued)

- **User reported:** on rapid movement the yaw/fused heading is "left rolling
  values." Investigated -- it's a real correctness bug in `yaw_rate.cpp`, not
  tuning. `YawRate::Update` rejected any sample-to-sample yaw delta >
  `spike_threshold_deg_per_tick` (default 2 deg/tick = 200 deg/s at 100 Hz),
  and on reject it (a) HELD the last non-zero filtered rate and (b) did NOT
  update `prev_yaw_deg_`. Correct for an isolated 1-tick mag glitch -- but
  under real *sustained* fast rotation the yaw moves far past the frozen
  `prev_yaw_deg_`, so every following sample also exceeds the threshold and is
  rejected too. The estimator LATCHES into permanent rejection, keeps returning
  the stale high rate, and `HeadingFilter::Predict()` integrates it every 10 ms
  -> fused heading rolls forever. Matches the symptom exactly.
- **Fix:** make rejection non-latching. Added `max_consecutive_rejects` (default
  3) to `YawRate`; after that many rejects in a row, treat the sample as real
  motion: resync `prev_yaw_deg_` to it, clear the counter, and ZERO the held
  rate (don't fabricate a rate across an unknown gap -- the next accepted sample
  measures it afresh). A lone glitch never reaches the count (next good sample
  resets it), so the existing single-spike behavior is preserved. Bench-fast
  hand-waving now recovers in a few ticks instead of running away; on the boat
  real yaw rates (a few deg/s = ~0.05 deg/tick) never trip the threshold anyway,
  but a genuine BNO glitch that jumped-and-stayed would also no longer latch.
- Added regression test `test_sustained_fast_move_recovers_not_latched`
  (reproduces the runaway: build a rate, 20 ticks of +5 deg/tick, then hold
  still -> asserts not stuck rejecting and rate settles to ~0). `pio test -e
  native`: **53/53** (was 52 + 1 new; the old `test_spike_is_rejected_and_holds
  _rate` still passes). `pio run -e shesp32`: SUCCESS (RAM 16.4%, Flash 85.4%).
- **Host compiler note:** the WinLibs MinGW GCC installed back on 2026-07-01 was
  GONE this session (not on PATH, not in WinGet\Packages) -- `pio test -e native`
  couldn't build. Reinstalled via `winget install BrechtSanders.WinLibs.POSIX.
  UCRT`; it lands at `...\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_*\
  mingw64\bin` -- prefix that on PATH for native tests (winget's PATH entry
  doesn't reach this session's shells).
- **Not yet device-verified:** built but not flashed by me. Needs a flash + the
  user repeating the rapid-movement test to confirm the roll is gone on real
  hardware (host regression test covers the logic).

## 2026-07-07 — Seed fused heading from first GNSS fix (no crawl-from-0 on boot)
Session: (current session, continued)

- **User asked:** should `sensors.headingHold.fusedHeading` start at
  `navigation.headingTrue` instead of 0? Yes -- that's the ~25 s climb from 0
  we kept watching after each reboot. `HeadingFilter` is constructed with
  `initial_heading_deg = 0` and, even on the first-ever GNSS fix, only applied
  the `k_corr` gain (0 + k_corr*(true-0)), so it crawled up over many fixes.
- **Fix:** in `HeadingFilter::Correct`, on the first accepted fix
  (`!has_prev_gnss_`) SEED `fused_deg_` directly to `gnss.heading_deg` instead
  of the gain-limited nudge; every subsequent fix keeps the normal `k_corr`
  behavior + plausibility gate. The reported heading is now correct from the
  first fix (~1 s after boot) rather than climbing. Construction guess (0) only
  matters for the brief pre-first-fix window (gyro-only Predict).
- Updated `test_first_correction_bootstraps_regardless_of_jump_size` ->
  `test_first_correction_seeds_directly_to_the_fix` (now asserts fused == 90
  after a first fix of 90, not the old 9.0 nudge). All other heading_filter
  tests unaffected (they only check acceptance/relative movement; the coast-gap
  convergence test's first fix is at the true heading, so snap==nudge there).
  `pio test -e native`: **53/53**. `pio run -e shesp32`: SUCCESS.

## 2026-07-08 — Fix yaw-rate sign not reaching fusion; retune k_corr
Session: (current session)

- **External code review caught a real bug:** the Phase 9 `imu_yaw_rate_invert_`
  flag (MEASUREMENTS.md Item 6) was applied only in `PublishTelemetrySnapshot()`,
  to the local rate used for `rate_of_turn_rad_s`. The raw, uninverted rate
  still went into `heading_filter_.Predict()` in `Tick()`. If this mount ever
  needs the invert, `fused_deg()` -- already published live as
  `navigation.attitude` / `sensors.headingHold.fusedHeading`, not a
  deferred/inert value -- would integrate heading in the wrong direction
  between GNSS corrections. The Phase 9 journal entry's reasoning ("nothing
  downstream uses this yet") missed that the fused heading itself was already
  a live consumer, even though `Switcher`/direction control is not wired in.
- **Fix:** apply the sign in `Tick()`, to `r_dps_`, right where it's read from
  `yaw_rate_.Update()` -- before it reaches `Predict()`. `PublishTelemetrySnapshot()`
  no longer re-applies the sign (removed the now-redundant local `sign` var).
  The pure `HeadingFilter`/`YawRate` modules are untouched -- still agnostic to
  the flag; only the glue-code call site moved earlier.
- **Also retuned `HeadingFilter::Cfg::k_corr`** 0.02 -> 0.1 (effective time
  constant ~10 s -> ~2 s at the current 5 Hz SK subscribe throttle), per the
  same review: 10 s was overly gyro-biased for a bow-thruster loop. Left an
  explicit comment that this is provisional and should not be raised further
  until MEASUREMENTS.md Item 3 (GNSS quality/RTK gating -- still open;
  `SkHeadingIn` currently treats any received value as fresh, not
  quality-checked) lands, since a larger gain trusts each accepted fix more.
- **Self-review via 8 parallel Explore-agent finder angles + manual
  verification** (correctness, removed-behavior, cross-file, reuse,
  simplification, efficiency, altitude, CLAUDE.md conventions) caught two real
  defects in my own first pass at this fix, both corrected before landing:
  a stale `control_task.h` comment ("applied only at publish time") that no
  longer covered the yaw-sign flag once it moved earlier, and a wrong SK path
  name (`fusedHeadingDeg`, copied from this journal's own Phase 9 entry above
  -- that entry has the same typo, left as-is since it's a dated log, not
  living docs) instead of the real `sensors.headingHold.fusedHeading`.
- **Item 3 (GNSS quality/RTK gating), resolved -- and found a worse bug than
  expected:** asked the user for the real SK quality path; they pointed to
  their own plugin, `github.com/KEGustafsson/signalk-um982-plugin` (dev
  branch). Read `src/plugin.ts` directly: `uniheadingAParser` already judges
  quality at the source (UM982 manual Table 0-4/0-5) and sets
  `navigation.headingTrue` to JSON **null** whenever `solutionStatus !=
  'SOL_COMPUTED'` or `positionType == 'NONE'` -- so Item 3's quality gate
  already exists upstream; nothing new needed in firmware for the judgment
  itself. But reading SensESP's actual vendored
  `sensesp/signalk/signalk_value_listener.h`
  (`.pio/libdeps/shesp32/SensESP/...`) showed `SKValueListener<float>::
  parse_value` does `json["value"].as<float>()`, and ArduinoJson coerces a
  null `JsonVariant` to `0.0f` -- silently turning the plugin's explicit
  "don't trust this" into a **fake, VALID 0-degree heading**, worse than no
  gating at all (a real gate-you-forgot-to-add just under-protects; this
  actively manufactures bad data exactly when RTK quality drops, and on a
  first-ever fix would SEED the fused heading directly to 0).
- **Fix:** `sk_heading_in.cpp` now defines `HeadingTrueListener`, a small
  subclass of `sensesp::SKValueListener<float>` overriding `parse_value` to
  check `json["value"].isNull()` and drop the delta (no `emit()`) instead of
  coercing to 0. `SkHeadingIn::begin()` constructs this subclass instead of
  the base listener; `SkHeadingIn.h`'s `listener_` member type (base-class
  pointer) needed no change since `parse_value` is virtual.
- Considered and rejected building a separate tiered RTK-quality gate in
  firmware (e.g. requiring `positionType == NARROW_INT`) -- the plugin's own
  comment explains it deliberately blacklists only `NONE` rather than
  whitelisting fixed solutions, because float solutions (`NARROW_FLOAT` etc.)
  still carry a real heading. Duplicating a stricter policy in firmware would
  override that already-considered upstream judgment call, not fix a gap.
- `pio test -e native`: **53/53**. `pio run -e shesp32`: SUCCESS (RAM 16.2%,
  Flash 82.2%).

## 2026-07-08 — Fix arm/coast trust gate, RVC yaw range, timeout doc
Session: (current session, continued)

- **External review, High: fresh-but-rejected GNSS samples looked "current"
  to the safety FSM.** `control_task.cpp` computed `heading_age_ms` from
  `cached_gnss_.t_ms` (raw SK-delta arrival time) and ignored
  `heading_filter_.Correct()`'s return value. A stream of fresh deltas that
  `Correct()` keeps rejecting (stale or implausible per its plausibility
  gate) would keep `heading_age_ms` near zero, so both `heading_ok_to_arm`
  and the HOLDING coast-max disengage timer would never fire -- the FSM
  could arm/keep-holding indefinitely on pure gyro dead reckoning while
  every incoming correction was silently discarded, exactly the unbounded-
  drift scenario the coast-timeout exists to prevent.
- **Fix:** added `HeadingFilter::age_since_accepted_correction_ms(now_ms)`,
  built on the filter's own existing `has_prev_gnss_`/`prev_gnss_t_ms_`
  (already updated only on an ACCEPTED correction, never a rejected one --
  no new state needed). `control_task.cpp` now derives `heading_age_ms` and
  `in.heading_ok_to_arm` from this instead of raw arrival time. Also fixes a
  related Low finding: `sensors.headingHold.gnssValid` was passing through
  `SkHeadingIn`'s "have we EVER seen a real delta" latch (permanently true
  after the first heading, per its own header comment) as if it meant
  "is the heading good right now" -- now `snap.gnss_valid` is
  `heading_age_ms <= t_fresh_ms`, matching what an operator actually wants
  to read off the dashboard. New tests in `test_heading_filter.cpp`.
- **External review, Medium: RVC parser accepted any checksum-valid int16
  yaw**, including magnitudes past SPEC.md's documented ±18000 domain
  (0.01 deg/LSB -> ±180.00 deg, `SPEC.md` line 254). The checksum is one
  byte (256 values) -- a corrupted UART frame can collide and still parse.
  Fixed in `rvc_parse.cpp`: an out-of-domain yaw is now rejected the same
  way as a bad checksum (window slides by one byte; `last_valid_ms_`/
  `has_valid_frame_` untouched), so it can't refresh the BNO-health
  watchdog with nonsense data. New test `test_out_of_range_yaw_rejected`.
  Left pitch/roll unbounded -- SPEC.md only documents yaw's domain, and
  CLAUDE.md/AGENTS.md say don't guess hardware specs.
- **External review, Medium: `RvcParser::TimedOut`'s doc comment didn't
  match its (correct) behavior.** The header implied a `timeout_ms` grace
  period from construction before the first possible fault; the code (and
  `test_timed_out_before_any_frame`, which asserts `TimedOut(0, 200)` is
  true) actually fault immediately if no frame has EVER arrived -- zero
  grace period. Confirmed this is the intended fail-safe (BNO health is
  unproven until proven, and unproven should read as unhealthy, not
  healthy-by-default), so fixed the comment in `rvc_parse.h` to describe
  the real behavior instead of changing the behavior to match a comment
  that was simply wrong.
- **Not acted on (matches reviewer's own framing, not a formal finding):**
  `switcher.cpp`'s "skip across the off-band in one tick" behavior --
  correct for the current Schmitt design and inert until `Switcher` is
  wired to live outputs (blocked on MEASUREMENTS.md Item 2 regardless).
  Revisit when that wiring happens.
- `pio test -e native`: **57/57** (+4 new). `pio run -e shesp32`: SUCCESS
  (RAM 16.2%, Flash 82.2%).

## 2026-07-08 — Re-review after fix batch
Session: (current session, continued)

- Re-reviewed the current tree specifically against the earlier external-code-
  review findings (arm/coast trust gate, RVC yaw range, timeout contract, and
  GNSS-valid telemetry semantics), plus a broader pass over the surrounding
  safety-critical glue (`control_task`, `heading_filter`, `rvc_parse`,
  `output_map`, `main` telemetry publish).
- **High/medium findings from the previous review are now correctly addressed
  in code, not just in the journal note:**
  - `control_task.cpp` now derives the FSM arm/coast freshness gate from
    `HeadingFilter::age_since_accepted_correction_ms()` (accepted corrections
    only), so fresh-but-rejected GNSS deltas no longer keep HOLDING/arming
    alive indefinitely on gyro-only coast.
  - `TelemetrySnapshot.gnss_valid` now means "currently trusted/fresh enough"
    rather than SkHeadingIn's old permanent "have we ever seen a sample" latch.
  - `rvc_parse.cpp` now rejects checksum-valid but out-of-domain yaw values
    past SPEC's ±18000 raw range, so nonsense frames can't refresh the BNO-
    health watchdog.
  - `rvc_parse.h`'s timeout comment now matches the intended fail-safe behavior
    (immediate unhealthy before the first valid frame).
- **Low-severity issue, now fixed:** `lib/control_core/output_map.h`'s
  "unreachable" default branch (only reachable via a corrupted/out-of-range
  `Cmd` -- bad cast, memory corruption, uninitialized read; the switch above
  it is exhaustive over the enum's 3 named values) returned
  `{enable=true, port=false, stbd=false}` instead of fail-off. Changed to
  `{false, false, false}`: if `cmd` is ever in a state this file can't
  interpret, don't leave ENABLE asserted on the assumption "no direction
  bits" is enough. Added `test_out_of_range_cmd_fails_off_completely`
  (constructs the corrupted `Cmd` via `static_cast<Cmd>(99)`) so this path is
  no longer untested.
- Verification rerun on the current code after re-review:
  - `pio test -e native`: **58/58 PASSED** (+1 new)
  - `pio run -e shesp32`: **SUCCESS** (RAM 16.2%, Flash 82.2%)

## 2026-07-08 — Re-review after output-map regression test
Session: (current session, continued)

- Performed another fresh code review after the output-map fallback fix and its
  new regression test landed. Focused on whether the prior findings were fully
  fixed in code and whether the fixes introduced any new safety or integration
  regressions.
- Result of this pass: **no new findings**. The previously flagged issues
  (GNSS trust gate, RVC yaw-domain check, timeout contract wording, and the
  output-map fail-off default) all still look correct in the current tree.
- Verification rerun on the current code:
  - `pio test -e native`: **58/58 PASSED**
  - `pio run -e shesp32`: **SUCCESS** (RAM 16.2%, Flash 82.2%)
- Residual watch item only: `Switcher`'s "skip across the off-band in one
  tick" behavior still matches the current Schmitt design and remains inert
  until real direction control is wired in (blocked on MEASUREMENTS.md Item 2 /
  reversal-dwell measurement). Revisit when Switcher is connected to live
  outputs rather than before.

## 2026-07-08 — Backlog: autopilot-style commanded target heading
Session: (current session, continued)

- Discussed the on-engage semantics: `ψ_fused` (published `fusedHeading`) is a
  continuously-running state estimate and must NOT be reset/snapped to
  `navigation.headingTrue` on ENGAGE. What is captured on engage is the
  *setpoint* (snapshot of `fused_deg()` at `JustEnteredHolding`) — still a TODO
  in control_task.cpp, blocked on Switcher wiring / reversal-dwell measurement.
- Added SPEC §Phase 10 item **10d**: optional SK-driven commanded target
  heading (autopilot-style +/- arrow-button UI, e.g. signalk-autopilot).
  Subscribe control task to a settable target (e.g.
  `steering.autopilot.target.headingTrue`) over the same mutex-protected
  one-way boundary as SkHeadingIn; switcher holds to the commanded target
  instead of the on-engage snapshot; slew-rate-limit the setpoint; HARD
  INVARIANTS and manual authority unchanged; does not touch `ψ_fused`.
- Recorded as future work only — no code change this session.

## 2026-07-10 — Wire in the Switcher (Item 2 measured: reversal dead time)
Session: (current session)

- **The long-deferred blocker is resolved.** User measured the bow-thruster's
  **reversal dead time ≈ 1.75 s** on the real control box: turning port↔starboard
  the motor stays off for that gap (the box's built-in anti-reversal interlock);
  same-direction re-pulsing has **no** delay. Also confirmed the **output control
  FETs** are bench-tested and usable — **high = assert, low = off**, matching
  `config::kOutputActiveHigh = true`. Both are exactly the values MEASUREMENTS.md
  Item 2 / Item 1 were waiting on; recorded them in the results sheet.
- **Key realization before touching code:** `Switcher::CanLeaveOff` *already*
  implements the exact semantics the user described — the `reversal_dwell_s` gate
  only applies when `requested != last_thrust_dir_` (a reversal); same-direction
  re-engagement waits only `min_off`. So the measurement maps 1:1 onto
  `reversal_dwell_s`; no switching-logic change was needed, only the value + the
  glue wiring that had been stubbed since Phase 8.
- `config.h`: added `kReversalDwellS = 1.85f` (1.75 s measured + 0.10 s margin
  per Item 2's "+50–100 ms"). Documented the reversal-only semantics and that the
  rest of `SwitchCfg` keeps its SPEC Sec 6 in-class "typical start" defaults
  (same cfg-reuse convention as `HeadingFilter::Cfg`/`FsmCfg`). Cleared the Item 2
  TODO. Noted the FET polarity confirmation on `kOutputActiveHigh`.
- **`control_task` — Switcher wired in** (replacing the always-`kOff` stub at the
  "Item 2" marker): on `SafetyFsm::JustEnteredHolding()` capture the setpoint from
  `heading_filter_.fused_deg()` and `switcher_.Reset(now_ms)`; while `HOLDING`,
  `dir = switcher_.Update(wrap(setpoint − fused), r_dps_, now_ms)`. `r_dps_` is
  already sign-corrected (Item 6) so the lead term anticipates in the same frame
  as the fused heading. Outside HOLDING the direction is forced `kOff`, and
  `output_map`/`Outputs` still re-enforce invariants 1–2 (never both, inert unless
  armed) downstream — the Switcher only *decides*, it never bypasses the safe
  output path. Status log now includes `setpoint/e/dir/duty` for tuning.
- **New pure method `Switcher::Reset(now_ms)`** — clears the switching state
  machine (current cmd, last-thrust dir, min-on/off/dwell timers) to a clean OFF
  when a *new* hold begins, so stale timing from a hold that ended seconds ago
  can't leak into the next one (e.g. a reversal_dwell measured against an ancient
  thrust). **Duty accounting is deliberately preserved** across Reset: S2 thermal
  protection must not be defeatable by releasing/re-pressing engage. Added
  regression test `test_reset_clears_switching_state_but_keeps_duty` (drives STBD,
  builds duty, Reset, asserts cmd→OFF, duty preserved, and that the first post-reset
  engage is treated as a fresh ON gated only by min_off — not a reversal of the
  pre-reset direction).
- **`pio test -e native`: 59/59 PASSED** (was 58 + the new Reset test) — the pure
  Switcher change (Reset + the reversal semantics that back the wiring) is fully
  host-covered.
- **NOT built/flashed this session — and NOT hardware-verified.** This is a cloud
  session with no device attached, and the pioarduino platform toolchain host
  (github releases) is blocked by the environment's egress policy, so
  `pio run -e shesp32` can't fetch the platform here. The control-task glue is
  code-complete and reviewed but has only been compiled where the toolchain is
  reachable historically (the user's Windows box). **Before trusting this on a
  live thruster it MUST be built, flashed, and bench-verified on a scope/LEDs**
  per SPEC Phase 6/8: (1) commanded PORT/STBD pulses appear only in HOLDING;
  (2) a commanded reversal shows the ~1.85 s OFF dwell between directions and
  never both lines high; (3) same-direction re-pulsing has no dwell; (4) the whole
  thing with the **thruster motor power isolated** first (MEASUREMENTS.md safety
  preamble) before any in-water test.
- **Follow-up (flagged, not done):** the remaining `SwitchCfg` tuning knobs
  (on_thr/off_thr/Td/min_on/min_off/duty) are still compile-time — sea-trial
  tuning (SPEC §8 playbook) wants them live in the web UI, which needs the pure
  Switcher's `const cfg_` restructured to read live values (the Phase 9 concern).
  `reversal_dwell` (the safety-critical one) is now fixed at the measured value,
  which is the right default regardless.

## 2026-07-10 — Consolidate all status indication onto one LED (GPIO2)
Session: (current session, continued)

- **User request:** use the single onboard LED on GPIO2 ("port2") for all
  status, with the pattern: **error = fast blink, normal = slow blink, in use
  = solid** (or similar).
- Before this, GPIO2 only did an unconditional 500 ms liveness blink, and
  `kLedHoldingPin`(25)/`kLedFaultPin`(26) were defined but **never driven** by
  anything -- so there was really only ever one working LED anyway.
- `config.h`: replaced the three LED pin defines with a single
  `kLedStatusPin = 2`; documented the pattern and that 25/26 are unused now.
- `main.cpp`: replaced the liveness blink with a state-driven single-LED
  indicator on an `onRepeat(50, ...)`, reading the control task's telemetry
  snapshot (`snap.state`) through the existing non-blocking `latestTelemetry()`
  boundary -- LED is status/periphery, never a safety output, so it lives on
  the SensESP loop, not the control task. Mapping:
  - `kFault` -> `(millis()/100)%2` ~5 Hz **fast blink**,
  - `kHolding` -> **solid on** ("in use" / actively holding),
  - default (`kDisarmed`/`kArmedIdle`) -> `(millis()/500)%2` ~1 Hz **slow
    blink** ("powered & normal").
  On a contended mutex tick it reuses the last known state; before the first
  snapshot exists it defaults to the slow normal blink.
- Also fixed a comment in `main.cpp`'s telemetry block left stale by the
  previous commit ("Switcher isn't wired in yet") -- the Switcher IS wired in
  now; the switching-command/duty signals are simply not in the SK snapshot
  yet (they're in the status log), which is a separate telemetry follow-up.
- `pio test -e native`: **59/59** (pure core untouched -- change is only in
  `src/main.cpp` + `config.h`). **Not built/flashed/hardware-verified here**
  (no device; ESP32 platform toolchain still egress-blocked in this cloud
  session) -- needs a build + flash on the user's machine to confirm the three
  blink patterns actually appear on the real LED as the FSM changes state.

## 2026-07-10 — Finish Phase 9 follow-ups: live Switcher tuning + full SK telemetry
Session: (current session, on the user's own Windows machine -- `pio` and the
shesp32 toolchain both reachable here, unlike the prior two cloud sessions)

- **Scope:** close out the two concretely-flagged, code-only remaining items
  from Phases 8/9 so every phase through 9 is code-complete (Phase 10
  excluded per SPEC, still backlog-only): (1) live web-UI tuning for the
  non-safety `SwitchCfg` knobs, (2) publishing `e`/setpoint/switching-
  command/duty to SK (SPEC Sec 7), which had only ever reached the serial
  status log.
- **`lib/control_core/switcher.{h,cpp}`:** added `Switcher::SetTunables(on_thr,
  off_thr, lead_time, min_on, min_off, duty_warn, duty_max)`. Deliberately
  does NOT touch `reversal_dwell_s` or `duty_window_s` -- `reversal_dwell_s`
  is the measured MEASUREMENTS.md Item 2 safety value (CLAUDE.md invariant 7)
  and must stay fixed regardless of what a web-UI value says; `duty_window_s`
  defines what the already-accumulated `duty_ema_` means, so changing it live
  would silently reinterpret history. `off_thr_deg` is clamped below
  `on_thr_deg` (falls back to `on_thr_deg * 0.5` if a bad value would violate
  the Schmitt deadband) and `duty_warn` is clamped below `duty_max` the same
  way -- a bad UI value degrades to a safe default rather than breaking the
  no-chatter/duty-widening structural guarantees. `cfg_` changed from `const`
  to a plain member so it can be updated after construction; existing
  switching state (current_cmd_, timers, duty_ema_) is untouched by a
  tunables update. New tests: `test_set_tunables_applies_new_thresholds`,
  `test_set_tunables_clamps_off_thr_below_on_thr`,
  `test_set_tunables_does_not_weaken_reversal_dwell`.
- **`include/config.h`:** added defaults + `ConfigItem` flash paths for the
  seven live knobs (`kOnThrDegDefault` etc.), matching `SwitchCfg`'s own
  in-class SPEC Sec 6 "typical start" values so the live defaults equal the
  old compile-time ones -- flashing this doesn't change current behavior
  until someone actually moves a slider.
- **`src/control_task.{h,cpp}`:** seven new `PersistingObservableValue<float>`
  + `ConfigItem` registrations in `begin()` (same pattern as the Phase 9 IMU
  tare values). `Tick()` now calls `switcher_.SetTunables(...)` from the live
  values every tick, but only inside the `HOLDING` branch (matches the
  existing rule that the Switcher only runs while `HOLDING` -- tunables
  outside a hold are moot). Added `setpoint_rad`/`error_rad`/`switch_cmd`/
  `duty` to `TelemetrySnapshot`, computed in `PublishTelemetrySnapshot()` from
  the same `setpoint_deg_`/`heading_filter_.fused_deg()`/`last_dir_`/
  `switcher_.duty()` the status log already used. Added `ControlTask::
  CmdName()` (mirrors the existing `StateName()`) and refactored
  `LogStatusIfDue()`'s inline port/stbd/off string logic to use it instead of
  duplicating it.
- **`src/main.cpp`:** publishes the four new fields --
  `sensors.headingHold.setpoint`/`.error` (rad), `.switchCmd` (string),
  `.duty` (SK unit `"ratio"`, the SK convention for a dimensionless 0-1
  fraction, e.g. state-of-charge-style paths) -- removed the now-stale
  comment saying these weren't published yet.
- **Verification (host + real toolchain, still no device attached this
  session):**
  - `pio test -e native`: **62/62 PASSED** (59 + 3 new `SetTunables` tests).
  - `pio run -e shesp32`: **SUCCESS** (RAM 16.2%, Flash 82.5%, up slightly
    from 82.2%) -- unlike the two prior cloud sessions, this machine's
    toolchain (`pioarduino` release download) was reachable, so this is a
    real confirmed compile against the actual board target, not just
    `env:native`.
- **Not hardware-verified:** no device connected this session (`pio device
  list` returned empty). The new ConfigItems' web-UI rendering, the live-
  tuning-without-reflash behavior (already proven for IMU tare in the
  original Phase 9 session, same mechanism), and the four new SK telemetry
  fields arriving at a live SK server all still need a flash + a reachable SK
  server to confirm -- same gap Phase 6/8's Switcher wiring is already
  waiting on.
- **Status: every phase in the SPEC Sec 8 build order (0-9) is now
  code-complete and host-tested** (Phase 10 explicitly out of scope per this
  session's instructions, backlog only -- Sec 10d recorded above, 10a/10b/10c
  not started). What remains before this can run a real thruster is entirely
  hardware-gated, not code-gated:
  1. **Build + flash + bench-verify the Switcher** (SPEC Phase 6/8
     done-when, flagged since the 2026-07-10 wiring session above): PORT/STBD
     only in HOLDING, the ~1.85 s reversal dwell actually appears on a
     scope/LEDs, never both lines high, thruster motor power isolated first.
  2. **Confirm the new ConfigItems + telemetry fields on the real web UI /
     live SK server** (same reachability gap noted throughout Phases 3/8/9).
  3. **Hardware-only measurement items still open** (not blockers, tune later
     per MEASUREMENTS.md): Item 4 (UM982 update rate/antenna baseline), Item 5
     (thruster S2 rating -- `duty_warn`/`duty_max` are live-tunable now but
     still default to SPEC's generic 50%/70%, not this thruster's real
     rating), Item 6 (real IMU tare -- mechanism proven, boat value not yet
     measured).
  4. **Deadman input** (`in.deadman_ok = true` hardcoded in
     `control_task.cpp`) -- no physical deadman switch exists on this build
     yet; wiring it in is a one-line `digitalRead()` change once the switch
     is installed, not a code gap today.
  5. Full sea-trial per SPEC Sec 10 checklist, only after 1-2 above pass.

## 2026-07-12 — Safety review follow-up (7 review findings)
Session: claude/bow-thruster-safety-review-hod9dm

A code review of the bow-thruster controller raised 3 blocking safety issues
and 4 correctness/hygiene items. All 7 are addressed in this session.

- **(1, blocking) Independent output fail-off path:** the control task now
  refreshes a heartbeat (`std::atomic<uint32_t>`) after every completed tick;
  a periodic `esp_timer` callback -- dispatched from the esp_timer service
  task on **core 0**, entirely outside the control task's scheduling -- calls
  `Outputs::allOff()` whenever the heartbeat is older than
  `config::kOutputFailoffTimeoutMs` (100 ms = 10 control periods, checked
  every 25 ms). A control task that stalls or crashes after asserting a
  direction can no longer leave the GPIOs latched until reboot; the watchdog
  is started *before* the control task in `begin()` so a task that never
  starts is also caught. Repeat-fires until the heartbeat resumes; ESP_LOGE
  rate-limited to 1/s. **Limit (documented in config.h and here): this is
  still firmware.** A true hardware fail-off (external enable-heartbeat
  circuit that drops ENABLE when the MCU stops toggling a safety line) is
  strongly recommended for the user-provided physical interface (SPEC §3.4)
  -- firmware cannot protect against its own total lockup with interrupts
  disabled.
- **(2, blocking) ENGAGE debounce:** new pure `lib/control_core/debounce.h`
  (`Debounce`), two-sided as the review asked: 50 ms stable assert AND 50 ms
  stable release (`config::kEngageAssertStableMs`/`kEngageReleaseStableMs`)
  before the FSM sees an edge, any bounce restarting the clock. The raw opto
  read no longer reaches `SafetyFsm` (whose `FsmInputs::engage` contract was
  always "debounced by caller"). 7 new tests in `test_debounce/`, plus
  integration tests proving chatter can't arm and a release glitch while
  HOLDING neither disarms nor re-captures the setpoint.
- **(3, blocking) `Switcher::SetTunables` boundary validation:** every live
  value is now treated as untrusted: NaN/inf **rejected** (previous value
  kept), finite values clamped into public bounds (`kOnThrMinDeg`=0.1 /
  `kOnThrMaxDeg`=45, `kOffThrMinDeg`=0.05, lead 0-10 s, min_on/min_off 0-30 s
  (`kLeadTimeMaxS`/`kMinOnOffMaxS`), duty fractions 0-1), and the off<on +
  warn<max relations re-enforced on the *sanitized* values so NaN can't dodge
  a comparison. 5 new `test_switcher` cases: non-finite rejection, negative
  on_thr (would have caused spurious thrust at zero error!), out-of-range
  clamp, huge min_on bounded (thruster can't be welded on), duty_warn>=max
  clamp (would have gutted the S2 inhibit latch).
- **(6) Host-testable integration layer:** the entire per-tick decision
  pipeline moved out of `ControlTask::Tick()` into pure
  `lib/control_core/control_step.{h,cpp}` (`ControlStep`): yaw rate ->
  heading fusion -> engage debounce -> safety FSM -> setpoint capture ->
  switcher. `ControlTask` is now hardware glue only (sample UART/GPIO/config
  -> `Step()` -> `Outputs::apply()` -> telemetry). New
  `test_control_step/` (8 scenarios) simulates a boat+sensors around the
  real wired pipeline and asserts the review's exact list: setpoint captured
  exactly once on first-valid-heading+engage; GNSS loss coasts then
  disengages cleanly at coast_max; BNO timeout drops outputs the same tick
  while thrusting; held engage across fault/coast recovery does NOT resume
  hold (fresh press required, re-captures setpoint); engage bounce never
  arms; release while thrusting disarms. **Every simulated tick** also passes
  through an output-safety invariant check (dir only while HOLDING, nothing
  unless armed, `ComputeOutputLevels` never both directions) -- so output
  safety is verified across every state transition of every scenario, not
  just at final states.
- **(7) Atomic output API:** `Outputs::setEnabled()`+`setDirection()`
  replaced by single `Outputs::apply(armed, dir)` -- the armed/direction
  invariant now lands in one call with no intermediate state between two
  writes for future edits to weaken. `allOff()` kept (also the watchdog's
  entry point; its header now documents the only-when-owner-is-dead
  cross-task contract).
- **(4) `setup()` returns normally:** the `while (true) { loop(); }` at the
  end of `setup()` is gone; long-lived shared_ptrs moved to a static
  `retained_objects` vector so the Arduino framework's own setup->loop flow
  (and its task/watchdog expectations) stays intact.
- **(5, security) `include/secrets.h` untracked:** was actually still
  committed (the .gitignore did NOT cover it, contrary to the review's
  reading -- verified with `git ls-files`). Now `git rm --cached`ed and
  gitignored. **The WiFi + OTA credentials in it are in git history
  (commits 98477d8/65fea8e) and must be rotated by the user** -- removing
  the file forward does not scrub history.
- **Verification:** `pio test -e native`: **82/82 PASSED** (was 62; +7
  debounce, +8 control_step integration, +5 SetTunables validation).
  `pio run -e shesp32` could NOT be verified this session: this cloud
  environment's egress policy blocks the pioarduino platform download
  (HTTP 403 from the proxy, not a code error). The `src/` changes
  (esp_timer watchdog, `apply()`, retained-objects vector) compile only
  against the device toolchain, so **an on-target build + the Phase 6/8
  bench checks (watchdog trip test: wedge the control task, scope ENABLE
  drop within ~125 ms) are still required before trusting this on the
  boat.**

### Follow-up (same day): secrets.h kept tracked per user decision
User reviewed item 5 and chose to keep `include/secrets.h` committed as
before -- the repo is private and they accept the credentials living in it.
Reverted the untracking + .gitignore entry. Noted for the record: "private"
is an access-control setting, not encryption -- anyone granted repo access
(or a future visibility change) sees the WiFi/OTA passwords.

## 2026-07-25 — Android: first real build of :app (toolchain + four build blockers)
Session: claude/android-plugin-ui-parity-otk4hc

The Android station had never been built. `:core:test` was green because it
needs nothing but a JDK, but `:app` had only ever been *written* -- no machine
in the project had an Android SDK, so `assembleDebug` had never run and four
independent blockers had accumulated behind that gate. All four are the kind
that only a real build finds.

Toolchain installed on the owner's machine: Temurin JDK 21.0.11, Android SDK
at `C:\Android\Sdk` (cmdline-tools 16.0, platform-35, build-tools 35.0.0;
AGP additionally pulled build-tools 34.0.0 itself). `android/local.properties`
carries `sdk.dir` and stays gitignored.

- **(1) Gradle plugin classloading -- two failures, one root cause.** The root
  build file declared only `alias(libs.plugins.kotlin.jvm) apply false`.
  `kotlin.jvm` (:core) and `kotlin.android` (:app) ship in the **same**
  artifact, `org.jetbrains.kotlin:kotlin-gradle-plugin`, so that one line put
  the artifact on a classloader :app inherits, and :app's own request for
  `kotlin.android` failed with *"already on the classpath with an unknown
  version"*. Declaring both Kotlin plugins at the root got past resolution and
  then died differently: the Kotlin plugin sat in the parent classloader while
  AGP sat in :app's child one, and a parent cannot see its child --
  `NoClassDefFoundError: com/android/build/gradle/api/BaseVariant`. Declaring
  nothing at the root and letting each module carry its own versioned plugins
  *does* build, but the Kotlin plugin warns that it "was loaded multiple times
  in different subprojects, which is not supported and may break the build" --
  not something to ship under SAFETY.md. **Resolution:** every plugin,
  including AGP, is declared `apply false` at the root. All three dead ends are
  written into `android/build.gradle.kts` so the next person does not re-walk
  them.
- **Cost of (1), and why it is acceptable.** AGP is now *resolved* by
  `./gradlew :core:test` even with no SDK present, which the previous layout
  deliberately avoided. The guarantee that actually matters is unchanged and
  was **re-verified by moving `local.properties` aside and running with
  `ANDROID_HOME`/`ANDROID_SDK_ROOT` unset: 125/125 core tests pass**.
  settings.gradle.kts still keeps :app out of that build, and an unapplied
  plugin never runs its SDK check. What was lost is a download, not an outcome.
- **(2) `android.useAndroidX=true` was missing** from `gradle.properties`.
  Every UI dependency :app declares is AndroidX, so AGP refused the build at
  `:app:checkDebugAarMetadata`. Added.
- **(3) Four XML comments contained `--`,** which is illegal inside an XML
  comment; `:app:mergeDebugResources` rejected the files outright. This is a
  direct collision between the project's prose style (`--` as an em dash, used
  throughout every other file) and XML. Three were references to the plugin's
  CSS custom properties (`surface`, `forward`, `reverse` in `colors.xml` and
  `ic_launcher_foreground.xml`), now named without their leading dashes with a
  note saying why; one was an em dash in `network_security_config.xml`, now a
  semicolon. **Anything written into `android/**/*.xml` has to avoid `--`.**
- **Verification:** `:app:assembleDebug` **BUILD SUCCESSFUL** --
  `app/build/outputs/apk/debug/app-debug.apk`, 11.16 MB. `:core:test`
  **125/125 passed**, both with and without the SDK visible.

**Not addressed, and worth knowing:**
- `:app:testDebugUnitTest` is **NO-SOURCE**. `app/src/` has only `main` -- no
  test source set at all -- even though `app/build.gradle.kts` declares
  `testImplementation(libs.okhttp.mockwebserver)` for tests that do not exist.
  Consistent with AGENTS.md (decisions live in the tested `:core`), but the
  unused dependency reads as an intention that was never finished.
- Two deprecation warnings in `MdnsDiscovery.kt` (`NsdManager.resolveService`
  at :73, `NsdServiceInfo.host` at :131). Both compile and run on the API
  levels this app targets; neither was touched.
- **The APK has never been installed or run on a phone.** It compiles and
  packages -- nothing more. TX/RX/HH hardware status is unchanged by this
  session.

### Follow-up (same day): cleartext blocked — the network security config never matched anything
First contact with a live server. Pressing **Request access** failed with
*"CLEARTEXT communication to 192.168.0.100 not permitted by network security
policy"* — despite `res/xml/network_security_config.xml` existing, being
correctly referenced from the manifest, and containing eighteen entries whose
whole purpose was to permit exactly that.

**The entries could never match.** Android matches a `<domain>` by **suffix**:
the hostname must equal it, or end with `"." + domain`. That is right for DNS
names — `example.com` covering `api.example.com` — and useless for IP
addresses, whose network part is a **prefix**. `192.168.0.100` does not end
with `.192.168`. The file's own comment ("Domain rules match by suffix, so the
leading octets cover the whole range") had the direction inverted, and that one
inverted premise generated all eighteen dead entries. Only the exact literals
(`localhost`, `127.0.0.1`, `10.0.2.2`) and `local` — a genuine DNS suffix —
ever did anything.

**It cannot be fixed in that file.** Android's network security config has no
CIDR or range syntax at all, and the server's address is not known at build
time. The intent was not expressible where it was written.

- **The rule moved into `:core`.** New `PrivateAddress.kt`: `isPrivateHost()`
  covers RFC1918, 127/8, 169.254/16, `.local`, single-label names, and IPv6
  `::1` / `fc00::/7` / `fe80::/10`. `ServerAddress.isCleartextSafe` is
  `useTls || isPrivateHost(host)`. **Unrecognised forms return false**, so an
  address the parser does not understand is refused rather than trusted — an
  IPv4-mapped `::ffff:…` is rejected, and the remedy is `https://`, which is
  allowed unconditionally. The XML is now a bare permissive `base-config` whose
  comment records why the restriction is no longer there.
- **Enforced at `useServer()`,** which is the single point both a typed address
  and a tapped discovery result pass through — so a discovered service
  advertising a public hostname is refused on the same terms as a typo. Nothing
  is stored and no socket opens, so the token cannot leave. `SetupScreen` also
  greys out Connect and explains why while the operator is still typing; that
  is the kinder telling, not the gate.
- **A test of mine was wrong, not the code.** `assertFalse(isPrivateHost(
  "notlocal"))` failed: a single-label name has no TLD, cannot be delegated in
  public DNS, and is private by the same rule that makes `localhost` and `boat`
  work. Corrected to `evil.notlocal` (which genuinely tests the `.local` suffix
  precision) and the single-label decision given its own named test so it does
  not read as a hole later.
- **Verification:** `:core:test` **137/137** (was 125; +12).
  `:app:assembleDebug` **BUILD SUCCESSFUL**, 11.23 MB.

**Still true:** the APK has never been installed or run on a phone, and nothing
past the access request has been exercised against a live server. What this
session proves about the network path is that the request now leaves the
device — not that the flow completes.

### Follow-up (same day): HTTP 401 on the intent POST — the readwrite route, confirmed on a live server
With cleartext working, the access request completed and the app reached the
control panel — then every intent POST came back **HTTP 401**, surfaced as
"Signal K refused this device (HTTP 401)".

**Not an app bug.** This is exactly the failure ARCHITECTURE.md §10 predicts:
in `signalk-server`, a plugin route registered *directly* on the plugin router
inherits the `/plugins` gate's **admin-only** default, so a device holding an
access-request token is rejected while a browser opened from the admin UI
succeeds on its session cookie. The fix for it — `router.access('readwrite')`,
commit **0b59671** — existed only on this branch and **had never been
deployed**; the server was still running the older plugin build.

- **Confirmed by deploying it.** The owner built and installed the updated
  plugin on the boat server and the intent POST is now accepted with an
  ordinary **readwrite** token. That closes §10's open commissioning question
  for this server: `router.access()` is present on **signalk-server 2.30.0**,
  so the feature-detected fallback is NOT in play and no station needs an
  admin-level access request. **This is the first time the token-authenticated
  command path has worked end to end anywhere.** What is proven is that the
  POST is accepted — commanding behaviour past that point is still unexercised.
- **`main` still carries the admin-only registration.** Commit 0b59671 is on
  `claude/android-plugin-ui-parity-otk4hc` and nowhere else, so a plugin
  deployed from `main` would reintroduce the 401 for every token station —
  including TX, RX and HH. Merging it is a prerequisite for the firmware
  stations, not just the phone.
- **Plugin re-verified before deploying:** `npm test` **203/203**,
  `npm run build` clean.

**Gap found while diagnosing, NOT yet fixed: the app cannot recover from a
token that stops working.** `forgetToken()` exists in `StationViewModel` and
**nothing calls it** — no button, no automatic trigger. A 401 on the intent
POST only sets `authError` (a red line in the status panel) and the app stays
in `Stage.Ready`, re-POSTing the dead token every 250 ms forever; the stream's
401 path bumps the auth scheme once and then just reconnects on backoff;
`tokenExpired()` compares only against the expiry the server *stated*, which a
server-side revocation never changes. The `Stage.Ready` screen offers no route
back to setup either, so recovery today means clearing app data. A station that
has lost its authority should stop presenting itself as armed and go back to
asking for it.

### Follow-up (same day): bigger drive buttons, collapsed telemetry — both stations
Two UI changes asked for on the Android station and then mirrored in the
plugin. They landed differently in each, which is the interesting part.

- **FWD/REV at least as large as the thruster's PORT/STBD.** On **Android** the
  drives really were the smaller pair: the thruster row had a hard-coded
  `88.dp` while the drive buttons carried only `weight(1f)` and took whatever
  the layout had left. Both now read one `ContactButtonMinHeight` constant —
  the thruster as its exact height, the drives as a floor they grow past. In
  the **plugin** they were already the larger pair (96 px against the
  thruster's 76 px); the numbers moved into `--drive-button-min-h` /
  `--thruster-button-min-h` custom properties so the relationship is stated
  once instead of being a coincidence of two literals, and the floor went to
  112 px.
- **Telemetry collapsed by default.** Android: `rememberSaveable` toggle, the
  whole summary row is the tap target, and the expanded block is
  `heightIn(max = 200.dp)` + `verticalScroll` so opening it can never push the
  drive buttons off the bottom. Plugin: a native `<details>`, which collapses
  by default, is keyboard-operable and screen-reader-announced with no state of
  ours. `.status-panel` became a flex column with the auto-fit lamp grid moved
  into `__summary` / `__lamps`, and it now hugs its content so the space it
  gives back goes to the drives row's `1fr`.
- **The two stations disagree on the fault line, on purpose.** Android's
  collapsed summary names any unit that has gone quiet; the plugin's does not.
  The plugin's kill switch already says "tap to arm — drive unit not
  responding" and is never collapsed, so a second copy is noise — **two
  App-level tests caught the duplicate the moment it was added**, which is
  exactly what they are for. Android's kill switch has a real gap the plugin's
  does not: when one unit is missing but the other still allows arming,
  `canArm` is true and its DISARMED branch says only "tap to arm", naming
  nothing. Until that is fixed, the Android summary line is the only place that
  state is stated.
- **Verification:** `:core:test` **137/137**, `:app:assembleDebug` OK (11.24
  MB). Plugin `npm test` **207/207** (was 203; +4 pinning what is visible
  without a tap), `npm run build` clean.

**Not verified:** neither UI has been looked at. No device is connected, the
project has no screenshot tests, and the plugin build was not opened in a
browser — the layout reasoning is sound but the proportions are unconfirmed.

### Follow-up (same day): plugin UI made proportional, referenced to a Galaxy S25
"A bit too long" on the owner's phone. Fixed by measurement rather than by
adjusting numbers until it looked right — the Playwright harness that generates
the README screenshots was reused to drive the **real built app** at a set of
viewports and report `document.scrollHeight` against `window.innerHeight`.
Script kept out of the repo (scratchpad); Playwright stays a docs-only,
`--no-save` install and **`package-lock.json` was restored** after it, since
sk-plugin/README.md is explicit that it must not become a dependency.

Reference device, confirmed from the spec sheets rather than memory:
**Samsung Galaxy S25 — 360 x 780 CSS px, DPR 3** (1080 x 2340 physical, 19.5:9,
6.2").

What the measurement found, none of which was obvious from reading the CSS:

- **The status panel was 140px collapsed, not the ~98 estimated.** Its lamp grid
  used `minmax(108px, 1fr)`, and at 360px wide the three summary lamps had only
  320px to sit in — so two fitted per row and the third wrapped, costing a whole
  second row. An 88px track puts all three on one row down to a 320px screen and
  took the panel to 92px.
- **The previous session's `--drive-button-min-h: 112px` was the actual cause of
  the overflow.** The drives row is the layout's `1fr` and the buttons carry
  `flex: 1`, so their real height is leftover space — 144px on an S25. The floor
  only bites when the viewport is too short, and a floor set near the
  comfortable size does not mean "bigger buttons", it means "the page scrolls".
  Back to 88px, a touch floor that never governs in practice.
- **Capping the panel did not stop it overflowing.** With `max-height` but no
  `overflow: hidden`, the lamp grid kept its full content height and simply
  painted outside the panel, growing the document by 38px — the same scrollbar,
  by a longer route. `overflow-y: auto` alone does not scroll an element whose
  height is not already constrained; `flex: 1 1 auto` + `min-height: 0` is what
  actually lets it shrink.

The layout is now **proportional rather than patched**. `--u` is "one reference
pixel", `clamp(0.72px, calc(100dvh / 780), 1.45px)`, and every vertical
dimension is written `calc(N * var(--u))` — meaning "N px at the S25
reference", scaled. `dvh`, not `vh`, because a phone's toolbar shows and hides
and `vh` is frozen at the largest viewport. Root font-size rides the same scale
(`clamp(13px, calc(16 * var(--u)), 19px)`) so the type stays in proportion with
the boxes; almost every font-size in the file was already in `rem`, so that one
line carried the lot.

**Verified — zero overflow at every size, collapsed AND expanded:**

| viewport | FWD/REV | PORT/STBD | drives share | collapsed | expanded |
|---|---|---|---|---|---|
| 320x568 | 97 | 55 | 34% | fits | fits |
| 360x600 | 106 | 58 | 34% | fits | fits |
| 360x660 | 121 | 64 | 46% | fits | fits |
| 360x720 | 133 | 70 | 46% | fits | fits |
| **360x780 (S25)** | **144** | **76** | **46%** | **fits** | **fits** |
| 384x824 | 153 | 80 | 46% | fits | fits |
| 412x915 | 172 | 89 | 46% | fits | fits |
| 1280x800 | 202 | 78 | 59% | fits | fits |

`npm test` **207/207**, `npm run build` clean.

**Not done:** the Android station still uses fixed `dp` and was not given the
same treatment — it was not asked for, and Compose has no direct `--u`
equivalent, so it would be a different mechanism rather than the same change.
Neither UI has been looked at by a human; the numbers above are measured
geometry, not a judgement that it looks right.

### Follow-up (same day): the capped telemetry panel was the wrong call — reverted
Owner report: with the panel open, "I see only partially those" details, on both
phone and laptop. Correct, and it was a design mistake made earlier the same
session, not a layout accident.

The previous entry's cap — `max-height: 23dvh` on `.status-panel` plus an
internally scrolling lamp grid — kept the page exactly one screen tall by
**hiding the thing the operator had just asked to see**. A phone renders no
visible scrollbar for an inner region, so "Detail" opened onto content that was
simply cut off. Keeping a promise about page height by breaking the feature is
the wrong trade.

There was also a plain bug underneath the laptop half of the report: the
desktop override `.status-panel { max-height: none }` sat in the
`@media (min-width: 860px)` block near the TOP of the file, while the capping
rule was ~250 lines further down. Same specificity, so **source order won and
the cap applied on desktop too** — the sidebar was being clipped despite an
override written specifically to stop that.

Now: no cap, no inner scroll. Opening the detail is a deliberate act by someone
who has stopped manoeuvring to read, so it gets the room. The drives row is the
`1fr` and yields first, and — via
`.app:has(.status-panel__details[open])` — the drive buttons' touch floor
relaxes from `64 * --u` to `max(48px, ...)` **only while the detail is open**,
reverting the moment it closes. 48px is the platform minimum touch target, so
the drives stay pressable throughout; where the floor wins, the page scrolls
instead, which is at least a scrollbar the operator can see. `:has()` is
unsupported on nothing current, and where it is missing the rule is skipped and
the page is merely taller.

The detail's seven lamps are also packed tighter than the three summary lamps
(3px vs 5px padding) — they are read at rest, not at a glance.

**Verified — every one of the seven detail lamps fully on screen, measured by
bounding rect against the viewport, not by eye:**

| viewport | detail open | all 7 lamps visible |
|---|---|---|
| 360x660 | fits | yes |
| 360x720 | fits | yes |
| **360x780 (S25)** | **fits** | **yes** |
| 384x824 | fits | yes |
| 412x915 | fits | yes |
| 1280x800 | fits | yes |
| 360x600 | +13px scroll | yes |
| 320x568 | +41px scroll | no — last lamp below the fold |

320x568 is a legacy 4-inch screen far outside the S25 reference; there the page
scrolls and the last lamp is reachable by scrolling. `npm test` **207/207**.

**The Android station has the SAME defect, unfixed.** Its `StatusPanel` expanded
block carries `heightIn(max = 200.dp) + verticalScroll` — the identical "keep
the page short by hiding the detail" decision, made in the same session. It was
not touched here because the fix is not symmetric: Compose has no `:has()`, so
relaxing the drive floor while the panel is open needs the expanded state
hoisted out of `StatusPanel` to where the drive controls can see it, and
removing the bound without that would clip rather than scroll.

### Follow-up (same day): the Android launcher mark reused as the plugin's icon
The plugin had **no icon at all** — no favicon, and nothing in Signal K's
Webapps list. It now carries the same mark as the Android station: two opposed
chevrons, forward (`--forward` #2A78D6) over reverse (`--reverse` #EB6834), on
the `--surface` background, drawn on the Android adaptive icon's 108-unit
canvas so the two are the same drawing at the same proportions.

Signal K's mechanism was checked against its own docs rather than assumed:
`package.json` → `signalk.appIcon`, a path **relative to the published `public/`
directory**, wanting a raster of at least 72x72.

- **`publicDir` had to come back.** It was disabled outright because Vite's
  default (`public/`) collided with this project's build OUTPUT directory of the
  same name. Pointing it at `static/` resolves the clash and, more importantly,
  gives verbatim copying with **stable filenames** — an imported asset would be
  emitted hashed (`icon-a1b2c3.png`) and break both `appIcon` and the favicon
  `<link>` on every rebuild.
- **The PNG is rasterised by a committed script, not an image library.**
  `scripts/make-icon.cjs` does a point-in-polygon fill with 4x supersampling and
  writes the PNG through `zlib` — about 150 lines, no dependency, for what is
  two flat-coloured polygons. Output is committed (`static/` is not gitignored,
  unlike `public/`), so building or installing the plugin needs nothing extra.
- **The script's self-check caught a bug in itself.** It counts pixels per
  colour so a botched run cannot silently ship a blank square; the first run
  reported `forward 0px`. The renderer was fine — the CHECK was wrong, testing
  `red > 0x80` when forward (#2A78D6) is blue-dominant with a red channel of
  0x2A. Classified by dominant channel now: 1404px each, symmetric, as two
  mirrored chevrons should be.
- `displayName` was deliberately NOT added alongside `appIcon`. It also feeds a
  server-side redirect, and changing routing was not what was asked for.

**Verified:** built output contains `public/favicon.svg` and
`public/icons/icon-192x192.png` at fixed paths; `index.html` links both with
relative hrefs (the app is mounted under `/<package name>/`, not at the root);
PNG header confirmed 192x192, 8-bit RGBA; **and the rendered image was actually
looked at**, not merely counted. `npm test` **207/207**.

### Follow-up (same day): Android connection + token lifecycle
The gap flagged two entries ago is closed: the station could not recover from a
token that stopped working, and had no way to leave a server once chosen.
`forgetToken()` existed and **nothing called it** — it is now deleted, because
the two paths that replaced it cover both cases properly.

**The decision that needed a test, and the trap in it:** a 401 does NOT mean the
token is dead. Both clients probe [AuthScheme.TRY_ORDER] — `Bearer` then `JWT`,
because signalk-server has historically accepted only one or the other (issue
#715) — so **every rejection before the right scheme is the handshake working**.
A naive "401 ⇒ re-authorise" would throw away a good token mid-manoeuvre on
exactly the servers the probe exists for. New pure `TokenHealth` counts
*consecutive* rejections and declares death only past
`AuthScheme.TRY_ORDER.size + 1` — derived from the list, not hard-coded, so
adding a third scheme cannot silently make the probe look like a dead token. One
acceptance clears the count. At the 250 ms heartbeat the verdict lands in 750 ms,
which a test pins against `PERIODIC_REFRESH_MS`.

**A transport failure is never fed in.** Losing the network is not losing
authority; counting it would drop a good token every time the boat's wifi
hiccuped, and the station must stay on the control screen while offline because
its disarm has to keep working when it cannot see the boat.

The use cases now handled, including the ones not asked for but found while
working through the state space:

| Case | Behaviour |
|---|---|
| Start with server + valid token | straight to the controls (unchanged) |
| Start with a token past its stated expiry | token dropped, server screen, notice |
| Start with no server | server screen |
| Network drops, token still good | **stays on the controls**, stream reconnects |
| Token revoked server-side | past the probe → session ends, server screen, notice |
| Token expires mid-session | pre-empted by the heartbeat, not waited for |
| Auth-scheme probe in progress | a note, no teardown |
| Operator taps Disconnect | final neutral intent, then session ends |
| Disconnect **while armed** | refused, "Disarm before changing server" |
| Switching to a different server | previous server's token withheld → access flow |

- **The token is now bound to the server that issued it** (`KEY_TOKEN_SERVER`).
  Without that, pointing the app at a second server sent it straight to the
  control screen holding the first server's credentials, to fail as a 401 on
  the first press. A token stored before this binding existed has no recorded
  server and is treated as belonging to the configured one, so upgrading does
  not force a re-authorisation.
- **Teardown order is deliberate:** release all controls to neutral *while the
  heartbeat and token are still live*, then stop, then close. The arbiter sees
  this station let go rather than merely fall silent. Falling silent also works
  — staleness eviction is the backstop — but it costs a timeout for nothing.
- **Disconnect is refused while armed** rather than hidden. Disconnecting armed
  would leave the station holding the arm token until stale eviction, with the
  operator on a screen showing no controls: armed, still commanding, unable to
  see or stop it.
- **Disconnect lives behind the telemetry toggle**, not on the main surface — a
  control that ends the session should not sit where a thumb lands during a
  manoeuvre.
- The server screen gained the **reason** it was returned to (otherwise it reads
  as the app having lost its settings) and a one-tap **Reconnect** to the last
  server, which goes through the same `useServer()` path, so the token check and
  the cleartext gate still apply.

**Verified:** `:core:test` **145/145** (was 137; +8 for `TokenHealth`),
`:app:assembleDebug` OK, 11.24 MB.

**Not verified:** none of this has run on a device. The state machine is
reasoned and the pure part is tested, but no token has actually been revoked
against a real server to watch the app return to the server screen. That is a
five-minute check in the Signal K admin UI and worth doing before relying on it.

## 2026-07-26 — Docs audit: four documents did not know the Android station existed
Session: claude/android-plugin-ui-parity-otk4hc

Owner asked whether the Android tooling was documented well enough for another
developer, and whether the docs were in line with the code. Answers were **yes**
and **no**.

`android/README.md`'s *Building and testing* section was already the strongest
doc in the tree — prerequisites split by target, exact verified versions,
per-OS one-time setup, three ways to point Gradle at the SDK, the debug-keystore
gotcha. A new developer can follow it cold. Everything around it had drifted.

**The corrections that were factual errors, not omissions:**

- **`android/README.md` said the app had never been installed or run.** It had
  — on the owner's phone, against the boat's server. The same table two rows
  above already said the intent POST had been accepted at readwrite, so the
  document contradicted itself. **The journal was wrong first:** the cleartext
  entry above closes with "the APK has never been installed or run on a phone"
  while describing button presses in a running app. Confirmed with the owner
  before rewriting: a real phone, not the emulator. History is not edited, so
  this entry is the correction.
- It also still called the §10 commissioning `curl` "the first thing to do".
  That was answered two entries ago: **signalk-server 2.30.0 has
  `router.access`**, so the readwrite route is live and the admin fallback is
  not in play. Now recorded in ARCHITECTURE §10 as a confirmed data point rather
  than an open question, with the warning that it only holds for a **deployed**
  plugin build.
- Root `README.md` claimed the plugin suite was 199 cases; it is 207, and the
  Android core's 145 were not mentioned at all.

**The omissions.** `ARCHITECTURE.md` had **zero occurrences of "Android" in 698
lines** — absent from the parts table, the repository layout, the Signal K
contract and *Extending the system*. `SAFETY.md` had none either, and the three
checks that cannot be inferred from a green test run (two-fingered multi-touch,
fail-safe on backgrounding, exclusive arm against the browser UI) were sitting
in a module README rather than in the document billed as holding the
checklists. Root `README.md` listed four parts, not five, and its CI table
omitted the `android-core` job that has been running all along.

- **New ARCHITECTURE §11**, with §11–13 renumbered to §12–14. It states the
  thing the parts table alone would not: **four stations, three authority tiers**
  — both phone stations are clients of the one arbiter, so adding a station adds
  no authority. That framing is why the section can be short.
- **New SAFETY checklist, *Before trusting the Android station***, and a new
  cross-cutting rule: the access token is a key to the machinery, hence the
  cleartext gate, hence the requirement that a station whose authority has been
  withdrawn stops presenting itself as able to command. The checklist says to
  run it **on the phone that will actually be used** — multi-touch and doze
  behaviour are per-device.
- **`android/README.md` gained** the emulator notes (`10.0.2.2` passes the
  cleartext gate; mDNS does not work under the emulator's NAT), `applicationId`
  vs `namespace`, the fact that no signing config exists so `assembleRelease`
  produces an unsigned APK, and the token-lifecycle case table that until now
  lived only in this journal.
- **`system-overview.drawio` gained the Android box** and both its edges, and
  the rule block now states the three-tier point. Re-exported with
  `--embed-diagram` so the PNG stays editable. The diff is 35 lines — worth
  noting because the first attempt rewrote the whole file: repairing the layout
  with a PowerShell `Get-Content -Raw` / `Set-Content` round-trip **mangled
  every em dash into mojibake**, since `Get-Content` read the BOM-less UTF-8 as
  ANSI. Repaired by reading bytes and writing back through
  `UTF8Encoding($false)`. **Do not round-trip these files through
  `Get-Content`/`Set-Content`.**

**Known gaps now written down instead of only being known.** The Android
`StatusPanel`'s `heightIn(max = 200.dp)` cap — the same decision reverted in the
plugin as the wrong call — is in `android/README.md`'s *Known gaps* and in the
SAFETY checklist as a thing to look at on the real phone. So is the Android kill
switch naming nothing when one unit is missing but the other is still armable.

**Not done:** no suite was re-run in this session; the counts quoted (234 / 207
/ 145) are carried from the entries above. Only `system-overview` was updated —
the other six diagrams do not depict command stations and needed nothing.

## 2026-07-26 — Screenshots, and the Android drive buttons measured on hardware
Session: claude/android-plugin-ui-parity-otk4hc

Owner asked whether the screenshots were current, and for Android ones. The
plugin's were stale; the Android session produced something more valuable than
pictures.

### Plugin: regenerated, and the alt text was worse than the images
All six predated the collapsed-telemetry change, so every one showed the old
always-expanded lamp panel. The README's descriptions were the real problem —
they described rows ("connected, commands reaching the boat, no device armed,
both units responding") that now live behind a `<details>` and appeared in **no
screenshot at all**.

- New **phone-detail-open.png** for the seven hidden lamps.
- **phone-unit-missing.png** and **desktop-tx-override.png** now open the detail
  before capturing, because those two shots exist FOR rows inside it — the red
  THRUSTER UNIT / NOT SEEN lamp and the PORT forward · TX remote attribution.
  Collapsed they showed three green summary lamps and no evidence of their own
  subject. The other five stay collapsed: that is what the operator meets.
- `screenshots.cjs` gained an `openDetail()` that waits for the last lamp to be
  visible rather than sleeping. `npm test` **207/207**, build clean, Playwright
  installed with `--no-save` so `package-lock.json` is untouched.

Noticed and NOT changed: on desktop the sidebar is ~90% empty with the panel
collapsed, and `THRUSTER UNIT` truncates to `THRUSTER U…` in that narrow
column. Defaulting `<details>` to open above the 860 px breakpoint would use
the space, but that is a design decision, not a documentation fix.

### Android: the app commanded both machines for the first time
Phone connected over `adb` (Galaxy S25, SM-S931B, 1080×2340, density 480). The
app was already running and connected to the boat server, and the expanded
detail showed **DRIVE UNIT responding · THRUSTER UNIT responding** — so arming
was a real hardware action, not a UI exercise. Confirmed with the owner that
the hardware was safe before touching the kill switch.

Captured, all against the live server: disarmed; armed with port FWD held
(readout FORWARD, released back to NEUTRAL and verified); thruster PORT held in
MANUAL; HOLD engaged showing a real **096° HOLDING · TRIM +10°**; and the
detail expanded. Disarmed afterwards and verified — DISARMED, both drives
NEUTRAL, "nobody armed" — and the device's temp files removed.

**The two-handed shot does not exist and cannot be scripted.** `adb`'s
`input motionevent` injects a single pointer, so it cannot demonstrate the one
thing `ui/Momentary.kt` is for. Real multi-touch needs raw `sendevent` with MT
protocol B slots, which risks leaving a phantom contact — on a *live drive
button of an armed station*. Not done, deliberately. It stays a human item on
the SAFETY.md checklist, which is the honest place for it.

### The finding: `weight(1f)` silently defeats the drive buttons' floor
Measured with `uiautomator dump`, same session, nothing else changed:

| Detail panel | Each FWD/REV button |
|---|---|
| collapsed | `[30,1019][525,1411]` → 392 px = **131 dp** |
| expanded | `[30,1019][525,1033]` → 14 px = **4.7 dp** |

`ui/Controls.kt` asks for `Modifier.fillMaxWidth().weight(1f).heightIn(min =
ContactButtonMinHeight)` — 88.dp. **The floor does nothing.** In a `Column`,
`weight(1f)` hands the child an exact height from the parent's remaining space,
and `heightIn(min = …)` cannot grow past an incoming max constraint. The
sibling panel takes the room and the buttons comply silently.

The earlier entry predicted this would "clip rather than scroll". It does
neither: it **compresses**. And the hazard is not the obvious one — 4.7 dp is a
tenth of the platform minimum touch target, and that 14-pixel strip sits
directly between the PORT and NEUTRAL labels, so armed, a thumb aimed at a
label can land on a live drive control. The kill switch is fixed at the top and
unaffected, so disarm keeps working throughout.

Worth noting for whoever fixes it: **removing `heightIn(max = 200.dp)` from
`StatusPanel` would make this worse**, not better — the panel would take even
more of the column. The fix is to hoist the expanded state out of `StatusPanel`
so the drive row can refuse to yield, which is the asymmetry with the plugin's
`:has()` solution the earlier entry already flagged.

Documented, not fixed — the owner chose to capture first.
## 2026-07-25 — Firmware control-core review follow-up

A deep safety review found and fixed two control-law edge cases. Switching an
already-engaged remote from MANUAL to HOLD did not produce a new FSM edge, so
the hold loop could reuse its default or a previous session's captured heading;
the mode transition now captures the current fused heading and has an
integration regression test. Separately, the Schmitt switcher could keep the
old thrust direction when the lead variable jumped completely across the
deadband into the opposite sign; it now releases to OFF using a
direction-specific threshold, then observes the existing reversal dwell before
allowing the opposite direction. Regression coverage was added for both paths;
the native suite could not be run in this review environment because PlatformIO
is not installed.

## 2026-07-25 — Signal K command-authority safety review
Session: multi-agent deep review

Closed three server-side fail-safe gaps found in review. The arbiter now gates
and erases each machine's commands independently when that machine is absent,
so a command cannot wait invisibly and activate when its controller returns. A
new station's first accepted STOP is now universal even if its mount heartbeat
was lost or reordered. Plugin shutdown publishes disabled, neutral/off state
before removing its heartbeat and subscriptions. Regression tests cover both
unit-return paths, first-packet STOP ordering, and the final shutdown delta.
The plugin suite could not run in this environment because its installed
dependencies are absent.

## 2026-07-25 — Close the remaining multi-agent review findings
Session: follow-up to the deep safety review

Closed the review backlog across all three runtime layers. RX and HH now accept
a remote command tuple only when every independently retained Signal K field is
fresh and arrived within one bounded generation window; cached snapshots age
locally through a rare mutex collision instead of either extending authority or
causing a spurious disconnect. RX calibration edits and HH watchdog/output GPIO
writes now cross explicit FreeRTOS critical sections, removing their C++ data
races.

The plugin's safety clock is now monotonic, including browser telemetry arrival
ages, so an NTP or administrator wall-clock correction cannot extend stale
authority. The SensESP dependency comments and CI prose now state the owner's
actual upstream-tracking decision rather than claiming a revision is pinned.

Android now serializes intent POSTs, captures the token for its final safe
teardown send, stops its heartbeat after background release, rejects callbacks
from superseded sockets, resets the HTTP auth probe per server/token, and ages
both telemetry and plugin verdicts on monotonic clocks. A retained old
`plugin.*Live=false` can no longer override fresh direct telemetry. The drive
bank reserves 280 dp inside a scrollable page, so expanding telemetry cannot
negotiate the live contacts down to the measured 4.7 dp strips. This layout fix
is code-reviewed but still needs the documented remeasurement on the reference
phone; none of the firmware concurrency changes has been re-bench-tested.

Local syntax checks passed for the pure C++ core, Node plugin sources, Python
helpers and shell launcher. Full PlatformIO, npm/Vitest and Gradle suites remain
blocked in this environment by missing tools/dependencies and the proxy's 403
responses; CI remains the executable backstop, not evidence of hardware
behaviour.

## 2026-07-26 — Review of PR #14, and the fixes it needed
Session: review of "Resolve remaining safety review findings"

Reviewed PR #14 against a running toolchain rather than syntax checks, which
changed the verdict. The plugin suite was **red**: 7 of 212 cases failed, and
CI had already said so. Four were pre-existing thruster tests broken by the new
absent-unit gating — `makeArbiter()` announces RX but never HH, so every
thruster command in that suite was being neutralised by the gate rather than
exercising the arbitration it was written for. Two were the PR's own new
unit-return tests, which armed a station at t=2 and then asserted it still held
the token at t=3000, past the 1 s client eviction. The last was the new
monotonic-clock test, which assumed `vi.advanceTimersByTime` moves
`performance.now()`; `performance` is not in vitest's default `toFake` set, so
nothing expired and the assertion was measuring the wrong thing.

The layout fix traded one hazard for a worse one. Wrapping the whole page in
`verticalScroll` put the kill switch inside the scrolling region — and the
reason the original 4.7 dp defect was survivable, stated in this repository's
own notes, was that the kill switch stayed fixed at the top and disarm kept
working throughout. STOP is now outside the scroll again: a fixed header, with
only the thruster/drive/telemetry stack scrolling under it. The contacts went
back to `heightIn(min = …)` floors rather than exact heights, so the constant
means what it is named.

Two other holes closed. The arbiter erased a departed unit's command only in
`tick()`, but `onIntent()` advances the same liveness verdict — an intent that
noticed the unit's death and was then discarded at the ordering gate consumed
the falling edge, and the following tick saw was-live already false and erased
nothing, leaving the command latent behind the `state()` mask. Both paths now
share `_refreshUnitLiveness()`, with a regression test that fails without it.
And `evaluateLiveness` had grown a defaulted `pluginVerdictAgeMs = null` whose
default silently discarded the server's `false` verdict; an unknown age now
means *believe the server*, the fail-safe direction.

Also: `stream.close()` no longer queues behind the teardown send (a blocking
OkHttp call cancellation cannot interrupt, so the read socket could stay open
for a full timeout); backgrounding only releases controls when there is a
control session, since the lifecycle observer now mounts for every stage; and
the `app/` "no test source set" known gap was restored to `android/README.md`,
because deleting the entry did not create the tests — `app/` is still compiled
by nothing, CI included.

Verified here: plugin suite 213/213 and `tsc -b` clean, Android `:core:test`
147/147. NOT verified here: `:app` (no Android SDK, and CI does not build it
either) and the firmware (no PlatformIO in this environment) — though CI had
already shown all three firmware builds and the native suite green on the
reviewed commit. The coherence-window change in `sk_command_in`/`sk_thruster_in`
remains the most safety-consequential part of that commit and still has no test
at all, being hardware glue outside `pio test -e native`; measure the real
per-path arrival spread on the bench before trusting it afloat.

## 2026-07-26 — PR #15 review round: phantom STOP on re-registration
Session: review of PR #14, follow-up

An automated reviewer raised two P1s on PR #15. One was right, one was not.

The right one: `disarmReq` is cumulative for the life of a station's session,
so "the first packet WE have seen" and "the first packet IT has sent" are not
the same thing. Treating a re-registering station as brand new meant any
station that had ever pressed STOP would clear another station's arm the moment
it came back from more than `staleTimeoutMs` away — with no operator action.
The release-on-background change in the previous commit is what turned that
from a corner case into an ordinary one: backgrounding stops the Android
heartbeat, the arbiter evicts the client about a second later, and resuming
re-registers it. A phone glanced away from mid-manoeuvre would silently disarm
the browser station holding the token.

Fixed by remembering evicted clients' disarm counters (`_counterMemory`,
bounded and aged by last use) and comparing a re-registration against that
remembered total instead of against zero. All three properties hold: a
genuinely new station's first STOP still fires immediately, which is what the
first-packet rule was added for; a station returning with the same total does
not fire; and a station whose counter ADVANCED while it was away — a real press
whose POST could not get through — still fires. Two regression tests, one of
which fails without the fix and one of which guards against over-correcting.

The wrong one claimed the HH fail-off watchdog could spin behind the control
task on `Outputs::mux_` and leave the thruster latched past its 100 ms timeout.
That misreads the primitive. `portENTER_CRITICAL` masks interrupts on the
holding core, so a task cannot be descheduled inside the section, and the
section is two assignments and three `digitalWrite`s — no loops, no blocking
calls, no yields. The stalls the fail-off exists to catch (a wedged loop, a
blocked network call, starvation) all happen OUTSIDE it, with the spinlock
free. Making that path non-blocking would reintroduce exactly the interleaved
GPIO race the critical section was added to close. Left as is, with the
reasoning recorded on the thread.

Plugin suite 215/215, `tsc -b` clean. CI green on all seven jobs for the
preceding commit, firmware builds included.

## 2026-07-26 — First `:app` compile of the branch, on a machine that has the SDK
Session: verification of `claude/android-plugin-ui-parity-otk4hc`

Asked to check the Android station was sound on this branch. This workstation
has Temurin 21.0.11 and the SDK at `C:\Android\Sdk`, so for the first time the
pair could both be run rather than just `:core:test`.

`:core:test` 147/147, no warnings. `:app:assembleDebug` **BUILD SUCCESSFUL** —
`app-debug.apk`, 11.24 MB. A forced `:app:compileDebugKotlin --rerun-tasks`
produced only the two documented `NsdManager` deprecations, no new ones. That
retires the "not compiled anywhere" half of the layout known gap: the 280 dp
drive bank and the scrolling region below the kill switch now have a compiler's
verdict. It does not retire the other half — a Compose layout that compiles can
still lay out wrong, which is how the 4.7 dp defect shipped in the first place.

Checked by hand, because a silent failure would have broken liveness invariant
2: the branch moved view derivation to `SystemClock.elapsedRealtime()`
(`StationViewModel` L561), and `SkStream` L147 stamps arrivals with the same
monotonic clock, while token expiry stays on `System.currentTimeMillis()` on
both its parse and its check side. Both pairings are internally consistent. A
wall-clock/monotonic mismatch here would have made every path look permanently
fresh — stale telemetry presented as live, with no test to catch it, since
`:core` takes `nowMs` as a parameter and never reads a clock.

Ran AGP lint for the first time (no linter is configured; this was `lintDebug`
by hand): 1 error, 32 warnings. The error is `ProduceStateDoesNotAssignValue`
at `MainActivity` L190 and reads as a false positive — `value` *is* assigned,
inside the `collect {}` nested in the producer lambda, which that check is
known to miss; the function is untouched by this branch; and mDNS discovery
demonstrably worked against the real server on 2026-07-25. The warnings are the
deliberate ones already reasoned about in the README (permissive `base-config`,
because the cleartext gate lives in tested Kotlin; locked orientation) plus
dependency-version nags. Not wiring lint into the build on the strength of one
run against one false positive.

Doc drift found and fixed: `android/README.md` still claimed 145 core tests in
two places (the branch's two new `LivenessTest` cases make it 147), and both it
and `AGENTS.md` still said the phone would be the first compiler to see `app/`.

Still unverified, and unchanged by any of this: the 88 dp contact floors and
the kill switch's position at every scroll offset. `adb devices` is empty and
no emulator or system image is installed. Worth recording why the emulator is
not simply the answer — the drive bank only exists on the control screen, which
needs a reachable Signal K server and an approved token, so measuring the fix
needs either the phone on the boat's network or a stand-in server, not just an
AVD. `app/` still has no test source set, so the new `onBackgrounded` /
`onForegrounded` gating and the `intentMutex` send ordering in `teardown()`
remain covered by reasoning only.

## 2026-07-26 — The layout floors become a suite, and it finds an 80 dp button
Session: verification of `claude/android-plugin-ui-parity-otk4hc`, continued

The layout fix had been reasoned about, documented and — as of this morning —
compiled, but never measured. Measuring it on the phone needs the boat's server
and an approved token; an emulator cannot reach the control screen at all. So
the measurement moved to where it can run every time instead: Robolectric lays
the real Compose tree out on the JVM.

`ControlScreen` was lifted out of `MainActivity` first, taking plain values and
callbacks rather than the ViewModel. That is the whole enabling move — the
geometry was previously reachable only through a live session.

`LayoutFloorsTest` (6 cases) asserts, at the S25's 360x780 dp and at a
deliberately too-short 480 dp: every live contact >= 88 dp, the drive bank >=
its 280 dp reservation, and the kill switch displayed and *unmoved* after
scrolling the region below it to the end. Assertions are written against the
literal 88 and 280, not against the constants the layout uses — asserting
against the constant would let code and test drift downward together, which is
the exact failure being guarded against.

**It found a real defect on its first run.** The thruster's PORT/STBD contacts
were 80 dp, not 88. `Modifier.fillMaxWidth().height(88.dp).padding(top = 8.dp)`
fixes the row at 88 and then takes the 8 dp gap out of the *button*; padding
must come first so the gap sits around it. Not dangerous on its own — 80 dp
still clears Android's 48 dp minimum — but it silently broke the "one constant,
a floor everywhere" rule that the drive contacts depend on, and it had been
shipped and photographed without anyone noticing. That is precisely the class
of thing a human reading a screenshot does not catch.

Regression value was checked rather than assumed. Reconstructing the pre-fix
layout (weight(1f) on the contacts, no scrolling region, no reservation) turns
the suite red at **72 dp** on the reference screen and **0 dp** on the short
one — live, commanding buttons with no height at all. Restored, all 6 green.

CI gained an `android-app` job running `:app:testDebugUnitTest` and
`:app:assembleDebug`, so `app/` is compiled by CI for the first time. The
existing `android-core` job now explicitly *clears* ANDROID_HOME: GitHub's
ubuntu runners ship an SDK, which would have made `settings.gradle.kts`
configure `:app` and quietly destroy that job's whole purpose — it exists to
fail if something Android-specific leaks into the pure layer. Verified locally
by hiding `local.properties` and unsetting both variables: 147/147, `:app` not
configured.

Robolectric runs as a JUnit4 runner under the module's existing JUnit Platform
via the vintage engine, rather than splitting the two modules across test
frameworks.

What this still does not prove: that the S25 agrees. Robolectric models that
phone's density, not its system bars, display cutout, gesture insets or the
operator's font scale, all of which take real room. The `uiautomator dump`
stays on SAFETY.md's checklist, with that limit now stated. And `app/`'s
BEHAVIOUR remains untested — `onBackgrounded`, the `intentMutex` send lane and
the teardown ordering are still covered by reasoning alone. Only the geometry
moved from reasoning to measurement.

Final state: `:core:test` 147/147, `:app:testDebugUnitTest` 6/6,
`:app:assembleDebug` green (11.2 MB APK), two known `NsdManager` deprecations.
## 2026-07-26 — Second Codex round: scroll-as-command, and STOP under a full table
Session: review of PR #14, follow-up

Four more automated findings. Two fixed here, two raised for a decision.

**Fixed — a drag on a live contact was a command (P1, and self-inflicted).**
`momentaryPress` claims in its own docstring that the `finally` runs when "the
pointer [is] consumed by a parent". It does not: `awaitFirstDown` fires the
press on touch down, and the loop only ends when the pointer lifts or vanishes
— never on consumption. That was harmless until the previous commit wrapped the
controls in a `verticalScroll`, at which point dragging to reach telemetry
would press a drive or thruster contact for the entire drag, while armed. The
layout was the mistake, so the layout is what changed: the kill switch, the
thruster contacts and the drive bank are all fixed now, and only the telemetry
panel scrolls — which it was already doing internally anyway (`heightIn(max =
200.dp)` plus its own scroll). Nothing that can command a machine has a
scrolling ancestor. `momentaryPress` also now releases on `isConsumed`, which
makes its docstring true and makes reintroducing a scroll ancestor safe rather
than silently dangerous.

**Fixed — STOP was droppable when the client table was full (P2).** The
capacity guard returned before the first-packet disarm handling, so with 32
live sessions (or a client-id flood) a genuine stop from an untracked station
got a 200 and did nothing while a machine was still under command. The disarm
edge is now evaluated before the capacity check and without creating a record:
the cap still refuses the bookkeeping, but universal STOP outranks it, and a
path that records nothing gives a flood no foothold. Regression test fails with
the old ordering.

**Raised, not fixed.** Two findings are real but are design decisions on code
this environment cannot test: STOP serialised behind a stalled intent POST
(worst case one `callTimeout`, 1 s), and the non-atomic four-watchdog coherence
check in `sk_command_in`/`sk_thruster_in`, where a snapshot landing between
listener callbacks can read a torn generation and call a healthy source dead.
The second is the same ingest path already flagged as the most
safety-consequential untested change in this branch.

Plugin 216/216, `tsc -b` clean. CI green on all seven jobs for f648b5e.

## 2026-07-26 — Merging the two layout fixes: theirs wins, mine gets tested
Session: merge of `claude/review-pr-14-751x6x` into the layout-suite work

Two sessions fixed the same defect in different directions and both landed. The
merge is not a text conflict — the two layouts are incompatible answers to the
same question, so this is a decision, recorded as one.

**The other session was right, and found a bug in my change.** I had wrapped
everything below the kill switch in a `verticalScroll` so a short screen would
gain a scrollbar rather than squeeze live buttons. But `momentaryPress` claims
the pointer on touch DOWN and never releases on consumption, so with a scrolling
ancestor a drag to reach telemetry pressed a drive or thruster contact for the
whole drag, while armed. Scrolling was silently a command. Their layout — kill
switch, thruster and drive bank all fixed, only the telemetry panel scrolling —
removes the hazard at its source rather than patching the gesture. That is the
better fix and it is the one that survived.

**What survived from this side** is the part that made the disagreement
checkable at all: `ControlScreen` extracted from `MainActivity` so the layout
renders without a session, `LayoutFloorsTest`, the CI job, and the 80 dp
thruster contact fix. Their arrangement moved into `ControlScreen` unchanged in
substance; `MainActivity` still just delegates.

The suite grew to 8 and now asserts their invariant structurally: walk every
live control's ancestors and fail if any of them scrolls. Verified it has teeth
by putting the `verticalScroll` back — red, precisely that test. Position-based
assertions would not have caught it in a state where scrolling happens to move
nothing.

**The merged layout has a floor their commit did not state, and the suite found
it.** With nothing scrollable above telemetry there is no escape valve, so once
the fixed content stops fitting, `heightIn(min = 280.dp)` is coerced by the
Column's remaining space and the LAST child absorbs all of the shortfall.
Measured by bisection: 88 dp holds down to **512 dp** of available height, then
80 dp at 504, 72 at 496, 64 at 488, 56 at 480. My original short-screen test
picked 480 dp arbitrarily and so failed against their layout — that failure was
the useful part, not noise. It is now three tests: the reference S25 (780 dp), a
budget 720x1280 phone (640 dp), and the 512 dp minimum pinned so it cannot drift.

That floor is far below any real phone, but the number is *available* height:
`safeDrawingPadding` takes the status bar, navigation bar and cutout off it, and
a large font-scale setting inflates the kill switch and readouts. Worth knowing
before someone runs this on an old handset with big text. If that test ever
needs relaxing, the layout wants an escape valve, not a smaller number.

Their `momentaryPress` `isConsumed` fix, `arbiter.cjs` STOP-before-capacity fix
and its regression test merged without conflict and were left exactly as
written.

Final state: `:core:test` 147/147, `:app:testDebugUnitTest` 8/8,
`:app:assembleDebug` green, plugin 216/216, `tsc -b` clean.
## 2026-07-26 — The two findings held back for a decision
Session: review of PR #14, follow-up

Owner chose both recommended options; implemented here.

**STOP no longer queues behind a stalled POST.** `intentMutex` exists so a
press can never arrive after its own release, but making disarm wait for it
meant one stalled POST delayed a STOP by a full `callTimeout` (1 s) — and
disarm is the one control this system says is never gated. `requestDisarm()`,
`releaseAllControls()` and the teardown send now skip the lane. Ordering
survives because the sequence number is claimed before any I/O rather than
inside the lock: an overtaken press carries the lower `seq`, and the arbiter
already discards `seq <= lastSeq`. The guarantee moves from the client's lock
to the server's ordering gate, which was the authority on it anyway. Normal
presses and the heartbeat still serialise.

**The coherence window is gone; per-path freshness stays.** Requiring every
member of a command tuple to be fresh in its own right is what stops a fresh
heartbeat combining with a retained old direction after a partial reconnect —
the stale member fails its own watchdog, which was the whole point. The extra
"and they all arrived within 2x listen_delay of each other" test on top added
nothing to that property and invented a failure: the listeners update under
separate mutex acquisitions, so a snapshot landing between them reads one field
a refresh newer than the rest. That torn read of a healthy publisher was being
scored as a dead source — dropping the drives to neutral for a tick on RX, and
on HH dropping HOLD so the next complete tuple looked like a fresh engage and
silently recaptured the heading, abandoning the operator's setpoint.

Worth recording that this is the mechanism, not just the tuning: no window
value would have been correct, because the torn read is a property of how the
four listeners write, not of how far apart the publisher sends. The remaining
`last_update_ms` bookkeeping (oldest member's arrival) is unchanged and still
what the control tasks age a cached snapshot against.

Plugin 216/216, `tsc -b` clean, Android `:core:test` 147/147. The firmware
change is compile-checked by CI only; the ingest path still has no test and
still owes the bench measurement recorded earlier in this journal.

## 2026-07-26 — Third review round: two bugs in the previous two fixes
Session: review of PR #14, follow-up

Both findings fixed here were defects in the fixes from the round before,
which is worth recording as its own lesson: each narrowing of the arming
authority created a new edge somewhere adjacent.

**The capacity STOP became a ratchet.** Honouring a disarm edge ahead of the
capacity check consumed it without banking the counter, so at capacity every
later heartbeat from that untracked station read `disarmReq > 0` against a
baseline of zero and re-fired the stop indefinitely — nobody could re-arm for
as long as the table stayed full. Turning "STOP is honoured" into "STOP is
permanent" is arguably the worse of the two failures. The counter is now
banked on that path too, so the edge is spent exactly once whether or not the
station gets a record.

**A unit outage falling entirely between ticks was invisible.** `tick()` and
`onIntent()` both settle liveness, but `onRxTelemetry`/`onHhTelemetry` set the
verdict to live directly. A gap that crossed the timeout at 1500 and was
answered at 1600, with the next 250 ms tick not due until 1750, was therefore
never observed as a dead interval at all — and the returning frame republished
the holder's pre-gap command, after the unit's own watchdog had already failed
its outputs safe. Both arrival handlers now settle the falling edge against the
previous arrival before recording the new one.

Plugin 217/217, `tsc -b` clean. Each new test was checked against the
unfixed code.

**Three findings raised for a decision rather than fixed**, because they are
design tensions rather than defects: the auth-scheme probe now being reachable
concurrently by urgent sends; the drive-bank floor not surviving a short
display once the outer scroll was removed (immediate press, no scroll hijack,
and a guaranteed floor cannot all three hold in one naive layout); and whether
a recovered unit should require a fresh command edge rather than accepting the
holder's next unchanged heartbeat. The last one is the operating-model
question underneath several of this round's findings.

## 2026-07-26 — Second merge from the cloud sessions, and :app did not compile
Session: merge of `claude/review-pr-14-751x6x` (96efa9b, 0322f5b)

Two more review-round commits merged in: STOP unblocked from the send lane and
the torn-read coherence window removed; then the capacity-STOP ratchet and the
between-ticks outage fixed. Both are sound and neither needed a decision from
this side. Only `JOURNAL.md` conflicted textually, and both sets of entries were
kept.

**But the merged tree did not build.** `nextSeq()` was declared `Int` and
`sendIntent(seqForThisSend: Int)` took an `Int`, while the backing field is
`private var seq = 0L` and `ClientIntent.seq` is a `Long` — three type errors in
`StationViewModel`. Widened both signatures to `Long`, which is what the wire
format and the arbiter's `seq <= lastSeq` gate have always used; nothing about
the intended change moves.

Worth naming plainly, because it is the exact thing the new CI job was added
for. That commit reports "Android `:core:test` 147/147" and stops there — the
authoring session had no Android SDK, so `:app` was never compiled by anyone,
and the sequence-number rework it introduces lives entirely in `:app`. Before
today the next compiler would have been the phone, at the helm. It happened to
be this workstation instead, and now it is CI on every push. Two `app/` changes
in two days have now failed at first compile; the module is not exotic, it was
simply never checked.

Verified after the fix, all on the merged tree: native `pio test -e native`
235/235, plugin 217/217 with `tsc -b` clean, Android `:core:test` 147/147,
`:app:testDebugUnitTest` 8/8, `:app:assembleDebug` green, and the RX and HH
firmware compiled — the only automated check the changed `sk_command_in` /
`sk_thruster_in` glue has, since it sits outside `pio test -e native`. That
ingest path still owes its bench measurement.

## 2026-07-26 — The new CI job failed on its first real run
Session: PR #15 review check

`Android layout floors` went red on bec9df4 with `sdkmanager: command not
found`, exit 127. My step assumed `sdkmanager` was on PATH; the runner image
sets `ANDROID_HOME` but does not export its `cmdline-tools` bin directory. The
job never reached a test — every step after it was skipped, so the layout suite
has still never run on a runner.

Resolved by path rather than PATH: search `$ANDROID_HOME/cmdline-tools` for the
binary, fail loudly if it is missing rather than silently skipping, and tolerate
`yes` taking SIGPIPE on the licence pipe — that would otherwise fail the step
under the default `bash -eo pipefail` even with the licences accepted. Both the
YAML and the step's shell parse locally, but the only real test is a runner:
this workstation is Windows and its SDK ships only `.bat` wrappers, so the
extensionless path being searched for cannot exist here.

Worth noting what this cost: the job was added specifically because `app/` was
reaching the phone uncompiled, and its own first run proved nothing. The suite
passes locally; it has not yet passed anywhere else.

## 2026-07-26 — A restarted phone could not stop the boat
Session: PR #15 review check

Codex raised a P1 against the counter memory I added earlier in this branch,
and it is the mirror image of the bug that memory was built to fix.

`clientId` is persisted on the phone; `disarmReq` lives in the ViewModel. A
process restart therefore reuses the id with a counter that begins again at 0.
Scored against the dead session's remembered total, every STOP the restarted
station sends until it climbs back past that total reads as "below the
baseline" and is discarded. The station gets a 200 and the boat keeps moving.

Reachable because a STOP no longer waits behind the intent lane: an urgent
send can be the first packet of the new session the arbiter sees, so the
ordinary heartbeat that would have re-baselined the counter at 0 need not have
landed yet. Tapping STOP before ever arming does the same thing, since disarm
sends without a heartbeat running.

Fixed by reading a backward step as what it is. There is no record on this
path, so there is no session for a lower counter to be a replay *within* — it
is a restarted client, and it is scored against 0. The residual is a delayed
pre-eviction packet firing a STOP that need not have fired, which is the trade
the counter memory already documents in the safe direction.

Also banked the counter on every first sighting rather than only when the edge
fires, so a restarted station evicted a second time is not scored against its
dead session's total all over again — the same ratchet the capacity path had.

Regression test `honours a STOP from a station whose counter restarted` fails
without the change (`expected { enabled: true, … } to match { enabled: false,
activeClient: '', port: 'neutral' }`) and carries a second act asserting the
restart does not then ratchet. 218 plugin cases pass.

Worth recording that this is the third distinct defect in this one counter
comparison. Rejecting the session nonce kept a protocol field out of three
hand-synced config copies, and has now cost three rounds of edge cases; if a
fourth appears, the nonce is the answer rather than another special case.

## 2026-07-26 — Two of the three open PR #15 findings closed with measurements
Session: PR #15 review check, continued

**CI first: the layout job had never actually run.** It failed on its first real
attempt with `sdkmanager: command not found` — the runner sets `ANDROID_HOME`
but does not export its `cmdline-tools` bin directory. Now resolved by searching
that directory, failing loudly if absent, and tolerating `yes` taking SIGPIPE on
the licence pipe under `bash -eo pipefail`. Cannot be proven from here: this
workstation is Windows and its SDK ships only `.bat` wrappers.

**Token death no longer conflates a scheme probe with a dead token**
(`StationViewModel.kt:500`, Codex P2). `TokenHealth` counted replies, and since
STOP deliberately skips the send lane, three sends on a `JWT`-only server can be
built with the same unprobed `Bearer` before any reply advances it — spending
the whole budget on ONE failed probe and taking the controls away at the moment
the operator reached for STOP. `rejected()` now requires the scheme that was
refused, `isDead` requires every scheme in `TRY_ORDER` to have been refused as
well as the count, and `IntentPoster.Unauthorized` carries the scheme the
request actually used (reading the poster's current scheme afterwards would
name one the request never used). Three new `:core` cases; all three fail
against the old count-only rule.

**Contact floors are no longer negotiable** (`MainActivity.kt:112`, Codex P2).
Measured the interaction the previous round could only estimate — and the
estimate was wrong in both directions. Screen size alone is not the problem;
screen size *times* system font scale is, because the kill switch and readouts
size off `sp` while the contacts are `dp` and cannot compensate:

| viewport | 1.0x | 1.3x | 1.5x | 2.0x |
|---|---|---|---|---|
| 512 dp | 88 | 56.7 | — | **0.0** |
| 560 dp | — | — | 85 | 43 |
| 600 dp | — | — | 88 | 83 |
| 640 dp | 88 | 88 | 88 | 88 |

A 0 dp live button is the 4.7 dp collapse in different clothes, and split-screen
on the reference phone reaches that regime — so this was reachable on the
owner's actual device, not a hypothetical. The contacts moved from `heightIn` to
`requiredHeightIn`, which ignores the parent's maximum: the button keeps its
size and the overflow clips off the bottom. A visibly cut-off control is a much
better failure than one quietly too small to hit — the first is obvious, the
second invites a mis-press while armed. Suite is 11 cases now, including 512 dp
and 640 dp at 2.0x and a 390 dp split-screen window at 1.5x; the two new ones
fail against `heightIn`.

Also found while measuring: an sp-driven layout lands on 87.99997 dp where it
means 88, so the assertion carries 0.01 dp of rounding slack — four orders of
magnitude below the 56/4.7/0 dp failures it exists to catch.

**Left open deliberately.** `arbiter.cjs:353` — whether a unit coming back
should require a fresh command edge before motion resumes — is an operating-model
choice with two defensible readings, not a defect, so it stays with the owner.
And what the app *should* do on a window too small for the controls (clip as it
now does, shed the thruster block, or refuse and say so) is a product decision.
What is no longer open is whether a live contact may be smaller than its floor.

`:core` 150/150, `:app` 11/11, `assembleDebug` green.

## 2026-07-26 — The monotonic clock stops when the host sleeps
Session: PR #15 review check

Codex found the other half of the clock question this branch opened when it
moved the safety timeouts off `Date.now()`.

`performance.now()` is libuv's `uv_hrtime()`, which on Linux is
`CLOCK_MONOTONIC` — and that clock does not advance while the host is
suspended. A server sleeping with a station armed therefore wakes with every
client and telemetry arrival still scored as younger than its timeout. The
first tick republishes the retained drive and thruster commands, and motion
resumes with no operator behind it. RX's watchdog is no defence: it is being
fed a live command again, which is precisely what it is waiting for.

Fixed by adding back the time the monotonic clock refused to count. Wall time
running ahead of elapsed runtime between two samples is the signature of a
suspend, and that difference is added to an offset that only ever grows — so a
backward civil correction still cannot rewind the clock, which is the property
the original change was made for. The two are not in conflict once the clock
carries both facts.

Deliberately no arbiter change. Advancing the clock makes the existing,
already-tested timeout paths do the work: clients age out, both units go
absent, the falling-edge erasure clears the stored commands. A separate
"reset on suspend" entry point would have been new safety surface doing what
the timeouts already do correctly.

A large forward NTP step is indistinguishable from a suspend and is treated as
one. That disarms, which is the right way to be wrong.

Regression test `fails safe when the host suspends with a station armed` moves
the faked system clock a minute forward without advancing the faked monotonic
origin — sinon keeps `hrNow` separate from `setSystemTime`, so that is exactly
the shape of a suspend — then lets one ordinary 250 ms tick fire. It fails
without the correction (`expected { …(9) } to match object { …(5) }`), the
holder still looking alive at 250 ms of runtime. 219 plugin cases pass.

## 2026-07-26 — Fix all three: recovery gate, browser clock, clipped controls
Session: PR #15 review check, continued. Owner asked for all outstanding items,
including the two previously held back as owner decisions.

**Reviewed the incoming suspend fix first (c1721aa) — it is sound.** Verified
rather than read: reverted `runtimeNowMs` to plain `performance.now()` and its
new test fails with `activeClient` still held, so it has teeth. Monotonicity is
preserved (the offset only grows), a backward NTP step is correctly ignored, and
sub-threshold jitter cannot accumulate because the baseline advances regardless.
It is the only clock the arbiter sees.

**The recovery gate (Codex P1, `arbiter.cjs:353`).** Erasing a stored command
when a unit vanishes only clears what is *stored*: the holder's next 250 ms
heartbeat carries whatever its buttons say, so a finger that never moved rewrote
`forward` the moment the unit answered. `seq` orders transport, it is not
evidence of intent. A machine whose unit has been away is now quarantined —
safe values published regardless of what the holder asks — until the holder is
seen asking for the safe value. That release is the baseline; the next non-safe
value after it is a real command. Mirrors the arm token, which already needs a
fresh press once RX is back.

Two existing tests had to change, and that is worth stating plainly rather than
burying: both ended by asserting that a bumped `seq` carrying an unchanged
`forward` republished it, with a comment calling it "a fresh post-return
operator command". Neither ever released to neutral, so neither demonstrated
freshness — they encoded exactly the assumption being removed. They now assert
held-is-still-neutral, then release, then command. Three new tests cover the
thruster, per-machine independence (a thruster outage must not cost the drives
their commands) and that a disarm does not strand a quarantine. All four fail
with the gate neutered.

One of those new tests failed first time for the right reason: I had not kept RX
telemetry flowing, so RX genuinely *had* been absent and the drives were
correctly quarantined. The test was wrong, not the code — it now feeds RX every
250 ms and asserts `rxLive: true, hhLive: false` before proceeding.

**The browser's suspend-blind clock.** The same premise as c1721aa applies to
the UI, where it was not fixed: `useUnitLiveness` aged arrivals against raw
`performance.now()`, so a laptop closed with the panel open woke reporting
minutes-old telemetry as *responding* — invariant 6. New `pure/runtimeClock.ts`
mirrors the plugin's three lines, used by both `skClient` (stamping) and the
hook (ageing), because they must be the same clock or every age is meaningless.
Five tests: suspend counted, backward correction ignored, monotonic across a
suspend followed by a backward step, and no drift over 200 samples. The Android
station needs no equivalent — `elapsedRealtime()` already counts deep sleep.

**Clipped controls.** Making the contacts `requiredHeightIn` last round was
half a fix, and the missing half was instructive: the *bank* still had a
negotiable `heightIn`, so it shrank to fit while its 88 dp contacts overflowed
inside it — the reservation honoured on paper, broken in fact, and invisible to
a check on the bank's own bottom edge. That is how the first version of the new
warning test failed. Both are `requiredHeightIn` now, and the screen says when
a live control has been pushed off the bottom rather than leaving the operator
to discover it mid-manoeuvre.

Verified: native 235/235, plugin 227/227 with `tsc -b` clean, Android `:core`
150/150, `:app` 14/14, `assembleDebug` green.

Still not settled, and still not mine: nothing here is bench- or phone-verified.
The recovery gate changes what the boat does after a dropout, and it has only
been exercised against tests.

## 2026-07-26 — Three more Codex findings, including one I caused
Session: PR #15 comment check, continued

Three unanswered findings had landed against c1721aa. All three were right.

**A stale 401 could permanently misdirect the auth probe (P2, mine).**
`IntentPoster` keeps one `schemeIndex`, and teardown's final intent is sent
outside the send lane, so the operator can choose a new server while a request
to the old one is still in flight. That reply then advanced the NEW server's
probe.

Worth being plain about: my own change last round made the consequence worse.
The stale 401 pushes the index to the last scheme, where it sticks — the index
only climbs and the target no longer changes — so a Bearer-only server is probed
as JWT forever. And because I had just made `isDead` require *every* scheme to
have been refused, a token never tried with Bearer can now never be declared
dead either. Before that change it would at least have given up. So I turned a
mis-probe into a permanent stuck state: refused forever, reporting that it was
retrying. Fixed with an `authGeneration` counter carried by each request; any
reply whose generation is stale returns `Failed`, which feeds neither the probe
nor `TokenHealth`.

That needed a test in `app/`, and it is the first behaviour test there.
`IntentPosterTest` uses `mockwebserver` — declared in `app/build.gradle.kts`
since the module was written, for tests that never existed — with a
`headersDelay` on server A so its 401 genuinely lands after the switch to B.
Three cases; the race one fails without the guard.

**Suspend-gap policy moved into the pure layer (P1).** The wall/runtime skew
accounting decides when every arrival ages out and authority is revoked, so it
is command-authority policy, not I/O. It sat in `index.cjs`, where the plugin's
own suite could not reach it and an alternate shell could have fed raw
`performance.now()` and silently restored command resurrection after sleep. Now
`suspendClock.cjs` beside `arbiter.cjs`: the shell samples both clocks, the pure
class decides what the pair means. Six tests, all exact — samples are passed in,
so no fake timers. The existing suspend test in `pluginRoute.test.ts` still
passes, which is what proves the shell still behaves.

**Cached-snapshot ageing moved into the pure core (P1).** Six hand-written
copies of the same rule across RX and HH decided how long a cached remote
command may keep speaking for its source after a contended snapshot — a safety
rule living in hardware glue, against AGENTS.md's own instruction, with the
drive and thruster paths free to drift apart. Now
`control_core::AgeCachedSnapshot`, a template because `RemoteSource` and
`ThrusterRemote` share no base and neither should grow one just to be aged. Six
Unity cases including the ~49.7-day `millis()` rollover, which is exactly the
kind of thing Codex predicted would diverge. No behaviour change intended:
`pio test -e native` 241/241 and both firmwares still build.

One correction to my own first attempt at the helper test: the drive type is
`RemoteSource`, not `RemoteCommand`. Caught by the compiler, not by review.

Verified: native 241/241, plugin 233/233 with `tsc -b` clean, Android `:core`
150/150, `:app` 17/17 (14 layout + 3 HTTP), `assembleDebug` green, rx_shesp32
and hh_shesp32 both built.

## 2026-07-26 — Two more findings, both on the fixes from an hour ago
Session: PR #15 comment watch

Codex reviewed 044b547 and found two P1s in what I had just written. Both right.

**The recovery gate could be lifted by a HOLD heartbeat (`arbiter.cjs`).** My
release predicate asked for `thruster === 'off'` — but in HOLD the direction is
*always* 'off', because HOLD commands through the trim, not through a direction.
So every ordinary HOLD heartbeat satisfied it, lifting the quarantine while a
non-zero trim was still being asked for, and the next heartbeat restarted
automatic thrust off an offset dialled in before the outage. The erase path
already zeroes `trimDeg`, which is exactly the evidence that trim belongs to the
safe tuple; my release check simply did not read the same tuple the erase wrote.
Now requires `thruster === 'off' && trimDeg === 0`. Two escapes for the
operator: trim back to zero, or leave HOLD, which zeroes the trim in the app
anyway.

**Ordinary releases still queued behind a stalled press (`StationViewModel`).**
I made STOP and the lifecycle releases urgent last round and stopped there. But
every finger-lift — `setPort(NEUTRAL)`, `setThrusterDirection(OFF)` — still took
the mutex and could wait a full `callTimeout` while the *press* stayed applied.
That is the same fault as gating STOP, just smaller and vastly more frequent.
Releases are now urgent; presses stay in the lane, which is what the lane was
added for. Ordering survives the same way it does for STOP: the sequence number
is claimed before any I/O and the arbiter discards `seq <= lastSeq`.

Worth noting the shape of both mistakes, because it is the same shape twice: I
fixed the dramatic case (STOP; the thruster direction) and left the ordinary one
(a button release; the trim) to be found by someone else. The next review of my
own work should start by asking which sibling case I did not carry the fix to.

The release-urgency change has NO test. `StationViewModel` has no test seam --
the poster and dispatchers are not injectable -- so this one is covered by
reasoning only, like the rest of that class. Said plainly rather than left to
look verified.

Verified: plugin 234/234 with `tsc -b` clean, Android `:core` 150/150, `:app`
17/17, `assembleDebug` green. Native and both firmware builds were re-run for the
cached-snapshot refactor earlier this session: 241/241, rx and hh both SUCCESS
(RX 85.8% flash, HH 87.4%).

## 2026-07-26 — The recovery gate needed a third fix: release must be seen while live
Session: PR #15 comment watch

Codex on 9821da6: a release arriving DURING the outage cleared the quarantine,
because `_maybeRelease` never checked liveness. So the requirement could be
satisfied before there was anything to recover from — let go, press again while
the unit is still absent (masked, so the press looks inert), and the first
heartbeat after the unit returns republishes it. Motion restarting off a press
made during a blackout.

Fixed: a safe intent only counts while that unit is live. Regression test covers
the exact sequence and fails without the liveness requirement.

**Three findings in the same gate now — trim, then liveness.** Each time I wrote
the predicate for the case I was imagining and not for the state space around
it: direction but not trim, safe-value but not when. The gate itself is sound;
my checks of it kept being narrower than the thing being guarded. If a future
session touches `_maybeRelease`, the question to ask is not "does this handle the
case I have in mind" but "what is the complete safe tuple, and when is a report
of it admissible".

Plugin 235/235, `tsc -b` clean.

Also tightened the PR comment watch: it had been echoing this session's own
replies back as events, which would eventually drown a real finding. It now
filters comments carrying the Claude Code footer, so a genuine hand-written
comment from the owner still comes through.

## 2026-07-26 — "Force safe" was not safe in HOLD; and a race in my own guard
Session: PR #15 comment watch

**Backgrounding while armed in HOLD kept the thruster working.**
`setControlsSafe()` cleared the manual direction and nothing else, so the urgent
release intent still carried `thrusterMode = HOLD` and the live trim. In HOLD, HH
does not follow a direction at all — it controls toward the trimmed setpoint — so
`thruster = OFF` said nothing to it, the station still qualified as a live
source, and the thruster kept running until stale eviction about a second later.
A lifecycle path whose whole promise is an immediate safe release was not
delivering one for the thruster.

The root cause is better than the symptom: **`:core` already had
`ClientIntent.safe()`, tested, encoding MANUAL + OFF + trim 0 — and the app never
used it.** Two hand-rolled near-copies existed instead (`setControlsSafe()` and
the teardown intent), and both got the thruster wrong in the same way, because
both preserved the live mode and trim. Teardown now calls `ClientIntent.safe()`;
`setControlsSafe()` sets the same tuple and updates the UI to match, so on
resuming, re-entering HOLD is a deliberate act rather than a state the display
claims while the boat was told otherwise.

And the test that should have caught it asserted four of the five fields.
`the safe intent commands nothing` checked port, stbd, thruster and trim — not
`thrusterMode`, the one field that was wrong. Now pinned, with the reason: `OFF`
is not a safe direction if the mode ignores directions.

**A data race in the generation guard I added an hour ago.** `send()` runs on an
arbitrary `Dispatchers.IO` worker and urgent sends deliberately do not queue, so
several are genuinely concurrent — and `authTarget`, `authGeneration` and
`schemeIndex` were plain unsynchronised fields. No happens-before between one
worker bumping the generation and another comparing against it, and
`schemeIndex += 1` is a read-modify-write that can lose an update. The guard
could be defeated by exactly the interleaving it was written to prevent. Now
under a plain lock: retarget-and-capture is one atomic step, and the 401 path
re-checks the generation inside the lock rather than trusting the earlier read.
The HTTP call stays outside, so nothing is serialised that was not before.

Pattern worth naming, third instance today: I keep writing a guard for the
scenario I am picturing and not for the state space around it. Direction but not
trim; safe-value but not when; a generation counter but not the memory model it
lives in.

`:core` 150/150, `:app` 17/17, `assembleDebug` green.

## 2026-07-26 — I took the plugin down on the boat, and the app blamed the token
Session: PR #15 comment watch, interrupted by a live failure

The owner reported the Android station looping on "rejects this device's access
token (HTTP 401) ... likely revoked or expired", with Signal K happily issuing
fresh tokens that changed nothing. It had worked at read/write shortly before.

**My fault, from 9821da6 an hour earlier.** Extracting `suspendClock.cjs` out of
`index.cjs` added `require('./suspendClock.cjs')` — and `package.json`'s `files`
list was not updated. So every installed copy shipped an `index.cjs` whose first
act was to require a file that was not in the tarball: `MODULE_NOT_FOUND` at
load, plugin never loads, no routes registered, and
`POST /plugins/signalk-drive-remote-controller/intent` falls through to
signalk-server's admin-only `/plugins` gate. 401. The station then reported the
operator's perfectly good token as revoked.

Diagnosed against the real server rather than guessed, and the sequence is worth
keeping because my first hypothesis was wrong:

| probe | result | reading |
|---|---|---|
| `GET /signalk/v1/stream` | 426 Upgrade | token valid, auth fine |
| `PUT` a path with no handler | 405 "PUT not supported" | device really has read/write — past the gate, refused by the handler |
| `POST .../intent` | 401 | refused by the security gate |
| same with `JWT` | 401 | not an auth-scheme issue |

I concluded from that the deployed plugin predated the readwrite route
registration. Wrong: the owner said it had worked at read/write days before,
which no old-plugin theory survives. The 401 body is the generic `/plugins`
denial, which is *also* what an unregistered route returns — that ambiguity is
what sent me down the wrong path. `npm pack --dry-run` settled it in one command.

Two things this says about the failure mode, both worth remembering:

1. **Nothing local fails.** 238 tests green, `tsc -b` clean, the plugin runs fine
   from a checkout. The break exists only in the packaged artefact.
2. **It surfaces as an auth error naming the wrong component.** The station's
   message accuses the operator's token. Nothing in it points at the plugin.

Fixed by adding the file to `files`, and guarded by `test/packaging.test.ts`,
which resolves local requires transitively from `main` and asserts every one is
shipped. It fails with exactly `[ 'suspendClock.cjs' ]` against the broken
manifest. A second case asserts the walk really reaches the graph, so the check
cannot pass by finding nothing.

Owner action: redeploy the plugin and restart it. No admin grant needed — the
server is 2.30.0 and the route registers at read/write once the plugin loads.

## 2026-07-26 — A check/use gap inside the fix for a check/use gap
Session: PR #15 comment watch

Codex on 93cfa75: `isStale()` took the lock, answered, and released it — then the
`when` branch built the result outside. A retarget landing in that window
returned an `Ok` or `Unauthorized` from the OLD server classified as current, and
`StationViewModel` applied it to the new session's `TokenHealth`: overlapping old
refusals helping discard a working new token, or an old success clearing genuine
current ones. Guarding `schemeIndex` was not enough, because the **verdict** is
what the caller acts on.

Classification now happens inside one locked block together with the generation
comparison. And the `isStale()` helper is gone rather than left unused: a helper
that takes a lock, answers and releases invites the caller to act on the answer
afterwards, which is a check/use gap by construction. That accessor was not
incidental to the bug, it was the bug's shape.

Same lesson as the arbiter gate, in a different register: I keep protecting the
state and not the decision derived from it. The trim, the liveness, the first
sighting, `schemeIndex` — each time the guarded thing was one step short of the
thing that mattered.

`:core` 150/150, `:app` 17/17, `assembleDebug` green.

## 2026-07-26 — Invalidate on session change, not on next send
Session: PR #15 comment watch

Codex on 1bd2b17: the generation only advanced inside `send()`, so during setup —
choosing a server with no stored token, then waiting for approval, sending
nothing — the OLD server remained the current target. Its delayed reply
classified as current and landed on the new session: an old 401 ending a setup
that was going fine, or later counting against a token just issued.

`IntentPoster.abandonInFlight()` now bumps the generation and clears the target,
called from `useServer()` where the new session actually begins.

**Not** called from `endSession()`, and that is the interesting part. My first
pass wired it into all three places that reset `TokenHealth`, which would have
been a regression: `endSession()` runs immediately after `teardown()` has
LAUNCHED the final all-safe intent, so resetting the probe under it would send
that release with `schemeIndex` back at 0 — on a JWT-only server it 401s, and the
release is never retried. The teardown release is the thing that tells the
arbiter this station let go; degrading it to fix a classification bug would have
been a bad trade. Caught by reading the ordering in `endSession()` before
trusting the symmetry, which is the check I have been failing all day.

Two tests, one of which fails without the fix. `:app` is 19 cases now.

`:core` 150/150, `:app` 19/19, `assembleDebug` green.

## 2026-07-26 — A session nonce, because a restart and a replay are the same packet
Session: PR #15 comment watch

Codex on 519e505: a process restart inside `staleTimeoutMs` meets the record it
left behind. `clientId` is persisted — it must be, the Signal K token is issued
to that device id — while `seq` and the counters live in the ViewModel and begin
again at 0. So the new session's packets carry a LOWER seq, hit
`seq <= rec.lastSeq`, and are discarded whole: HTTP 200 while the station's STOP
does nothing and the retained command stands until eviction.

The earlier fix (c482b0a) only covered the no-record case. A retained record
bypassed it entirely.

**Why a nonce rather than inferring it.** Within a session a delayed packet also
arrives with lower counters, so "went backwards" cannot separate a restart from a
replay. Guessing restart lets a replay re-fire an old edge; guessing replay is
what swallows the STOP. There is no safe default — the ambiguity has to be
removed, not traded. `ClientIntent.session` is per-launch, distinct from the
persisted `clientId`.

Implemented by **deleting the record and falling through to the first-sighting
path** rather than resetting baselines in place. That path already gets a new
session right — baselines without an edge, STOP honoured on the first packet,
commands masked for absent units, counters banked — and is tested. A second copy
of those rules could only drift from it. Same lesson as `ClientIntent.safe()`
this morning: use the tested definition, do not restate it.

Two things worth recording about scope:

- **The browser needs nothing.** `App.tsx` does `useState(makeClientId)`, so a
  reload already mints a new `clientId` and reads as a first sighting. Only the
  phone has the problem, and only because it must persist its id. Checked rather
  than assumed symmetric — the temptation was to add the field to both.
- **Deploy order does not matter.** A missing `session` disables the check for
  that client, so an old app on a new plugin behaves exactly as before rather
  than erroring, and a new app on an old plugin sends a field that is ignored.

Three plugin tests; two fail with the detection disabled. The third pins that the
nonce did not weaken the ordering gate — a replay from the SAME session is still
discarded. The Kotlin field-set test now includes `session`, which is the point
of pinning field names as literals.

Plugin 241/241, `:core` 150/150, `:app` 19/19, `assembleDebug` green.

## 2026-07-26 — Session ids are unordered, and a restart was keeping the arm
Session: PR #15 comment watch

Codex on 6daca57 — my own fix from twenty minutes earlier. Session ids are uuids,
so "different from the one on record" cannot mean "newer". `s1 -> s2 -> late s1`
read the third packet as yet another restart, wiped the new session's record and
**republished the dead process's `forward`**. A heartbeat from a process that has
already exited could move a machine.

Fixed by retaining superseded session ids per client and discarding their packets
outright, like a replay. Bounded twice — 4 ids per client, MAX_TRACKED_CLIENTS
clients, both aged by last use with the same delete-then-set idiom as
`_rememberCounters` — so repeated relaunching cannot grow it.

**And writing the test found a second defect neither of us had named.** My first
assertion failed: after the relaunch the station was still ARMED. `holder` is
keyed by `clientId`, which survives a restart, so deleting the record left the
arm in place — the boat stayed armed across a relaunch that no operator
authorised, with the new process at `armReq = 0` having pressed nothing. That is
exactly what edge-not-level arming exists to prevent. The arm is now released
when the session changes.

Worth noting how it surfaced: not from reading the code, but from writing down
what the state SHOULD be after a restart and finding the code disagreed. That is
the method I keep failing to apply and then rediscovering.

**This is the fourth consecutive round where my fix produced the next finding**
(recovery gate -> trim -> liveness -> first-sighting; then nonce -> superseded).
The through-line is unchanged: I patch the case in front of me instead of writing
out the state space. Recommending to the owner that the next step is a deliberate
pass over the arbiter's session/counter state machine rather than another patch.

Plugin 243/243, `tsc -b` clean.

## 2026-07-26 — Replacing the nonce with an ordered generation
Session: PR #15 comment watch

Two more findings on my own session work, and together they said the design was
wrong rather than incomplete.

1. **Legacy record (P1).** `rec.session == null` skipped detection, so the
   mixed-version path -- old app's record, upgraded app's first packet -- kept the
   original defect: seq gate filters, STOP returns 200 and does nothing.
2. **Forgotten superseded id (P2).** Past 4 relaunches, or eviction from the
   32-client map, a closed id was forgotten. A late packet from it would then
   clear the real holder AND mark the RUNNING session superseded, silently
   rejecting that station's STOPs from then on. My comment had reasoned the bound
   was "the safe direction". It was not.

The second is the interesting one: the bound was not a tuning problem, it was
evidence the representation was wrong. Uuids are unordered, so "different" cannot
mean "newer", so dead ids have to be remembered, so the memory has to be bounded,
so something eventually gets forgotten. Each step follows from the first.

Replaced with a **persisted, strictly increasing launch generation**. Nothing to
remember and nothing to forget: `session < rec.session` is closed for good by
comparison. The whole `_supersededSessions` map, its per-client cap, its outer
cap and the helper that maintained them are deleted -- the fix made the previous
fix's machinery unnecessary rather than adding to it.

Written into the arbiter as an explicit verdict table over (incoming, on record),
covering all six combinations including the two null cases the previous version
got wrong. That is the state enumeration I have been saying was needed, done for
this cluster.

Android: `SettingsStore.nextSessionGeneration()`, `commit()` not `apply()` --
the value must be on disk before the first intent carries it, or a
crash-and-relaunch could reuse a generation the server has already seen, which is
the one thing an order cannot survive. Clearing app data resets it to 1 and also
clears `clientId`, so the station returns as a different device.

Plugin 245/245, `:core` 150/150, `:app` 19/19, `assembleDebug` green.

## 2026-07-26 — The kill switch could grant the token
Session: PR #15 comment watch

Four findings. Three fixed; one deliberately left open because my attempt broke
seven existing safety tests and I am not confident enough to rewrite them.

**Fixed — STOP could ARM the boat (P1, the worst thing found today).** The code
relied on statement order for "an intent carrying both a new disarm and a new arm
resolves to disarmed": disarm ran first and set `holder = null`, and the arm gate
is `holder === null` — so the disarm HANDED the arm its precondition and the same
packet armed. The comment above it asserted the opposite of what the code did.
Made directly reachable by my own change letting STOP bypass the send lane: an
urgent STOP overtakes a not-yet-delivered ARM and the snapshot carries both
counters already incremented. The disarm edge is now computed once and suppresses
arm processing for the whole intent; the arm baseline still clamps forward, so the
edge is consumed rather than queued and re-arming needs a fresh press.

**Fixed — the session watermark died with the record (P1).** The generation lived
only in the evictable `_clients` record. After an eviction a delayed packet from
an older session met `rec == null`, was taken as a first sighting, and a baseline
followed by an ARM edge could re-arm and move a machine after the live session had
gone quiet. Now a separate per-client watermark that outlives eviction, bounded
and aged like `_counterMemory` — with the same residual stated rather than
implied.

**Fixed — a verdict could be applied to the wrong session (P2).** I had declined
this earlier, saying the classification was already inside the lock. That was
beside the point: the gap is the hop back from `Dispatchers.IO` to the caller.
Verdicts now carry their generation and the ViewModel validates it where it acts,
on the main dispatcher that also runs every session change — so the check and the
state it guards cannot be interleaved.

**NOT fixed — releasing the holder on every both-units-dead transition (P1).**
The request is right in intent: a complete outage of both output units should cost
the station its authority, including a gap that opens and closes between ticks.
But putting the release in `_refreshUnitLiveness` broke seven existing tests, and
the reason matters. The pre-update refresh added earlier today (0322f5b)
deliberately evaluates liveness against the PREVIOUS arrival, so inside
`onRxTelemetry` both units transiently compute dead even when one is about to be
marked live. A holder release layered onto that fires on a state known to be
about to change.

I could argue the tests feed telemetry unrealistically — real units republish
every 250 ms, so both-dead would not transiently hold. That argument is exactly
the shape of the reasoning that has gone wrong repeatedly today, and acting on it
means rewriting seven safety tests on arming semantics. It also points at a real
design answer rather than a placement tweak: "a both-dead interval OCCURRED" is a
STATE, like the release quarantine, not an instantaneous condition. Left for the
owner.

Plugin 248/248, `tsc -b` clean, `:core` 150/150, `:app` 19/19, `assembleDebug`
green.

## 2026-07-26 — A generation that was never written, and a bound I chose to accept
Session: PR #15 comment watch

Two findings, both on code from fifteen minutes earlier.

**Fixed — the generation was used whether or not it persisted (P2).** I wrote a
comment saying the value must be on disk before the first intent carries it, then
ignored what `commit()` returned. Codex's consequence is sharper than the one I
had reasoned: a reused generation makes the arbiter see `session === rec.session`,
treat the relaunch as the SAME session, and filter the new packets behind the
retained sequence baseline -- STOP included. The exact bug generations were added
to fix.

`nextSessionGeneration()` now returns `Long?` and the field is OMITTED from the
wire when null. That degrades to the pre-generation path the arbiter already
handles and tests, rather than proceeding on a value known to be a lie. Chosen
over the stricter reading of "fail closed" -- refusing to open a control session
at all -- because bricking a helm station over full storage is its own hazard, and
that trade is the owner's to make rather than mine. Flagged as such.

**NOT changed -- the watermark bound (P1).** This is the residual I documented one
commit ago and accepted. Codex is right that documenting it does not close it:
past 32 session-aware client ids, a displaced watermark lets two queued packets
from a dead generation (baseline, then an ARM edge) re-arm and restore an obsolete
command.

What I know about the exposure: it needs two in-flight packets from an exited
process AND 32 distinct session-aware client ids displacing that station's
watermark inside the in-flight window, on a route already behind Signal K auth.
What I do not want to do is pick a third representation for this unsupervised,
fifteen minutes after picking the second -- the last two rounds were exactly that
and each produced the next finding. The options (unbounded with a different abuse
defence, a time-bounded window, timestamp-derived generations needing no memory)
trade different risks, and choosing among them is the owner's call.

Left open with the analysis on the thread. That is now two open items -- this and
the both-units-dead release -- both of which are design decisions rather than
defects awaiting a patch.

`:core` 151/151, `:app` 19/19, `assembleDebug` green.

## 2026-07-26 — My "safe" fallback bricked the station; and knowing when to stop
Session: PR #15 comment watch

**Fixed — the null-session fallback was worse than the bug it replaced (P1).**
Fifteen minutes after choosing it, and while explicitly reasoning that "bricking a
helm station over full storage is its own hazard", I had implemented exactly that.
Returning null on a failed persist does not enter a self-clearing legacy path: the
arbiter closes a null session whenever a record OR the watermark holds a numbered
one for that client, and the watermark outlives eviction. An established station
would be rejected indefinitely -- every request, including STOP.

The failure of reasoning is precise and worth recording: I evaluated the
`rec != null` row of the verdict table and forgot the watermark row, which I had
added myself in the same session, in the same file, specifically to close a
different hole. Writing the table down did not stop me reading only half of it.

Replaced with `max(stored + 1, System.currentTimeMillis())`. The generation is
strictly increasing across launches whether or not the write lands, because the
clock has moved on by the next one; the stored value is a FLOOR guarding against
a backwards clock. No null, so no lockout; no reuse, so no seq filtering.
Persisting is still attempted, it is just no longer load-bearing.

**NOT fixed -- restoring the scheme a success proved (P2).** I implemented the
obvious version (pin the scheme on 2xx) and wrote a test, and the test failed:
pinning only helps when the success arrives last, and a stale 401 landing after a
success still moves the index away from the proven scheme. Chasing that leads to
"once a scheme is proven, stop probing" -- which then breaks `isDead`, because it
requires EVERY scheme to have been refused and only one would ever be tried. That
is a third interacting change to the same negotiation state machine, and the last
two I made there each produced the next finding.

So I backed the partial fix out rather than shipping a half-measure that passes
its own test by describing a different scenario than the report. Left open with
the analysis.

Three items now wait on the owner, all design decisions rather than defects: this,
the watermark's client-cap bound, and the both-units-dead holder release.

`:core` 150/150, `:app` 19/19, `assembleDebug` green.

---

## 2026-08-15 — Pin review: two boot-time traps and an unflagged safety input
Session: hardware pin-location review

Owner asked whether the pins were in safe places. Checked all three firmwares
against the SH-ESP32 hardware doc, the ESP32's strapping/boot behaviour and the
actual `pinMode`/`digitalRead` sites. Most of the map was already careful — the
two classic traps (GPIO12, which stops the board booting if high at reset, and
GPIO5) were correctly avoided, and the 36/39 external-pull-up design was right.
Three things were not.

**GPIO14 and GPIO15 emit a signal at boot, before `setup()` runs.** Both were in
use and both mattered:

- HH read the BNO086's UART on 14. The ESP32 drove that pin as an output at boot
  while the BNO's push-pull TX drove it too — real driver contention on every
  reset. → **GPIO23**.
- RX drove its starboard servo from 14, so a glitch could land in the 1–2 ms
  pulse window before `attach()`. The released engage relay covers this on the
  finished boat, but only if its pull-down is actually fitted, and not at all on
  the bench before the clutch exists. → both servos to **GPIO16/17**, the I2C
  header, which also carries GND on the same connector.
- HH's reserved BNO reset sat on 15, a strapping pin that must be HIGH at boot —
  and `hh-wiring.drawio` was actively inviting someone to wire an active-low
  reset there ("RST ← GPIO15 (optional)"). → **GPIO22**.

**ENGAGE had no polarity flag — the one that could have hurt.** Every other opto
crossing in this project carries one (`kRxArmOutputActiveHigh`, `kOutputActiveHigh`)
because the stage between connector and GPIO is not trusted sight-unseen. ENGAGE
— the *unconditional* local arming authority for the thruster — hardcoded
`== HIGH` at its `digitalRead`. Hat Labs' public docs do not state the opto
input's sense, and optocoupler input stages commonly invert. GPIO35 is input-only
with no internal pull, so there is no firmware backstop: if it inverts, an
unwired ENGAGE reads asserted and HH arms itself into HOLD on an empty connector.
Added `kEngageActiveHigh` and put the unwired-reads-disarmed check at the *head*
of SAFETY.md's thruster list. **The default is still an assumption — it must be
metered before HH is trusted.** Logged as MEASUREMENTS.md item 11.

**Two documentation asymmetries, both on GPIO33.** The "this pin floats from
reset, give the driver its own pull-down" note existed only on RX's copy; HH's
ENABLE — same pin, same window, gating a bow-thruster contactor — had one bare
line. Mirrored it, and added the hold-in-reset scope check the thruster list was
missing. RX had that check; HH did not.

**Deadman moved 39 → 19 as a bonus.** GPIO19 has a real internal pull-down, so an
unwired deadman now reads a definite not-held instead of floating. That dissolves
the reason `kDeadmanWired` had to exist as a safety guard; the flag stays, but
only because reporting "held" from a switch nobody installed is a lie the FSM
would act on.

**Deliberately not changed:** the lever-neutral sensors stay on 36/39. An
internal pull-up would be a second line of defence, but it would also mask a
missing or open external 10 k — and the unplug-the-sensor check is the only way
anyone ever finds that out. On the pin granting arm permission, a fault you can
prove beats one you have quietly padded.

**Pin budget, now written into `config.h` as tier A/B/C/D rules.** TX and RX
between them consumed every clean header pin, because one wiring diagram covers
both panels. Moving the servos to the I2C header freed 13 — so the shared TX/RX
diagram now has exactly two spares, 13 and 4 (1-Wire). HH has plenty. Nothing
costs N2K on any unit; RX gives up I2C, which it does not use.

**THE TWO BENCH-PROVEN BOARDS MUST BE REWIRED BEFORE THEY ARE FLASHED AGAIN.**
This is not a firmware-only change. HH's BNO086 TX is physically on GPIO14 and
was confirmed there on 2026-07-01 (live yaw/pitch/roll tracking real handling);
RX's servos were watched moving on GPIO13/14. Flashing this build onto either
board without moving the wires gives a dead IMU (HH faults on RVC timeout —
which is at least the safe direction) and two servos that never receive a pulse.
Move HH's BNO TX to GPIO23 and RX's two servo signal leads to the I2C header
(16/17) first. HH's reset line and the deadman were never wired, so those two
moves cost nothing physical.

Native suite 241/241, all three firmware builds green. Diagrams regenerated from
the drawio sources. Nothing here has been on hardware — the ENGAGE polarity in
particular is a check to run, not a result to trust.

---

## 2026-08-20 — A pulse cap for the case the control law cannot end, and the knob I chose not to add
Session: switcher pulse cap

`max_on_s` landed: a hard ceiling on one continuous thrust, checked in
`Switcher::Update` before the normal release test. Default 2 s, live-tunable and
persisted like the rest of the switcher knobs, bounded to [0.1, 10] s — a
deliberately tighter ceiling than `kMinOnOffMaxS`'s 30 s, because a 30 s max-on
is no protection at all on an S2-rated motor. `SetTunables` raises it to
`min_on_s` if it would land below, since a cap the timing gates can never reach
is not a cap.

The gap it closes: the only exit from thrusting was `s` crossing `off_thr`. A
boat that cannot turn — pinned by wind or current, a fouled thruster, a dead or
wrong-signed yaw rate — leaves `s` stuck at `e` and the motor running with
nothing to stop it. Duty inhibition does not cover that, and it took reading
`Update()` to see why: `duty_inhibited_` is consulted only on the transition out
of OFF, so it can refuse the *next* thrust but can never interrupt one already
running. Without an OFF window the S2 limiter has no way in. Capping the pulse
manufactures that window — sustained load becomes a 2 s on / 0.5 s off train,
~80 % duty, which crosses `duty_max` and hands the thruster to the limiter.
That hand-off is the intended outcome, not a side effect.

**Asked today whether the cap should shorten when the hold error is small —
"dynamic driving". Decided no, and left it fixed.** The reasoning is worth
keeping because the question will come back: `max_on_s` is not a feel knob. On a
normal small-error hold the pulse already ends far short of 2 s on the lead
variable, so scaling the cap down would change nothing there. And in the case it
exists for, the pulse-start error is irrelevant — what matters is that `s` stays
stuck, which a small-error hold can fall into the moment the boat stops
answering. Shortening the cap by error would weaken the protection exactly where
it is the only thing acting.

A genuine proportional / duty-modulated control law is a different and larger
change: it reaches into the safety-critical switching state machine and has to
be reconciled with `min_on_s` anti-chatter, the duty EMA, and the mandatory
reversal dwell (SAFETY.md thruster invariant 7). Not folded into a constant.
Revisit after more time on the water — the owner's call was more testing first.

Native suite green.

---

## 2026-08-20 — The fused heading really was lagging; the cause was not the GNSS
Session: heading-lag investigation

Owner reported `fusedHeading` lagging badly when the bow was swung by hand and
asked for live monitoring. Captured three runs off the Signal K delta stream
(TCP 8375, `policy: instant`) with local arrival timestamps: 20 s at rest, 240 s
of slow sweeps, 150 s of lock-to-lock swings as fast as the bow could be pushed.

**I got the direction wrong first and had to correct it.** My initial
cross-correlation said fused *led* `navigation.headingTrue` by 0.3 s; I had the
shift sign inverted. Two independent methods then agreed it is a lag: the
corrected cross-correlation, and regressing the error against the turn rate,
which gives τ = +0.295 s (slow run) and +0.244 s (fast run), correlation −0.51
on both. The direction split makes it unarguable — during starboard turns fused
sits low, during port turns it sits high, symmetrically.

**The lag is the yaw-rate low-pass, and the arithmetic is exact.** Filtering the
rate with time constant τ and integrating it makes `fused/true` a first-order lag
of the same τ, whose steady-state error under a constant turn rate is precisely
`-τ * rate`. `YawRate`'s `lpf_tau_s` was 0.3 s. Measured τ, 0.24–0.30 s. Nothing
else needed explaining.

That filter was guarding against noise that is not there: the BNO086 RVC
rate measures **0.034 °/s rms at rest**. At 0.1 s the noise is ~0.06 °/s, which
contributes 0.06° to the Switcher's lead variable (Td = 1.0 s) against a 2.0°
on-threshold. Lowered to 0.1 s, and promoted out of a default argument into
`ControlStep::Cfg` and `config.h` — a knob this load-bearing should not be
findable only by reading a constructor signature.

**Second, separate defect: the correction gain was rate-dependent and the rate
was wrong.** `navigation.headingTrue` was arriving at **1 Hz**, not the ~5 Hz
this code assumed. The assumption came from reading `kSkHeadingListenDelayMs`
(200 ms) as the fix rate — it is a subscribe *throttle*, and says nothing about
how fast the receiver emits. So the documented "~2 s" time constant was really
~10 s, and a 1–2° offset took 10–15 s to wash out after the bow moved. Visible
in the trace: an error of −0.90° at t=97.6 s took 15.4 s to fall under 0.3°.

Fixed structurally rather than by re-picking a number. `k_corr` (per-fix gain) is
now `tau_corr_s` (seconds), with `k = 1 - exp(-dt_gnss_s / tau_corr_s)` derived
from the actual interval between accepted fixes. The tuning no longer depends on
the fix rate being what anyone assumed. Owner separately reconfigured the UM982
to 10 Hz mid-session (`UNIHEADINGA 0.1` + `SAVECONFIG`), verified at the server
at 10.24 Hz — and `kSkHeadingListenDelayMs` went 200 → 50 ms, because a 200 ms
throttle would have decimated that new 10 Hz stream straight back to 5 Hz.

**Two suspects cleared, both by measurement.** The `max_rate_dps = 10` GNSS
plausibility gate never fires: the fastest this bow can be swung by hand is
**3.9 °/s**, a 2.5× margin. And there were zero rejected fixes across both runs —
`gnssAge` never exceeded 1.138 s in 3900 samples.

**`t_fresh_s` 1.5 → 2.0 s, and the reasoning changed mid-task.** At the 1 Hz I
first measured, 1.5 s left only 0.47 s of margin over a 1.03 s worst-case age —
one dropped sentence from refusing to arm. I had planned ~3 s. Once the receiver
went to 10 Hz that premise dissolved (worst-case age 0.403 s), and 3 s would have
been needlessly slow to notice a real GNSS loss. 2.0 s is sized to survive either
rate: ~20 fixes of margin at 10 Hz, ~1 s of margin if it ever falls back to 1 Hz.

**A latent bug found while reading, not reported by anyone.** The um982 plugin
publishes `navigation.headingTrue` from *two* sentences (`$GNHPR` and
`#UNIHEADINGA`) ~1 ms apart with the same value. Under the old fixed gain, any
such pair straddling a tick boundary spent two full corrections on one
measurement. Under a time constant, 1 ms against a 2 s τ is a gain of 5e-4 —
nothing. There is a test for it.

Five tests added, and one of them earned its keep immediately: my first version
of the rate-independence test stepped the GNSS heading 10° between fixes 100 ms
apart, and the plausibility gate correctly rejected it as 100 °/s. The test was
wrong, not the code. Rewritten to introduce the error through gyro drift, which
is both the real scenario and the only one the gate permits.

Native suite 246/246 (241 + 5). All three firmware builds green.

**Flashed and verified the same session**, against the same capture method and
the same hand-swung bow, so the before/after is like for like:

| | before (slow) | before (fast) | after |
|---|---|---|---|
| tracking lag | +0.295 s | +0.244 s | **+0.145 s** |
| p95 abs error | 1.14 deg | 1.57 deg | **0.53 deg** |
| max abs error | 1.97 deg | 2.01 deg | **0.78 deg** |
| settling after a stop | up to 15.4 s | up to 19.9 s | **0.0 s, all six stops** |
| cross-correlation residual | 0.458 deg | 0.535 deg | **0.105 deg** |
| peak swing rate | 3.13 deg/s | 3.89 deg/s | 4.33 deg/s |

The owner swung HARDER on the verification run (4.33 deg/s, the fastest of the
three) and the error still came out 2.5x smaller. The settling row is the
clearest result: before, the estimate arrived at a stop 0.9-1.0 deg off and
crawled in over 5-20 s; after, the worst error at any of the six stops is
0.24 deg, already inside tolerance with nothing left to settle.

At rest afterwards: rate noise 0.053 deg/s rms (predicted ~0.06), gnssAge max
0.206 s against the new 2.0 s trust window, 10.95 Hz of fixes reaching the
filter, and fused within 0.02 deg of the GNSS.

Predicted lag was ~0.1 s and it measured 0.145 s. That gap is accounted for
rather than mysterious: ~0.1 s is the LPF itself, telemetry publishes at ~15 Hz
while the analysis grid is 10 Hz (~0.05 s of apparent lag on its own), and the
GNSS report carries its own pipeline latency. **Stop tuning here** - pushing
lpf_tau_s lower would chase measurement resolution rather than real lag, and
would spend noise budget on something invisible from the helm.

Still uncharacterised, and unchanged by this session: `max_rate_dps = 10` has no
HOLD-mode data behind it. Everything measured here is DISARMED and hand-swung.
The margin against a hand swing is 2.3x; what the thruster does under HOLD is
not known.

*Toolchain note:* PlatformIO was not installed on this machine. Installed via
pip; the ESP32 build additionally needs a venv, and `python3 -m venv` fails here
(no `python3.10-venv`). Worked around without sudo by creating
`~/.platformio/penv` with `virtualenv` instead.

---

## 2026-08-20 — Merging the pulse cap onto the fixed heading filter
Session: switcher pulse cap (merge)

`main` picked up the heading-lag fix (PR #19) and, separately, the SensESP fork
line. Merged `main` into this branch. Every code file auto-merged; the two
conflicts were both bookkeeping.

**The cap needs no re-tuning after the lag fix, and it is worth writing down
why, because the instinct says otherwise.** The release condition is a rate test
in disguise — `e - Td*r < off_thr` solves to `r > (e - off_thr) / Td` — so
making `r` respond faster (LPF 0.3 → 0.1 s) means the loop reaches the release
threshold *sooner*. Normal pulses now end further short of the 2 s cap than they
did when 2 s was chosen. The cap fires less often on merged code, not more, so
it stays at 2 s.

What the lag fix does *not* do is close the gap the cap exists for. Better
fidelity in `r` is worth nothing when the true `r` is zero because the boat is
not answering — pinned, fouled, or reading a dead rate. `s` still sticks at `e`
there, and the cap is still the only exit.

`t_fresh_s` went 1.5 → 2.0 s on the heading branch, which lets a hold coast
slightly longer on a stale correction. Small, but it points the same way: a
bounded pulse is the backstop for a hold running on an estimate that is quietly
going wrong.

**Dropped from this branch:** its own `platformio.ini` commit. `main` made the
same fork switch independently (`e27a752`), differing only by a `.git` suffix —
took `main`'s spelling. While there, corrected the comment above `lib_deps`,
which still read "Upstream SensESP … all three firmwares deliberately track
upstream together" after the line beneath it had stopped pointing at upstream.
It now says which fork and branch, and notes that a branch ref moves under you —
the unpinned-dependency warning matters more, not less, once the ref is a
branch name.

Journal conflict resolved by ordering both 2026-08-20 entries by when the work
happened (cap first, heading second), and the cap entry gained the `---` and
`Session:` lines every other entry carries.

Native suite 253/253 (241 + 5 heading + 7 switcher); the two test files are
independent and neither needed touching. **Still not on the water.** The cap has
never fired on hardware, and the behaviour it produces under sustained load — a
2 s on / 0.5 s off train at ~80 % duty, handing over to the S2 limiter — is
new in exactly the conditions a harbour trial can create (holding against wind,
or a boat against a line). That is the thing to watch on the next trial.

---

## 2026-09-09 — Build documentation, a disclosure policy, and a CRA self-assessment
Session: claude/building-security-cra

Added the three documents the project had never had: `docs/BUILDING.md`,
`docs/SECURITY.md`, and a `SECURITY.md` at the repository root. Modelled on
[crew-radio](https://github.com/KEGustafsson/crew-radio), which splits the same
material the same way — procedures in `docs/BUILDING.md`, threat model plus a
CRA Annex I mapping in `docs/SECURITY.md`, and a short disclosure policy at the
root where GitHub looks for it.

**BUILDING.md is written against what the build actually does, not against what
a reader would assume.** Three findings were worth the reading it took:

- **`-t upload` goes over the network, not over USB.** `[env]` sets
  `upload_protocol = espota` and each environment carries its board's IP, so the
  documented flash command silently requires being on the boat's network with
  the board powered. A virgin board — the case a newcomer hits first — cannot be
  reached that way at all.
- **There is no command-line escape from that.** `upload_port` and
  `upload_flags` have `PLATFORMIO_*` environment overrides; `upload_protocol`
  does not. Checked against PlatformIO Core 6.1.19's own `options.py` rather
  than guessed: `upload_port` carries `sysenvvar="PLATFORMIO_UPLOAD_PORT"`,
  `upload_protocol` carries no `sysenvvar` at all. This `pio run` has no
  `--project-option` flag either (checked `--help`). So the serial procedure is
  "comment two lines out of `platformio.ini`", and the doc says that instead of
  offering an override that would fail. The tidier fix — a dedicated
  `[env:*_serial]` — is named in the doc and deliberately not made, since it is
  a build-config change to a safety-critical project that nobody asked for.
- **`pio test -e native` needs a host compiler, which Windows does not have.**
  Documented with the `winget` line for WinLibs MinGW-w64, verified against the
  toolchain actually on this machine.

Also stated where it will be read: `npm pack` without `npm run build` first
produces a plugin whose server half works and whose UI is missing, because
`public/` is gitignored but *is* in `package.json`'s `files` list.

**SECURITY.md's threat model is deliberately unflattering.** §3 "The known
weaknesses" lists seven, in plain words: committed WiFi and OTA credentials that
are also in git history, one shared static OTA password as the entire barrier to
reflashing a board wired to a clutch and a thruster contactor, nothing signed
anywhere (no secure boot, no release-signing config, no SBOM, no attestation),
the SensESP branch ref that moves under every build, no dependency or code
scanning configured, cleartext on the boat LAN, and `plugin.*` being
single-writer only by convention since Signal K cannot enforce per-path
ownership. §4 names the three assumptions the whole remote-authority model rests
on, the load-bearing one being that the boat's Signal K server has security
enabled — with it off, anything on the LAN can publish `plugin.*` directly and
§2 evaporates.

The framing that made the document work: the asset here is **authority over
machinery**, not data. Confidentiality barely appears, availability of the
*stop* path outranks availability of the command path, and a station displaying
a stale reading as live is a security finding rather than a cosmetic one. The
floor under all of it is physical and does not depend on any network claim being
true — local controls win unconditionally, the drive unit refuses control unless
both levers prove neutral, and everything falls to neutral or off rather than to
last-commanded. Someone who wins every row in the threat table still does not
beat a person at the helm. That is stated at the top of both security documents
so a reader does not mistake the list of gaps for a list of ways to move the
boat.

**The CRA mapping is a self-assessment, marked honestly.** Regulation (EU)
2024/2847 does not apply: this is a private, non-commercial project for one boat,
nothing is placed on the market, and there is no open-source steward within
Article 24. Its Annex I is still the best available checklist for a device that
commands machinery. Of the thirteen Part I requirements, five are marked
**partial** and one — (2)(c) security updates — is a **gap**, because OTA is
manual, unsigned and authenticated by one shared static password. Part II fares
worse: no SBOM, no secure update distribution. The two rows that come out
strongest are (2)(b) secure-by-default and (2)(h) DoS resilience, which is
unsurprising: fail-safe *is* the design centre, and the CRA's availability
requirement happens to describe it. Dates confirmed against EUR-Lex and the
Commission's own summary rather than recalled: in force 10 Dec 2024, Article 14
reporting from 11 Sep 2026 (two days from this entry), everything else 11 Dec
2027.

**Nothing was verified by running it.** No build, no test suite and no flash was
run this session; the documents describe procedures read out of
`platformio.ini`, `package.json`, `libs.versions.toml`, `ci.yml` and the four
existing READMEs. The one thing checked empirically was PlatformIO's option
table, above. Every version number in BUILDING.md comes from a config file in
this repository, and the "verified with" lines are quoted from
`android/README.md` rather than re-confirmed.

Indexes updated: the project README's documentation table gained both files, its
quick-start now points at BUILDING.md for the full version, and its secrets
warning now names the rotation procedure and the fact that git history holds the
same credentials. The workspace root README gained a short section naming this
project and its four entry points, since a visitor landing on the fork otherwise
sees only Hat Labs' generic workspace README.

---

## 2026-09-09 — A release pipeline, and the reason firmware could not simply be published
Session: claude/building-security-cra (continued)

Added the release and scanning half of crew-radio's process set: a manually
triggered `release.yml` that builds, verifies, attests and publishes every
artifact this project makes; `codeql.yml`; `dependabot.yml`; Android release
signing and git-derived versioning; and a CycloneDX SBOM per artifact.

**The finding that shaped the whole design: the firmware binaries contain the
boat's credentials.** `src/{tx,rx,hh}/main.cpp` pass `SECRET_WIFI_SSID`,
`SECRET_WIFI_PASSWORD` and `SECRET_OTA_PASSWORD` to `set_wifi_clients()` and
`enable_ota()`, and `secrets.h` is committed with the real values. A published
`.bin` would therefore hand out the boat's WiFi password and the password that
authorises reflashing a board wired to a clutch and a thruster contactor, both
recoverable with `strings`. Publishing firmware was not a packaging problem; it
was a disclosure problem wearing packaging clothes.

Two things follow, and the second is the one that matters:

- The release job copies `secrets.example.h` over `secrets.h` before building,
  so a published image joins no network until its recipient configures WiFi
  through SensESP's portal and sets their own OTA password.
- **The copy is not trusted to have worked.** `scripts/check_no_secrets.py`
  reads the real values back out of *git* — not the working tree, which the
  workflow has just overwritten — and searches the actual bytes of each image
  for them, OTA image and factory image both. It never prints a value it
  matched; the log names only which `#define` leaked. Writing the copy step and
  calling it done would have produced exactly the class of failure this project
  keeps finding in itself: a proxy checked instead of the effect.

The guard is itself checked, in `ci.yml`, both directions: a fixture holding a
committed credential must fail and a fixture holding only the placeholder must
pass. A protection nobody exercises is a protection nobody knows about. Run
locally, it correctly rejected the dirty fixture and accepted the clean one.

Incidentally confirmed by that run, and worth knowing: three of the five real
values in `secrets.h` are the same string, so the OTA password and the WiFi
password are one secret, not two. Rotating one without the other is not a thing
that can be done.

**Verified by running it, not by reading the config.** The Android half was
exercised end to end on this machine:

- `assembleRelease` with no key produces `app-release-unsigned.apk`, 8.2 MB.
  That is the filename to document; it is not `app-release.apk`.
- With `DRIVEREMOTE_KEYSTORE`/`_PASSWORD`/`_KEY_ALIAS` set against a throwaway
  4096-bit PKCS12 keystore, it produces `app-release.apk`, and `apksigner
  verify --print-certs` reports the throwaway certificate. The keystore was
  created in the scratchpad and deleted afterwards.
- `:app:sbom` writes CycloneDX 1.5 with 103 components, each with the SHA-256 of
  its artifact, and `:app:printVersion` writes `0.147`.

The firmware and plugin halves of the workflow are **not** proven: no CI run has
happened. What was checked is that every `run:` block in all three workflows
parses as YAML and passes `bash -n`, that `npm sbom --sbom-format cyclonedx`
produces 184 components for the plugin, and that `scripts/sbom.py` produces a
valid CycloneDX from PlatformIO's `.piopm` metadata (17 components from the
existing libdeps). The factory-image step and the certificate check have never
run anywhere.

**Versioning moved from a hand-edited number to git.** `versionCode` is the
commit count and `versionName` is `0.<count>`, so the APK, the firmware assets
and the release tag cannot disagree; the workflow computes the same value
independently and fails if Gradle's differs. It stays below 1.0 on purpose. The
old `versionCode = 1` is far below the new 147, so this is an upgrade rather
than a refused downgrade for anything already installed.

**Three deliberate deviations from crew-radio, each with a reason:**

1. **The release is triggered by hand, not by a merge to `main`.** crew-radio's
   merge-is-the-release works because a mesh intercom that ships a bad build
   costs a conversation. This ships firmware for a clutch and a thruster
   contactor, on a system SAFETY.md says has not been commissioned and whose TX
   unit has never been flashed. Publishing on every merge, including one that
   fixed a typo, is not a sensible default. The workflow header carries the
   two-line change to adopt crew-radio's behaviour instead, so this is a setting
   rather than an opinion baked into the file.
2. **No `signalk-ci.yml`.** crew-radio's own comment on that file says the
   Signal K reusable workflow breaks as soon as any lockfile exists in the
   checkout, because setup-node's cache then looks for one at the repository
   root — which is why crew-radio deliberately has no `package-lock.json` in
   `sk-plugin/`. This project *does* commit one, and `npm ci` against it is
   worth more here than App Store indicators for a plugin nobody is publishing.
   Adding the workflow would mean deleting the lockfile to make it pass, which
   is the wrong trade.
3. **R8 stays off.** crew-radio shrinks its release build; this one sets
   `isMinifyEnabled = false` and the existing comment explaining why is still
   right. Turning it on means writing and testing keep rules for OkHttp, Compose
   and kotlinx.serialization first, and re-running the layout-floor suite
   against the shrunk build — none of which is release-pipeline work.

**CodeQL's C++ job does not build a firmware**, and that is the point: the
xtensa cross-compiler is not something CodeQL traces usefully, while
`pio test -e native --without-testing` compiles `lib/control_core/` with the
host compiler. That is simultaneously the code most worth analysing and the only
C++ here an ordinary toolchain can build. Five languages in the matrix: c-cpp,
java-kotlin, javascript-typescript, python, actions.

**Dependabot cannot see the dependency that matters most.** There is no
PlatformIO ecosystem, and it would not follow a branch ref if there were, so
`SensESP#fix_analog_input` still moves under every firmware build with nothing
watching it. The firmware SBOM in each release is now the only place a given
binary's resolved dependency versions are written down. Said plainly in the
dependabot config, in BUILDING.md 11.2 and in the CRA mapping rather than left
for someone to discover.

**The security documents were corrected, not merely extended.** Three claims in
them became false with this change and were rewritten rather than left standing:
"nothing is signed and nothing is attested", "no SBOM is produced", and "no
automated dependency or code scanning is configured". The CRA rows moved with
them — (2)(a) and (2)(c) improved, Part II's SBOM row went from gap to yes, and
secure distribution went from gap to partial. What did *not* move: the firmware
is still unsigned, the boards still have no secure boot, and the committed
credentials are still committed and still in git history. The "what would have
to change" list now strikes through the three done items rather than deleting
them, so the record of what changed stays readable.

### Follow-up (same day): CodeQL is correct and cannot run
The workflow went red on its first run. Two causes, one after the other, and
neither was the analysis:

1. **Missing `actions: read`.** Every job analysed to completion and failed at
   the SARIF upload with *Resource not accessible by integration*. That is the
   documented private-repository requirement for the CodeQL action. Added.
2. **Code scanning is not enabled on the repository**, which is the real
   blocker. `security_and_analysis` reads null on the API, and this repository
   is private — code scanning there needs GitHub Code Security, a paid add-on.
   Nothing in a workflow file can fix that.

**What the failed runs did prove**, and it is the part that could not be checked
locally: the `c-cpp` job's approach works. Building `pio test -e native
--without-testing` rather than a firmware let CodeQL scan **28 of 38 C++ files
and 21 of 31 C files** — the pure control core — and every language's queries
were interpreted before the upload was refused.

**Deliberately not gated behind a repository variable.** That would turn a red
check into a silent no-op, and a scanning workflow that quietly does nothing is
worse than one that says out loud it cannot run. The header says why it is red
and what turns it green.

**Three documentation claims were corrected the same hour they became false.**
The CRA row (2)(a), Part II row (3), and item 5 of the "what would still have to
change" list all said CodeQL was running. It is configured and verified, and it
is not running. Writing that down is the whole discipline this file exists for:
the reversal dwell once shipped mismatched with every document for a commit, and
the way that happens is a claim written when the intent was true rather than when
the effect was.

### Follow-up: the red check, and three things verified against real binaries
CI was green on every commit; the red on the PR was CodeQL's five jobs, which
is a repository setting nobody can change from a workflow file. Reversed the
earlier "deliberately not gated" decision, but not the reasoning behind it: the
analysis jobs are now gated on `ENABLE_CODE_SCANNING`, and a `status` job
**always** runs and always writes which state it is in to the run summary, with
the command to switch it on. The objection to gating was that a scanning
workflow would quietly do nothing. It no longer does it quietly, and it no
longer paints every pull request red for a setting that costs money on a
private repository.

**Three parts of the release pipeline that had never run anywhere are now
verified locally**, which was the honest gap in the previous entry:

- **`esptool.py` is the right command name after all.** The pip wheel installs
  console scripts whose entry-point names literally end in `.py`
  (`esptool.py -> esptool.__init__:_main`), so the Linux runner gets
  `bin/esptool.py`. Worth checking rather than assuming: on Windows the same
  wheel lands as `esptool.exe`, which is what prompted the doubt. `merge_bin`
  is a valid subcommand in 4.8.1.
- **The factory image builds.** Ran the workflow's exact merge against the local
  `hh_shesp32` build: 1.78 MB, *ready to flash to offset 0x0*. `boot_app0.bin`
  is where the `find` expects it, and `bootloader.bin` / `partitions.bin` are
  both in the build directory as the step assumes.
- **The credential guard catches a real firmware, not just a fixture.** Run
  against the locally built `firmware.bin`, it found **all five** committed
  values — OTA password, both WiFi passwords, both SSIDs — and found them again
  in the merged factory image. The central claim of this whole design is now
  measured rather than reasoned: every firmware binary built from this working
  tree carries the entire credential set, and the guard sees all of it.

What is still unproven is the pipeline as a whole in CI: no release run has
happened, and it cannot until the workflow is on the default branch.

### Follow-up: an adversarial review of the never-run release pipeline
CodeRabbit's free plan produces a walkthrough and no line-by-line review, and
its chat commands need a paid plan, so its review was never going to arrive. Ran
an independent adversarial review instead, aimed squarely at the workflow that
has never executed. Fifteen findings, of which these mattered:

**No release could ever have published.** Artifact attestations need a public
repository, or a private one owned by an organisation on a plan that includes
them. This one is private and personally owned, so `attest` would have failed —
exactly as the code-scanning API already does in codeql.yml — and `publish`
`needs:` it, so a failed attestation would have skipped the release entirely.
Even a dry run would never have gone green, which is what BUILDING.md told the
operator to expect. The attest step is now `continue-on-error`, publishes an
`attested` output, and the release notes claim an attestation only when there
actually is one. Losing provenance is a real loss; withholding a firmware that
has already been built and checked because of it is a worse one.

**Four hand-written guards could never have run.** Under `set -euo pipefail` a
failing command substitution kills the step *before* the next line's guard can
print. So `[ -n "$BOOT_APP0" ] || { echo "::error::boot_app0.bin not found"; }`
and three siblings were decoration. The worst was the certificate check: a
legitimate `grep` miss — apksigner relabels the line, or the APK is unsigned —
would have killed the step with no message at all, on the single most important
check in the workflow. `find | head -1` was doubly wrong: head exits, find takes
SIGPIPE, pipefail turns that into a dead step, and a warm PlatformIO cache
holding two framework versions makes two matches realistic. Now `-print -quit`.

**The fail-closed check covered half the secrets.** Only two of the four signing
secrets were checked. An unset repository secret arrives as an EMPTY STRING
rather than unset, so Gradle's `getenv(...) ?: "driveremote"` elvis never fired
and the alias became `""` — failing deep inside AGP with an opaque keystore
error instead of the message that names the fix. Both halves fixed: all four
checked in the workflow, `takeUnless { it.isNullOrEmpty() }` in Gradle.

**Nothing pinned the checkout to the commit being released.** Each job checked
out the branch tip when its runner started, while the tag, the target and the
notes all used the commit `version` resolved. A push landing mid-run would have
produced a Release tagged at one commit carrying firmware built from another,
silently. All three build jobs now check out `needs.version.outputs.commit`.

**A second copy of the OTA password that the guard never read.**
`platformio.ini` carries it as `upload_flags = --auth=…`, in a file with nothing
to do with `secrets.h`. Today the guard still catches it because the same string
is in both — but cleaning up `secrets.h` per BUILDING.md 8 without also fixing
`platformio.ini` would have left the guard with nothing to look for while the
password stayed in the repository and stayed compiled into the firmware. The
guard now takes repeatable sources and parses `--auth=` as well; verified that
given `platformio.ini` alone it still catches the password in a real image.

Also: an empty SBOM would have shipped looking like a real one (`sbom.py` now
takes `--min-components`, and the release passes 5); the asset-count floor of 20
let one missing file through when the real count is exactly 21; the Android SDK
packages `ci.yml` installs deliberately were missing from both the release and
CodeQL builds; the notes claimed a checksum for all 21 assets when only the 7
binaries have one; `ci.yml` used floating action tags while the other two
workflows pinned SHAs, on the same repository the pinning argument was written
for; and the dependabot header claimed majors are held back "everywhere" when
the actions group deliberately does not.

The reviewer also checked and cleared the things worth being sure about: every
`working-directory`, the `git show HEAD:<path>` semantics, context availability,
the `$GITHUB_ENV`/`$GITHUB_OUTPUT` handoffs, the artifact plumbing, the
`!inputs.dry_run` guard, the heredoc's indentation, the esptool offsets, `npm
pack`'s contents against the `files` allowlist, and the permission split.

### Follow-up: the signing key created, and the first green release run

The adversarial review fixed everything that could be fixed by reading. The
first real `workflow_dispatch` then failed for the one reason no amount of
reading could fix: there was no signing key. `DRIVEREMOTE_KEYSTORE_BASE64`,
`_KEYSTORE_PASSWORD`, `_KEY_ALIAS` and `_KEY_PASSWORD` were all unset, and the
fail-closed check written for exactly this named all four and stopped the job.
That check earned its keep on its first outing — the message pointed straight at
BUILDING.md 10.4 rather than at an opaque AGP keystore error.

So the key now exists: PKCS12, RSA-4096, alias `driveremote`, 10000 days,
`CN=Drive Remote Controller, O=KEGustafsson`, at `~/.driveremote/` and nowhere
inside the repository. Its certificate is
`b760e9c94b9489762784c4494d2a3c716a0c76fe9b324a688f8ce30d0e0944fe`, now the
repository variable `DRIVEREMOTE_CERT_SHA256`. Not the crew-radio key, which was
sitting right there: one key for two apps means one compromise for two apps, and
these two do not have the same blast radius.

Verified in both places rather than one. Locally: `assembleRelease` printed
`Release signing: release key ...`, produced `app-release.apk` without the
`-unsigned`, and `apksigner verify --print-certs` returned that fingerprint. In
CI, the dry run went green end to end — every guard the review added ran and
passed, the certificate check printed the *same* fingerprint the local build
did, and the asset count came to exactly 21. The two APKs even came out the same
size, 8247846 bytes.

The `attest` step failed as the review predicted it would, and did the right
thing about it: `continue-on-error` held, the annotation said "build provenance
could not be attested; publishing without it", and the summary explained why.
That is the designed behaviour on a personally-owned private repository, not a
regression.

Then the real thing, `dry_run` off: green in every job including `publish`, and
`v0.153` exists — the first Release this repository has ever had, pre-release,
21 assets, tagged at c333984. Checked by downloading it rather than by reading
the log: the published APK verifies against its own `.sha256`, and apksigner
reports `CN=Drive Remote Controller, O=KEGustafsson` with the fingerprint above.
Not byte-identical to the local build — same 8247846 bytes, different hash —
which is what signing under a different JDK and build-tools produces, and not
something this pipeline ever claimed.

The notes tell the truth about what is missing, unprompted: "**No provenance
attestation.** This release could not be attested, so there is nothing for `gh
attestation verify` to check." That sentence is the whole point of making
`attest` non-blocking rather than removing it.
