"""
Recognising this project's own serials, so its escrow records stay out of a recovery picker.

Joining the trust circle writes an escrow record beside the user's real hardware. The picker
then asks "which device's screen-lock passcode do you have?" about a program with no screen, and
there is no answer: that passcode was generated, never shown, and was never the user's to know.

**Both directions are load-bearing, and they fail in opposite ways.** Too lax leaves unusable
tiles the user tries to guess a PIN for. Too eager hides real hardware, and presents as "it
cannot see my Mac" - which costs somebody the only recovery path they had.
"""

from __future__ import annotations

import pytest

from exporter.identity import (
    APP_SERIAL_PREFIX,
    EXPORTER_SERIAL,
    OUR_SERIAL_PREFIXES,
    SERIAL_PREFIX,
    written_by_opentagviewer,
)

LEGACY_APP_SERIAL = "0PENTAGVIEWR"
"""What the app presented before serials were drawn. Named here so the test reads as evidence."""


class TestOurOwnRecordsAreRecognised:
    @pytest.mark.parametrize("serial", [
        "0PENTAGVQ4WM",   # the app, drawn
        "0PENTAGXR7KD",   # this program, drawn
        LEGACY_APP_SERIAL,
        EXPORTER_SERIAL,
    ])
    def test_a_serial_this_project_presents_is_ours(self, serial: str) -> None:
        assert written_by_opentagviewer(serial)

    def test_the_legacy_constants_need_no_special_case(self) -> None:
        # They begin with their own prefixes, so matching the prefix catches them for free.
        # Worth pinning: a later reader may be tempted to add them as literals, or to remove
        # them believing they are handled separately.
        assert LEGACY_APP_SERIAL.startswith(APP_SERIAL_PREFIX)
        assert EXPORTER_SERIAL.startswith(SERIAL_PREFIX)

    def test_every_prefix_is_covered(self) -> None:
        # So adding a third prefix without adding it to the tuple fails here rather than in a
        # picker on somebody's account.
        for prefix in OUR_SERIAL_PREFIXES:
            assert written_by_opentagviewer(prefix + "0000")


class TestRealHardwareIsLeftAlone:
    @pytest.mark.parametrize("serial", [
        "F2LX9Q4RNB",      # an actual-shaped Apple serial
        "C02XK1ABCDEF",
        "0PENTAG",         # short of either prefix
        "0PENTAHV1234",    # one letter off
        "PENTAGV1234",     # missing the leading zero
        "X0PENTAGV123",    # ours, but not at the start
        "0pentagv1234",    # lowercase: Apple serials are uppercase, so this is not one of ours
        "",
    ])
    def test_a_serial_that_is_not_ours_is_not_claimed(self, serial: str) -> None:
        assert not written_by_opentagviewer(serial)


class TestRecordsThatSayNothing:
    """
    The escrow schema is genuinely unstable - of twelve records on one account, one had no
    serial, build or bottle id at all. So the absent cases are ordinary, not defensive padding.
    """

    @pytest.mark.parametrize("value", [None, 12345, b"0PENTAGV1234", ["0PENTAGV1234"]])
    def test_anything_that_is_not_a_string_is_not_ours(self, value: object) -> None:
        # Kept rather than dropped: hiding a record because a field was missing would hide real
        # hardware on the strength of nothing at all.
        assert not written_by_opentagviewer(value)  # type: ignore[arg-type]
