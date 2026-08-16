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


class FakeNamedAccount:
    """An account that records whether it was asked to name itself."""

    def __init__(self, fail: bool = False) -> None:
        self.fail = fail
        self.announced = 0

    async def announce_device(self) -> None:
        self.announced += 1

        if self.fail:
            raise RuntimeError("Apple said no")

    def to_json(self):
        return {
            "ids": {"uid": "uid-1", "devid": "devid-1"},
            "anisette": {"type": "aniLocal", "prov_data": "AAAA"},
        }


class TestNamingThisDeviceInTheAccountList:
    """
    Without this the entry is named after the hardware the client claims to be - a bare
    "MacBook Pro" among somebody's real Macs, beside a *Remove from Account* button.
    """

    def test_a_first_run_names_itself(self, tmp_path, monkeypatch):
        identity = tmp_path / "device-identity.json"
        monkeypatch.setattr(icloud.device, "identity_path", lambda: identity)
        account = FakeNamedAccount()

        asyncio.run(icloud.remember(account))

        assert account.announced == 1

    def test_a_later_run_does_not_name_itself_again(self, tmp_path, monkeypatch):
        # Announcing writes to the user's account. Once the identity is stored this installation
        # has registered before, so there is nothing new to name and nothing to write.
        identity = tmp_path / "device-identity.json"
        monkeypatch.setattr(icloud.device, "identity_path", lambda: identity)

        asyncio.run(icloud.remember(FakeNamedAccount()))
        second = FakeNamedAccount()
        asyncio.run(icloud.remember(second))

        assert second.announced == 0

    def test_the_identity_is_stored_either_way(self, tmp_path, monkeypatch):
        identity = tmp_path / "device-identity.json"
        monkeypatch.setattr(icloud.device, "identity_path", lambda: identity)

        asyncio.run(icloud.remember(FakeNamedAccount()))

        assert icloud.device.load(identity) is not None

    def test_a_failure_to_name_does_not_fail_the_export(self, tmp_path, monkeypatch):
        # What is lost is a recognisable name. Failing a sign-in that has already succeeded to
        # report a cosmetic problem would be the wrong trade.
        identity = tmp_path / "device-identity.json"
        monkeypatch.setattr(icloud.device, "identity_path", lambda: identity)
        account = FakeNamedAccount(fail=True)

        asyncio.run(icloud.remember(account))

        assert account.announced == 1
        assert icloud.device.load(identity) is not None


class TestOneName:
    def test_cloudkit_and_the_device_list_are_told_the_same_thing(self):
        """
        Rule 11. Two names for one installation would put two differently-labelled things on the
        account, which is exactly the confusion the serial exists to prevent.
        """
        from exporter import identity

        assert identity.CLOUDKIT_DEVICE_NAME == identity.DEVICE_NAME
        assert identity.DEVICE_NAME == "OpenTagViewer Exporter"
