"""
The exporter signing in as an emulated Mac (exporter/macadi.py).

Offline throughout by default. The engine itself runs only with `OTV_COREADI` set to a fetched
library - `python -c "from exporter import macadi; macadi.ensure_library(Path('CoreADI.x86_64'))"` -
and even then sends Apple nothing: `info` is `Initialize` and `IsMachineProvisioned`.
"""

from __future__ import annotations

import gzip
import hashlib
import importlib.util
import json
import os
import re
import struct
import subprocess
import sys
import uuid
from pathlib import Path

import pytest
from findmy.reports.anisette import _AKD_BUNDLE  # noqa: PLC2701 - the bundle sign-in names

from exporter import device, icloud, macadi

MAC_DEVICE_ID = "00006000-0123456789ABCDEF"
HOST_UUID = "786d2185e483fde6eb73112524314456"


class FakeMac:
    """An `EmulatedMac` that counts how often the engine would have been run."""

    def __init__(self, serial: str = "0PENTAGXTEST") -> None:
        self.identity = {
            "device_id": MAC_DEVICE_ID,
            "local_user_uuid": "12E2E61F124C792F2361EB2082DD4CB5",
            "client_info": macadi.IDENTITY.client_info(_AKD_BUNDLE),
            "host_uuid": HOST_UUID,
            "routing": 17106176,
            "serial": serial,
        }
        self.asked = 0

    def headers(self) -> dict[str, str]:
        self.asked += 1
        return {
            "X-Apple-I-MD": f"otp-{self.asked}",
            "X-Apple-I-MD-M": "mid",
            "X-Apple-I-MD-RINFO": "17106176",
            "X-Apple-I-Client-Time": "2026-11-20T12:00:00Z",
        }


@pytest.fixture
def mac(monkeypatch: pytest.MonkeyPatch) -> FakeMac:
    fake = FakeMac()
    opened: list[tuple[Path, str]] = []

    def open_(root: Path, serial: str) -> FakeMac:
        opened.append((root, serial))
        return fake

    monkeypatch.setattr(macadi.EmulatedMac, "open", staticmethod(open_))
    fake.opened = opened  # type: ignore[attr-defined]
    return fake


class TestTheIdentityItPresents:
    def test_the_os_is_spelt_the_way_apple_still_lets_through(self):
        """#289: `<macOS;…>` is refused at sign-in since 2026-11-20, `<Mac OS X;…>` is not."""
        assert macadi.IDENTITY.os_name == "Mac OS X"
        assert "<macOS;" not in macadi.IDENTITY.client_info(_AKD_BUNDLE)

    def test_it_is_the_machine_the_engine_provisions_as(self):
        """
        Provisioning sends the engine's own client info, and the login sends this one. Two
        descriptions of the platform are two machines (rule 11), so they are pinned equal.
        """
        spec = importlib.util.find_spec("machoadi")
        assert spec is not None and spec.origin is not None
        source = (Path(spec.origin).parent / "mac_anisette.py").read_text(encoding="utf-8")
        engine = re.search(r'^DEFAULT_CLIENT_INFO = "([^"]+)"', source, re.MULTILINE)

        assert engine is not None
        assert engine.group(1) == macadi.IDENTITY.client_info(_AKD_BUNDLE)

    def test_it_is_the_apps_emulated_mac(self):
        """`MacIdentity` in #274: one project, one kind of Mac."""
        assert macadi.IDENTITY.platform == "<MacBookPro13,2> <Mac OS X;13.1;22C65>"


class TestTheProvider:
    def test_one_engine_run_serves_a_whole_otp_window(self, mac: FakeMac):
        provider = macadi.EmulatedMacAnisetteProvider(mac)  # type: ignore[arg-type]

        assert (provider.otp, provider.machine, provider.router, provider.timestamp) == (
            "otp-1", "mid", "17106176", "2026-11-20T12:00:00Z")
        assert mac.asked == 1

    def test_the_next_window_asks_again(self, mac: FakeMac, monkeypatch: pytest.MonkeyPatch):
        provider = macadi.EmulatedMacAnisetteProvider(mac)  # type: ignore[arg-type]
        now = [1_700_000_000.0]
        monkeypatch.setattr(macadi.time, "time", lambda: now[0])

        first = provider.otp
        now[0] += macadi.WINDOW_SECONDS

        assert provider.otp != first
        assert mac.asked == 2

    def test_it_presents_the_machines_serial_and_ids(self, mac: FakeMac):
        provider = macadi.EmulatedMacAnisetteProvider(mac)  # type: ignore[arg-type]

        assert provider.serial == "0PENTAGXTEST"
        assert provider.uid == mac.identity["local_user_uuid"]
        assert provider.devid == MAC_DEVICE_ID
        assert provider.identity == macadi.IDENTITY

    def test_what_it_stores_carries_nothing_device_json_would_refuse(self, mac: FakeMac):
        provider = macadi.EmulatedMacAnisetteProvider(mac)  # type: ignore[arg-type]

        device._only_identity({"uid": "u", "devid": "d", "anisette": provider.to_json()})


