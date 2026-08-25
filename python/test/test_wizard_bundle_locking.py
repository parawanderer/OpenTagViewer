"""
The wizard can lock the bundles it writes, and shows the code once.

**The default is off, and it is off for a reason that will expire.** A locked bundle can only be
opened by app 1.1.0 or newer; anything older fails with a message about the zip rather than about
a code. The person who meets that failure is the recipient, who chose neither the exporter nor its
version - so until 1.1.0 is *released*, not merely built, locking by default would break exports
for the one party who can do nothing about it.

**These tests assert the default in both directions on purpose.** Nothing caught the previous flip
in either direction, so the value has changed twice with no test noticing. When app 1.1.0 ships and
the default goes back on, `test_the_checkbox_starts_unticked` is the test that should fail and be
inverted - deliberately, in the same change, rather than discovered later.

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


def write(window, bundle, path, *, locked: bool):
    """Run the write step with the checkbox in a known state, and report what happened."""
    window.lock_bundle.set(locked)

    with mock.patch.object(wizard, "write_zip") as write_zip, \
         mock.patch.object(wizard, "_show_the_code") as shown, \
         mock.patch.object(wizard.messagebox, "showinfo") as info, \
         mock.patch.object(wizard.messagebox, "showerror") as error:
        closed = window._write_it(bundle, str(path), 3)

    return write_zip, shown, info, error, closed


class TestTheDefault:
    """
    Off until an app that can open one is released - see the module docstring.

    A bundle holds key material that cannot be revoked and travels through other people's
    infrastructure, so on is where this belongs eventually. It is not there yet.
    """

    def test_the_checkbox_starts_unticked(self, window):
        assert window.lock_bundle.get() is False, (
            "locking by default writes bundles that no released app can open. Flip this only in"
            " the change that raises the minimum app version - see AGENTS.md rule 9"
        )

    def test_a_bundle_is_written_with_a_code(self, window, bundle, tmp_path):
        write_zip, _shown, _info, _error, _closed = write(
            window, bundle, tmp_path / "x.zip", locked=True)

        passcode = write_zip.call_args.kwargs["password"]
        assert passcode, "the bundle was written unlocked while the box was ticked"
        assert len(passcode) == 12

    def test_the_code_uses_the_alphabet_the_importer_expects(self, window, bundle, tmp_path):
        # Crockford's base32, minus I, L, O and U. The app folds the confusable letters back on
        # input; a code containing one would still work, but it would defeat the point of the
        # alphabet - which is that this gets read off a screen and typed somewhere else.
        write_zip, *_ = write(window, bundle, tmp_path / "x.zip", locked=True)

        assert set(write_zip.call_args.kwargs["password"]) <= set(
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ")


class TestShowingTheCode:
    """
    **The one moment it exists in a readable form.** Nothing keeps it - not the bundle, not the
    log, not the program - so a bundle written without showing its code is one nobody can open.
    """

    def test_the_code_is_shown_and_it_is_the_one_that_was_used(self, window, bundle, tmp_path):
        write_zip, shown, _info, _error, _closed = write(
            window, bundle, tmp_path / "x.zip", locked=True)

        shown.assert_called_once()
        assert shown.call_args.args[3] == write_zip.call_args.kwargs["password"]

    def test_an_unlocked_bundle_says_so_instead(self, window, bundle, tmp_path):
        write_zip, shown, info, _error, _closed = write(
            window, bundle, tmp_path / "x.zip", locked=False)

        assert write_zip.call_args.kwargs["password"] is None
        shown.assert_not_called()
        assert "not locked" in info.call_args.args[1]


class TestWhenItCannotBeWritten:
    """
    Failing with the window still up, because the alternative is reading the account again.
    """

    def test_a_missing_pyzipper_is_said_plainly(self, window, bundle, tmp_path):
        window.lock_bundle.set(True)

        with mock.patch.object(wizard, "write_zip",
                               side_effect=RuntimeError("pyzipper is not installed")), \
             mock.patch.object(wizard.messagebox, "showerror") as error:
            closed = window._write_it(bundle, str(tmp_path / "x.zip"), 1)

        assert closed is False, "the window closed on a failure, losing the selection"
        assert "pyzipper" in error.call_args.args[1]

    def test_a_disk_that_will_not_take_it_keeps_the_window(self, window, bundle, tmp_path):
        window.lock_bundle.set(True)

        with mock.patch.object(wizard, "write_zip", side_effect=OSError("No space left")), \
             mock.patch.object(wizard.messagebox, "showerror") as error:
            closed = window._write_it(bundle, str(tmp_path / "x.zip"), 1)

        assert closed is False
        assert "No space left" in error.call_args.args[1]

    def test_a_successful_write_does_close_it(self, window, bundle, tmp_path):
        *_rest, closed = write(window, bundle, tmp_path / "x.zip", locked=True)

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
