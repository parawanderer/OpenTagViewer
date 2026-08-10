"""Tests for scripts/add_strings.py.

Most of `scripts/` is one-shot utilities where a failure is loud and immediate to whoever
ran them, and tests would not earn their keep. This one is different for two reasons:

- `escape()` is the logic that has actually broken here, repeatedly. A regression corrupts
  every locale file at once, and the damage is subtle enough to reach users - a stray
  `\\&#8217;` where a French apostrophe belonged.
- `--check` gates CI. A check that wrongly passes stops protecting anything while still
  reporting green, which is the worst way for a gate to fail.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import add_strings  # noqa: E402


EMPTY = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'


@pytest.fixture
def res(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A throwaway res/ tree, so tests never touch the app's real strings."""
    res_dir = tmp_path / "res"
    monkeypatch.setattr(add_strings, "RES_DIR", res_dir)
    monkeypatch.setattr(add_strings, "REPO_ROOT", tmp_path)
    return res_dir


def make_locale(res_dir: Path, qualifier: str, body: str = EMPTY) -> Path:
    directory = res_dir / qualifier
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "strings.xml"
    path.write_text(body, encoding="utf-8")
    return path


def with_strings(*entries: str) -> str:
    return ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            + "".join(f"    {entry}\n" for entry in entries)
            + "</resources>\n")


# ---------------------------------------------------------------------------------------
# escape
# ---------------------------------------------------------------------------------------

def test_escapes_apostrophes():
    # aapt rejects a bare apostrophe outright: "Invalid unicode escape sequence".
    assert add_strings.escape("it's") == "it\\'s"


def test_leaves_the_typographic_apostrophe_alone():
    # The one French should use, and the one that needs no escaping.
    assert add_strings.escape("l’archive") == "l’archive"


def test_escapes_ampersands_once():
    assert add_strings.escape("Tom & Jerry") == "Tom &amp; Jerry"


def test_does_not_double_escape_the_entities_it_creates():
    assert "&amp;lt;" not in add_strings.escape("a < b & c")


def test_preserves_allowed_inline_tags():
    # Styling that belongs to the copy belongs in the resource, so it survives translation.
    assert add_strings.escape("<u>link</u>") == "<u>link</u>"


def test_escapes_tags_that_are_not_allowed():
    assert add_strings.escape("<script>x</script>") == "&lt;script&gt;x&lt;/script&gt;"


def test_keeps_format_specifiers_intact():
    assert add_strings.escape("%1$d of %2$d") == "%1$d of %2$d"


# ---------------------------------------------------------------------------------------
# discover_locales
# ---------------------------------------------------------------------------------------

def test_discovers_locales_and_puts_default_first(res: Path):
    make_locale(res, "values")
    make_locale(res, "values-de")
    make_locale(res, "values-zh-rCN")

    assert add_strings.discover_locales() == ["default", "de", "zh-rCN"]


def test_ignores_qualifiers_that_are_not_languages(res: Path):
    # These are configurations, not translations. Matching on "has a strings.xml" alone
    # would start demanding German for values-night the day it gained one.
    make_locale(res, "values")
    for qualifier in ("values-night", "values-land", "values-v27", "values-w600dp"):
        make_locale(res, qualifier)

    assert add_strings.discover_locales() == ["default"]


# ---------------------------------------------------------------------------------------
# check
# ---------------------------------------------------------------------------------------

def test_check_passes_when_every_locale_is_complete(res: Path):
    make_locale(res, "values", with_strings('<string name="hello">Hello</string>'))
    make_locale(res, "values-de", with_strings('<string name="hello">Hallo</string>'))

    assert add_strings.check(["default", "de"]) == 0


def test_check_fails_when_a_locale_is_missing_a_string(res: Path):
    make_locale(res, "values", with_strings('<string name="hello">Hello</string>'))
    make_locale(res, "values-de")

    assert add_strings.check(["default", "de"]) == 1


