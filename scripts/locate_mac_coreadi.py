#!/usr/bin/env python3
"""
Check where Apple's macOS CoreADI sits in the iTunes 12.8.3 installer, and optionally extract it.

The app fetches the library with one HTTP range request into a 291 MB disk image
(`MacCoreAdiFetcher`). That only works because, in that exact image, the package payload holding
the library is stored uncompressed and in one piece, so its byte range is a constant. This script
is how anybody can confirm the constants rather than take them on trust.

The constants are read from the Java sources, not copied here, so the two cannot drift apart:
`PAYLOAD_OFFSET`, `PAYLOAD_LENGTH`, `PAYLOAD_SHA256` and `DMG_URL` from `MacCoreAdiFetcher.java`,
`BUILD_SHA256` from `MacCoreAdi.java`.

    # Ask Apple for the range, the way the app does, and check both hashes
    python scripts/locate_mac_coreadi.py --remote

    # The same against a full copy of the image, and show which chunks of it the range spans
    python scripts/locate_mac_coreadi.py --dmg iTunes12.8.3.dmg

    # Also write the x86_64 library out (the host check in app/src/test/macadi-host/ uses it)
    python scripts/locate_mac_coreadi.py --remote --out /tmp/CoreADI.x86_64

Apple's library is never written into the repository. `--out` is for a scratch location.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import plistlib
import re
import struct
import sys
import urllib.request
from pathlib import Path
from typing import NamedTuple

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/dev/wander/android/opentagviewer/anisette"
FRAMEWORK_PATH = "System/Library/PrivateFrameworks/CoreADI.framework/Versions/A/CoreADI"
CPU_TYPE_X86_64 = 0x01000007


def constant(source: str, name: str) -> str:
    """The value of `NAME = ...;` in a Java source, with string concatenation and `_` folded."""
    match = re.search(rf"\b{name}\s*=\s*(.*?);", source, re.DOTALL)
    if match is None:
        raise SystemExit(f"{name} not found in the Java sources")
    text = match.group(1)
    strings = re.findall(r'"([^"]*)"', text)
    if strings:
        return "".join(strings)
    return text.strip().rstrip("L").replace("_", "")


class Constants(NamedTuple):
    url: str
    offset: int
    length: int
    payload_sha256: str
    build_sha256: str


def load_constants() -> Constants:
    fetcher = (JAVA / "MacCoreAdiFetcher.java").read_text()
    adi = (JAVA / "MacCoreAdi.java").read_text()
    return Constants(
        url=constant(fetcher, "DMG_URL"),
        offset=int(constant(fetcher, "PAYLOAD_OFFSET")),
        length=int(constant(fetcher, "PAYLOAD_LENGTH")),
        payload_sha256=constant(fetcher, "PAYLOAD_SHA256"),
        build_sha256=constant(adi, "BUILD_SHA256"),
    )


def fetch_range(url: str, offset: int, length: int) -> bytes:
    request = urllib.request.Request(
        url, headers={"Range": f"bytes={offset}-{offset + length - 1}"})
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status != 206:
            raise SystemExit(f"Apple answered HTTP {response.status} to the range; expected 206")
        return response.read()


def file_from_cpio(cpio: bytes, wanted: str) -> bytes:
    """One file out of an odc cpio archive - the same walk as MacCoreAdiFetcher.fileFromCpio."""
    pos = 0
    while pos + 76 <= len(cpio):
        if cpio[pos:pos + 6] != b"070707":
            raise SystemExit(f"not an odc cpio archive at offset {pos}")
        name_size = int(cpio[pos + 59:pos + 65], 8)
        file_size = int(cpio[pos + 65:pos + 76], 8)
        name = cpio[pos + 76:pos + 76 + name_size - 1].decode()
        data = pos + 76 + name_size
        if name == "TRAILER!!!":
            break
        if name.removeprefix("./") == wanted and file_size > 0:
            return cpio[data:data + file_size]
        pos = data + file_size
    raise SystemExit(f"{wanted} is not in the payload")


def x86_64_slice(fat: bytes) -> bytes:
    magic, count = struct.unpack(">II", fat[:8])
    if magic != 0xCAFEBABE:
        raise SystemExit("the library is not a universal binary")
    for i in range(count):
        cpu, _, offset, size, _ = struct.unpack(">IIIII", fat[8 + 20 * i:28 + 20 * i])
        if cpu == CPU_TYPE_X86_64:
            return fat[offset:offset + size]
    raise SystemExit("the library has no x86_64 slice")


def chunks_spanned(dmg: Path, offset: int, length: int) -> list[str]:
    """Which UDIF chunks the range lies in, and that they are stored raw and back to back."""
    with dmg.open("rb") as f:
        f.seek(-512, 2)
        koly = f.read(512)
        if koly[:4] != b"koly":
            raise SystemExit("not a UDIF disk image")
        xml_offset, xml_length = struct.unpack(">QQ", koly[0xD8:0xE8])
        f.seek(xml_offset)
        resources = plistlib.loads(f.read(xml_length))

    spanned = []
    for part in resources["resource-fork"]["blkx"]:
        table = part["Data"]
        count = struct.unpack(">I", table[200:204])[0]
        for i in range(count):
            kind, _, _, _, at, size = struct.unpack(">IIQQQQ", table[204 + 40 * i:244 + 40 * i])
            if at < offset + length and at + size > offset:
                if kind != 1:
                    raise SystemExit(f"the range crosses a compressed chunk (type {kind:#x})")
                spanned.append(f"raw chunk at {at}, {size} bytes")
    return spanned


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--remote", action="store_true", help="range-fetch from Apple")
    source.add_argument("--dmg", type=Path, help="a local copy of iTunes12.8.3.dmg")
    parser.add_argument("--out", type=Path, help="write the x86_64 library here")
    args = parser.parse_args()

    c = load_constants()
    print(f"payload: bytes {c.offset}..{c.offset + c.length - 1} of the image")

    if args.remote:
        payload = fetch_range(c.url, c.offset, c.length)
    else:
        for line in chunks_spanned(args.dmg, c.offset, c.length):
            print(f"  {line}")
        with args.dmg.open("rb") as f:
            f.seek(c.offset)
            payload = f.read(c.length)

    payload_hash = hashlib.sha256(payload).hexdigest()
    print(f"payload sha256 {payload_hash}")
    if payload_hash != c.payload_sha256:
        raise SystemExit("payload hash does not match PAYLOAD_SHA256")

    library = x86_64_slice(file_from_cpio(gzip.decompress(payload), FRAMEWORK_PATH))
    library_hash = hashlib.sha256(library).hexdigest()
    print(f"library sha256 {library_hash} ({len(library)} bytes)")
    if library_hash != c.build_sha256:
        raise SystemExit("library hash does not match BUILD_SHA256")

    if args.out:
        args.out.write_bytes(library)
        print(f"wrote {args.out}")
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
