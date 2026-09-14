"""
Tests for `check_gsa_edge.py`, the scheduled question to Apple's edge.

Nothing here talks to Apple. The parts that can go wrong silently are the ones tested: reading the
app's hardware profiles out of Java, telling a refusal from a request that got through, and the
exit code a scheduled run turns into an issue.

The composition tests need the pinned FindMy.py and skip without it. The static-checks job runs
this file with pytest alone; the edge-check workflow installs the library and runs all of it.
"""

from __future__ import annotations

import io
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_gsa_edge as edge  # noqa: E402


def _answer(status, content_type="", length=0):
    return edge.Answer(status, content_type, length, "Apple", None)


# --- reading the app's profiles ---------------------------------------------------------------


def test_every_profile_the_app_declares_is_read():
    names = {p.name for p in edge.read_profiles()}

    assert {"LEGACY_MAC", "IPHONE"} <= names


def test_the_legacy_profile_is_read_despite_its_escaped_quote():
    """
    The regression this exists for. The display name is `"MacBook Pro 13\\""`, and the first
    version of the pattern could not read it - so it dropped the profile every install uses and
    reported the others as fine.
    """
    legacy = next(p for p in edge.read_profiles() if p.name == "LEGACY_MAC")

    assert legacy.model == "MacBookPro13,2"
    assert (legacy.os_name, legacy.os_version, legacy.os_build) == ("macOS", "13.1", "22C65")


def test_a_constant_the_pattern_cannot_read_fails_loudly(tmp_path):
    java = tmp_path / "AdiDeviceIdentity.java"
    java.write_text(
        '        READABLE(\n'
        '                "Name", "Model1,1", "macOS", "13.1", "22C65",\n'
        '                "1404.0.5", "22.3.0",\n'
        '                "com.apple.akd/1.0") {\n'
        '        },\n'
        '        UNREADABLE(\n'
        '                DISPLAY_NAME, "Model2,1", "macOS", "13.1", "22C65",\n'
        '                "1404.0.5", "22.3.0",\n'
        '                "com.apple.akd/1.0") {\n'
        '        };\n'
    )

    with pytest.raises(RuntimeError, match="UNREADABLE"):
        edge.read_profiles(java)


# --- reading an answer ------------------------------------------------------------------------


@pytest.mark.parametrize(("status", "content_type"), [
    (503, "text/html"),                  # the Xcode block, 190 bytes
    (429, "text/html"),                  # the edge's rate limit, 162 bytes
    (403, "text/html; charset=utf-8"),
])
def test_an_html_error_page_is_the_edge_refusing(status, content_type):
    assert edge.classify(_answer(status, content_type)) == edge.REFUSED


@pytest.mark.parametrize(("status", "content_type"), [
    (404, ""),                           # what a junk body gets once past the edge
    (401, "text/plain"),
    (200, "text/x-xml-plist;charset=UTF-8"),
    (500, "text/x-xml-plist"),           # Grand Slam's own error, not the edge's
])
def test_anything_else_got_past_the_edge(status, content_type):
    assert edge.classify(_answer(status, content_type)) == edge.THROUGH


def test_no_answer_is_unreachable_rather_than_either():
    assert edge.classify(edge.Answer(None, "", 0, "", None, "URLError: timed out")) == edge.UNREACHABLE


# --- what a run reports -----------------------------------------------------------------------


def _probe(name, expect):
    return edge.Probe(name, "POST", edge.GSA, {}, expect)


def _run(answers):
    out = io.StringIO()
    checks = [_probe(name, expect) for name, expect, _ in answers]
    by_name = {name: answer for name, _, answer in answers}
    code = edge.run(checks, ask=lambda probe: by_name[probe.name], out=out)
    return code, out.getvalue()


def test_all_as_expected_passes():
    code, _ = _run([
        ("sign-in, app LEGACY_MAC", edge.THROUGH, _answer(404)),
        ("sign-in, Xcode control", edge.REFUSED, _answer(503, "text/html", 190)),
    ])

    assert code == 0


def test_one_of_our_identities_refused_fails_and_names_it():
    code, out = _run([
        ("sign-in, app LEGACY_MAC", edge.THROUGH, _answer(503, "text/html", 190)),
        ("sign-in, exporter", edge.THROUGH, _answer(404)),
    ])

    assert code == 1
    assert "sign-in, app LEGACY_MAC" in out.split("refused", 1)[1]


def test_the_control_getting_through_is_news_not_a_failure():
    code, out = _run([
        ("sign-in, Xcode control", edge.REFUSED, _answer(404)),
    ])

    assert code == 0
    assert "no longer refuses the Xcode identifier" in out


def test_apple_unreachable_is_its_own_exit_code():
    """A runner's network failing is not Apple refusing us, and must not open that issue."""
    code, _ = _run([
        ("sign-in, exporter", edge.THROUGH, edge.Answer(None, "", 0, "", None, "URLError: down")),
    ])

    assert code == 2


# --- what is sent: composed by the pinned library ---------------------------------------------


def test_the_legacy_profile_signs_in_with_what_the_app_sent():
    """
    Pinned to the wire, not to this script: the header below is copied from the app's refusal
    log on 2026-09-13. If the check ever composes something else, it is probing a request the
    app does not make.
    """
    pytest.importorskip("findmy")
    legacy = next(p for p in edge.probes(edge.read_profiles()) if p.name == "sign-in, app LEGACY_MAC")

    assert legacy.headers["X-MMe-Client-Info"] == (
        "<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"
    )
    assert legacy.headers["User-Agent"] == "akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0"


def test_only_the_control_names_xcode_on_sign_in():
    pytest.importorskip("findmy")
    sign_ins = [p for p in edge.probes(edge.read_profiles()) if p.name.startswith("sign-in")]

    for probe in sign_ins:
        names_xcode = "com.apple.dt.Xcode" in probe.headers["X-MMe-Client-Info"]
        assert names_xcode == (probe.expect == edge.REFUSED), probe.name
