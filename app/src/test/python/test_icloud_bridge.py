"""
Driving an Apple account from the app, without an Apple account.

Everything here runs against fakes standing in for the Find My client. That is not a compromise
for the sake of a test suite - the alternative needs a real Apple ID, a real device with a
passcode and a real escrow record, so the paths that would never be covered are exactly the ones
users meet: the account with nothing to recover from, the service having a bad day, the rejected
passcode.

**What the fakes cannot check is checked elsewhere.** That the module imports at all inside the
APK is `PythonPackagingTest`; that the identity it presents is this app's and not the exporter's
is asserted here *and* pinned on the Java side by `IdentityBridgeTest`.
"""

from __future__ import annotations

import asyncio
import base64
import json
import plistlib
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest

import icloud_bridge
from exporter import icloud
from cryptography.hazmat.primitives.asymmetric import ec
from findmy.keychain.join import JoinedPeer
from findmy.keychain.recovery import RecoveryError


class FakeRecord:
    """An escrow record, as far as this module is concerned: a serial and a description."""

    def __init__(self, serial: str) -> None:
        self.serial = serial

    def describe(self) -> str:
        return f"A device, serial {self.serial}"


class FakeRecoveredPeer:
    """What a recovery yields: the identity a join is sponsored by."""

    def __init__(self, serial: str) -> None:
        self.peer_id = f"peer-for-{serial}"


class FakeOptions:
    def __init__(self, recoverable, trustworthy: bool = True) -> None:
        self.recoverable = recoverable
        self.viability_is_trustworthy = trustworthy


class FakeClient:
    """
    The Find My client, with the two calls the bridge makes on it and the sockets left out.

    `unlock` is the interesting one: it records what it was given, so a test can assert that a
    passcode reached it, and raises whatever it was told to.
    """

    def __init__(self, options=None, unlockError=None) -> None:
        self._options = options or FakeOptions([FakeRecord("F2LX9Q")])
        self._unlockError = unlockError
        self.unlockedWith: list[tuple[str, str]] = []
        self.resumedAs: list = []
        self.joinedWith = None
        self._joinError = None
        self._resumeError = None
        self.entered = False
        self.exited = False

    async def __aenter__(self):
        self.entered = True
        return self

    async def __aexit__(self, *_):
        self.exited = True
        return False

    async def recovery_options(self, *, refresh: bool = False):
        return self._options

    # `unlock` recovers explicitly now - `session.recover` then `client.resume` - because the
    # peer a recovery yields is what sponsors a join, and `client.unlock` keeps it to itself.
    @property
    def session(self):
        return self

    async def recover(self, record, passcode):
        self.unlockedWith.append((record.serial, passcode))
        if self._unlockError is not None:
            raise self._unlockError
        return FakeRecoveredPeer(record.serial)

    async def resume(self, peer, **_):
        self.resumedAs.append(getattr(peer, "peer_id", peer))
        if self._resumeError is not None:
            raise self._resumeError
        return []

    async def join(self, peer, *, passcode, device, os_version):
        self.joinedWith = SimpleNamespace(
            peer=peer, passcode=passcode, device=device, os_version=os_version)
        if self._joinError is not None:
            raise self._joinError
        return SimpleNamespace(
            peer=SimpleNamespace(
                peer_id="peer-ours", to_json=lambda: {"peer_id": "peer-ours"}),
            bottle=SimpleNamespace(entropy=bytes([7]) * 72),
            label="a-label",
            shares=2)


class FakeAccount:
    """
    The app's `AppleAccount`, in the two attributes the bridge reaches for.

    Named after the real private attributes on purpose. If FindMy.py renames either, this fake
    keeps the old names and the tests keep passing while the app breaks - so `openSession`
    guards on both and `testItRefusesAnAccountWhoseInternalsMoved` is what actually covers the
    rename. A fake cannot notice one.
    """

    def __init__(self, loop) -> None:
        self._asyncacc = FakeAsyncAccount()
        self._evt_loop = loop


class FakeAsyncAccount:
    """
    The async account, in the two public things a join reads off it.

    Both are what rule 11 is about: the model and build reach the escrow record's metadata, and
    the serial reaches the peer's stable info, and both are read from the one identity rather
    than composed - a path that invents its own makes one client look like several.
    """

    serial = "0PENTAGVIEWR"
    identity = SimpleNamespace(
        model="iPhone17,1", os_name="iPhone OS", os_version="18.1",
        os_build="22B83", cfnetwork="1568.100.1", darwin="24.1.0")


