"""Read a release's version out of the source, and check its tag against it.

Covers both things this repository releases: the desktop exporter, whose version is `VERSION` in
`python/exporter/version.py`, and the Android app, whose version is `versionName` in
`app/build.gradle.kts`. Neither is rewritten at build time, so in both cases the tag is a claim
about the source that nothing verifies unless something like this does.

`VERSION` in `python/exporter/version.py` is the single source of truth for the exporter. It is
shown in the window title and, more importantly, stamped into every export it produces as
`via: <producer>:<version>`. That field is how anyone looking at a zip afterwards -
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

`VERSION` is read by parsing the module rather than importing it. That module is deliberately
tiny and importable anywhere, but parsing costs nothing and keeps the check working even if it
ever grows an import that a lint runner does not have.

The Android app has the same problem for the same reason: `versionName` is what the app
reports about itself and what is baked into the APK, and the release workflow only parses the
tag to name the artifact. An `android-app-v*` tag could disagree with it indefinitely.

Usage
-----
Print the version the source declares:

    python scripts/release_version.py --kind exporter --print
    python scripts/release_version.py --kind android --print

Check a release tag against it. Accepts a bare tag or a full ref, so `$GITHUB_REF` works:

    python scripts/release_version.py --kind exporter --tag macos-exporter-v1.0.5
    python scripts/release_version.py --kind android --tag refs/tags/android-app-v1.0.5

On success it prints the version, so a workflow can use it directly:

    APP_VERSION="$(python scripts/release_version.py --kind android --tag "$GITHUB_REF")"
"""

from __future__ import annotations

import argparse
import ast
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
VERSION_PATH = REPO_ROOT / "python" / "exporter" / "version.py"
GRADLE_PATH = REPO_ROOT / "app" / "build.gradle.kts"

VERSION_CONSTANT = "VERSION"
REF_PREFIX = "refs/tags/"

GRADLE_VERSION_NAME = re.compile(r'^\s*versionName\s*=\s*"([^"]+)"', re.MULTILINE)

# Deliberately loose - the project has shipped three- and four-part versions (1.0.3.1) - but
# strict enough to catch a tag like 'macos-exporter-vlatest' reaching the artifact name.
VERSION_PATTERN = re.compile(r"^\d+(?:\.\d+)*$")


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
    path = path or VERSION_PATH
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
        f"The release check reads it from there; see {KINDS['exporter']['docs']}."
    )


def read_gradle_version(path: Path | None = None) -> tuple[str, int]:
    """Return `versionName` from the Gradle build, with the line it is declared on."""
    path = path or GRADLE_PATH
    try:
        source = path.read_text(encoding="utf-8")
    except OSError as error:
        raise VersionError(f"Could not read {path}: {error}")

    match = GRADLE_VERSION_NAME.search(source)
    if not match:
        raise VersionError(
            f"No versionName = \"...\" found in {_display(path)}.\n"
            f"The release check reads it from there."
        )

    lineno = source[:match.start()].count("\n") + 1
    return match.group(1), lineno


# Each releasable thing, and where its version actually lives. The tag is derived from the
# source rather than typed, so the two cannot drift.
KINDS = {
    "exporter": {
        "prefix": "exporter-v",
        # The old spelling, from when this only built for macOS. Still accepted so that tags
        # already published resolve and a half-remembered release still works.
        "also_accepts": ("macos-exporter-v",),
        "path": VERSION_PATH,
        "read": read_version,
        "field": 'VERSION = "..."',
        "docs": "CONTRIBUTING.md -> Releasing the exporter",
        "why": (
            "Its VERSION is shown in the window title and stamped into every export as\n"
            "`via: <producer>:<version>`, including exports made by running the wizard\n"
            "from source - which no build step can rewrite."
        ),
    },
    "android": {
        "prefix": "android-app-v",
        "path": GRADLE_PATH,
        "read": read_gradle_version,
        "field": 'versionName = "..."',
        "docs": "CONTRIBUTING.md -> Releasing the Android app",
        "why": (
            "versionName is what the app reports about itself in Settings and in bug reports,\n"
            "and it is baked into the APK - no build step rewrites it from the tag."
        ),
    },
}


