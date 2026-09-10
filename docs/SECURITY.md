# Drive Remote Controller — Security Design

What can go wrong, what the system does about it, and where it stands against the EU
Cyber Resilience Act's essential requirements. The disclosure policy is in the
[repository's SECURITY.md](../SECURITY.md); the code is described in
[ARCHITECTURE.md](ARCHITECTURE.md); the physical invariants and the commissioning
checklists are in [SAFETY.md](SAFETY.md).

**Read this alongside SAFETY.md, not instead of it.** Everything here is about
adversaries. SAFETY.md is about the far likelier case of nothing adversarial happening at
all and the system still needing to fail in the right direction.

---

## 1. What this is, security-wise

A remote control for a motor yacht's **two drives** and its **bow thruster**. Three
ESP32 units, a Signal K server plugin, a browser UI and a native Android app, all on the
boat's own WiFi. There is no cloud service, no account, and no traffic that leaves the
boat.

That makes the asset unusual. What is being protected is not data:

| Asset | Why it matters |
|---|---|
| **Authority over the machinery** | Whoever can arm and command can put a drive in gear or run the thruster. This is the asset. |
| **The ability to stop** | A denial of service against the disarm path is worse than one against the command path. |
| **Truthfulness of the display** | An operator acting on a confident-looking indication that is stale is being lied to at the dock. |
| The station access tokens and the OTA password | Both are keys to the first asset. |

Confidentiality barely appears. Nothing here is secret except the credentials, and a
recording of the traffic tells an attacker which way the boat is manoeuvring, which they
can also see by looking at it.

**The floor under all of it is physical.** Each unit's own local controls win
unconditionally over every remote station, the drive unit will not take control unless
both shift levers are proven in neutral, and every unit falls back to neutral or off on
any loss of link or authority rather than to whatever was last commanded. Those
properties are enforced in firmware on the unit itself and do not depend on any network
claim being true. An attacker who wins everything in the table below still does not beat
someone standing at the helm.

---

## 2. Threats, and what is done about them

| Threat | Where | What the system does |
|---|---|---|
| **A device on the boat LAN commands the machinery** | Anyone on the boat's WiFi | Commanding is a Signal K write, and both routes to it sit behind `signalk-server`'s own authentication: the `/plugins/*` intent route and delta writes. The plugin registers `/intent` through the access-scoped registrar at `readwrite`, so a station authenticates with a Signal K token or a session. **This is contingent on server security being enabled — see [§4](#4-the-assumptions-this-rests-on).** |
| **A second station takes over mid-manoeuvre** | Two phones, or a phone and the handheld | Precedence is fixed and never recency-based: a unit's own local controls, then the handheld TX, then the plugin tier. Within the plugin tier the server-side arbiter grants a single arm token to one client at a time; a second client sees IN USE and cannot arm. |
| **A stopped or crashed station leaves the machinery commanded** | Any station | The arm token is held only by a client that keeps heart-beating (250 ms). Stale eviction releases it, and no live holder means drives NEUTRAL, thruster OFF, trim 0. Each unit additionally runs its own arrival-based liveness watchdog and falls safe on its own. |
| **A replayed or reordered command** | Anyone who can capture LAN traffic and post to the route | Each intent carries a per-client `seq`; one not strictly greater than the last accepted is discarded whole, so a delayed heartbeat re-delivered out of order can neither revert live commands nor touch the edge baselines. `armReq`/`disarmReq` are rising-edge counters whose stored baselines are clamped monotonic, so a replayed old counter can never read as a fresh operator tap. This closed a real silent re-arm found in the 2026-07-23 review. |
| **A malformed or hostile value crashes a unit or produces a surprising output** | Network, web UI, or a hand-crafted POST | Untrusted input is screened where it enters. Unrecognised command strings read as the safe value (`neutral`, `off`, `hold`) rather than being guessed at; non-finite numbers are rejected; trim is clamped to ±45°. The same rules exist three times — `arbiter.cjs`, the firmware's pure core, and the Android core — and are vector-tested in each. |
| **Resource exhaustion on the plugin** | Many clients, or a script | The arbiter tracks at most 32 clients and evicts the least recently seen. Intents are small fixed-shape JSON. |
| **A station keeps commanding after its authority is withdrawn** | Token revoked or expired server-side | Consecutive rejections past the auth-scheme probe end the session and return the station to asking for authorisation. A 401 alone is not treated as proof — the `Bearer`/`JWT` probe produces them by design — and a transport failure is never counted, because losing the network is not losing authority. |
| **A station's token is captured in transit** | The boat LAN | Only partly addressed, and honestly: the boat's Signal K server is plain HTTP on a private network, so a token on that LAN is readable by anyone already on that LAN. What the Android station does enforce is that the token never crosses a link where a *stranger* could read it: `PrivateAddress.isPrivateHost()` refuses cleartext to anything not on a private network, decided in tested Kotlin, before anything is stored and before any socket opens. Anything the parser does not recognise is refused rather than trusted; `https://` is allowed unconditionally. |
| **A stolen or sold phone carries the token onward** | Physical | The token is kept in `KeystoreEncryptedPreferences` — AES-256-GCM with a fresh IV per write, under a key generated in and never leaving the Android Keystore. The app sets `allowBackup="false"`, and the token is bound to the server that issued it. A token past its stated expiry is dropped at startup. A value that will not decrypt (key invalidated, data restored to another device) reads as absent, so the station asks for a new token rather than failing obscurely. |
| **A device on the LAN reflashes a unit** | The boat LAN | The OTA password, and nothing else. Uploads use `espota` with `--auth=`; a board rejects an unauthenticated upload. **This is a single shared static password, the same on all three units, and the value in use was committed to the predecessor repository — see [§3](#3-the-known-weaknesses).** |
| **A compromised dependency reaches a board** | Supply chain | Weakly addressed. See [§3](#3-the-known-weaknesses). |
| **An operator is shown a confident reading that is no longer live** | Any station | Treated as a safety defect rather than a cosmetic one. Liveness is judged on message *arrival*, never on a value, because Signal K retains a path's last value forever and a switched-off unit therefore leaves its own `linkUp` reading `true` indefinitely. This was a real reported bug: an all-green panel that would arm for a board with no power. Every derived indication greys out when its source is not arriving. |
| **The stop path is blocked by something failing** | Any station | Disarm is never gated on anything. It travels over HTTP and stays live while the read-side WebSocket is down; the kill switch never greys out; a disconnect is refused while armed rather than leaving a station holding the token behind a screen with no controls. Command widgets grey out and go inert when they could not reach their machine, and the stop path deliberately does not. |

---

## 3. The known weaknesses

Stated plainly, because a security document that only lists mitigations is marketing.

**No credential is committed, and none ever was here.**
`esp32/include/secrets.h` — the boat's WiFi passwords and the OTA password — is gitignored,
and `scripts/ota_auth.py` injects the OTA password from it at upload time so
`platformio.ini` needs no second copy. This repository's entire history has been searched
for every value and holds none of them.

They do exist in the *predecessor* repository's history, and the same OTA password is
reused by the sibling `SensESP_engines` project — so one disclosure would reach the boat's
WiFi, all three units and that project together. **Both of those repositories are private
and stay that way (owner decision, 2026-09-10)**, which closes the route: nothing has been
disclosed and nothing is pending.

What would reopen it is making either repository public, archiving it publicly or sharing
it. [BUILDING.md §8](BUILDING.md#8-credentials-and-what-must-be-rotated) is the procedure
if that day comes.

**The OTA password is the whole barrier to reflashing a board that drives a clutch and a
thruster contactor.** It is one static shared secret, the same on all three units, with
no rate limiting and no second factor. Anyone on the boat's LAN who has it can put
arbitrary firmware on a unit wired to machinery.

**The firmware is not signed, and the boards have no secure boot.** A release now signs
the Android APK with a release key, attaches a SHA-256 and a CycloneDX SBOM to every
artifact, and signs a build-provenance attestation over the set
([BUILDING.md §10](BUILDING.md#10-releases)). None of that reaches the firmware images
themselves: an ESP32 here will run whatever is flashed to it, and the only thing gating a
flash is the OTA password above. Signed firmware and secure boot would be the fix, and
neither exists.

**One dependency is tracked by a moving branch ref.** All three firmwares build against
`KEGustafsson/SensESP#fix_analog_input`, which can change under any build with no diff in
this repository. A build is therefore not reproducible from `platformio.ini` alone, and a
compromise of that branch reaches a board at the next flash. `ESP32Servo` is pinned to an
exact commit; SensESP deliberately is not. Each release's firmware SBOM records the
versions that were actually resolved, which is the only place a given binary's
dependencies are written down — but nothing watches that branch between releases, because
Dependabot has no PlatformIO ecosystem.

**The Gradle build has no dependency verification.** There is no
`gradle/verification-metadata.xml`, so a dependency update is accepted on the strength of
the tests and the lockfile alone. The npm side has `npm ci` against a committed
`package-lock.json`; the PlatformIO side has neither.

**Traffic on the boat LAN is cleartext.** The Signal K server is plain HTTP, so tokens,
commands and telemetry are readable by anything already on that network. The Android
station's cleartext gate bounds *where* that can happen (private networks only); it does
not encrypt anything.

**The `plugin.*` paths are only conventionally single-writer.** Signal K cannot enforce
per-path write ownership, so an authenticated client could publish those paths directly
and bypass the arbiter entirely. The arbiter being "sole writer" is a property of nobody
else trying.

**Published firmware must be built from the template secrets, and this is enforced rather
than remembered.** A `secrets.h`'s values are compiled in, so a firmware binary built from
a working tree that has one carries the boat's WiFi password and the OTA password.
Measured, not assumed: run against a real locally built image on 2026-09-09, the guard
found **all five** values in it — the OTA password, both WiFi passwords and both SSIDs —
and again in the merged factory image. That is what a binary you build yourself still
looks like; it is only the *published* ones that are built from the template.

The release pipeline builds from `secrets.example.h` and then verifies the result:
`esp32/scripts/check_no_secrets.py` reads the real values out of git and fails the release
if any appears in a published image. **A binary built by hand and passed to somebody has
had no such check.**

**Most of this has never run on a boat.** As of 2026-07-26 the drive and heading-hold
units have run on the bench and the Android station has commanded both machines against
the live server; the handheld TX has never been flashed at all, and the drive unit's
gear-neutral arm interlock landed after its bench session. SAFETY.md's commissioning
checklists are the gate, not this document.

---

## 4. The assumptions this rests on

**Signal K server security must be enabled.** With it set to "none", any device on the
LAN can post intents or publish `plugin.*` deltas directly, and the entire remote
authority model in §2 evaporates. The physical local controls and each unit's own
fail-safes do not depend on this; remote arming discipline depends on it completely.

**The boat's WiFi is the perimeter.** Everything here treats the boat's private network
as trusted enough to carry a cleartext token. Anyone on that network is inside the
perimeter, which makes the WiFi password a control of the same rank as the OTA password.

**Physical access is out of scope.** Anyone who can reach the boards can reach their
outputs directly, and no software property survives that.

**The units' own local controls are the last line, and they are hardware.** They are
assumed wired correctly, which is what SAFETY.md's commissioning checklists exist to
confirm.

---

## 5. CRA Annex I mapping (informative)

The EU Cyber Resilience Act, [Regulation (EU) 2024/2847](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/eng),
entered into force on 10 December 2024. Its reporting obligations for actively exploited
vulnerabilities and severe incidents (Article 14) apply from **11 September 2026**, and
the remaining obligations — the essential requirements, conformity assessment, CE marking
and technical documentation — from **11 December 2027**.

**The Act's manufacturer obligations do not apply to this project.** It is a
non-commercial project for one boat; nothing is placed on the market, made available, or
supplied in the course of a commercial activity, and the CRA's scope turns on exactly
that. Publishing the source publicly under the EUPL does not change it: free and
open-source software not supplied in the course of a commercial activity is outside scope,
and there is no open-source steward within the meaning of Article 24.

Its essential requirements are still the best available checklist for a device that
commands machinery, so this is where the system stands against each. Rows are marked
**partial** or **gap** where that is the truth; the ones marked gap are the same items as
[§3](#3-the-known-weaknesses).

### Annex I, Part I — security properties

| Requirement | Status |
|---|---|
| (1) Appropriate level of cybersecurity based on the risks | **Yes.** Threat model in §2; the risk is authority over machinery on a shared LAN, not data disclosure. Every safety decision lives in a pure, host-tested core reproduced in three languages with shared test vectors. |
| (2)(a) No known exploitable vulnerabilities at release | **Partial.** Dependabot proposes grouped weekly updates for Gradle, npm and the Actions, and is live. CodeQL is configured for five languages — including the firmware's pure control core, through its native build — and verified to analyse, but **is switched off**: code scanning has not been enabled on the repository, so its jobs are gated behind a repository variable and a status job reports that on every run. It is free to enable now that the repository is public. Two further gaps are structural: no scanning follows the firmware's branch-tracked SensESP dependency, and there is no Gradle dependency verification. |
| (2)(b) Secure by default configuration | **Yes.** The plugin installs **disabled**; no station comes armed; arming is edge-triggered and requires a live unit; the drive unit refuses control unless both levers are proven neutral; the app opens in MANUAL with trim 0. Every default is the inert one. |
| (2)(c) Security updates | **Partial.** There is now a release channel: a manually triggered pipeline builds every artifact, signs the APK, and publishes it with a checksum, an SBOM and a provenance attestation. The firmware half is still weak — OTA is manual, the image is unsigned, the boards have no secure boot, and one shared static password authorises a flash. There is no automatic update anywhere, by design: nothing here calls out to a server. |
| (2)(d) Protection from unauthorised access | **Partial.** Commanding requires authentication through `signalk-server`, and the intent route is registered at `readwrite` so it admits token stations rather than only admin sessions. It rests entirely on server security being enabled (§4), and on an OTA password that is a single static secret shared by all three units, the boat's WiFi and a sibling project. |
| (2)(e) Confidentiality of data | **Partial.** Tokens at rest are encrypted with AES-256-GCM under an Android Keystore key, with cloud backup disabled, and the Android station refuses cleartext off private networks. Encryption failing drops the write rather than storing plaintext, and that is counted rather than silent. On the boat's own LAN everything is plain HTTP by construction. |
| (2)(f) Integrity of data, commands and configuration | **Yes, at the application layer.** Per-client monotonic `seq`, monotonic-clamped arm/disarm edge baselines, strict validation at every boundary with unrecognised values degrading to the safe one, and a single server-side authority as the sole intended writer of the command paths. No transport-layer integrity: the LAN is cleartext, so this is replay and ordering resistance, not authentication of the wire. |
| (2)(g) Data minimisation | **Yes.** The system carries switch positions, commands, headings and liveness. Nothing is recorded, nothing is stored beyond each station's own settings and token, and nothing leaves the boat. |
| (2)(h) Availability of essential functions, resilience to DoS | **Yes, and it is the design centre.** Every unit falls to neutral or off on loss of link, liveness is arrival-based so a silent unit is detected rather than assumed live, the arm token auto-releases when its holder stops heart-beating, the arbiter bounds tracked clients at 32, and the disarm path is never gated on anything — including on the health of the transport that carries the telemetry. |
| (2)(i) Minimising impact on other services | **Yes.** Small periodic messages at 250 ms on the boat's own LAN, one WebSocket subscription per station, mDNS discovery that is optional by design because boat access points not uncommonly block multicast. |
| (2)(j) Limited attack surface | **Yes.** No cloud service, no account, no inbound service on any unit beyond what SensESP's configuration portal and OTA expose, three plain permissions on the Android app (`INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`), and no third-party networking, crypto or analytics libraries in the app beyond AndroidX, Compose and OkHttp. |
| (2)(k) Reduced impact of incidents | **Partial.** A compromised token is revoked in the Signal K admin UI and the station returns to asking for authorisation. A compromised OTA or WiFi password requires rotating and reflashing every board (BUILDING.md §8). Underneath both, the local controls and each unit's fail-safes are unaffected by anything on the network. |
| (2)(l) Security-relevant logging | **Partial.** Each unit logs to serial and over `/api/log`; the stations display link, authority and per-unit liveness, and the arbiter's verdicts are published as `plugin.activeClient`, `plugin.rxLive` and `plugin.hhLive`. Nothing is retained, and there is no audit record of who armed when. |
| (2)(m) Secure deletion | **Yes.** Uninstalling the app removes its private storage including the encrypted token; a unit's stored configuration is erased by reflashing. |

### Annex I, Part II — vulnerability handling

| Requirement | Status |
|---|---|
| (1) Identify and document components (SBOM) | **Yes.** Every release carries a CycloneDX 1.5 SBOM per artifact: the Android classpath with a SHA-256 per component, the plugin's whole npm tree, and each firmware's resolved PlatformIO packages. The firmware SBOM is the only record of what a branch-tracked dependency resolved to for that build. |
| (2) Address vulnerabilities without delay | Fixes land on `main` through CI; deployment is a manual OTA or a manual plugin reinstall. |
| (3) Regular testing and review | CI runs the three pure suites, the three firmware builds with a size report, the plugin's full suite and the Android layout floors on every push and pull request. CodeQL would add `security-and-quality` over C++, Kotlin, TypeScript, Python and the workflows, but cannot upload findings until code scanning is enabled on the repository. |
| (4) Public disclosure of fixed vulnerabilities | Would be through the repository's advisories; nothing has been published. |
| (5) Coordinated vulnerability disclosure policy | **In place** — [SECURITY.md](../SECURITY.md) at the repository root. |
| (6) Contact address for reporting | **In place**, same document. |
| (7) Secure distribution of updates | **Partial.** Releases are built by a workflow whose publishing job holds no secrets and runs no repository code, the APK is signed with a key held only as a repository secret and checked against an expected certificate before publishing, and every binary carries a checksum. Provenance attestation is attempted but **may not be available**: it needs a public repository, or a private one owned by an organisation on a plan that includes it. When it fails the release still publishes and says plainly that it was not attested. The firmware images are unsigned and the boards have no secure boot. |
| (8) Timely and free security patches | Would apply if this were distributed. It is not, beyond the repository's own Releases. |

### What would still have to change before distributing this

Not a roadmap, and not a commitment — the honest list of what the gaps above would cost if
this ever went to a second boat. Three items that used to be on it are done, and are
struck through rather than deleted so the record of what changed stays readable.

1. ~~Rotate every credential that has ever been committed~~ — **closed by owner decision,
   2026-09-10.** `secrets.h` is untracked, `platformio.ini`'s duplicate is gone, and this
   repository's history has never held either. The remaining copies are in two private
   repositories that stay private, so the reuse has no route to disclosure. Making either
   public would reopen this before anything else
   ([BUILDING.md §8](BUILDING.md#8-credentials-and-what-must-be-rotated)).
2. Per-unit OTA passwords at minimum; signed firmware images and secure boot properly.
   Still open, and it is the largest remaining gap.
3. Pin the SensESP dependency to a commit rather than a branch. Still open. ~~Produce an
   SBOM per build~~ — done, one per artifact per release.
4. ~~A release-signed APK with a keystore held outside the repository~~ — done, with the
   certificate checked against an expected fingerprint before anything is published.
5. ~~Automated dependency scanning~~ — done for Gradle, npm and the Actions. Code scanning
   is written and verified to analyse all five languages, but **cannot run until code
   scanning is enabled on the repository**, which is now a free setting rather than a paid
   add-on. Neither covers the firmware's PlatformIO dependencies, which no tool here
   watches.
6. Gradle dependency verification (`gradle/verification-metadata.xml`). Still open.
7. Work through SAFETY.md's commissioning checklists on real hardware, which gates all of
   this anyway.
