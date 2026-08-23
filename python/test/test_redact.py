"""
Taking personal identifiers out of a log before somebody posts it.

Two properties, and the second is the one that is easy to forget.

**It removes what it claims to.** Every rule here exists because that identifier was observed in a
real log, so a rule that stops matching is a regression with a privacy cost.

**And it leaves the diagnosis intact.** Redaction that eats timestamps, module names, byte counts
or model numbers produces a file nobody can debug from, and the person sending it has no way to
tell. A log stripped to `***` is not safer in any way that matters - it just gets a second request
for the unredacted one.
"""

from __future__ import annotations

import pytest

from exporter.redact import redact, summarise


def clean(text: str) -> str:
    return redact(text)[0]


class TestWhatItRemoves:
    def test_an_apple_id(self):
        assert "someone@example.com" not in clean("Attempting authentication for user someone@example.com")

    def test_a_home_directory_names_a_person(self):
        # Tracebacks are full of these and the account name is very often somebody's real name.
        out = clean('File "/home/kmvaesp/OpenTagViewer/python/exporter/cli.py", line 893')

        assert "kmvaesp" not in out
        assert "OpenTagViewer/python/exporter/cli.py" in out, "the useful half of the path stays"

    @pytest.mark.parametrize("path", [
        "/Users/kmvaesp/Library/Logs/x.log",
        "/home/kmvaesp/x.log",
        r"C:\Users\kmvaesp\AppData\x.log",
    ])
    def test_every_platform_spelling_of_a_home_directory(self, path):
        assert "kmvaesp" not in clean(path)

    def test_a_peer_hash(self):
        line = "Peer SHA256:7Yt2Rg90+i3fSELB8nD25LePIq+Op/V3P18oT8t7qPI= carries no signing key"

        out = clean(line)

        assert "7Yt2Rg90+i3fSEL" not in out
        assert "carries no signing key" in out

    def test_a_device_serial(self):
        assert "BWBPC2K1ZR" not in clean("iPad, iPad Pro, serial BWBPC2K1ZR, escrowed 2024-03-11")

    def test_the_name_somebody_gave_their_phone(self):
        # The model beside it is not personal and is often the point of the line, so it stays.
        out = clean("  1. Sam's iPhone, iPhone15,2, serial BWBPC2, escrowed 2024-03-11")

        assert "Sam" not in out
        assert "iPhone15,2" in out

    def test_keychain_attributes_printed_verbatim(self):
        # What Apple puts in these is Apple's choice, so the safe assumption is that it identifies
        # somebody.
        out = clean("Service key item: class='genp', acct='someone@example.com', labl='Home WiFi'")

        assert "someone@example.com" not in out
        assert "Home WiFi" not in out

    def test_record_identifiers(self):
        assert "725A989D" not in clean("Record 725A989D-D871-49A7-B2FE-948C24F356AB carries a field")


class TestTheThingsOnlyTheAppProduces:
    """
    Locations, place names, bundle passwords and the number Apple texts a code to.

    None of these appear in a wizard log - the exporter never sees a coordinate or a password.
    They are here rather than in a second redactor because two sets of rules mean two answers to
    "is my data in this file", and only one of them would get maintained.
    """

    @pytest.mark.parametrize("line", [
        "tapped, point=(51.5074, -0.1278)",
        "Error reverse geocoding location: 51.5074, -0.1278",
        "Got geocoding data for 51.5074,-0.1278 (rounded) from cache!",
        "lat=51.5074 lon=-0.1278",
        "latitude: 51.5074, longitude: -0.1278",
    ])
    def test_a_coordinate_is_somebodys_home(self, line):
        # Four decimal places is about eleven metres. This is the most personal thing the app
        # handles, and it is the one identifier that is not a name or a number but a place.
        out = clean(line)

        assert "51.5074" not in out
        assert "0.1278" not in out

    def test_the_line_is_still_readable_around_it(self):
        out = clean("tapped, point=(51.5074, -0.1278)")

        assert out.startswith("tapped, point=(")
        assert out.endswith(")")

    def test_a_resolved_place_name(self):
        # Nothing logs one today. The rule is here so that the day somebody adds
        # `Log.d(TAG, "resolved to " + address)`, nobody has to remember to come back here.
        out = clean("resolved to address=221B Baker Street, London NW1 6XE")

        assert "Baker Street" not in out
        assert "NW1" not in out

    def test_a_bundle_passcode(self):
        # Whoever holds an export and its code can locate those tags indefinitely - unpairing is
        # the only way to withdraw it. A password in a log is not the same risk as an identifier.
        out = clean("Trying passcode 4RTZ-9KMX-P2W7 against the bundle")

        assert "4RTZ" not in out
        assert "P2W7" not in out

    def test_the_number_apple_would_text(self):
        assert "900123" not in clean("Option: SMS (+44 7700 900123)")


