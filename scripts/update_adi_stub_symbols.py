"""Regenerate the stub symbol lists that let the app run Apple's ADI libraries cheaply.

Background. Running Anisette in-app means loading Apple's libstoreservicescore.so, which
declares DT_NEEDED on libCoreFoundation.so and libmediaplatform.so. Those two drag in ICU,
curl, libxml2, libdispatch and BlocksRuntime - about 20 MB that nothing ever calls, because
libstoreservicescore imports no symbol from any of them.

bionic satisfies DT_NEEDED by SONAME against libraries that are already loaded, and does not
check who built them. So the app ships a few kB of generated stubs carrying those two SONAMEs,
and the 20 MB never has to be downloaded. This script produces the symbol lists those stubs are
generated from, in app/src/main/cpp/stubs/.

The lists are checked in as text, and the C is generated at build time by
app/src/main/cpp/stubs/generate_stub.cmake, so no binary of Apple's enters this repository.

Run this when Apple ships a new Apple Music APK and the app starts failing to load ADI with an
unresolved symbol - the failure message names the missing symbol and points here.

    python scripts/update_adi_stub_symbols.py

Symbols are unioned across ABIs. The mangled names should be identical everywhere, but "should
be" is not a good reason for a stub to be missing a symbol on one architecture only.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import re
import struct
import sys
import urllib.request
import zlib

APK_URL = "https://apps.mzstatic.com/content/android-apple-music-apk/applemusic.apk"

ABIS = ["arm64-v8a", "x86_64"]

#: The library whose imports decide everything. ADI's entry points live here.
ROOT = "libstoreservicescore.so"

#: The libraries we stand in for. Everything behind them becomes unreachable.
STUBBED = ["libCoreFoundation.so", "libmediaplatform.so"]

#: Libraries we take from Apple whole rather than standing in for. libc++_shared is here
#: because its mangled names are __ndk1-namespaced and the NDK's own libc++ should not be
#: assumed ABI-identical without checking.
#:
#: Anything libstoreservicescore imports from that is neither here nor in STUBBED is a
#: dependency Apple has added, and needs a decision rather than a regenerated list.
DOWNLOADED_WHOLE = ["libc++_shared.so"]

#: What the app actually pulls from Apple at runtime. libCoreADI.so is not a DT_NEEDED of
#: anything - libstoreservicescore opens it by path once ADILoadLibraryWithPath is called -
#: so it has to be listed explicitly rather than discovered through the dependency graph.
FETCHED_AT_RUNTIME = DOWNLOADED_WHOLE + ["libstoreservicescore.so", "libCoreADI.so"]

#: Read by the app at runtime to verify what it downloaded before calling into any of it.
MANIFEST = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "adi-libraries.json")

#: Provided by Android itself, present in every app's linker namespace, never bundled.
PLATFORM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
    "libstdc++.so", "libjnigraphics.so", "libGLESv2.so", "libEGL.so", "libOpenSLES.so",
    "libmediandk.so", "libnativewindow.so", "libvulkan.so", "libaaudio.so", "libcamera2ndk.so",
}

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "cpp", "stubs")

STT_FUNC = 2
SHN_UNDEF = 0
PT_DYNAMIC = 2
DT_NEEDED = 1

HEADER = """\
# Symbols that {root} imports from {library}.
#
# Regenerate with: python scripts/update_adi_stub_symbols.py
#
# Checked in as text on purpose - app/src/main/cpp/stubs/generate_stub.cmake turns this into a
# .c file at build time, so nothing of Apple's is stored in this repository.
#
# F = function, D = data object. Sorted, so that a diff is readable when Apple changes their
# libraries.
"""


# ------------------------------------------------------------------------------------------
# Reading the APK without downloading it
#
# A zip's central directory is at the end of the file, so range requests can read the
# directory, find the members we want, and fetch only those.
# ------------------------------------------------------------------------------------------

def fetch_range(start: int, end: int) -> bytes:
    """Fetch [start, end] inclusive."""
    request = urllib.request.Request(APK_URL, headers={"Range": f"bytes={start}-{end}"})
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status != 206:
            raise SystemExit(f"the CDN ignored the range request (HTTP {response.status})")
        return response.read()


def apk_size() -> int:
    request = urllib.request.Request(APK_URL, method="HEAD")
    with urllib.request.urlopen(request, timeout=60) as response:
        length = int(response.headers["Content-Length"])
    return length


def central_directory(size: int) -> bytes:
    tail = fetch_range(size - min(65536 + 22, size), size - 1)
    index = tail.rfind(b"PK\x05\x06")
    if index < 0:
        raise SystemExit("no end-of-central-directory record found - zip64?")
    cd_size, cd_offset = struct.unpack_from("<II", tail, index + 12)
    return fetch_range(cd_offset, cd_offset + cd_size - 1)


def entries(cd: bytes):
    """Yield (name, method, compressed_size, local_header_offset) for each member."""
    pos = 0
    while pos + 46 <= len(cd) and cd[pos:pos + 4] == b"PK\x01\x02":
        method, = struct.unpack_from("<H", cd, pos + 10)
        comp_size, = struct.unpack_from("<I", cd, pos + 20)
        name_len, extra_len, comment_len = struct.unpack_from("<HHH", cd, pos + 28)
        local_offset, = struct.unpack_from("<I", cd, pos + 42)
        name = cd[pos + 46:pos + 46 + name_len].decode("utf-8", "replace")

        yield name, method, comp_size, local_offset
        pos += 46 + name_len + extra_len + comment_len


def extract(method: int, comp_size: int, local_offset: int) -> bytes:
    """Fetch one member. Its local header repeats the name at a different length, so it has
    to be read to find where the data starts."""
    header = fetch_range(local_offset, local_offset + 29)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    start = local_offset + 30 + name_len + extra_len

    data = fetch_range(start, start + comp_size - 1)
    return zlib.decompress(data, -15) if method == 8 else data


# ------------------------------------------------------------------------------------------
# Just enough ELF
# ------------------------------------------------------------------------------------------

def sections(data: bytes) -> list[dict]:
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)

    out = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name_off, _type, _flags, _addr, sh_off, sh_size, link, _info, _align, entsize = \
            struct.unpack_from("<IIQQQQIIQQ", data, off)
        out.append({"name_off": name_off, "offset": sh_off, "size": sh_size,
                    "link": link, "entsize": entsize})

    strtab = out[e_shstrndx]
    names = data[strtab["offset"]:strtab["offset"] + strtab["size"]]
    for section in out:
        end = names.index(b"\0", section["name_off"])
        section["name"] = names[section["name_off"]:end].decode()
    return out


def dynamic_symbols(data: bytes):
    """Yield (name, defined, is_function) for every .dynsym entry."""
    found = sections(data)
    dynsym = next((s for s in found if s["name"] == ".dynsym"), None)
    if dynsym is None:
        return

    dynstr = found[dynsym["link"]]
    strings = data[dynstr["offset"]:dynstr["offset"] + dynstr["size"]]

    for i in range(dynsym["size"] // dynsym["entsize"]):
        off = dynsym["offset"] + i * dynsym["entsize"]
        st_name, st_info, _other, st_shndx, _value, _size = \
            struct.unpack_from("<IBBHQQ", data, off)
        if st_name == 0:
            continue
        end = strings.index(b"\0", st_name)
        yield (strings[st_name:end].decode(),
               st_shndx != SHN_UNDEF,
               (st_info & 0xF) == STT_FUNC)


def needed(data: bytes) -> list[str]:
    """The DT_NEEDED entries - what dlopen will insist on resolving."""
    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)

    dyn_off = dyn_size = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from("<I", data, off)
        if p_type == PT_DYNAMIC:
            dyn_off, = struct.unpack_from("<Q", data, off + 8)
            dyn_size, = struct.unpack_from("<Q", data, off + 32)
    if dyn_off is None:
        return []

    dynstr = next(s for s in sections(data) if s["name"] == ".dynstr")
    strings = data[dynstr["offset"]:dynstr["offset"] + dynstr["size"]]

    out, pos = [], dyn_off
    while pos < dyn_off + dyn_size:
        tag, value = struct.unpack_from("<qQ", data, pos)
        pos += 16
        if tag == 0:
            break
        if tag == DT_NEEDED:
            out.append(strings[value:strings.index(b"\0", value)].decode())
    return out


# ------------------------------------------------------------------------------------------
# Working out who imports what
# ------------------------------------------------------------------------------------------

def download_closure(abi: str, cache: str) -> dict[str, bytes]:
    """Everything reachable from ROOT through DT_NEEDED, kept on disk between runs."""
    os.makedirs(cache, exist_ok=True)

    index = {name: (method, comp, off)
             for name, method, comp, off in entries(central_directory(apk_size()))
             if name.startswith(f"lib/{abi}/")}

    libraries: dict[str, bytes] = {}
    queue, seen = [ROOT], set()
    while queue:
        library = queue.pop(0)
        if library in seen or library in PLATFORM:
            continue
        seen.add(library)

        path = os.path.join(cache, library)
        if os.path.isfile(path):
            blob = open(path, "rb").read()
        elif f"lib/{abi}/{library}" in index:
            blob = extract(*index[f"lib/{abi}/{library}"])
            open(path, "wb").write(blob)
            print(f"    downloaded {library} ({len(blob) / 1e6:.2f} MB)")
        else:
            print(f"    not in the APK: {library}", file=sys.stderr)
            continue

        libraries[library] = blob
        queue.extend(needed(blob))
    return libraries


def imports_by_library(libraries: dict[str, bytes]) -> dict[str, dict[str, bool]]:
    """-> {providing library: {symbol: is_function}} for ROOT's undefined symbols."""
    provider: dict[str, str] = {}
    for library, blob in libraries.items():
        if library == ROOT:
            continue
        for name, defined, _is_function in dynamic_symbols(blob):
            if defined:
                provider.setdefault(name, library)

    out: dict[str, dict[str, bool]] = collections.defaultdict(dict)
    for name, defined, is_function in dynamic_symbols(libraries[ROOT]):
        if not defined and name in provider:
            out[provider[name]][name] = is_function
    return out


