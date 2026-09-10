# Security policy

This repository holds firmware and control software for the **Drive Remote Controller** —
a remote control for a motor yacht's two drives and its bow thruster. A security problem
here is not a data breach. It is a machine that moves when it should not, or that cannot
be stopped.

Please treat reports accordingly, and read
[the threat model](docs/SECURITY.md) before deciding
whether something is a finding: it lists what the system already knows it does not do,
including several weaknesses that are deliberate and documented rather than overlooked.

## Status

**The software runs on one boat, and has not been commissioned.** By its own
[SAFETY.md](docs/SAFETY.md) the handheld unit has never
been flashed on hardware at all. Treat anything published here as unproven on machinery.

A release pipeline exists and is run by hand, never automatically. A release carries the
three firmware images, the Signal K plugin and the Android station, each with a SHA-256, a
CycloneDX SBOM and a signed build-provenance attestation. The APK is signed with a release
key held only as a repository secret; **the firmware images are not signed and the boards
have no secure boot**, which is stated in the threat model rather than glossed over.

Published firmware is built from the template credentials: `secrets.h` is gitignored, the
release job refuses to build if one is present at all, and the pipeline then searches each
published image for any credential it can find in the committed tree, refusing to publish
if it finds one.

## Supported versions

Only the newest release, and the current `main` branch. Versions are `0.<commit count>`.

The three firmwares, the Signal K plugin and the Android station share a path and timing
contract that is hand-synced across three toolchains, so **"update everything together" is
the only supported configuration**. Mixing versions can produce a system that builds,
tests green and silently stops commanding.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.** Use GitHub's private
reporting on this repository: **Security › Report a vulnerability**. If that is not
available to you, contact the maintainer through their GitHub profile
([@KEGustafsson](https://github.com/KEGustafsson)) and ask for a private channel before
sending details.

Useful things to include:

- Which part is affected — a firmware unit (TX, RX or HH), the Signal K plugin and its
  arbiter, the browser UI, or the Android station.
- The commit you looked at.
- Whether it needs access to the boat's own network, a valid Signal K token, or neither.
- What an attacker gets: authority to command, the ability to prevent a stop, a false
  indication shown to the operator, or a credential.

That last one matters more than severity scoring here. A finding that breaks the **stop**
path, defeats the **fixed precedence** between stations, or makes a station display a
stale reading as live is more serious than one that only leaks information, whatever a
CVSS score would say.

You will get an acknowledgement within 7 days. Because nothing is distributed, a fix
lands on `main` and is deployed by hand; there is no release to wait for.

## Scope

**In scope**

- The three ESP32 firmwares in `esp32/`, including the
  shared pure control core.
- The Signal K plugin, its server-side arming arbiter, and the browser UI in
  `sk-plugin/`.
- The Android station in `android/`.
- The release tooling in `esp32/scripts/` and the workflows in `.github/`.

**Out of scope** — report these to their own projects, but tell us too if this code can
work around them:

- [SensESP](https://github.com/SignalK/SensESP) and the other upstream libraries.
- [signalk-server](https://github.com/SignalK/signalk-server), including its
  authentication, its access-request flow and its plugin routing.
- The Arduino ESP32 core, the ESP32 hardware itself, and the Android platform.
- The boat's WiFi network and access point.

**Already known, and documented rather than reported**

The [threat model](docs/SECURITY.md#3-the-known-weaknesses)
covers these in full; in short: the OTA password is a single shared static secret and the
value in use was committed to the predecessor repository, along with the WiFi passwords;
the firmware is unsigned and the boards have no secure boot; one dependency is tracked by
a moving branch ref; the Gradle build has no dependency verification; and traffic on the
boat's LAN is cleartext. A report that these are true is not a new finding.
A report that one of them is exploitable in a way the document does not describe is.

A finding in a **published release asset** always is worth reporting, whether or not the
underlying weakness is listed — in particular a released firmware image that contains a
credential, which the pipeline is built to make impossible.

## What the system does not rely on being secure

Worth stating, because it bounds the blast radius of everything above. Each unit's own
local controls win unconditionally over every remote station, the drive unit will not
take control unless both shift levers are proven in neutral, and every unit falls back to
neutral or off on any loss of link or authority rather than to whatever was last
commanded. Those properties are enforced in firmware on the unit and do not depend on any
network claim being true.

They are also the reason
[SAFETY.md](docs/SAFETY.md) is the more important
document of the two. A wiring error will hurt somebody long before an attacker does.
