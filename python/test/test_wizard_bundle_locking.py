"""
The wizard locks every bundle it writes, and shows the code once.

**There is no way to ask it not to, and that is the behaviour under test.** It was a ticked
checkbox, which is one idle click from an unlocked bundle holding key material that cannot be
revoked - and that click has been made in the field, by somebody who sent their tags to a
stranger in an unlocked zip. The escape hatch lives on the CLI's `--no-password`, where finding
a flag and typing it is evidence of a decision.

**Asserted as the absence of a control, not only as a default.** A default is a value somebody
can flip back; this suite fails if the window grows a way to turn locking off at all. The value
moved twice before without a test noticing either time.

The code is the part with a permanent cost. It is not stored anywhere and cannot be recovered, so
a bundle written without the user being shown its code is a bundle nobody can ever open.
"""

from __future__ import annotations

from unittest import mock

import pytest

# Before anything that imports tkinter - see the note in test_save_logs_button.py. A skip inside a
# fixture is too late, because the module is imported during collection.
tk = pytest.importorskip("tkinter", reason="needs a Python built with Tk")

from exporter import wizard  # noqa: E402 - has to follow the importorskip above
from opentagviewer_export import ExportBundle  # noqa: E402


@pytest.fixture(scope="module")
def window():
    """One window for the module. Repeatedly building a Tk in one process is unstable on macOS."""
    try:
        app = wizard.WizardApp()
    except tk.TclError as e:  # pragma: no cover - depends on the machine, not the code
        pytest.skip(f"needs a display to build a window: {e}")

    app.withdraw()
    try:
        yield app
    finally:
        app.destroy()


@pytest.fixture
def bundle():
    return ExportBundle(entries={"OPENTAGVIEWER.yml": b"version: 0.0.2\n"}, exported_at_ms=0)


def write(window, bundle, path):
    """Run the write step and report what happened. There is no state to set: it always locks."""
    with mock.patch.object(wizard, "write_zip") as write_zip, \
         mock.patch.object(wizard, "_show_the_code") as shown, \
         mock.patch.object(wizard.messagebox, "showinfo") as info, \
         mock.patch.object(wizard.messagebox, "showerror") as error:
        closed = window._write_it(bundle, str(path), 3)

    return write_zip, shown, info, error, closed


