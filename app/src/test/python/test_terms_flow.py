"""
Showing Apple's terms of service, and agreeing to them.

**Why this exists at all.** Apple takes acceptance on one of its own devices or on iCloud.com and
nowhere else, and a user of this app generally has neither. Without this they sit on a sign-in
screen that keeps refusing them and never says why - the sign-in itself worked, and the delegate
exchange after it did not.

**What is being protected is the document, not the mechanism.** What is displayed is what gets
agreed to, so the tests that matter here are the ones asserting that nothing is shortened,
reordered, omitted or mangled on the way to the screen - and that nothing can be agreed to except
the exact document that was fetched and shown.
"""

from __future__ import annotations

import json

import pytest
from findmy import MobileMeDelegateError
from findmy.reports.state import LoginState
from findmy.reports.terms import Terms, require_fetched

import main

_A_LONG_PARAGRAPH = (
    "A LONG PARAGRAPH: this sentence is deliberately far longer than any terminal width the"
    " renderer would otherwise wrap at, because a paragraph shorter than the wrap width proves"
    " nothing about wrapping at all - it comes out identical either way. It is also on one source"
    " line, because the renderer keeps a newline in the source as a line break and Apple's own"
    " HTML is machine-generated with long lines."
)
"""Assembled rather than written out, so the source line stays short and the HTML line does not."""

A_PAGE = """
<html>
  <head><style>.hidden { display: none }</style><script>alert('no')</script></head>
  <body>
    <h1>iCloud Terms of Service</h1>
    <p>Welcome to <b>iCloud</b>. This agreement covers your use of the service.</p>
    <p>""" + _A_LONG_PARAGRAPH + """</p>
    <p>Apple Inc.<br>One Apple Park Way<br>Cupertino, CA</p>
    <ul>
      <li>You must be of legal age.</li>
      <li>You are responsible for your account.</li>
    </ul>
  </body>
</html>
"""


def _terms(pageId: str = "iCloud", html: str = A_PAGE, agreeUrl: str = "https://apple/agree"):
    return Terms(page_id=pageId, agree_url=agreeUrl, html=html)


class FakeAccount:
    """
    An account with terms pending, in the three calls the bridge makes on it.

    `accept_terms` runs FindMy.py's own :func:`require_fetched` rather than merely recording the
    call. That is the point of the fake: a bridge that rebuilt a `Terms` from a page id instead
    of handing back the fetched object would satisfy a recording fake and fail here, exactly as
    it would fail against Apple.
    """

    def __init__(self, documents=None, loginState=LoginState.LOGGED_IN, fetchError=None):
        self._documents = [_terms()] if documents is None else documents
        self._loginState = loginState
        self._fetchError = fetchError
        self.accepted: list[Terms] = []
        self.completedTimes = 0

    def fetch_terms(self, *names: str):
        if self._fetchError is not None:
            raise self._fetchError
        return list(self._documents)

    def accept_terms(self, terms: Terms) -> None:
        require_fetched(terms)
        self.accepted.append(terms)

    def complete_login(self):
        self.completedTimes += 1
        return self._loginState


@pytest.fixture(autouse=True)
def _forgetTermsBetweenTests():
    """Module-level state, and a document left over from one test must not be acceptable in the next."""
    main._PENDING_TERMS.clear()
    main._ACCEPTED_TERMS.clear()
    yield
    main._PENDING_TERMS.clear()
    main._ACCEPTED_TERMS.clear()


def _rendered(account, pageId="iCloud") -> str:
    answer = json.loads(main.pendingTerms(account))
    assert answer["ok"]
    return next(d["text"] for d in answer["documents"] if d["pageId"] == pageId)


