"""
Running the CLI with nobody at the keyboard.

Two things are being protected, and the second is the reason this file is long.

**That a scripted run does not hang.** The failure it replaces is not an error - it is a cron job
sitting forever on a question nobody will answer, which reports nothing and finishes never. So
`--non-interactive` has to cover *every* prompt, including ones added later, and the test for that
is written against the guard rather than against a list of prompts.

**That a secret does not end up somewhere it can be read.** There is deliberately no `--password`
flag; a file must not be world-readable; and nothing is silently trimmed, because a password that
legitimately ends in a space would otherwise fail as though it were wrong.
"""

from __future__ import annotations

import asyncio

import pytest

from exporter import cli, prompts, secrets


@pytest.fixture(autouse=True)
def _prompting_allowed():
    """Every test starts able to prompt, whatever the last one did to the module-level flag."""
    prompts.forbid_prompting(False)
    yield
    prompts.forbid_prompting(False)


@pytest.fixture
def secret_file(tmp_path):
    def write(content: str, mode: int = 0o600):
        path = tmp_path / "secret"
        path.write_text(content, encoding="utf-8")
        path.chmod(mode)
        return path

    return write


class TestThereIsNoPasswordFlag:
    """
    The single most important property here, and the easiest to undo by being helpful.

    A command line is readable by every user on the machine through `ps`, and it lands in shell
    history. Adding `--password` would be one line and would quietly undo the whole design, so a
    test says it is absent rather than a comment asking people not to.
    """

    @pytest.mark.parametrize("flag", ["--password", "--passcode", "--apple-password"])
    def test_no_secret_can_be_given_on_the_command_line(self, flag, capsys):
        with pytest.raises(SystemExit):
            cli.build_parser().parse_args([flag, "hunter2"])

        assert "unrecognized arguments" in capsys.readouterr().err

    def test_the_file_and_environment_routes_do_exist(self):
        arguments = cli.build_parser().parse_args(["--password-file", "/dev/null"])

        assert arguments.password_file is not None
        assert secrets.APPLE_PASSWORD_VAR.startswith("OPENTAGVIEWER_")


class TestReadingASecretFromAFile:
    def test_it_reads_the_first_line(self, secret_file):
        assert secrets.read(secret_file("hunter2\n"), "UNSET_VAR") == "hunter2"

    def test_a_file_with_no_trailing_newline_works(self, secret_file):
        assert secrets.read(secret_file("hunter2"), "UNSET_VAR") == "hunter2"

    def test_leading_and_trailing_spaces_are_kept(self, secret_file):
        # A password may legitimately begin or end with a space. Trimming one produces a rejected
        # sign-in that looks exactly like a wrong password, which is the worst kind to debug: the
        # user is certain they typed it correctly, and they did.
        assert secrets.read(secret_file("  hunter2  \n"), "UNSET_VAR") == "  hunter2  "

    def test_a_second_line_is_not_part_of_the_secret(self, secret_file):
        assert secrets.read(secret_file("hunter2\n# a note\n"), "UNSET_VAR") == "hunter2"

    def test_a_world_readable_file_is_refused(self, secret_file):
        # Refused rather than warned about. The reason to prefer a file over an environment
        # variable is that a file can have permissions; one that does not is worse than what it
        # was chosen over, and a warning is advice nobody acts on.
        path = secret_file("hunter2\n", mode=0o644)

        with pytest.raises(secrets.SecretError, match="readable by other users"):
            secrets.read(path, "UNSET_VAR")

    def test_a_group_readable_file_is_refused_too(self, secret_file):
        path = secret_file("hunter2\n", mode=0o640)

        with pytest.raises(secrets.SecretError, match="readable by other users"):
            secrets.read(path, "UNSET_VAR")

    def test_the_advice_names_the_fix(self, secret_file):
        path = secret_file("hunter2\n", mode=0o644)

        with pytest.raises(secrets.SecretError, match=f"chmod 600 {path}"):
            secrets.read(path, "UNSET_VAR")

    def test_an_empty_file_is_an_error_rather_than_an_empty_password(self, secret_file):
        with pytest.raises(secrets.SecretError, match="is empty"):
            secrets.read(secret_file(""), "UNSET_VAR")

    def test_a_missing_file_says_so(self, tmp_path):
        with pytest.raises(secrets.SecretError, match="Could not read"):
            secrets.read(tmp_path / "nope", "UNSET_VAR")


class TestReadingASecretFromTheEnvironment:
    def test_it_is_used_when_no_file_is_given(self, monkeypatch):
        monkeypatch.setenv("A_TEST_VAR", "hunter2")

        assert secrets.read(None, "A_TEST_VAR") == "hunter2"

    def test_a_file_wins_over_the_environment(self, secret_file, monkeypatch):
        # The better mechanism takes precedence, so a leftover variable cannot quietly override a
        # deliberate file.
        monkeypatch.setenv("A_TEST_VAR", "from the environment")

        assert secrets.read(secret_file("from the file\n"), "A_TEST_VAR") == "from the file"

    def test_neither_is_not_an_error(self, monkeypatch):
        # It means "ask", and only the caller knows whether it may.
        monkeypatch.delenv("A_TEST_VAR", raising=False)

        assert secrets.read(None, "A_TEST_VAR") is None

    def test_an_empty_variable_is_not_treated_as_a_password(self, monkeypatch):
        monkeypatch.setenv("A_TEST_VAR", "")

        assert secrets.read(None, "A_TEST_VAR") == ""


