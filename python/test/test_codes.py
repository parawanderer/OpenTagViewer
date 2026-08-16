"""
What counts as a verification code.

These used to live in `test_cli.py` and import them from `exporter.wizard`, which imports tkinter -
so the CLI's own test file could not be collected on a machine with no Tk. CI is such a machine,
and so is any server somebody runs the headless exporter on. The functions moved to
`exporter.codes`; these moved with them.
"""

from __future__ import annotations

import pytest

from exporter.codes import is_verification_code, verification_code


class TestVerificationCodes:
    """
    Blocking on a code that cannot be one saves a round trip to Apple to be told what was already
    knowable - and a rejected code is not a free question: the next one has to be sent again.

    Both entry points use these. The window disables its OK button; the CLI asks again.
    """

    @pytest.mark.parametrize("typed", ["123456", "123 456", "123-456", " 123456 "])
    def test_accepts_six_digits_however_they_were_pasted(self, typed):
        # People paste them with a space or a hyphen, because that is how the notification shows
        # them. Taking the digits is kinder than rejecting the paste.
        assert is_verification_code(typed)
        assert verification_code(typed) == "123456"

    @pytest.mark.parametrize("typed", ["", "   ", "12345", "1234567", "abcdef", "12345a"])
    def test_refuses_anything_that_is_not_six_digits(self, typed):
        assert not is_verification_code(typed)


class TestNothingHereNeedsADisplay:
    def test_the_module_does_not_drag_in_tkinter(self):
        """
        The reason this module exists, asserted rather than trusted.

        A later refactor that moves these back beside the window would pass every test above and
        break the CLI on exactly the machines it is for, which is how this got missed the first
        time.
        """
        import subprocess
        import sys

        proc = subprocess.run(
            [sys.executable, "-c",
             "import sys, exporter.codes; assert 'tkinter' not in sys.modules"],
            capture_output=True,
        )

        assert proc.returncode == 0, proc.stderr.decode()