class TestTheDocumentReachesTheScreenIntact:
    """
    The half that cannot be checked by looking at it once.

    A contract rendered wrong is not a cosmetic bug: it is somebody agreeing to something other
    than what they read.
    """

    def test_every_word_of_the_body_survives(self):
        text = _rendered(FakeAccount())

        for phrase in [
            "This agreement covers your use of the service",
            "One Apple Park Way",
            "You must be of legal age",
            "You are responsible for your account",
        ]:
            assert phrase in text, f"{phrase!r} was lost on the way to the screen"

    def test_no_markup_survives(self):
        text = _rendered(FakeAccount())

        assert "<" not in text
        assert ">" not in text

    def test_scripts_and_styles_are_not_shown_as_text(self):
        # Neither is content and both read as gibberish, which in a legal document reads as
        # the app having mangled it.
        text = _rendered(FakeAccount())

        assert "alert(" not in text
        assert "display: none" not in text

    def test_an_inline_tag_does_not_insert_a_space(self):
        """
        `Welcome to <b>iCloud</b>.` must not become "Welcome to iCloud ."

        The specific mangling the renderer joins without a separator to avoid, asserted here so
        the app is covered by it and not just the desktop.
        """
        text = _rendered(FakeAccount())

        assert "Welcome to iCloud. This agreement" in text
        assert "iCloud ." not in text

    def test_a_line_break_stays_a_line_break(self):
        # An address whose <br> was dropped runs onto one line and looks like a parsing bug.
        text = _rendered(FakeAccount())

        assert "Apple Inc.\nOne Apple Park Way\nCupertino, CA" in text

    def test_a_heading_still_reads_as_a_heading(self):
        text = _rendered(FakeAccount())

        assert "ICLOUD TERMS OF SERVICE" in text
        assert "-----" in text, "the underline is what makes it a heading without markup"

    def test_list_items_still_read_as_a_list(self):
        text = _rendered(FakeAccount())

        assert "  - You must be of legal age." in text
        assert "  - You are responsible for your account." in text

    def test_paragraphs_are_not_pre_wrapped_for_a_terminal(self):
        """
        The Android-specific one.

        `render` wraps to a terminal width by default, and a `TextView` then re-wraps the result -
        so text broken at 88 columns arrives as short ragged lines with a hard break in the middle
        of each. The paragraph must reach the view as one line and be laid out for the screen it
        is actually on.

        Asserted on a paragraph **longer than any width the renderer would wrap at**. A short one
        comes out identical whether wrapping is on or off, so an earlier version of this test
        passed with the wrapping left in - which is the failure it was written to catch.
        """
        text = _rendered(FakeAccount())

        longParagraph = next(
            line for line in text.splitlines() if line.startswith("A LONG PARAGRAPH"))

        assert len(longParagraph) > 200, (
            "the paragraph was broken up by wrapping meant for a terminal, and a TextView will"
            " now re-wrap those fragments into ragged short lines")
        assert longParagraph.endswith("machine-generated with long lines.")

    def test_a_document_that_cannot_be_parsed_is_still_shown(self):
        # Swallowing it would leave the user agreeing to a blank box.
        text = _rendered(FakeAccount([_terms(html="just some bare text, no tags at all")]))

        assert "just some bare text" in text


class TestWhatIsOfferedForAcceptance:
    def test_each_document_is_offered_by_the_id_it_is_accepted_by(self):
        account = FakeAccount([_terms("iCloud"), _terms("iCloudTerms2")])

        answer = json.loads(main.pendingTerms(account))

        assert [d["pageId"] for d in answer["documents"]] == ["iCloud", "iCloudTerms2"]

    def test_a_document_apple_will_not_take_agreement_to_says_so(self):
        """
        An empty `agree_url` means acceptance cannot be recorded, and `require_fetched` refuses
        it. Better a screen that says so than a button that fails.
        """
        answer = json.loads(main.pendingTerms(FakeAccount([_terms(agreeUrl="")])))

        assert answer["documents"][0]["canAccept"] is False

    def test_a_fetch_that_fails_is_reported_with_words_in_it(self):
        account = FakeAccount(fetchError=RuntimeError("Apple said no"))

        answer = json.loads(main.pendingTerms(account))

        assert not answer["ok"]
        assert "Apple said no" in answer["message"]


