"""
What this app tells Apple it is.

Signing in registers a device in the user's Apple account. They see it in their device list, with
a *Remove from Account* button beside the words "If you do not recognise this device" - so the
identity here is not an implementation detail, it is a row a person reads and acts on. This is
rule 11 in AGENTS.md, and `python/exporter/identity.py` is the same file for the desktop exporter.

**The app and the exporter are two devices, deliberately.** Different installs with different
lifetimes; one entry covering both would mean removing it - or a re-login - silently taking out
the other. Two entries sharing a prefix sort together and read as one project, while each can be
recognised and removed on its own.

.. warning::
    **This applies to new sessions only.** Apple binds a session to the identity that
    established it, so handing this to an account that was signed in under FindMy.py's default
    would cost that user a sign-in and leave a second, unrecognisable entry in their device
    list. Somebody already signed in keeps what they have - see `identityForRestore`.
"""

from __future__ import annotations

import json
import traceback
from typing import Any

from findmy.reports.anisette import DeviceIdentity

IDENTITY_FIELDS = frozenset(
    {"model", "os_name", "os_version", "os_build", "cfnetwork", "darwin"}
)
"""
The six fields Java has to send, checked before they are used.

`DeviceIdentity.from_json` fills a missing key from FindMy.py's *own* identity rather than
failing, which is right for reading back an identity written by an older version and wrong
here: a renamed key on either side would quietly produce a machine that is part this app and
part the library - a MacBook Pro 18,3 on macOS 13.1, a release that does not exist. Checking
first turns that into a visible fallback instead of an invisible hybrid.
"""

APP_SERIAL = "0PENTAGVIEWR"
"""
The serial a new session presents, in `X-Apple-I-SRL-NO`.

Twelve uppercase alphanumeric characters, which is the shape Apple accepts, and deliberately
implausible as real hardware so nothing mistakes it for a Mac. It shares its prefix with the
exporter's `0PENTAGXPORT` so a user seeing both recognises them as the same project.

Without this a session presents FindMy.py's default, `0FINDMYPY001` - which names the library
rather than the program, in the one place the user ever looks.
"""

APP_CLOUDKIT_DEVICE_NAME = "OpenTagViewer"
"""
What this app calls itself *to CloudKit*, in the `AsyncCloudKitClient` device name.

**Not the device-list name**, despite reading like one - see the note below on why that entry
cannot be named. This is a separate field on a separate service, sent when the app reads the
account's beacon records, and it is not optional: `AsyncCloudKitClient` takes a string, so a
client that set nothing here would be named by the library instead. That is the same
second-identity problem as the serial, one layer down.

Distinct from the exporter's `OpenTagViewer Exporter` for the same reason `APP_SERIAL` is
distinct from `0PENTAGXPORT`: two programs, two devices, deliberately.
"""

# **There is deliberately no device-list name here, and no announce_device() call.**
#
# Naming the device-list entry needs `announce_device()`, which authenticates with the
# `com.apple.gs.idms.hb` heartbeat token. That token arrives once, in the same set as the PET,
# and an account serialised before FindMy.py started keeping it has nothing to announce with -
# so for the installed base it does not work, and getting it to work means those users signing
# in again. Not worth a re-login for a label.
#
# The serial carries the recognisability on its own: an entry reading `0PENTAGVIEWR` is
# identifiable as software the user installed, which is the thing that stops them removing it.
# The row is still titled after the claimed model, and that is accepted.


def hardwareProfile(localAnisette: Any) -> DeviceIdentity | None:
    """
    The machine this install claims to be, **read from Java rather than decided here**.

    This used to be a constant, and a constant is wrong for a reason that only shows up in one
    case. Java persists the profile per install: an install that predates the choice keeps the
    Mac it has always claimed, and a fresh one is an iPhone. So there is no single right answer
    for the Python side to hold - somebody signed in before all this, who signs out and signs
    back in, must present the Mac their ADI was provisioned with, while the install beside
    theirs must present the iPhone. A copy here would be right for one of them.

    It is also the contradiction rule 11 is about. The same six values reach Apple twice: once
    in the client info Java sends when it provisions ADI, and once in the client info FindMy.py
    sends at login. Apple's own clients never disagree with themselves about which machine they
    are, and two values maintained in two languages disagree eventually.

    Returns None when there is nothing to ask or the answer is unusable, which leaves FindMy.py
    on its own identity. That is a real mismatch, and worth saying so loudly - but it is a
    mismatch on a *new* sign-in, which costs a device-list entry that is wrong rather than a
    session that stops working, and failing the login outright would be worse.
    """
    if localAnisette is None:
        # No bridge at all: nothing to align with, so nothing to impose.
        return None

    try:
        payload = json.loads(str(localAnisette.hardwareProfileJson()))
    except Exception:
        print(f"Could not read the hardware profile from Java: {traceback.format_exc()}")
        return None

    missing = IDENTITY_FIELDS - payload.keys()
    if missing:
        print(
            "The hardware profile from Java is missing "
            f"{sorted(missing)} - falling back to FindMy.py's own identity, which will not "
            "match what ADI was provisioned with. AdiDeviceIdentity.Hardware.toJson and "
            "DeviceIdentity have drifted apart."
        )
        return None

    return DeviceIdentity.from_json(payload)


