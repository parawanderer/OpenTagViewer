"""
A 503 from Apple is not a bug report.

Issue #176. One account met the same 503 three times in three minutes, at `td_2fa_submit`, at
`request_pet` a second later, and at `login` a minute after that. Only the first had any handling,
so the other two reached the wizard's catch-all - the one that names an exception type and links
the issue tracker - and the user filed an issue, because the program asked them to.

The 2FA path recovers on its own and that half works: the same log shows it waiting, requesting a
new code, and signing in. This is about the other two, where there is nothing to retry but the
whole sign-in.
"""

from __future__ import annotations

import pytest
from findmy.errors import AppleServiceUnavailableError, UnhandledProtocolError

from exporter import icloud


def a_503() -> AppleServiceUnavailableError:
    return AppleServiceUnavailableError(503, "The Grand Slam request")


class TestFindingItInWhateverWrappedIt:
    def test_itisFoundWhenRaisedDirectly(self):
        """What `log_in` does: the error arrives as itself."""
        error = a_503()

        assert icloud.apple_is_declining(error) is error

    def test_itisFoundThroughACauseChain(self):
        """
        What `open_client` does.

        `request_pet` fails several frames down and the failure is re-raised wrapped, which is
        exactly the path that reached the bug-report dialog in #176.
        """
        declining = a_503()
        wrapped = RuntimeError("could not open the client")
        wrapped.__cause__ = declining

        assert icloud.apple_is_declining(wrapped) is declining

    def test_itisFoundThroughImplicitChaining(self):
        """`raise X` inside an `except` sets __context__ rather than __cause__."""
        declining = a_503()
        try:
            try:
                raise declining
            except AppleServiceUnavailableError:
                raise RuntimeError("while handling")  # noqa: B904, TRY200
        except RuntimeError as raised:
            assert icloud.apple_is_declining(raised) is declining

    def test_anordinaryProtocolErrorIsNotThis(self):
        """
        The distinction the whole change turns on.

        `UnhandledProtocolError` still means "report this". Only the subclass means "wait".
        """
        assert icloud.apple_is_declining(
            UnhandledProtocolError("Error response for GSA request: 418")) is None

    def test_nothingAtAllIsNotThis(self):
        assert icloud.apple_is_declining(ValueError("unrelated")) is None

    def test_acycleDoesNotHangIt(self):
        """
        A self-referencing chain must terminate.

        Contrived, but this walks `__cause__` and `__context__` on exceptions this program did
        not construct, and an infinite loop here would hang a wizard rather than fail it.
        """
        first = RuntimeError("a")
        second = RuntimeError("b")
        first.__cause__ = second
        second.__cause__ = first

        assert icloud.apple_is_declining(first) is None


class TestWhatTheUserIsTold:
    @pytest.fixture
    def message(self) -> str:
        return icloud.describe_apple_declining(a_503())

    def test_itcarriesTheStatus(self, message):
        """503 is what makes a report answerable if this ever turns out not to be weather."""
        assert "503" in message

    def test_itsaysTheFaultIsApples(self, message):
        assert "Apple's side" in message
        assert "rather than anything you did" in message

    def test_itclearsThePasswordAndTheCode(self, message):
        """Otherwise the next thing tried is a password reset, which cannot help."""
        assert "password" in message
        assert "verification code" in message

    def test_itsaysNothingWasChanged(self, message):
        """The first question after a failed sign-in to your own Apple account."""
        assert "nothing was changed" in message.lower()

    def test_itsaysWhenReportingWouldBeReasonable(self, message):
        """
        Not "never report this".

        A 503 that persists for an hour is worth hearing about; the point is that a run of them
        over a few minutes is not.
        """
        assert "worth reporting" in message

    def test_itdoesNotAskForABugReportOutright(self, message):
        """The behaviour being fixed."""
        assert "github.com" not in message.lower()
