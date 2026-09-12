"""
Every install presenting the same serial is what this stops.

`EXPORTER_SERIAL` was a constant, so every copy of this program anywhere told Apple it was the
same machine - one serial against thousands of device identities and thousands of Apple IDs, from
everywhere, at once. Issues #168, #176 and #181 are a 503 from Grand Slam that some accounts never
recover from, and one reporter cleared their device identity to no effect, which is what would
happen if the serial were the part being matched on.

That is a hypothesis. These tests are about the properties the fix has to have either way.
"""

from __future__ import annotations

import random
import subprocess
import sys
from pathlib import Path

import pytest

from exporter import device, icloud
from exporter.identity import (
    EXPORTER_SERIAL,
    SERIAL_ALPHABET,
    SERIAL_PREFIX,
    generate_serial,
    serial_from,
)


class TestTheShapeAppleAccepts:
    def test_itis_twelve_uppercase_alphanumerics(self):
        serial = generate_serial()

        assert len(serial) == 12
        assert serial.isalnum()
        assert serial == serial.upper()

    def test_itis_recognisable_as_this_project(self):
        """The prefix is the whole reason a user does not remove the entry."""
        assert generate_serial().startswith(SERIAL_PREFIX)

    def test_the_serial_it_replaced_has_the_same_prefix_and_shape(self):
        """
        So a user seeing either one reads the same project.

        It is *not* a member of the drawn set, and cannot be: `PORT` contains an `O`, which the
        alphabet excludes as a confusable. That is worth knowing rather than fixing - a serial
        containing `O` is by construction an install from before this change, and nothing here
        depends on the old value being drawable.
        """
        assert EXPORTER_SERIAL.startswith(SERIAL_PREFIX)
        assert len(EXPORTER_SERIAL) == 12

    def test_the_serial_it_replaced_can_never_be_drawn(self):
        """Pinned, because the docstring above claims it and a widened alphabet would break it."""
        tail = EXPORTER_SERIAL[len(SERIAL_PREFIX):]
        assert any(character not in SERIAL_ALPHABET for character in tail)

    def test_the_alphabet_leaves_out_what_a_reader_would_confuse(self):
        """A person compares this across two screens; that is the only job it has."""
        for confusable in "O0I1S5B8Z2":
            assert confusable not in SERIAL_ALPHABET, confusable


class TestItIsActuallyRandom:
    """
    <b>The failure that would quietly reinstate the constant.</b>

    A generator seeded the same way on every machine hands every fresh install the same serial,
    and nothing downstream would notice: the shape is right, it persists, it looks per-install.
    """

    def test_many_draws_are_almost_all_different(self):
        drawn = [generate_serial() for _ in range(500)]

        assert len(set(drawn)) > 450, "the tail is not varying"

    def test_seeding_the_ordinary_random_module_does_not_pin_it(self):
        """
        `secrets` reads OS entropy and cannot be seeded. Asserted because switching it to
        `random.choice` would pass every other test in this file.
        """
        random.seed(0)
        first = generate_serial()
        random.seed(0)
        second = generate_serial()

        assert first != second

    def test_separate_processes_do_not_agree(self):
        """
        The real case: two people installing on two machines.

        In-process draws share one generator, so they would differ even from a seeded PRNG. Only
        a fresh interpreter shows whether the entropy is per-process.
        """
        script = (
            "import sys; sys.path.insert(0, %r);"
            "from exporter.identity import generate_serial; print(generate_serial())"
            % str(Path(__file__).resolve().parents[1])
        )
        drawn = {
            subprocess.run(
                [sys.executable, "-c", script],
                capture_output=True, text=True, check=True,
            ).stdout.strip()
            for _ in range(5)
        }

        assert len(drawn) == 5, f"separate processes agreed on a serial: {drawn}"


class TestWhichSerialAnInstallPresents:
    def test_a_stored_serial_is_kept(self):
        """Every run after the first. The file exists so this is stable."""
        assert serial_from({"uid": "u", "devid": "d", "serial": "0PENTAGXA3K9"}) == "0PENTAGXA3K9"

    def test_an_install_from_before_this_keeps_what_it_has_been_presenting(self):
        """
        <b>It has an identity and no serial, so it predates serials varying.</b>

        Re-identifying a working install costs a second device-list entry and may cost a sign-in,
        for no benefit to somebody who is not affected.
        """
        assert serial_from({"uid": "u", "devid": "d"}) == EXPORTER_SERIAL

    def test_nothing_stored_draws_a_new_one(self):
        """
        A new install - and also the remedy for an account Apple is refusing.

        Deleting the identity file now draws a different serial instead of the same one, which is
        something a person can do without waiting for a release.
        """
        assert serial_from(None) != EXPORTER_SERIAL
        assert serial_from(None).startswith(SERIAL_PREFIX)

    def test_rubbish_in_the_file_is_treated_as_absent(self):
        for nonsense in ({"serial": ""}, {"serial": None}, {"serial": 12}, {"serial": []}):
            assert serial_from({"uid": "u", "devid": "d", **nonsense}) == EXPORTER_SERIAL


