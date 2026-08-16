"""
The part of the iCloud route that can be tested without an account.

Fetching, decrypting and signing in cannot be: they need a real Apple ID, a real device passcode
and Apple's servers, and none of that is faked here. What *is* testable is what happens to the
records afterwards - which accessory is kept, which is set aside and why, and what is written for
one that arrived with no name. That is where an export goes wrong quietly.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone

import pytest
from findmy.cloudkit.beacons import DecryptedRecord

from exporter.icloud import Candidate, ExportSourceError, _candidate, _why_not_locatable, fetch, to_export

BEACON_ID = "725A989D-D871-49A7-B2FE-948C24F356AB"
NAMING_ID = "59AFE75B-8BD8-4522-85BC-F9B0BECD257A"

UUID_V4 = re.compile(r"^[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$")


def beacon_record(**values) -> DecryptedRecord:
    """A decrypted `MasterBeaconRecord`, as the library hands one over."""
    fields = {
        "privateKey": bytes(range(52)),
        "sharedSecret": bytes(range(32)),
        "sharedSecret2": bytes(range(1, 33)),
        "model": "",
        "productId": 21760,
        "vendorId": 76,
        "pairingDate": datetime(2025, 2, 27, 20, 15, 44, tzinfo=timezone.utc),
        "stableIdentifier": "2001~#001234a12345aaac~#A02BCDEFG1AB",
    }
    fields.update(values)
    return DecryptedRecord(name=BEACON_ID, record_type="MasterBeaconRecord", values=fields)


def naming_record(**values) -> DecryptedRecord:
    fields = {"associatedBeacon": BEACON_ID, "name": "cat", "emoji": "\N{CAT}", "roleId": 9}
    fields.update(values)
    return DecryptedRecord(name=NAMING_ID, record_type="BeaconNamingRecord", values=fields)


class TestWhatIsSetAside:
    def test_a_record_with_no_private_key_is_described_as_a_device(self):
        # The test the macOS exporter has always used. An iPad in the zone is normal; exporting it
        # would produce an entry that can never do anything.
        ipad = beacon_record(privateKey=None, model="iPad13,18", secureLocationsSharedSecret=bytes(32))

        reason = _why_not_locatable(ipad)

        assert "cannot be located" in reason
        assert "iPad13,18" in reason

    def test_an_accessory_shaped_record_is_not_guessed_at(self):
        # No model and no `secureLocationsSharedSecret`: nothing here says what it is, so the
        # message says only what is known rather than inventing a taxonomy.
        odd = beacon_record(privateKey=None, model="")

        assert "looks like one of your own devices" not in _why_not_locatable(odd)


class TestRenderingACandidate:
    def test_carries_the_name_and_emoji_the_owner_chose(self):
        candidate = _candidate(beacon_record(), naming_record(), None)

        assert candidate.name == "cat"
        assert candidate.emoji == "\N{CAT}"
        assert candidate.label == "\N{CAT} cat"

    def test_says_when_there_is_no_alignment_record(self):
        # Whoever imports the bundle then searches the tag's whole key history on first locate,
        # which is worth showing at the point of choosing rather than explaining afterwards.
        assert _candidate(beacon_record(), naming_record(), None).has_alignment is False

    def test_an_accessory_with_no_naming_record_has_no_name(self):
        candidate = _candidate(beacon_record(), None, None)

        assert candidate.name is None
        assert candidate.label == "unnamed"

    def test_an_unnamed_accessory_is_described_by_what_it_is_instead(self):
        # "unnamed" three times over is not a list anybody can choose from. Everything needed to
        # tell them apart is already on the record.
        candidate = _candidate(beacon_record(), None, None)

        assert "AirTag" in candidate.details
        assert "serial A02BCDEFG1AB" in candidate.details
        assert "paired 2025-02-27" in candidate.details

    def test_wraps_key_material_the_way_the_plist_format_does(self):
        # Bare bytes produce a file that fails to import for a reason nothing will explain.
        candidate = _candidate(beacon_record(), naming_record(), None)

        assert candidate.owned_beacon["privateKey"] == {"key": {"data": bytes(range(52))}}

    def test_renames_the_secondary_secret_to_what_the_plist_calls_it(self):
        # `sharedSecret2` in CloudKit, `secondarySharedSecret` in the plist: the one rename in the
        # whole mapping.
        candidate = _candidate(beacon_record(), naming_record(), None)

        assert "secondarySharedSecret" in candidate.owned_beacon
        assert "sharedSecret2" not in candidate.owned_beacon


class TestNamingWhatHasNoName:
    def unnamed(self) -> Candidate:
        return _candidate(beacon_record(), None, None)

    def test_refuses_to_export_a_nameless_accessory_silently(self):
        # The importer drops any accessory it cannot pair with a naming record, so exporting one
        # unnamed produces a bundle quietly missing a tag the user selected.
        with pytest.raises(ExportSourceError, match="no name of its own"):
            to_export(self.unnamed())

    @pytest.mark.parametrize("blank", ["", "   "])
    def test_a_blank_name_is_not_a_name(self, blank):
        with pytest.raises(ExportSourceError, match="no name of its own"):
            to_export(self.unnamed(), name=blank)

    def test_a_supplied_name_produces_a_record_the_importer_will_match(self):
        export = to_export(self.unnamed(), name="  wallet  ")

        assert export.naming_record["name"] == "wallet"
        assert export.naming_record["associatedBeacon"] == BEACON_ID
        assert UUID_V4.match(export.naming_record["identifier"])

    def test_invents_nothing_it_cannot_know(self):
        # A synthesised record carries the name it was given and no emoji or role: those are the
        # owner's, and guessing them puts words in their mouth.
        export = to_export(self.unnamed(), name="wallet")

        assert "emoji" not in export.naming_record
        assert "roleId" not in export.naming_record

    def test_an_accessory_that_has_a_name_keeps_its_own_record(self):
        export = to_export(_candidate(beacon_record(), naming_record(), None))

        assert export.naming_record["identifier"] == NAMING_ID
        assert export.naming_record["emoji"] == "\N{CAT}"


class TestFetchIsNotTestedHere:
    def test_fetch_needs_an_account_and_is_not_faked(self):
        """
        Deliberately not tested: `fetch` talks to Apple.

        Faking CloudKit well enough to exercise it would test the fake. What it does *after* the
        network - the grouping, the discard rule, the rendering - is what everything above covers,
        because that is the part that can be wrong without anything failing.
        """
        assert callable(fetch)
