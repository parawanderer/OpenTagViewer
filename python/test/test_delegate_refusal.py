"""
Telling "Apple will not open iCloud for this account" apart from "terms are pending".

Both arrive as a `MobileMeDelegateError` from the same call, and for a long time both were
answered with the terms flow - because terms were the only cause anybody had a remedy for. The
response has two independent error channels and terms use only one of them, so that answer was
right about half the time and misleading the rest.

OpenTagViewer#221 is the misleading half, reported against the app: an account whose terms were
fine, offered terms, shown nothing, and left to work out why.
"""

from __future__ import annotations

from findmy.errors import MobileMeDelegateError

from exporter.icloud import not_a_terms_problem

# Quoted from the screenshot on #221 rather than invented, because the wording is the evidence.
REFUSED = MobileMeDelegateError(
    status=1,
    status_message="A server problem is blocking Apple ID sign in. Try signing in later.",
)


class TestWhichFailuresTheTermsFlowCanAnswer:
    def test_a_failure_with_no_localized_error_is_not_about_terms(self) -> None:
        assert not_a_terms_problem(REFUSED) is not None

    def test_a_failure_that_named_one_is_left_to_the_terms_flow(self) -> None:
        # The other half of the branch. Without it, "fixing" this by always returning a message
        # makes the terms flow unreachable and nothing goes red.
        error = MobileMeDelegateError(localized_error="SOME_VALUE", status=1)

        assert not_a_terms_problem(error) is None

    def test_an_unauthorized_response_is_also_left_alone(self) -> None:
        # A known value on the same channel: the credential was refused, not the account. It has
        # its own remedy in the library's message, and this must not talk over it.
        error = MobileMeDelegateError(localized_error="UNAUTHORIZED", status=1)

        assert not_a_terms_problem(error) is None


class TestWhatThePersonIsTold:
    def test_it_says_signing_in_actually_worked(self) -> None:
        # The screen this replaces appears immediately after a verification code was typed, so
        # the first thing to settle is that the password and the code were not the problem.
        message = not_a_terms_problem(REFUSED)

        assert message is not None
        assert "Signing in worked" in message

    def test_it_says_plainly_that_terms_are_not_the_cause(self) -> None:
        message = not_a_terms_problem(REFUSED)

        assert message is not None
        assert "not about terms of service" in message

    def test_it_quotes_what_apple_said(self) -> None:
        # The status message is the only evidence a bug report about this can carry, and the
        # thing to search for when the next person meets it.
        message = not_a_terms_problem(REFUSED)

        assert message is not None
        assert "A server problem is blocking Apple ID sign in." in message

    def test_it_names_the_remedy_other_clients_found(self) -> None:
        # Borrowed from macless-haystack#84/#86/#87. Not ours, and not proven - but it is the
        # only remedy anybody has, and withholding it helps nobody.
        message = not_a_terms_problem(REFUSED)

        assert message is not None
        assert "appleid.apple.com" in message
        assert "payment method" in message

    def test_it_does_not_repeat_apples_advice_to_wait(self) -> None:
        """
        Apple says "try signing in later" and across these clients that does not work.

        Passing it on would send somebody to retry an unchanged sign-in indefinitely, which is
        the one outcome worse than saying "this looks like the account".
        """
        message = not_a_terms_problem(REFUSED)

        assert message is not None
        assert "generally does not" in message

    def test_a_response_that_said_nothing_still_produces_a_description(self) -> None:
        # `status_message` can be absent. Falling back to the whole error keeps the message from
        # having a blank where its only evidence should be.
        message = not_a_terms_problem(MobileMeDelegateError(status=1))

        assert message is not None
        assert "com.apple.mobileme" in message