@pytest.fixture
def loop():
    made = asyncio.new_event_loop()
    yield made
    made.close()


@pytest.fixture
def session(loop, monkeypatch):
    """An opened session over a `FakeClient`, with the client reachable as `session.client`."""

    def open_with(client):
        async def fake_open_client(account, identity=None):
            return client

        monkeypatch.setattr(icloud, "open_client", fake_open_client)

        made = icloud_bridge.openSession(FakeAccount(loop))
        assert json.loads(made.open())["ok"]
        made.client = client

        return made

    return open_with


class TestStartingASession:
    def test_it_uses_the_account_the_app_is_already_signed_in_with(self, loop):
        account = FakeAccount(loop)

        made = icloud_bridge.openSession(account)

        assert made is not None
        assert made._async is account._asyncacc
        assert made._loop is loop

    def testItRefusesAnAccountWhoseInternalsMoved(self, loop):
        """
        FindMy.py renaming `_asyncacc` must degrade, not explode.

        The app reaches into a private attribute here and in `main._preferLocalAnisette`, and
        both guard the same way. Returning None sends the user to sign in again, which is
        wrong but survivable; an AttributeError crossing the Chaquopy boundary is a crash.
        """
        class Moved:
            _evt_loop = None

        assert icloud_bridge.openSession(Moved()) is None

    def test_nothing_can_be_done_before_the_client_is_open(self, loop):
        made = icloud_bridge.openSession(FakeAccount(loop))

        for call in (made.recoveryOptions, lambda: made.unlock("F2LX9Q", "1234"), made.fetch,
                     lambda: made.records("[]")):
            assert json.loads(call())["reason"] == icloud_bridge.REASON_NOT_SIGNED_IN


class TestTheIdentityItPresents:
    """
    Rule 11, at the one place this app talks to CloudKit.

    A default here would present `0PENTAGXPORT` from a phone - the desktop exporter's serial -
    and the two programs would share one device-list entry that neither could be removed from
    without breaking the other.
    """

    def test_it_is_this_app_and_not_the_exporter(self):
        assert icloud_bridge.APP_IDENTITY.serial == "0PENTAGVIEWR"
        assert icloud_bridge.APP_IDENTITY != icloud.EXPORTER_IDENTITY

    def test_the_cloudkit_name_is_set_rather_than_left_to_the_library(self):
        assert icloud_bridge.APP_IDENTITY.device_name
        assert icloud_bridge.APP_IDENTITY.device_name != icloud.EXPORTER_IDENTITY.device_name

    def test_it_is_what_reaches_open_client(self, loop, monkeypatch):
        seen = {}

        async def fake_open_client(account, identity=None):
            seen["identity"] = identity
            return FakeClient()

        monkeypatch.setattr(icloud, "open_client", fake_open_client)
        icloud_bridge.openSession(FakeAccount(loop)).open()

        assert seen["identity"] is icloud_bridge.APP_IDENTITY


class TestWhatCanBeRecoveredFrom:
    def test_the_devices_come_back_with_something_to_choose_between(self, session):
        made = session(FakeClient(FakeOptions([FakeRecord("F2LX9Q"), FakeRecord("C02XK")])))

        answer = json.loads(made.recoveryOptions())

        assert answer["ok"]
        assert [d["serial"] for d in answer["devices"]] == ["F2LX9Q", "C02XK"]
        assert all(d["description"] for d in answer["devices"])

    def test_an_account_with_nothing_to_recover_from_says_so(self, session):
        """
        The real case this whole flow has to answer for.

        Somebody with an Apple ID and no Apple hardware has never escrowed a keychain, so there
        is nothing here for them and no amount of retrying will change it. Telling them to try
        again later would be a lie that costs them an evening.
        """
        made = session(FakeClient(FakeOptions([], trustworthy=True)))

        answer = json.loads(made.recoveryOptions())

        assert not answer["ok"]
        assert answer["reason"] == icloud_bridge.REASON_NOTHING_TO_RECOVER_FROM

    def test_a_service_having_a_bad_day_is_a_different_answer(self, session):
        """
        And the advice is the opposite one, which is why they are two reasons and not one.

        Nothing reported usable *at all* is far more likely an outage than an account where
        every record went bad at once.
        """
        made = session(FakeClient(FakeOptions([], trustworthy=False)))

        answer = json.loads(made.recoveryOptions())

        assert answer["reason"] == icloud_bridge.REASON_SERVICE_UNSURE

    def test_the_two_empty_answers_are_not_the_same_reason(self, session):
        """Belt and braces, because collapsing them is a one-character edit."""
        assert (icloud_bridge.REASON_NOTHING_TO_RECOVER_FROM
                != icloud_bridge.REASON_SERVICE_UNSURE)


