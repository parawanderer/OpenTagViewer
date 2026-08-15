"""
What the wizard's list does when a key file is added to it.

The list is the one screen where an irreversible decision is made, and adding a key file happens
*after* somebody has read it and ticked things. Rebuilding the list is the natural way to show a
new row, and it is also how the ticks and the "could not be exported" note came to be thrown away
- silently, mid-decision, on a screen whose whole job is to say what is about to be handed over.

Tk is needed to have a list at all, and CI has none - see `test_codes.py`. Nothing here runs the
main loop: the window is built, driven directly, and destroyed.
"""

from __future__ import annotations

import base64
import json
from pathlib import Path

import pytest

tk = pytest.importorskip("tkinter")

from exporter import wizard  # noqa: E402 - after the skip above
from exporter.icloud import Candidate, Skipped  # noqa: E402

KEY = bytes(range(28))


@pytest.fixture
def app(monkeypatch):
    """
    The window, with the accessories already read and nothing that opens a dialog.

    `_load` is scheduled by the constructor and never fires, because no main loop runs here.
    """
    try:
        window = wizard.WizardApp()
    except tk.TclError as e:
        pytest.skip(f"no display to draw on: {e}")

    window.withdraw()
    window.candidates = [
        Candidate(
            beacon_id="A", name="cat", emoji=None, has_alignment=True,
            owned_beacon={"model": "", "productId": 21760}, naming_record={}, key_alignment_record=None,
        ),
        Candidate(
            beacon_id="B", name="bag", emoji=None, has_alignment=True,
            owned_beacon={"model": "", "productId": 21760}, naming_record={}, key_alignment_record=None,
        ),
    ]

    monkeypatch.setattr(wizard, "_ask_string", lambda *_args, **_kwargs: "the bike")

    yield window

    try:
        window.destroy()
    except tk.TclError:
        pass


@pytest.fixture
def key_file(tmp_path, monkeypatch):
    """A Macless Haystack file, and the file dialog already answered with it."""
    path = tmp_path / "devices.json"
    path.write_text(json.dumps([{
        "id": 7,
        "name": "bike",
        "privateKey": base64.b64encode(KEY).decode(),
        "additionalKeys": [],
    }]))

    monkeypatch.setattr(wizard, "askopenfilenames", lambda *_args, **_kwargs: (str(path),))
    return Path(path)


def ticks(app) -> list[str]:
    """Which rows show a tick, read back off the widget rather than out of the set."""
    return [row for row in app.choices.get_children() if app.choices.set(row, "tick") == wizard.TICKED]


class TestAddingAKeyFile:
    def test_keeps_what_was_already_ticked(self, app, key_file):
        """
        The bug: the list was rebuilt from scratch, so every tick came back empty.

        Nobody is told, and the "Export…" button stays enabled - so the next click writes a bundle
        holding one tag where the user had chosen three, and the screen that says what is being
        handed over has just quietly stopped saying it.
        """
        app._show([])
        app._set_tick("c0", True)
        app._set_tick("c1", True)

        app._add_key_file()

        assert ticks(app) == ["c0", "c1"]
        assert app.ticked == {"c0", "c1"}

    def test_the_new_row_is_not_ticked(self, app, key_file):
        # Same rule as everything else in this list: what leaves the account is ticked on purpose.
        app._show([])

        app._add_key_file()

        assert "k0" in app.choices.get_children()
        assert ticks(app) == []

    def test_keeps_the_note_about_records_that_could_not_be_exported(self, app, key_file):
        # It is the only place that number is ever shown. Rebuilding the list with no skipped
        # records to hand replaced it with the generic note, so adding a key file made the
        # explanation for a short list disappear.
        app._show([Skipped(beacon_id="C", reason="no key material")])
        assert "could not be exported" in app.note.cget("text")

        app._add_key_file()

        assert "could not be exported" in app.note.cget("text")

    def test_a_tag_the_user_declined_to_name_is_not_added(self, app, key_file, monkeypatch):
        monkeypatch.setattr(wizard, "_ask_string", lambda *_args, **_kwargs: "")
        app._show([])

        app._add_key_file()

        assert app.custom_tags == []
        assert app.choices.get_children() == ("c0", "c1")