def write_list(library: str, symbols: dict[str, bool], out_dir: str) -> str:
    path = os.path.join(out_dir, library.replace(".so", "") + ".symbols")
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(HEADER.format(root=ROOT, library=library))
        for name in sorted(symbols):
            handle.write(f"{'F' if symbols[name] else 'D'} {name}\n")
    return path


def apk_identity() -> dict:
    """What Apple's CDN says it is serving.

    There is one "latest" URL and no versioned variant, so the build cannot be pinned in the
    sense of asking for an old one. It can be pinned in the sense that matters: record exactly
    what was verified, and refuse to trust anything else. As of writing, Last-Modified has not
    moved since April 2025.
    """
    request = urllib.request.Request(APK_URL, method="HEAD")
    with urllib.request.urlopen(request, timeout=60) as response:
        headers = response.headers

    return {
        "version": headers.get("X-Apple-Version-Number"),
        "etag": headers.get("Etag"),
        "lastModified": headers.get("Last-Modified"),
        "contentLength": int(headers["Content-Length"]),
    }


def library_digests(abi: str, cache: str) -> dict[str, dict]:
    """Size and SHA-256 of each library the app downloads at runtime.

    This is what lets the app verify, before calling into any of it, that it received the
    same bytes these stub lists were generated from - and fall back to the remote Anisette
    server rather than guessing if it did not.
    """
    index = {name: (method, comp, off)
             for name, method, comp, off in entries(central_directory(apk_size()))
             if name.startswith(f"lib/{abi}/")}

    out = {}
    for library in FETCHED_AT_RUNTIME:
        path = os.path.join(cache, abi, library)
        if os.path.isfile(path):
            blob = open(path, "rb").read()
        else:
            os.makedirs(os.path.join(cache, abi), exist_ok=True)
            blob = extract(*index[f"lib/{abi}/{library}"])
            open(path, "wb").write(blob)

        out[library] = {"size": len(blob), "sha256": hashlib.sha256(blob).hexdigest()}
    return out


