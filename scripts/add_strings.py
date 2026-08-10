"""Add user-facing strings to every locale at once, or check that none are missing.

A string that is missing from one locale silently falls back to English for those users, so
it is not caught by anything - the build succeeds and the app looks fine in the language the
developer speaks. Adding strings by hand means one near-identical edit per locale, and doing
it with a shell loop has twice mangled non-ASCII text here: a French apostrophe reached the
screen as a literal `\\&#8217;`, because the translation went through shell quoting on the
way. Translations are therefore read from a JSON file and never from an argument.

The set of locales is discovered from the tree rather than hardcoded, so adding
`values-it/strings.xml` immediately makes Italian required with no change to this script.

Usage
-----
Adding strings. Write a JSON file (see below), then:

    python scripts/add_strings.py path/to/new_strings.json

Checking. Fails if any locale is missing a string the default locale has, so it can run in
CI:

    python scripts/add_strings.py --check

Back-filling what --check reported. Adds a string only to the locales that lack it, instead
of refusing because it already exists elsewhere:

    python scripts/add_strings.py --fill path/to/missing_strings.json

Rewording strings that already exist. Print what is currently there, in the same format the
other modes read, edit it, then feed it back:

    python scripts/add_strings.py --show anisette_upgrade_message > reword.json
    python scripts/add_strings.py --replace reword.json

--replace refuses unless the string is already defined in every locale, so a mistyped name
fails instead of silently doing nothing.

Listing the locales it found:

    python scripts/add_strings.py --locales

Input format
------------
    {
      "how_do_i_get_the_zip": {
        "default": "How do I get the zip?",
        "en": "How do I get the zip?",
        "de": "Wie erhalte ich die ZIP-Datei?",
        "fr": "Comment obtenir l'archive zip ?",
        "nl": "Hoe kom ik aan de zip?",
        "ru": "Как получить zip-архив?",
        "ja": "zip ファイルの入手方法",
        "ko": "zip 파일은 어떻게 받나요?",
        "zh-rCN": "如何获取 zip 文件？",
        "zh-rTW": "如何取得 zip 檔案？"
      }
    }

Every discovered locale is required; a missing one is an error rather than a silent English
fallback. Run --locales first if you are unsure which keys to supply. For a string that
genuinely should not be translated - a product name, a URL - set `"translatable": false` and
give only `"default"`.

Escaping is handled for you: apostrophes, quotes and ampersands are escaped as Android
requires. The inline tags <u>, <b> and <i> are passed through so emphasis still works; any
other angle bracket is escaped.
"""

from __future__ import annotations

import io
import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"

DEFAULT_LOCALE = "default"

# A resource qualifier is only a locale if it looks like one. `values-night`, `values-land`
# and `values-v27` are configurations, not languages, and must never be asked for a
# translation - matching on "has a strings.xml" alone would wrongly pick them up the day one
# of them gains a strings.xml of its own.
LOCALE_QUALIFIER = re.compile(r"^(?:[a-z]{2,3}(?:-r(?:[A-Z]{2}|[0-9]{3}))?|b\+[A-Za-z0-9+]+)$")

# Styling that is part of the copy rather than the layout, so it belongs in the resource: a
# paint flag set in code would only ever apply to the language it was written against.
ALLOWED_TAGS = ["u", "b", "i"]

CLOSING = "</resources>"


def discover_locales() -> list[str]:
    """Every locale that has a strings.xml, default first, then alphabetical."""
    found = []

    for path in sorted(RES_DIR.glob("values*/strings.xml")):
        qualifier = path.parent.name

        if qualifier == "values":
            found.append(DEFAULT_LOCALE)
            continue

        suffix = qualifier[len("values-"):]
        if LOCALE_QUALIFIER.match(suffix):
            found.append(suffix)

    found.sort(key=lambda locale: (locale != DEFAULT_LOCALE, locale))
    return found


def strings_file(locale: str) -> Path:
    directory = "values" if locale == DEFAULT_LOCALE else f"values-{locale}"
    return RES_DIR / directory / "strings.xml"