class TestUnlocking:
    def test_the_passcode_reaches_the_chosen_record(self, session):
        made = session(FakeClient(FakeOptions([FakeRecord("AAAA"), FakeRecord("BBBB")])))
        made.recoveryOptions()

        assert json.loads(made.unlock("BBBB", "1234"))["ok"]
        assert made.client.unlockedWith == [("BBBB", "1234")]

    def test_a_serial_this_session_never_saw_is_refused_without_asking_apple(self, session):
        """
        Attempts are probably a limited resource, so a mistake here must not spend one.
        """
        made = session(FakeClient())
        made.recoveryOptions()

        answer = json.loads(made.unlock("NOPE", "1234"))

        assert answer["reason"] == icloud_bridge.REASON_NO_SUCH_RECORD
        assert made.client.unlockedWith == []

    def test_a_rejection_carries_the_librarys_own_words(self, session):
        """
        **Not reworded into "incorrect passcode".**

        A rejection is not proof the passcode was wrong - FindMy.py's first advice is to try the
        same one again, because the exchange fails intermittently. Its text says that; ours
        would not.
        """
        rejection = RecoveryError("Rejected. Worth trying the same passcode again first.")
        made = session(FakeClient(unlockError=rejection))
        made.recoveryOptions()

        answer = json.loads(made.unlock("F2LX9Q", "0000"))

        assert answer["reason"] == icloud_bridge.REASON_PASSCODE_REJECTED
        assert "trying the same passcode again" in answer["message"]

    def test_one_attempt_per_call(self, session):
        """
        The retry is Java's, so the cap can live beside the dialog that spends it.

        A loop in here would burn all three attempts behind one press of a button.
        """
        made = session(FakeClient(unlockError=RecoveryError("no")))
        made.recoveryOptions()
        made.unlock("F2LX9Q", "0000")

        assert len(made.client.unlockedWith) == 1


A_KEY = b"\x01" * 28


def _candidate(name=None, alignment=None):
    return icloud.Candidate(
        beacon_id="F1C4A0E2-1111-4222-8333-444455556666",
        name=name,
        emoji="🚲" if name else None,
        has_alignment=alignment is not None,
        owned_beacon={"identifier": "F1C4A0E2", "privateKey": A_KEY, "batteryLevel": 100},
        naming_record=None if name is None else {"name": name, "emoji": "🚲"},
        key_alignment_record=alignment,
        hardware="AirTag",
        serial_number="HXXXXXXXXXXX",
        paired_at=datetime(2024, 3, 1, tzinfo=timezone.utc),
    )