def test_check_ignores_untranslatable_strings(res: Path):
    make_locale(res, "values", with_strings(
        '<string name="brand" translatable="false">OpenTagViewer</string>'))
    make_locale(res, "values-de")

    assert add_strings.check(["default", "de"]) == 0


# ---------------------------------------------------------------------------------------
# add_strings
# ---------------------------------------------------------------------------------------

def test_writes_the_string_to_every_locale(res: Path):
    make_locale(res, "values")
    make_locale(res, "values-de")

    result = add_strings.add_strings(
        {"hello": {"default": "Hello", "de": "Hallo"}}, ["default", "de"])

    assert result == 0
    assert 'name="hello">Hello<' in (res / "values" / "strings.xml").read_text(encoding="utf-8")
    assert 'name="hello">Hallo<' in (res / "values-de" / "strings.xml").read_text(encoding="utf-8")


def test_refuses_when_a_locale_is_missing(res: Path):
    make_locale(res, "values")
    make_locale(res, "values-de")

    result = add_strings.add_strings({"hello": {"default": "Hello"}}, ["default", "de"])

    assert result == 1
    # Nothing partially written: a half-applied change is worse than none.
    assert "hello" not in (res / "values" / "strings.xml").read_text(encoding="utf-8")


def test_refuses_an_unknown_locale(res: Path):
    make_locale(res, "values")

    result = add_strings.add_strings(
        {"hello": {"default": "Hello", "xx": "Hello"}}, ["default"])

    assert result == 1


def test_refuses_a_duplicate_without_fill(res: Path):
    make_locale(res, "values", with_strings('<string name="hello">Hello</string>'))

    assert add_strings.add_strings({"hello": {"default": "Hi"}}, ["default"]) == 1


def test_fill_adds_only_where_missing(res: Path):
    make_locale(res, "values", with_strings('<string name="hello">Hello</string>'))
    make_locale(res, "values-de")

    result = add_strings.add_strings(
        {"hello": {"default": "Hello", "de": "Hallo"}}, ["default", "de"], fill=True)

    assert result == 0
    # The existing entry is left as it was rather than duplicated.
    assert (res / "values" / "strings.xml").read_text(encoding="utf-8").count("hello") == 1
    assert 'name="hello">Hallo<' in (res / "values-de" / "strings.xml").read_text(encoding="utf-8")


def test_untranslatable_strings_go_only_in_the_default_locale(res: Path):
    make_locale(res, "values")
    make_locale(res, "values-de")

    result = add_strings.add_strings(
        {"brand": {"default": "OpenTagViewer", "translatable": False}}, ["default", "de"])

    assert result == 0
    assert 'translatable="false"' in (res / "values" / "strings.xml").read_text(encoding="utf-8")
    assert "brand" not in (res / "values-de" / "strings.xml").read_text(encoding="utf-8")


def test_written_files_are_still_valid_xml(res: Path):
    import xml.etree.ElementTree as ElementTree

    make_locale(res, "values")

    add_strings.add_strings(
        {"tricky": {"default": "Tom & Jerry's <u>bar</u>", "translatable": False}}, ["default"])

    # The whole point of escaping: aapt would reject this file otherwise.
    ElementTree.parse(res / "values" / "strings.xml")


# ---------------------------------------------------------------------------------------
# --show and --replace
#
# Rewording shipped copy has a failure mode that adding does not: a locale you miss keeps
# the old wording and still passes --check, so nothing complains and the app says two
# different things in two languages.
# ---------------------------------------------------------------------------------------

def test_show_round_trips_through_replace(res: Path, capsys: pytest.CaptureFixture):
    import json

    # Both kinds of escaping in one string: an XML entity, and Android's own backslash.
    escaped = '<string name="hi">Tom &amp; Jerry' + "\\'" + 's</string>'
    make_locale(res, "values", with_strings(escaped))
    make_locale(res, "values-de", with_strings('<string name="hi">Hallo</string>'))

    assert add_strings.show_strings(["hi"], ["default", "de"]) == 0

    shown = json.loads(capsys.readouterr().out)
    # Decoded, not raw: what comes out has to be editable and feedable straight back in,
    # without anybody escaping it a second time on the way.
    assert shown["hi"]["default"] == "Tom & Jerry's"

    # Feeding it straight back has to reproduce the file byte for byte, or every round trip
    # would add another layer of escaping to a string nobody meant to change.
    assert add_strings.replace_strings(shown, ["default", "de"]) == 0
    assert (res / "values" / "strings.xml").read_text(encoding="utf-8") == with_strings(escaped)


