"""
Writing a bundle out as a zip, locked and unlocked.

The encrypted case carries a warning it is worth having a test say out loud: no OpenTagViewer
release published so far can open one, because the Android importer reads zips with
`java.util.zip.ZipInputStream`, which cannot decrypt anything at all.
"""

from __future__ import annotations

import zipfile

import pytest

from opentagviewer_export import ExportBundle, generate_passcode, write_zip

BUNDLE = ExportBundle(
    entries={
        "OPENTAGVIEWER.yml": b"version: 0.0.2\n",
        "OwnedBeacons/725A989D-D871-49A7-B2FE-948C24F356AB.plist": b"<?xml ?>",
        "BeaconNamingRecord/725A989D-D871-49A7-B2FE-948C24F356AB/59AFE75B-8BD8-4522-85BC-F9B0BECD257A.plist": b"<?xml ?>",  # noqa: E501
    },
    exported_at_ms=1786282770586,
)


class TestAPlainZip:
    def test_holds_every_entry_and_nothing_else(self, tmp_path):
        written = write_zip(BUNDLE, tmp_path / "export.zip")

        with zipfile.ZipFile(written) as archive:
            assert set(archive.namelist()) == set(BUNDLE.entries)

    def test_the_contents_survive_the_trip(self, tmp_path):
        written = write_zip(BUNDLE, tmp_path / "export.zip")

        with zipfile.ZipFile(written) as archive:
            for name, content in BUNDLE.entries.items():
                assert archive.read(name) == content

    def test_uses_forward_slashes_so_a_zip_written_on_windows_still_imports(self, tmp_path):
        written = write_zip(BUNDLE, tmp_path / "export.zip")

        with zipfile.ZipFile(written) as archive:
            assert not any("\\" in name for name in archive.namelist())

    def test_the_same_bundle_written_twice_is_the_same_file_twice(self, tmp_path):
        # Entry order and timestamps come from the bundle rather than the clock, which is what
        # makes a byte comparison possible at all.
        first = write_zip(BUNDLE, tmp_path / "first.zip").read_bytes()
        second = write_zip(BUNDLE, tmp_path / "second.zip").read_bytes()

        assert first == second

    def test_entries_are_dated_from_the_bundle_not_from_now(self, tmp_path):
        written = write_zip(BUNDLE, tmp_path / "export.zip")

        with zipfile.ZipFile(written) as archive:
            assert archive.getinfo("OPENTAGVIEWER.yml").date_time[:3] == (2026, 8, 9)

    def test_a_clock_set_before_1980_produces_an_odd_date_rather_than_an_exception(self, tmp_path):
        # Zip cannot represent one, and failing halfway through writing a file of key material is
        # worse than a wrong date on an entry nobody reads.
        early = ExportBundle(entries={"OPENTAGVIEWER.yml": b"x"}, exported_at_ms=1)

        with zipfile.ZipFile(write_zip(early, tmp_path / "early.zip")) as archive:
            assert archive.getinfo("OPENTAGVIEWER.yml").date_time[:3] == (1980, 1, 1)


class TestAnEncryptedZip:
    def test_opens_with_the_code(self, tmp_path):
        code = generate_passcode()
        written = write_zip(BUNDLE, tmp_path / "export.zip", password=code)

        pyzipper = pytest.importorskip("pyzipper")
        with pyzipper.AESZipFile(written) as archive:
            archive.setpassword(code.encode("utf-8"))
            assert archive.read("OPENTAGVIEWER.yml") == BUNDLE.entries["OPENTAGVIEWER.yml"]

    def test_does_not_open_with_a_different_code(self, tmp_path):
        written = write_zip(BUNDLE, tmp_path / "export.zip", password=generate_passcode())

        pyzipper = pytest.importorskip("pyzipper")
        with pyzipper.AESZipFile(written) as archive:
            archive.setpassword(generate_passcode().encode("utf-8"))
            with pytest.raises(RuntimeError):
                archive.read("OPENTAGVIEWER.yml")

    def test_the_listing_is_readable_without_the_code_and_the_contents_are_not(self, tmp_path):
        # Worth stating rather than discovering: AES-zip encrypts entry data, not the central
        # directory. Anyone holding the file can see how many tags it carries.
        written = write_zip(BUNDLE, tmp_path / "export.zip", password=generate_passcode())

        with zipfile.ZipFile(written) as archive:
            assert set(archive.namelist()) == set(BUNDLE.entries)
            with pytest.raises(RuntimeError):
                archive.read("OPENTAGVIEWER.yml")

    def test_an_empty_password_is_refused_rather_than_written_unlocked(self, tmp_path):
        # `password=""` reads as "lock it" and would otherwise produce something nobody can open.
        with pytest.raises(ValueError, match="empty password"):
            write_zip(BUNDLE, tmp_path / "export.zip", password="")


class TestOverwriting:
    def test_replaces_an_existing_file_rather_than_appending_to_it(self, tmp_path):
        path = tmp_path / "export.zip"
        write_zip(BUNDLE, path)
        smaller = ExportBundle(entries={"OPENTAGVIEWER.yml": b"x"}, exported_at_ms=BUNDLE.exported_at_ms)

        write_zip(smaller, path)

        with zipfile.ZipFile(path) as archive:
            assert archive.namelist() == ["OPENTAGVIEWER.yml"]
