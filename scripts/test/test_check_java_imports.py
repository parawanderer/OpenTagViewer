"""Tests for scripts/check_java_imports.py.

The check is only worth turning on if it never flags a long name Java actually needs - a false
finding there either blocks a PR or, through `--fix`, rewrites working code into a clash. So most
of these are the clashes, and each fixture is a file the compiler would accept.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_java_imports  # noqa: E402


def java(tmp_path: Path, body: str, name: str = "Thing", package: str = "dev.example") -> Path:
    directory = tmp_path.joinpath(*package.split("."))
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{name}.java"
    path.write_text(f"package {package};\n\n{body}", encoding="utf-8")
    return path


def flagged(tmp_path: Path) -> list[str]:
    return [f.qualified for _, found in check_java_imports.scan([tmp_path]) for f in found]


# --- what it flags ---------------------------------------------------------------------------

def test_flags_a_qualified_type_that_could_be_imported(tmp_path: Path):
    java(tmp_path, "class Thing {\n    android.content.Context context;\n}\n")
    assert flagged(tmp_path) == ["android.content.Context"]


def test_flags_annotations_static_calls_and_nested_types(tmp_path: Path):
    java(tmp_path, (
        "class Thing {\n"
        "    @org.junit.Before void setUp() {}\n"
        "    Object a = java.util.Objects.requireNonNull(null);\n"
        "    java.util.Map.Entry<String, String> entry;\n"
        "}\n"
    ))
    assert flagged(tmp_path) == ["org.junit.Before", "java.util.Objects", "java.util.Map"]


def test_reports_the_line_and_column(tmp_path: Path):
    java(tmp_path, "class Thing {\n    android.content.Context context;\n}\n")
    [(_, [finding])] = check_java_imports.scan([tmp_path])
    assert (finding.line, finding.column) == (4, 5)


# --- what it ignores -------------------------------------------------------------------------

def test_ignores_comments_strings_and_text_blocks(tmp_path: Path):
    java(tmp_path, (
        "/** See {@link java.util.Map} and {@code android.view.View}. */\n"
        "class Thing {\n"
        "    // java.util.List here\n"
        "    /* java.util.Set here */\n"
        '    String a = "java.util.Locale";\n'
        "    String b = \"\"\"\n"
        "        android.os.Bundle\n"
        "        \"\"\";\n"
        "    char c = '\\'';\n"
        "}\n"
    ))
    assert flagged(tmp_path) == []


def test_ignores_package_and_import_lines(tmp_path: Path):
    java(tmp_path, "import java.util.List;\nimport static java.util.Objects.requireNonNull;\n\nclass Thing {}\n")
    assert flagged(tmp_path) == []


def test_ignores_field_access_that_only_looks_like_a_package(tmp_path: Path):
    java(tmp_path, "class Thing {\n    void f() { this.binding.Header.show(); }\n}\n")
    assert flagged(tmp_path) == []


# --- the clashes that make a long name necessary ----------------------------------------------

def test_allows_it_when_another_type_of_that_name_is_imported(tmp_path: Path):
    java(tmp_path, (
        "import com.google.android.gms.maps.model.LatLng;\n\n"
        "class Thing {\n    com.amap.api.maps.model.LatLng other;\n}\n"
    ))
    assert flagged(tmp_path) == []


def test_allows_it_when_the_file_declares_that_name(tmp_path: Path):
    java(tmp_path, "class Thing {\n    static class Context {}\n    android.content.Context real;\n}\n")
    assert flagged(tmp_path) == []


def test_allows_it_when_the_package_has_a_class_of_that_name(tmp_path: Path):
    java(tmp_path, "class Context {}\n", name="Context")
    java(tmp_path, "class Thing {\n    android.content.Context real;\n}\n")
    assert flagged(tmp_path) == []


def test_allows_android_r_next_to_the_apps_own_r(tmp_path: Path):
    java(tmp_path, "class Thing {\n    int a = android.R.attr.colorPrimary;\n}\n")
    assert flagged(tmp_path) == []


def test_allows_it_where_an_import_would_shadow_java_lang(tmp_path: Path):
    java(tmp_path, "class Thing {\n    com.example.weird.String s;\n}\n")
    assert flagged(tmp_path) == []


def test_allows_two_types_of_the_same_name_both_written_long(tmp_path: Path):
    java(tmp_path, (
        "class Thing {\n"
        "    com.amap.api.maps.model.LatLng a;\n"
        "    com.google.android.gms.maps.model.LatLng b;\n"
        "}\n"
    ))
    assert flagged(tmp_path) == []


def test_leaves_a_file_with_a_wildcard_import_alone(tmp_path: Path):
    java(tmp_path, "import java.util.*;\n\nclass Thing {\n    android.content.Context context;\n}\n")
    assert flagged(tmp_path) == []


# --- --fix -----------------------------------------------------------------------------------

def test_fix_shortens_the_name_and_adds_the_import(tmp_path: Path):
    path = java(tmp_path, "import java.util.List;\n\nclass Thing {\n    android.content.Context a, b;\n"
                          "    android.content.Context c;\n}\n")
    assert check_java_imports.main(["--fix", str(tmp_path)]) == 0
    text = path.read_text(encoding="utf-8")
    assert text.count("import android.content.Context;") == 1
    assert "android.content.Context c" not in text and "    Context c;" in text


def test_fix_adds_an_import_block_when_there_is_none(tmp_path: Path):
    path = java(tmp_path, "class Thing {\n    java.util.Locale locale;\n}\n")
    check_java_imports.main(["--fix", str(tmp_path)])
    assert path.read_text(encoding="utf-8").startswith(
        "package dev.example;\n\nimport java.util.Locale;\n")


def test_fix_needs_no_import_for_java_lang_or_the_same_package(tmp_path: Path):
    java(tmp_path, "class Helper {}\n", name="Helper")
    path = java(tmp_path, "class Thing {\n    java.lang.String s;\n    dev.example.Helper h;\n}\n")
    check_java_imports.main(["--fix", str(tmp_path)])
    text = path.read_text(encoding="utf-8")
    assert "import" not in text
    assert "    String s;" in text and "    Helper h;" in text


def test_fix_leaves_the_clashes_and_rechecks_clean(tmp_path: Path):
    java(tmp_path, (
        "import com.google.android.gms.maps.model.LatLng;\n\n"
        "class Thing {\n    com.amap.api.maps.model.LatLng other;\n    java.util.Locale locale;\n}\n"
    ))
    check_java_imports.main(["--fix", str(tmp_path)])
    assert check_java_imports.main([str(tmp_path)]) == 0


def test_check_fails_when_it_finds_something(tmp_path: Path):
    java(tmp_path, "class Thing {\n    java.util.Locale locale;\n}\n")
    assert check_java_imports.main([str(tmp_path)]) == 1
