"""Report the oldest macOS a built binary can run on, and fail if it is too new.

The export wizard exists to be run on *old* Macs. macOS 15 tightened keychain access so the
BeaconStore key can no longer be read automatically, so every user this app serves is on macOS
14 or earlier - while the machine that builds it is whatever GitHub currently offers, which is
now newer than that.

A binary built on a newer macOS usually still runs on older ones, but only if the deployment
target recorded in its Mach-O headers is low enough, and only if nothing it bundles was built
with a higher one. Homebrew bottles are the classic trap: they are built for the builder's OS
and are not backward compatible, so a single Homebrew dylib pulled into the bundle can make
the whole app refuse to launch. That failure happens on a user's machine, at startup, with no
traceback - the same shape as the `Abort trap: 6` this project already hit from the other
direction on a test VM.

So this reads what the binary actually declares instead of assuming:

  * `LC_BUILD_VERSION` -> `minos`, on anything built with a modern toolchain
  * `LC_VERSION_MIN_MACOSX` -> `version`, on older binaries

The maximum across every Mach-O file in the bundle is the real floor: the app can only run
where *all* of its parts can.

Policy: always print the floor, and fail only when it exceeds the newest macOS the wizard
supports. A hard failure means the build is unusable by the entire user base, which is worth
stopping a release for. Anything lower is reported and left alone, because the exact floor
depends on how the interpreter itself was built and is not ours to dictate.

Usage
-----
    python scripts/check_macos_min_version.py python/dist/OpenTagViewer.app
    python scripts/check_macos_min_version.py python/dist --max-supported 14.0
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# macOS 15 changed keychain access, so the wizard cannot work there. A build that will not
# start on 14 cannot be used by anyone.
DEFAULT_MAX_SUPPORTED = "14.0"

MINOS = re.compile(r"^\s*minos\s+([0-9]+(?:\.[0-9]+)*)\s*$", re.MULTILINE)
VERSION_MIN = re.compile(r"^\s*version\s+([0-9]+(?:\.[0-9]+)*)\s*$", re.MULTILINE)


def parse_version(text: str) -> tuple[int, ...]:
    return tuple(int(part) for part in text.split("."))


def format_version(version: tuple[int, ...]) -> str:
    return ".".join(str(part) for part in version)


def minimum_versions(otool_output: str) -> list[tuple[int, ...]]:
    """Every minimum-macOS declaration in `otool -l` output, in either encoding."""
    found = MINOS.findall(otool_output)

    # LC_VERSION_MIN_MACOSX also has a `version` line; LC_BUILD_VERSION has `sdk` instead, so
    # the two do not collide.
    found += VERSION_MIN.findall(otool_output)

    return [parse_version(value) for value in found]


def highest_requirement(requirements: dict[Path, tuple[int, ...]]) -> tuple[Path, tuple[int, ...]] | None:
    """The file demanding the newest macOS - the one that decides where the app can run."""
    if not requirements:
        return None
    path = max(requirements, key=lambda p: requirements[p])
    return path, requirements[path]


def scan(target: Path) -> dict[Path, tuple[int, ...]]:
    """Map every Mach-O file under `target` to the oldest macOS it will run on."""
    files = [target] if target.is_file() else sorted(p for p in target.rglob("*") if p.is_file())

    requirements: dict[Path, tuple[int, ...]] = {}
    for path in files:
        try:
            result = subprocess.run(["otool", "-l", str(path)], capture_output=True, text=True,
                                    check=False, encoding="utf-8", errors="replace")
        except FileNotFoundError:
            print("ERROR: otool not found. This check only runs on macOS.", file=sys.stderr)
            raise SystemExit(2)

        if result.returncode != 0:
            continue  # not a Mach-O file

        versions = minimum_versions(result.stdout)
        if versions:
            requirements[path] = max(versions)

    return requirements


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n")[0])
    parser.add_argument("target", type=Path, help="a built binary, or a directory to walk")
    parser.add_argument("--max-supported", default=DEFAULT_MAX_SUPPORTED,
                        help=f"fail if the binary needs newer than this (default "
                             f"{DEFAULT_MAX_SUPPORTED}, the newest macOS the wizard supports)")
    args = parser.parse_args(argv)

    if not args.target.exists():
        print(f"ERROR: {args.target} does not exist", file=sys.stderr)
        return 2

    requirements = scan(args.target)
    highest = highest_requirement(requirements)

    if highest is None:
        print(f"ERROR: found no Mach-O files under {args.target}", file=sys.stderr)
        return 2

    path, version = highest
    limit = parse_version(args.max_supported)

    print(f"Scanned {len(requirements)} Mach-O file(s) under {args.target}")
    print(f"Oldest macOS this build can run on: {format_version(version)}")
    print(f"  set by: {path}")

    if version > limit:
        print(
            f"\nERROR: this build requires macOS {format_version(version)}, but the wizard is "
            f"only usable on {args.max_supported} and earlier - macOS 15 tightened keychain "
            f"access so the BeaconStore key cannot be read there.\n"
            f"Nobody who needs this app could run this binary.\n"
            f"\n"
            f"The usual cause is a dependency built on the runner's own macOS - Homebrew "
            f"bottles in particular are built for the builder's OS and are not backward "
            f"compatible.\n"
            f"Check what {path.name} pulled in, and that MACOSX_DEPLOYMENT_TARGET is set for "
            f"the build.",
            file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
