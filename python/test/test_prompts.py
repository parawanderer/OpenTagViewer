"""
The asking layer, and specifically the path taken when there is no terminal.

The drawn path cannot be tested without a pseudo-terminal, and trying is worse than not: with no
terminal attached prompt_toolkit does not fail, it waits for keystrokes that never arrive - so a
test that got it wrong would hang the suite rather than fail it.

What *is* tested is the decision to draw at all, and the plain path everything falls back to. That
path is what runs in CI, under a pipe, and on a terminal too limited to draw on, so it is not a
lesser version to be left rotting.
"""

from __future__ import annotations

import asyncio
import io

import pytest

from exporter import prompts
from exporter.prompts import Abandoned, Option, _answered


def run(coroutine):
    return asyncio.run(coroutine)


@pytest.fixture
def plain(monkeypatch):
    """Force the non-interactive path, whatever the suite is being run from."""
    monkeypatch.setattr(prompts, "interactive", lambda: False)


@pytest.fixture
def typed(monkeypatch):
    """Feed lines to `input()`, as a person at a plain prompt would."""
    def _typed(*lines: str) -> None:
        monkeypatch.setattr("builtins.input", io.StringIO("\n".join(lines) + "\n").readline)
    return _typed


OPTIONS = [Option("cat", "a"), Option("keys", "b", note="(no alignment record)"), Option("bike", "c")]


class TestWhetherToDraw:
    def test_needs_a_terminal_at_both_ends(self, monkeypatch):
        # Output redirected to a file with input still on the terminal would paint the list into
        # the file, so both are required rather than either.
        monkeypatch.setattr("sys.stdin.isatty", lambda: True)
        monkeypatch.setattr("sys.stdout.isatty", lambda: False)

        assert prompts.interactive() is False

    def test_a_pipe_takes_the_plain_path(self, monkeypatch):
        monkeypatch.setattr("sys.stdin.isatty", lambda: False)
        monkeypatch.setattr("sys.stdout.isatty", lambda: True)

        assert prompts.interactive() is False


class TestSelectingOne:
    def test_returns_the_value_behind_what_was_picked(self, plain, typed):
        typed("2")

        assert run(prompts.select("Which?", OPTIONS)) == "b"

    def test_keeps_asking_until_the_answer_is_one_of_them(self, plain, typed, capsys):
        typed("9", "nonsense", "1")

        assert run(prompts.select("Which?", OPTIONS)) == "a"
        assert "is not one of the numbers above" in capsys.readouterr().err

    def test_refuses_to_ask_about_nothing(self, plain):
        with pytest.raises(ValueError, match="Nothing to choose"):
            run(prompts.select("Which?", []))


class TestSelectingSeveral:
    def test_takes_several_numbers(self, plain, typed):
        typed("1 3")

        assert run(prompts.checkbox("Which?", OPTIONS)) == ["a", "c"]

    def test_all_means_all(self, plain, typed):
        typed("all")

        assert run(prompts.checkbox("Which?", OPTIONS)) == ["a", "b", "c"]

    def test_one_bad_number_rejects_the_whole_line(self, plain, typed):
        # Rather than exporting the two tags that parsed and quietly dropping the third.
        typed("1 9", "1 2")

        assert run(prompts.checkbox("Which?", OPTIONS)) == ["a", "b"]

    def test_a_note_is_shown_beside_the_option(self, plain, typed, capsys):
        # It is what someone needs *before* choosing - an accessory with no alignment record makes
        # the recipient's first locate slow, and that is not discoverable afterwards.
        typed("1")

        run(prompts.checkbox("Which?", OPTIONS))

        assert "(no alignment record)" in capsys.readouterr().err


class TestTextAndConfirmation:
    def test_takes_a_line_of_text(self, plain, typed):
        typed("  the good bike  ")

        assert run(prompts.text("Name?")) == "the good bike"

    def test_an_empty_answer_takes_the_default(self, plain, typed):
        typed("")

        assert run(prompts.text("Name?", default="Tag 7F3A")) == "Tag 7F3A"

    @pytest.mark.parametrize(("answer", "expected"), [("y", True), ("yes", True), ("n", False), ("", False)])
    def test_confirmation_defaults_to_no(self, plain, typed, answer, expected):
        typed(answer)

        assert run(prompts.confirm("Sure?")) is expected

    def test_confirmation_can_default_to_yes(self, plain, typed):
        typed("")

        assert run(prompts.confirm("Sure?", default=True)) is True


class TestStopping:
    def test_ctrl_c_at_a_prompt_is_an_exception_rather_than_a_none(self):
        # questionary returns None when the user interrupts, which is easy to carry on from by
        # accident - and carrying on from an unanswered question about which tags to export is
        # the worst possible place to guess.
        with pytest.raises(Abandoned):
            _answered(None)

    def test_a_real_answer_passes_through(self):
        assert _answered(["a"]) == ["a"]
        # Including the falsy ones: an empty selection is an answer, not an interruption.
        assert _answered([]) == []


class TestLiningTheNotesUp:
    def test_notes_start_in_the_same_column(self, plain, typed, capsys):
        typed("1")
        options = [Option("cat", "a", note="AirTag"), Option("a much longer name", "b", note="iPad")]

        run(prompts.checkbox("Which?", options))

        lines = [line for line in capsys.readouterr().err.splitlines() if "." in line[:5]]
        assert [line.index("AirTag") for line in lines if "AirTag" in line] == \
               [line.index("iPad") for line in lines if "iPad" in line]

    def test_an_emoji_counts_as_the_two_columns_it_occupies(self):
        # Padding by len() lines up a list of plain names and stairsteps the moment one tag has an
        # emoji and another does not - which is every real account.
        assert prompts.display_width("\N{SCHOOL SATCHEL} bag") == len(" bag") + 2

    def test_an_option_with_no_note_is_not_padded(self):
        assert Option("cat", "a").padded(20) == "cat"
