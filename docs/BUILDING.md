# Drive Remote Controller — building, flashing and releasing

Step by step, from an empty machine to firmware on a board and a plugin on the boat's
server. [README.md](../README.md) says what the system is;
[ARCHITECTURE.md](ARCHITECTURE.md) says how the code works; [SAFETY.md](SAFETY.md) says
what must be checked before any of it reaches a drive or a thruster. This page is only
about the procedures.

**Building is not commissioning.** Everything here can succeed on a laptop while the
system is still unsafe to connect. A green build proves the code compiles; a green test
run proves the pure decisions still hold. Neither proves a servo moves the right way.
SAFETY.md's commissioning checklists are the gate, and they are not optional.

Five artifacts come out of this one project, from three unrelated toolchains:

| Artifact | Toolchain | Directory | Command |
|---|---|---|---|
| TX — Handheld Controller | PlatformIO | `esp32/` | `pio run -e tx_shesp32` |
| RX — Motion Controller | PlatformIO | `esp32/` | `pio run -e rx_shesp32` |
| HH — Heading Hold Controller | PlatformIO | `esp32/` | `pio run -e hh_shesp32` |
| Signal K plugin + web UI | npm | `sk-plugin/` | `npm run build` |
| Android station | Gradle | `android/` | `./gradlew :app:assembleDebug` |