class TestTheAccount:
    def test_the_default_is_the_emulated_mac(self, mac: FakeMac, tmp_path: Path):
        account = icloud.make_account(identity_path=tmp_path / "device-identity.json")

        assert isinstance(account._anisette, macadi.EmulatedMacAnisetteProvider)
        assert mac.opened == [(tmp_path / macadi.DIRNAME, mac.opened[0][1])]  # type: ignore[attr-defined]

    def test_it_signs_in_with_the_ids_the_machine_was_provisioned_with(
            self, mac: FakeMac, tmp_path: Path):
        """`X-Apple-I-MD-M` is derived from that registration; other ids beside it are no Mac."""
        account = icloud.make_account(identity_path=tmp_path / "device-identity.json")

        assert account._uid == mac.identity["local_user_uuid"]
        assert account._devid == MAC_DEVICE_ID

    def test_a_machine_provisioned_now_presents_this_installs_serial(
            self, mac: FakeMac, tmp_path: Path):
        path = tmp_path / "device-identity.json"
        device.save("old-uid", "old-devid", {"type": "aniLocal"}, path, serial="0PENTAGXK7QX")

        icloud.make_account(identity_path=path)

        assert mac.opened[0][1] == "0PENTAGXK7QX"  # type: ignore[attr-defined]

    def test_the_second_run_is_the_same_device(self, mac: FakeMac, tmp_path: Path):
        path = tmp_path / "device-identity.json"
        first = icloud.make_account(identity_path=path)
        icloud.remember(first, path)

        second = icloud.make_account(identity_path=path)

        assert (second._uid, second._devid) == (first._uid, first._devid)
        assert device.load(path)["anisette"] == {"type": "aniEmulatedMac"}

    def test_an_install_that_was_the_android_machine_moves_and_says_so(
            self, mac: FakeMac, tmp_path: Path, caplog: pytest.LogCaptureFixture):
        path = tmp_path / "device-identity.json"
        device.save("old-uid", "old-devid", {"type": "aniLocal"}, path, serial="0PENTAGXK7QX")

        account = icloud.make_account(identity_path=path)

        assert account._devid == MAC_DEVICE_ID
        assert "adds one entry to the account's device list" in caplog.text

    def test_naming_an_anisette_server_still_uses_it(self, mac: FakeMac, tmp_path: Path):
        icloud.make_account("https://example.invalid", identity_path=tmp_path / "identity.json")

        assert mac.opened == []  # type: ignore[attr-defined]

    def test_naming_the_android_libraries_still_uses_them(self, mac: FakeMac, tmp_path: Path):
        account = icloud.make_account(
            libs_path=str(tmp_path / "libs.bin"), identity_path=tmp_path / "identity.json")

        assert not isinstance(account._anisette, macadi.EmulatedMacAnisetteProvider)
        assert mac.opened == []  # type: ignore[attr-defined]


class TestWhatCloudKitIsToldTheDeviceIs:
    def test_a_uuid_is_passed_through_untouched(self):
        class Account:
            device_uuid = "9F1C2D3E-4B5A-4C6D-8E7F-001122334455"

        account = Account()
        assert icloud._with_a_uuid_for_cloudkit(account) is account

    def test_a_mac_device_id_gets_the_machines_host_uuid(self, mac: FakeMac):
        class Account:
            device_uuid = MAC_DEVICE_ID
            _anisette = macadi.EmulatedMacAnisetteProvider(mac)  # type: ignore[arg-type]
            serial = "0PENTAGXTEST"

        view = icloud._with_a_uuid_for_cloudkit(Account())

        assert view.device_uuid == str(uuid.UUID(HOST_UUID)).upper()
        assert view.serial == "0PENTAGXTEST"

    def test_without_a_provider_that_can_say_it_is_derived_and_stable(self):
        class Account:
            device_uuid = MAC_DEVICE_ID

        first = icloud._with_a_uuid_for_cloudkit(Account()).device_uuid
        assert first == icloud._with_a_uuid_for_cloudkit(Account()).device_uuid
        uuid.UUID(first)


def _odc_entry(name: str, data: bytes) -> bytes:
    name_bytes = name.encode() + b"\0"
    return (b"070707" + b"0" * 53 + f"{len(name_bytes):06o}".encode()
            + f"{len(data):011o}".encode() + name_bytes + data)


