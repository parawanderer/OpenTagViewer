"""
Carrying Apple's own "try again in N" from a refused sign-in to the screen.

**Absent is the case that matters most**, because it is the only one observed. No refusal from
Grand Slam has been seen with a `Retry-After` header - not the 503s of September 2026, not the
429 that followed them - so what these tests protect first is that a missing header stays
missing, all the way across, instead of becoming a zero that reads as "retry now".
"""

from __future__ import annotations

from findmy.errors import AppleServiceUnavailableError

import main


class TestTheWaitCrossesTheBridge:
    def test_a_wait_apple_named_reaches_java(self, signingIn):
        _, answer = signingIn(
            AppleServiceUnavailableError(429, "The Grand Slam request", retry_after=90.0),
        )

        assert answer["reason"] == main.REASON_APPLE_DECLINED
        assert answer["retryAfterSeconds"] == 90.0

    def test_no_wait_is_no_key_rather_than_zero(self, signingIn):
        """Zero is a real answer, meaning "now", and Apple did not give it."""
        _, answer = signingIn(AppleServiceUnavailableError(429, "The Grand Slam request"))

        assert answer["reason"] == main.REASON_APPLE_DECLINED
        assert "retryAfterSeconds" not in answer

    def test_a_wait_of_zero_is_still_carried(self, signingIn):
        """A date already past arrives as 0, and is not the same as no answer."""
        _, answer = signingIn(
            AppleServiceUnavailableError(503, "The Grand Slam request", retry_after=0.0),
        )

        assert answer["retryAfterSeconds"] == 0.0

    def test_other_failures_carry_no_wait(self, signingIn):
        _, answer = signingIn(TimeoutError())

        assert "retryAfterSeconds" not in answer


class TestReadingTheWait:
    def test_it_is_read_off_the_error(self):
        error = AppleServiceUnavailableError(429, "The Grand Slam request", retry_after=45.0)

        assert main.appleAskedToWait(error) == 45.0

    def test_it_is_none_when_apple_did_not_say(self):
        assert main.appleAskedToWait(AppleServiceUnavailableError(429, "x")) is None

    def test_it_is_none_for_anything_else(self):
        assert main.appleAskedToWait(RuntimeError("Retry-After: 30")) is None
