from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path


BOM = b"\xef\xbb\xbf"


def validate_wheel(path: Path) -> list[str]:
    errors: list[str] = []

    if not path.exists():
        return [f"{path}: file not found"]

    try:
        with zipfile.ZipFile(path) as zf:
            dist_info_dirs = sorted(
                {
                    name.rsplit("/", 1)[0]
                    for name in zf.namelist()
                    if name.endswith(".dist-info/WHEEL")
                }
            )
            if len(dist_info_dirs) != 1:
                return [f"{path}: expected exactly one .dist-info/WHEEL entry, found {len(dist_info_dirs)}"]

            dist_info_dir = dist_info_dirs[0]
            wheel_bytes = zf.read(f"{dist_info_dir}/WHEEL")
            if wheel_bytes.startswith(BOM):
                errors.append(f"{path}: {dist_info_dir}/WHEEL starts with UTF-8 BOM")

            wheel_text = wheel_bytes.decode("utf-8-sig")
            if not any(line.startswith("Wheel-Version:") for line in wheel_text.splitlines()):
                errors.append(f"{path}: {dist_info_dir}/WHEEL is missing Wheel-Version")

            metadata_bytes = zf.read(f"{dist_info_dir}/METADATA")
            if metadata_bytes.startswith(BOM):
                errors.append(f"{path}: {dist_info_dir}/METADATA starts with UTF-8 BOM")

            record_bytes = zf.read(f"{dist_info_dir}/RECORD")
            if record_bytes.startswith(BOM):
                errors.append(f"{path}: {dist_info_dir}/RECORD starts with UTF-8 BOM")
    except zipfile.BadZipFile:
        return [f"{path}: invalid zip archive"]
    except KeyError as exc:
        return [f"{path}: missing required wheel entry: {exc}"]

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate local stub wheels checked into the repository."
    )
    parser.add_argument(
        "wheels",
        nargs="+",
        type=Path,
        help="Wheel files to validate.",
    )
    args = parser.parse_args()

    all_errors: list[str] = []
    for wheel in args.wheels:
        all_errors.extend(validate_wheel(wheel))

    if all_errors:
        for error in all_errors:
            print(error, file=sys.stderr)
        return 1

    for wheel in args.wheels:
        print(f"{wheel}: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
