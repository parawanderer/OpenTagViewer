"""
The "Save logs…" button, driven through the actual window.

**Separate from `test_redact.py` on purpose.** That file proves the patterns work on text. This one
proves the button is wired to them, which is the part that breaks silently: a window that writes an
unredacted file looks exactly like one that writes a redacted file.

Every identifier below is randomly generated and belongs to nobody. That is deliberate - the first
draft of this file used values lifted from a real user's bug report, which is precisely what the
feature under test exists to prevent, and would have committed them to the history permanently.
"""

from __future__ import annotations

import tkinter as tk
from unittest import mock

import pytest

from exporter import wizard

# Random, and shaped like the real thing: a peer hash is base64 of a SHA-256, a serial is ten
# uppercase alphanumerics, a home directory carries somebody's login name.
PEER = "7Yt2Rg90+i3fSELB8nD25LePIq+Op/V3P18oT8t7qPI="
SERIAL = "BWBPC2K1ZR"
USER = "kmvaesp"

# One of everything the redactor should find, in the shapes FindMy.py actually writes them - so
# this fails if the wiring is right and the patterns have rotted.
LOG = f"""\
2026-08-16 12:09:48 WARNING  exporter.privacy: NOT safe to publish
2026-08-16 12:09:49 INFO     findmy.reports.account: Attempting authentication for user someone@example.com
2026-08-16 12:09:50 INFO     findmy.keychain.recovery: Beginning escrow recovery for Sam's iPhone
2026-08-16 12:09:51 WARNING  findmy.keychain.peers: Peer SHA256:{PEER} carries no signing key
2026-08-16 12:09:52 INFO     findmy.keychain.items: Service key item: acct='someone@example.com', labl='Home WiFi'
  1. Sam's iPhone, iPhone15,2, serial {SERIAL}, escrowed 2024-03-11
  File "/home/{USER}/OpenTagViewer/python/exporter/cli.py", line 893, in run
2026-08-16 12:09:53 INFO     findmy.keychain.session: The Manatee view holds 141 elliptic-curve key(s)
"""

SECRETS = [
    "someone@example.com",
    "Sam's iPhone",
    PEER[:15],
    SERIAL,
    f"/home/{USER}/",
    "Home WiFi",
]


@pytest.fixture(scope="module")
def window():
    """
    One window for the whole module.

    **Built once rather than per test.** Repeatedly creating and destroying a `Tk` in one process
    is unstable on macOS - the first attempt at this crashed the interpreter during collection,
    before a single assertion ran. Nothing here mutates window state, so one is enough.
    """
    try:
        app = wizard.WizardApp()
    except tk.TclError as e:  # pragma: no cover - depends on the machine, not on the code
        pytest.skip(f"needs a display to build a window: {e}")

    app.withdraw()
    try:
        yield app
    finally:
        app.destroy()


@pytest.fixture
def log(tmp_path):
    """A log file this test owns."""
    path = tmp_path / "exporter.log"
    path.write_text(LOG, encoding="utf-8")

    return path


def click_save_logs(window, log, saving_to):
    """Press the button, answering its save dialog with this path. None cancels the dialog."""
    with mock.patch.object(wizard, "log_file", return_value=log), \
         mock.patch.object(wizard, "asksaveasfilename",
                           return_value=str(saving_to) if saving_to else ""), \
         mock.patch.object(wizard.messagebox, "showinfo") as info, \
         mock.patch.object(wizard.messagebox, "showerror") as error:
        window._save_logs()

    return info, error


def button_labels(widget) -> list[str]:
    found: list[str] = []

    for child in widget.winfo_children():
        try:
            label = child.cget("text")
        except tk.TclError:
            label = ""
        if label:
            found.append(str(label))
        found.extend(button_labels(child))

    return found


class TestTheButtonExists:
    def test_it_is_in_the_window(self, window):
        assert "Save logs…" in button_labels(window)

    def test_cancel_is_not(self, window):
        # It did exactly what the window's own close button does, and this took its place.
        assert "Cancel" not in button_labels(window)


class TestWhatItWrites:
    def test_no_identifier_survives_into_the_saved_file(self, window, log, tmp_path):
        """
        The whole reason the button exists.

        Asserted per identifier rather than by comparing whole files, so a failure names which one
        leaked instead of only saying that something did.
        """
        target = tmp_path / "out.txt"

        click_save_logs(window, log, target)

        saved = target.read_text(encoding="utf-8")
        for secret in SECRETS:
            assert secret not in saved, f"{secret!r} reached a file a user is about to post"

    def test_the_log_is_still_worth_reading(self, window, log, tmp_path):
        # Redaction that eats the diagnosis just produces a second request for the original.
        target = tmp_path / "out.txt"

        click_save_logs(window, log, target)

        saved = target.read_text(encoding="utf-8")
        assert "The Manatee view holds 141 elliptic-curve key(s)" in saved
        assert "carries no signing key" in saved
        assert "iPhone15,2" in saved, "a model is not personal and is often the point of the line"
        assert "line 893, in run" in saved

    def test_the_original_on_disk_is_left_alone(self, window, log, tmp_path):
        # The copy the user keeps stays complete; only what leaves the machine is redacted.
        click_save_logs(window, log, tmp_path / "out.txt")

        assert log.read_text(encoding="utf-8") == LOG

    def test_it_says_what_it_took_out(self, window, log, tmp_path):
        info, _ = click_save_logs(window, log, tmp_path / "out.txt")

        body = info.call_args[0][1]
        assert "Replaced" in body
        assert "serial" in body

    def test_it_does_not_promise_the_file_is_clean(self, window, log, tmp_path):
        # The one claim that must never be made. Pattern-matching cannot know what Apple put in a
        # field, and somebody told "this is safe now" will not read it.
        info, _ = click_save_logs(window, log, tmp_path / "out.txt")

        body = info.call_args[0][1].lower()
        assert "read it before you post it" in body
        assert "cannot promise" in body

    def test_it_distinguishes_the_log_from_the_tags(self, window, log, tmp_path):
        info, _ = click_save_logs(window, log, tmp_path / "out.txt")

        assert "not your tags" in info.call_args[0][1]


class TestWhenThereIsNothingToSave:
    def test_a_missing_log_does_not_open_a_save_dialog(self, window, log):
        log.unlink()

        with mock.patch.object(wizard, "log_file", return_value=log), \
             mock.patch.object(wizard, "asksaveasfilename") as save, \
             mock.patch.object(wizard.messagebox, "showinfo") as info:
            window._save_logs()

        assert not save.called, "asking where to save nothing wastes the one action they took"
        assert "nothing in the log yet" in info.call_args[0][1].lower()

    def test_an_empty_log_is_treated_the_same(self, window, log):
        log.write_text("", encoding="utf-8")

        with mock.patch.object(wizard, "log_file", return_value=log), \
             mock.patch.object(wizard, "asksaveasfilename") as save, \
             mock.patch.object(wizard.messagebox, "showinfo"):
            window._save_logs()

        assert not save.called


class TestWhenTheUserBacksOut:
    def test_cancelling_the_dialog_writes_nothing(self, window, log, tmp_path):
        before = set(tmp_path.iterdir())

        info, error = click_save_logs(window, log, None)

        assert set(tmp_path.iterdir()) == before
        assert not info.called and not error.called
