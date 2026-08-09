"""Tests for scripts/check_macos_min_version.py.

The parsing runs on `otool` output, which only exists on macOS - so the tests feed it recorded
output rather than shelling out, and run anywhere.

What this guards is a release that installs and then refuses to launch on every machine its
users have. That failure surfaces on a stranger's Mac at startup with no traceback, so the
check has to be right in both directions: it must catch a bundle built for a too-new macOS,
and it must not cry wolf on a normal one.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_macos_min_version as checker  # noqa: E402


# Modern toolchains emit LC_BUILD_VERSION.
BUILD_VERSION = """\
Load command 8
      cmd LC_BUILD_VERSION
  cmdsize 32
 platform 1
    minos 11.0
      sdk 15.0
   ntools 1
"""

# Older binaries, and some prebuilt wheels, still use LC_VERSION_MIN_MACOSX.
VERSION_MIN = """\
Load command 9
      cmd LC_VERSION_MIN_MACOSX
  cmdsize 16
  version 10.13
      sdk 10.15
"""


def test_reads_the_minimum_from_a_modern_build_command():
    assert checker.minimum_versions(BUILD_VERSION) == [(11, 0)]


def test_reads_the_minimum_from_an_older_build_command():
    assert checker.minimum_versions(VERSION_MIN) == [(10, 13)]


def test_ignores_the_sdk_version():
    """The SDK is what it was built *with*; minos is what it will run *on*."""
    assert (15, 0) not in checker.minimum_versions(BUILD_VERSION)


def test_a_file_with_no_version_command_reports_nothing():
    assert checker.minimum_versions("Load command 0\n      cmd LC_SEGMENT_64\n") == []


# Trimmed from a real Apple Silicon build. Three other load commands here carry something
# that looks like a version, and one of them is LC_SOURCE_VERSION holding 1230.1.
REALISTIC = """\
Load command 9
      cmd LC_BUILD_VERSION
  cmdsize 32
 platform 1
    minos 11.0
      sdk 15.0
   ntools 1
Load command 10
      cmd LC_SOURCE_VERSION
  cmdsize 16
  version 1230.1
Load command 11
          cmd LC_LOAD_DYLIB
      cmdsize 56
         name /usr/lib/libSystem.B.dylib (offset 24)
   time stamp 2 Thu Jan  1 01:00:02 1970
      current version 1345.120.2
compatibility version 1.0.0
Load command 12
      cmd LC_UUID
  cmdsize 24
"""


def test_only_the_field_belonging_to_the_right_command_counts():
    """
    The bug this replaced: any line matching `version <number>` was taken as a macOS
    requirement, so LC_SOURCE_VERSION's 1230.1 was read as "needs macOS 1230.1". That failed a
    perfectly good Apple Silicon build after it had already been compiled, and left the
    release with only the Intel binary attached.
    """
    assert checker.minimum_versions(REALISTIC) == [(11, 0)]


def test_a_dylibs_current_version_is_not_a_macos_requirement():
    assert (1345, 120, 2) not in checker.minimum_versions(REALISTIC)


def test_the_source_version_is_not_a_macos_requirement():
    assert (1230, 1) not in checker.minimum_versions(REALISTIC)


def test_a_fat_binary_reports_every_slice():
    """A universal binary carries one load command per architecture."""
    both = BUILD_VERSION + BUILD_VERSION.replace("minos 11.0", "minos 12.0")

    assert sorted(checker.minimum_versions(both)) == [(11, 0), (12, 0)]


# --- which file decides where the app can run -------------------------------------------

def test_the_newest_requirement_wins():
    """
    The app runs only where all of its parts run, so one Homebrew dylib built on the runner's
    own macOS sets the floor for the whole bundle.
    """
    requirements = {
        Path("OpenTagViewer"): (11, 0),
        Path("libtcl9.0.dylib"): (15, 0),
        Path("libcrypto.dylib"): (11, 0),
    }

    highest = checker.highest_requirement(requirements)

    assert highest is not None
    path, version = highest
    assert path == Path("libtcl9.0.dylib")
    assert version == (15, 0)


def test_no_mach_o_files_at_all_is_reported_rather_than_passing():
    assert checker.highest_requirement({}) is None


# --- version comparison ------------------------------------------------------------------

def test_versions_compare_numerically_not_as_strings():
    # "9.0" > "15.0" as strings, and 10.13 vs 10.9 is the classic one.
    assert checker.parse_version("15.0") > checker.parse_version("9.0")
    assert checker.parse_version("10.13") > checker.parse_version("10.9")


def test_a_build_for_an_older_macos_is_fine():
    assert checker.parse_version("11.0") <= checker.parse_version(checker.DEFAULT_MAX_SUPPORTED)


def test_a_build_requiring_macos_15_is_not():
    """
    macOS 15 tightened keychain access, so the wizard cannot work there at all. A binary that
    needs 15 is unusable by every single person who wants this app.
    """
    assert checker.parse_version("15.0") > checker.parse_version(checker.DEFAULT_MAX_SUPPORTED)


def test_the_default_limit_matches_the_newest_macos_the_wizard_supports():
    assert checker.DEFAULT_MAX_SUPPORTED == "14.0"
