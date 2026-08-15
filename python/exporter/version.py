"""
The exporter's version, and the strings that carry it into an export.

**This is the single source of truth** - see rule 9 in AGENTS.md. It reaches the wizard's window
title and, more importantly, every export as `via: <producer>:<version>`, which is how anyone
looking at a zip afterwards works out what built it. Releases are tagged
`macos-exporter-v<this>`, and `scripts/release_version.py` refuses to publish a release whose tag
disagrees.

**It lives here rather than in wizard.py so that reading it costs nothing.** The CLI is headless
and the release check runs on a lint runner; importing the wizard for a version string would pull
in tkinter, which one of those has no display for and neither needs. The check parses this module
rather than importing it, so `VERSION` has to stay a plain string literal.

Nothing patches it at build time, and nothing should: the wizard also runs from source, and those
exports stamp `via:` too, so a build-time patch would make two artifacts from one commit disagree.
"""

from __future__ import annotations

VERSION = "1.1.0"

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