class TestOpeningWithoutAnAppleAccount:
    """
    The window is usable before anything is signed into, and stays usable if that fails.

    It used to sign in the moment it appeared, which made an Apple account the price of opening
    the program: the sign-in ran unasked, and a failure closed the window - taking with it the
    "+ Add from key file…" button, which is the one thing that needs no Apple account at all.
    Somebody whose tags are all self-generated was shown an Apple error about tags that have
    nothing to do with Apple, and then nothing.

    So reading the account is a button now, and this is the wizard's half of the CLI's
    `--source none`.
    """

    def failing_to_read(self, monkeypatch, error) -> list:
        """Make the read fail, and collect whatever the user was told about it."""
        said = []

        def _fails(*_args, **_kwargs):
            raise error

        monkeypatch.setattr(wizard, "run_with_progress", _fails)
        monkeypatch.setattr(wizard.messagebox, "showerror", lambda _title, message, **_kw: said.append(message))
        return said

    def test_nothing_is_read_until_it_is_asked_for(self, app, monkeypatch):
        # The strongest form of it: the reader is replaced with something that fails the test if
        # it is ever called, and the window is built anyway.
        def _should_not_run(*_args, **_kwargs):
            raise AssertionError("the wizard read a source without being asked to")

        monkeypatch.setattr(wizard, "run_with_progress", _should_not_run)

        window = wizard.WizardApp()
        try:
            window.withdraw()
            assert window.choices.get_children() == ()
            assert str(window.confirm_button.cget("state")) == "disabled"
            assert str(window.read_button.cget("state")) == "normal"
        finally:
            window.destroy()

    def test_the_empty_list_says_what_the_two_buttons_do(self, app):
        app.candidates = []
        app._show([])

        note = app.note.cget("text")

        assert "key file" in note
        # Said here rather than discovered by trying it: it is the reason this screen exists.
        assert "needs no account" in note

    def test_a_key_file_alone_is_enough_to_export(self, app, key_file):
        app.candidates = []
        app._show([])

        app._add_key_file()

        assert [tag.name for tag in app.custom_tags] == ["the bike"]
        assert app.choices.get_children() == ("k0",)
        assert str(app.confirm_button.cget("state")) == "normal"

    def test_a_failed_sign_in_leaves_the_window_standing(self, app, key_file, monkeypatch):
        from exporter.icloud import ExportSourceError

        said = self.failing_to_read(monkeypatch, ExportSourceError("Signing in was cancelled."))
        app.candidates = []
        app._show([])
        app._add_key_file()

        app._load()

        assert app.winfo_exists()
        assert "Signing in was cancelled." in said[0]
        # And what they had already added is still there to export.
        assert app.choices.get_children() == ("k0",)
        assert str(app.read_button.cget("state")) == "normal"

    def test_closing_the_progress_window_stops_that_and_nothing_else(self, app, monkeypatch):
        # Cancelling a sign-in says stop signing in. It used to close the program.
        self.failing_to_read(monkeypatch, wizard.Cancelled())

        app._load()

        assert app.winfo_exists()

    def test_reading_the_account_is_offered_once(self, app, monkeypatch):
        # A second read would sign in again and rebuild the list under whatever is already ticked.
        from exporter.icloud import Fetched

        monkeypatch.setattr(
            wizard, "run_with_progress",
            lambda *_args, **_kwargs: Fetched(candidates=app.candidates, skipped=[]),
        )

        app._load()

        assert app.choices.get_children() == ("c0", "c1")
        assert str(app.read_button.cget("state")) == "disabled"


class TestTheListItself:
    def test_a_row_can_be_ticked_and_unticked(self, app):
        app._show([])

        app._set_tick("c0", True)
        assert app.ticked == {"c0"}

        app._set_tick("c0", False)
        assert app.ticked == set()
        assert ticks(app) == []

    def test_exporting_is_only_offered_once_there_is_something_to_export(self, app):
        app.candidates = []
        app._show([])
        assert str(app.confirm_button.cget("state")) == "disabled"

        app.candidates = [
            Candidate(
                beacon_id="A", name="cat", emoji=None, has_alignment=True,
                owned_beacon={}, naming_record={}, key_alignment_record=None,
            ),
        ]
        app._show([])
        assert str(app.confirm_button.cget("state")) == "normal"
