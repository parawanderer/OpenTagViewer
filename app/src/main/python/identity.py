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
    **Changing the serial adds an entry rather than renaming one**, and may require signing in
    again: Apple binds a session to the identity that established it. Not a thing to adjust once
    it has shipped.
"""

from __future__ import annotations

APP_SERIAL = "0PENTAGVIEWR"
"""
The serial this app presents, in `X-Apple-I-SRL-NO`.

Twelve uppercase alphanumeric characters, which is the shape Apple accepts, and deliberately
implausible as real hardware so nothing mistakes it for a Mac. It shares its prefix with the
exporter's `0PENTAGXPORT` so a user seeing both recognises them as the same project.

Without this the app presents FindMy.py's default, `0FINDMYPY001` - which names the library
rather than the program, in the one place the user ever looks.
"""

KNOWN_IDENTITY_MISMATCH = (
    "<MacBookPro13,2> <macOS;13.1;22C65>",
    "<MacBookPro18,3> <Mac OS X;13.4.1;22F8>",
)
"""
**The one part of the identity that does not agree with itself yet.**

Rule 11 requires the model, OS version and build to describe one real release. They do not:

- `AdiDeviceIdentity.CLIENT_INFO` on the Java side sends the first of these, a 2016 13-inch
  MacBook Pro on macOS 13.1.
- FindMy.py sends the second from `reports/anisette.py` and `reports/account.py` - a 2021 14-inch
  M1 Pro on 13.4.1. Note even the OS *name* differs, `macOS` against `Mac OS X`.

So one app claims to be two different Macs in one session, which is the contradiction rule 11
exists to prevent. It is recorded here rather than fixed because the second string is hardcoded
inside the library - `reports/anisette.py` and `reports/account.py` both carry a copy - and there
is nowhere to pass one in. A request to expose it has gone to FindMy.py, the sibling of the one
that produced `serial=`.

**Do not "fix" this by editing the Java string to match.** That changes the login identity, which
costs every existing user a sign-in and leaves a stale entry in their device list - the same cost
as the serial change, and worth paying once rather than twice. When the library can be told, both
move together in one deliberate change.

This constant is not sent anywhere. It exists so the discrepancy has a name and a test.
"""
