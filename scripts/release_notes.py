"""Do the release-notes copy-paste ritual, via `gh`.

Every release page here has the same shape: a wrapper that sells the thing - description,
screenshot, feature list, wiki link - then a "Changes" section for that version, then a footer.
Only the newest release carries the full wrapper. When a new one goes out, the previous release
is demoted: the wrapper is stripped back to its first line and its Changes are folded into a
collapsed `<details>` block.

Done by hand that is: copy the previous body, swap the Changes, publish, then go back and edit
the old release. Easy to get half-right, and the half that gets forgotten is the demotion,
which nobody notices until two releases both look like the current one.

The wrapper is never hardcoded here. It is read from the release being superseded and carried
forward, which is what the copy-paste does anyway - so editing the wording on the latest
release is enough to change it for the next one, and this script does not need to know what a
release page says.

Usage
-----
See what the new release would say, without creating anything:

    python scripts/release_notes.py draft --kind exporter --changes-file notes.md --dry-run

Create it as a draft. Nothing builds: the release workflow triggers on `published`, so the
draft sits there until someone clicks the button:

    python scripts/release_notes.py draft --kind exporter --changes-file notes.md

After publishing, collapse the release it replaced:

    python scripts/release_notes.py demote --kind exporter

`--kind android` does the same for the Android app releases, which use the same layout with a
differently worded Changes heading.

The version comes from the source - `VERSION` in the wizard, `versionName` in the Gradle build
- so it cannot disagree with what the app reports about itself. Pass `--version` to override.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import release_version  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent

# A heading like "### Changes" or "### What Changed since Last Release". Both kinds of release
# use a heading with "chang" in it, and nothing else in these bodies does.
CHANGES_HEADING = re.compile(r"^#{1,6}\s+.*chang", re.IGNORECASE)

# A markdown horizontal rule, which is what separates the Changes section from the footer.
HORIZONTAL_RULE = re.compile(r"^\s*(-{3,}|\*{3,}|_{3,})\s*$")

COLLAPSED_TEMPLATE = """{summary}

---------------

<details>
<summary>Summary</summary>

{changes}

</details>
"""


class ReleaseError(Exception):
    """Something is wrong with the release, the arguments, or gh. The message is the report."""


# ---------------------------------------------------------------------------------------
# Where each kind of release gets its version and its title
# ---------------------------------------------------------------------------------------

def _version(kind: str) -> str:
    """
    The version the source declares, read by the same module CI checks the tag against.

    Delegated rather than re-implemented: release_version.py already knows where each version
    lives and how to read it, and it is what fails a release whose tag disagrees. If this
    script read the version its own way, the two could differ - and the failure would be a
    release page describing one version while the build inside it reports another, which is
    the exact problem release_version.py exists to prevent.
    """
    return release_version.KINDS[kind]["read"]()[0]


# Only what a release *page* needs. The version and the tag prefix come from
# release_version.py, so there is one place that decides what a release is called.
KINDS = {
    "exporter": {
        "title": "OpenTagViewer MacOS AirTag Exporter v{version}",
        "bump": 'python/exporter/version.py  ->  VERSION = "..."',
    },
    "android": {
        "title": "OpenTagViewer Android App v{version}",
        "bump": 'app/build.gradle.kts  ->  versionName = "..."  (and versionCode)',
    },
}


def _prefix(kind: str) -> str:
    return release_version.KINDS[kind]["prefix"]


# ---------------------------------------------------------------------------------------
# Body parsing - the part worth testing
# ---------------------------------------------------------------------------------------

def split_body(body: str) -> tuple[str, str, str]:
    """
    Split a release body into (preamble, changes, footer).

    `changes` starts at the Changes heading and runs to the next horizontal rule, or to the end
    when there is none - the Android releases have no footer.
    """
    lines = body.replace("\r\n", "\n").split("\n")

    start = next((i for i, line in enumerate(lines) if CHANGES_HEADING.match(line)), None)
    if start is None:
        raise ReleaseError(
            "Could not find a Changes heading in the release body.\n"
            "Expected a markdown heading containing the word 'Changes', for example:\n"
            "    ### Changes\n"
            "If the layout has moved on, this script needs updating rather than working around."
        )

    end = next((i for i in range(start + 1, len(lines)) if HORIZONTAL_RULE.match(lines[i])),
               len(lines))

    # The preamble is kept verbatim, trailing blank lines and all. The two kinds of release
    # space their sections differently - one has a blank line after the rule above the changes
    # heading and one does not - so reproducing that exactly beats guessing a separator.
    return (
        "\n".join(lines[:start]),
        "\n".join(lines[start:end]).rstrip(),
        "\n".join(lines[end:]).strip(),
    )


def build_new_body(previous_body: str, changes: str) -> str:
    """Carry the previous release's wrapper forward, with a new Changes section inside it."""
    preamble, previous_changes, footer = split_body(previous_body)

    changes = changes.strip()
    if not CHANGES_HEADING.match(changes.split("\n")[0]):
        # The caller supplied just the bullet list. Reuse the heading the previous release
        # used rather than imposing one: the exporter says "### Changes" and the Android app
        # says "### What Changed since Last Release", and neither should be renamed by a tool.
        heading = previous_changes.split("\n")[0]
        changes = f"{heading}\n\n{changes}"

    # The preamble already carries its own trailing spacing, so a single newline reproduces
    # the original layout instead of drifting one blank line further on every release.
    body = f"{preamble}\n{changes}" if preamble else changes
    if footer:
        body = f"{body}\n\n{footer}"
    return body + "\n"