class TestItSurvivesBeingWrittenDown:
    def test_it_round_trips_through_the_identity_file(self, tmp_path):
        path = tmp_path / "device-identity.json"
        device.save("uid-1", "devid-1", {"a": 1}, path, serial="0PENTAGXQ7WM")

        assert serial_from(device.load(path)) == "0PENTAGXQ7WM"

    def test_saving_without_one_does_not_overwrite_what_is_there(self, tmp_path):
        """
        A save from a path that does not know the serial must not blank it.

        The key is omitted rather than written as null, so `serial_from` reads it the same way it
        reads a file from before this existed.
        """
        path = tmp_path / "device-identity.json"
        device.save("uid-1", "devid-1", {"a": 1}, path)

        assert "serial" not in (device.load(path) or {})

    def test_the_identity_file_still_refuses_anything_else(self, tmp_path):
        """
        The guard that keeps credentials out of this file has to still be a guard.

        Adding a key to `_ALLOWED` is exactly when that gets loosened by accident.
        """
        assert "serial" in device._ALLOWED
        for forbidden in ("username", "password", "token", "session"):
            assert forbidden not in device._ALLOWED


class TestItIsWhatActuallyGoesToApple:
    """
    The provider is what sends `X-Apple-I-SRL-NO`, so the drawn serial has to reach *it*.

    Substituting the identity and then building the provider from the old one would leave every
    request presenting the constant while every test about `serial_from` stayed green - which is
    not hypothetical: the line building the provider was dropped while writing this change, and
    nothing in the suite noticed, because every other test passes a provider in.
    """

    def test_an_account_built_without_a_provider_still_gets_one(self, tmp_path):
        """
        `account.serial` reads through the provider, so it is also the check that there is one.

        With the provider left as None this raises rather than returning a wrong serial, which
        is how the dropped line would have presented in the field: the first thing the wizard
        does after building an account is fail on an attribute of None.
        """
        account = icloud.make_account(
            "https://example.invalid", identity_path=tmp_path / "identity.json")

        assert isinstance(account.serial, str)

    def test_and_that_provider_presents_the_drawn_serial(self, tmp_path):
        account = icloud.make_account(
            "https://example.invalid", identity_path=tmp_path / "identity.json")

        assert account.serial.startswith(SERIAL_PREFIX)
        assert account.serial != EXPORTER_SERIAL

    def test_the_first_run_stores_the_serial_it_actually_signed_in_with(self, tmp_path):
        """
        The one that is wrong in the way nobody notices until the second run.

        A first run has no identity file, so anything that re-derives the serial from disk draws
        a *second* one and stores that - and the next run introduces itself to Apple as a
        different device than the one just registered, adding an entry to the user's device list
        every other time they export.
        """
        path = tmp_path / "device-identity.json"
        account = icloud.make_account("https://example.invalid", identity_path=path)
        presented = account.serial

        icloud.remember(account, path)

        assert serial_from(device.load(path)) == presented

    def test_and_the_run_after_that_presents_the_same_one(self, tmp_path):
        path = tmp_path / "device-identity.json"
        first = icloud.make_account("https://example.invalid", identity_path=path)
        icloud.remember(first, path)

        second = icloud.make_account("https://example.invalid", identity_path=path)

        assert second.serial == first.serial

    def test_a_caller_that_names_an_identity_keeps_it(self, tmp_path):
        """The app passes its own, and must not have it replaced by the exporter's."""
        theirs = icloud.ClientIdentity(serial="0PENTAGVK7QX", device_name="OpenTagViewer")

        account = icloud.make_account(
            "https://example.invalid",
            identity=theirs,
            identity_path=tmp_path / "identity.json")

        assert account.serial == "0PENTAGVK7QX"


@pytest.mark.parametrize("draw", range(20))
def test_every_draw_is_within_the_declared_alphabet(draw):
    """A character outside it would be a serial Apple might not accept."""
    assert set(generate_serial()[len(SERIAL_PREFIX):]) <= set(SERIAL_ALPHABET)