def identityForNewSession(localAnisette: Any) -> dict:
    """
    The identity a **new** sign-in presents: this app's serial, and Java's machine.

    Only for a new sign-in. Everything restored from a stored account keeps whatever it was
    established with - see `identityForRestore`, and the warning at the top of this module.

    The two halves are not the same kind of thing, which is why only one of them is asked for.
    The serial is a label, chosen by this app and free to be the same on every install. The
    machine is not a choice at all: it has to match what this particular install already told
    Apple during ADI provisioning, so it is read rather than decided.

    Returns keyword arguments for the provider, so a library that grows another identity field
    fails loudly here instead of silently dropping it.
    """
    kwargs: dict = {"serial": APP_SERIAL}

    identity = hardwareProfile(localAnisette)
    if identity is not None:
        kwargs["identity"] = identity

    return kwargs


def deviceIdsForNewSession(localAnisette: Any) -> dict:
    """
    The two ids this installation **already introduced itself to Apple with**.

    ADI provisioning is its own exchange, made before FindMy.py is involved, and it carries
    `X-Mme-Device-Id` and `X-Apple-I-MD-LU`. Without these FindMy.py mints a fresh random pair,
    so one install talks to Apple as two devices - and `X-Mme-Device-Id` is the field Apple is
    most likely to correlate on, since identifying a particular installation is what it is for.

    **The uid is passed as Java stores it, not as Java sends it.** FindMy.py base64-encodes it
    on the way out; handing over the encoded form would encode it twice and produce a value
    nobody has ever seen. Which of the two conventions Java's own header used is decided per
    hardware profile - see `AdiDeviceIdentity.Hardware#localUserHeader`. A fresh install agrees
    on both. An install from before profiles existed provisioned under the raw convention, which
    cannot be reproduced through base64, so for those two the device id aligns and the local
    user id does not; passing it anyway at least keeps it stable across sign-ins.

    Both or neither. FindMy.py raises on one of two, deliberately: a client matching one id and
    minting the other is a shape no real client produces, and looks worse than matching neither.

    New sessions only, like everything else here. A restored account keeps the pair it was
    established with, and `state_info` wins over these upstream in any case.
    """
    if localAnisette is None:
        return {}

    try:
        payload = json.loads(str(localAnisette.deviceIdsJson()))
        ids = {"uid": payload["uid"], "devid": payload["devid"]}
    except Exception:
        print(f"Could not read this installation's ids from Java: {traceback.format_exc()}")
        return {}

    if not all(ids.values()):
        print(f"Java gave an incomplete pair of ids ({ids}) - letting FindMy.py mint its own.")
        return {}

    return ids


def identityForRestore(previous: Any) -> dict:
    """
    The identity a *restored* account should keep: whatever it already had.

    **Not this app's.** A session signed in before any of this was bound to FindMy.py's
    defaults, and handing it `APP_SERIAL` now would present Apple with a different machine on
    an existing session - a sign-in for the user, and a second device-list entry they did not
    ask for. The gain would be a nicer name on an entry they have already learned to recognise.

    So the identity is read off the provider FindMy.py rebuilt from the stored account, and
    carried across unchanged. That matters because this is called while swapping the Anisette
    *transport* - local for remote - and **swapping the transport must not change the machine**.
    Rule 4 says the same thing from the other side: local and remote Anisette present different
    identities, and changing identity requires a re-login.

    Returns the keyword arguments to construct the replacement provider with, so a library that
    grows another identity field fails here loudly rather than silently dropping it.
    """
    kwargs: dict = {}

    serial = getattr(previous, "serial", None)
    if serial is not None:
        kwargs["serial"] = serial

    identity = getattr(previous, "identity", None)
    if identity is not None:
        kwargs["identity"] = identity

    return kwargs
