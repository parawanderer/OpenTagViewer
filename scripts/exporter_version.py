"""Read the macOS exporter's version out of the source, and check a release tag against it.

`VERSION` in `python/main/wizard.py` is the single source of truth for the exporter. It is
shown in the window title and, more importantly, stamped into every export it produces as
`via: OpenTagViewer.app:<version>`. That field is how anyone looking at a zip afterwards -
a maintainer triaging a bug report, `check_export_compatibility.py`, a future importer -
works out which exporter built it, so a version that lies about itself costs real debugging
time.

Nothing in the release pipeline rewrites that constant: the tag names the artifact and the
GitHub release, and the source keeps whatever was committed. So tagging `macos-exporter-v1.0.5`
against a tree that still says `1.0.4` publishes a build that calls itself 1.0.4 everywhere a
user can see. This script exists so that mistake fails the release instead of shipping.

The fix is deliberately *not* to patch the tag into the source at build time. The wizard also
runs from source - the macOS VM bootstrap, anyone following CONTRIBUTING.md - and those runs
stamp `via:` too. A build-time patch would leave two artifacts from one commit disagreeing
about their version, which is the same drift somewhere harder to notice.

`VERSION` is read by parsing the module rather than importing it: importing `wizard` pulls in
tkinter and yaml, neither of which is wanted on a lint runner, and one of which cannot even
open a display in CI.

Usage
-----
Print the version the source declares:

    python scripts/exporter_version.py --print

Check a release tag against it. Accepts a bare tag or a full ref, so `$GITHUB_REF` works:

    python scripts/exporter_version.py --tag macos-exporter-v1.0.5
    python scripts/exporter_version.py --tag refs/tags/macos-exporter-v1.0.5

On success it prints the version, so a workflow can use it directly:

    APP_VERSION="$(python scripts/exporter_version.py --tag "$GITHUB_REF")"
"""

from __future__ import annotations

import argparse
import ast
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
WIZARD_PATH = REPO_ROOT / "python" / "main" / "wizard.py"

VERSION_CONSTANT = "VERSION"
TAG_PREFIX = "macos-exporter-v"
REF_PREFIX = "refs/tags/"

# Deliberately loose - the project has shipped three- and four-part versions (1.0.3.1) - but
# strict enough to catch a tag like 'macos-exporter-vlatest' reaching the artifact name.
VERSION_PATTERN = re.compile(r"^\d+(?:\.\d+)*$")

RELEASE_DOCS = "CONTRIBUTING.md -> Releasing the macOS exporter"


class VersionError(Exception):
    """Something is wrong with the tag or the source version. The message is the report."""


def _display(path: Path) -> str:
    """Repo-relative path where possible, for messages that a reader has to act on."""
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return str(path)


def read_version(path: Path | None = None) -> tuple[str, int]:
    """Return the `VERSION` string declared in `path`, with the line it is declared on."""
    path = path or WIZARD_PATH
    try:
        source = path.read_text(encoding="utf-8")
    except OSError as error:
        raise VersionError(f"Could not read {path}: {error}")

    try:
        module = ast.parse(source, filename=str(path))
    except SyntaxError as error:
        raise VersionError(f"Could not parse {path}: {error}")

    for node in module.body:
        if not isinstance(node, ast.Assign):
            continue
        names = [t.id for t in node.targets if isinstance(t, ast.Name)]
        if VERSION_CONSTANT not in names:
            continue
        if not isinstance(node.value, ast.Constant) or not isinstance(node.value.value, str):
            raise VersionError(
                f"{path}:{node.lineno}: {VERSION_CONSTANT} must be a plain string literal so it can be\n"
                f"read without importing the module. Found: {ast.dump(node.value)}"
            )
        return node.value.value, node.lineno

    raise VersionError(
        f"No module-level {VERSION_CONSTANT} = \"...\" found in {path}.\n"
        f"The release check reads it from there; see {RELEASE_DOCS}."
    )


def version_from_tag(tag: str) -> str:
    """Return the version encoded in a release tag, accepting a bare tag or a full git ref."""
    name = tag[len(REF_PREFIX):] if tag.startswith(REF_PREFIX) else tag

    if not name.startswith(TAG_PREFIX):
        raise VersionError(
            f"'{name}' is not an exporter release tag.\n"
            f"Exporter releases are tagged {TAG_PREFIX}<version>, for example {TAG_PREFIX}1.0.5.\n"
            f"See {RELEASE_DOCS}."
        )

    version = name[len(TAG_PREFIX):]
    if not VERSION_PATTERN.match(version):
        raise VersionError(
            f"'{version}' (from tag '{name}') is not a version number.\n"
            f"Expected digits separated by dots, for example 1.0.5 or 1.0.3.1."
        )
    return version


def check_tag(tag: str, path: Path | None = None) -> str:
    """Verify a release tag matches the source version. Returns the agreed version."""
    path = path or WIZARD_PATH
    tagged = version_from_tag(tag)
    declared, lineno = read_version(path)
    relative = _display(path)

    if tagged != declared:
        raise VersionError(
            "Release tag and source version disagree.\n"
            "\n"
            f"    tag  {TAG_PREFIX}{tagged}  declares  {tagged}\n"
            f"    {relative}:{lineno}  declares  {declared}\n"
            "\n"
            f"{relative} is the single source of truth. Its VERSION is shown in the window title\n"
            "and stamped into every export as `via: OpenTagViewer.app:<version>`, including exports\n"
            "made by running the wizard from source - which no build step can rewrite. Publishing\n"
            f"this release would ship a build that calls itself {declared} under a {tagged} tag.\n"
            "\n"
            "To fix:\n"
            "\n"
            f"    1. edit {relative} -> VERSION = \"{tagged}\"\n"
            "    2. commit and push that to main\n"
            f"    3. delete the release and its tag, then re-tag the new commit as {TAG_PREFIX}{tagged}\n"
            "\n"
            f"Or keep the code as it is and release it as {TAG_PREFIX}{declared} instead.\n"
            "\n"
            "Releasing the exporter is two steps on purpose: the bump is a commit, and the tag only\n"
            f"publishes it. That is what stops the two from drifting apart. See {RELEASE_DOCS}."
        )

    return declared


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Read or verify the macOS exporter version declared in python/main/wizard.py.",
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--print",
        dest="print_version",
        action="store_true",
        help="print the version declared in the source",
    )
    group.add_argument(
        "--tag",
        metavar="TAG",
        help=f"check a release tag ({TAG_PREFIX}<version>, or a full refs/tags/... ref) against the source",
    )
    args = parser.parse_args(argv)

    try:
        version = read_version()[0] if args.print_version else check_tag(args.tag)
    except VersionError as error:
        print(f"\nERROR: {error}\n", file=sys.stderr)
        return 1

    print(version)
    return 0


if __name__ == "__main__":
    sys.exit(main())
