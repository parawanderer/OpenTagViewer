"""Build the stub `unicorn` wheel from source.

FindMy.py >= 0.9 pulls in `anisette`, which requires `unicorn` (a CPU emulator used
only for *local* Anisette). Chaquopy cannot build unicorn's native code for Android,
so dependency resolution fails outright. This produces a pure-Python wheel exposing
just the surface anisette imports, which lets the rest of 0.9.x install.

The wheel is a *build artifact*, generated at Gradle configuration time from the real
source files in app/stubs/unicorn/. It is deliberately not checked in: a prebuilt
binary that claims to be a well-known dependency is hard to audit and easy to
mistake for the real thing.

Usage:
    python scripts/build_unicorn_stub_wheel.py <output-wheel-path>
"""

from __future__ import annotations

import base64
import csv
import hashlib
import sys
import zipfile
from io import StringIO
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_DIR = REPO_ROOT / "app" / "stubs" / "unicorn"

NAME = "unicorn"
# Must satisfy anisette's `unicorn>=2.1.1` constraint.
VERSION = "2.1.1"
DIST_INFO = f"{NAME}-{VERSION}.dist-info"

METADATA = f"""Metadata-Version: 2.1
Name: {NAME}
Version: {VERSION}
Summary: Unicorn CPU emulator engine - Android stub, no native code, raises on use
License: GPL-2
"""

WHEEL_METADATA = """Wheel-Version: 1.0
Generator: build_unicorn_stub_wheel.py
Root-Is-Purelib: true
Tag: py3-none-any
"""


def _record_row(path: str, data: bytes) -> tuple[str, str, str]:
    digest = hashlib.sha256(data).digest()
    digest_b64 = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return path, f"sha256={digest_b64}", str(len(data))


def _build_record(rows: list[tuple[str, str, str]]) -> bytes:
    output = StringIO()
    writer = csv.writer(output, lineterminator="\n")
    for row in rows:
        writer.writerow(row)
    writer.writerow((f"{DIST_INFO}/RECORD", "", ""))
    return output.getvalue().encode("utf-8")


def _collect_sources() -> dict[str, bytes]:
    if not SOURCE_DIR.is_dir():
        msg = f"stub source directory not found: {SOURCE_DIR}"
        raise FileNotFoundError(msg)

    sources: dict[str, bytes] = {}
    for path in sorted(SOURCE_DIR.rglob("*.py")):
        arcname = path.relative_to(SOURCE_DIR).as_posix()
        sources[arcname] = path.read_bytes()

    if not sources:
        msg = f"no .py files found under {SOURCE_DIR}"
        raise FileNotFoundError(msg)
    return sources


def build(output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)

    entries = _collect_sources()
    entries[f"{DIST_INFO}/METADATA"] = METADATA.encode("utf-8")
    entries[f"{DIST_INFO}/WHEEL"] = WHEEL_METADATA.encode("utf-8")

    rows: list[tuple[str, str, str]] = []
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for arcname, data in entries.items():
            # Fixed timestamp so the wheel is byte-for-byte reproducible; otherwise
            # Gradle sees a "changed" input on every configure and re-runs pip.
            info = zipfile.ZipInfo(arcname, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, data)
            rows.append(_record_row(arcname, data))

        record = zipfile.ZipInfo(f"{DIST_INFO}/RECORD", date_time=(1980, 1, 1, 0, 0, 0))
        record.compress_type = zipfile.ZIP_DEFLATED
        record.external_attr = 0o644 << 16
        zf.writestr(record, _build_record(rows))


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2

    output = Path(argv[1])
    build(output)
    print(f"Built stub wheel: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
