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
