"""
Retrying a rejected screen-lock passcode.

**The behaviour under test is what a typo costs.** Before this, both entry points made exactly one
attempt and a rejection propagated out of the whole export - so one mistyped character in a hidden
field threw away the sign-in, the verification code and everything after it.

That is the wrong shape for this failure in particular. FindMy.py's advice on a rejection leads
with "try again with the same passcode", because the call has been observed to fail intermittently
and then succeed. The tests that matter here are therefore about *not spending* what cannot be got
back: attempts are capped, nothing retries without being asked, and the passcode is not kept.

`asyncio.run` rather than pytest-asyncio, which this project does not depend on - the same way
`test_cli.py` drives the flow's other coroutines.
"""

from __future__ import annotations

import asyncio
import inspect

import pytest
from findmy.keychain.recovery import RecoveryError

from exporter import icloud

RECORD = object()

REJECTION = (
    "Escrow proxy rejected recover: HTTP 409, status -6015 -- CLUBH ERROR: Credential is"
    " not verified."
)


class FakeClient:
    """
    A client that accepts one passcode and rejects every other.

    It records what it was given, so a test can assert on the passcodes tried and not only on how
    many times - "it retried" and "it retried with the right thing" are different claims.
    """

    def __init__(self, accepts: str | None = None, *, reject_first: int = 0) -> None:
        self.accepts = accepts
        self.reject_first = reject_first
        self.tried: list[str] = []

    async def unlock(self, record, passcode: str) -> None:
        assert record is RECORD
        self.tried.append(passcode)

        # `reject_first` models the intermittent failure: the same passcode is refused and then
        # accepted, which is the case the library says to expect.
        if len(self.tried) <= self.reject_first:
            raise RecoveryError(REJECTION)

        if self.accepts is not None and passcode != self.accepts:
            raise RecoveryError(REJECTION)


class Typist:
    """Types the given passcodes in order, and records the attempt numbers it was asked for."""

    def __init__(self, *passcodes: str) -> None:
        self.passcodes = passcodes
        self.asked: list[int] = []

    async def __call__(self, attempt: int) -> str:
        self.asked.append(attempt)

        return self.passcodes[attempt - 1]


class Answer:
    """Answers "try again?" the same way every time, recording which attempts asked."""

    def __init__(self, reply: bool) -> None:
        self.reply = reply
        self.asked: list[int] = []
        self.messages: list[str] = []

    async def __call__(self, error, attempt: int) -> bool:
        self.asked.append(attempt)
        self.messages.append(str(error))

        return self.reply


def run(client, typist, answer):
    return asyncio.run(icloud.unlock(client, RECORD, typist, answer))


class TestARejectedPasscodeCanBeRetried:
    def test_a_typo_does_not_end_the_run(self):
        client = FakeClient(accepts="1234")

        run(client, Typist("12e4", "1234"), Answer(True))

        assert client.tried == ["12e4", "1234"]

    def test_the_same_passcode_again_is_a_normal_thing_to_do(self):
        # The library's first advice on a rejection, because the call fails intermittently and the
        # identical passcode then works. Nothing may require a retry to differ from what preceded
        # it, which is easy to break by "helpfully" rejecting a repeat.
        client = FakeClient(reject_first=1)

        run(client, Typist("1234", "1234"), Answer(True))

        assert client.tried == ["1234", "1234"]

    def test_a_passcode_that_works_first_time_asks_nothing_further(self):
        client, answer = FakeClient(accepts="1234"), Answer(True)

        run(client, Typist("1234"), answer)

        assert client.tried == ["1234"]
        assert answer.asked == []

    def test_the_attempt_number_counts_from_one(self):
        # It is shown to the user as "attempt N of M", so an off-by-one is visible and alarming.
        client, typist = FakeClient(accepts="1234"), Typist("wrong", "1234")

        run(client, typist, Answer(True))

        assert typist.asked == [1, 2]


class TestNothingIsSpentWithoutBeingAsked:
    """
    The cap is Apple's and its size is not established here, so an attempt is not this program's to
    spend on its own. That is the whole reason the rejection callback returns a value.
    """

    def test_declining_stops_immediately(self):
        client = FakeClient(accepts="1234")

        with pytest.raises(RecoveryError):
            run(client, Typist("wrong"), Answer(False))

        assert client.tried == ["wrong"], "declining must not try again"

    def test_it_gives_up_at_the_cap_rather_than_looping(self):
        client = FakeClient(accepts="never matches")
        typist = Typist(*["wrong"] * icloud.MAX_UNLOCK_ATTEMPTS)

        with pytest.raises(RecoveryError):
            run(client, typist, Answer(True))

        assert len(client.tried) == icloud.MAX_UNLOCK_ATTEMPTS

    def test_the_last_attempt_does_not_ask_whether_to_try_again(self):
        # There is nothing to decide: the answer cannot be acted on either way, and asking implies
        # an attempt is available when it is not.
        client = FakeClient(accepts="never matches")
        answer = Answer(True)

        with pytest.raises(RecoveryError):
            run(client, Typist(*["wrong"] * icloud.MAX_UNLOCK_ATTEMPTS), answer)

        assert answer.asked == list(range(1, icloud.MAX_UNLOCK_ATTEMPTS))

    def test_the_cap_is_more_than_one(self):
        # Guards the point of the change: a cap of 1 is the old behaviour wearing a loop.
        assert icloud.MAX_UNLOCK_ATTEMPTS > 1


class TestWhatTheUserIsTold:
    def test_the_librarys_own_advice_reaches_the_caller(self):
        # The exporter writes no advice about escrow recovery of its own: FindMy.py's message
        # carries the rejection and three ordered things to do about it, and a re-wording here
        # would be a second version to keep in step with the first.
        client, answer = FakeClient(accepts="1234"), Answer(True)

        run(client, Typist("wrong", "1234"), answer)

        assert len(answer.messages) == 1
        assert "-6015" in answer.messages[0]

    def test_the_error_that_escapes_is_a_recovery_error(self):
        # `main` distinguishes this from an unmodelled protocol shape and reports it without a
        # traceback, which only works if the type survives the loop.
        client = FakeClient(accepts="never matches")

        with pytest.raises(RecoveryError, match="-6015"):
            run(client, Typist("a", "b", "c"), Answer(True))


class TestThePasscodeIsNotKept:
    def test_it_is_released_after_every_attempt(self):
        """
        The loop holds a passcode across a question that waits on a person, which is longer than
        the one-shot version ever did. The `del` in the `finally` is what stops that becoming a
        name that outlives the call, and it is invisible unless something says so.
        """
        source = inspect.getsource(icloud.unlock)

        assert "del passcode" in source, "the passcode must not outlive the attempt"
        assert source.index("finally") < source.index("del passcode")
