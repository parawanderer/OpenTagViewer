"""
The machine this exporter signs in as: an emulated Mac, running Apple's own macOS CoreADI.

**Why not the Android ADI that `anisette` runs.** Since 2026-10-12 Apple has treated a session
provisioned by its Android ADI libraries differently from a Mac's: the sign-in is asked for a
second factor in a new way (#248, handled in FindMy.py since parawanderer/FindMy.py#9) and the
tokens that follow it are not the ones a Mac gets (malmeloo/FindMy.py#278). A Mac-lineage machine
is what Apple still serves. The Android app reaches the same conclusion in #274.

**What runs it.** `machoadi`, from sacca97/os-findmy-e by way of parawanderer/os-findmy-e: Apple's
x86_64 `CoreADI.framework` mapped and executed under Unicorn, with a synthetic macOS userland. It
is pure Python on the `unicorn` wheel, which the exporter already ships through `anisette`, so it
runs on Windows and Linux - where nearly everybody runs this - as well as on a Mac.

**It runs in its own process, always.** `machoadi` is GPL-2.0 and this exporter is MIT, so it is
invoked as a program rather than imported - the arrangement `docs/emulated-mac-emulator-choice.md`
describes for the app. That also suits it technically: one Unicorn process faults after about
eight OTP requests, so `machoadi`'s own provider already asks a fresh process once per 30-second
OTP window and reuses the answer within it.

**Apple's library is fetched from Apple, never shipped.** It sits inside the iTunes 12.8.3 for Mac
installer Apple still serves, at a fixed position; one range request of 3.5 MB gets it, and two
pinned hashes check it. This is `MacCoreAdiFetcher` in the app, ported.

**The identity is the app's emulated Mac's**, `MacIdentity` in #274: a MacBookPro13,2 on macOS 13.1,
which is what os-findmy-e was observed to be issued tokens as. The OS is spelt `Mac OS X`, because
Apple's edge refuses `macOS` at sign-in since 2026-11-20 (#289). The serial is this install's own
`0PENTAGX` one (rule 11), handed to the engine when the machine is first provisioned.
"""

from __future__ import annotations

import gzip
import hashlib
import importlib
import json
import logging
import os
import struct
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.request
import uuid
from pathlib import Path
from typing import Any

from findmy.reports.anisette import BaseAnisetteProvider, DeviceIdentity
from findmy.util.tls import apple_trust_context
from typing_extensions import override

logger = logging.getLogger(__name__)

DIRNAME = "emulated-mac"
"""Beside `device-identity.json`: the CoreADI library, and the machine's state directory."""

IDENTITY = DeviceIdentity(
    model="MacBookPro13,2",
    os_name="Mac OS X",
    os_version="13.1",
    os_build="22C65",
    cfnetwork="1404.0.5",
    darwin="22.2.0",
)
"""
What this machine claims to be, field for field the app's `MacIdentity`.

**The provisioning half is not composed here.** `machoadi` provisions under its own
`DEFAULT_CLIENT_INFO`, which the parawanderer fork spells identically; `test_macadi` pins that the
two agree, because a login describing a different platform than provisioning did is a second
machine (rule 11).
"""

WINDOW_SECONDS = 30
"""The OTP changes only at multiples of 30 seconds of Unix time, so one engine run serves a window."""

ENGINE_TIMEOUT_SECONDS = 180
"""Provisioning is two round trips to Apple plus emulation; headers alone take a few seconds."""

DMG_URL = (
    "https://secure-appldnld.apple.com/itunes12/"
    "041-56365-20210413-79A5AC6D-6B0A-46EC-B399-5A381183E3BE/iTunes12.8.3.dmg"
)
PAYLOAD_OFFSET = 184_678_769
PAYLOAD_LENGTH = 3_471_595
PAYLOAD_SHA256 = "8a6c068c95036326eb2d2885fa60912ed0f7229d0939ae92934a70a3bb6cf025"
LIBRARY_SHA256 = "2eafc3c79c7f41a8afbfbeaaf23ee5dab5ad5bda55ec8e54dbc0ddf1d20ce630"
"""The x86_64 slice of CoreADI 4.19 - the build `machoadi` knows the dispatcher offset of."""

