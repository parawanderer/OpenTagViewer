"""Find fully qualified type names in Java code that should be imports, and optionally fix them.

`android.content.Context context` where `import android.content.Context;` would do is the
pattern this catches. A fully qualified name is allowed only where the short name would clash
with something else in the file: an import of a different type, a type the file declares, a
class in the same package, a type nested in a supertype the project declares, or a
`java.lang` type an import would shadow. That is the one case
where Java needs the long form, and it is decided here from the source alone, so this runs in
well under a second with no Gradle and no classpath.

    python scripts/check_java_imports.py              # check app/src, exit 1 on findings
    python scripts/check_java_imports.py PATH...      # check particular files or directories
    python scripts/check_java_imports.py --fix        # rewrite to short names and add imports

Comments, string literals (text blocks included) and the `package` and `import` lines are
ignored, so `{@link java.util.Map}` in a Javadoc is never a finding. A file with a non-static
wildcard import is left alone, since what the `*` brings in cannot be read from the source.

Only names whose first segment is a known package root are looked at - `java`, `android`,
`com`, and the first segment of every import in the scanned files. That is what keeps
`this.binding.Foo`-shaped field access from reading as a package.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parent.parent / "app" / "src"

# Package roots worth recognising even before any import mentions them.
BASE_ROOTS = frozenset({
    "android", "androidx", "com", "dalvik", "dev", "io", "java", "javax", "junit", "kotlin",
    "kotlinx", "lombok", "net", "org",
})

# Generated per module into the app's own package, so they never appear as a file there, and an
# unqualified `R` always means the app's own. `android.R` next to it is the textbook clash.
GENERATED_IN_PACKAGE = frozenset({"R", "BuildConfig", "Manifest"})

# java.lang types an import would shadow. Not exhaustive, just the ones a clash is plausible for.
JAVA_LANG = frozenset({
    "AssertionError", "AutoCloseable", "Boolean", "Byte", "CharSequence", "Character", "Class",
    "ClassCastException", "ClassLoader", "Comparable", "Deprecated", "Double", "Enum", "Error",
    "Exception", "Float", "FunctionalInterface", "IllegalArgumentException",
    "IllegalStateException", "IndexOutOfBoundsException", "Integer", "InterruptedException",
    "Iterable", "Long", "Math", "Module", "NullPointerException", "Number",
    "NumberFormatException", "Object", "Override", "Package", "Process", "Record", "Runnable",
    "Runtime", "RuntimeException", "SafeVarargs", "SecurityException", "Short", "String",
    "StringBuilder", "SuppressWarnings", "System", "Thread", "Throwable",
    "UnsupportedOperationException", "Void",
})

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
IMPORT_RE = re.compile(r"^\s*import\s+(static\s+)?([\w.]+?)(\.\*)?\s*;", re.MULTILINE)
DECLARED_RE = re.compile(r"\b(?:class|interface|enum|record)\s+([A-Z]\w*)")
# A type declaration up to its opening brace, which is where `extends` and `implements` live.
HEADER_RE = re.compile(r"\b(?:class|interface|enum|record)\s+[A-Z]\w*([^{;]*)\{")
GENERICS_RE = re.compile(r"<[^<>]*>")
# A run of lowercase segments followed by the first capitalised one: the type. Anything after it
# (`Map.Entry`, `View.VISIBLE`) stays as written and resolves through the import.
QUALIFIED_RE = re.compile(r"(?<![\w.])((?:[a-z_][a-z0-9_]*\.)+)([A-Z]\w*)")


@dataclass(frozen=True)
class Finding:
    path: Path
    line: int
    column: int
    qualified: str
    start: int
    end: int

    @property
    def simple(self) -> str:
        return self.qualified.rsplit(".", 1)[1]

    @property
    def package(self) -> str:
        return self.qualified.rsplit(".", 1)[0]


def blank_out(source: str) -> str:
    """The source with comments and literals replaced by spaces, keeping every offset and newline."""
    out = list(source)
    i = 0
    n = len(source)

    def erase(start: int, end: int) -> None:
        for k in range(start, min(end, n)):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        end = _literal_or_comment_end(source, i)
        if end is None:
            i += 1
            continue
        erase(i, end)
        i = end
    return "".join(out)


def _literal_or_comment_end(source: str, i: int) -> int | None:
    """Where the comment or literal starting at `i` ends, or None if none starts there."""
    if source.startswith("//", i):
        newline = source.find("\n", i)
        return len(source) if newline < 0 else newline
    if source.startswith("/*", i):
        close = source.find("*/", i + 2)
        return len(source) if close < 0 else close + 2
    if source.startswith('"""', i):
        close = source.find('"""', i + 3)
        return len(source) if close < 0 else close + 3
    if source[i] in "\"'":
        return _quoted_end(source, i)
    return None


