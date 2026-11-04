#!/usr/bin/env bash
# Run os-findmy-e's Rust adi-core and this repository's C++ port (app/src/main/cpp/macadi/, which
# is libmacadi.so) through the same offline CoreADI calls, and diff the output byte for byte.
#
#     scripts/adi-emulator-diff/compare-libmacadi.sh <CoreADI.x86_64> [provisioned store dir] [work dir]
#
# The calls, the frozen clock, the host UUID and the synthetic spims are adi-diff.rs's, the driver
# run.sh uses to compare Unicorn with icicle; app/src/test/macadi-host/macadi_diff.cpp replays the
# same sequence against the port and prints the same lines. A difference is a port bug, of the
# kind a single successful sign-in would not show.
#
# With a provisioned store directory both also run one header session on that machine with the
# clock frozen, which reaches the OTP path. Use a copy, and never a store an app is still using:
# nothing here writes it, but it is the machine's identity. Without one, nothing is sent anywhere
# and nothing needs Apple but the library itself:
#     python scripts/locate_mac_coreadi.py --remote --out /tmp/CoreADI.x86_64
#
# Needs git, cargo, cmake and a C++17 compiler.
set -euo pipefail

OS_FINDMY_E=374d83854f657a7fdca45f813387d99d491db0e2
DISPATCH=979a0

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
dylib="$(cd "$(dirname "${1:?usage: compare-libmacadi.sh <CoreADI.x86_64> [store dir] [work dir]}")" && pwd)/$(basename "$1")"
store=""
if [ -n "${2:-}" ]; then
    store="$(cd "$2" && pwd)"
fi
work="${3:-${TMPDIR:-/tmp}/adi-emulator-diff-libmacadi}"
mkdir -p "$work" && cd "$work"

if [ ! -d os-findmy-e ]; then
    git init -q os-findmy-e
    git -C os-findmy-e remote add origin https://github.com/sacca97/os-findmy-e
fi
git -C os-findmy-e fetch -q --depth 1 --filter=blob:none origin "$OS_FINDMY_E"
git -C os-findmy-e checkout -q FETCH_HEAD
git -C os-findmy-e checkout -q -- native/adi-core

core=os-findmy-e/native/adi-core
mkdir -p "$core/src/bin"
cp "$here/adi-diff.rs" "$core/src/bin/"
(cd "$core" && cargo build -q --release --locked --bin adi-diff --target-dir target-unicorn)

cmake -S "$repo/app/src/test/macadi-host" -B macadi-host -DCMAKE_BUILD_TYPE=Release > /dev/null
cmake --build macadi-host --parallel --target macadi_diff > /dev/null

"$core/target-unicorn/release/adi-diff" "$dylib" ${store:+"$store"} > adi-core.txt
macadi-host/macadi_diff "$dylib" "$DISPATCH" ${store:+"$store"} > libmacadi.txt

cat adi-core.txt
if diff -u adi-core.txt libmacadi.txt; then
    echo "libmacadi: identical to adi-core ($(wc -l < adi-core.txt) lines)"
else
    echo "libmacadi: DIFFERS from adi-core" >&2
    exit 1
fi