FRAMEWORK_PATH = "System/Library/PrivateFrameworks/CoreADI.framework/Versions/A/CoreADI"
_CPU_TYPE_X86_64 = 0x01000007


class EmulatedMacError(RuntimeError):
    """The emulated Mac could not be fetched, provisioned or asked for headers."""


# -- Apple's library ---------------------------------------------------------------------------


def ensure_library(target: Path, url: str = DMG_URL) -> Path:
    """Make sure `target` holds Apple's x86_64 CoreADI, fetching it if not."""
    if target.is_file() and _sha256(target.read_bytes()) == LIBRARY_SHA256:
        return target

    logger.info("Fetching Apple's macOS CoreADI from Apple (3.5 MB of the iTunes 12.8.3 installer)")
    library = extract(_fetch_payload(url))

    target.parent.mkdir(parents=True, exist_ok=True)
    # Written beside and renamed over, so an interrupted write never leaves a truncated library.
    partial = target.with_name(target.name + ".partial")
    partial.write_bytes(library)
    partial.replace(target)
    return target


def _fetch_payload(url: str) -> bytes:
    request = urllib.request.Request(
        url, headers={"Range": f"bytes={PAYLOAD_OFFSET}-{PAYLOAD_OFFSET + PAYLOAD_LENGTH - 1}"})
    try:
        with urllib.request.urlopen(request, timeout=60, context=apple_trust_context()) as response:
            # Anything but a partial answer means the range was ignored, and reading on would
            # download the whole 291 MB image to throw nearly all of it away.
            if response.status != 206:
                msg = f"Apple answered HTTP {response.status} to the range request; expected 206"
                raise EmulatedMacError(msg)
            payload = response.read(PAYLOAD_LENGTH + 1)
    except OSError as e:
        msg = f"Could not download Apple's CoreADI: {e}"
        raise EmulatedMacError(msg) from e

    if len(payload) != PAYLOAD_LENGTH:
        msg = f"Expected {PAYLOAD_LENGTH} bytes of the installer, got {len(payload)}"
        raise EmulatedMacError(msg)
    return payload


def extract(payload: bytes) -> bytes:
    """The x86_64 library out of the installer's payload. Bytes in, bytes out, no network."""
    if _sha256(payload) != PAYLOAD_SHA256:
        msg = f"The installer bytes are not the expected ones (sha256 {_sha256(payload)})"
        raise EmulatedMacError(msg)

    library = _slice(_file_from_cpio(gzip.decompress(payload), FRAMEWORK_PATH), _CPU_TYPE_X86_64)
    if _sha256(library) != LIBRARY_SHA256:
        msg = f"The library is not the expected build (sha256 {_sha256(library)})"
        raise EmulatedMacError(msg)
    return library


def _file_from_cpio(cpio: bytes, wanted: str) -> bytes:
    """
    One file from an "odc" cpio archive, which Apple's package payloads are.

    Each entry is the magic `070707`, six-digit octal fields up to the name size at offset 59 and
    an eleven-digit file size at 65 - 76 bytes - then the name with its NUL, then the data.
    """
    pos = 0
    while pos + 76 <= len(cpio):
        if cpio[pos:pos + 6] != b"070707":
            msg = f"Not an odc cpio archive at offset {pos}"
            raise EmulatedMacError(msg)
        name_size = int(cpio[pos + 59:pos + 65], 8)
        file_size = int(cpio[pos + 65:pos + 76], 8)
        data = pos + 76 + name_size
        name = cpio[pos + 76:data - 1].decode("utf-8").removeprefix("./")
        if name == "TRAILER!!!":
            break
        # A hard link's twins carry no data; the entry that matters is the one that does.
        if name == wanted and file_size:
            return cpio[data:data + file_size]
        pos = data + file_size
    msg = f"{wanted} is not in the installer payload"
    raise EmulatedMacError(msg)