class TestItDoesNotEatTheLogLookingForCoordinates:
    """
    A coordinate is two decimals side by side, and so is half of everything else in a log.

    This is the rule most likely to over-match, and over-matching is not a cosmetic problem: a
    redactor that removes the durations and version numbers is one people stop using, and then
    they post the raw log instead.
    """

    @pytest.mark.parametrize("line", [
        # **The one that actually exercises the boundary.** Two adjacent floats is the shape
        # the rule keys on; what saves this line is that neither has three decimal places.
        # Without a case like it the guard passes for the wrong reason - the first version of
        # this list was floats with words between them, which the rule would never have matched
        # however loose it was, and loosening it to \d+ left every test green.
        "Render scale factors 1.25, 0.75 applied",
        "Fetch took 1.25 seconds, parse took 0.5",
        "FindMy 0.9.1, anisette 3.0",
        "Key search window is 290 indices wide",
        "Zone ProtectedCloudStorage fully synced after page 1",
        "Reading the Manatee view with classA: 64 bytes",
        "escrowed 2024-03-11",
    ])
    def test_what_is_not_a_coordinate(self, line):
        assert clean(line) == line

    @pytest.mark.parametrize("line", [
        "Beacon ID F2LX9QABCDEF has 12 reports",
        "SHA256 fingerprint check passed",
        "version 1.1.0-beta",
    ])
    def test_what_is_not_a_passcode(self, line):
        assert clean(line) == line


class TestWhatItKeeps:
    """
    A log that cannot be read is not a safer log, it is a second round trip.
    """

    @pytest.mark.parametrize("line", [
        "INFO     findmy.keychain.session: Reading the Manatee view with classA: 64 bytes",
        "DEBUG    findmy.cloudkit.client: Zone ProtectedCloudStorage fully synced after page 1",
        "INFO     findmy.keychain.items: View holds 61 item(s) and 29 pointer(s)",
        "2026-08-16 12:09:48,557 WARNING  exporter.privacy: ====",
        "DerError: Truncated DER: element claims 109 bytes, 61 remain",
        "The Manatee view holds 141 elliptic-curve key(s)",
    ])
    def test_the_lines_that_make_a_log_worth_having(self, line):
        assert clean(line) == line

    def test_a_traceback_still_points_at_code(self):
        out = clean('  File "/home/kmvaesp/x/findmy/keychain/servicekey.py", line 297, in service_keys_from_der')

        assert "servicekey.py" in out
        assert "line 297" in out
        assert "service_keys_from_der" in out


class TestTheSameValueGetsTheSameName:
    """
    Numbered rather than blanked, because "these two lines are about one device" is often the
    whole diagnosis - and blanket `***` destroys exactly that.
    """

    def test_one_value_twice_is_one_placeholder(self):
        out = clean("serial ABC123 said no\nserial ABC123 said no again\n")

        assert out.count("<serial-1>") == 2

    def test_two_values_are_told_apart(self):
        out = clean("serial ABC123 here\nserial XYZ789 there\n")

        assert "<serial-1>" in out
        assert "<serial-2>" in out

    def test_the_count_is_of_distinct_values(self):
        # What a person checks the result against: two devices on a two-device account is right,
        # and ten is a rule matching too much.
        _, counts = redact("serial ABC123 x\nserial ABC123 y\nserial XYZ789 z\n")

        assert counts["serial"] == 2


class TestSayingWhatHappened:
    def test_it_names_what_was_replaced(self):
        _, counts = redact("user a@b.com and serial ABC123")

        assert summarise(counts) == "Replaced 1 email, 1 serial."

    def test_finding_nothing_is_said_plainly(self):
        # Not silence: "nothing was found" and "the redactor did not run" look identical otherwise,
        # and only one of them is fine.
        assert "Nothing recognisable" in summarise(redact("View holds 61 item(s)")[1])


class TestItDoesNotInventIdentifiers:
    def test_an_empty_field_is_left_alone(self):
        out = clean("Service key item: acct='', labl='x'")

        assert "acct=''" in out, "an empty attribute must not become a fake identifier"