def demote_body(body: str) -> str:
    """
    Rewrite a release body into the collapsed form used by superseded releases.

    Keeps the first line of the wrapper - the one-line description - and folds the Changes into
    a `<details>` block. Everything else in the wrapper goes: the screenshot, the feature list
    and the wiki link belong on the current release only.
    """
    preamble, changes, _ = split_body(body)

    summary = next((line for line in preamble.split("\n") if line.strip()), "").strip()
    if not summary:
        raise ReleaseError("The release body has no description line to keep")

    return COLLAPSED_TEMPLATE.format(summary=summary, changes=changes.strip())


def is_already_demoted(body: str) -> bool:
    """A body that is already collapsed, so demoting it again would be a no-op."""
    return "<summary>Summary</summary>" in body


# ---------------------------------------------------------------------------------------
# gh
# ---------------------------------------------------------------------------------------

def _gh(*args: str) -> str:
    try:
        # encoding is explicit because the default on Windows is cp1252, and these release
        # bodies contain emoji - "❓" and "👉" are in the current ones. Without it, reading a
        # release fails with a UnicodeDecodeError from a background thread and gh appears to
        # have returned nothing at all.
        result = subprocess.run(["gh", *args], capture_output=True, text=True, check=False,
                                encoding="utf-8", cwd=REPO_ROOT)
    except FileNotFoundError:
        raise ReleaseError(
            "The GitHub CLI (gh) is not installed, or not on PATH.\n"
            "See CONTRIBUTING.md - it is also what lets an agent read CI failures."
        )

    if result.returncode != 0:
        raise ReleaseError(f"gh {' '.join(args)} failed:\n{result.stderr.strip()}")

    return result.stdout


def published_releases(prefix: str) -> list[dict]:
    """Published releases whose tag starts with `prefix`, newest first."""
    raw = _gh("release", "list", "--limit", "100", "--json",
              "tagName,name,createdAt,isDraft")
    releases = [r for r in json.loads(raw)
                if r["tagName"].startswith(prefix) and not r["isDraft"]]

    if not releases:
        raise ReleaseError(f"No published release found with a tag starting '{prefix}'")

    return sorted(releases, key=lambda r: r["createdAt"], reverse=True)


def latest_release(prefix: str) -> dict:
    """The most recent published release whose tag starts with `prefix`."""
    return published_releases(prefix)[0]


def check_version_is_new(tag: str, existing_tags: list[str], bump_hint: str) -> None:
    """
    Refuse to release a version that already exists.

    The version is read from the source, so if nobody bumped it the computed tag is the one
    already released - and the script would otherwise offer to create a draft on top of a
    published release, with notes describing changes that release does not contain.
    """
    if tag in existing_tags:
        raise ReleaseError(
            f"{tag} has already been released.\n"
            f"\n"
            f"The version comes from the source, so this means it has not been bumped yet:\n"
            f"\n"
            f"    {bump_hint}\n"
            f"\n"
            f"Bump it, commit it, and run this again. Releasing is two steps on purpose - the\n"
            f"bump is a commit, and the tag only publishes it."
        )