def _quoted_end(source: str, i: int) -> int:
    quote = source[i]
    j = i + 1
    while j < len(source) and source[j] != quote and source[j] != "\n":
        j += 2 if source[j] == "\\" else 1
    return j + 1


@dataclass
class JavaFile:
    path: Path
    source: str
    code: str
    package: str
    imports: dict[str, str]
    declared: frozenset[str]
    # Non-static `import a.b.*;` lines. What one brings in cannot be known from the source, so a
    # file with one keeps its long names rather than risking a clash `--fix` would compile into.
    has_wildcard: bool

    @classmethod
    def read(cls, path: Path) -> JavaFile:
        source = path.read_text(encoding="utf-8")
        code = blank_out(source)
        package_match = PACKAGE_RE.search(code)
        found = IMPORT_RE.findall(code)
        imports = {
            name.rsplit(".", 1)[1]: name
            for static, name, wildcard in found
            if not static and not wildcard
        }
        return cls(
            path=path, source=source, code=code,
            package=package_match.group(1) if package_match else "",
            imports=imports, declared=frozenset(DECLARED_RE.findall(code)),
            has_wildcard=any(wildcard and not static for static, _, wildcard in found),
        )

    @property
    def qualified(self) -> str:
        return f"{self.package}.{self.path.stem}" if self.package else self.path.stem

    def supertypes(self) -> set[str]:
        """Every type named after `extends` or `implements`, generics stripped, as written."""
        named: set[str] = set()
        for header in HEADER_RE.findall(self.code):
            while GENERICS_RE.search(header):
                header = GENERICS_RE.sub("", header)
            for clause in re.split(r"\b(?:extends|implements|permits)\b", header)[1:]:
                named.update(part.strip() for part in clause.split(",") if part.strip())
        return {name for name in named if re.fullmatch(r"[\w.]+", name)}


