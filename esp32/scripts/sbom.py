#!/usr/bin/env python3
"""Write a CycloneDX 1.5 software bill of materials for one PlatformIO firmware.

PlatformIO has no SBOM of its own, but it leaves a machine-readable `.piopm`
file beside every package it installs -- the platform, the framework, each
toolchain, and every library resolved from `lib_deps`. That metadata is what
this reads, so the SBOM describes the packages that actually went into the
build rather than what `platformio.ini` asked for.

The distinction matters here more than it usually would. This project tracks
its SensESP dependency by BRANCH (see docs/BUILDING.md section 3.1), so the
declared dependency moves under the build and only the resolved version
recorded here says what a given firmware was made of.

Usage:
    python scripts/sbom.py --env rx_shesp32 --version 0.147 --output bom.json

Exits non-zero on an unusable argument, an unwritable output path, or fewer
components than --min-components asks for. A single package whose metadata
cannot be read is reported on stderr and skipped, so one odd package cannot fail
a release -- but a wholesale failure to find anything can, which is the point of
--min-components.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

SPEC_VERSION = "1.5"


def core_dir() -> Path:
    """PlatformIO's core directory, where platforms and tool packages live."""
    env = os.environ.get("PLATFORMIO_CORE_DIR")
    return Path(env) if env else Path.home() / ".platformio"


def read_piopm(path: Path) -> dict | None:
    """Parse one `.piopm`, or return None and say why on stderr."""
    try:
        with path.open(encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as exc:
        print(f"sbom: skipping {path}: {exc}", file=sys.stderr)
        return None
    name = data.get("name")
    if not name:
        print(f"sbom: skipping {path}: no name", file=sys.stderr)
        return None
    spec = data.get("spec") or {}
    return {
        "type": data.get("type") or "library",
        "name": name,
        "version": str(data.get("version") or "unknown"),
        "uri": spec.get("uri") or "",
        "owner": spec.get("owner") or "",
    }


def collect(env: str, project_dir: Path) -> list[dict]:
    """Every package behind this environment, de-duplicated by name+version."""
    roots = [
        project_dir / ".pio" / "libdeps" / env,
        core_dir() / "platforms",
        core_dir() / "packages",
    ]
    found: dict[tuple[str, str], dict] = {}
    for root in roots:
        if not root.is_dir():
            print(f"sbom: no packages under {root}", file=sys.stderr)
            continue
        for piopm in sorted(root.glob("*/.piopm")):
            pkg = read_piopm(piopm)
            if pkg is not None:
                found[(pkg["name"], pkg["version"])] = pkg
    return [found[k] for k in sorted(found)]


def component(pkg: dict) -> dict:
    """One CycloneDX component. `pkg:generic` because these are not Maven or npm."""
    purl = f"pkg:generic/{pkg['name']}@{pkg['version']}"
    comp: dict = {
        "type": "library",
        "name": pkg["name"],
        "version": pkg["version"],
        "purl": purl,
        "bom-ref": purl,
        "properties": [{"name": "platformio:type", "value": pkg["type"]}],
    }
    if pkg["owner"]:
        comp["group"] = pkg["owner"]
    if pkg["uri"]:
        comp["externalReferences"] = [{"type": "distribution", "url": pkg["uri"]}]
    return comp


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--env", required=True, help="PlatformIO environment, e.g. rx_shesp32")
    ap.add_argument("--version", required=True, help="release version, e.g. 0.147")
    ap.add_argument("--output", required=True, help="path to write the CycloneDX JSON to")
    ap.add_argument(
        "--project-dir",
        default=".",
        help="the PlatformIO project directory (default: the working directory)",
    )
    ap.add_argument(
        "--commit", default="", help="the git commit this firmware was built from, if known"
    )
    ap.add_argument(
        "--min-components",
        type=int,
        default=0,
        help="fail if fewer than this many components were found. A release should "
        "set this: an SBOM listing nothing is a valid CycloneDX document and a "
        "useless one, and it would ship looking exactly like a real one.",
    )
    args = ap.parse_args()

    project_dir = Path(args.project_dir).resolve()
    packages = collect(args.env, project_dir)
    if not packages:
        print(
            "sbom: found no PlatformIO packages at all -- has the firmware been built?",
            file=sys.stderr,
        )
    if len(packages) < args.min_components:
        print(
            f"::error::sbom: found {len(packages)} components, expected at least "
            f"{args.min_components}. Refusing to write an SBOM that would understate "
            f"what went into this build.",
            file=sys.stderr,
        )
        return 1

    app_ref = f"pkg:generic/drive-remote-controller-{args.env}@{args.version}"
    metadata: dict = {
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "tools": {
            "components": [
                {
                    "type": "application",
                    "name": "drive-remote-controller esp32/scripts/sbom.py",
                    "version": args.version,
                }
            ]
        },
        "component": {
            "type": "firmware",
            "name": f"drive-remote-controller-{args.env}",
            "version": args.version,
            "bom-ref": app_ref,
        },
    }
    if args.commit:
        metadata["component"]["properties"] = [
            {"name": "git:commit", "value": args.commit}
        ]

    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": SPEC_VERSION,
        "serialNumber": f"urn:uuid:{uuid.uuid4()}",
        "version": 1,
        "metadata": metadata,
        "components": [component(p) for p in packages],
        "dependencies": [
            {"ref": app_ref, "dependsOn": [f"pkg:generic/{p['name']}@{p['version']}" for p in packages]}
        ],
    }

    out = Path(args.output)
    if out.parent and not out.parent.exists():
        out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(bom, indent=2) + "\n", encoding="utf-8")
    print(f"sbom: {out} ({len(packages)} components)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
