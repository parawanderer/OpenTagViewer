#!/usr/bin/env bash
# Run Apple's macOS CoreADI under Unicorn and under icicle, with the same inputs, and diff the
# results. See docs/emulated-mac-emulator-choice.md for why this exists and what it showed.
#
#     scripts/adi-emulator-diff/run.sh <path to the x86_64 CoreADI> [work dir]
#
# The library is the one the emulated Mac downloads from Apple (iTunes 12.8.3, CoreADI 4.19,
# sha256 2eafc3c7...). It is never committed; get it with
#     python scripts/locate_mac_coreadi.py --remote --out /tmp/CoreADI.x86_64
# (added by #274), or from an iTunes 12.8.3 disk image.
#
# Needs git, cargo and cmake. Clones three repositories at pinned commits into the work dir and
# sends nothing to Apple: every call made is answerable offline.
set -euo pipefail

OS_FINDMY_E=374d83854f657a7fdca45f813387d99d491db0e2
ICICLE=638508b72f480f9be6fe2e267ef09de8f1541f9a
GHIDRA=164003036607e669226647bd0f7bedaf281e56a6

here="$(cd "$(dirname "$0")" && pwd)"
dylib="$(cd "$(dirname "${1:?usage: run.sh <CoreADI.x86_64> [work dir]}")" && pwd)/$(basename "$1")"
work="${2:-${TMPDIR:-/tmp}/adi-emulator-diff}"
mkdir -p "$work" && cd "$work"

fetch() { # url commit dir [sparse path]
    if [ ! -d "$3" ]; then
        git init -q "$3"
        git -C "$3" remote add origin "$1"
        if [ -n "${4:-}" ]; then
            git -C "$3" sparse-checkout set "$4"
        fi
    fi
    git -C "$3" fetch -q --depth 1 --filter=blob:none origin "$2"
    git -C "$3" checkout -q FETCH_HEAD
}
fetch https://github.com/sacca97/os-findmy-e "$OS_FINDMY_E" os-findmy-e
fetch https://github.com/icicle-emu/icicle-emu "$ICICLE" icicle-emu
fetch https://github.com/NationalSecurityAgency/ghidra "$GHIDRA" ghidra Ghidra/Processors/x86

core=os-findmy-e/native/adi-core
git -C os-findmy-e checkout -q -- native/adi-core
mkdir -p "$core/src/bin"
cp "$here/vm_icicle.rs" "$core/src/"
cp "$here/adi-diff.rs" "$core/src/bin/"

# vm.rs's userland, verbatim, with only the CPU type swapped for the icicle shim.
python3 - "$core" <<'EOF'
import sys
core = sys.argv[1]
src = open(f"{core}/src/vm.rs").read()
body = src[:src.index("/// The pristine state")]
body = "\n".join(l for l in body.splitlines() if not l.startswith("//!")) + "\n"
for old, new in [
    ("use unicorn_engine::unicorn_const::{Arch, HookType, MemType, Mode, Prot};\n"
     "use unicorn_engine::{RegisterX86, Unicorn};\n", "use shim::{Cpu as Uc, RegisterX86};\n"),
    ("type Uc<'a> = Unicorn<'a, Env>;\n", ""),
    ("Uc<'_>", "Uc"),
]:
    if old not in body:
        sys.exit(f"vm.rs no longer contains {old!r}; the generator needs updating")
    body = body.replace(old, new)
open(f"{core}/src/vm_icicle_env.rs", "w").write(body)

toml = open(f"{core}/Cargo.toml").read()
toml = toml.replace('jni = { version = "0.21", optional = true }',
    'jni = { version = "0.21", optional = true }\n'
    'icicle-vm = { path = "../../../icicle-emu/icicle-vm", optional = true }\n'
    'pcode = { path = "../../../icicle-emu/sleigh/pcode", optional = true }')
toml = toml.replace('jni = ["dep:jni"]', 'jni = ["dep:jni"]\nicicle = ["dep:icicle-vm", "dep:pcode"]')
open(f"{core}/Cargo.toml", "w").write(toml)

lib = open(f"{core}/src/lib.rs").read()
lib = lib.replace("mod vm;\n", '#[cfg(not(feature = "icicle"))]\nmod vm;\n'
                  '#[cfg(feature = "icicle")]\n#[path = "vm_icicle.rs"]\nmod vm;\n', 1)
open(f"{core}/src/lib.rs", "w").write(lib)
EOF

(cd "$core" && cargo build -q --release --bin adi-diff --target-dir target-unicorn)
(cd "$core" && cargo build -q --release --bin adi-diff --features icicle --target-dir target-icicle)

export GHIDRA_SRC="$work/ghidra"
"$core/target-unicorn/release/adi-diff" "$dylib" > unicorn.txt
ICICLE_NO_JIT=1 "$core/target-icicle/release/adi-diff" "$dylib" > icicle.txt
"$core/target-icicle/release/adi-diff" "$dylib" > icicle-jit.txt

cat unicorn.txt
for out in icicle.txt icicle-jit.txt; do
    if diff -u unicorn.txt "$out"; then
        echo "$out: identical to Unicorn"
    else
        echo "$out: DIFFERS from Unicorn" >&2
        exit 1
    fi
done
