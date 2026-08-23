"""
The app building an export bundle of its own.

**A third producer of the format**, beside the desktop wizard and its CLI. It writes through the
same ``opentagviewer_export.build_export`` they do, so what is tested here is the bridge - the
join from what the app stores to what that function wants - and not the format, which has its own
tests in ``python/opentagviewer_export/tests/``.

Two things about that join can go wrong quietly, and both are covered below. A plist is not UTF-8
and carries raw key material, so anything lossy in the crossing produces a bundle that imports and
then locates nothing. And ``via:`` is what tells anybody looking at a zip afterwards which of the
three programs built it, so a bundle that lies about that makes a bug report unanswerable.
"""

from __future__ import annotations

import base64
import datetime
import json
import plistlib
from pathlib import Path

import pytest

import main

FIXTURE = Path(__file__).resolve().parents[2] / "test" / "resources" / "19032025"
BEACON_ID = "F612A183-492B-45A8-A5A2-233CA9062A94"

VIA = "OpenTagViewer.android:1.1.0"


@pytest.fixture
def one_accessory() -> str:
    """A real accessory, in the shape the app holds it: two plists as XML strings."""
    owned = (FIXTURE / "OwnedBeacons" / f"{BEACON_ID}.plist").read_text(encoding="utf-8")
    naming = next(
        (FIXTURE / "BeaconNamingRecord" / BEACON_ID).glob("*.plist"),
    ).read_text(encoding="utf-8")

    return json.dumps([{"ownedBeaconPlist": owned, "namingRecordPlist": naming}])


def answer(accessories: str, **kwargs) -> dict:
    """The whole reply: `entries` on success, `error` on refusal, `warning` when something was
    left out."""
    defaults = {"via": VIA, "sourceUser": "someone", "exportedAtMs": 1_700_000_000_000}
    defaults.update(kwargs)

    return json.loads(main.buildExportBundle(accessories, **defaults))


def built(accessories: str, **kwargs) -> dict:
    """
    Just the entries, and it insists the build actually succeeded.

    **Asserting that first is not ceremony.** The alignment test below originally reported "an
    alignment record was dropped" when what had really happened was the whole export being
    refused - a helper that hands back an empty mapping on failure makes every assertion about
    content quietly vacuous, and the message points at the wrong thing.
    """
    reply = answer(accessories, **kwargs)

    assert "error" not in reply, reply.get("error")
    return reply["entries"]


class TestWhatItProduces:

    def test_thebundleCarriesTheThreeThingsAnImportNeeds(self, one_accessory):
        entries = built(one_accessory)

        assert "OPENTAGVIEWER.yml" in entries
        assert f"OwnedBeacons/{BEACON_ID}.plist" in entries
        assert any(name.startswith(f"BeaconNamingRecord/{BEACON_ID}/") for name in entries)

    def test_itsaysTheAppProducedIt(self, one_accessory):
        # **Not the wizard and not the CLI.** Three programs write this format, and `via:` is the
        # only thing in a zip that says which - so a bug report about a bundle is answerable or
        # not depending on this line being right.
        manifest = base64.b64decode(built(one_accessory)["OPENTAGVIEWER.yml"]).decode("utf-8")

        assert f"via: {VIA}" in manifest

    def test_thekeyMaterialSurvivesTheCrossing(self, one_accessory):
        # The failure this guards is not a crash. A plist carries the private key, and anything
        # lossy between Python and Java produces a bundle that imports cleanly and then cannot
        # locate the tag - discovered days later, by the recipient.
        entries = built(one_accessory)

        written = plistlib.loads(base64.b64decode(entries[f"OwnedBeacons/{BEACON_ID}.plist"]))
        original = plistlib.loads(
            (FIXTURE / "OwnedBeacons" / f"{BEACON_ID}.plist").read_bytes())

        assert written["privateKey"] == original["privateKey"]

    def test_everyEntryIsBase64AndDecodes(self, one_accessory):
        for name, content in built(one_accessory).items():
            assert base64.b64decode(content), f"{name} decoded to nothing"


class TestWhatItRefuses:
    """
    Handed back as a message rather than raised. The caller shows it, and a Chaquopy traceback is
    not a sentence anybody can act on.
    """

    def test_anemptySelectionIsRefusedWithAReason(self):
        reply = answer("[]")

        assert "error" in reply
        assert "Nothing was selected" in reply["error"]

    def test_ablankViaIsRefused(self, one_accessory):
        # build_export refuses to invent one, and this bridge must not invent one either: a
        # bundle that cannot say what produced it is the thing `via:` exists to prevent.
        reply = answer(one_accessory, via="")

        assert "error" in reply

    def test_somethingThatIsNotAPlistDoesNotEscapeAsATraceback(self):
        reply = answer(json.dumps([
            {"ownedBeaconPlist": "not a plist", "namingRecordPlist": "nor this"},
        ]))

        assert "error" in reply
        assert "could not be built" in reply["error"]


class TestTheAlignmentRecord:
    """
    Optional, and worth passing whenever there is one - see AGENTS.md rule 6. Without it the
    recipient's first fetch searches the tag's whole key history, which for an 18-month-old tag is
    ~50,000 keys at Apple's ~290-per-request limit: slow enough to look like abuse of the account.
    """

    def with_alignment(self, one_accessory: str, alignment: dict) -> str:
        accessory = json.loads(one_accessory)[0]
        accessory["alignmentPlist"] = plistlib.dumps(alignment).decode()
        return json.dumps([accessory])

    def test_itisCarriedWhenTheAppHasOne(self, one_accessory):
        entries = built(self.with_alignment(one_accessory, {
            "identifier": "9A1D4C22-6E3B-4F7A-9C88-1B2E5D6F0A34",
            "beaconIdentifier": BEACON_ID,
            "lastIndexObserved": 12,
            "lastIndexObservationDate": datetime.datetime(
                2025, 3, 20, tzinfo=datetime.timezone.utc),
        }))

        assert any("KeyAlignmentRecord" in name for name in entries), \
            "an alignment record the app held was dropped on the way out"

    def test_anunusableOneCostsTheOptimisationAndNotTheExport(self, one_accessory):
        """
        **The format layer refuses these and says what to do about it** - "pass no alignment
        record at all rather than an unreadable one: the import is then slow, not broken". So the
        bridge acts on that rather than relaying it. Refusing a whole export because an optional
        record is malformed trades something that works badly for nothing at all.
        """
        reply = answer(self.with_alignment(one_accessory, {"index": 12}))

        assert "error" not in reply, "a bad optional record killed the whole export"
        assert reply["entries"], "nothing was written"
        assert not any("KeyAlignmentRecord" in name for name in reply["entries"])
        assert "warning" in reply, "it dropped the record without saying so"

    def test_arealProblemIsStillReportedRatherThanSwallowed(self):
        """The retry must not turn a broken accessory into a silent partial export."""
        reply = answer(json.dumps([{
            "ownedBeaconPlist": plistlib.dumps({"identifier": BEACON_ID}).decode(),
            "namingRecordPlist": plistlib.dumps({"identifier": BEACON_ID}).decode(),
            "alignmentPlist": plistlib.dumps({"index": 1}).decode(),
        }]))

        assert "error" in reply
        assert "entries" not in reply

    def test_itsabsenceIsNormalRatherThanAnError(self, one_accessory):
        entries = built(one_accessory)

        assert not any("KeyAlignmentRecord" in name for name in entries)