There is **no shared build step between the three toolchains**, which is why
[§9](#9-after-changing-a-signal-k-path-or-a-timing-constant) exists.

Commands are written for a POSIX shell: Git Bash on Windows (it ships with Git for
Windows), or any shell on macOS and Linux. The Gradle wrapper is the one thing that
differs — `.\gradlew.bat` in PowerShell and `cmd`, `./gradlew` everywhere else.

---

## 1. What the machine needs, once

Nothing here is needed all at once. The pure test suites are deliberately cheap: the
firmware core needs a C++ compiler and no ESP32, and the Android core needs a JDK and no
Android SDK. Install only the toolchain for the part you are changing.

### 1.1 PlatformIO, for the three firmwares

PlatformIO is the only thing to install. There is nothing to vendor alongside it:
`esp32/platformio.ini` names its dependency sources and fetches SensESP and ESP32Servo
from git itself.

```sh
pip install --upgrade platformio     # or: uv tool install platformio
pio --version                        # developed against PlatformIO Core 6.1.19
```

The ESP32 toolchain, the Arduino framework and the libraries are fetched on the first
`pio run` (roughly 1 GB into `~/.platformio`). That first build is slow; later ones reuse
`esp32/.pio/build_cache/`.

### 1.2 A host C++ compiler, for `pio test -e native`

`env:native` builds the pure core with the *system* compiler, not the ESP32 one, so it
needs a real `g++` on `PATH`. macOS and Linux normally have one already (`xcode-select
--install`, or `build-essential`). Windows does not:

```powershell
winget install --id BrechtSanders.WinLibs.POSIX.UCRT
```

Reopen the shell afterwards and check that `g++ --version` answers. Any C++17-capable
compiler works; there is nothing ESP32-specific in `esp32/lib/control_core/`, which is
the entire point of that directory.

### 1.3 Node, for the Signal K plugin

`sk-plugin/` is an ordinary npm project (Vite 6, Vitest 2, React 19, TypeScript 5.7).
CI runs it on **Node 22**; `package.json` declares no `engines` field, so any current LTS
should work.

### 1.4 JDK and Android SDK, for the Android station

| | For `:core:test` | For `:app` |
|---|---|---|
| JDK 17 or newer | **required** | **required** |
| Android SDK, platform-35, build-tools 35.0.0 | not needed | **required** |

Verified with Temurin 21.0.11. The Gradle wrapper downloads Gradle 8.14.3 on first run —
**do not install Gradle separately.** The full one-time setup for Windows, Linux and
macOS, including the `cmdline-tools/latest` layout that `sdkmanager` insists on, is in
[android/README.md § Building and testing](../android/README.md#building-and-testing) and
is not repeated here.

`:app` is included in the Gradle build **only when an SDK is present**, so a `:core`-only
machine is a supported configuration rather than a broken one.

---

## 2. The fast loop — host tests, no hardware

These are what you run while you work. Together they cover every safety decision the
system makes, in all three languages that make it.

```sh
cd esp32      && pio test -e native      # the firmware's pure core, C++
cd sk-plugin  && npm test                # the arbiter, the UI logic, an operator session
cd android    && ./gradlew :core:test    # the Android station's pure core, Kotlin
```

The first is the one to keep green above all others. `env:native` compiles
`esp32/lib/control_core/` only — `build_src_filter = -<*>` excludes `esp32/src/` entirely
— so one run covers all three firmwares' arbitration, control law, filters, parsers and
state machines in seconds.

**That exclusion cuts both ways.** A change under `esp32/src/` is invisible to the native
suite, however green it goes. Hardware glue is only ever proved by a real build
([§3](#3-building-the-three-firmwares)) and ultimately by SAFETY.md's checklists.

Each test directory under `esp32/test/` is its own Unity binary, and **the directory
names must be unique across the project**. Add a module and add its suite alongside, red
before green.

---

## 3. Building the three firmwares

```sh
cd esp32
pio run -e tx_shesp32
pio run -e rx_shesp32
pio run -e hh_shesp32
pio run                    # all three: default_envs lists them
```

A bare `pio run` builds all three, which is rarely what you want mid-change — name the
env.

Each environment compiles exactly one subdirectory of `esp32/src/` through
`build_src_filter`, sharing `esp32/lib/control_core/` and `esp32/include/config.h`. All
three target the Hat Labs **SH-ESP32** (`board = esp32dev`) through the
[pioarduino platform fork](https://github.com/pioarduino/platform-espressif32), pinned in
`platformio.ini` to its `stable` release zip.

`board_build.partitions = min_spiffs.csv` gives two app partitions, which is what leaves
room for OTA. Watch the size report if a firmware grows:

```sh
pio run -e rx_shesp32 -t size
```

Discovering at an upload that the image no longer fits is a bad way to find out, so CI
runs this after every build.

### 3.1 Dependencies, and what "reproducible" would mean here

`platformio.ini` tracks the owner's SensESP fork **by branch**:

```ini
lib_deps = https://github.com/KEGustafsson/SensESP.git#fix_analog_input
```

That is deliberate (owner decision, 2026-07-25) and it means the dependency **moves under
you**. Two consequences worth stating plainly:

- A build is not reproducible from `platformio.ini` alone. If a build matters — a bench
  session, or a board that goes on the boat — record the resolved revision from its log
  alongside the result. The commented-out upstream `SignalK/SensESP` line above it is the
  record of where to return.
- Two machines building "the same" firmware on different days can produce different
  binaries with no diff in this repository.

`ESP32Servo` (RX only) is pinned to an exact commit, which is the shape the SensESP
dependency would take if it were pinned. `esp_websocket_client` comes from an Espressif
component-registry download URL rather than a git ref.

### 3.2 Secrets

`esp32/include/secrets.h` holds the WiFi and OTA credentials. Every main includes it when
it exists and falls back to `secrets.example.h` otherwise, so **a fresh clone builds
without it** — the firmware simply will not join WiFi until the file is created or WiFi
is configured through SensESP's setup portal.

`secrets.h` is **gitignored and not committed** — copy `secrets.example.h` to it and
fill in your own values. It holds everything specific to one boat:

| Define | Used by |
|---|---|
| `SECRET_WIFI_SSID` / `_PASSWORD` (×3) | `set_wifi_clients()`, compiled in |
| `SECRET_OTA_PASSWORD` | `enable_ota()`, compiled in — and `scripts/ota_auth.py` reads the same `#define` at upload time, so the uploader authenticates with the one the board expects |
| `SECRET_OTA_HOST_TX` / `_RX` / `_HH` | `scripts/ota_auth.py`, at upload time only |
| `SECRET_SK_SERVER_ADDRESS` / `_PORT` | `config.h`'s `kSkServerAddress` / `kSkServerPort`, compiled in as the default server |

Nothing committed to this repository holds a credential or names the boat's network. Read
[§8](#8-credentials-and-what-must-be-rotated) for what is still owed on the values that
were committed before.

---

## 4. Flashing

### 4.1 Over the air — the configured default

`[env]` sets `upload_protocol = espota`, and each board's address comes from your
`include/secrets.h` — `scripts/ota_auth.py` picks the one matching the environment being
built:

| Env | Address from | Board |
|---|---|---|
| `tx_shesp32` | `SECRET_OTA_HOST_TX` | tx-remote |
| `rx_shesp32` | `SECRET_OTA_HOST_RX` | rx-remote |
| `hh_shesp32` | `SECRET_OTA_HOST_HH` | hh-remote |

An IP or an mDNS name both work. They are not credentials, but they describe the boat's
network, so they live with the rest of the site configuration rather than in a file
everyone can read — `include/secrets.example.h` has the template.

```sh
pio run -e rx_shesp32 -t upload
```

So **`-t upload` goes over the network by default, not over USB**, and it works only from
a machine on the boat's network with the board powered and joined. The OTA password is
`scripts/ota_auth.py`, which reads it from `include/secrets.h`; it must match the board's compiled-in
`SECRET_OTA_PASSWORD`, so those two rotate together
([§8](#8-credentials-and-what-must-be-rotated)).

> **An OTA reflashes a board wired to a clutch and a thruster contactor**, and the board
> reboots when it lands. Do not upload to a unit that is powering machinery. Treat an
> OTA the way you would treat pulling the board out: machinery off first.

Override the address without editing anything when a board is on a different one — an
explicit port wins, and `ota_auth.py` leaves it alone:

```sh
pio run -e rx_shesp32 -t upload --upload-port 192.168.1.50
PLATFORMIO_UPLOAD_PORT=192.168.1.50 pio run -e rx_shesp32 -t upload   # equivalent
```

### 4.2 Over USB — for a virgin board

A board that has never joined WiFi cannot be reached by OTA, so the first flash of a new
unit has to be serial. **There is no command-line override for this.** `upload_port` and
`upload_flags` have `PLATFORMIO_*` environment overrides; `upload_protocol` does not
(checked against PlatformIO Core 6.1.19's own option table), and this version of
`pio run` has no `--project-option` flag either.

Comment the one line out in `[env]` for the duration:

```ini
;upload_protocol = espota
```

then:

```sh
pio run -e rx_shesp32 -t upload --upload-port COM5
```

Nothing else needs touching. `scripts/ota_auth.py` checks the protocol and does nothing
unless the transfer is actually going over the air, so it does not put an `--auth=` flag in
front of esptool — which would not understand one.

Restore them afterwards. If serial flashing becomes routine, the tidier fix is a
dedicated `[env:rx_shesp32_serial]` that extends the same base and overrides
`upload_protocol` — a build-config change, deliberately not made here.

Serial ports by platform:

| | Pattern |
|---|---|
| Windows | `COM3`, `COM5`, … |
| macOS | `/dev/cu.usbserial-*`, `/dev/cu.usbmodem*` |
| Linux | `/dev/ttyUSB*`, `/dev/ttyACM*` — needs membership of the `dialout` group |
| WSL | USB passthrough via `usbipd-win` |

### 4.3 Watching a board

```sh
cd esp32
pio device monitor            # auto-detects the port; Ctrl-] to leave
```

**Opening the serial port resets the board.** When the question is what a *running* unit
is doing, that reset destroys the answer. Read the log over HTTP instead — SensESP `main`
serves it on a board already on WiFi, and nothing about reading it resets anything:

```sh
curl -N http://rx-remote.local/api/log
```

`monitor_filters = esp32_exception_decoder` is set, so a crash backtrace comes out
symbolised.

---

## 5. The Signal K plugin

```sh
cd sk-plugin
npm install
npx tsc -b         # typecheck alone, so a type error reports as one
npm test           # vitest run
npm run build      # tsc -b && vite build -> public/
```

`public/` is the servable output and is **gitignored** — the same convention as the
firmware's `.pio/`. Rebuild it after any change; a stale `public/` is a UI that no longer
matches the arbiter it is talking to.

`npm run dev` serves the UI on its own port with hot reload, which is convenient and
unrepresentative: the dev server's origin is not the Signal K server's, so the
same-origin session cookie the WebSocket normally inherits is absent and the Commands
lamp reads "unconfirmed". For anything touching authentication or arming, build and
install instead.

### 5.1 Installing into a Signal K server

Copy or symlink the directory into the server's plugin directory, then restart the
server:

```sh
ln -s "$PWD" ~/.signalk/node_modules/signalk-drive-remote-controller
```

Or pack it, which respects the `files` list in `package.json` (`index.cjs`,
`arbiter.cjs`, `suspendClock.cjs`, `public`) and produces a tarball to carry to a boat:

```sh
npm pack --ignore-scripts        # signalk-drive-remote-controller-<version>.tgz
npm install /path/to/signalk-drive-remote-controller-<version>.tgz
```

**Run `npm run build` before packing.** `public/` is gitignored but it *is* in `files`,
so a pack without a build produces a plugin whose server half works and whose UI is
missing.

It appears in the admin UI as "Drive Remote Control", **disabled by default** — a remote
control for machinery should not come armed just because it was installed — and once
enabled its webapp is at `/signalk-drive-remote-controller/`.

### 5.2 The one deployment check that is not a build check

The plugin registers its `/intent` route through the server's access-scoped registrar,
`router.access('readwrite')`. Registered directly instead, the route inherits the
`/plugins` gate's **admin-only** default, and then every station holding an ordinary
Signal K access token — the Android app, and TX, RX and HH — gets HTTP 401 on every
command while the browser UI keeps working, because a browser opened from the admin UI
carries an admin session cookie.

This has actually happened here (2026-07-25). Confirm it after deploying to a server you
have not used before, with a token obtained through the access-request flow:

```sh
SK=http://signalk.local:3000      # or your server's address
curl -i -H "Authorization: Bearer $TOK" "$SK/signalk/v1/stream"     # expect 426 Upgrade
curl -i -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -X POST $SK/plugins/signalk-drive-remote-controller/intent \
  -d '{"clientId":"commissioning","seq":1,"armReq":0,"disarmReq":0,"port":"neutral","stbd":"neutral","thruster":"off","thrusterMode":"manual","trimDeg":0}'
```

A 401 or 403 on the second call means the route is still admin-only on that server. Some
server versions accept only the `JWT <token>` prefix rather than `Bearer`; retry with
`JWT` before concluding the token is bad. The full reasoning is in
[ARCHITECTURE.md § The plugin](ARCHITECTURE.md#10-the-plugin).

---

## 6. The Android station

### 6.1 Debug — the everyday loop

```sh
cd android
./gradlew :core:test              # pure logic -- JDK only, no Android SDK
./gradlew :app:testDebugUnitTest  # the layout floors, measured (needs an SDK)
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`:core:test` prints its own total, so a run can be quoted in the journal the way
`pio test` and `npm test` are.

`:app`'s unit tests are not decoration: they lay the real Compose tree out on the JVM
under Robolectric and **measure the live controls**. A telemetry-panel change once
squeezed both drive contacts to 4.7 dp — a tenth of Android's minimum touch target — on a
station that commands machinery, and a human reading a `uiautomator` dump off a phone is
what found it. That suite is also the only thing in the project that compiles `:app` at
all.

Debug APKs are signed with the per-machine key in `~/.android/debug.keystore`, so a build
from a second machine will not install over one from the first. Uninstall first.

### 6.2 Release without a key — what you get by default

```sh
./gradlew :app:assembleRelease
```

With no keystore configured this produces
`app/build/outputs/apk/release/app-release-unsigned.apk`, **which no phone will install**.
That is the expected result, not a misconfiguration: the signing key is not in the
repository, so a build that has not been given one has nothing to sign with.

The build prints one line saying which case it is in, so nobody reads *BUILD SUCCESSFUL*
as *signed*:

```text
Release signing: UNSIGNED (no release keystore; see docs/BUILDING.md section 6)
```

The version comes from git rather than from a hand-edited number:

| | |
|---|---|
| `versionCode` | the commit count, monotonic on `main` |
| `versionName` | `0.<commit count>` |

Still below 1.0 deliberately. SAFETY.md's commissioning checklists have not been worked
through and the version should not imply otherwise. `./gradlew :app:printVersion` prints
it and writes `app/build/version.txt`, which is the file the release workflow reads.

### 6.3 What release mode does and does not do

Two things that do **not** change between debug and release, and are sometimes assumed to
— plus one that used to be on this list and no longer is:

- **The code itself.** R8 is on, and shrinks it — [§6.3.1](#631-r8-and-what-it-took-to-turn-on).
- **The cleartext gate.** `PrivateAddress.isPrivateHost()` lives in `:core` and applies
  identically in both. `network_security_config.xml` is a bare permissive `base-config` in
  both, because the decision it used to try to express cannot be written there at all.
- **Logging.** Nothing strips log statements from the release build.

What release mode *does* change is the debug tooling: `debugImplementation` dependencies
(Compose's UI tooling and test manifest) are left out, and the build is signed if it was
given a key.

#### 6.3.1 R8, and what it took to turn on

`buildTypes.release` sets `isMinifyEnabled = true` and `isShrinkResources = true`, with
keep rules in `android/app/proguard-rules.pro`.

**It was off until 2026-09-10.** The objection was never the tooling: it was that
shrinking and obfuscating an *unverified* codebase adds a failure mode that exists only in
the release build — a stripped class, a field renamed out from under a reflective lookup —
and the place it surfaces is a boat, in an app that commands a clutch and a bow-thruster
contactor.

That objection was answered by testing rather than by argument. The owner fresh-installed
the shrunk APK on a phone, went through a **new Signal K access request**, armed against
the live server, and commanded a drive and the thruster — precisely the path the Tink risk
below threatens, run end to end.

**The build change**

```kotlin
// android/app/build.gradle.kts
buildTypes {
  release {
    isMinifyEnabled = true
    isShrinkResources = true          // optional; requires isMinifyEnabled
    proguardFiles(
      getDefaultProguardFile("proguard-android-optimize.txt"),
      "proguard-rules.pro",
    )
    if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
  }
}
```

`proguard-android-optimize.txt` comes from the SDK. **`android/app/proguard-rules.pro`
must exist.** A config naming a rules file that is not there builds far enough to fail
inside R8, and the `Supplied proguard configuration does not exist` line is only a warning
several screens above the real error.

**What actually needs keep rules here**

Worth knowing which parts are already handled, so only the rest gets debugged. Checked by
unzipping the artifacts in the Gradle cache rather than assumed:

| Dependency | Ships its own R8 rules? | What may still be needed |
|---|---|---|
| Compose, AGP, AndroidX | Yes, as consumer rules inside the AARs | Nothing normally |
| OkHttp 4.12 | Yes — `META-INF/proguard/okhttp3.pro` | The Conscrypt / BouncyCastle / OpenJSSE `-dontwarn` lines, if the build warns |
| kotlinx.serialization 1.7.3 | Yes — in `kotlinx-serialization-core`, including an R8-specific `kotlinx-serialization-r8.pro` | Nothing for compiler-generated serializers, which is all of `:core`'s |
| **`androidx.security:security-crypto` 1.1.0-alpha06 → Tink** | **No.** The AAR carries no `proguard.txt` at all, and `tink-android-1.8.0` ships only protobuf rules rather than its own | **Expect to write rules here** — Tink registers its key managers reflectively |

**That last row still matters, but less than it did.** The token store no longer uses
that library: `SettingsStore` goes through `KeystoreEncryptedPreferences`, which uses the
platform's own AES-256-GCM under an Android Keystore key and pulls in no Tink. Tink
survives only because `LegacySecureStoreMigration` reads the old store once on upgrade —
so these rules protect a migration rather than the live path, and **they should be deleted
in the same commit as that file**, once every station has run a migrating build. Doing so
also returns the ~96% of shrinking they cost. The blunt rule while they are needed:

```proguard
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# Covers all three families below. Do not narrow this to a single package.
-dontwarn com.google.crypto.tink.**
```

**That last line is broad on purpose.** Tink carries references R8 cannot resolve, and
each unresolved reference is a hard `ERROR` rather than a warning. There are three
families, and **R8 reports one family per build** — so fixing them by name is several
rounds of rebuild-and-see:

| Missing | Referenced from |
|---|---|
| `com.google.errorprone.annotations.*` | Tink generally — compile-only annotations |
| `com.google.api.client.http.*` | `KeysDownloader` — google-api-client |
| `org.joda.time.Instant` | `KeysDownloader` — joda-time |

`-dontwarn` suppresses unresolved references **originating in** the named classes, and all
three originate in Tink, so one line covers the lot. AGP writes the narrower per-package
list to `app/build/outputs/mapping/release/missing_rules.txt` as it discovers each family,
which is worth reading but is not the shortest route to a green build.

`KeysDownloader` fetches keysets over HTTP and **this app never uses it** — the token store
is local. It survives only because the blunt `-keep` above keeps all of Tink; narrowing
that keep would drop it, and this whole problem with it.

**Measured, not guessed.** The change above and that rules file were applied to this tree
on 2026-09-10 (AGP 8.7.3), built, and reverted:

| | Unshrunk | With R8 |
|---|---|---|
| `app-release-unsigned.apk` | 8,239,654 B | **1,947,599 B** — 76% smaller |
| Classes removed (`usage.txt`) | — | 48,358 lines |
| Kept (`seeds.txt`) | — | 21,560 entries |
| …of those, Tink | — | **20,682 — 96%** |
| `mapping.txt` | — | 30 MB |

**96% of everything R8 kept is Tink**, held by that one blunt rule. Nearly all the
remaining shrinking therefore lives in narrowing it — and narrowing it wrong is exactly
what breaks the token store in the release build only, where no suite here would catch it.
If you narrow it, narrow against `usage.txt` and **repeat the hardware test**: a fresh
install and a new access request, not merely a launch.

Everywhere else, add rules in response to an actual observed failure rather than
pre-emptively: a keep rule written on a guess quietly defeats the shrinking it was turned
on for, and nothing reports that.

**The part that is easy to miss**

`./gradlew :app:testDebugUnitTest` tests the **debug** variant. Robolectric lays out the
real Compose tree, but against unminified debug classes — so the layout-floor suite, the
thing standing between an operator and a 4.7 dp STOP button, **does not see the shrunk
build at all**. This is the one objection the hardware test did not answer, and it remains
open: a control-layout change needs that suite *and* a look at the shrunk APK on glass.
Options for closing it, cheapest first:

1. `testBuildType = "release"` in the `android { }` block, so `:app:testReleaseUnitTest`
   runs the same suite against the release variant. Note that unit tests run on the JVM
   against pre-minified classes even then: this changes which variant is compiled, not
   whether R8 has processed what the test sees.
2. An instrumented run (`connectedAndroidTest`) against the minified APK on a device or
   emulator. The only option that exercises the actual shrunk code — and there is no
   `androidTest` source set in this project yet.
3. At minimum, install the minified APK and work through SAFETY.md's Android checklist by
   hand before it goes anywhere near machinery.

**Verifying a shrunk build**

```sh
cd android
./gradlew :app:assembleRelease
cat app/build/outputs/mapping/release/usage.txt   # what R8 removed
cat app/build/outputs/mapping/release/seeds.txt   # what it kept, and why
ls  app/build/outputs/mapping/release/mapping.txt # the deobfuscation map
```

`mapping.txt` belongs to that exact build, and measured **30 MB** here. Without the one
matching the APK, a stack trace from the boat is unreadable — so it has to be archived
alongside the published APK. [The release workflow](#10-releases) does not currently do
that, which is one more thing to add before enabling this.

### 6.4 Creating a release key, once and never again

A new key means every phone must uninstall and reinstall, which also clears the stored
Signal K token and needs a fresh access request approved on the server. Create one only if
there is none, and **back the file up somewhere that is not this machine.**

```sh
keytool -genkeypair -v \
  -keystore ~/.driveremote/driveremote.keystore \
  -storetype PKCS12 \
  -alias driveremote \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

`keytool` ships with the JDK. It asks for a store password, used again as the key password
with PKCS12, and for the name fields, which appear only in the certificate. The alias
`driveremote` is what the build assumes when `DRIVEREMOTE_KEY_ALIAS` is unset, and 10000
days is about 27 years — a signing key should outlive the phones.

Keep it outside the repository. `android/.gitignore` already refuses `*.keystore`, `*.jks`
and `keystore.properties`, but the safe habit is that the key is never under the
repository at all.

### 6.5 Building release-signed locally

`app/build.gradle.kts` reads these from the environment and nowhere else — there is no
properties file to commit by accident:

| Variable | What it is | Default |
|---|---|---|
| `DRIVEREMOTE_KEYSTORE` | Path to the keystore file | `app/release.keystore` |
| `DRIVEREMOTE_KEYSTORE_PASSWORD` | The store password | none — no password, no signing |
| `DRIVEREMOTE_KEY_ALIAS` | The key's alias inside the store | `driveremote` |
| `DRIVEREMOTE_KEY_PASSWORD` | The key's own password | the store password |

Two guards worth knowing before you hit them:

- **A keystore named but unusable stops the build.** If `DRIVEREMOTE_KEYSTORE` is set and
  the file is missing, or its password is empty, Gradle fails. A typo would otherwise
  produce an unsigned APK without a word.
- **A shallow clone is refused** for a signed build, because its commit count is wrong and
  the resulting `versionCode` could read as a downgrade. `git fetch --unshallow` fixes it.

Set them for the shell you are about to build in, rather than in a startup file:

```sh
# Git Bash, macOS, Linux -- `read -s` keeps the password out of shell history
export DRIVEREMOTE_KEYSTORE="$HOME/.driveremote/driveremote.keystore"
read -s -p "keystore password: " DRIVEREMOTE_KEYSTORE_PASSWORD; echo
export DRIVEREMOTE_KEYSTORE_PASSWORD
```

```powershell
# PowerShell
$env:DRIVEREMOTE_KEYSTORE = "$env:USERPROFILE\.driveremote\driveremote.keystore"
$env:DRIVEREMOTE_KEYSTORE_PASSWORD = (Get-Credential -UserName driveremote `
  -Message "keystore password").GetNetworkCredential().Password
```

Then:

```sh
./gradlew :app:assembleRelease
```

Run the layout floors alongside it if anything in `app/` changed — geometry is a safety
property on the control screen:

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

**`BUILD SUCCESSFUL` proves neither that it is signed nor that it is signed by the right
key.** Three checks, in increasing strength:

```text
Release signing: release key /home/you/.driveremote/driveremote.keystore
```

```sh
ls app/build/outputs/apk/release/        # app-release.apk -- a signed build drops "-unsigned"

# apksigner lives in $ANDROID_HOME/build-tools/35.0.0/ (apksigner.bat on Windows)
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The certificate SHA-256 that prints is the one to compare against
[§6.6](#66-the-certificate-fingerprint) — it is what the release workflow gates on, and
checking it locally is how you find out you signed with the wrong key before a phone
refuses the upgrade.

A local signed build is for testing an upgrade path, or for a phone that cannot reach
GitHub. The APK on a Release page comes from the workflow in
[§10](#10-releases).

### 6.6 The certificate fingerprint

The release workflow refuses to publish an APK that is not signed with the expected
certificate, comparing against the repository variable `DRIVEREMOTE_CERT_SHA256`:
lowercase hex, no colons.

From a signed APK, with `apksigner` from the SDK's `build-tools` (`apksigner.bat` on
Windows):

```sh
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Or straight from the keystore, converting `keytool`'s colon-separated uppercase into the
form the workflow wants:

```sh
keytool -list -v -keystore ~/.driveremote/driveremote.keystore -alias driveremote \
  | grep -i "SHA256:" | head -1 | sed 's/.*SHA256: *//' | tr -d ':' | tr 'A-F' 'a-f'
```

### 6.7 Installing, and switching between the two

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

`adb` is in `$ANDROID_HOME/platform-tools`. Or copy the APK to the phone and tap it,
allowing installation from unknown sources.

Android will not replace a build signed with one key by a build signed with another, in
either direction, so moving between debug and release means `adb uninstall
io.github.kegustafsson.driveremote` first. **That uninstall clears the stored Signal K
token**, which is in app-private storage excluded from backup, so the station needs a
fresh access request approved in the server's admin UI afterwards.

`applicationId` is `io.github.kegustafsson.driveremote`. Change it and your build installs
*alongside* the original rather than over it, which is what you want while comparing two
versions.

---

## 7. Continuous integration

There are four workflows. This section is about the first; the other three have their own
sections.

| Workflow | When | What it is for |
|---|---|---|
| `ci.yml` | every push to `main`, every pull request, on demand | Everything checkable without hardware. Publishes nothing, holds no secrets. |
| `codeql.yml` | same, plus weekly | Static analysis, five languages — [§11.1](#111-codeql) |
| `release.yml` | **by hand only** | Builds, verifies, attests and publishes every asset — [§10](#10-releases) |
| `dependabot.yml` | weekly | Grouped dependency updates — [§11.2](#112-dependabot) |

`ci.yml` is the one that gates a change.

| Job | What it runs |
|---|---|
| Pure core | `pio test -e native` |
| Build ×3 | `pio run -e {tx,rx,hh}_shesp32`, then `-t size` — a matrix, so a break names one unit |
| Signal K plugin | `npm ci`, `tsc -b`, `npm test`, `npm run build` |
| Android pure core | `./gradlew :core:test`, **with the SDK path blanked on purpose** |
| Android layout floors | `./gradlew :app:testDebugUnitTest`, then `:app:assembleDebug` |
| Release scripts | Byte-compiles `esp32/scripts/`, and self-tests the credential guard |

Two of those are subtler than they look. The Android core job blanks `ANDROID_HOME`
because GitHub's runners ship an SDK, which would make Gradle configure `:app` and
quietly destroy what the job is *for*: if `:core` ever stops building without an SDK,
something Android-specific has leaked into the pure layer, and the job going red is the
intended alarm. And `main` is never cancelled by a newer push, because its runs are the
record of what was verified on the trunk.

The release-scripts job is the third. `check_no_secrets.py` is what stops a published
firmware carrying a credential, so its own contract is checked here in both directions —
a binary holding the committed OTA password must fail, one holding only placeholders must
pass — rather than first being exercised during a release.

**What CI cannot tell you** is whether the firmware works. It catches the class of
problem that has actually bitten here: a pure-core change that breaks an arbitration or
timing rule, a firmware that stops compiling against its configured dependencies, a touch
target that shrinks below its floor, and documentation drifting from the code it
describes. Everything else is SAFETY.md's job.

---

## 8. Credentials, and what must be rotated

**This repository is public.** The rotation below was written as the thing to do *before*
that happened, and it has not been done — so read this section as overdue work, not as a
precaution.

| Where | What | In this repository? |
|---|---|---|
| `esp32/include/secrets.h` | Real boat WiFi SSIDs and passwords, the OTA password, and the three boards' addresses | No — gitignored, and the only copy |
| `esp32/include/config.h` | Pins, Signal K paths and tunables — no addresses | **Yes, committed** |
| The predecessor repository's history | The same WiFi and OTA credentials, from earlier commits | Its own history, which is private |

`esp32/platformio.ini` used to hold the OTA password a second time, as
`upload_flags = --auth=…`, and the three boards' addresses as `upload_port` per env. It
no longer holds either — `scripts/ota_auth.py` injects them from `secrets.h`. The
password goes in only when an OTA upload is actually requested; the address is set on a
plain build too, purely so the platform builder stops printing a spurious
`Error: Please specify IP address or host name of ESP device` on every build (§13). Both
are skipped for a serial flash, and a checkout with no secrets file still compiles all
three firmwares, which is what CI does.

`esp32/include/config.h` used to carry the boat's Signal K server address as
`kSkServerAddress`, which is compiled into all three firmwares. That is site
configuration rather than a tunable, so it moved to `SECRET_SK_SERVER_ADDRESS` in
`secrets.h` too, along with the port; `config.h` includes the same
`secrets.h`/`secrets.example.h` pair the mains do and reads both macros.

**Nothing committed to this repository now names the boat's network or holds a
credential.** The values themselves are still burned; see the rotation above.

The OTA password is the only thing standing between a device on the boat's LAN and
reflashing a board that drives a clutch and a thruster contactor.

**Every one of those values is compiled into the firmware binary**, so a `.bin` built from
this working tree carries them and `strings` recovers them. Released firmware is built
from `secrets.example.h` instead, and a guard checks each published image for them —
[§10.3](#103-the-one-thing-that-makes-firmware-releasable-at-all). Any binary you build
locally and hand to somebody has had no such check.

**What is owed, in order:**

1. Rotate the OTA password in your `secrets.h`, then reflash all three boards **over
   serial**, since the boards still expect the old password for OTA. There is only one
   place to change now, but the old value is burned: it was committed before, and the
   same string was also the WiFi password.
2. Rotate the boat's WiFi passwords. They are not in this repository, but they are in the
   predecessor repository's history.
3. Understand that removing a file forward does not scrub git history. Either rewrite
   history, or treat every credential that has ever been committed as burned.

[SECURITY.md](SECURITY.md) covers the rest of the picture: the threat model, what the
system does about each threat, and where it stands against the EU Cyber Resilience Act's
essential requirements.

---

## 9. After changing a Signal K path or a timing constant

There is no shared build step between PlatformIO, npm and Gradle, so the path and timing
contract is **hand-synced three ways**:

| File | Owns |
|---|---|
| `esp32/include/config.h` | The source of truth: pins, paths, tunables |
| `sk-plugin/src/config.ts` | The plugin UI's mirror of the paths it needs |
| `android/core/src/main/kotlin/.../core/SkContract.kt` | The Android station's mirror |

`sk-plugin/index.cjs` carries its own copy of the `plugin.*` path strings as well, since
it is CommonJS and does not import the TypeScript config.

Constants are cross-referenced by name so a diff is obvious, but **nothing enforces
this**. A path changed in one place and not the others produces a system that builds
clean, tests green, and silently stops commanding — the units subscribe to a path nobody
writes any more. Change all of them in the same commit.

---

## 10. Releases

`.github/workflows/release.yml` builds every artifact this project produces, verifies each
one, signs a provenance attestation over the set, and publishes a single GitHub Release
carrying the lot.

### 10.1 What a release contains

One version number covers everything, so no two assets in a release can disagree about
what they came from.

| Asset | Per | What it is |
|---|---|---|
| `drive-remote-controller-<unit>-<v>-factory.bin` | tx, rx, hh | The whole flash image, for a board that has never run this firmware. Flash at offset 0. |
| `drive-remote-controller-<unit>-<v>-ota.bin` | tx, rx, hh | The application image alone, for an over-the-air update. |
| `DriveRemoteController-<v>.apk` | — | The Android station, signed with the release key. |
| `DriveRemoteController-<v>.mapping.txt.gz` | — | R8's deobfuscation map for that exact APK, gzipped (~30 MB raw). Without it a stack trace off a phone is unreadable, and this is the only copy that will ever match the published binary. |
| `signalk-drive-remote-controller-<x.y.z>.tgz` | — | The plugin, packed as a server installs it. It keeps its own semantic version. |
| `*.sha256` | every binary | Its checksum. |
| `*.sbom.cdx.json` | every artifact | A CycloneDX 1.5 software bill of materials. |

Every file also carries a signed build-provenance attestation — **when the repository is
entitled to one.** Artifact attestations need a public repository, or a private one owned
by an organisation on a plan that includes them. The attestation step is allowed to fail
without blocking the release: the run summary and the release notes then both say there is
no attestation, rather than the notes advertising a verification that would fail for
whoever tried it.

### 10.2 Running one

There is no automatic release. **Actions › Release › Run workflow**, on `main`.

`dry_run` builds, verifies and attests everything without creating the Release, which is
how to check a change to the pipeline without publishing.

The version is `0.<commit count>`, computed once in the first job and passed to the rest.
Nobody edits a number, and the Android build independently computes the same value and
fails the job if the two disagree.

> **Why this is not triggered by a push to `main`, unlike crew-radio's equivalent.** This
> system commands a clutch and a bow-thruster contactor, and by SAFETY.md it has not been
> through commissioning — TX has never been flashed on hardware at all. Publishing signed
> firmware for it on every merge, including a merge that fixed a typo, is not a sensible
> default. To adopt merge-is-the-release behaviour instead, add `push: branches: [main]`
> to the workflow's `on:` block and drop the `github.event_name` guard on `publish`.

### 10.3 The one thing that makes firmware releasable at all

Every value in a `secrets.h` is compiled into the binary: `set_wifi_clients()` takes the
SSIDs and passwords, `enable_ota()` takes the OTA password. **A published `.bin` built
from a real one would hand out the boat's WiFi password and the password that authorises
reflashing a board wired to machinery**, both recoverable with `strings`.

`secrets.h` is untracked, so a release checkout has none and the mains build against
`secrets.example.h` — the published firmware joins no network until its recipient
configures WiFi through SensESP's setup portal and sets their own OTA password. That
absence is asserted rather than assumed: the release job **fails outright if a `secrets.h`
is present at all**, because a stale cache or a future `.gitignore` change is exactly how
a real one would reach a published image.

`esp32/scripts/check_no_secrets.py` then searches the actual bytes of each image for any
credential it can find in the committed tree. With the OTA password moved out of
`platformio.ini` there is nothing left for it to look for, and it says so and passes —
**the guarantee is now the absence assertion above, not the search.** The search is kept
as the backstop that goes red the day somebody writes a credential back into a committed
file. It runs against both the OTA image and the factory image, and never prints a value
it matched; the job log names only which setting leaked.

It reads **two** files, and the second one matters more than it looks. `platformio.ini`
carries the OTA password a second time, as `upload_flags = --auth=…`, in a file that has
nothing to do with `secrets.h`. Checking only `secrets.h` would mean that cleaning that
file up per [§8](#8-credentials-and-what-must-be-rotated) without also fixing
`platformio.ini` leaves this guard with nothing to look for while the password is still in
the repository and still compiled into the firmware. Verified: given `platformio.ini`
alone, the guard still catches the OTA password in a real image.

Run it by hand the same way, and on a locally built image it should **fail** — that is the
point:

```sh
cd esp32
python scripts/check_no_secrets.py \
  --secrets-file include/secrets.h \
  --secrets-file platformio.ini \
  .pio/build/rx_shesp32/firmware.bin
```

`--secrets-file` reads the working tree, which is what you want here: your own `secrets.h`
is not in git for `--ref` to read. The release pipeline uses `--ref
"HEAD:esp32/platformio.ini"` instead, because there it is checking a file the workflow
itself could have modified.

> **This is not theoretical.** Run against a real locally built `firmware.bin` on
> 2026-09-09, the guard found **all five** committed values in the image — the OTA
> password, both WiFi passwords and both SSIDs — and found them again in the merged factory
> image. Every firmware binary built from this working tree carries the whole set.

### 10.4 One-time setup, before the first release

> **Done on 2026-09-10.** All four secrets and the variable are set on the repository from
> `~/.driveremote/`, and the base64 was verified to decode byte-identically and open with
> the stored password. The steps below are the record of how, and what to repeat if the key
> is ever replaced. `gh secret list` shows the names; no value can be read back.

The Android job fails closed: without these it stops rather than publishing an unsigned
APK. Create the keystore first ([§6.4](#64-creating-a-release-key-once-and-never-again)).

1. Base64 the keystore into a file:

   ```sh
   base64 -w0 ~/.driveremote/driveremote.keystore > keystore.b64   # Linux, Git Bash
   base64 -i ~/.driveremote/driveremote.keystore -o keystore.b64   # macOS
   ```

   ```powershell
   [Convert]::ToBase64String(
     [IO.File]::ReadAllBytes("$env:USERPROFILE\.driveremote\driveremote.keystore")
   ) | Set-Content -NoNewline keystore.b64
   ```

2. Set four secrets and one variable, with `gh` or by hand under *Settings › Secrets and
   variables › Actions*. The three `gh secret set` calls without a file prompt for the
   value:

   ```sh
   gh secret set DRIVEREMOTE_KEYSTORE_BASE64 < keystore.b64
   gh secret set DRIVEREMOTE_KEYSTORE_PASSWORD
   gh secret set DRIVEREMOTE_KEY_ALIAS
   gh secret set DRIVEREMOTE_KEY_PASSWORD
   gh variable set DRIVEREMOTE_CERT_SHA256 --body "<the fingerprint from 6.6>"
   ```

3. **Delete `keystore.b64`.** It is the signing key in another dress.

4. Run the workflow with `dry_run` on. It should reach the certificate check and print the
   same fingerprint.

### 10.5 How the jobs are split, and why

No job holds both a secret and a token that could write to the repository.

| Job | Token | Holds |
|---|---|---|
| `version` | read | nothing |
| `firmware` ×3 | read | nothing |
| `plugin` | read | nothing |
| `android` | read | the keystore, deleted immediately after the build |
| `attest` | read, plus attestation signing | nothing |
| `publish` | **write** | nothing, and it runs no code from this repository — no checkout, no Gradle, no npm, no PlatformIO |

So a compromised action in a build job can read this repository and nothing more, and the
one job that can create a Release only downloads files that have already been built,
checked and attested.

### 10.6 Verifying a downloaded release

```sh
sha256sum -c DriveRemoteController-<v>.apk.sha256
apksigner verify --print-certs DriveRemoteController-<v>.apk
gh attestation verify DriveRemoteController-<v>.apk --repo <owner>/<repo>   # if attested
```

Each **binary** has a `.sha256`; the checksum files and the SBOMs do not have one of their
own. The attestation line only applies to a release whose notes say it was attested.

`sha256sum` is the one line with no PowerShell equivalent; there, compare by eye:

```powershell
Get-FileHash DriveRemoteController-<v>.apk -Algorithm SHA256 | Format-List
Get-Content DriveRemoteController-<v>.apk.sha256
```

The attestation proves the file came out of this repository's workflow, at the commit
named in the release notes.

---

## 11. Dependency and code scanning

Two automated things watch the code between releases. Neither replaces review, and neither
knows anything about whether a servo moves the right way.

### 11.1 CodeQL

> **Configured, and still gated.** Code scanning must be enabled on the repository before
> the analysis can upload its results; without it the analysis runs to completion and then
> fails at the upload with *Code scanning is not enabled for this repository* — a
> repository setting, not a workflow fault.
>
> On a **public** repository, which this now is, code scanning is free — the paid GitHub
> Code Security add-on that a private repository needed no longer applies. The five
> analysis jobs are still gated on a repository variable, and a `status` job always runs
> and always writes which state it is in to the run summary. Two turns to enable it:
> switch on code scanning under *Settings › Advanced Security*, then
>
> ```sh
> gh variable set ENABLE_CODE_SCANNING --body true
> ```
>
> The analysis half is known to work. The run on 2026-09-09 scanned 28 of 38 C++ files and
> 21 of 31 C files and interpreted every language's queries, failing only at the upload.

`.github/workflows/codeql.yml` runs on every push and pull request to `main`, and weekly so
new query packs reach unchanged code. Findings land under *Security › Code scanning*, one
category per language.

| Language | Build | Covers |
|---|---|---|
| `c-cpp` | `pio test -e native --without-testing` | The firmware's pure control core |
| `java-kotlin` | `./gradlew :app:assembleDebug` | The Android station, `:core` and `:app` |
| `javascript-typescript` | none needed | The plugin's server half, its arbiter and its UI |
| `python` | none needed | The helper scripts and the release scripts |
| `actions` | none needed | The workflows themselves |

The `c-cpp` job is the interesting one. It does **not** build a firmware — that would need
the xtensa cross-compiler, which CodeQL cannot trace usefully. It builds the native test
target instead, which compiles `esp32/lib/control_core/` with the host compiler. That is
both the code most worth analysing and the only C++ here an ordinary toolchain can build.

The query suite is `security-and-quality`, which is wider and noisier than the default.
That trade is deliberate on a codebase that commands machinery.

### 11.2 Dependabot

`.github/dependabot.yml` opens at most one grouped pull request per ecosystem per week for
the Android Gradle dependencies, the plugin's npm tree, and the GitHub Actions pinned in
the workflows. Major versions are excluded from the first two: AGP, Kotlin, Compose,
Robolectric, React, Vite and Vitest majors all carry migration work, and Robolectric's
sandbox level is tied to `compileSdk`.

**What none of this watches is the most important dependency in the project.** There is no
PlatformIO ecosystem in Dependabot, and it would not follow a branch ref if there were, so
`KEGustafsson/SensESP#fix_analog_input` moves under every firmware build with nothing
looking at it ([§3.1](#31-dependencies-and-what-reproducible-would-mean-here)). The
firmware SBOMs in each release are the only place a given binary's actual dependency
versions are written down.

The Gradle build has no dependency verification either — there is no
`gradle/verification-metadata.xml` — so a Gradle update PR is green on the strength of the
tests alone.

---

## 12. Regenerating documentation assets

Screenshots capture the built app, so build first:

```sh
cd sk-plugin
npm run build
npm i --no-save playwright          # ~120 MB, docs-only tooling
npx playwright install chromium
node scripts/screenshots.cjs        # -> sk-plugin/docs/screenshots/*.png
```

Diagrams in `docs/diagrams/` are **draw.io-editable PNGs**: each `.png` carries its own
source in a PNG text chunk, so opening it in [draw.io Desktop](https://www.drawio.com/),
app.diagrams.net, or the VS Code *Draw.io Integration* extension gives you the editable
diagram with no separate file to hunt for. The matching `.drawio` files are the same
content as plain XML, kept alongside so diagram changes show up as a readable diff.

Re-export after editing so the two stay in step:

```sh
cd docs/diagrams
for f in *.drawio; do
  drawio --export -f png --embed-diagram --scale 2 -b 16 -o "${f%.drawio}.png" "$f"
done
```

`--embed-diagram` is what keeps the PNG editable; `--scale 2` is what keeps it legible on
a phone and in print. On Windows the binary is `"/c/Program Files/draw.io/draw.io.exe"`.

---

## 13. When the build stops

| What it says | What it means |
|---|---|
| `Unknown environment names` | Typo in `-e`. The envs are `tx_shesp32`, `rx_shesp32`, `hh_shesp32`, `native`. |
| `g++: not found`, or an unknown-compiler error on `-e native` | No host C++ compiler; §1.2. The ESP32 toolchain is not used by that env. |
| A platform or library download fails | `platformio.ini` fetches from GitHub and Espressif's registry at build time. A proxy that blocks either fails the build with no code error. |
| `No response from the ESP` on upload | OTA to a board that is off, on another network, or at a different address than §4.1 lists. |
| `Authentication Failed` on upload | `SECRET_OTA_PASSWORD` in your `include/secrets.h` no longer matches the one compiled into the board; §8. |
| `ota_auth: … does not exist` on upload | No `include/secrets.h`, so there is no OTA password to upload with; §3.2. |
| `Error: Please specify IP address or host name of ESP device` during a **build** | Not a build failure — the platform writes it to stderr and the build still ends in `SUCCESS`, but IDEs show it as an error. It means no `upload_port` was resolved for an `espota` env: normally `scripts/ota_auth.py` supplies one from `include/secrets.h`, so it appears on a checkout without that file (CI) or when the matching `SECRET_OTA_HOST_*` is missing. Harmless; §3.2 to silence it. |
| Upload goes to the network when you wanted USB | Expected — `upload_protocol = espota` is the default here; §4.2. |
| Partition or size overflow | The image outgrew `min_spiffs.csv`'s app partition. `pio run -e <env> -t size` shows the margin. |
| `SDK location not found` (Gradle) | No `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties`; §1.4. |
| `Task ':app:…' not found` (Gradle) | `:app` is included only when an SDK is present. Same fix. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (adb) | A build signed with a different key is already installed. Uninstall first. |
| The plugin's web UI 404s after install | `public/` was never built, or was built before the last source change; §5. |
| Every intent POST returns 401 | The `/intent` route is admin-only on that server, or the token needs the other auth prefix; §5.2. |
| `DRIVEREMOTE_KEYSTORE names …, which is not a file` | The path is wrong. The build refuses to fall back to an unsigned APK; §6.5. |
| `DRIVEREMOTE_KEYSTORE is set but DRIVEREMOTE_KEYSTORE_PASSWORD is empty` | The password is not in this shell's environment; §6.5. |
| `Release-signed builds need the full git history` | Shallow clone: `git fetch --unshallow`; §6.5. |
| Release job: `refusing to publish` | The four keystore secrets are not set on the repository; §10.4. |
| Release job: `assembleRelease produced an UNSIGNED apk` | The keystore secret decoded to something Gradle would not use. Check the base64 round-trips. |
| Release job: `APK is not signed with the expected release certificate` | The secrets hold a different key than `DRIVEREMOTE_CERT_SHA256` names; §6.6. |
| Release job: `include/secrets.h is present in the checkout` | A real secrets file reached the runner. Nothing is built; §10.3. |
| Release job: `contains committed credentials` | A credential in a committed file reached a binary. Nothing is published; §10.3. |
| Release job: `public/ was not built` | The plugin's build step did not produce a UI, so the tarball would ship without one; §5. |
