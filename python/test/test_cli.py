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
from findmy import InvalidCredentialsError, MobileMeDelegateError

from exporter import cli, prompts
from exporter.icloud import ExportSourceError
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


class FakeAccount:
    """Enough of an `AsyncAppleAccount` for the sign-in flow: it can be closed, and it says so."""

    def __init__(self) -> None:
        self.closed = False

    async def close(self) -> None:
        self.closed = True


@pytest.fixture
def apple(monkeypatch):
    """
    The account, the sign-in and the identity store, faked.

    Nothing here reaches Apple, so what can be tested is what the CLI does around the sign-in:
    whether it remembers the device it just registered, and whether it hands the session back.
    """
    account = FakeAccount()
    remembered: list = []
    outcome: list = [None]  # what `log_in` does: None to succeed, or an exception to raise

    async def _log_in(*_args, **_kwargs):
        if outcome[0] is not None:
            raise outcome[0]

    async def _password(_question: str) -> str:
        return "hunter2"

    async def _text(_question: str, default: str = "") -> str:
        return "someone@example.com"

    monkeypatch.setattr(cli.icloud, "make_account", lambda *_args, **_kwargs: account)
    monkeypatch.setattr(cli.icloud, "log_in", _log_in)
    monkeypatch.setattr(cli.icloud, "remember", remembered.append)
    monkeypatch.setattr(prompts, "password", _password)
    monkeypatch.setattr(prompts, "text", _text)

    class Apple:
        def __init__(self) -> None:
            self.account = account
            self.remembered = remembered

        def fails_with(self, error: BaseException) -> None:
            outcome[0] = error

    return Apple()


def signing_in(**overrides):
    arguments = cli.build_parser().parse_args([])
    for name, value in overrides.items():
        setattr(arguments, name, value)
    return arguments


class TestRememberingTheDevice:
    """
    Every sign-in registers a device on the user's Apple account, and it should be the same one.

    Forgetting to store the identity is not a slow leak of disk space: the account's device list
    gains another "MacBook Pro, 0PENTAGXPORT" per export, each looking like a different machine
    that happens to share a serial - next to a button offering to remove a device the user does
    not recognise. See `exporter.device`.
    """

    def test_an_ordinary_sign_in_is_remembered(self, apple):
        # The path almost every run takes, and the one that was storing nothing: the call sat in
        # the terms handler, so only an account with unaccepted terms kept its identity.
        account = asyncio.run(cli.sign_in(signing_in()))

        assert apple.remembered == [account]

    def test_a_sign_in_that_needed_the_terms_accepting_is_remembered_once(self, apple, monkeypatch):
        apple.fails_with(MobileMeDelegateError(localized_error="TERMS"))

        async def _accepted(*_args, **_kwargs):
            return True

        async def _yes(*_args, **_kwargs):
            return True

        monkeypatch.setattr(cli, "accept_terms", _accepted)
        monkeypatch.setattr(prompts, "confirm", _yes)

        account = asyncio.run(cli.sign_in(signing_in()))

        assert apple.remembered == [account]

    def test_a_failed_sign_in_registered_nothing_to_remember(self, apple):
        apple.fails_with(InvalidCredentialsError("no"))

        with pytest.raises(InvalidCredentialsError):
            asyncio.run(cli.sign_in(signing_in()))

        assert apple.remembered == []


class TestHandingBackTheSession:
    """
    A sign-in that fails leaves an open aiohttp session behind.

    asyncio reports that as "Unclosed client session" *after* the real error, where it reads like
    a second, worse fault - so every way out of `sign_in` other than success closes it. The terms
    handler is the one that did not: an exception raised inside an `except` clause is not caught
    by a sibling `except` on the same `try`.
    """

    def test_a_rejected_password_closes_it(self, apple):
        apple.fails_with(InvalidCredentialsError("no"))

        with pytest.raises(InvalidCredentialsError):
            asyncio.run(cli.sign_in(signing_in()))

        assert apple.account.closed

    def test_declining_to_fetch_the_terms_closes_it(self, apple, monkeypatch):
        apple.fails_with(MobileMeDelegateError(localized_error="TERMS"))

        async def _no(*_args, **_kwargs):
            return False

        monkeypatch.setattr(prompts, "confirm", _no)

        with pytest.raises(ExportSourceError):
            asyncio.run(cli.sign_in(signing_in()))

        assert apple.account.closed

    def test_stopping_at_the_terms_themselves_closes_it(self, apple, monkeypatch):
        apple.fails_with(MobileMeDelegateError(localized_error="TERMS"))

        async def _declined(*_args, **_kwargs):
            return False

        monkeypatch.setattr(cli, "accept_terms", _declined)

        with pytest.raises(ExportSourceError):
            asyncio.run(cli.sign_in(signing_in(accept_terms=True)))

        assert apple.account.closed

    def test_a_successful_sign_in_hands_the_session_over_still_open(self, apple):
        # The caller does the fetching and closes it afterwards; closing it here would end the
        # export at the moment it became possible.
        assert asyncio.run(cli.sign_in(signing_in())) is apple.account
        assert not apple.account.closed


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
