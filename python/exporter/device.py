"""
Remembering which device this exporter is, so a second export is not a second Mac.

**Apple identifies a device by more than its serial**, and the rest of it was being regenerated on
every run. `AsyncAppleAccount` invents a device UUID when it is not given one, and the exporter
built a fresh account each time - so an account's device list gained another "MacBook Pro,
0PENTAGXPORT" for every export anybody made, each looking to Apple like a different machine that
happened to share a serial.

So the identity is kept between runs. What that means precisely:

- **What is stored**: two UUIDs Apple knows this installation by, and the Anisette provisioning
  data - the state that makes this machine the same machine to Apple's servers.
- **What is not**: the Apple ID, the password, any session token, any keychain key, anything about
  the accessories. :func:`_only_identity` enforces that rather than trusting this docstring, and
  refuses to write a file carrying anything else.

**None of it is a credential**, which is why keeping it does not contradict the rule that this
program stores none. It is the opposite of a credential: it identifies the *program*, and its
whole purpose is to stop the program looking like a stranger every time it runs. Deleting the file
costs nothing but a new entry in the device list on the next export.
"""

from __future__ import annotations

import json
import logging
import os
import sys
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

FILENAME = "device-identity.json"

# Everything this file may contain. A key outside this set is a bug or a change upstream, and
# either way the file is not written - the point of keeping it is that it holds nothing sensitive,
# and a check is worth more than an intention.
_ALLOWED = frozenset({"uid", "devid", "anisette"})

# Nothing named like this reaches disk, whatever else changes. Belt and braces with `_ALLOWED`,
# because the anisette mapping comes from a library and its shape is not this project's to fix.
_FORBIDDEN = ("password", "username", "token", "session", "pet", "adsid", "dsid", "cookie")


def identity_path() -> Path:
    """Where the identity lives, per platform convention."""
    if sys.platform == "darwin":
        directory = Path.home() / "Library" / "Application Support" / "OpenTagViewer"
    elif sys.platform == "win32":
        directory = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "OpenTagViewer"
    else:
        base = os.environ.get("XDG_CONFIG_HOME")
        directory = (Path(base) if base else Path.home() / ".config") / "opentagviewer"

    return directory / FILENAME


def load(path: Path | None = None) -> dict[str, Any] | None:
    """
    Read the stored identity, or None if there is not one yet.

    Never raises. A file that has been corrupted, hand-edited or written by a future version costs
    one extra device-list entry, which is not worth failing an export over - so it is reported and
    ignored, and the next successful run replaces it.
    """
    path = path or identity_path()

    try:
        stored = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as e:
        logger.warning("Ignoring the stored device identity at %s: %s", path, e)
        return None

    if not isinstance(stored, dict) or not {"uid", "devid"} <= stored.keys():
        logger.warning("The stored device identity at %s is not one; ignoring it", path)
        return None

    return stored


def save(uid: str, devid: str, anisette: Any, path: Path | None = None) -> None:
    """
    Store the identity for next time.

    Never raises either. Failing to save costs a new device-list entry on the next run; failing an
    export that has already succeeded costs the export.
    """
    path = path or identity_path()
    document = {"uid": uid, "devid": devid, "anisette": anisette}

    try:
        _only_identity(document)
    except ValueError:
        logger.exception("Refusing to store the device identity")
        return

    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document, indent=2), encoding="utf-8")
        # Readable by its owner alone. It is not secret, but it names this installation to Apple
        # and there is no reason for anybody else on the machine to have it.
        #
        # **A no-op on Windows**, where chmod only toggles the read-only attribute and the group
        # and other bits cannot be cleared at all. Left in rather than made conditional: it is
        # correct where it works, harmless where it does not, and the alternative - real ACLs -
        # needs icacls or pywin32. Worth knowing that this file is not protected there.
        path.chmod(0o600)
    except OSError as e:
        logger.warning("Could not store the device identity at %s: %s", path, e)


def forget(path: Path | None = None) -> bool:
    """
    Delete the stored identity. Returns whether there was one.

    The next export then registers a new device, and the old entry stays in the account's device
    list until somebody removes it - so this is a thing to do deliberately, not a cleanup step.
    """
    path = path or identity_path()

    try:
        path.unlink()
    except FileNotFoundError:
        return False
    except OSError as e:
        logger.warning("Could not delete the device identity at %s: %s", path, e)
        return False

    return True


def _only_identity(document: dict[str, Any]) -> None:
    """
    Insist the document holds an identity and nothing else.

    :raises ValueError: If it carries an unexpected key, or one that looks like a credential at any
        depth. The account's own `to_json` writes the username and password alongside the ids, so
        "store what the library serialises" is exactly the mistake to make here.
    """
    unexpected = document.keys() - _ALLOWED
    if unexpected:
        msg = f"An identity file holds {sorted(_ALLOWED)}, and this one adds {sorted(unexpected)}."
        raise ValueError(msg)

    for key in _walk_keys(document):
        if any(forbidden in key.lower() for forbidden in _FORBIDDEN):
            msg = f"{key!r} looks like a credential, and none of those are stored."
            raise ValueError(msg)


def _walk_keys(value: Any) -> list[str]:
    """Every key anywhere in a nested structure."""
    if isinstance(value, dict):
        return [key for k, v in value.items() for key in [str(k), *_walk_keys(v)]]
    if isinstance(value, (list, tuple)):
        return [key for item in value for key in _walk_keys(item)]

    return []