def escape(text: str) -> str:
    """Escape for an Android string resource, preserving the allowed inline tags."""
    placeholders: dict[str, str] = {}

    def stash(match: re.Match[str]) -> str:
        token = f"\x00{len(placeholders)}\x00"
        placeholders[token] = match.group(0)
        return token

    tags = "|".join(ALLOWED_TAGS)
    text = re.sub(rf"</?(?:{tags})>", stash, text)

    # Ampersand first, or it would double-escape the entities produced below.
    text = text.replace("&", "&amp;")
    text = text.replace("<", "&lt;").replace(">", "&gt;")
    # aapt rejects a bare apostrophe outright and treats a bare quote as a delimiter.
    text = text.replace("'", "\\'").replace('"', '\\"')

    for token, original in placeholders.items():
        text = text.replace(token, original)

    return text


def existing_names(path: Path) -> set[str]:
    if not path.is_file():
        return set()

    root = ElementTree.parse(path).getroot()

    names: set[str] = set()
    for element in root.iter("string"):
        name = element.get("name")
        if name:
            names.add(name)

    return names


def validate_spec(spec: dict, locales: list[str]) -> list[str]:
    """Everything wrong with the input, so the caller can report it all at once rather than
    making the user fix one problem per run."""
    problems: list[str] = []

    for name, translations in spec.items():
        if not isinstance(translations, dict):
            problems.append(f"{name}: expected an object of locale -> text")
            continue

        translatable = translations.get("translatable", True)
        required = [DEFAULT_LOCALE] if translatable is False else locales

        missing = [locale for locale in required if locale not in translations]
        if missing:
            problems.append(
                f"{name}: missing {', '.join(missing)}."
                " Every locale is required - a missing one silently falls back to English.")

        unknown = [
            key for key in translations
            if key != "translatable" and key not in locales
        ]
        if unknown:
            problems.append(
                f"{name}: unknown locale(s) {', '.join(unknown)}."
                f" Known: {', '.join(locales)}")

    return problems


def find_duplicates(spec: dict, present: dict[str, set[str]], locales: list[str]) -> list[str]:
    problems: list[str] = []

    for locale in locales:
        for name in spec:
            if name in present[locale]:
                problems.append(
                    f"{name}: already defined in"
                    f" {strings_file(locale).relative_to(REPO_ROOT)}."
                    " Use --fill to add it only where it is missing.")

    return problems


def render_entries(spec: dict, locale: str, present: set[str]) -> list[str]:
    """The <string> lines to append to one locale, skipping any it already has."""
    entries: list[str] = []

    for name, translations in spec.items():
        if name in present:
            continue

        if translations.get("translatable", True) is False:
            if locale != DEFAULT_LOCALE:
                continue
            text = escape(translations[DEFAULT_LOCALE])
            entries.append(
                f'    <string name="{name}" translatable="false">{text}</string>')
        else:
            entries.append(
                f'    <string name="{name}">{escape(translations[locale])}</string>')

    return entries


