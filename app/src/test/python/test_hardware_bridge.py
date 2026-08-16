"""
The app's side of the shared hardware heuristic.

The heuristic itself is tested in `python/opentagviewer_export/tests/test_hardware.py`. What is
tested here is the bridge: that the app can reach it at all, that it reads the plist the app
actually stores, and that a failure costs a label rather than an accessory.
"""

from __future__ import annotations

import pytest

import main

PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>identifier</key><string>725A989D-D871-49A7-B2FE-948C24F356AB</string>
    <key>model</key><string></string>
    <key>productId</key><integer>21760</integer>
    <key>vendorId</key><integer>76</integer>
    <key>stableIdentifier</key><array><string>2001~#001234a12345aaac~#A02BCDEFG1AB</string></array>
</dict>
</plist>
"""


def plist_with(body: str) -> str:
    return PLIST.replace(
        "<key>productId</key><integer>21760</integer>\n    <key>vendorId</key><integer>76</integer>",
        body,
    )


class TestIdentifying:
    def test_recognises_an_airtag_from_the_plist_the_app_stores(self):
        assert main.identifyHardware(PLIST) == "AirTag"

    def test_recognises_one_of_the_owners_own_devices(self):
        as_ipad = plist_with("<key>productId</key><integer>-1</integer>").replace(
            "<key>model</key><string></string>", "<key>model</key><string>iPad13,18</string>")

        assert main.identifyHardware(as_ipad) == "iPad (iPad13,18)"

    def test_says_nothing_rather_than_guessing_when_it_does_not_know(self):
        bare = plist_with("<key>productId</key><integer>-1</integer>")

        assert main.identifyHardware(bare) is None

    def test_a_broken_plist_costs_a_label_and_not_an_accessory(self):
        # This is decoration on a row in a list. It must never be the reason a tag fails to load.
        assert main.identifyHardware("not a plist at all") is None


class TestPointingAtTheRegistry:
    def test_offers_the_lookup_for_a_maker_nobody_here_knows(self):
        unknown = plist_with(
            "<key>productId</key><integer>1</integer>\n    <key>vendorId</key><integer>2457</integer>")

        advice = main.whereToLookUpHardware(unknown)

        assert advice is not None
        assert "0x0999" in advice

    def test_says_nothing_for_a_maker_it_names(self):
        assert main.whereToLookUpHardware(PLIST) is None

    def test_a_broken_plist_is_not_fatal_here_either(self):
        assert main.whereToLookUpHardware("not a plist at all") is None
