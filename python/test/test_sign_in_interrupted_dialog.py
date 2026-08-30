"""
What the wizard says when Apple takes a verification code and then fails anyway.

**Issue #168, and specifically the heading over it.** The failure itself is Apple's - a 503 from
Grand Slam half a second after the code was accepted - and the exporter's part was showing the
wrong thing about it. Twice, as it turned out:

- it fell through to the catch-all handler, which names an exception type and asks for a bug
  report, so the one person who could do nothing about it was invited to file it
- and once that was fixed, the generic message dialog titled it "Could not read your accessories",
  which is not what happened. Nothing was read; signing in never finished.

A dialog that misdescribes what happened is worse than a vague one, because the reader corrects
for it and then trusts the rest of it less - so the title is asserted, not just the body.
"""

from __future__ import annotations

from unittest import mock

import pytest

# Before anything that imports tkinter - a skip inside a fixture is too late, because the module
# is imported during collection.
tk = pytest.importorskip("tkinter", reason="needs a Python built with Tk")

from findmy.errors import UnhandledProtocolError  # noqa: E402

from exporter import icloud, wizard  # noqa: E402
from exporter.icloud import ExportSourceError  # noqa: E402


@pytest.fixture(scope="module")
def window():
    """
    One window for the whole module.

    Built once rather than per test: repeatedly creating and destroying a `Tk` in one process is
    unstable on macOS. Nothing here mutates window state.
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


def load_failing_with(window, error):
    """Press Read, with the sign-in raising this, and return what was shown."""
    with mock.patch.object(wizard, "run_with_progress", side_effect=error), \
         mock.patch.object(wizard.messagebox, "showerror") as shown:
        window._load()

    assert shown.called, "the failure was swallowed"
    return shown.call_args.args


THE_REAL_ONE = icloud._apple_failed_after_taking_the_code(
    UnhandledProtocolError("Error response for GSA request: 503"))


class TestTheHeadingSaysWhatFailed:
    def test_it_says_signing_in_rather_than_reading_accessories(self, window):
        title, _body = load_failing_with(window, THE_REAL_ONE)

        assert "signing in" in title.lower()
        assert "accessories" not in title.lower(), (
            "nothing was read - signing in never finished")

    def test_everything_else_keeps_the_heading_it_had(self, window):
        """
        The handler above must not have widened.

        It is placed before the general one and catches a subclass, so a mistake here is easy to
        make and invisible: every source failure would quietly start claiming to be a sign-in.
        """
        title, _body = load_failing_with(
            window, ExportSourceError("The bundle could not be read."))

        assert title == "Could not read your accessories"


class TestTheBodySaysWhoseFaultItIsAndWhatToDo:
    def test_it_does_not_ask_for_a_bug_report(self, window):
        """
        The complaint, asserted.

        This is what the catch-all handler adds, and reaching it is what produced issue #168.
        """
        _title, body = load_failing_with(window, THE_REAL_ONE)

        assert "report" not in body.lower()
        assert "github.com" not in body.lower()

    def test_it_says_the_fault_is_apples(self, window):
        _title, body = load_failing_with(window, THE_REAL_ONE)

        assert "Apple's side" in body
        assert "rather than anything you did" in body

    def test_it_says_it_already_waited(self, window):
        """
        Otherwise the advice reads as "just try again", which is what they have been doing.

        Somebody who has watched the progress window count down for three minutes needs to be told
        that is what it was doing, or this is asking them to repeat work the program already did.
        """
        _title, body = load_failing_with(window, THE_REAL_ONE)

        assert "chances to settle" in body
        assert str(icloud.SPENT_CODE_WAITS[0]) in body

    def test_it_warns_the_password_may_be_refused_once(self, window):
        """
        Because it was, and it read as a second unrelated fault.

        The reproduction went: 503 after the code, then a *password* rejection on the next
        attempt, then success. Somebody not told to expect the middle one concludes they have
        mistyped a password they have typed correctly for years.
        """
        _title, body = load_failing_with(window, THE_REAL_ONE)

        assert "password" in body

    def test_it_keeps_the_status_code(self, window):
        """503 is the whole diagnosis if this ever turns out to be more than weather."""
        _title, body = load_failing_with(window, THE_REAL_ONE)

        assert "503" in body

    def test_it_says_nothing_was_sent(self, window):
        """The first question anybody asks after a failed sign-in to their own Apple account."""
        _title, body = load_failing_with(window, THE_REAL_ONE)

        assert "nothing was sent" in body.lower()
