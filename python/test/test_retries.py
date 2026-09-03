"""
Retrying the three things a person types, instead of ending the run on a typo.

**The behaviour under test is what a typo costs.** Every one of these made exactly one attempt, and
a rejection propagated out of the whole export - so one wrong character threw away everything
before it and the user began again from their Apple ID.

Each stage has its own reason for retrying, and its own reason for being careful:

- **The Apple ID and password.** Capped low, because Apple locks an account after enough failures
  and how many is not a thing to establish by experiment on somebody's real account.
- **The verification code.** Re-typing must not request a new one: a resend invalidates the code
  Apple already sent, so the recovery step would destroy what the user is holding.
- **The screen-lock passcode.** FindMy.py's own advice leads with trying again *with the same
  passcode*, because the call fails intermittently and then succeeds. Attempts are probably capped
  too, so nothing here retries without being asked.

`asyncio.run` rather than pytest-asyncio, which this project does not depend on - the same way
`test_cli.py` drives the flow's other coroutines.
"""

from __future__ import annotations

import asyncio
import inspect

import pytest
from findmy import InvalidCredentialsError, LoginState
from findmy.errors import UnhandledProtocolError
from findmy.keychain.recovery import RecoveryError

from exporter import icloud
from exporter.icloud import ExportSourceError

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


class FakeAccount:
    """An account that accepts one credential pair and rejects every other."""

    def __init__(self, accepts=("me@example.com", "hunter2"), state=None) -> None:
        self.accepts = accepts
        self.state = state
        self.tried: list[tuple[str, str]] = []

    async def login(self, email: str, password: str):
        self.tried.append((email, password))

        if (email, password) != self.accepts:
            raise InvalidCredentialsError("Apple rejected that.")

        return self.state


class FakeFactor:
    """A second factor that accepts one code, and counts how often a new one was requested."""

    def __init__(self, accepts: str = "123456") -> None:
        self.accepts = accepts
        self.submitted: list[str] = []
        self.requests = 0

    async def request(self) -> None:
        self.requests += 1

    async def submit(self, code: str):
        self.submitted.append(code)

        if code != self.accepts:
            raise InvalidCredentialsError("Apple rejected that code.")

        return LoginState.LOGGED_IN


class FakeFactorThatTakesTheCodeThenFails:
    """
    A second factor that models issue #168: the code is accepted, and signing in fails anyway.

    `td_2fa_submit` submits the code and *then* re-authenticates against Grand Slam. The second
    half returned 503 and the first had already spent the code, which is why `submitted` records
    every attempt even when nothing succeeded.
    """

    def __init__(self, *, fail_first: int = 1) -> None:
        self.fail_first = fail_first
        self.submitted: list[str] = []
        self.requests = 0
        self.on_request = None

    async def request(self) -> None:
        self.requests += 1
        if self.on_request is not None:
            self.on_request()

    async def submit(self, code: str):
        self.submitted.append(code)

        if len(self.submitted) <= self.fail_first:
            raise UnhandledProtocolError("Error response for GSA request: 503")

        return LoginState.LOGGED_IN


@pytest.fixture
def no_waiting(monkeypatch):
    """
    The same number of retries, with the waiting taken out.

    Patched rather than shortened in the source: the durations are the finding (see
    `SPENT_CODE_WAITS`), and a suite that quietly ran with different ones would be testing
    something nobody ships.
    """
    monkeypatch.setattr(icloud, "SPENT_CODE_WAITS", tuple(0 for _ in icloud.SPENT_CODE_WAITS))


