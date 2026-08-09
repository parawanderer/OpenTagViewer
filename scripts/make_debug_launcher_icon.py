"""Generate the debug launcher icon by inverting the release one.

Debug builds install alongside a real one (see applicationIdSuffix in
app/build.gradle.kts). Two identical icons on a launcher are easy to confuse, which
matters here because the two apps hold separate databases and the real one holds data
that cannot be recovered if it is lost.

The launcher foreground is a .webp per density, and it is full-bleed - it covers the
adaptive icon's background entirely, so recolouring the background alone has no visible
effect. This inverts the foreground images instead, preserving alpha so the adaptive
icon's shape masking still works.

Writes into app/src/debug/res/, which overrides app/src/main/res/ for debug builds only.

Usage:
    python scripts/make_debug_launcher_icon.py
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    from PIL import Image, ImageChops
except ImportError:
    print("Pillow is required: pip install pillow", file=sys.stderr)
    raise SystemExit(2) from None

REPO_ROOT = Path(__file__).resolve().parent.parent
MAIN_RES = REPO_ROOT / "app" / "src" / "main" / "res"
DEBUG_RES = REPO_ROOT / "app" / "src" / "debug" / "res"

# Only the foreground needs inverting; it covers the background completely.
ICON_NAME = "ic_launcher_foreground.webp"
DENSITIES = ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")


def invert_preserving_alpha(source: Path, destination: Path) -> None:
    with Image.open(source) as image:
        image = image.convert("RGBA")
        rgb = Image.merge("RGB", image.split()[:3])
        inverted = ImageChops.invert(rgb)
        alpha = image.split()[3]
        result = Image.merge("RGBA", (*inverted.split(), alpha))

        destination.parent.mkdir(parents=True, exist_ok=True)
        result.save(destination, "WEBP", lossless=True)


def main() -> int:
    written = 0
    for density in DENSITIES:
        source = MAIN_RES / f"mipmap-{density}" / ICON_NAME
        if not source.is_file():
            print(f"  skipping {density}: no source icon")
            continue

        destination = DEBUG_RES / f"mipmap-{density}" / ICON_NAME
        invert_preserving_alpha(source, destination)
        print(f"  wrote {destination.relative_to(REPO_ROOT)}")
        written += 1

    if written == 0:
        print("No icons written - is the source path right?", file=sys.stderr)
        return 1

    print(f"\nInverted {written} icon(s) into app/src/debug/res/.")
    print("Re-run this if the release icon ever changes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