class TestListingWhatIsThere:
    """
    The first half: what the account holds, described well enough to choose from.

    Deliberately without key material - see `testTheListingCarriesNoSecrets`, which is the
    assertion that keeps it that way.
    """

    def test_each_accessory_is_described(self, session, monkeypatch):
        made = session(FakeClient())
        monkeypatch.setattr(icloud, "fetch", _fetching([_candidate(name="Bike")], []))

        answer = json.loads(made.fetch())
        first = answer["accessories"][0]

        assert answer["ok"]
        assert first["beaconId"] == "F1C4A0E2-1111-4222-8333-444455556666"
        assert first["name"] == "Bike"
        assert first["hasName"] is True

    def testTheListingCarriesNoSecrets(self, session, monkeypatch):
        """
        A screen that shows a list of names does not need anybody's private keys.

        Rendering every accessory's key material into a JSON string so that a picker can be
        drawn puts more of the secret in more places than the picker needs, and the user has
        not chosen anything yet. The records come later, for what they picked.
        """
        made = session(FakeClient())
        monkeypatch.setattr(icloud, "fetch", _fetching([_candidate(name="Bike")], []))

        listing = made.fetch()

        assert "privateKey" not in listing
        assert A_KEY.hex() not in listing.lower()
        assert "ownedBeaconPlist" not in listing

    def test_an_accessory_nobody_named_is_flagged_but_not_a_problem(self, session, monkeypatch):
        """
        Nothing here invents a name, and nothing here demands one.

        `to_export` refuses a nameless accessory because a *bundle* is inner-joined by its
        importer, so one exported without a name goes silently missing. That does not apply to
        an account read straight into the app, which left-joins and shows a nameless tag
        perfectly well. The flag is for the picker, where `details` is all a person has to tell
        one unnamed accessory from another.
        """
        made = session(FakeClient())
        monkeypatch.setattr(icloud, "fetch", _fetching([_candidate(name=None)], []))

        first = json.loads(made.fetch())["accessories"][0]

        assert first["hasName"] is False
        assert first["details"], "with no name, this is all the user has to recognise it by"

    def test_what_was_set_aside_is_named_rather_than_dropped(self, session, monkeypatch):
        """
        "Fewer tags than expected" and "some of those were never tags" look identical from
        outside, and the second is the common one - an account's own iPhones come back in the
        same records.
        """
        made = session(FakeClient())
        monkeypatch.setattr(icloud, "fetch", _fetching(
            [], [icloud.Skipped("My MacBook", "no private key, so it is a device not a tag")]))

        answer = json.loads(made.fetch())

        assert answer["accessories"] == []
        assert answer["skipped"][0]["beaconId"] == "My MacBook"
        assert "not a tag" in answer["skipped"][0]["reason"]


def _fetching(candidates, skipped):
    async def fake_fetch(client):
        return icloud.Fetched(candidates=candidates, skipped=skipped)

    return fake_fetch


ANID = "F1C4A0E2-1111-4222-8333-444455556666"


class TestTakingTheRecords:
    """
    The second half: the chosen accessories, as the documents the importer already reads.
    """

    def test_they_arrive_as_the_plists_the_importer_already_reads(self, session, monkeypatch):
        """
        The point of the whole exercise: no second format, and no zip in the middle.

        An accessory read from an account and one read from a bundle become the same rows in
        the same tables, because they arrive as the same documents.
        """
        made = session(FakeClient())
        monkeypatch.setattr(icloud, "fetch", _fetching([_candidate(name="Bike")], []))
        made.fetch()

        first = json.loads(made.records(json.dumps([{"beaconId": ANID}])))["accessories"][0]

        parsed = plistlib.loads(first["ownedBeaconPlist"].encode("utf-8"))
        assert parsed["privateKey"] == A_KEY, "key material must survive as <data>, not repr()"
        assert parsed["batteryLevel"] == 100
        assert plistlib.loads(first["namingRecordPlist"].encode("utf-8"))["name"] == "Bike"

    def test_only_what_was_asked_for(self, session, monkeypatch):
        """The keys of a tag the user did not pick have no business leaving Python."""
        made = session(FakeClient())
        other = icloud.Candidate(
            beacon_id="OTHER", name="Keys", emoji=None, has_alignment=False,
            owned_beacon={"privateKey": b"\x02" * 28}, naming_record={"name": "Keys"},
            key_alignment_record=None)
        monkeypatch.setattr(icloud, "fetch", _fetching([_candidate(name="Bike"), other], []))
        made.fetch()

        answer = made.records(json.dumps([{"beaconId": ANID}]))

        assert len(json.loads(answer)["accessories"]) == 1
        assert (b"\x02" * 28).hex() not in answer.lower()

    def testANamelessAccessoryComesThroughWithNoNamingRecord(self, session, monkeypatch):
        """
        Rather than failing, and rather than being given a name it never had.

        Both alternatives were wrong. Refusing it makes an importable tag unimportable over a
        label; inventing one puts a tag the user did not name into their list as though they
        had. Null is the true answer, and the app already knows what to do with it - it is what
        a self-generated tag looks like, and `UserBeaconOptions` is how anything gets renamed.
        """
        made = session(FakeClient())
        monkeypatch.setattr(icloud, "fetch", _fetching([_candidate(name=None)], []))
        made.fetch()

        answer = json.loads(made.records(json.dumps([{"beaconId": ANID}])))
        first = answer["accessories"][0]

        assert answer["ok"]
        assert first["namingRecordPlist"] is None
        assert first["ownedBeaconPlist"], "the tag itself is entirely fine"

    def test_no_alignment_record_is_null_rather_than_an_empty_document(
            self, session, monkeypatch):
        made = session(FakeClient())
        monkeypatch.setattr(icloud, "fetch", _fetching([_candidate(name="Bike")], []))
        made.fetch()

        first = json.loads(made.records(json.dumps([{"beaconId": ANID}])))["accessories"][0]

        assert first["keyAlignmentPlist"] is None

    def test_an_id_this_session_never_saw_is_refused(self, session, monkeypatch):
        made = session(FakeClient())
        monkeypatch.setattr(icloud, "fetch", _fetching([_candidate(name="Bike")], []))
        made.fetch()

        answer = json.loads(made.records(json.dumps([{"beaconId": "NOPE"}])))

        assert answer["reason"] == icloud_bridge.REASON_NO_SUCH_ACCESSORY

    def testClosingDropsTheDecryptedRecords(self, session, monkeypatch):
        """
        They are decrypted key material, and the session is over.

        Held on the session only so the two halves can be separate calls; keeping them past
        `close` would mean a screen that has gone away can still hand out secrets.
        """
        made = session(FakeClient())
        monkeypatch.setattr(icloud, "fetch", _fetching([_candidate(name="Bike")], []))
        made.fetch()

        made.close()

        assert made._candidates == {}


