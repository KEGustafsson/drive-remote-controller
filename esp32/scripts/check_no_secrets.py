#!/usr/bin/env python3
"""Refuse to publish a firmware binary that carries this repository's credentials.

A `secrets.h` holds the boat's WiFi and OTA passwords, and its values are
compiled INTO every firmware: `set_wifi_clients()` takes the SSIDs and
passwords, `enable_ota()` takes the OTA password. A `.bin` built from one
therefore contains the boat's WiFi password and the password that authorises
reflashing a board wired to a clutch and a thruster contactor, both recoverable
with `strings`.

`secrets.h` is NOT tracked in this repository, so a clean checkout has none and
the mains fall back to `secrets.example.h`. That is what makes released firmware
safe to publish, and it is not what this script checks.

What this checks is whatever the COMMITTED tree still holds. That is currently
nothing -- `platformio.ini` carried the OTA password until scripts/ota_auth.py
started injecting it from the untracked secrets.h -- so on a clean tree the
script reports that there is nothing to look for and passes. It is the backstop
that goes red the day a credential is written back into a committed file.

Point it at a local `secrets.h` with --secrets-file when checking a binary you
built yourself: those DO carry the real values, and nothing else checks them.

It is a byte search, not a string search, so it finds a value however the linker
laid it out, and it deliberately reads the reference values from git rather than
from the working tree -- the working tree is exactly what the workflow has just
overwritten.

Usage:
    python scripts/check_no_secrets.py --ref HEAD:esp32/platformio.ini build/*.bin
    python scripts/check_no_secrets.py --secrets-file include/secrets.h firmware.bin

Exit codes:
    0  no committed credential appears in any binary
    1  a credential was found, or a binary could not be read
    2  the reference secrets could not be obtained (fail closed)
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# #define NAME "value" -- the shape secrets.h uses. Anything after the closing
# quote is ignored: secrets.example.h puts a trailing `// tx-remote` comment on
# some lines, and requiring the quote at end-of-line skipped those defines
# entirely -- a credential written with a comment after it would have sailed
# past this guard unchecked.
DEFINE = re.compile(r'^\s*#define\s+(\w+)\s+"([^"]*)"')

# --auth=<password> in platformio.ini's upload_flags. platformio.ini no longer
# carries one -- scripts/ota_auth.py injects it from the untracked secrets.h at
# upload time -- but this pattern stays: it is what makes the guard notice if a
# password is ever written back into that file, which is exactly the regression
# that would put a credential into a public repository again.
AUTH = re.compile(r'^\s*--auth=(\S+)\s*$')

# Values that are placeholders rather than credentials: finding one of these in a
# binary is the CORRECT outcome, since a template build is made from them.
#
# Read from secrets.example.h rather than written out here. Hard-coding them made
# the template unchangeable in a way nothing announced: edit a value there and
# this guard would start treating the new placeholder as a real credential, find
# it in every template-built binary, and fail. The example file is the definition
# of "placeholder", so it is the thing to ask.
FALLBACK_PLACEHOLDERS = {
    "",
    "set-a-strong-ota-password",
    "your-wifi-ssid",
    "your-wifi-password",
}


def load_placeholders() -> set[str]:
    """Every value in secrets.example.h, plus the empty string."""
    example = Path(__file__).resolve().parent.parent / "include" / "secrets.example.h"
    try:
        text = example.read_text(encoding="utf-8", errors="replace")
    except OSError:
        # A caller running this from somewhere the template is not reachable
        # still gets the historical set, so the guard never becomes MORE
        # permissive than it was -- it just cannot learn about new placeholders.
        print(
            f"check_no_secrets: cannot read {example}; using the built-in placeholder list",
            file=sys.stderr,
        )
        return set(FALLBACK_PLACEHOLDERS)
    found = {""}
    for line in text.splitlines():
        m = DEFINE.match(line)
        if m:
            found.add(m.group(2))
    return found | FALLBACK_PLACEHOLDERS


PLACEHOLDERS = load_placeholders()

# A value this short cannot be a meaningful credential, and searching for it
# would match unrelated bytes in a megabyte of firmware.
MIN_LENGTH = 4


def secrets_from_git(ref: str) -> str:
    """The committed secrets file, read from git rather than the working tree."""
    try:
        out = subprocess.run(
            ["git", "show", ref],
            capture_output=True,
            check=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        print(f"check_no_secrets: cannot read {ref} from git: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
    return out.stdout


def parse(text: str, label: str = "") -> dict[str, str]:
    """Every credential-shaped value in this file that is worth checking for."""
    values: dict[str, str] = {}
    for line in text.splitlines():
        m = DEFINE.match(line)
        if m:
            name, value = m.group(1), m.group(2)
        else:
            m = AUTH.match(line)
            if not m:
                continue
            name, value = "upload_flags --auth", m.group(1)
        if value in PLACEHOLDERS or len(value) < MIN_LENGTH:
            continue
        values[f"{label}{name}" if label else name] = value
    return values


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--ref",
        action="append",
        default=[],
        help="git ref:path of a committed file holding credentials, e.g. "
        "HEAD:path/to/secrets.h. Repeatable.",
    )
    ap.add_argument(
        "--secrets-file",
        action="append",
        default=[],
        help="path to a file on disk holding credentials. Repeatable.",
    )
    ap.add_argument("binaries", nargs="+", help="the firmware .bin files to check")
    args = ap.parse_args()

    if not args.ref and not args.secrets_file:
        print("check_no_secrets: give at least one --ref or --secrets-file", file=sys.stderr)
        return 2

    values: dict[str, str] = {}
    for ref in args.ref:
        values.update(parse(secrets_from_git(ref), label=f"{ref.rsplit('/', 1)[-1]}:"))
    for name in args.secrets_file:
        path = Path(name)
        if not path.is_file():
            print(f"check_no_secrets: {path} is not a file", file=sys.stderr)
            return 2
        values.update(parse(path.read_text(encoding="utf-8", errors="replace"),
                            label=f"{path.name}:"))
    if not values:
        # Nothing to check for is the good end state -- it means every source
        # given holds only placeholders. Say so; do not fail.
        print("check_no_secrets: the sources given hold no real values")

    failed = False
    for name in sorted(values):
        print(f"check_no_secrets: checking for {name} ({len(values[name])} chars)")

    for binary in args.binaries:
        path = Path(binary)
        try:
            blob = path.read_bytes()
        except OSError as exc:
            print(f"check_no_secrets: cannot read {path}: {exc}", file=sys.stderr)
            failed = True
            continue

        hits = [name for name, value in values.items() if value.encode("utf-8") in blob]
        if hits:
            failed = True
            # The value itself is never printed -- this output goes to a build log.
            print(
                f"::error::{path.name} contains committed credentials: {', '.join(sorted(hits))}."
                " Build release firmware from secrets.example.h, never from a real secrets.h.",
                file=sys.stderr,
            )
        else:
            print(f"check_no_secrets: {path.name} is clean ({len(blob)} bytes)")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