def add_strings(spec: dict, locales: list[str], fill: bool = False) -> int:
    """Write each string to every locale. With `fill`, locales that already have it are left
    alone instead of being treated as a mistake - which is what back-filling drift needs."""
    present: dict[str, set[str]] = {
        locale: existing_names(strings_file(locale)) for locale in locales
    }

    problems = validate_spec(spec, locales)
    if not fill:
        problems += find_duplicates(spec, present, locales)

    if problems:
        print("Refusing to write:\n", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    for locale in locales:
        path = strings_file(locale)
        content = path.read_text(encoding="utf-8")

        additions = render_entries(spec, locale, present[locale])
        if not additions:
            continue

        if CLOSING not in content:
            print(f"{path} has no {CLOSING}; is it a strings file?", file=sys.stderr)
            return 1

        path.write_text(
            content.replace(CLOSING, "\n".join(additions) + "\n" + CLOSING),
            encoding="utf-8")

        # Catch a malformed write now rather than at aapt time, where the error is worse.
        try:
            ElementTree.parse(path)
        except ElementTree.ParseError as error:
            print(f"{path} is no longer valid XML after writing: {error}", file=sys.stderr)
            return 1

        print(f"  {path.relative_to(REPO_ROOT)}: +{len(additions)}")

    print(f"\nDone. {len(spec)} string(s) across {len(locales)} locale(s).")
    return 0


def inner_markup(element: ElementTree.Element) -> str:
    """The contents of a <string>, with XML entities decoded and inline tags kept.

    `element.text` alone stops at the first child, so a string containing <u> would come back
    truncated at it.
    """
    parts = [element.text or ""]

    for child in element:
        parts.append(f"<{child.tag}>{inner_markup(child)}</{child.tag}>")
        parts.append(child.tail or "")

    return "".join(parts)


def show_strings(names: list[str], locales: list[str]) -> int:
    """Print the current text of some strings, in the input format the other modes take.

    Rewording means starting from what is already there, in ten languages. Reading that out of
    ten files by hand is where the wrong locale gets edited, or where an escape sequence is
    copied into the JSON and then escaped a second time on the way back in.
    """
    spec: dict[str, dict[str, str]] = {}
    missing: list[str] = []

    for name in names:
        translations: dict[str, str] = {}

        for locale in locales:
            path = strings_file(locale)
            if not path.is_file():
                continue

            for element in ElementTree.parse(path).getroot().iter("string"):
                if element.get("name") != name:
                    continue
                # The parser decodes &amp; and friends, but \' and \" are Android's own
                # escapes and mean nothing to XML, so they arrive with the backslash still on.
                translations[locale] = (inner_markup(element)
                                        .replace("\\'", "'").replace('\\"', '"'))

        if translations:
            spec[name] = translations
        else:
            missing.append(name)

    for name in missing:
        print(f"No string named {name} in any locale", file=sys.stderr)

    if spec:
        print(json.dumps(spec, indent=2, ensure_ascii=False))

    return 1 if missing else 0


def rewrite_one_locale(spec: dict, locale: str) -> tuple[str, int]:
    """One locale's file contents with the given strings reworded, and how many changed."""
    content = strings_file(locale).read_text(encoding="utf-8")
    replaced = 0

    for name, translations in spec.items():
        if translations.get("translatable", True) is False and locale != DEFAULT_LOCALE:
            continue

        text = escape(translations[locale])
        # DOTALL because a message can span lines, and non-greedy so that two strings in one
        # file cannot be swallowed as a single match.
        pattern = re.compile(
            rf'(<string name="{re.escape(name)}"[^>]*>).*?(</string>)', re.DOTALL)

        # A function replacement, so re does not process backslash escapes in what it returns
        # - the escaped text goes in verbatim. Passing a string here instead would turn every
        # \' the escaper produced into something else.
        content, count = pattern.subn(
            lambda match: match.group(1) + text + match.group(2), content)
        replaced += count

    return content, replaced


def missing_everywhere(spec: dict, locales: list[str]) -> list[str]:
    """Strings that cannot be replaced because they are not there to replace."""
    problems: list[str] = []

    for locale in locales:
        present = existing_names(strings_file(locale))
        for name in spec:
            if name not in present:
                problems.append(
                    f"{name}: not defined in"
                    f" {strings_file(locale).relative_to(REPO_ROOT)}."
                    " Nothing to replace - did you mean to add it?")

    return problems


def replace_strings(spec: dict, locales: list[str]) -> int:
    """Reword strings that already exist, in every locale at once.

    Adding is not the only thing that has to happen ten times. Rewording shipped copy by hand
    means ten near-identical edits with the same escaping traps, and the failure mode is worse
    than for a new string: the ones you miss keep the old wording and still pass --check, so
    nothing complains and the app quietly says two different things in two languages.

    Refuses to write unless the string already exists everywhere, which is what makes this
    distinct from adding - a typo in the name would otherwise silently do nothing.
    """
    problems = validate_spec(spec, locales) + missing_everywhere(spec, locales)

    if problems:
        print("Refusing to write:\n", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    for locale in locales:
        path = strings_file(locale)
        content, replaced = rewrite_one_locale(spec, locale)

        path.write_text(content, encoding="utf-8")

        try:
            ElementTree.parse(path)
        except ElementTree.ParseError as error:
            print(f"{path} is no longer valid XML after writing: {error}", file=sys.stderr)
            return 1

        print(f"  {path.relative_to(REPO_ROOT)}: ~{replaced}")

    print(f"\nDone. {len(spec)} string(s) reworded across {len(locales)} locale(s).")
    return 0


def check(locales: list[str]) -> int:
    default_path = strings_file(DEFAULT_LOCALE)
    if not default_path.is_file():
        print(f"No default strings file at {default_path}", file=sys.stderr)
        return 1

    root = ElementTree.parse(default_path).getroot()

    expected: set[str] = set()
    for element in root.iter("string"):
        name = element.get("name")
        if name and element.get("translatable") != "false":
            expected.add(name)

    failed = False
    for locale in locales:
        if locale == DEFAULT_LOCALE:
            continue

        missing = sorted(expected - existing_names(strings_file(locale)))
        if missing:
            failed = True
            print(f"\n{strings_file(locale).relative_to(REPO_ROOT)}"
                  f" is missing {len(missing)} string(s):", file=sys.stderr)
            for name in missing:
                print(f"  - {name}", file=sys.stderr)

    if failed:
        print("\nUsers of those locales would see English for the strings above.",
              file=sys.stderr)
        return 1

    print(f"All {len(expected)} translatable strings are present in"
          f" all {len(locales) - 1} translated locale(s).")
    return 0


def load_spec(path: Path) -> dict | None:
    """The parsed input file, or None having already explained what was wrong with it."""
    if not path.is_file():
        print(f"No such file: {path}", file=sys.stderr)
        return None

    try:
        spec = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        print(f"{path} is not valid JSON: {error}", file=sys.stderr)
        return None

    if not isinstance(spec, dict) or not spec:
        print(f"{path} should contain a non-empty object of string name -> translations",
              file=sys.stderr)
        return None

    return spec


def use_utf8_output() -> None:
    """Windows consoles still default to cp1252, which cannot encode most of what this prints
    - Japanese, Korean, Cyrillic. Without this, printing a translation is a
    UnicodeEncodeError rather than a translation, and redirecting to a file writes mojibake."""
    for stream in (sys.stdout, sys.stderr):
        # Guarded because these are only TextIOWrapper when attached to a real stream - under
        # pytest's capture, or any redirect, they are something else without reconfigure().
        if isinstance(stream, io.TextIOWrapper):
            stream.reconfigure(encoding="utf-8")


def main(argv: list[str]) -> int:
    use_utf8_output()

    locales = discover_locales()

    if not locales or DEFAULT_LOCALE not in locales:
        print(f"Found no default strings.xml under {RES_DIR}", file=sys.stderr)
        return 2

    if argv == ["--locales"]:
        print(f"Discovered {len(locales)} locale(s) under {RES_DIR.relative_to(REPO_ROOT)}:")
        for locale in locales:
            print(f"  {locale:<8} {strings_file(locale).relative_to(REPO_ROOT)}")
        return 0

    if argv == ["--check"]:
        return check(locales)

    if len(argv) >= 2 and argv[0] == "--show":
        return show_strings(argv[1:], locales)

    mode = argv[0] if len(argv) == 2 and argv[0] in ("--fill", "--replace") else None
    if mode:
        argv = argv[1:]

    if len(argv) != 1 or argv[0].startswith("-"):
        print(__doc__, file=sys.stderr)
        return 2

    spec = load_spec(Path(argv[0]))
    if spec is None:
        return 2

    if mode == "--replace":
        return replace_strings(spec, locales)

    return add_strings(spec, locales, fill=mode == "--fill")


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
