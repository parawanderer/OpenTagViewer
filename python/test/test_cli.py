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
from findmy import InvalidCredentialsError, MobileMeDelegateError, UnhandledProtocolError
from findmy.keychain.session import KeychainSessionError

from exporter import cli, prompts
from exporter.icloud import ExportSourceError
from exporter.version import EXPORT_VIA_CLI, GITHUB_ISSUES_LINK
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


class TestKeyFilesReachTheBundle:
    """
    A tag from `--add-keys` is not in any Apple account, and nothing else can produce it again.

    It is read, named by the user, and then - until this was fixed - dropped without a word if the
    account half of the run had nothing to contribute, because the key-file tags were only added
    to the export after the point where an empty account gave up. The windowed exporter has always
    allowed a bundle of nothing but key-file tags; this makes the CLI agree.
    """

    def key_file(self, tmp_path: Path) -> Path:
        path = tmp_path / "devices.json"
        path.write_text(json.dumps([{
            "id": 7,
            "name": "bike",
            "privateKey": base64.b64encode(KEY).decode(),
            "additionalKeys": [],
        }]))
        return path

    def arguments(self, tmp_path: Path):
        return cli.build_parser().parse_args([
            "--add-keys", str(self.key_file(tmp_path)),
            "--source", "icloud",
            "--no-password",
            "--output", str(tmp_path / "export.zip"),
        ])

    def fetched(self, monkeypatch, value) -> None:
        """What the account produced - or None, for a sign-in or unlock that did not get there."""
        async def _read(*_args, **_kwargs):
            return value

        monkeypatch.setattr(cli, "read_icloud", _read)

    def exported_names(self, tmp_path: Path) -> list[str]:
        with zipfile.ZipFile(tmp_path / "export.zip") as archive:
            return archive.namelist()

    def test_an_account_with_nothing_in_it_still_exports_the_key_file_tag(self, tmp_path, answers, monkeypatch):
        from exporter.icloud import Fetched

        answers.append("")  # accept the suggested name
        self.fetched(monkeypatch, Fetched(candidates=[], skipped=[]))

        assert asyncio.run(cli.run(self.arguments(tmp_path))) == 0
        assert any("bike" in name or "tag-" in name for name in self.exported_names(tmp_path))

    def test_a_run_with_no_key_files_and_nothing_to_export_still_fails(self, tmp_path, monkeypatch):
        # Unchanged: an empty account and nothing else asked for is a run that produced nothing,
        # and writing an empty bundle would be worse than saying so.
        from exporter.icloud import Fetched

        arguments = self.arguments(tmp_path)
        arguments.add_keys = []
        self.fetched(monkeypatch, Fetched(candidates=[], skipped=[]))

        assert asyncio.run(cli.run(arguments)) == 1
        assert not (tmp_path / "export.zip").exists()

    def test_a_sign_in_that_never_got_there_keeps_the_tag_and_still_reports_failure(
        self, tmp_path, answers, monkeypatch, capsys,
    ):
        """
        Two things are true at once, and the run says both.

        The keychain could not be unlocked, so what was asked for did not all happen - that is the
        exit code. The key-file tag was read and named and is in no danger of being recoverable
        later, so it is written - and the message says what the bundle does not contain, because a
        file that is quietly missing half its contents is worse than one that is missing them
        loudly.
        """
        answers.append("")
        self.fetched(monkeypatch, None)

        assert asyncio.run(cli.run(self.arguments(tmp_path))) == 1
        assert (tmp_path / "export.zip").exists()
        assert "key file" in capsys.readouterr().err

    def test_no_account_is_read_at_all_when_that_is_what_was_asked_for(self, tmp_path, answers, monkeypatch):
        """
        `--source none`: the tags are already on disk, so nothing is signed into.

        Both readers are replaced with something that fails the test if it is called, because the
        cost being avoided is not time - it is an Apple ID password, a device registered on the
        account, and a keychain unlock, to fetch a list nothing was going to be taken from.
        """
        async def _should_not_be_read(*_args, **_kwargs):
            raise AssertionError("--source none read a source")

        monkeypatch.setattr(cli, "read_icloud", _should_not_be_read)
        monkeypatch.setattr(cli, "read_local", _should_not_be_read)

        answers.append("")
        arguments = self.arguments(tmp_path)
        arguments.source = "none"

        assert asyncio.run(cli.run(arguments)) == 0
        assert self.exported_names(tmp_path)

    def test_reading_no_account_with_no_key_files_says_what_is_missing(self, tmp_path, monkeypatch):
        # It would otherwise write an empty bundle, or read the account it was told not to.
        arguments = self.arguments(tmp_path)
        arguments.source = "none"
        arguments.add_keys = []

        with pytest.raises(ExportSourceError, match="--add-keys"):
            asyncio.run(cli.run(arguments))

        assert not (tmp_path / "export.zip").exists()

    def test_ticking_nothing_still_exports_the_key_file_tag(self, tmp_path, answers, monkeypatch):
        from exporter.icloud import Fetched

        answers.append("")
        self.fetched(monkeypatch, Fetched(candidates=[], skipped=[]))

        async def _nothing_ticked(*_args, **_kwargs):
            return []

        monkeypatch.setattr(prompts, "checkbox", _nothing_ticked)

        assert asyncio.run(cli.run(self.arguments(tmp_path))) == 0
        assert self.exported_names(tmp_path)


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

    def test_dropping_them_all_is_allowed_when_key_files_are_being_exported_too(self, monkeypatch):
        # Then "none of these" is an answer rather than an empty run: the bundle still has the
        # tags read from the key files, which is what `or_nothing` is told about.
        _, mac = self.candidates()

        async def _no(*_args, **_kwargs):
            return False

        monkeypatch.setattr(prompts, "confirm", _no)

        assert asyncio.run(cli._confirm_devices([mac], or_nothing=True)) == []