class TestFailingWithSomethingToShow:
    """
    Every failure has to arrive with words in it.

    The app has already shipped a dialog reading `Login failed:` and then nothing, because
    `str(TimeoutError())` is the empty string. A bare exception crossing the Chaquopy boundary
    does the same thing again, one screen along.
    """

    def test_an_exception_with_no_message_still_produces_one(self, session, monkeypatch):
        made = session(FakeClient())

        async def timing_out(client):
            raise TimeoutError

        monkeypatch.setattr(icloud, "fetch", timing_out)

        answer = json.loads(made.fetch())

        assert not answer["ok"]
        assert answer["reason"] == icloud_bridge.REASON_UNKNOWN
        assert answer["message"].strip(), "an empty message is the bug this exists to prevent"
        assert "TimeoutError" in answer["message"]

    def test_a_failure_to_open_is_a_value_rather_than_a_raise(self, loop, monkeypatch):
        async def refusing(account, identity=None):
            msg = "the keychain service said no"
            raise RuntimeError(msg)

        monkeypatch.setattr(icloud, "open_client", refusing)

        answer = json.loads(icloud_bridge.openSession(FakeAccount(loop)).open())

        assert not answer["ok"]
        assert "the keychain service said no" in answer["message"]

    def test_every_step_answers_in_the_same_shape(self, session, monkeypatch):
        """
        So Java has one parser and not four.

        A step that returned a bare string, or None, on one of its paths would be the one Java
        forgot to handle - and it would be a failure path, which is where nobody looks.
        """
        made = session(FakeClient(FakeOptions([])))
        monkeypatch.setattr(icloud, "fetch", _fetching([], []))

        for call in (made.recoveryOptions, lambda: made.unlock("X", "1"), made.fetch,
                     lambda: made.records(json.dumps([{"beaconId": "X"}])),
                     lambda: made.records("not json at all")):
            answer = json.loads(call())
            assert isinstance(answer["ok"], bool)
            if not answer["ok"]:
                assert answer["reason"] and answer["message"]


class TestClosing:
    def test_it_closes_the_client(self, session):
        made = session(FakeClient())

        made.close()

        assert made.client.exited

    def test_closing_twice_is_not_an_error(self, session):
        made = session(FakeClient())

        made.close()
        made.close()

    def test_a_client_that_will_not_close_does_not_raise(self, session):
        """
        Java calls this from a `finally`. An exception here would replace whatever real failure
        sent it there with a confusing one about closing.
        """
        made = session(FakeClient())

        async def refusing(*_):
            msg = "still busy"
            raise RuntimeError(msg)

        made.client.__aexit__ = refusing
        made.close()


def aStoredMembership() -> dict:
    """A membership as the app would have stored it, with real keys."""
    return JoinedPeer(
        peer_id="peer-ours",
        signing=ec.generate_private_key(ec.SECP384R1()),
        encryption=ec.generate_private_key(ec.SECP384R1()),
    ).to_json()