class TestAgreeing:
    def test_the_object_that_was_fetched_is_the_object_that_is_accepted(self):
        """
        Not a reconstruction from the page id.

        `require_fetched` exists to make agreeing to a document this never obtained refusable,
        and the fake enforces it - so this is the test that would fail if the bridge started
        rebuilding `Terms` to avoid holding them.
        """
        document = _terms("iCloud")
        account = FakeAccount([document])
        main.pendingTerms(account)

        assert json.loads(main.acceptTerms(account, "iCloud"))["ok"]
        assert account.accepted == [document]
        assert account.accepted[0] is document

    def test_nothing_that_was_not_shown_can_be_accepted(self):
        account = FakeAccount()
        main.pendingTerms(account)

        answer = json.loads(main.acceptTerms(account, "SomethingElse"))

        assert not answer["ok"]
        assert answer["reason"] == "no_such_document"
        assert account.accepted == [], "nothing may be sent for a document nobody saw"

    def test_nothing_can_be_accepted_before_anything_is_fetched(self):
        account = FakeAccount()

        assert not json.loads(main.acceptTerms(account, "iCloud"))["ok"]
        assert account.accepted == []

    def test_signing_in_is_only_finished_once_every_document_is_agreed(self):
        """
        `complete_login` is the exchange that failed in the first place. Running it with terms
        still outstanding just fails again, and reads to the user as agreeing not having worked.
        """
        account = FakeAccount([_terms("iCloud"), _terms("iCloudTerms2")])
        main.pendingTerms(account)

        first = json.loads(main.acceptTerms(account, "iCloud"))

        assert first["remaining"] == 1
        assert account.completedTimes == 0

        second = json.loads(main.acceptTerms(account, "iCloudTerms2"))

        assert second["remaining"] == 0
        assert account.completedTimes == 1

    def test_the_login_state_is_reported_so_a_bad_one_is_not_stored(self):
        """
        The caller must refuse to store anything but LOGGED_IN - issues #43 and #119, where an
        account persisted in another state failed every later fetch inside FindMy.py's own check.
        """
        account = FakeAccount(loginState=LoginState.REQUIRE_2FA)
        main.pendingTerms(account)

        answer = json.loads(main.acceptTerms(account, "iCloud"))

        assert answer["ok"]
        assert answer["loginState"] == "REQUIRE_2FA"

    def test_the_happy_path_reports_logged_in(self):
        account = FakeAccount()
        main.pendingTerms(account)

        assert json.loads(main.acceptTerms(account, "iCloud"))["loginState"] == "LOGGED_IN"

    def test_a_refused_acceptance_is_reported_rather_than_raised(self):
        account = FakeAccount([_terms(agreeUrl="")])
        main.pendingTerms(account)

        answer = json.loads(main.acceptTerms(account, "iCloud"))

        assert not answer["ok"]
        assert answer["message"].strip()

    def test_a_second_fetch_forgets_the_first(self):
        # Otherwise a document from an abandoned attempt stays acceptable, and "accept" could
        # send agreement for something the user is no longer looking at.
        account = FakeAccount([_terms("iCloud")])
        main.pendingTerms(account)
        main.pendingTerms(FakeAccount([_terms("iCloudTerms2")]))

        assert not json.loads(main.acceptTerms(account, "iCloud"))["ok"]


class TestRecognisingThatTermsAreWhyTheSignInFailed:
    def test_a_delegate_failure_is_reported_as_terms(self):
        assert main.classifyLoginFailure(
            MobileMeDelegateError(localized_error="TERMS")) == main.REASON_TERMS

    def test_it_is_not_confused_with_a_network_failure(self):
        # The two need opposite screens: one offers the terms, the other says try again.
        assert main.classifyLoginFailure(TimeoutError()) == main.REASON_NETWORK
        assert main.REASON_TERMS != main.REASON_NETWORK

    def test_the_detail_is_never_empty(self):
        detail = main.describeLoginFailure(MobileMeDelegateError(localized_error="TERMS"))

        assert detail.strip()