def pick_superseded(releases: list[dict]) -> dict:
    """
    The release a demotion should collapse: the second newest, not the newest.

    Demotion runs *after* the new release is published, so by then the newest release is the
    one that should keep its wrapper. Defaulting to "newest" collapsed the release that had
    just gone out - stripping its screenshot and feature list moments after publishing it.

    `releases` must be newest first.
    """
    if len(releases) < 2:
        raise ReleaseError(
            f"Only one published release exists ({releases[0]['tagName']}), so there is "
            f"nothing it supersedes. Pass --tag explicitly if you meant to collapse it."
        )
    return releases[1]


def release_body(tag: str) -> str:
    return json.loads(_gh("release", "view", tag, "--json", "body"))["body"]


# ---------------------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------------------

def command_draft(args) -> int:
    kind = KINDS[args.kind]
    version = args.version or _version(args.kind)
    tag = f"{_prefix(args.kind)}{version}"
    title = kind["title"].format(version=version)

    changes = Path(args.changes_file).read_text(encoding="utf-8") if args.changes_file \
        else args.changes

    # Drafts count: a half-prepared release still occupies the tag.
    raw = _gh("release", "list", "--limit", "100", "--json", "tagName")
    check_version_is_new(tag, [r["tagName"] for r in json.loads(raw)], kind["bump"])

    previous = latest_release(_prefix(args.kind))
    body = build_new_body(release_body(previous["tagName"]), changes)

    print(f"Previous release : {previous['tagName']}")
    print(f"New release      : {tag}  ({title})")
    print(f"Target           : {args.target}")
    print()
    print(body)

    if args.dry_run:
        print("--- dry run, nothing created ---")
        return 0

    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as handle:
        handle.write(body)
        notes_path = handle.name

    _gh("release", "create", tag, "--draft", "--target", args.target,
        "--title", title, "--notes-file", notes_path)

    print(f"Created {tag} as a DRAFT. Nothing is built or published until it is released.")
    print(f"After publishing, run:  python scripts/release_notes.py demote --kind {args.kind}")
    return 0


def command_demote(args) -> int:
    if args.tag:
        tag = args.tag
    else:
        releases = published_releases(_prefix(args.kind))
        superseded = pick_superseded(releases)
        tag = superseded["tagName"]
        print(f"Current release  : {releases[0]['tagName']}  (keeps its wrapper)")
        print(f"Collapsing       : {tag}")

    body = release_body(tag)
    if is_already_demoted(body):
        print(f"{tag} is already collapsed; nothing to do.")
        return 0

    collapsed = demote_body(body)

    print(f"Collapsing {tag} to:\n")
    print(collapsed)

    if args.dry_run:
        print("--- dry run, nothing changed ---")
        return 0

    if not args.yes:
        answer = input(f"Rewrite the notes of the published release {tag}? [y/N] ")
        if answer.strip().lower() not in ("y", "yes"):
            print("Left alone.")
            return 1

    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as handle:
        handle.write(collapsed)
        notes_path = handle.name

    _gh("release", "edit", tag, "--notes-file", notes_path)
    print(f"{tag} collapsed.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    draft = sub.add_parser("draft", help="create the next release as a draft")
    draft.add_argument("--kind", choices=sorted(KINDS), required=True)
    draft.add_argument("--version", help="override the version read from the source")
    draft.add_argument("--target", default="main", help="branch the tag is created from")
    group = draft.add_mutually_exclusive_group(required=True)
    group.add_argument("--changes-file", help="markdown file holding this version's changes")
    group.add_argument("--changes", help="this version's changes, as markdown")
    draft.add_argument("--dry-run", action="store_true")
    draft.set_defaults(func=command_draft)

    demote = sub.add_parser("demote", help="collapse the release that was just superseded")
    demote.add_argument("--kind", choices=sorted(KINDS), required=True)
    demote.add_argument("--tag", help="which release to collapse "
                                      "(default: the one the newest release superseded)")
    demote.add_argument("--dry-run", action="store_true")
    demote.add_argument("--yes", action="store_true", help="skip the confirmation")
    demote.set_defaults(func=command_demote)

    args = parser.parse_args(argv)

    try:
        return args.func(args)
    except ReleaseError as error:
        print(f"\nERROR: {error}\n", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