class TestACodeAppleTookAndThenFailedOn:
    """
    Issue #168: Apple accepted the code, then answered 503 to the re-authentication behind it.

    Nothing the user could type recovers this - the code is spent - and the only recovery anyone
    has observed is the passage of time. So it waits and asks for a new code itself, twice, and
    only then says it could not.

    What was broken was never the retrying. This escaped the loop entirely, landed in the wizard's
    catch-all handler, and asked the one person who could do nothing about it to file a bug. One
    duly did.
    """

    def test_it_is_recognised_as_a_spent_code(self):
        assert icloud.code_was_already_spent(
            UnhandledProtocolError("Error response for GSA request: 503"))

    def test_a_rejected_code_is_not_a_spent_one(self):
        """The opposite case, and the one where re-typing is right."""
        assert not icloud.code_was_already_spent(
            InvalidCredentialsError("Apple rejected that code."))

    def test_it_waits_and_sends_a_new_code(self, no_waiting):
        """The recovery, and that the code is a *new* one rather than the spent one re-typed."""
        factor = FakeFactorThatTakesTheCodeThenFails(fail_first=1)
        codes = iter(["111111", "222222"])

        state = asyncio.run(icloud._submit_code_with_retries(
            factor, lambda: _next(codes), _unused,
        ))

        assert state == LoginState.LOGGED_IN
        assert factor.requests == 1, "a spent code has to be replaced"
        assert factor.submitted == ["111111", "222222"]

    def test_the_user_is_never_asked_about_it(self, no_waiting):
        """
        `retry_code` must not be consulted, and that is load-bearing.

        There is no question worth asking: re-typing cannot work and waiting is the only option,
        so a prompt would be asking somebody to choose between one real answer and a wrong one.
        Asserted because a retry path that never fires looks harmless in review.
        """
        factor = FakeFactorThatTakesTheCodeThenFails(fail_first=1)
        codes = iter(["111111", "222222"])

        asyncio.run(icloud._submit_code_with_retries(factor, lambda: _next(codes), _unused))

    def test_it_gives_up_after_the_waits_run_out(self, no_waiting):
        """A fault that does not clear must stop, rather than sending codes forever."""
        factor = FakeFactorThatTakesTheCodeThenFails(fail_first=99)
        codes = iter([str(n) * 6 for n in range(icloud.MAX_CODE_ATTEMPTS)])

        with pytest.raises(ExportSourceError):
            asyncio.run(icloud._submit_code_with_retries(factor, lambda: _next(codes), _unused))

        assert factor.requests == len(icloud.SPENT_CODE_WAITS)
        assert len(factor.submitted) == icloud.MAX_CODE_ATTEMPTS

    def test_there_is_a_wait_for_every_retry(self):
        """
        The two constants are coupled by an index, and nothing else says so.

        `SPENT_CODE_WAITS[attempt - 1]` is read on every attempt but the last, so raising the
        attempt cap without adding a duration is an IndexError in front of a user mid-sign-in.
        """
        assert len(icloud.SPENT_CODE_WAITS) == icloud.MAX_CODE_ATTEMPTS - 1

    def test_the_waits_clear_the_interval_that_was_seen_to_fail(self):
        """
        <b>The floor the one observed recovery puts under this.</b>

        A whole manual round - re-typing the Apple ID and password, waiting for a code, entering
        it - is the better part of a minute, and the attempt *after* that round was still refused.
        So a first wait materially under a minute is known to be too short, and this is the number
        that would have to be argued with rather than quietly lowered.
        """
        assert icloud.SPENT_CODE_WAITS[0] >= 60
        assert sum(icloud.SPENT_CODE_WAITS) >= 180, "the recovery seen was about two rounds out"

    def test_the_new_code_is_requested_after_the_wait_not_before(self, no_waiting):
        """
        Apple's codes expire, so one fetched before a two-minute wait is two minutes stale by the
        time it is typed. Ordering asserted because both orders look correct in a diff.
        """
        factor = FakeFactorThatTakesTheCodeThenFails(fail_first=1)
        order: list[str] = []

        async def announce_free(_text: str) -> None:  # pragma: no cover - not awaited
            pass

        factor.on_request = lambda: order.append("requested")
        asyncio.run(icloud._wait_then_send_a_new_code(
            factor, 2, lambda text: order.append(text)))

        assert order[-1] == "requested", "the code must be the last thing fetched"
        assert any("2s" in step for step in order[:-1]), "it counts down before asking"

    def test_what_escapes_is_not_the_kind_that_asks_for_a_bug_report(self, no_waiting):
        """
        <b>The complaint, in one assertion.</b>

        Both front ends show an ExportSourceError as a plain message and an unrecognised exception
        with the issue link. Raising the protocol error unchanged is what put a person in front of
        that link for weather.
        """
        factor = FakeFactorThatTakesTheCodeThenFails(fail_first=99)
        codes = iter([str(n) * 6 for n in range(icloud.MAX_CODE_ATTEMPTS)])

        with pytest.raises(ExportSourceError) as raised:
            asyncio.run(icloud._submit_code_with_retries(factor, lambda: _next(codes), _unused))

        assert not isinstance(raised.value, UnhandledProtocolError)
        assert "503" in str(raised.value)
        assert isinstance(raised.value.__cause__, UnhandledProtocolError)

    def test_it_says_it_already_waited(self, no_waiting):
        """
        Otherwise the advice reads as "just try again", which is what they have been doing.

        Somebody who has watched it wait three minutes needs to be told that is what happened, or
        the message is asking them to repeat work the program already did.
        """
        factor = FakeFactorThatTakesTheCodeThenFails(fail_first=99)
        codes = iter([str(n) * 6 for n in range(icloud.MAX_CODE_ATTEMPTS)])

        with pytest.raises(ExportSourceError) as raised:
            asyncio.run(icloud._submit_code_with_retries(factor, lambda: _next(codes), _unused))

        message = str(raised.value)
        assert "chances to settle" in message
        assert "Apple's side" in message
        assert "password" in message, "the next attempt may be refused once, and that surprised us"


