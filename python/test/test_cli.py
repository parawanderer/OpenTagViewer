"""
The CLI's own decisions - the ones it makes without an Apple account.

Signing in, unlocking and fetching are not tested here; they need a real account, and the
`--help` text is the only part of them anything can check. What is tested is what the CLI does
with a file it was handed, what it defaults to, and what it tells the user about a bundle that
cannot be taken back.
"""

from __future__ import annotations

import asyncio
import base64
import json
import zipfile
from pathlib import Path

import pytest

from exporter import cli, prompts
from exporter.version import EXPORT_VIA_CLI
from opentagviewer_export import ExportBundle, generate_passcode

KEY = bytes(range(28))


@pytest.fixture
def answers(monkeypatch):
    """
    Queue up what the user types, and fail the test if it asks more than it was told.

    Patches the prompt layer rather than stdin: with a terminal attached the real one draws a
    prompt_toolkit view, and without one it blocks on `input()` - neither of which a test wants.
    """
    queued: list[str] = []

    async def _text(_question: str, default: str = "") -> str:
        if not queued:
            raise AssertionError("the CLI asked more questions than the test answered")
        return queued.pop(0) or default

    monkeypatch.setattr(prompts, "text", _text)
    return queued


class TestArguments:
    def test_defaults_to_locking_the_bundle(self):
        # It holds keys that cannot be revoked once handed over.
        assert cli.build_parser().parse_args([]).no_password is False

    def test_a_plain_zip_has_to_be_asked_for(self):
        assert cli.build_parser().parse_args(["--no-password"]).no_password is True

    def test_key_files_accumulate(self):
        arguments = cli.build_parser().parse_args(["--add-keys", "a.keys", "--add-keys", "b.json"])

        assert arguments.add_keys == [Path("a.keys"), Path("b.json")]

    def test_anisette_runs_locally_unless_a_server_is_named(self):
        # Local keeps the sign-in between this machine and Apple; a server sees the headers.
        assert cli.build_parser().parse_args([]).anisette_url is None

    def test_the_default_filename_carries_a_date_that_sorts(self):
        assert cli._default_output().name.startswith("OpenTagViewer_export_")
        assert cli._default_output().suffix == ".zip"


class TestReadingKeyFiles:
    def macless_haystack(self, tmp_path: Path, name: str = "bike") -> Path:
        path = tmp_path / "devices.json"
        path.write_text(json.dumps([{
            "id": 7,
            "name": name,
            "privateKey": base64.b64encode(KEY).decode(),
            "additionalKeys": [],
        }]))
        return path

    def test_reads_a_file_and_keeps_the_name_it_carried(self, tmp_path, answers):
        answers.append("")  # accept the suggested name

        prepared, = asyncio.run(cli.read_key_files([self.macless_haystack(tmp_path)]))

        assert prepared.name == "bike"
        # The file said `7`; identifiers are derived from the tag's own public key instead, since
        # a number local to another tool collides the moment two people export from it.
        assert prepared.identifier.startswith("tag-")

    def test_a_typed_name_wins_over_the_suggestion(self, tmp_path, answers):
        answers.append("the good bike")

        prepared, = asyncio.run(cli.read_key_files([self.macless_haystack(tmp_path)]))

        assert prepared.name == "the good bike"

    def test_a_tag_whose_file_carried_no_name_is_offered_one_from_its_public_key(self, tmp_path, answers):
        answers.append("")
        path = tmp_path / "keys.txt"
        path.write_text(base64.b64encode(KEY).decode())

        prepared, = asyncio.run(cli.read_key_files([path]))

        # From the filename, since a bare key list carries nothing else - and the identifier comes
        # from the advertisement key, so two tags named alike do not collide in the bundle.
        assert prepared.name == "keys"
        assert prepared.identifier.startswith("tag-")

    def test_refuses_a_file_that_does_not_exist(self, tmp_path):
        with pytest.raises(Exception, match="Could not read"):
            asyncio.run(cli.read_key_files([tmp_path / "nothing.json"]))

    def test_a_file_of_nonsense_says_what_would_have_worked(self, tmp_path):
        path = tmp_path / "notes.txt"
        path.write_text("nothing like a key at all\n")

        with pytest.raises(Exception, match="Macless Haystack"):
            asyncio.run(cli.read_key_files([path]))