class TestWhereToReportSomethingUnfixable:
    """
    `UnhandledProtocolError` means Apple said something this library does not model.

    There is nothing for the user to correct, so the only useful next step is a bug report - and
    for years this asked for one without saying where to put it. The window has always named the
    URL in its error dialog; the headless half said "if you report this" and stopped.

    Issue #140 is the worked example: a `KeychainSessionError`, which is an
    `UnhandledProtocolError` by inheritance, arriving as "no keychain keys are held" - which reads
    as a broken account and is not one.
    """

    def _failing_run(self, monkeypatch, error):
        # `run`, not `_run_and_return` - the handlers are inside the latter, so patching it
        # would test nothing and let the exception straight out.
        async def _boom(_arguments):
            raise error

        monkeypatch.setattr(cli, "run", _boom)

    def test_it_names_where_to_report(self, monkeypatch, capsys):
        self._failing_run(monkeypatch, UnhandledProtocolError("no keychain keys are held"))

        assert cli.main([]) == 1
        assert GITHUB_ISSUES_LINK in capsys.readouterr().err

    def test_it_still_says_what_apple_did(self, monkeypatch, capsys):
        # The link is an addition, not a replacement: the message is the only description of the
        # actual problem anybody has.
        self._failing_run(monkeypatch, UnhandledProtocolError("no keychain keys are held"))

        cli.main([])

        assert "no keychain keys are held" in capsys.readouterr().err

    def test_a_keychain_failure_reaches_the_same_place(self, monkeypatch, capsys):
        # By inheritance rather than by being listed, which is the only reason #140 got a usable
        # message at all. A separate handler that forgot it would be silent here.
        self._failing_run(
            monkeypatch,
            KeychainSessionError("No keychain keys are held, so nothing can be decrypted."),
        )

        assert cli.main([]) == 1
        assert GITHUB_ISSUES_LINK in capsys.readouterr().err

    def test_a_mistake_the_user_can_fix_does_not_ask_for_a_bug_report(self, monkeypatch, capsys):
        # The distinction that makes the link worth anything. Being sent to file an issue about
        # your own typo is wrong, and it teaches people to ignore the link when it matters.
        self._failing_run(monkeypatch, ExportSourceError("Signing in was stopped."))

        assert cli.main([]) == 1
        assert GITHUB_ISSUES_LINK not in capsys.readouterr().err

    def test_the_template_it_links_to_exists(self):
        """
        The link names a file in `.github/ISSUE_TEMPLATE/`, and a rename breaks it in silence.

        GitHub does not error on an unknown `?template=`; it drops the reporter on a blank issue
        with none of the questions and none of the labels. So the failure is a slightly worse bug
        report, months later, and nothing anywhere says why.
        """
        name = GITHUB_ISSUES_LINK.partition("template=")[2]
        assert name, "the issues link no longer names a template"

        template = Path(__file__).resolve().parents[2] / ".github" / "ISSUE_TEMPLATE" / name
        assert template.is_file(), f"{GITHUB_ISSUES_LINK} points at a template that is not there"

    def test_the_template_carries_the_labels_itself(self):
        # Rather than the URL carrying them. GitHub applies `?labels=` only for somebody with
        # permission to label, which a person reporting a bug generally is not - so a URL that
        # looks like it labels things would quietly not.
        import yaml  # noqa: PLC0415 - only this test needs it

        name = GITHUB_ISSUES_LINK.partition("template=")[2]
        template = Path(__file__).resolve().parents[2] / ".github" / "ISSUE_TEMPLATE" / name
        front = yaml.safe_load(template.read_text(encoding="utf-8"))

        assert "bug" in front["labels"]
        assert "@exporter-tool" in front["labels"]
        assert "labels=" not in GITHUB_ISSUES_LINK
