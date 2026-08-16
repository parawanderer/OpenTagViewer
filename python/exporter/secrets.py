"""
Getting a secret into a scripted run without leaving it somewhere.

**There is deliberately no `--password` flag, and there will not be one.** Anything on a command
line is readable by every other user on the machine through `ps`, ends up in shell history, and is
copied into any process listing a crash reporter or a CI log happens to capture. Offering the flag
guarantees somebody uses it, and the failure is silent - nothing goes wrong at the time.

So the ways in, worst last:

1. **A file, or standard input.** `--password-file secret.txt`, or `--password-file -` to read one
   line from a pipe. Nothing appears in `ps`, nothing in the environment, nothing in history. A
   file is checked for being readable by other users and refused if it is.
2. **An environment variable.** `OPENTAGVIEWER_APPLE_PASSWORD`. Better than a flag and worse than a
   file: on Linux the environment of a running process is readable by the same user through
   `/proc`, it is inherited by every child process, and setting it inline on a shell command puts
   it in history anyway. Fine in a CI secret store, which is where most people will want it.
3. **Being asked**, which is what happens when neither is given and there is a terminal.

The docs say the same thing, in the same order - see `docs/how-to-export-with-the-cli.md`.
"""

from __future__ import annotations

import os
import stat
import sys
from pathlib import Path

APPLE_PASSWORD_VAR = "OPENTAGVIEWER_APPLE_PASSWORD"
DEVICE_PASSCODE_VAR = "OPENTAGVIEWER_DEVICE_PASSCODE"

STDIN = "-"


class SecretError(Exception):
    """A secret was asked for by file or environment and could not be read safely."""


def read(path: Path | None, variable: str) -> str | None:
    """
    Read a secret from a file or the environment, in that order of preference.

    :param path: Where to read it from. `-` reads one line from standard input. None skips to the
        environment.
    :param variable: The environment variable to fall back to.
    :returns: The secret, or None if neither was given - in which case the caller should ask.
    :raises SecretError: If a file was named and could not be read, or is readable by others.
    """
    if path is not None:
        return _from_file(path)

    # Not stripped of anything but the newline a file or a pipe adds. A password may legitimately
    # begin or end with a space, and quietly removing one produces a rejected sign-in that looks
    # like a wrong password - which is the hardest kind of bug to see, because the user is certain
    # they typed it right and they did.
    value = os.environ.get(variable)

    return value if value is None else value.removesuffix("\n")


def _from_file(path: Path) -> str:
    if str(path) == STDIN:
        line = sys.stdin.readline()
        if not line:
            msg = "Nothing arrived on standard input, so there is no secret to read."
            raise SecretError(msg)

        return line.removesuffix("\n").removesuffix("\r")

    try:
        mode = path.stat().st_mode
    except OSError as e:
        msg = f"Could not read {path}: {e}"
        raise SecretError(msg) from None

    # **Refused rather than warned about.** A warning on a file anybody can read is advice nobody
    # acts on, and the whole reason to prefer a file over an environment variable is that a file
    # can have permissions. One that does not is worse than the option it was chosen over.
    if mode & (stat.S_IRGRP | stat.S_IROTH):
        msg = (
            f"{path} is readable by other users on this machine, so it is not a safe place for a"
            f" secret. Run: chmod 600 {path}"
        )
        raise SecretError(msg)

    try:
        content = path.read_text(encoding="utf-8")
    except OSError as e:
        msg = f"Could not read {path}: {e}"
        raise SecretError(msg) from None

    # The first line, so a file ending in a newline works and a file with a trailing comment line
    # does not silently become part of the password.
    secret = content.split("\n", 1)[0].removesuffix("\r")

    if not secret:
        msg = f"{path} is empty, so there is no secret in it."
        raise SecretError(msg)

    return secret