def write_manifest(cache: str, path: str) -> None:
    manifest = {
        "_comment": [
            "What the app downloads at runtime to run Apple's ADI (Anisette) libraries, and "
            "the exact bytes it expects. Apple's libraries are never redistributed by this "
            "project - they are fetched from Apple's own CDN.",
            "Regenerate with: python scripts/update_adi_stub_symbols.py",
            "A mismatch at runtime means Apple shipped a new build. The symbol names may have "
            "moved with it, so the app must fall back to a remote Anisette server rather than "
            "call into libraries it cannot vouch for.",
        ],
        "url": APK_URL,
        "apk": apk_identity(),
        "libraries": {abi: library_digests(abi, cache) for abi in ABIS},
    }

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")
    print(f"wrote {path}: Apple Music {manifest['apk']['version']}, "
          f"last modified {manifest['apk']['lastModified']}")


def collect(cache: str) -> tuple[dict[str, dict[str, bool]], dict[str, bytes]]:
    """Union the stubbed libraries' import lists across every ABI."""
    merged: dict[str, dict[str, bool]] = {library: {} for library in STUBBED}
    last: dict[str, bytes] = {}

    for abi in ABIS:
        print(f"{abi}:")
        last = download_closure(abi, os.path.join(cache, abi))
        found = imports_by_library(last)

        for library in STUBBED:
            symbols = found.get(library, {})
            if not symbols:
                raise SystemExit(
                    f"{ROOT} imports nothing from {library} on {abi}. Either Apple has "
                    f"restructured their libraries, or the closure is wrong - do not ship "
                    f"stubs generated from this.")
            new = set(symbols) - set(merged[library])
            merged[library].update(symbols)
            print(f"  {library}: {len(symbols)} symbols, {len(new)} new")

        unexpected = {library: symbols for library, symbols in found.items()
                      if library not in STUBBED and library not in DOWNLOADED_WHOLE}
        if unexpected:
            raise SystemExit(
                f"{ROOT} now imports from libraries this app neither stubs nor downloads: "
                + ", ".join(f"{name} ({len(syms)} symbols)"
                            for name, syms in sorted(unexpected.items()))
                + f".\nOn {abi}. Apple has added a dependency, and regenerating the symbol "
                  "lists alone will not be enough - dlopen will fail with 'library not found' "
                  "until it is either stubbed or downloaded. Decide which, then update "
                  "STUBBED or DOWNLOADED_WHOLE here and the matching lists in "
                  "AdiLibrary/LocalAnisette.")

        for library, symbols in sorted(found.items()):
            if library in DOWNLOADED_WHOLE:
                print(f"  ({library}: {len(symbols)} symbols, downloaded from Apple)")

    return merged, last


