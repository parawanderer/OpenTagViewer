"""
Telling one unnamed accessory from another.

Every answer here is a heuristic, so the tests are mostly about what it does when it is *not*
sure: an accessory described wrongly is worse than one described as the numbers it carries, since
a wrong name is believed and a number gets looked up.

The Android app makes the same judgement in `BeaconInformation`, and the two must agree.
"""

from __future__ import annotations

import pytest

from opentagviewer_export.hardware import (
    AIRTAG_PRODUCT_ID,
    APPLE_VENDOR_ID,
    identify,
    is_own_device,
    where_to_look_up,
)

# The AirTag from the committed export fixture: empty model, and it identifies itself through its
# product and vendor ids instead.
AIRTAG = {
    "model": "",
    "productId": AIRTAG_PRODUCT_ID,
    "vendorId": APPLE_VENDOR_ID,
    "stableIdentifier": ["2001~#001234a12345aaac~#A02BCDEFG1AB"],
}

# The unmatched record from a real account, which turned out to be one of the owner's own iPads.
IPAD = {"model": "iPad13,18", "stableIdentifier": ["a:/00000000-0000-4000-8000-000000000000~#F2LX"]}


class TestAirTags:
    def test_is_recognised_by_its_product_id(self):
        # The same test the Android app makes.
        assert identify(AIRTAG) == "AirTag"

    def test_the_leading_number_of_the_stable_identifier_is_not_used(self):
        # It looks like a kind marker and is not: two accounts show two values - 2001 and 2006 -
        # for the same AirTag shape, so a reader matching on it rejects real ones.
        other_account = {**AIRTAG, "stableIdentifier": ["2006~#001234a12345aaac~#A02BCDEFG1AB"]}

        assert identify(other_account) == "AirTag"

    def test_an_empty_model_does_not_stop_it_being_identified(self):
        assert identify({**AIRTAG, "model": ""}) == "AirTag"


class TestTheOwnersOwnDevices:
    def test_an_ipad_is_named_and_keeps_its_model_identifier(self):
        # "iPad" is what a person recognises; `iPad13,18` is what a search engine answers about.
        assert identify(IPAD) == "iPad (iPad13,18)"

    @pytest.mark.parametrize(("model", "expected"), [
        ("iPhone15,2", "iPhone (iPhone15,2)"),
        ("MacBookPro18,3", "MacBook Pro (MacBookPro18,3)"),
        ("Watch6,18", "Apple Watch (Watch6,18)"),
        ("AudioAccessory5,1", "HomePod (AudioAccessory5,1)"),
    ])
    def test_other_families_are_named_too(self, model, expected):
        assert identify({"model": model}) == expected

    def test_an_unknown_family_falls_back_to_the_identifier(self):
        # Still perfectly identifying, and better than a guess at what "Doohickey3,1" is.
        assert identify({"model": "Doohickey3,1"}) == "Doohickey3,1"

    def test_a_model_that_is_not_an_apple_identifier_is_shown_as_it_is(self):
        assert identify({"model": "Some Tracker v2"}) == "Some Tracker v2"


class TestAirPods:
    def base(self, position: str, model: str = "AirPods") -> dict:
        serial = "48303031".upper()  # "H001" as hex ASCII, the form this field takes
        return {
            "stableIdentifier": [
                f"a:/00000000-0000-4000-8000-000000000000~#\u00b6{model}"
                f"\u00a7hwid\u00a7{serial}\u00a7{position}"
            ],
        }

    @pytest.mark.parametrize(("position", "unit"), [("0", "left"), ("1", "right"), ("2", "case")])
    def test_says_which_one_it_is(self, position, unit):
        # A set is three accessories sharing a group, so "AirPods" three times is exactly the
        # problem this exists to solve.
        assert identify(self.base(position)) == f"AirPods ({unit})"

    def test_an_unknown_position_still_names_the_model(self):
        assert identify(self.base("9")) == "AirPods"

    def test_a_truncated_structure_does_not_lose_the_kind(self):
        truncated = {"stableIdentifier": ["a:/uuid~#\u00b6AirPods\u00a7hwid"]}

        assert identify(truncated) == "AirPods"

    def test_is_checked_before_apple_hardware_in_general(self):
        # AirPods are Apple hardware too, and "Apple" would be true and useless.
        airpods_with_vendor = {**self.base("2"), "vendorId": APPLE_VENDOR_ID}

        assert identify(airpods_with_vendor) == "AirPods (case)"


class TestThirdPartyAccessories:
    THIRD_PARTY = {"stableIdentifier": ["a:/00000000-0000-4000-8000-000000000000~#SERIAL123"]}

    def test_the_a_slash_form_is_a_find_my_accessory(self):
        assert identify(self.THIRD_PARTY) == "Find My accessory"

    def test_is_named_by_its_maker_when_the_registry_knows_them(self):
        # "Chipolo tag" is a great deal more use than "Find My accessory" when three are listed.
        chipolo = {**self.THIRD_PARTY, "vendorId": 0x08C3}

        assert identify(chipolo) == "Chipolo tag"

    def test_a_maker_the_registry_does_not_name_stays_generic(self):
        assert identify({**self.THIRD_PARTY, "vendorId": 0x0999}) == "Find My accessory"