def collect(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for path in paths:
        files.extend(sorted(path.rglob("*.java")) if path.is_dir() else [path])
    return files


def classes_by_package(files: list[JavaFile]) -> dict[str, set[str]]:
    """Top-level class names per package, across every source set scanned."""
    found: dict[str, set[str]] = {}
    for java in files:
        found.setdefault(java.package, set()).add(java.path.stem)
    return found


def inherited_types(java: JavaFile, by_name: dict[str, JavaFile]) -> set[str]:
    """Types nested in this file's supertypes, transitively, as far as the scanned sources go.

    Inside a class body a member type inherited from a supertype shadows a single-type import of
    the same name - JLS 6.4.1 - so `import com.google...CameraPosition;` in a class implementing
    an interface that nests its own `CameraPosition` compiles to the wrong one. Supertypes outside
    the scanned sources (`View.OnClickListener` in a `View` subclass) cannot be seen from here;
    the compiler catches those.
    """
    found: set[str] = set()
    seen: set[str] = set()
    pending = [java]
    while pending:
        current = pending.pop()
        for name in current.supertypes():
            target = _resolve(name, current, by_name)
            if target is None or target.qualified in seen:
                continue
            seen.add(target.qualified)
            found.update(target.declared)
            pending.append(target)
    return found


def _resolve(name: str, java: JavaFile, by_name: dict[str, JavaFile]) -> JavaFile | None:
    top = name.split(".", 1)[0]
    if "." in name and top[:1].islower():
        return by_name.get(name)
    if top in java.imports:
        return by_name.get(java.imports[top])
    return by_name.get(f"{java.package}.{top}" if java.package else top)


def package_roots(files: list[JavaFile]) -> frozenset[str]:
    roots = set(BASE_ROOTS)
    for java in files:
        roots.update(name.split(".", 1)[0] for name in java.imports.values())
    return frozenset(roots)


def findings_in(java: JavaFile, same_package: set[str], roots: frozenset[str],
                inherited: set[str] | None = None) -> list[Finding]:
    candidates = list(_qualified_names(java, roots))
    targets_by_simple: dict[str, set[str]] = {}
    for finding in candidates:
        targets_by_simple.setdefault(finding.simple, set()).add(finding.qualified)

    return [
        finding for finding in candidates
        if not _must_stay_qualified(finding, java, same_package | (inherited or set()), targets_by_simple)
    ]


def _qualified_names(java: JavaFile, roots: frozenset[str]):
    for match in QUALIFIED_RE.finditer(java.code):
        if match.group(1).split(".", 1)[0] not in roots:
            continue
        line_start = java.code.rfind("\n", 0, match.start()) + 1
        line_text = java.code[line_start:java.code.find("\n", match.start())]
        if re.match(r"\s*(package|import)\s", line_text):
            continue
        yield Finding(
            path=java.path,
            line=java.code.count("\n", 0, match.start()) + 1,
            column=match.start() - line_start + 1,
            qualified=match.group(1) + match.group(2),
            start=match.start(), end=match.end(),
        )


def _must_stay_qualified(
        finding: Finding, java: JavaFile, same_package: set[str],
        targets_by_simple: dict[str, set[str]]) -> bool:
    """Whether the short name would mean something else here - the one licence for a long name."""
    simple = finding.simple
    if finding.package == java.package or finding.package == "java.lang":
        return False  # already in scope: the short name means exactly this
    if java.has_wildcard:
        return True
    imported = java.imports.get(simple)
    return (
        (imported is not None and imported != finding.qualified)
        or simple in java.declared
        or simple in same_package
        or simple in GENERATED_IN_PACKAGE
        or simple in JAVA_LANG
        or len(targets_by_simple[simple]) > 1
    )


def fix(java: JavaFile, findings: list[Finding]) -> str:
    """The source with each finding shortened and its import added."""
    source = java.source
    for finding in sorted(findings, key=lambda f: f.start, reverse=True):
        source = source[:finding.start] + finding.simple + source[finding.end:]

    needed = sorted({
        f.qualified for f in findings
        if f.package not in (java.package, "java.lang") and f.simple not in java.imports
    })
    return add_imports(source, needed) if needed else source


def add_imports(source: str, names: list[str]) -> str:
    lines = source.split("\n")
    new_lines = [f"import {name};" for name in names]
    import_lines = [i for i, line in enumerate(lines) if re.match(r"\s*import\s+(?!static\s)", line)]
    if import_lines:
        at = import_lines[-1] + 1
        return "\n".join(lines[:at] + new_lines + lines[at:])
    package_lines = [i for i, line in enumerate(lines) if re.match(r"\s*package\s", line)]
    at = package_lines[0] + 1 if package_lines else 0
    return "\n".join(lines[:at] + [""] + new_lines + lines[at:])


def scan(paths: list[Path]) -> list[tuple[JavaFile, list[Finding]]]:
    files = [JavaFile.read(path) for path in collect(paths)]
    by_package = classes_by_package(files)
    roots = package_roots(files)
    by_name = {java.qualified: java for java in files}
    result = []
    for java in files:
        found = findings_in(java, by_package.get(java.package, set()), roots,
                            inherited_types(java, by_name))
        if found:
            result.append((java, found))
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="*", type=Path, default=[DEFAULT_ROOT],
                        help="Java files or directories to scan (default: app/src)")
    parser.add_argument("--fix", action="store_true", help="shorten the names and add the imports")
    args = parser.parse_args(argv)

    findings = scan(args.paths)
    total = sum(len(found) for _, found in findings)

    for java, found in findings:
        if args.fix:
            java.path.write_text(fix(java, found), encoding="utf-8")
        for finding in found:
            print(f"{finding.path}:{finding.line}:{finding.column}: {finding.qualified} "
                  f"should be imported as {finding.simple}")

    if args.fix:
        print(f"Fixed {total} name(s) in {len(findings)} file(s).")
        return 0
    if total:
        print(f"\n{total} fully qualified name(s) in {len(findings)} file(s). "
              "Fix with: python scripts/check_java_imports.py --fix", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