class TestAWrongPasswordCanBeCorrected:
    def test_a_typo_does_not_end_the_run(self):
        account = FakeAccount(state=LoginState.LOGGED_IN)
        replacements = iter([("me@example.com", "hunter2")])

        async def retry(_error, _attempt):
            return next(replacements)

        asyncio.run(icloud.log_in(
            account, "me@example.com", "wrong",
            choose_second_factor=_unused, get_code=_unused, retry_credentials=retry,
        ))

        assert account.tried == [("me@example.com", "wrong"), ("me@example.com", "hunter2")]

    def test_the_apple_id_can_be_corrected_too(self):
        # Not only the password: the address may be the wrong one, and a retry that cannot fix it
        # is a dead end.
        account = FakeAccount(accepts=("right@example.com", "pw"), state=LoginState.LOGGED_IN)

        async def retry(_error, _attempt):
            return ("right@example.com", "pw")

        asyncio.run(icloud.log_in(
            account, "wrong@example.com", "pw",
            choose_second_factor=_unused, get_code=_unused, retry_credentials=retry,
        ))

        assert account.tried[-1] == ("right@example.com", "pw")

    def test_declining_stops_and_raises(self):
        account = FakeAccount()

        async def decline(_error, _attempt):
            return None

        with pytest.raises(InvalidCredentialsError):
            asyncio.run(icloud.log_in(
                account, "me@example.com", "wrong",
                choose_second_factor=_unused, get_code=_unused, retry_credentials=decline,
            ))

        assert len(account.tried) == 1

    def test_it_stops_at_the_cap(self):
        # Apple locks an account after enough failures, so this bound is the one that matters most.
        account = FakeAccount()

        async def retry(_error, _attempt):
            return ("me@example.com", "still wrong")

        with pytest.raises(InvalidCredentialsError):
            asyncio.run(icloud.log_in(
                account, "me@example.com", "wrong",
                choose_second_factor=_unused, get_code=_unused, retry_credentials=retry,
            ))

        assert len(account.tried) == icloud.MAX_LOGIN_ATTEMPTS

    def test_no_callback_means_one_attempt(self):
        account = FakeAccount()

        with pytest.raises(InvalidCredentialsError):
            asyncio.run(icloud.log_in(
                account, "me@example.com", "wrong",
                choose_second_factor=_unused, get_code=_unused,
            ))

        assert len(account.tried) == 1


class TestAWrongCodeCanBeCorrected:
    def test_retyping_does_not_request_a_new_code(self):
        """
        The property this whole design exists for.

        Requesting delivery again invalidates the code Apple already sent, so somebody who mistyped
        a code still sitting on their phone must not have it taken away by the recovery step.
        """
        factor = FakeFactor(accepts="123456")
        codes = iter(["123455", "123456"])

        async def retry(_error, _attempt):
            return icloud.CODE_AGAIN

        state = asyncio.run(icloud._submit_code_with_retries(
            factor, lambda: _next(codes), retry,
        ))

        assert state == LoginState.LOGGED_IN
        assert factor.submitted == ["123455", "123456"]
        assert factor.requests == 0, "re-typing must not invalidate the code they hold"

    def test_asking_for_a_new_code_requests_one(self):
        factor = FakeFactor(accepts="123456")
        codes = iter(["999999", "123456"])

        async def retry(_error, _attempt):
            return icloud.CODE_RESEND

        asyncio.run(icloud._submit_code_with_retries(factor, lambda: _next(codes), retry))

        assert factor.requests == 1

    def test_stopping_raises_without_requesting_anything(self):
        factor = FakeFactor(accepts="123456")

        async def stop(_error, _attempt):
            return None

        with pytest.raises(InvalidCredentialsError):
            asyncio.run(icloud._submit_code_with_retries(
                factor, lambda: _next(iter(["000000"])), stop,
            ))

        assert factor.requests == 0

    def test_it_stops_at_the_cap(self):
        factor = FakeFactor(accepts="never")
        codes = iter(["1"] * icloud.MAX_CODE_ATTEMPTS)

        async def retry(_error, _attempt):
            return icloud.CODE_AGAIN

        with pytest.raises(InvalidCredentialsError):
            asyncio.run(icloud._submit_code_with_retries(factor, lambda: _next(codes), retry))

        assert len(factor.submitted) == icloud.MAX_CODE_ATTEMPTS


async def _next(codes):
    return next(codes)


async def _unused(*_args, **_kwargs):
    raise AssertionError("should not have been asked")


class TestTheDiagnosticsSurvive:
    """
    `-vv` has to cover the module that explains a failure, not the modules that were interesting
    when the flag was written.
    """

    def test_verbose_covers_every_part_of_findmy(self):
        """
        The regression this exists for.

        `configure_logging` used to enumerate `findmy.cloudkit`, `findmy.keychain` and
        `findmy.icloud` - every part anybody had needed, and therefore wrong the first time a new
        one mattered. `announce_device` logs under `findmy.reports`, so the DEBUG line written
        specifically to explain an HTTP 401 was discarded by the flag turned on to read it.
        """
        import logging

        from exporter import cli

        cli.configure_logging(2)

        assert logging.getLogger("findmy.reports.account").isEnabledFor(logging.DEBUG)

    def test_but_not_the_socket_chatter(self):
        # Why it was a list rather than the root logger in the first place: aiohttp and asyncio at
        # DEBUG bury everything about Apple. `findmy` is the level that excludes them without
        # having to predict which of its modules matters next.
        import logging

        from exporter import cli

        cli.configure_logging(2)

        assert not logging.getLogger("aiohttp").isEnabledFor(logging.DEBUG)
        assert not logging.getLogger("asyncio").isEnabledFor(logging.DEBUG)
