"""Tests for scripts/exporter_version.py.

This one gates a release, and the way a gate fails matters: a check that wrongly passes
stops protecting anything while still reporting green. The failure it exists to catch -
tagging `macos-exporter-v1.0.5` against a tree that still says `1.0.4` - is invisible until
someone is holding a zip that lies about which exporter made it.

The last test is the one that would have caught the original problem, and it reads the real
wizard rather than a fixture, so a rename or a refactor of that constant fails here instead
of at release time.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import exporter_version  # noqa: E402


def wizard(tmp_path: Path, body: str) -> Path:
    path = tmp_path / "wizard.py"
    path.write_text(body, encoding="utf-8")
    return path


# --- reading the version out of the source ----------------------------------------------

def test_reads_the_version_and_the_line_it_is_on(tmp_path: Path):
    path = wizard(tmp_path, 'import os\n\nVERSION = "1.0.5"\n')
    assert exporter_version.read_version(path) == ("1.0.5", 3)


def test_reads_the_version_without_importing_the_module(tmp_path: Path):
    """wizard.py imports tkinter and yaml; the check has to work on a runner with neither."""
    path = wizard(tmp_path, 'import definitely_not_installed_anywhere\n\nVERSION = "1.0.5"\n')
    assert exporter_version.read_version(path)[0] == "1.0.5"


def test_ignores_a_version_that_is_not_module_level(tmp_path: Path):
    path = wizard(tmp_path, 'def f():\n    VERSION = "9.9.9"\n    return VERSION\n')
    with pytest.raises(exporter_version.VersionError, match="No module-level VERSION"):
        exporter_version.read_version(path)


def test_rejects_a_version_that_is_not_a_plain_literal(tmp_path: Path):
    """A computed VERSION cannot be read without importing, so it has to be refused loudly."""
    path = wizard(tmp_path, 'PARTS = (1, 0, 5)\nVERSION = ".".join(str(p) for p in PARTS)\n')
    with pytest.raises(exporter_version.VersionError, match="must be a plain string literal"):
        exporter_version.read_version(path)


def test_reports_a_missing_file_rather_than_raising_oserror(tmp_path: Path):
    with pytest.raises(exporter_version.VersionError, match="Could not read"):
        exporter_version.read_version(tmp_path / "nope.py")


# --- parsing the tag --------------------------------------------------------------------

@pytest.mark.parametrize("tag", [
    "macos-exporter-v1.0.5",
    "refs/tags/macos-exporter-v1.0.5",
])
def test_accepts_a_bare_tag_and_a_full_ref(tag: str):
    assert exporter_version.version_from_tag(tag) == "1.0.5"


def test_accepts_a_four_part_version():
    """1.0.3.1 has shipped, so the pattern must not assume semver."""
    assert exporter_version.version_from_tag("macos-exporter-v1.0.3.1") == "1.0.3.1"


@pytest.mark.parametrize("tag", ["v1.0.5", "refs/tags/v1.0.5", "app-v1.0.5"])
def test_rejects_a_tag_that_is_not_an_exporter_release(tag: str):
    """The Android app is tagged in the same repo; its tags must not name an exporter build."""
    with pytest.raises(exporter_version.VersionError, match="not an exporter release tag"):
        exporter_version.version_from_tag(tag)


@pytest.mark.parametrize("tag", ["macos-exporter-vlatest", "macos-exporter-v1.0.5-rc1", "macos-exporter-v"])
def test_rejects_a_tag_whose_version_is_not_a_number(tag: str):
    with pytest.raises(exporter_version.VersionError, match="not a version number"):
        exporter_version.version_from_tag(tag)


# --- the check itself -------------------------------------------------------------------

def test_passes_when_the_tag_matches_the_source(tmp_path: Path):
    path = wizard(tmp_path, 'VERSION = "1.0.5"\n')
    assert exporter_version.check_tag("macos-exporter-v1.0.5", path) == "1.0.5"


def test_fails_when_the_tag_is_ahead_of_the_source(tmp_path: Path):
    path = wizard(tmp_path, 'VERSION = "1.0.4"\n')
    with pytest.raises(exporter_version.VersionError) as caught:
        exporter_version.check_tag("macos-exporter-v1.0.5", path)

    message = str(caught.value)
    # Both numbers have to appear, or the reader cannot tell which end is wrong.
    assert "1.0.4" in message and "1.0.5" in message
    assert "CONTRIBUTING.md" in message


def test_fails_when_the_source_is_ahead_of_the_tag(tmp_path: Path):
    path = wizard(tmp_path, 'VERSION = "1.0.6"\n')
    with pytest.raises(exporter_version.VersionError, match="disagree"):
        exporter_version.check_tag("macos-exporter-v1.0.5", path)


# --- the command line -------------------------------------------------------------------

def test_print_writes_the_real_version_and_nothing_else(capsys: pytest.CaptureFixture):
    """The workflow captures stdout into APP_VERSION, so stray output would end up in a filename."""
    assert exporter_version.main(["--print"]) == 0
    assert capsys.readouterr().out.strip() == exporter_version.read_version()[0]


def test_a_failing_check_exits_nonzero_and_reports_on_stderr(tmp_path: Path, capsys: pytest.CaptureFixture,
                                                             monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(exporter_version, "WIZARD_PATH", wizard(tmp_path, 'VERSION = "1.0.4"\n'))
    assert exporter_version.main(["--tag", "macos-exporter-v1.0.5"]) == 1

    captured = capsys.readouterr()
    assert "disagree" in captured.err
    # Nothing on stdout, so `APP_VERSION="$(...)"` cannot silently capture an error message.
    assert captured.out == ""


# --- the real wizard --------------------------------------------------------------------

def test_the_real_wizard_still_declares_a_readable_version():
    """Guards the assumption the whole check rests on: VERSION is a module-level literal."""
    version, lineno = exporter_version.read_version()
    assert exporter_version.VERSION_PATTERN.match(version), f"{version!r} is not a version number"
    assert lineno > 0
