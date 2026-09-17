#!/usr/bin/env bash
# The same as extract_bionic.sh, for API 29+ images, where linker64 and libc live in
# the com.android.runtime APEX. Mounts images read-only (needs sudo) rather than using debugfs, so
# it copes with erofs as well as ext4.
#
#   extract_bionic_apex.sh <sys-img zip url> <sha1> <out dir> <root library>...
set -euo pipefail
url="$1"; sha1="$2"; out="$(realpath -m "$3")"; shift 3
work="$(mktemp -d)"
mounts=()
cleanup() { for m in "${mounts[@]}"; do sudo umount "$m" 2>/dev/null || true; done; rm -rf "$work"; }
trap cleanup EXIT

curl -fsSL -o "$work/img.zip" "$url"
echo "$sha1  $work/img.zip" | sha1sum -c -
unzip -l "$work/img.zip"
member="$(unzip -Z1 "$work/img.zip" | grep -E '(^|/)system\.img$' | head -1)"
unzip -p "$work/img.zip" "$member" > "$work/system.img"
rm "$work/img.zip"
fs="$work/system.img"
echo "system.img: $(file -b "$fs")"
if [[ "$(file -b "$fs")" == *"Android sparse image"* ]]; then
  sudo apt-get install -y -qq android-sdk-libsparse-utils >/dev/null
  simg2img "$fs" "$work/raw.img"; rm "$fs"; fs="$work/raw.img"
fi
if [[ "$(file -b "$fs")" == *"boot sector"* ]]; then
  sfdisk -J "$fs" | python3 -c 'import json,sys; [print(" ", p["name"], p["start"], p["size"]) for p in json.load(sys.stdin)["partitiontable"]["partitions"]]'
  read -r name start size < <(sfdisk -J "$fs" | python3 -c '
import json, sys
parts = json.load(sys.stdin)["partitiontable"]["partitions"]
p = next((p for p in parts if p.get("name") in ("super", "system")), max(parts, key=lambda p: p["size"]))
print(p.get("name"), p["start"], p["size"])')
  if [[ "$name" == super ]]; then
    # API 30+: dynamic partitions. system is a logical partition inside super.
    python3 "$(dirname "$0")/lp_extract.py" "$fs" $((start * 512)) system "$work/part.img"
  else
    dd if="$fs" of="$work/part.img" bs=4M iflag=skip_bytes,count_bytes \
       skip=$((start * 512)) count=$((size * 512)) status=none
  fi
  rm "$fs"; fs="$work/part.img"
fi
echo "filesystem: $(file -b "$fs")"

# Mount read-only; failing that (no erofs module), unpack with erofs-utils.
mount_ro() {
  mkdir -p "$2"
  if sudo mount -o loop,ro "$1" "$2" 2>/dev/null; then mounts=("$2" "${mounts[@]}"); return; fi
  if [[ "$(file -b "$1")" == *EROFS* ]] || head -c 1028 "$1" | tail -c 4 | od -An -tx4 | grep -q e0f5e1e2; then
    command -v fsck.erofs >/dev/null || sudo apt-get install -y -qq erofs-utils >/dev/null
    sudo fsck.erofs --extract="$2" "$1" >/dev/null && sudo chown -R "$(id -u)" "$2" && return
  fi
  echo "cannot open $1: $(file -b "$1")" >&2; return 1
}
mount_ro "$fs" "$work/sys"
sys="$work/sys"; [[ -d "$sys/system/bin" ]] && sys="$sys/system"
ls -la "$sys/bin/linker64" || true
ls "$sys/apex" | tr '\n' ' '; echo

# Every APEX, mounted, so a library can be looked for in all of them.
search=()
for apex in "$sys"/apex/*; do
  name="$(basename "$apex")"; name="${name%.apex}"; name="${name%.capex}"
  if [[ -d "$apex" ]]; then
    search+=("$apex")                                   # flattened APEX
    continue
  fi
  d="$work/apex/$name"; mkdir -p "$d"
  if [[ "$apex" == *.capex ]]; then
    unzip -p "$apex" original_apex > "$d.apex"; apex="$d.apex"
  fi
  unzip -p "$apex" apex_payload.img > "$d.img" 2>/dev/null || { echo "no payload in $name"; continue; }
  mount_ro "$d.img" "$d" || { echo "could not mount $name ($(file -b "$d.img"))"; continue; }
  search+=("$d")
done
runtime="$(printf '%s\n' "${search[@]}" | grep -E '/com\.android\.runtime$' | head -1)"
echo "runtime APEX: $runtime"

mkdir -p "$out/system/bin" "$out/system/lib64"
cp "$runtime/bin/linker64" "$out/system/bin/linker64"
chmod 755 "$out/system/bin/linker64"

find_lib() {
  for dir in "$runtime/lib64/bionic" "$runtime/lib64" "$sys/lib64" "${search[@]/%//lib64}"; do
    [[ -f "$dir/$1" ]] && { echo "$dir/$1"; return; }
  done
}
queue=("$@"); declare -A seen=(); missing=()
while ((${#queue[@]})); do
  lib="${queue[0]}"; queue=("${queue[@]:1}")
  [[ -n "${seen[$lib]:-}" ]] && continue
  seen[$lib]=1
  src="$(find_lib "$lib" || true)"
  if [[ -z "$src" ]]; then missing+=("$lib"); continue; fi
  echo "  $lib <- ${src#$work/}"
  cp -L "$src" "$out/system/lib64/$lib"
  while read -r dep; do queue+=("$dep"); done < <(
    readelf -d "$out/system/lib64/$lib" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
done
echo "extracted linker64 + $(ls "$out/system/lib64" | wc -l) libraries, $(du -sh "$out/system" | cut -f1)"
if ((${#missing[@]})); then echo "NOT FOUND: ${missing[*]}" >&2; exit 1; fi
