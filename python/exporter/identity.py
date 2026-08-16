"""
What this exporter tells Apple it is.

Signing in registers a device in the user's Apple account. They see it in their device list, with
a *Remove from Account* button beside the words "If you do not recognise this device" - so the
identity here is not an implementation detail, it is a row a person reads and acts on.

Three things follow, and they are rule 11 in AGENTS.md:

- **One source of truth.** A path that needs the identity reads it from here rather than composing
  its own. Paths that disagree do not fail; they register *separate devices*, and the user is
  invited to remove things they cannot identify - which breaks the session that was working.
- **The parts must agree with each other.** Model, OS version, build, CFNetwork and Darwin
  describe one real release. FindMy.py's default identity is a MacBook Pro on macOS 13.4.1, and
  this does not touch it: the library is internally consistent already, and a second opinion about
  what kind of Mac this is would be exactly the contradiction Apple's own clients never produce.
- **The serial is a label**, and the only field here the user actually sees.

**The app and this are two devices, deliberately.** OpenTagViewer on Android is a different
install with a different lifetime, and one entry covering both would mean removing it - or a
re-login - silently taking out the other. Two entries sharing a prefix sort together in the device
list and read as one project, while each can be recognised and removed on its own.

.. warning::
    **Changing the serial adds an entry rather than renaming one**, and may require signing in
    again: Apple binds a session to the identity that established it. It is not a thing to adjust
    once it has shipped.
"""

from __future__ import annotations

EXPORTER_SERIAL = "0PENTAGXPORT"
"""
The serial this exporter presents, in `X-Apple-I-SRL-NO`.

Twelve uppercase alphanumeric characters, which is the shape Apple accepts, and deliberately
implausible as real hardware so that nothing mistakes it for a Mac. It shares its prefix with the
Android app's `0PENTAGVIEWR` so a user seeing both recognises them as the same project.
"""

DEVICE_NAME = "OpenTagViewer Exporter"
"""
What this client calls itself, everywhere it is asked.

**Two places read this, and rule 11 is why it is one constant.** CloudKit is told it - FindMy.py
otherwise defaults to `FindMy.py`, naming the library rather than the program - and it is what
`announce_device` registers in the account's device list, which is the name a person actually
reads next to *Remove from Account*.

Those two disagreeing would not fail. It would put two differently-named things on one account for
one installation, which is precisely the confusion the serial exists to prevent.
"""

CLOUDKIT_DEVICE_NAME = DEVICE_NAME
"""Deprecated spelling, from when CloudKit was the only place this was reported."""