def test_show_keeps_inline_tags(res: Path, capsys: pytest.CaptureFixture):
    import json

    make_locale(res, "values", with_strings('<string name="hi">see <u>this</u> now</string>'))

    add_strings.show_strings(["hi"], ["default"])

    # element.text alone would stop at the <u> and silently truncate the string.
    assert json.loads(capsys.readouterr().out)["hi"]["default"] == "see <u>this</u> now"


def test_replace_rewrites_every_locale(res: Path):
    make_locale(res, "values", with_strings('<string name="hi">old</string>'))
    make_locale(res, "values-de", with_strings('<string name="hi">alt</string>'))

    result = add_strings.replace_strings(
        {"hi": {"default": "new", "de": "neu"}}, ["default", "de"])

    assert result == 0
    assert ">new<" in (res / "values" / "strings.xml").read_text(encoding="utf-8")
    assert ">neu<" in (res / "values-de" / "strings.xml").read_text(encoding="utf-8")


def test_replace_leaves_other_strings_alone(res: Path):
    make_locale(res, "values", with_strings(
        '<string name="before">keep</string>',
        '<string name="hi">old</string>',
        '<string name="after">keep too</string>'))

    add_strings.replace_strings({"hi": {"default": "new"}}, ["default"])

    content = (res / "values" / "strings.xml").read_text(encoding="utf-8")
    assert ">keep<" in content and ">keep too<" in content and ">new<" in content


def test_replace_refuses_a_string_that_does_not_exist(res: Path):
    make_locale(res, "values", with_strings('<string name="hi">old</string>'))

    # A mistyped name has to fail rather than quietly rewrite nothing.
    assert add_strings.replace_strings({"hlo": {"default": "new"}}, ["default"]) == 1
    assert ">old<" in (res / "values" / "strings.xml").read_text(encoding="utf-8")


def test_replace_refuses_when_one_locale_lacks_the_string(res: Path):
    make_locale(res, "values", with_strings('<string name="hi">old</string>'))
    make_locale(res, "values-de")

    # Half a reword is the failure this mode exists to prevent.
    assert add_strings.replace_strings(
        {"hi": {"default": "new", "de": "neu"}}, ["default", "de"]) == 1
    assert ">old<" in (res / "values" / "strings.xml").read_text(encoding="utf-8")


def test_replace_escapes_what_it_writes(res: Path):
    make_locale(res, "values", with_strings('<string name="hi">old</string>'))

    add_strings.replace_strings({"hi": {"default": "Tom & Jerry's"}}, ["default"])

    content = (res / "values" / "strings.xml").read_text(encoding="utf-8")
    # One backslash before the apostrophe, not two: re.sub with a function replacement does
    # not reprocess escapes, so doubling them here would put a literal "\" on screen.
    assert '<string name="hi">Tom &amp; Jerry' + "\\'" + 's</string>' in content


def test_replace_handles_a_multiline_string(res: Path):
    make_locale(res, "values",
                with_strings('<string name="hi">first line\n\nsecond line</string>'))

    assert add_strings.replace_strings({"hi": {"default": "one line"}}, ["default"]) == 0

    content = (res / "values" / "strings.xml").read_text(encoding="utf-8")
    assert ">one line<" in content and "second line" not in content


def test_show_reports_a_name_that_exists_nowhere(res: Path):
    make_locale(res, "values", with_strings('<string name="hi">old</string>'))

    assert add_strings.show_strings(["nope"], ["default"]) == 1
