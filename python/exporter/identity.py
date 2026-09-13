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

SERIAL_PREFIX = "0PENTAGX"
"""
The eight characters every serial this exporter presents begins with.

Recognisability lives here rather than in the whole string. An entry reading `0PENTAGX` followed
by anything is identifiably this project, which is what stops somebody removing it - see the
warning above about what removing it costs.
"""

SERIAL_ALPHABET = "ACDEFGHJKLMNPQRTUVWXY34679"
"""
What the four characters after the prefix are drawn from.

Uppercase alphanumeric, which is the shape Apple accepts, with the pairs a person reading a serial
off one screen and comparing it to another is most likely to confuse left out - no `O` against `0`,
no `I` or `1`, no `S` against `5`, no `B` against `8`, no `Z` against `2`. The user does not type
this, but they do compare it, and that is the whole job it has.
"""

EXPORTER_SERIAL = "0PENTAGXPORT"
"""
The serial every install presented before this was drawn per install.

**Kept, and still used**, by anything that already has an identity: changing the serial on an
install that works costs a second device-list entry and may cost a sign-in, for no benefit to
somebody who is not affected.

It shares the prefix and the shape of a drawn serial but is **not** one, and cannot be: `PORT`
contains an `O`, which :data:`SERIAL_ALPHABET` leaves out as a confusable. So a serial with an `O`
in it is, by construction, an install from before this change - useful when reading a report, and
the reason this is a named constant rather than a value that happens to come out of the generator.

**Why this stopped being the only one.** It was a constant, so every install of this program,
everywhere, presented Apple the same serial while presenting a *different* machine identity: one
serial against thousands of device IDs and thousands of Apple IDs, from every continent, at once.
Real hardware does not look like that, and a fingerprint nothing real produces is worth not
sending whether or not anything is matching on it.

**It has nothing to do with the 503s, and this docstring used to say it probably did.** Issues
#168, #176 and #181 were Apple's edge refusing any request naming `com.apple.dt.Xcode` - see
AGENTS.md rule 18. Serials were eliminated on the way there: a drawn one, this constant, and
upstream FindMy.py's bare `0` were all refused identically, and a QEMU macOS VM signs in with a
fabricated `C02...` serial that is not even unique across installs. Apple does not appear to look
at it at all.
"""


def generate_serial() -> str:
    """
    A serial for an install that does not have one yet.

    Twelve characters, of which the last four vary - about 450,000 of them, which is not a large
    space and does not need to be. The point is that two installs are unlikely to share one, not
    that a serial is unguessable; there is nothing to guess.

    `secrets` rather than `random` for no security reason: it is seeded from the OS, and a program
    that starts twice in the same second should not be able to draw the same serial twice.
    """
    import secrets

    tail = "".join(secrets.choice(SERIAL_ALPHABET) for _ in range(4))
    return SERIAL_PREFIX + tail


def serial_from(stored: dict | None) -> str:
    """
    The serial this install should present, given whatever is on disk.

    Three cases, and the middle one is the reason this is a function:

    - **A stored serial**: use it. This is every run after the first.
    - **A stored identity with no serial**: an install from before serials varied. It keeps
      :data:`EXPORTER_SERIAL`, because it has been presenting that to Apple and changing it now
      would re-identify a working install.
    - **Nothing stored**: a new install, or one whose identity file was deleted. It draws a new
      one. Deleting the file is therefore the remedy for an account that Apple is refusing, which
      is a thing a person can do without a new release.
    """
    if stored is None:
        return generate_serial()

    serial = stored.get("serial")
    return serial if isinstance(serial, str) and serial else EXPORTER_SERIAL


DEVICE_NAME = "OpenTagViewer Exporter"
"""
What this client tells CloudKit it is called.

FindMy.py otherwise defaults it to `FindMy.py`, which names the library rather than the program in
the one place this is reported.

**It is not the name in the account's device list, and nothing here can set that.** Signing in
registers the device by itself and Apple builds its row from the client identity, so the name
defaults to the claimed hardware - `MacBookPro`. The only call that sets one is the `postdata`
announce of findmy-export 02-mobileme-delegate §7, and Apple refuses it without a push token
(`ec -800012`, "Push token is invalid.").

That is a boundary rather than a gap: a push token is what makes a registered device trusted for
verification codes, and 01-authentication §13 argues at length that this program must never become
a second factor for somebody's Apple ID. The row says so, and it is worth more than a better name:

    This device cannot be used to receive Apple Account verification codes.

**So the serial is the label.** §13's three display fields are three independent levers, and the
serial is the only one sign-in controls that a person actually reads - which is why
:data:`EXPORTER_SERIAL` is legible rather than plausible.
"""

CLOUDKIT_DEVICE_NAME = DEVICE_NAME
"""Older spelling, kept for anything still importing it."""
