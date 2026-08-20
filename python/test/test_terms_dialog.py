"""
Apple's terms of service, in the window.

**Two halves, tested apart.** `_accept_pending_terms` is the flow - what gets fetched, what gets
sent, and what happens when the answer is no; it runs with the dialog stubbed, so it needs no
display. `_show_terms` is the window, and is driven for real, because the failure that matters
there is one nothing throws on: a dialog that shows the wrong document, or that treats a closed
window as agreement.

The second kind is why this exists at all. Sending acceptance of a contract the user rejected
would be silent, permanent and done in their name.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any
from unittest import mock

import pytest

# Before anything that imports tkinter, `exporter.wizard` included - see test_save_logs_button.
tk = pytest.importorskip("tkinter", reason="needs a Python built with Tk")

from tkinter import ttk  # noqa: E402 - same reason

from exporter import wizard  # noqa: E402 - has to follow the importorskip above
from exporter.icloud import ExportSourceError  # noqa: E402
from findmy import LoginState, MobileMeDelegateError, TermsError  # noqa: E402

TERMS_HTML = """
<html><head><style>p { color: red }</style></head>
<body>
  <div class="terms">
    <h1>iCloud Terms and Conditions</h1>
    <p>Welcome to <b>iCloud</b>. By using iCloud you agree to these terms.</p>
    <h2>1. Your Account</h2>
    <p>You are responsible for maintaining the confidentiality of your account.</p>
    <ul>
      <li>You must be over the age of majority.</li>
    </ul>
    <script>track();</script>
  </div>
