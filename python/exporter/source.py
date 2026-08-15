"""
Deciding which route an export takes, without asking anybody who does not need to be asked.

There are two, and they read the same records:

- **local** - the files macOS keeps under `~/Library/com.apple.icloud.searchpartyd`, decrypted
  with a key from the login keychain.
- **iCloud** - the same records, fetched from the account. Works anywhere, and is the only route
  on Windows, on Linux, and on a Mac new enough that the keychain no longer gives the key up.

**Local wins where it works**, and it is worth being clear that this is not nostalgia. It asks for
no Apple ID password - macOS already has one - it registers no new device on the account, and it
needs no network. Signing in is the bigger ask of the two, so it should not be the one made of
somebody who does not need to make it.

**It is not an offline route**, though, and the local files are not independent of iCloud: macOS
put them there by signing in, joining the keychain trust circle as a peer and syncing them down.
See :mod:`exporter.localsource`.

macOS 15 tightened keychain access so the BeaconStore key cannot be read, which is why the version
is part of this rather than just "is this a Mac".
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from exporter.airtag_decryptor import INPUT_PATH
from exporter.utils import MACOS_VER

ICLOUD = "icloud"
LOCAL = "local"
AUTO = "auto"
NONE = "none"
"""
Read no account at all: the export is whatever `--add-keys` produced.

Self-generated tags - OpenHaystack and the like - are in no Apple account and never were, so
fetching one to export them is asking for an Apple ID password, a device on the account and a
keychain unlock in order to read a list nothing will be taken from.

**Only ever chosen by name.** :func:`detect` never returns it, because a run that reads no account
is a thing somebody decides, not something to be handed to them because their machine looked a
certain way.
"""

CHOICES = (AUTO, ICLOUD, LOCAL, NONE)

LAST_SUPPORTED_MACOS = 14
"""
The last macOS whose login keychain hands over the BeaconStore key.

15 tightened it. The local route is not merely untested above this - it cannot read the key at all,
which is why this project shipped a VM bootstrap for people on newer machines.
"""


@dataclass(frozen=True)
class Route:
    """Which route to take, and why - so the reason can be shown rather than just the outcome."""

    kind: str
    reason: str

    @property
    def is_local(self) -> bool:
        return self.kind == LOCAL

    @property
    def reads_nothing(self) -> bool:
        """Whether this route reads no account at all - see :data:`NONE`."""
        return self.kind == NONE


def detect() -> Route:
    """
    Work out which route this machine should take.

    Local when it can, iCloud otherwise. Every branch names its reason, because "it asked me for my
    Apple ID password" and "it did not" is exactly the difference somebody will want explained.
    """
    if MACOS_VER is None:
        return Route(ICLOUD, "this is not a Mac, so there are no local Find My files to read")

    if MACOS_VER[0] > LAST_SUPPORTED_MACOS:
        return Route(
            ICLOUD,
            f"macOS {MACOS_VER[0]} does not let anything read the BeaconStore key out of the"
            " keychain, so the local files cannot be decrypted",
        )

    if not os.path.isdir(INPUT_PATH):
        return Route(
            ICLOUD,
            "this Mac has no Find My cache to read - it is created when the Mac is signed into"
            " iCloud with Find My turned on",
        )

    return Route(
        LOCAL,
        f"macOS {MACOS_VER[0]} keeps the records locally, so this can read them without asking for"
        " your Apple ID password or registering a device on your account",
    )


def resolve(requested: str) -> Route:
    """
    Turn what was asked for into what will happen.

    :param requested: One of :data:`CHOICES`.

    Asking for the local route on a machine that cannot take it is answered rather than obeyed: it
    would otherwise fail deep inside the keychain with a message about a missing key.
    """
    if requested == AUTO:
        return detect()

    if requested == ICLOUD:
        return Route(ICLOUD, "you asked for the iCloud route")

    # Before the local check below, and unconditional: this is the one answer that does not depend
    # on what the machine can do, because there is nothing for it to do.
    if requested == NONE:
        return Route(NONE, "you asked for no account to be read")

    detected = detect()
    if detected.is_local:
        return Route(LOCAL, "you asked for the local route")

    return Route(ICLOUD, f"the local route was asked for, but {detected.reason}")