def missing_adi_functions(store_services_core: bytes) -> list[str]:
    """Which of AdiFunction's obfuscated symbols are no longer exported.

    Read out of the enum rather than duplicated here, so there is exactly one copy of the
    mapping in the project and no way for the two to disagree.
    """
    source = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "java", "dev", "wander", "android", "opentagviewer",
        "anisette", "AdiFunction.java")

    with open(source, encoding="utf-8") as handle:
        declared = re.findall(r'\(\s*"(ADI\w+)"\s*,\s*"(\w+)"\s*\)', handle.read())

    if not declared:
        raise SystemExit(f"no entries found in {source} - has the enum been restructured?")

    exported = {name for name, defined, _is_function in dynamic_symbols(store_services_core)
                if defined}
    return [f"{apple_name} ({symbol})" for apple_name, symbol in declared
            if symbol not in exported]


def manifest_drift(manifest_path: str) -> list[str]:
    """Has Apple shipped a different APK than the one we verified against?"""
    if not os.path.isfile(manifest_path):
        return [f"{os.path.basename(manifest_path)} is missing. Generate it with:"
                "\n    python scripts/update_adi_stub_symbols.py"]

    with open(manifest_path, encoding="utf-8") as handle:
        recorded = json.load(handle)

    live = apk_identity()
    if recorded.get("apk", {}).get("etag") == live["etag"]:
        return []

    return [
        "Apple shipped a new Apple Music APK.\n"
        f"  Recorded: version {recorded.get('apk', {}).get('version')}, "
        f"modified {recorded.get('apk', {}).get('lastModified')}\n"
        f"  Live:     version {live['version']}, modified {live['lastModified']}\n"
        "  The ADI symbol names are obfuscated per build, so they may have moved with it. "
        "Until the manifest and symbol lists are regenerated and the app is tested against "
        "the new build, runtime hash verification will reject it and Anisette will fall back "
        "to a remote server. Fix with:\n"
        "    python scripts/update_adi_stub_symbols.py\n"
        "  then re-run the instrumented proof of concept before committing."]