def _kind(name: str) -> dict:
    if name not in KINDS:
        raise VersionError(f"Unknown release kind '{name}'. Expected one of: {', '.join(KINDS)}")
    return KINDS[name]


def _prefixes(spec: dict) -> tuple[str, ...]:
    """Every tag prefix this kind answers to, the canonical one first."""
    return (spec["prefix"], *spec.get("also_accepts", ()))


def version_from_tag(tag: str, kind: str = "exporter") -> str:
    """Return the version encoded in a release tag, accepting a bare tag or a full git ref."""
    spec = _kind(kind)
    prefix = spec["prefix"]
    name = tag[len(REF_PREFIX):] if tag.startswith(REF_PREFIX) else tag

    # The exporter was macOS-only and its tags said so. It builds for Windows and Linux now, so
    # the name changed - and the old one still resolves, because tags already published keep
    # their name forever and because nobody's muscle memory updates with a rename.
    matched = next((p for p in _prefixes(spec) if name.startswith(p)), None)

    if matched is None:
        raise VersionError(
            f"'{name}' is not a release tag for the {kind}.\n"
            f"Those are tagged {prefix}<version>, for example {prefix}1.0.5.\n"
            f"See {spec['docs']}."
        )

    version = name[len(matched):]
    if not VERSION_PATTERN.match(version):
        raise VersionError(
            f"'{version}' (from tag '{name}') is not a version number.\n"
            f"Expected digits separated by dots, for example 1.0.5 or 1.0.3.1."
        )
    return version


def check_tag(tag: str, kind: str = "exporter", path: Path | None = None) -> str:
    """Verify a release tag matches the source version. Returns the agreed version."""
    spec = _kind(kind)
    prefix = spec["prefix"]
    path = path or spec["path"]

    tagged = version_from_tag(tag, kind)
    declared, lineno = spec["read"](path)
    relative = _display(path)
    field = spec["field"].replace('"..."', f'"{tagged}"')

    if tagged != declared:
        raise VersionError(
            "Release tag and source version disagree.\n"
            "\n"
            f"    tag  {prefix}{tagged}  declares  {tagged}\n"
            f"    {relative}:{lineno}  declares  {declared}\n"
            "\n"
            f"{relative} is the single source of truth.\n"
            f"{spec['why']}\n"
            f"Publishing this release would ship a build that calls itself {declared} under a\n"
            f"{tagged} tag.\n"
            "\n"
            "To fix:\n"
            "\n"
            f"    1. edit {relative} -> {field}\n"
            "    2. commit and push that to main\n"
            f"    3. delete the release and its tag, then re-tag the new commit as {prefix}{tagged}\n"
            "\n"
            f"Or keep the code as it is and release it as {prefix}{declared} instead.\n"
            "\n"
            "Releasing is two steps on purpose: the bump is a commit, and the tag only publishes\n"
            f"it. That is what stops the two from drifting apart. See {spec['docs']}."
        )

    return declared


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Read or verify the version a release tag claims, against the source.",
    )
    parser.add_argument(
        "--kind",
        choices=sorted(KINDS),
        default="exporter",
        help="which release: the macOS exporter, or the Android app (default: exporter)",
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
        help="check a release tag (<prefix><version>, or a full refs/tags/... ref) against the source",
    )
    args = parser.parse_args(argv)

    try:
        spec = _kind(args.kind)
        version = spec["read"]()[0] if args.print_version else check_tag(args.tag, args.kind)
    except VersionError as error:
        print(f"\nERROR: {error}\n", file=sys.stderr)
        return 1

    print(version)
    return 0


if __name__ == "__main__":
    sys.exit(main())
