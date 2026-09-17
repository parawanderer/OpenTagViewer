#!/usr/bin/env bash
# Extract Android's runtime from an AOSP emulator system image: /system/bin/linker64 plus the
# DT_NEEDED closure, inside /system/lib64, of the libraries named on the command line.
#
#   extract_bionic.sh <sys-img zip url> <sha1> <out dir> <root library>...
#
# Leaves <out>/system/bin/linker64 and <out>/system/lib64/*.so. API 28 images only: from API 29
# on, linker64 and libc live in the com.android.runtime APEX, which this does not unpack.
set -euo pipefail
url="$1"; sha1="$2"; out="$3"; shift 3
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl -fsSL -o "$work/img.zip" "$url"
echo "$sha1  $work/img.zip" | sha1sum -c -
echo "downloaded $(stat -c %s "$work/img.zip") bytes"

member="$(unzip -Z1 "$work/img.zip" | grep -E '(^|/)system\.img$' | head -1)"
unzip -p "$work/img.zip" "$member" > "$work/system.img"
rm "$work/img.zip"
fs="$work/system.img"
kind="$(file -b "$fs")"
echo "system.img: $(stat -c %s "$fs") bytes, $kind"

if [[ "$kind" == *"Android sparse image"* ]]; then
  sudo apt-get install -y -qq android-sdk-libsparse-utils >/dev/null
  simg2img "$fs" "$work/raw.img"; rm "$fs"; fs="$work/raw.img"; kind="$(file -b "$fs")"
fi
if [[ "$kind" != *"ext"*"filesystem"* ]]; then
  # A GPT disk image (what API 28 emulator images are): take the partition named system.
  read -r start size < <(sfdisk -J "$fs" | python3 -c '
import json, sys
parts = json.load(sys.stdin)["partitiontable"]["partitions"]
p = next((p for p in parts if p.get("name") == "system"), max(parts, key=lambda p: p["size"]))
print(p["start"], p["size"])')
  dd if="$fs" of="$work/part.img" bs=4M iflag=skip_bytes,count_bytes \
     skip=$((start * 512)) count=$((size * 512)) status=none
  rm "$fs"; fs="$work/part.img"
fi

# System-as-root images keep everything under /system; older ones mount it at /.
prefix=""
debugfs -R "stat /system/bin/linker64" "$fs" 2>/dev/null | grep -q '^Inode' && prefix="/system"
if debugfs -R "stat $prefix/bin/linker64" "$fs" 2>/dev/null | grep -q 'Type: symlink'; then
  echo "linker64 is a symlink (runtime APEX) - this script handles API 28 images only" >&2
  exit 1
fi

mkdir -p "$out/system/bin" "$out/system/lib64"
debugfs -R "dump $prefix/bin/linker64 $out/system/bin/linker64" "$fs" 2>/dev/null
chmod 755 "$out/system/bin/linker64"

queue=("$@"); declare -A seen=()
while ((${#queue[@]})); do
  lib="${queue[0]}"; queue=("${queue[@]:1}")
  [[ -n "${seen[$lib]:-}" ]] && continue
  seen[$lib]=1
  debugfs -R "dump $prefix/lib64/$lib $out/system/lib64/$lib" "$fs" 2>/dev/null
  if [[ ! -s "$out/system/lib64/$lib" ]]; then
    echo "not in the image: $lib" >&2; exit 1
  fi
  while read -r dep; do queue+=("$dep"); done < <(
    readelf -d "$out/system/lib64/$lib" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
done
echo "extracted linker64 + ${#seen[@]} libraries, $(du -sh "$out/system" | cut -f1)"