def _slice(fat: bytes, cpu_type: int) -> bytes:
    """The slice for `cpu_type` out of a Mach-O universal binary."""
    if fat[:4] != b"\xca\xfe\xba\xbe":
        msg = "The library is not a universal binary"
        raise EmulatedMacError(msg)
    (count,) = struct.unpack(">I", fat[4:8])
    for i in range(count):
        cpu, _, offset, size, _ = struct.unpack(">5I", fat[8 + 20 * i:28 + 20 * i])
        if cpu == cpu_type:
            return fat[offset:offset + size]
    msg = "The library has no x86_64 slice"
    raise EmulatedMacError(msg)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# -- the engine, in its own process ------------------------------------------------------------


ENGINE_FLAG = "--run-emulated-mac"
"""
The argument that makes a *frozen* exporter run the engine instead of the window.

A PyInstaller build has no interpreter to hand `-m` to, so it runs itself: `exporter.wizard` checks
for this before it does anything else and calls :func:`run_engine_here`. It is still a separate
process - which the engine needs, and which keeps the GPL code at arm's length - and on Windows it
is the same executable, so it inherits the Control Flow Guard setting Unicorn needs.
"""

_ENGINE_MODULES = ("macho", "harness", "stubs", "adi_run", "gsa", "mac_anisette")
"""`machoadi`'s modules, leaves first: each imports only names earlier in this list."""


def load_engine() -> Any:
    """
    Import the engine so that its modules find each other, and return `mac_anisette`.

    **They import one another by bare name** - `from harness import ...` - after putting their own
    directory on `sys.path`. A frozen build keeps them in an archive rather than a directory, so
    that resolves to nothing. Importing each as `machoadi.<name>`, leaves first, and registering it
    under its bare name as well makes every one of those imports find the module already loaded.
    """
    for name in _ENGINE_MODULES:
        if name not in sys.modules:
            sys.modules[name] = importlib.import_module(f"machoadi.{name}")
    return sys.modules["mac_anisette"]


def run_engine_here(argv: list[str]) -> int:
    """
    Run one engine command in this process: `out err command [args...]`.

    **Its output goes to files the parent names, not to stdout.** A windowed Windows build may have
    no standard streams at all, and the answer is JSON the parent has to parse. Both are opened
    before the engine is imported, so even an import failure reaches the parent.
    """
    out_path, err_path, *command = argv
    with open(out_path, "w", encoding="utf-8") as out, open(err_path, "w", encoding="utf-8") as err:
        sys.stdout, sys.stderr = out, err
        try:
            mac_anisette = load_engine()
            sys.argv = ["mac_anisette", *command]
            return int(mac_anisette.main() or 0)
        except SystemExit as e:
            # The engine reports failures as `raise SystemExit("message")`.
            if isinstance(e.code, str):
                print(e.code, file=err)
                return 1
            return int(e.code or 0)
        except Exception:  # noqa: BLE001 - reported to the parent, which raises EmulatedMacError
            traceback.print_exc(file=err)
            return 1


def _engine(command: str, state: Path, library: Path, *extra: str) -> str:
    """Run one `machoadi` command in its own process and return what it printed."""
    args = [command, "--state", str(state), *extra]
    env = {**os.environ, "COREADI_PATH": str(library)}
    try:
        if getattr(sys, "frozen", False):
            code, stdout, stderr = _run_frozen(args, env)
        else:
            done = subprocess.run(
                [sys.executable, "-m", "machoadi.mac_anisette", *args],
                capture_output=True, text=True, timeout=ENGINE_TIMEOUT_SECONDS, env=env,
                check=False)
            code, stdout, stderr = done.returncode, done.stdout, done.stderr
    except subprocess.TimeoutExpired as e:
        msg = f"The emulated Mac did not answer `{command}` within {ENGINE_TIMEOUT_SECONDS}s"
        raise EmulatedMacError(msg) from e

    if code != 0:
        detail = (stderr or stdout).strip().splitlines()
        msg = f"The emulated Mac failed `{command}`: {detail[-1] if detail else code}"
        raise EmulatedMacError(msg)
    return stdout


