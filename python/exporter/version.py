"""
The exporter's version, and the strings that carry it into an export.

**This is the single source of truth** - see rule 9 in AGENTS.md. It reaches the wizard's window
title and, more importantly, every export as `via: <producer>:<version>`, which is how anyone
looking at a zip afterwards works out what built it. Releases are tagged
`exporter-v<this>` - `macos-exporter-v` is the older spelling and still resolves, because tags
already published keep their name - and `scripts/release_version.py` refuses to publish a release
whose tag disagrees.

**It lives here rather than in wizard.py so that reading it costs nothing.** The CLI is headless
and the release check runs on a lint runner; importing the wizard for a version string would pull
in tkinter, which one of those has no display for and neither needs. The check parses this module
rather than importing it, so `VERSION` has to stay a plain string literal.

Nothing patches it at build time, and nothing should: the wizard also runs from source, and those
exports stamp `via:` too, so a build-time patch would make two artifacts from one commit disagree.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

VERSION = "1.5.0"

APP_TITLE = f"OpenTagViewer AirTag Exporter {VERSION}"

EXPORT_VIA_WIZARD = f"OpenTagViewer.wizard:{VERSION}"
"""
What a bundle written by the windowed exporter says produced it.

**Was `OpenTagViewer.app` up to 1.0.5, and changed on purpose.** There is an Android app now, and
it will stamp its own `via:` - so "app" was the one word that could not stay, because it named the
wrong one of the three producers. `wizard` and `cli` say which of the two desktop entry points ran.

Nothing is lost by the change: bundles from 1.0.5 and earlier still say `OpenTagViewer.app`, and
that is still exactly what produced them.
"""

EXPORT_VIA_CLI = f"OpenTagViewer.cli:{VERSION}"
"""
And what the headless one says.

A different producer, because it is one: same version, same format, different program - and a bug
report that says which is worth more than one that says "the exporter".
"""

GITHUB_ISSUES_LINK = (
    "https://github.com/parawanderer/OpenTagViewer/issues/new?template=exporter-bug.yml"
)
"""
Where to report something neither the user nor this program can fix.

Here rather than in `wizard.py`, which is where it used to live alone, so the CLI can say it too
without importing tkinter - the same reason `VERSION` is here. Two copies of a URL is exactly the
sort of thing that goes stale in one place and is never noticed, because nothing tests a link.

**Straight at the template, not at a blank form.** The form asks for the version, the route and
the log, which is most of what a report of this needs and almost none of what one arrives with -
and its own front matter carries the labels. Labels can also be put in a URL, as
`?labels=bug,%40exporter-tool`, and that is the worse way round: GitHub applies those only for
somebody with permission to label, which a person reporting a bug generally is not.

`exporter-bug.yml` is a filename in `.github/ISSUE_TEMPLATE/`, so renaming that file breaks this
link silently - it degrades to a blank issue rather than an error, which is the failure nobody
notices.
"""


def describe_build() -> str:
    """
    What to write in a log so a report says which exporter produced it.

    **`VERSION` alone is not the answer on a checkout.** It is a committed literal, so it says
    whatever the last release set for every commit after it - a run from `main` two months into a
    release cycle reports the old version perfectly confidently. The commit is the only thing that
    identifies such a build, and `exporter-bug.yml` has to ask for it by hand precisely because
    nothing in the program said it.

    Three cases, distinguished because they are genuinely different builds:

    - **Frozen**, which is what people download. There is no checkout to ask and `VERSION` is
      exactly right, so nothing is spent finding that out.
    - **A source tree with git**, where the commit is the truth and the version is a hint.
    - **Anything else** - a source zip off a release tag, most likely, where `VERSION` is right
      again and there is no commit to name.
    """
    if getattr(sys, "frozen", False):
        return VERSION

    commit = _commit()

    return f"{VERSION} (from source, {commit})" if commit else VERSION


def _commit() -> str | None:
    """
    The short commit of the checkout this is running from, or None if that is not a thing.

    **Nothing here is allowed to matter.** It runs while logging is being set up, before anything
    the user asked for has started, so every way it can go wrong returns None: no git on PATH, not
    a repository, a git that hangs on a network-mounted directory. A version string is not worth
    failing a run over, and it is certainly not worth waiting on.
    """
    try:
        finished = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=Path(__file__).resolve().parent,
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None

    if finished.returncode != 0:
        return None

    return finished.stdout.strip() or None
