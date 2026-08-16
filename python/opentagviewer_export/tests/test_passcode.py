"""
The code that unlocks a bundle, and the reading-it-back-off-paper problem.

`normalise_passcode` is an interoperability contract rather than a convenience: the Android
importer will have to fold the same characters onto the same digits, because a zip password is
compared as bytes and one wrong character is indistinguishable from the wrong code.
"""

from __future__ import annotations

import pytest

from opentagviewer_export import (
    PASSCODE_ALPHABET,
    format_passcode,
    generate_passcode,
    normalise_passcode,
)
from opentagviewer_export.passcode import PASSCODE_LENGTH, PasscodeError


class TestGenerating:
    def test_is_long_enough_that_guessing_it_offline_is_not_worth_starting(self):
        # The zip format fixes key derivation at PBKDF2-SHA1, 1000 iterations, so length is the
        # only lever there is: a six-digit PIN would fall in seconds no matter what.
        assert PASSCODE_LENGTH == 12
        assert len(generate_passcode()) == PASSCODE_LENGTH

    def test_uses_only_characters_that_survive_being_written_down(self):
        assert set(generate_passcode(200)) <= set(PASSCODE_ALPHABET)

    @pytest.mark.parametrize("confusable", ["I", "L", "O", "U"])
    def test_leaves_out_the_letters_people_misread(self, confusable):
        # I and L for 1, O for 0 - and U so a random code cannot spell something unfortunate.
        assert confusable not in PASSCODE_ALPHABET

    def test_two_codes_are_not_the_same_code(self):
        assert len({generate_passcode() for _ in range(50)}) == 50

    def test_refuses_to_generate_nothing(self):
        with pytest.raises(ValueError):
            generate_passcode(0)


class TestFormatting:
    def test_groups_it_for_writing_down(self):
        assert format_passcode("H4K29WMR7TQX") == "H4K2-9WMR-7TQX"

    def test_the_hyphens_are_not_part_of_the_password(self):
        code = generate_passcode()

        assert normalise_passcode(format_passcode(code)) == code

    def test_a_length_that_does_not_divide_evenly_still_groups(self):
        assert format_passcode("H4K29") == "H4K2-9"


class TestReadingItBack:
    def test_accepts_the_grouped_form(self):
        assert normalise_passcode("H4K2-9WMR-7TQX") == "H4K29WMR7TQX"

    def test_accepts_lower_case(self):
        assert normalise_passcode("h4k29wmr7tqx") == "H4K29WMR7TQX"

    def test_accepts_spaces_wherever_someone_put_them(self):
        assert normalise_passcode("  H4K2 9WMR\t7TQX \n") == "H4K29WMR7TQX"

    @pytest.mark.parametrize(("typed", "meant"), [("O", "0"), ("I", "1"), ("L", "1"), ("l", "1")])
    def test_folds_the_letters_that_were_left_out_onto_what_they_were_mistaken_for(self, typed, meant):
        # The alphabet excludes these *because* people write them for the digits, so a code read
        # off paper has to survive the substitution rather than be rejected by it.
        assert normalise_passcode(f"H4K29WMR7TQ{typed}") == f"H4K29WMR7TQ{meant}"

    def test_refuses_a_character_that_is_not_in_the_alphabet_at_all(self):
        with pytest.raises(PasscodeError, match="made only of"):
            normalise_passcode("H4K2-9WMR-7TQ!")

    def test_refuses_nothing(self):
        with pytest.raises(PasscodeError, match="No code"):
            normalise_passcode("   ---  ")