class TestRefusingToAsk:
    """
    `--non-interactive` covers every prompt, not a remembered list of them.

    The guard lives in `prompts` so a prompt added later is covered without anybody deciding to
    cover it - which is the only version of this that stays true.
    """

    @pytest.mark.parametrize("call", [
        lambda: prompts.text("Apple ID"),
        lambda: prompts.password("Password"),
        lambda: prompts.confirm("Try again?"),
        lambda: prompts.select("Which device?", [prompts.Option(label="a", value=1)]),
        lambda: prompts.checkbox("What should go in?", [prompts.Option(label="a", value=1)]),
    ])
    def test_every_prompt_raises_instead_of_asking(self, call):
        prompts.forbid_prompting()

        with pytest.raises(prompts.PromptForbidden):
            asyncio.run(call())

    def test_the_failure_names_the_question_it_wanted_to_ask(self):
        # "Cannot prompt" tells somebody reading a CI log nothing. The question tells them which
        # value to supply.
        prompts.forbid_prompting()

        with pytest.raises(prompts.PromptForbidden, match="Screen-lock passcode for F2LX"):
            asyncio.run(prompts.password("Screen-lock passcode for F2LX"))

    def test_it_can_be_turned_back_off(self):
        prompts.forbid_prompting()
        prompts.forbid_prompting(False)

        # No exception: the flag is not one-way, which matters because it is module-level state
        # and the tests share a process.
        assert prompts._forbidden is False

    def test_every_public_prompt_is_guarded(self):
        """
        The one that survives a refactor.

        Written against the module rather than a list, so a prompt added next year fails this
        instead of quietly hanging a cron job.
        """
        import inspect

        asked = [
            name for name, value in vars(prompts).items()
            if inspect.iscoroutinefunction(value) and not name.startswith("_")
        ]

        assert asked, "no prompts found - this test is checking nothing"

        for name in asked:
            source = inspect.getsource(getattr(prompts, name))
            assert "_refuse_to_ask(" in source, f"prompts.{name} can hang a scripted run"


class FakeRecord:
    def __init__(self, serial: str) -> None:
        self.serial = serial

    def describe(self) -> str:
        return f"A device, serial {self.serial}"


class TestChoosingTheDeviceByserial:
    def test_it_finds_the_named_record(self):
        records = [FakeRecord("AAAA"), FakeRecord("BBBB")]

        assert cli._pick_device(records, "BBBB").serial == "BBBB"

    def test_the_match_ignores_case_and_spaces(self):
        # Serials get copied out of a device list, and a stray space is not worth a failed run.
        records = [FakeRecord("F2LX9Q")]

        assert cli._pick_device(records, "  f2lx9q ").serial == "F2LX9Q"

    def test_no_serial_means_ask(self):
        assert cli._pick_device([FakeRecord("AAAA")], None) is None

    def test_an_unknown_serial_is_an_error_that_lists_what_there_is(self):
        # Silently falling back to asking would hang a scripted run; silently picking the first
        # would unlock with the wrong device. Neither is acceptable, so it says what exists.
        records = [FakeRecord("AAAA"), FakeRecord("BBBB")]

        with pytest.raises(Exception, match="AAAA, BBBB"):
            cli._pick_device(records, "CCCC")


class FakeCandidate:
    def __init__(self, label: str, owned_beacon: dict) -> None:
        self.label = label
        self.owned_beacon = owned_beacon


AIRTAG = {"productId": 21760, "vendorId": 76}
A_MAC = {"model": "Mac16,1", "productId": 1, "vendorId": 76}


class TestTakingEverything:
    def test_it_takes_the_tags(self):
        candidates = [FakeCandidate("Wallet", AIRTAG), FakeCandidate("Backpack", AIRTAG)]

        taken = cli._take_all(candidates, include_my_devices=False)

        assert [c.label for c in taken] == ["Wallet", "Backpack"]

    def test_your_own_devices_are_left_out_by_default(self):
        """
        The safety property of `--all-tags`.

        "All my tags" and "all my tags plus the laptop I am sitting at" are different requests, and
        only one of them is what somebody writing a cron job meant. The interactive path names each
        device and asks again; a scripted run has nobody to ask.
        """
        candidates = [FakeCandidate("Wallet", AIRTAG), FakeCandidate("My MacBook", A_MAC)]

        taken = cli._take_all(candidates, include_my_devices=False)

        assert [c.label for c in taken] == ["Wallet"]

    def test_and_can_be_asked_for_explicitly(self):
        candidates = [FakeCandidate("Wallet", AIRTAG), FakeCandidate("My MacBook", A_MAC)]

        taken = cli._take_all(candidates, include_my_devices=True)

        assert [c.label for c in taken] == ["Wallet", "My MacBook"]

    def test_leaving_one_out_is_said_rather_than_done_quietly(self, capsys):
        # A bundle with fewer things in it than expected, and no line saying why, reads as a bug.
        candidates = [FakeCandidate("My MacBook", A_MAC)]

        cli._take_all(candidates, include_my_devices=False)

        assert "My MacBook" in capsys.readouterr().err


class TestTheFlagsParse:
    def test_the_scripted_run_needs_no_prompt_for_anything_but_the_code(self, tmp_path):
        # The documented shape of a scripted invocation, asserted so the docs cannot drift from it.
        password = tmp_path / "pw"
        password.write_text("hunter2")
        password.chmod(0o600)

        arguments = cli.build_parser().parse_args([
            "--non-interactive",
            "--apple-id", "me@example.com",
            "--password-file", str(password),
            "--passcode-file", str(password),
            "--device", "F2LX9Q",
            "--all-tags",
            "--no-password",
            "--output", str(tmp_path / "out.zip"),
        ])

        assert arguments.non_interactive
        assert arguments.apple_id == "me@example.com"
        assert arguments.device == "F2LX9Q"
        assert arguments.all_tags
        assert not arguments.include_my_devices

    def test_including_your_own_devices_is_never_implied(self):
        arguments = cli.build_parser().parse_args(["--all-tags"])

        assert not arguments.include_my_devices