class TestWritingTheBundle:
    BUNDLE = ExportBundle(entries={"OPENTAGVIEWER.yml": b"version: 0.0.2\n"}, exported_at_ms=1786282770586)

    def test_writes_a_locked_bundle_and_prints_the_code_once(self, tmp_path, capsys):
        code = generate_passcode()
        output = tmp_path / "export.zip"

        cli.write(self.BUNDLE, output, code)

        message = capsys.readouterr().err
        # Grouped for writing down, and said to travel separately - a code sent with the file is
        # in the same backup as the file.
        assert "-".join([code[0:4], code[4:8], code[8:12]]) in message
        assert "separately" in message
        with zipfile.ZipFile(output) as archive, pytest.raises(RuntimeError):
            archive.read("OPENTAGVIEWER.yml")

    def test_says_plainly_when_a_bundle_is_not_locked(self, tmp_path, capsys):
        output = tmp_path / "export.zip"

        cli.write(self.BUNDLE, output, None)

        message = capsys.readouterr().err
        assert "not locked" in message
        assert "cannot be revoked" in message
        with zipfile.ZipFile(output) as archive:
            assert archive.read("OPENTAGVIEWER.yml") == b"version: 0.0.2\n"


class TestWhatItStampsOnAnExport:
    def test_names_itself_rather_than_the_windowed_exporter(self):
        # Same version, different program: a bug report that says which is worth more than one
        # that says "the exporter".
        assert EXPORT_VIA_CLI.startswith("OpenTagViewer.cli:")


class TestIncludingYourOwnDevices:
    """
    The 'a for all' problem: one keystroke takes the Mac along with the AirTags.

    A bundle holding a Mac lets whoever receives it locate that Mac - not a wallet, the person -
    with no way to revoke it short of unpairing. The list says what each row is; this says what
    including one *means*, at the moment it is incurred and not before.
    """

    def candidates(self):
        from exporter.icloud import Candidate

        tag = Candidate(
            beacon_id="A", name="cat", emoji=None, has_alignment=True,
            owned_beacon={"model": "", "productId": 21760}, naming_record={}, key_alignment_record=None,
        )
        mac = Candidate(
            beacon_id="B", name="MacBook Air", emoji=None, has_alignment=False,
            owned_beacon={"model": "Mac14,15"}, naming_record={}, key_alignment_record=None,
        )
        return tag, mac

    def test_says_nothing_when_only_tags_were_chosen(self, monkeypatch, capsys):
        # A warning that fires when nothing is wrong is one people learn to dismiss.
        tag, _ = self.candidates()

        async def _refuse(*_args, **_kwargs):
            raise AssertionError("should not have asked")

        monkeypatch.setattr(prompts, "confirm", _refuse)

        assert asyncio.run(cli._confirm_devices([tag])) == [tag]

    def test_names_the_device_and_keeps_it_when_confirmed(self, monkeypatch, capsys):
        tag, mac = self.candidates()

        async def _yes(*_args, **_kwargs):
            return True

        monkeypatch.setattr(prompts, "confirm", _yes)

        assert asyncio.run(cli._confirm_devices([tag, mac])) == [tag, mac]
        assert "MacBook Air" in capsys.readouterr().err

    def test_drops_it_when_declined_and_keeps_everything_else(self, monkeypatch):
        # Rather than restarting the selection: what they said about the tags was not in doubt.
        tag, mac = self.candidates()

        async def _no(*_args, **_kwargs):
            return False

        monkeypatch.setattr(prompts, "confirm", _no)

        assert asyncio.run(cli._confirm_devices([tag, mac])) == [tag]

    def test_declining_everything_is_an_error_rather_than_an_empty_bundle(self, monkeypatch):
        _, mac = self.candidates()

        async def _no(*_args, **_kwargs):
            return False

        monkeypatch.setattr(prompts, "confirm", _no)

        with pytest.raises(Exception, match="Nothing left to export"):
            asyncio.run(cli._confirm_devices([mac]))


class TestVerificationCodes:
    """
    What counts as a code, in the window and on the CLI.

    Blocking OK until it is one saves a round trip to Apple to be told what the dialog already
    knew - and a rejected code is not a free question: the next one has to be sent again.
    """

    @pytest.mark.parametrize("typed", ["123456", "123 456", "123-456", " 123456 "])
    def test_accepts_six_digits_however_they_were_pasted(self, typed):
        # People paste them with a space or a hyphen, because that is how the notification shows
        # them. Taking the digits is kinder than rejecting the paste.
        from exporter.wizard import is_verification_code, verification_code

        assert is_verification_code(typed)
        assert verification_code(typed) == "123456"

    @pytest.mark.parametrize("typed", ["", "   ", "12345", "1234567", "abcdef", "12345a"])
    def test_refuses_anything_that_is_not_six_digits(self, typed):
        from exporter.wizard import is_verification_code

        assert not is_verification_code(typed)