def _run_frozen(args: list[str], env: dict[str, str]) -> tuple[int, str, str]:
    """This executable again, with :data:`ENGINE_FLAG`. See :func:`run_engine_here`."""
    with tempfile.TemporaryDirectory(prefix="otv-engine-") as scratch:
        out, err = Path(scratch) / "out", Path(scratch) / "err"
        done = subprocess.run(
            [sys.executable, ENGINE_FLAG, str(out), str(err), *args],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=ENGINE_TIMEOUT_SECONDS, env=env, check=False,
            # A windowed parent starting a child must not flash a console window on Windows.
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        read = lambda path: path.read_text(encoding="utf-8") if path.is_file() else ""  # noqa: E731
        return done.returncode, read(out), read(err)


class EmulatedMac:
    """One provisioned machine: its state directory, and Apple's library to run it with."""

    def __init__(self, root: Path) -> None:
        self.state = root / "machine"
        self.library = root / "CoreADI.x86_64"

    @classmethod
    def open(cls, root: Path, serial: str) -> EmulatedMac:
        """
        The machine kept under `root`, provisioning one first if there is none.

        :param serial: What a machine provisioned now presents. A stored machine keeps its own:
            changing it would be a second entry in the account's device list.
        """
        mac = cls(root)
        ensure_library(mac.library)

        if not mac.info()["provisioned"]:
            logger.info("Provisioning the emulated Mac with Apple (no account is involved)")
            _engine("provision", mac.state, mac.library, "--serial", serial)
        return mac

    def info(self) -> dict[str, Any]:
        return json.loads(_engine("info", self.state, self.library))

    @property
    def identity(self) -> dict[str, Any]:
        """`machoadi`'s `identity.json`: device id, local user id, client info, host UUID, serial."""
        return json.loads((self.state / "identity.json").read_text(encoding="utf-8"))

    def headers(self) -> dict[str, str]:
        return json.loads(_engine("headers", self.state, self.library))


class EmulatedMacAnisetteProvider(BaseAnisetteProvider):
    """Anisette from the emulated Mac, asked once per OTP window."""

    def __init__(self, mac: EmulatedMac) -> None:
        self._mac = mac
        self._machine = mac.identity
        super().__init__(serial=self._machine["serial"], identity=IDENTITY)

        self._window: int | None = None
        self._headers: dict[str, str] | None = None

    @property
    def uid(self) -> str:
        """The local user id the machine was provisioned with. FindMy.py sends `base64(uid)`."""
        return self._machine["local_user_uuid"]

    @property
    def devid(self) -> str:
        """The machine's provisioning UDID, `00006000-` and sixteen hex. Not a UUID."""
        return self._machine["device_id"]

    @property
    def cloudkit_device_uuid(self) -> str:
        """
        A UUID for CloudKit, which parses the device id as one and cannot parse a Mac's.

        The host UUID, as os-findmy-e's `tag_keys.CloudKitIdentity` used: stable for exactly as
        long as the machine is. CloudKit's identifier is a different field from `X-Mme-Device-Id`.
        """
        return str(uuid.UUID(self._machine["host_uuid"])).upper()

    def _fetch(self) -> dict[str, str]:
        window = int(time.time()) // WINDOW_SECONDS
        if self._headers is None or window != self._window:
            self._headers = self._mac.headers()
            self._window = window
        return self._headers

    @property
    @override
    def otp(self) -> str:
        return self._fetch()["X-Apple-I-MD"]

    @property
    @override
    def machine(self) -> str:
        return self._fetch()["X-Apple-I-MD-M"]

    @property
    @override
    def router(self) -> str:
        # The routing Apple handed back at provisioning, which is what the machine is registered
        # under - not one of the library's constants.
        return str(self._fetch()["X-Apple-I-MD-RINFO"])

    @property
    @override
    def timestamp(self) -> str:
        # From the same run as the OTP, so it cannot describe a different window.
        return self._fetch()["X-Apple-I-Client-Time"]

    @override
    def to_json(self, dst: str | Path | None = None, /) -> Any:
        # The state directory is the machine; a second copy of it here would drift. This only says
        # which kind of machine the stored ids belong to.
        return {"type": "aniEmulatedMac"}

    @classmethod
    @override
    def from_json(cls, val: Any) -> EmulatedMacAnisetteProvider:
        msg = "An emulated Mac is restored from its state directory, not from JSON"
        raise NotImplementedError(msg)

    @override
    async def close(self) -> None:
        self._headers = None
        self._window = None