class TestThereIsNoWayToTurnItOff:
    """
    The control is gone, not merely defaulted on - see the module docstring for what that cost.
    """

    def test_the_window_has_no_locking_switch(self, window):
        assert not hasattr(window, "lock_bundle"), (
            "the window grew a way to turn locking off again; the CLI's --no-password is where"
            " that belongs, because typing a flag is a decision and clicking a box is not"
        )

    def test_no_checkbox_offers_it_either(self, window):
        # The attribute could be renamed and the checkbox kept, which would pass the test above
        # while putting the click back on screen. So the widgets are searched as well.
        labels = []

        def walk(widget):
            for child in widget.winfo_children():
                try:
                    labels.append(str(child.cget("text")).lower())
                except tk.TclError:
                    pass
                walk(child)

        walk(window)

        assert not any("lock" in label for label in labels), (
            f"something on the window still offers locking as a choice: {labels}"
        )

    def test_a_bundle_is_written_with_a_code(self, window, bundle, tmp_path):
        write_zip, _shown, _info, _error, _closed = write(
            window, bundle, tmp_path / "x.zip")

        passcode = write_zip.call_args.kwargs["password"]
        assert passcode, "the bundle was written unlocked"
        assert len(passcode) == 12

    def test_the_code_uses_the_alphabet_the_importer_expects(self, window, bundle, tmp_path):
        # Crockford's base32, minus I, L, O and U. The app folds the confusable letters back on
        # input; a code containing one would still work, but it would defeat the point of the
        # alphabet - which is that this gets read off a screen and typed somewhere else.
        write_zip, *_ = write(window, bundle, tmp_path / "x.zip")

        assert set(write_zip.call_args.kwargs["password"]) <= set(
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ")


class TestShowingTheCode:
    """
    **The one moment it exists in a readable form.** Nothing keeps it - not the bundle, not the
    log, not the program - so a bundle written without showing its code is one nobody can open.
    """

    def test_the_code_is_shown_and_it_is_the_one_that_was_used(self, window, bundle, tmp_path):
        write_zip, shown, _info, _error, _closed = write(
            window, bundle, tmp_path / "x.zip")

        shown.assert_called_once()
        assert shown.call_args.args[3] == write_zip.call_args.kwargs["password"]

    def test_the_code_is_always_shown_because_there_is_always_one(self, window, bundle, tmp_path):
        # There used to be an "Exported, and this bundle is not locked" path here. It is gone
        # with the checkbox: every write from this window has a code, so every write shows one.
        # If a no-code path ever comes back, this fails rather than silently writing a bundle
        # whose only warning nobody wrote.
        write_zip, shown, info, _error, _closed = write(window, bundle, tmp_path / "x.zip")

        assert write_zip.call_args.kwargs["password"] is not None
        shown.assert_called_once()
        info.assert_not_called()


class TestWhenItCannotBeWritten:
    """
    Failing with the window still up, because the alternative is reading the account again.
    """

    def test_a_missing_pyzipper_is_said_plainly(self, window, bundle, tmp_path):

        with mock.patch.object(wizard, "write_zip",
                               side_effect=RuntimeError("pyzipper is not installed")), \
             mock.patch.object(wizard.messagebox, "showerror") as error:
            closed = window._write_it(bundle, str(tmp_path / "x.zip"), 1)

        assert closed is False, "the window closed on a failure, losing the selection"
        assert "pyzipper" in error.call_args.args[1]

    def test_a_disk_that_will_not_take_it_keeps_the_window(self, window, bundle, tmp_path):

        with mock.patch.object(wizard, "write_zip", side_effect=OSError("No space left")), \
             mock.patch.object(wizard.messagebox, "showerror") as error:
            closed = window._write_it(bundle, str(tmp_path / "x.zip"), 1)

        assert closed is False
        assert "No space left" in error.call_args.args[1]

    def test_a_successful_write_does_close_it(self, window, bundle, tmp_path):
        *_rest, closed = write(window, bundle, tmp_path / "x.zip")

        assert closed is True


class TestTheDialogItself:
    """
    **The window, not the call to it.** Every test above mocks ``_show_the_code`` out, so they
    prove the wiring and nothing about what a person sees. That is the same gap as asserting a
    share sheet opened without looking at what it was handed: a dialog rendering the wrong string,
    or a Copy button that copies nothing, passes all of them.

    It matters more here than most places, because this is the only moment the code exists in a
    readable form. Nothing stores it. A dialog that fails to show it produces a bundle that can
    never be opened, and the failure is silent at exactly the moment the user stops paying
    attention.
    """

    CODE = "4RTZ9KMXP2W7"

    def build(self, window, tmp_path):
        dialog = wizard._build_the_code_window(
            window, str(tmp_path / "export.zip"), 3, self.CODE)
        dialog.withdraw()
        return dialog

    def text_in(self, widget) -> str:
        """Everything the window says, however it is nested."""
        found = []
        for child in widget.winfo_children():
            try:
                found.append(str(child.cget("text")))
            except tk.TclError:
                pass
            try:
                found.append(str(child.get()))
            except (tk.TclError, AttributeError, TypeError):
                pass
            found.append(self.text_in(child))
        return " ".join(found)

    def test_thecodeIsOnScreen_grouped_for_reading(self, window, tmp_path):
        dialog = self.build(window, tmp_path)
        try:
            # Grouped, because it is read off a screen and typed into three boxes on a phone.
            assert "4RTZ-9KMX-P2W7" in self.text_in(dialog)
        finally:
            dialog.destroy()

    def test_itsaysTheCodeCannotBeRecovered(self, window, tmp_path):
        dialog = self.build(window, tmp_path)
        try:
            said = self.text_in(dialog)
            assert "cannot be recovered" in said
            # And the half people get wrong: the code must not travel with the file.
            assert "separately" in said
        finally:
            dialog.destroy()

    def test_copyPutsTheCodeOnTheClipboard(self, window, tmp_path):
        dialog = self.build(window, tmp_path)
        try:
            dialog.clipboard_clear()
            dialog.clipboard_append("something else")

            self.press(dialog, "Copy the code")

            assert dialog.clipboard_get() == "4RTZ-9KMX-P2W7"
        except tk.TclError as e:  # pragma: no cover - some CI hosts have no clipboard
            pytest.skip(f"no usable clipboard here: {e}")
        finally:
            dialog.destroy()

    def test_thepathIsShownSoTheyKnowWhichFileItOpens(self, window, tmp_path):
        dialog = self.build(window, tmp_path)
        try:
            assert "export.zip" in self.text_in(dialog)
        finally:
            dialog.destroy()

    def press(self, widget, label) -> None:
        """Find a button by its label and invoke it."""
        for child in widget.winfo_children():
            try:
                if str(child.cget("text")) == label:
                    child.invoke()
                    return
            except tk.TclError:
                pass
            self.press(child, label)