</body></html>
"""

MEDIA_HTML = "<html><body><h1>Media Services</h1><p>A second document.</p></body></html>"

DELEGATE_ERROR = MobileMeDelegateError(localized_error="Terms have not been accepted")


@dataclass(frozen=True)
class FakeTerms:
    page_id: str
    agree_url: str
    html: str


ICLOUD_TERMS = FakeTerms("iCloud", "https://example.invalid/agree", TERMS_HTML)
MEDIA_TERMS = FakeTerms("Media", "https://example.invalid/agree-media", MEDIA_HTML)


class FakeAccount:
    """
    An account with terms pending, recording only what actually reached Apple.

    `accepted` is the assertion that matters throughout: it is the list of documents this
    program told Apple the user agreed to.
    """

    def __init__(self, documents=(ICLOUD_TERMS,), *, final_state=LoginState.LOGGED_IN):
        self.documents = list(documents)
        self.accepted: list[FakeTerms] = []
        self.completed = 0
        self.final_state = final_state
        self.fetch_raises: Exception | None = None
        self.accept_raises: Exception | None = None

    async def fetch_terms(self):
        if self.fetch_raises:
            raise self.fetch_raises
        return self.documents

    async def accept_terms(self, document):
        if self.accept_raises:
            raise self.accept_raises
        self.accepted.append(document)

    async def complete_login(self):
        self.completed += 1
        return self.final_state


class DirectAsker(wizard.Asker):
    """
    An `Asker` that answers on the calling thread, since these tests have no worker.

    A real one hands the question to the main thread and blocks until an event loop draws it,
    which needs the worker thread that is not here. `ask` is the only method the flow uses, so
    nothing the base class sets up is wanted - hence no `super().__init__`.
    """

    def __init__(self):
        pass

    def ask(self, dialog):
        return dialog()


def run_terms(account, answers, monkeypatch, parent: Any = None):
    """Drive the flow with canned answers to each document, in order."""
    given = list(answers)
    shown: list[FakeTerms] = []

    def _fake_dialog(_parent, document, _index, _total):
        shown.append(document)
        return given.pop(0)

    monkeypatch.setattr(wizard, "_show_terms", _fake_dialog)

    asyncio.run(wizard._accept_pending_terms(parent, account, DirectAsker(), DELEGATE_ERROR))

    return shown


class TestAgreeing:
    def test_accepting_sends_agreement_for_that_document(self, monkeypatch):
        account = FakeAccount()

        run_terms(account, [True], monkeypatch=monkeypatch)

        assert account.accepted == [ICLOUD_TERMS]

    def test_the_sign_in_is_finished_afterwards(self, monkeypatch):
        # Without this the account is left at AUTHENTICATED - readable, and unusable for
        # everything that comes after. It is the step `login` would have run itself.
        account = FakeAccount()

        run_terms(account, [True], monkeypatch=monkeypatch)

        assert account.completed == 1

    def test_every_document_is_shown_and_accepted(self, monkeypatch):
        account = FakeAccount([ICLOUD_TERMS, MEDIA_TERMS])

        shown = run_terms(account, [True, True], monkeypatch=monkeypatch)

        assert shown == [ICLOUD_TERMS, MEDIA_TERMS]
        assert account.accepted == [ICLOUD_TERMS, MEDIA_TERMS]

    def test_a_sign_in_that_does_not_complete_is_reported(self, monkeypatch):
        account = FakeAccount(final_state=LoginState.AUTHENTICATED)

        with pytest.raises(ExportSourceError, match="ended at"):
            run_terms(account, [True], monkeypatch=monkeypatch)


class TestRefusing:
    def test_rejecting_sends_nothing(self, monkeypatch):
        # The whole point. Acceptance is permanent and made in the user's name.
        account = FakeAccount()

        with pytest.raises(wizard.TermsDeclined):
            run_terms(account, [False], monkeypatch=monkeypatch)

        assert account.accepted == []

    def test_rejecting_does_not_finish_the_sign_in(self, monkeypatch):
        account = FakeAccount()

        with pytest.raises(wizard.TermsDeclined):
            run_terms(account, [False], monkeypatch=monkeypatch)

        assert account.completed == 0

    def test_rejecting_the_first_document_does_not_show_the_second(self, monkeypatch):
        account = FakeAccount([ICLOUD_TERMS, MEDIA_TERMS])

        with pytest.raises(wizard.TermsDeclined):
            run_terms(account, [False, True], monkeypatch=monkeypatch)

        assert account.accepted == []

    def test_accepting_the_first_and_refusing_the_second_leaves_the_first_accepted(self, monkeypatch):
        # Honest about what happened rather than tidy: the first agreement was sent the moment it
        # was given, and cannot be taken back by refusing a later one.
        account = FakeAccount([ICLOUD_TERMS, MEDIA_TERMS])

        with pytest.raises(wizard.TermsDeclined):
            run_terms(account, [True, False], monkeypatch=monkeypatch)

        assert account.accepted == [ICLOUD_TERMS]
        assert account.completed == 0

    def test_it_names_the_document_that_was_refused(self, monkeypatch):
        account = FakeAccount([ICLOUD_TERMS])

        with pytest.raises(wizard.TermsDeclined, match="iCloud"):
            run_terms(account, [False], monkeypatch=monkeypatch)


class TestWhenItIsNotTermsAtAll:
    """
    Which error means "terms pending" is not established, so the flow looks rather than assumes.

    A delegate failure with nothing to accept has to report what Apple said. Reporting "no terms
    found" would describe the check rather than the problem.
    """

    def test_no_pending_terms_reports_what_apple_said(self, monkeypatch):
        account = FakeAccount([])

        with pytest.raises(ExportSourceError, match="Terms have not been accepted"):
            run_terms(account, [], monkeypatch=monkeypatch)

    def test_no_pending_terms_is_not_a_declined_terms_close(self, monkeypatch):
        # It must not close the window: nothing was refused, and trying again may well work.
        account = FakeAccount([])

        with pytest.raises(ExportSourceError):
            run_terms(account, [], monkeypatch=monkeypatch)

    def test_a_failed_fetch_carries_both_messages(self, monkeypatch):
        account = FakeAccount()
        account.fetch_raises = TermsError("no terms in the response")

        with pytest.raises(ExportSourceError) as raised:
            run_terms(account, [], monkeypatch=monkeypatch)

        assert "Terms have not been accepted" in str(raised.value)
        assert "no terms in the response" in str(raised.value)

    def test_a_rejected_acceptance_is_reported_rather_than_ignored(self, monkeypatch):
        # Apple refusing to record the agreement is not the same as it being recorded, and the
        # sign-in must not carry on as though it were.
        account = FakeAccount()
        account.accept_raises = TermsError("status 500")

        with pytest.raises(ExportSourceError, match="did not record agreement"):
            run_terms(account, [True], monkeypatch=monkeypatch)

        assert account.completed == 0


# ------------------------------------------------------------------------------------------------
# The window itself
# ------------------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def root():
    """
    One root for the whole module - see the same fixture in test_save_logs_button for why.

    A bare `Tk` rather than a `WizardApp`: the dialog only uses its parent to own the window, so
    building the whole wizard around it would be testing the wizard.
    """
    try:
        app = tk.Tk()
    except tk.TclError as e:  # pragma: no cover - depends on the machine, not on the code
        pytest.skip(f"needs a display to build a window: {e}")

    app.withdraw()
    try:
        yield app
    finally:
        app.destroy()


@pytest.fixture
def dialog(root):
    """The real terms window, built but not yet modal, with the answer it writes into."""
    window, accepted = wizard._build_terms_window(root, ICLOUD_TERMS, 1, 1)
    try:
        yield window, accepted
    finally:
        if window.winfo_exists():
            window.destroy()


def descendants(widget):
    yield widget
    for child in widget.winfo_children():
        yield from descendants(child)


def terms_box(window):
    for widget in descendants(window):
        if isinstance(widget, tk.Text):
            return widget
    raise AssertionError("the dialog has nowhere to show the terms")


def shown_text(window) -> str:
    return terms_box(window).get("1.0", "end")


def blurb(window) -> str:
    return " ".join(
        str(widget.cget("text"))
        for widget in descendants(window)
        if isinstance(widget, ttk.Label)
    )


def still_open(app) -> bool:
    """
    Whether the application is still there.

    Destroying a *root* tears down its Tcl interpreter, so `winfo_exists` does not come back
    False - it raises, because there is no longer an interpreter to ask. Anything checking that
    the exporter closed has to treat that as the answer rather than as an error.
    """
    try:
        return bool(app.winfo_exists())
    except tk.TclError:
        return False


def press(window, label) -> None:
    for widget in descendants(window):
        if isinstance(widget, ttk.Button) and str(widget.cget("text")) == label:
            widget.invoke()
            return

    raise AssertionError(f"the dialog has no {label!r} button")


class TestTheDialogsAnswer:
    def test_accept_agrees(self, dialog):
        window, accepted = dialog

        press(window, "Accept")

        assert accepted["value"] is True

    def test_reject_does_not(self, dialog):
        window, accepted = dialog

        press(window, "Reject")

        assert accepted["value"] is False

    def test_both_buttons_close_the_window(self, dialog):
        # A dialog still on screen after its answer was given reads as the click not registering,
        # and the next click lands on whatever replaced it.
        window, _ = dialog

        press(window, "Reject")

        assert not window.winfo_exists()

    def test_an_unanswered_dialog_has_not_agreed(self, dialog):
        # What the window's own close button leaves behind. `_show_terms` returns this value, so
        # closing a contract unanswered has to read as no.
        _, accepted = dialog

        assert accepted["value"] is False

    def test_escape_can_dismiss_it(self, dialog):
        window, _ = dialog

        assert window.bind("<Escape>"), "no way out of this dialog from the keyboard"

    def test_nothing_is_bound_to_return(self, dialog):
        """
        Every other dialog here submits on Return. This one must not: its answer is agreement to a
        contract, and a stray keypress is not agreement.

        **Asserted on the binding rather than by pressing the key**, which is not a shortcut. A
        Toplevel whose root is withdrawn is not viewable and so holds focus from nobody, and
        `event_generate` on an unfocused window is dropped without complaint - so a test that
        presses Return and checks nothing happened passes just as well when the key was never
        delivered. It did, here, until the same call started failing a run later.
        """
        window, _ = dialog

        assert not window.bind("<Return>")
        assert not window.bind("<KP_Enter>")


class TestWhatIsOnScreen:
    def test_the_document_is_shown(self, dialog):
        window, _ = dialog

        assert "By using iCloud you agree to these terms." in shown_text(window)
        assert "You are responsible for maintaining the confidentiality" in shown_text(window)

    def test_list_items_survive(self, dialog):
        window, _ = dialog

        assert "You must be over the age of majority." in shown_text(window)

    def test_no_markup_reaches_the_reader(self, dialog):
        window, _ = dialog
        text = shown_text(window)

        assert "<p>" not in text
        assert "<b>" not in text
        # A contract with someone's analytics in the middle of it reads as a forgery.
        assert "track();" not in text
        assert "color: red" not in text

    def test_the_terms_cannot_be_edited(self, dialog):
        # A Text is editable by default, and a contract you can type into is not the document
        # that was fetched.
        window, _ = dialog

        assert str(terms_box(window).cget("state")) == "disabled"

    def test_it_says_apple_is_waiting_on_this(self, dialog):
        window, _ = dialog

        assert "will not finish signing you in" in blurb(window)
        assert "Nothing has been sent yet" in blurb(window)

    def test_one_document_is_not_counted(self, dialog):
        window, _ = dialog

        assert "(1 of 1)" not in blurb(window)

    def test_several_documents_are_counted(self, root):
        # Otherwise accepting the first looks like the whole thing, and a second contract appears
        # from nowhere.
        window, _ = wizard._build_terms_window(root, MEDIA_TERMS, 2, 2)

        try:
            assert "(2 of 2)" in blurb(window)
            assert "A second document." in shown_text(window)
            assert "By using iCloud" not in shown_text(window)
        finally:
            window.destroy()

    def test_the_title_names_the_document(self, root):
        window, _ = wizard._build_terms_window(root, MEDIA_TERMS, 2, 2)

        try:
            assert "Media" in window.title()
        finally:
            window.destroy()


class TestRejectingClosesTheExporter:
    """
    The one failure this window does not offer to retry.

    Everything else that goes wrong during a sign-in leaves the button there, because trying
    again might work. Refusing a contract is not like that: the same document is waiting on the
    next attempt, so offering a retry would be pretending the answer might change by itself.
    """

    def test_it_says_nothing_was_sent_and_then_closes(self, monkeypatch):
        app = wizard.WizardApp()
        app.withdraw()

        def _declined(*_args, **_kwargs):
            raise wizard.TermsDeclined("iCloud")

        monkeypatch.setattr(wizard, "run_with_progress", _declined)

        try:
            with mock.patch.object(wizard.messagebox, "showinfo") as info, \
                 mock.patch.object(wizard.messagebox, "showerror") as error:
                app._load()

            # Not the generic handler, which asks for a bug report with a log attached. Somebody
            # who read a contract and declined it has not hit a bug.
            assert not error.called, "declining terms is not something to report"

            body = info.call_args[0][1]
            assert "Nothing was sent" in body
            assert "unchanged" in body
            assert "iCloud" in body, "say which document, since there may have been several"
            # Where they can accept, for somebody who declined by accident or changed their mind.
            assert "icloud.com" in body

            assert not still_open(app), "Reject has to close the exporter"
        finally:
            if still_open(app):
                app.destroy()
