"""Take the OTA password and board address from include/secrets.h, not platformio.ini.

`enable_ota()` already compiles `SECRET_OTA_PASSWORD` out of `include/secrets.h`
into the firmware, so the board's OTA password comes from that file. The uploader
needs the same string, and it used to be written a SECOND time in platformio.ini
as `upload_flags = --auth=...` -- a real credential in a committed file, in a
PUBLIC repository, authorising the reflash of boards wired to a clutch and a
thruster contactor.

The three boards' addresses were committed the same way, as `upload_port` per
env. They are not credentials, but they are the boat's network laid out for
anybody reading the repository, and they belong with the rest of the site
configuration rather than in a file everyone can see.

This script closes both: it reads the one copy of each from secrets.h
(gitignored) and hands them to the uploader, so nothing committed here names a
password or an address.

What it expects in secrets.h -- see include/secrets.example.h for the template:

    #define SECRET_OTA_PASSWORD "..."     shared by all three units
    #define SECRET_OTA_HOST_TX  "..."     the address, or hostname, of each board
    #define SECRET_OTA_HOST_RX  "..."
    #define SECRET_OTA_HOST_HH  "..."

The suffix comes from the environment name: env:rx_shesp32 -> SECRET_OTA_HOST_RX.

Wiring, from platformio/builder/main.py:

    1. pre: extra scripts run          <- this script sets UPLOAD_PORT and UPLOAD_FLAGS
    2. the platform builder runs       <- espressif32 sets UPLOADERFLAGS for espota,
                                          and reads $UPLOAD_PORT
    3. if "UPLOAD_FLAGS" in env: env.Prepend(UPLOADERFLAGS=["$UPLOAD_FLAGS"])
    4. post: extra scripts run

Step 3 is the documented path for the `upload_flags` project option, and step 2
reads `UPLOAD_PORT` the same way it would have read `upload_port`, so setting
both in a pre-script reaches the uploader exactly as the ini did.

The password is injected only when an upload was actually asked for. CI builds
all three firmwares on a checkout with no secrets.h, and a missing file must not
fail a build -- only an upload, which cannot succeed without it anyway.

The address is set on a plain build too, quietly, whenever secrets.h supplies it.
Building does not need it: platform-espressif32's builder only writes

    Error: Please specify IP address or host name of ESP device using `upload_port`

to stderr and carries on -- `pio run` still ends in SUCCESS. But it writes it on
every build of an espota environment, and IDEs surface that line as a build
error, which is a false alarm nobody should have to diagnose twice. Handing the
builder the port it is looking for silences it at the source. A checkout without
secrets.h (CI again) just gets the harmless warning back; nothing fails either way.

Overrides still work: `pio run -e rx_shesp32 -t upload --upload-port 1.2.3.4`
sets UPLOAD_PORT before this runs, and it is left alone. That is the escape hatch
for flashing a board at an unexpected address.

It also does nothing unless the upload is actually going over the air. Serial
flashing a virgin board (docs/BUILDING.md section 4.2) means commenting
`upload_protocol = espota` out, which leaves esptool -- and `--auth=` is not a
flag esptool understands, so injecting it would break exactly the procedure a
newcomer follows first.
"""

import os
import re
import sys

Import("env")  # noqa: F821 -- injected by SCons

UPLOAD_TARGETS = {"upload", "uploadfs", "uploadfsota"}

# PlatformIO switches an unset protocol to espota when the port looks like an IP
# or an mDNS name (platform-espressif32 builder/main.py). Mirrored here so this
# script agrees with the uploader that eventually runs, rather than guessing.
LOOKS_OTA = re.compile(r'"?((([0-9]{1,3}\.){3}[0-9]{1,3})|[^\\/]+\.local)"?$')

# The shape secrets.h uses: #define NAME "value"
# Anything after the closing quote is ignored: secrets.example.h puts a trailing
# `// tx-remote` comment on each host line, and an earlier `"(.*)"\s*$` quietly
# refused to match those -- so a real address read as "not defined at all".
def _define(name):
    return re.compile(r'^\s*#define\s+%s\s+"([^"]*)"' % re.escape(name), re.M)


def _fail(message):
    """Stop the run with an explanation rather than a doomed upload."""
    print("ota_auth: %s" % message, file=sys.stderr)
    env.Exit(1)  # noqa: F821


def main():
    targets = set(COMMAND_LINE_TARGETS)  # noqa: F821 -- injected by SCons
    # An upload takes the password as well as the address, and says so loudly
    # when either is missing. A plain build takes the address only, silently:
    # it is there to keep the builder quiet, and must still work -- and stay
    # quiet -- on a checkout with no secrets.h, which is the path CI takes.
    uploading = bool(targets & UPLOAD_TARGETS)

    # Only OTA uploads take an --auth= flag. A serial flash (section 4.2) runs
    # esptool, which would reject it outright.
    protocol = env.subst("$UPLOAD_PROTOCOL")  # noqa: F821
    given_port = env.subst("$UPLOAD_PORT")  # noqa: F821
    if protocol != "espota" and not (not protocol and LOOKS_OTA.match(given_port)):
        return

    include_dir = env.subst("$PROJECT_INCLUDE_DIR")  # noqa: F821
    if not include_dir:
        include_dir = os.path.join(env.subst("$PROJECT_DIR"), "include")  # noqa: F821
    secrets = os.path.join(include_dir, "secrets.h")

    if not os.path.isfile(secrets):
        if uploading:
            _fail(
                "%s does not exist, so there is no OTA password or board address to\n"
                "          upload with. Copy include/secrets.example.h to include/secrets.h\n"
                "          and fill it in. See docs/BUILDING.md section 3.2." % secrets
            )
        return

    with open(secrets, encoding="utf-8", errors="replace") as handle:
        text = handle.read()

    # --- the password, shared by all three units -----------------------------
    # Only an upload needs it, and only an upload is entitled to fail over it.
    if uploading:
        match = _define("SECRET_OTA_PASSWORD").search(text)
        if not match or not match.group(1):
            _fail(
                "%s defines no non-empty SECRET_OTA_PASSWORD.\n"
                "          The board will reject an unauthenticated upload, so this stops\n"
                "          here rather than failing halfway through espota." % secrets
            )
            return
        # Never printed: this output goes to build logs.
        env.Append(UPLOAD_FLAGS=["--auth=%s" % match.group(1)])  # noqa: F821

    # --- the address of the board this env flashes ---------------------------
    # An explicit --upload-port wins: it is how you reach a board at an
    # unexpected address, and how the serial route in BUILDING.md 4.2 works.
    if given_port:
        if uploading:
            print("ota_auth: OTA password from include/secrets.h; "
                  "upload port left as given")
        return

    pioenv = env.subst("$PIOENV")  # noqa: F821 -- e.g. "rx_shesp32"
    unit = pioenv.split("_", 1)[0].upper()  # -> "RX"
    key = "SECRET_OTA_HOST_%s" % unit
    match = _define(key).search(text)
    if not match or not match.group(1):
        if uploading:
            _fail(
                "%s defines no non-empty %s, and no --upload-port was given.\n"
                "          espota needs an address for env:%s. Add %s to secrets.h, or\n"
                "          pass --upload-port explicitly." % (secrets, key, pioenv, key)
            )
        return

    env.Replace(UPLOAD_PORT=match.group(1))  # noqa: F821
    if uploading:
        print("ota_auth: OTA password and %s taken from include/secrets.h" % key)


main()