def report_drift(merged: dict[str, dict[str, bool]], out_dir: str,
                 store_services_core: bytes, manifest_path: str) -> int:
    """Compare against what is checked in, and explain how to fix any difference."""
    problems = manifest_drift(manifest_path)

    gone = missing_adi_functions(store_services_core)
    if gone:
        problems.append(
            "Apple re-obfuscated the ADI entry points. These are no longer exported by "
            + ROOT + ":\n    " + "\n    ".join(gone)
            + "\n  Anisette will fail at runtime for everyone until AdiFunction.java is "
              "updated. The new names have to be recovered by hand - see docs/ADI.md in "
              "Dadoum/Provision for how the exports are arranged.")

    for library, symbols in merged.items():
        path = os.path.join(out_dir, library.replace(".so", "") + ".symbols")
        expected = HEADER.format(root=ROOT, library=library) + "".join(
            f"{'F' if symbols[name] else 'D'} {name}\n" for name in sorted(symbols))

        actual = open(path, encoding="utf-8").read() if os.path.isfile(path) else ""
        if actual == expected:
            continue

        current = {line.split(" ", 1)[1] for line in actual.splitlines()
                   if line and not line.startswith("#")}
        added = sorted(set(symbols) - current)
        removed = sorted(current - set(symbols))
        problems.append(
            f"{os.path.basename(path)} is out of date: "
            f"{len(added)} symbol(s) added, {len(removed)} removed."
            + (f"\n  Added: {', '.join(added[:8])}" if added else "")
            + (f"\n  Removed: {', '.join(removed[:8])}" if removed else "")
            + "\n  A missing symbol makes dlopen of " + ROOT + " fail outright. Fix with:"
              "\n    python scripts/update_adi_stub_symbols.py"
              "\n  then commit the regenerated .symbols files.")

    if not problems:
        print("\nUp to date: the ADI entry points and both stub symbol lists still match "
              "Apple's current APK.")
        return 0

    print("\n" + "\n\n".join(f"FAIL: {problem}" for problem in problems), file=sys.stderr)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", default=".adi-libs",
                        help="where to keep downloaded libraries between runs")
    parser.add_argument("--out", default=OUT_DIR, help="where to write the .symbols files")
    parser.add_argument("--manifest", default=MANIFEST,
                        help="where to write the runtime verification manifest")
    parser.add_argument("--check", action="store_true",
                        help="do not write anything; exit non-zero if Apple's libraries have "
                             "drifted from what is checked in. For CI.")
    args = parser.parse_args()

    merged, libraries = collect(args.cache)

    if args.check:
        return report_drift(merged, args.out, libraries[ROOT], args.manifest)

    os.makedirs(args.out, exist_ok=True)
    for library, symbols in merged.items():
        functions = sum(1 for is_function in symbols.values() if is_function)
        path = write_list(library, symbols, args.out)
        print(f"wrote {path}: {len(symbols)} symbols "
              f"({functions} functions, {len(symbols) - functions} data)")

    write_manifest(args.cache, args.manifest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
