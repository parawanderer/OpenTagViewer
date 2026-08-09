"""Tests for scripts/release_notes.py.

The script rewrites the notes of a *published* release, so a parsing mistake is visible to
everyone who visits the releases page. The parsing is also the fragile part: it works by
finding a heading in prose that a human wrote and may reword.

These cover the pure functions only - nothing here shells out to gh.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import release_notes  # noqa: E402


# The real shape of a macOS exporter release: wrapper, changes, footer.
EXPORTER_BODY = """Simple GUI Application for exporting Apple AirTags to a `.zip` file.

![image](https://example.invalid/screenshot.png)

The benefit of this GUI Application are:
- Being easily able to see which AirTags are available for export

❓**How to install/use?** Read wiki page 👉 [here](https://example.invalid/wiki)

---------------
### Changes

- Something that changed in this version
- Something else

-------------

An alternative approach is using the export python script from your terminal.
"""

# The Android releases use a differently worded heading and have no footer.
ANDROID_BODY = """Release version `1.0.4` of the main Android Application

### Main Features

- View the current "live" location of your AirTags

----------------

### What Changed since Last Release

- Added an option to export debug logs
"""


# --- splitting --------------------------------------------------------------------------

def test_splits_an_exporter_body_into_its_three_parts():
    preamble, changes, footer = release_notes.split_body(EXPORTER_BODY)

    assert preamble.startswith("Simple GUI Application")
    assert "screenshot.png" in preamble
    assert changes.startswith("### Changes")
    assert "Something else" in changes

    # The footer keeps the rule that separates it from the changes, so rebuilding the body
    # reproduces the original layout rather than running the two sections together.
    assert footer.startswith("---")
    assert "An alternative approach" in footer


def test_splits_an_android_body_with_no_footer():
    """The heading is worded differently and nothing follows the changes."""
    preamble, changes, footer = release_notes.split_body(ANDROID_BODY)

    assert "Main Features" in preamble
    assert changes.startswith("### What Changed since Last Release")
    assert "export debug logs" in changes
    assert footer == ""


def test_the_changes_section_stops_at_the_footer_rule():
    _, changes, _ = release_notes.split_body(EXPORTER_BODY)

    # Otherwise the footer would be carried into the collapsed block on every demotion,
    # accumulating a copy of itself each release.
    assert "alternative approach" not in changes


def test_a_body_with_no_changes_heading_is_an_error():
    with pytest.raises(release_notes.ReleaseError, match="Could not find a Changes heading"):
        release_notes.split_body("Just a description, nothing else.")


def test_windows_line_endings_are_handled():
    """GitHub stores these bodies with CRLF; a naive split leaves \\r on every line."""
    preamble, changes, _ = release_notes.split_body(EXPORTER_BODY.replace("\n", "\r\n"))

    assert "\r" not in preamble
    assert "\r" not in changes
    assert changes.startswith("### Changes")


# --- building the new release -----------------------------------------------------------

def test_carries_the_wrapper_forward_and_swaps_the_changes():
    body = release_notes.build_new_body(EXPORTER_BODY, "- A brand new thing")

    # The wrapper is never hardcoded in the script - it comes from the release being replaced.
    assert "Simple GUI Application" in body
    assert "screenshot.png" in body
    assert "alternative approach" in body

    assert "- A brand new thing" in body
    assert "Something that changed in this version" not in body


def test_adds_the_changes_heading_when_only_bullets_are_given():
    body = release_notes.build_new_body(EXPORTER_BODY, "- Just a bullet")

    assert "### Changes" in body


def test_reuses_the_headings_wording_from_the_previous_release():
    """The Android releases say "What Changed since Last Release"; a tool should not rename it."""
    body = release_notes.build_new_body(ANDROID_BODY, "- Just a bullet")

    assert "### What Changed since Last Release" in body
    assert "### Changes" not in body


def test_keeps_a_changes_heading_that_was_supplied():
    body = release_notes.build_new_body(EXPORTER_BODY, "### Changes\n\n- Just a bullet")

    assert body.count("### Changes") == 1


@pytest.mark.parametrize("body,changes", [
    (EXPORTER_BODY, "- Something that changed in this version\n- Something else"),
    (ANDROID_BODY, "- Added an option to export debug logs"),
])
def test_rebuilding_with_the_same_changes_reproduces_the_body(body, changes):
    """
    The strongest check available without a network: put the changes back and the body should
    come out as it went in. Anything else means the script is quietly reformatting a page
    somebody wrote by hand, a little more on every release.
    """
    assert release_notes.build_new_body(body, changes).strip() == body.strip()


def test_builds_a_body_for_a_previous_release_that_had_no_footer():
    body = release_notes.build_new_body(ANDROID_BODY, "- A brand new thing")

    assert "Main Features" in body
    assert "- A brand new thing" in body
    assert not body.rstrip().endswith("----------------")


# --- demoting the superseded release ----------------------------------------------------

def test_demoting_keeps_only_the_description_and_collapses_the_changes():
    collapsed = release_notes.demote_body(EXPORTER_BODY)

    assert collapsed.startswith("Simple GUI Application")
    assert "<summary>Summary</summary>" in collapsed
    assert "Something that changed in this version" in collapsed

    # The pitch belongs on the current release only.
    assert "screenshot.png" not in collapsed
    assert "How to install/use" not in collapsed
    assert "alternative approach" not in collapsed


def test_demoting_is_recognised_as_already_done():
    collapsed = release_notes.demote_body(EXPORTER_BODY)

    # Running it twice would otherwise nest a <details> inside a <details>, and the second run
    # would keep only the "Summary" line as the description.
    assert release_notes.is_already_demoted(collapsed)
    assert not release_notes.is_already_demoted(EXPORTER_BODY)


def test_demoting_an_android_release_works_too():
    collapsed = release_notes.demote_body(ANDROID_BODY)

    assert collapsed.startswith("Release version `1.0.4`")
    assert "What Changed since Last Release" in collapsed
    assert "Main Features" not in collapsed


def test_demoting_a_body_with_no_description_is_an_error():
    with pytest.raises(release_notes.ReleaseError, match="no description line"):
        release_notes.demote_body("### Changes\n\n- Something")


# --- versions come from the source ------------------------------------------------------

def test_the_exporter_version_is_read_from_the_wizard():
    """
    Not from the tag, and not typed in. The same number is stamped into every export as
    `via: OpenTagViewer.app:<version>`, so the release must agree with it.
    """
    import exporter_version

    assert release_notes.KINDS["exporter"]["version"]() == exporter_version.read_version()[0]


def test_the_android_version_is_read_from_the_gradle_build():
    version = release_notes.KINDS["android"]["version"]()

    assert version
    assert version[0].isdigit(), f"{version!r} does not look like a version"


def test_the_tag_prefixes_match_what_the_release_workflow_filters_on():
    """
    macos-exporter-python.yml only runs for tags starting 'macos-exporter-v'. A prefix typo
    here would create a release that quietly never builds anything.
    """
    workflow = (Path(__file__).resolve().parents[2] / ".github" / "workflows"
                / "macos-exporter-python.yml").read_text(encoding="utf-8")

    assert release_notes.KINDS["exporter"]["prefix"] in workflow