class TestWhenItDoesNotKnow:
    def test_says_what_the_record_holds_rather_than_guessing(self):
        # A wrong product name in a list somebody is choosing from is worse than a number they can
        # search for, so nothing is invented here.
        #
        # 0x0999 is a real, assigned identifier that this project's curated table does not carry,
        # which is exactly the case being tested. The first draft used 0x012D as a stand-in for
        # "nobody knows this one" and it turned out to be Sony - which is the argument for reading
        # the registry rather than trusting a memory of it.
        unknown = {"productId": 4660, "vendorId": 0x0999}

        assert identify(unknown) == "vendor 0x0999 product 0x1234"

    def test_names_apple_when_only_the_vendor_is_recognised(self):
        assert identify({"vendorId": APPLE_VENDOR_ID, "productId": 4660}) == "Apple product 0x1234"

    def test_ignores_the_minus_one_that_means_absent(self):
        # The app records that an iPad carries -1 for both, rather than carrying nothing.
        assert identify({"productId": -1, "vendorId": -1}) is None

    def test_a_record_with_nothing_identifying_gets_nothing_invented(self):
        assert identify({}) is None

    def test_a_known_vendor_is_named_even_when_the_product_is_not(self):
        assert identify({"vendorId": 0x067C, "productId": 4660}) == "Tile product 0x1234"


class TestTellingSomebodyHowToFindOut:
    def test_points_at_the_registry_for_a_vendor_nobody_here_knows(self):
        # Better than a name this module made up: the number is real, the registry is public, and
        # it settles the question in a browser in under a minute.
        advice = where_to_look_up({"vendorId": 0x0999})

        assert advice is not None
        assert "0x0999" in advice
        assert "bluetooth.com" in advice

    def test_says_nothing_when_the_vendor_is_already_named(self):
        assert where_to_look_up({"vendorId": APPLE_VENDOR_ID}) is None

    def test_says_nothing_when_there_is_no_vendor_to_look_up(self):
        assert where_to_look_up({"productId": -1, "vendorId": -1}) is None
        assert where_to_look_up({}) is None


class TestBothRecordShapes:
    def test_reads_a_cloudkit_record_where_the_stable_identifier_is_a_string(self):
        # CloudKit carries one string; the plist carries a list of them. The same function serves
        # a record fetched from an account and one read back out of a bundle.
        as_fetched = {"stableIdentifier": "a:/uuid~#\u00b6AirPods\u00a7hw\u00a748\u00a72"}

        assert identify(as_fetched) == "AirPods (case)"

    def test_reads_a_plist_record_where_it_is_a_list(self):
        assert identify(AIRTAG) == "AirTag"


class TestWhatImportingItCosts:
    def test_identifying_an_accessory_does_not_drag_in_the_bundle_writer(self):
        """
        `opentagviewer_export.hardware` must be importable without PyYAML or pyzipper.

        Writing a bundle needs both; identifying an accessory needs neither, and identifying is
        the only part of this the Android app calls. An eager import in `__init__` would make them
        dependencies of a phone app in exchange for a label on a row.

        Checked in a fresh interpreter rather than in this one, where the test suite has imported
        everything already - an in-process check would pass whether or not the fix worked.
        """
        import subprocess
        import sys

        result = subprocess.run(
            [sys.executable, "-c",
             "import opentagviewer_export.hardware, sys;"
             " print('yaml' in sys.modules, 'pyzipper' in sys.modules)"],
            capture_output=True, text=True, check=True,
        )

        assert result.stdout.strip() == "False False"


class TestTellingDevicesFromAccessories:
    """
    A separate question from "can it be located", and the answer differs.

    A real account returned an iPad and a MacBook Air that both carried key material, so both were
    exportable. Whether they *should* be exported is the user's call - but it is a different act
    from exporting a wallet tag, and the CLI asks before including one.
    """

    def test_an_apple_model_identifier_makes_it_a_device(self):
        assert is_own_device(IPAD) is True

    def test_a_device_secondary_secret_makes_it_one_too(self):
        # An accessory carries `secondarySharedSecret`; an iPhone, iPad or Mac carries this.
        assert is_own_device({"model": "", "secureLocationsSharedSecret": b"\x00" * 32}) is True

    def test_an_airtag_is_not_a_device(self):
        assert is_own_device(AIRTAG) is False

    def test_airpods_are_an_accessory_somebody_bought(self):
        # Their model lives inside stableIdentifier rather than in `model`, so they do not trip
        # the identifier test - and they are not the laptop somebody works on.
        airpods = {"stableIdentifier": ["a:/uuid~#\u00b6AirPods\u00a7hw\u00a748\u00a72"]}

        assert is_own_device(airpods) is False

    def test_a_third_party_tag_is_not_a_device(self):
        assert is_own_device({"stableIdentifier": ["a:/uuid~#SERIAL"], "vendorId": 0x08C3}) is False
