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

from typing import Any

from findmy.reports.anisette import DeviceIdentity

APP_SERIAL = "0PENTAGVIEWR"
"""
The serial a new session presents, in `X-Apple-I-SRL-NO`.

Twelve uppercase alphanumeric characters, which is the shape Apple accepts, and deliberately
implausible as real hardware so nothing mistakes it for a Mac. It shares its prefix with the
exporter's `0PENTAGXPORT` so a user seeing both recognises them as the same project.

Without this a session presents FindMy.py's default, `0FINDMYPY001` - which names the library
rather than the program, in the one place the user ever looks.
"""

APP_IDENTITY = DeviceIdentity(
    model="MacBookPro13,2",
    os_name="macOS",
    os_version="13.1",
    os_build="22C65",
    cfnetwork="1404.0.5",
    darwin="22.2.0",
)
"""
The machine a new session claims to be - **the same one ADI is initialised with**.

This is the whole point of it. `AdiDeviceIdentity.CLIENT_INFO` on the Java side sends
`<MacBookPro13,2> <macOS;13.1;22C65>` when it provisions ADI, and FindMy.py used to send a
MacBook Pro 18,3 on macOS 13.4.1 from its own defaults. One app, one session, two different
Macs - down to the OS *name*, `macOS` against `Mac OS X`. Rule 11 exists to prevent exactly
that, and until FindMy.py grew `DeviceIdentity` there was nowhere to say otherwise.

**Moved to match Java rather than the other way round**, because the ADI string is the half
that is also baked into how ADI was provisioned on the device.

The six parts describe one real release and move together: macOS 13.1 is build 22C65,
CFNetwork 1404.0.5, Darwin 22.2.0. Nothing validates that - FindMy.py has no copy of Apple's
release table - so changing one means changing all six deliberately.
"""

# **There is deliberately no device name here, and no announce_device() call.**
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