class TestJoiningTheAccount:
    """
    The one call here that writes, and the reason it is worth writing.

    A non-member reads with view keys it holds a share of, and those keep working - until the
    view keys **roll**, which is expected whenever the circle's membership changes. Only a
    current member is given shares of the new ones, so a non-member goes quietly stale: still
    holding keys, still looking fine, decrypting nothing new. Here that is a map that stopped
    updating for no reason.
    """

    def test_it_cannot_join_before_anything_is_unlocked(self, session):
        made = session(FakeClient())

        answer = json.loads(made.join("a-passcode"))

        assert answer["reason"] == icloud_bridge.REASON_NOT_UNLOCKED
        assert made.client.joinedWith is None, "nothing may be sent without a sponsor"

    def test_it_refuses_an_empty_passcode(self, session):
        """Enrolment refuses it anyway, but failing here says which side got it wrong."""
        made = session(FakeClient())
        made.recoveryOptions()
        made.unlock("F2LX9Q", "1234")

        assert not json.loads(made.join(""))["ok"]
        assert made.client.joinedWith is None

    def test_the_peer_that_was_recovered_is_the_one_that_sponsors(self, session):
        made = session(FakeClient())
        made.recoveryOptions()
        made.unlock("F2LX9Q", "1234")

        assert json.loads(made.join("a-passcode"))["ok"]
        assert made.client.joinedWith.peer.peer_id == "peer-for-F2LX9Q"

    def test_it_describes_itself_with_the_identity_it_already_presents(self, session):
        """
        Rule 11. The model and build reach the escrow record's metadata and the serial reaches
        the peer's stable info - both read from the one identity rather than composed, because
        a path that invents its own makes one client look like several.
        """
        made = session(FakeClient())
        made.recoveryOptions()
        made.unlock("F2LX9Q", "1234")
        made.join("a-passcode")

        device = made.client.joinedWith.device
        assert device.serial == "0PENTAGVIEWR"
        assert device.model == "iPhone17,1"
        assert device.build == "22B83"
        assert made.client.joinedWith.os_version == "18.1"

    def test_the_membership_comes_back_to_be_stored(self, session):
        made = session(FakeClient())
        made.recoveryOptions()
        made.unlock("F2LX9Q", "1234")

        answer = json.loads(made.join("a-passcode"))

        assert answer["peer"] == {"peer_id": "peer-ours"}
        assert answer["label"] == "a-label"
        assert answer["shares"] == 2

    def test_the_entropy_comes_back_too_and_is_not_the_membership(self, session):
        """
        Both are kept, and neither substitutes for the other.

        The membership is how a refresh avoids a passcode. The entropy, with the passcode the
        record was enrolled under, is how the peer is recovered through escrow if the app's
        encrypted store is ever destroyed.
        """
        made = session(FakeClient())
        made.recoveryOptions()
        made.unlock("F2LX9Q", "1234")

        answer = json.loads(made.join("a-passcode"))

        assert len(base64.b64decode(answer["entropy"])) == 72
        assert answer["entropy"] != json.dumps(answer["peer"])

    def test_a_join_that_fails_is_reported_with_words_in_it(self, session):
        made = session(FakeClient())
        made.recoveryOptions()
        made.unlock("F2LX9Q", "1234")
        made.client._joinError = RuntimeError("cuttlefish said no")

        answer = json.loads(made.join("a-passcode"))

        assert not answer["ok"]
        assert "cuttlefish said no" in answer["message"]


class TestReadingAsTheMemberItAlreadyIs:
    def test_it_needs_no_passcode(self, session):
        made = session(FakeClient())

        # A real `JoinedPeer`, serialised the way the app will store it. A hand-written stub
        # would skip the deserialisation this call actually does, which is the half that fails
        # when a stored value comes back damaged.
        answer = json.loads(made.resume(json.dumps(aStoredMembership())))

        assert answer["ok"]
        assert made.client.unlockedWith == [], "resuming must not spend an unlock attempt"

    def test_a_membership_that_no_longer_works_says_which_kind_of_failure_it_is(self, session):
        """
        Its own reason, because the answer is different. The peer may have been removed from the
        account - which is how a user revokes this app - so the way forward is a passcode and a
        fresh join, not retrying with the same stored keys.
        """
        made = session(FakeClient())
        made.client._resumeError = RuntimeError("no such peer")

        answer = json.loads(made.resume(json.dumps(aStoredMembership())))

        assert answer["reason"] == icloud_bridge.REASON_MEMBERSHIP_UNUSABLE
        assert answer["message"].strip()

    def test_rubbish_in_storage_does_not_crash(self, session):
        made = session(FakeClient())

        assert not json.loads(made.resume("not json at all"))["ok"]