class TestTakingTheLibraryOutOfTheInstaller:
    def test_a_file_is_found_in_an_odc_archive(self):
        cpio = (_odc_entry("./other", b"x") + _odc_entry("./" + macadi.FRAMEWORK_PATH, b"lib")
                + _odc_entry("TRAILER!!!", b""))

        assert macadi._file_from_cpio(cpio, macadi.FRAMEWORK_PATH) == b"lib"

    def test_the_x86_64_slice_is_taken_from_a_universal_binary(self):
        arm, intel = b"ARM64", b"INTEL"
        header = b"\xca\xfe\xba\xbe" + struct.pack(">I", 2)
        first = 8 + 40
        fat = (header
               + struct.pack(">5I", 0x0100000C, 0, first, len(arm), 0)
               + struct.pack(">5I", 0x01000007, 0, first + len(arm), len(intel), 0)
               + arm + intel)

        assert macadi._slice(fat, 0x01000007) == intel

    def test_bytes_that_are_not_apples_are_refused(self):
        with pytest.raises(macadi.EmulatedMacError, match="not the expected ones"):
            macadi.extract(gzip.compress(b"not the installer"))

    def test_a_stored_library_is_not_fetched_again(self, tmp_path: Path, monkeypatch):
        """Only the hash decides; a library that matches is never downloaded twice."""
        target = tmp_path / "CoreADI.x86_64"
        target.write_bytes(b"pretend")
        monkeypatch.setattr(macadi, "LIBRARY_SHA256", hashlib.sha256(b"pretend").hexdigest())
        monkeypatch.setattr(macadi, "_fetch_payload", lambda url: pytest.fail("fetched again"))

        assert macadi.ensure_library(target) == target


@pytest.mark.skipif(not os.environ.get("OTV_COREADI"), reason="set OTV_COREADI to a fetched library")
class TestTheRealEngine:
    """Apple's CoreADI under Unicorn, in its own process. Nothing is sent to Apple."""

    def test_it_loads_and_reports_an_unprovisioned_machine(self, tmp_path: Path):
        library = Path(os.environ["OTV_COREADI"])
        assert macadi.ensure_library(library) == library

        info = json.loads(macadi._engine("info", tmp_path / "machine", library))

        assert info["provisioned"] is False
        assert "<Mac OS X;" in info["identity"]["client_info"]


class TestTheFrozenExportersWayIn:
    """A PyInstaller build has no interpreter, so it runs itself with `--run-emulated-mac`."""

    def test_the_engines_modules_are_found_under_the_names_they_import_each_other_by(self):
        """
        In a frozen bundle they cannot be found on disk, so each must already be loaded - as the
        same module object, or the engine would hold two copies of its own state.
        """
        script = (
            "import sys; from exporter import macadi; macadi.load_engine();"
            "print(all(sys.modules[n] is sys.modules['machoadi.' + n] for n in macadi._ENGINE_MODULES))"
        )
        done = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True,
                              cwd=Path(__file__).resolve().parents[1], check=True)

        assert done.stdout.strip() == "True"

    def test_a_failure_reaches_the_parent_through_the_error_file(self, tmp_path: Path):
        out, err = tmp_path / "out", tmp_path / "err"

        code = _engine_here(out, err, "no-such-command")

        assert code != 0
        assert "invalid choice" in err.read_text(encoding="utf-8")

    def test_the_wizard_hands_over_before_anything_else(self):
        source = (Path(__file__).resolve().parents[1] / "exporter" / "wizard.py").read_text("utf-8")
        main = source[source.index('if __name__ == "__main__":'):]

        assert main.index("macadi.ENGINE_FLAG") < main.index("--self-test")

    @pytest.mark.skipif(not os.environ.get("OTV_COREADI"), reason="set OTV_COREADI to a fetched library")
    def test_it_runs_the_real_engine(self, tmp_path: Path):
        out, err = tmp_path / "out", tmp_path / "err"

        code = _engine_here(out, err, "info", "--state", str(tmp_path / "machine"),
                            env={**os.environ, "COREADI_PATH": os.environ["OTV_COREADI"]})

        assert code == 0, err.read_text(encoding="utf-8")
        assert json.loads(out.read_text(encoding="utf-8"))["provisioned"] is False


def _engine_here(out: Path, err: Path, *command: str, env: dict | None = None) -> int:
    """`run_engine_here` in a fresh interpreter, as the frozen child would be."""
    script = "import sys; from exporter import macadi; sys.exit(macadi.run_engine_here(sys.argv[1:]))"
    return subprocess.run([sys.executable, "-c", script, str(out), str(err), *command],
                          cwd=Path(__file__).resolve().parents[1], env=env, check=False).returncode
