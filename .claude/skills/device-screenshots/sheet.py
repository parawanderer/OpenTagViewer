"""Turn a directory of device screenshots into a few small, labelled sheets.

Screenshots off an Android device are ~1080px wide and mostly whitespace. Reading them at full
size spends a large number of vision tokens on a picture whose informative part is a few hundred
pixels, and reading a before/after pair separately doubles that for a comparison the eye wants
side by side anyway.

This groups files by subject, puts each subject's variants in one sheet, and scales them down.

Naming
------
Files are grouped on the part before the last hyphen, and the part after it is the variant:

    my_device_list_item-fixed.png      -> subject "my_device_list_item", variant "fixed"
    my_device_list_item-wallpaper.png                                    variant "wallpaper"

Anything with no hyphen becomes its own single-panel sheet.

Usage
-----
    python .claude/skills/device-screenshots/sheet.py <input-dir> <output-dir>
    python .claude/skills/device-screenshots/sheet.py <in> <out> --width 420 --max-height 400
    python .claude/skills/device-screenshots/sheet.py <in> <out> --dark wallpaper_dark,dark

Two behaviours worth knowing, both learned the hard way:

* Transparent pixels are composited over a background rather than dropped. Several layouts here
  have no background of their own, and converting them straight to RGB renders them solid black.
* Tall narrow images (the timeline tiles are 13x48) are laid out side by side and capped by
  height. Scaling those to a fixed width produced a 300x2252 sheet.

Needs Pillow. If it is missing:  python -m pip install pillow
"""

from __future__ import annotations

import argparse
import sys
from collections import OrderedDict
from pathlib import Path

try:
    # Pillow is not in the exporter venv pyright resolves against, so the pre-commit hook would
    # reject this file for a dependency it is right not to have. Suppressed at the line rather
    # than in pyrightconfig.json, which would hide genuinely missing imports everywhere else.
    from PIL import Image, ImageDraw  # pyright: ignore[reportMissingImports]
except ImportError:  # pragma: no cover - the message is the whole point
    sys.exit("Pillow is required: python -m pip install pillow")

LABEL_HEIGHT = 15
PADDING = 10

# Panels for a dark variant are composited over near-black instead of white, or a dark-mode
# screenshot with transparency comes out as dark-on-white and looks broken rather than dark.
LIGHT_BACKGROUND = (255, 255, 255)
DARK_BACKGROUND = (18, 18, 18)

# Above this ratio an image is treated as a tall sliver and laid out horizontally, capped by
# height rather than width.
TALL_RATIO = 2.0


def load(path: Path, background: tuple[int, int, int]) -> Image.Image:
    raw = Image.open(path).convert("RGBA")
    canvas = Image.new("RGBA", raw.size, background + (255,))
    return Image.alpha_composite(canvas, raw).convert("RGB")


def group_by_subject(source: Path) -> "OrderedDict[str, list[tuple[str, Path]]]":
    groups: "OrderedDict[str, list[tuple[str, Path]]]" = OrderedDict()

    for path in sorted(source.glob("*.png")):
        stem = path.stem
        subject, _, variant = stem.rpartition("-")

        if not subject:
            subject, variant = stem, ""

        groups.setdefault(subject, []).append((variant, path))

    return groups


def build_sheet(
        panels: list[tuple[str, Image.Image]],
        horizontal: bool) -> Image.Image:
    """Lay the panels out with a label above each."""
    if horizontal:
        width = sum(p.width for _, p in panels) + PADDING * (len(panels) + 1)
        height = max(p.height for _, p in panels) + LABEL_HEIGHT + PADDING
    else:
        width = max(p.width for _, p in panels)
        height = sum(p.height + LABEL_HEIGHT for _, p in panels)

    sheet = Image.new("RGB", (width, height), LIGHT_BACKGROUND)
    draw = ImageDraw.Draw(sheet)

    if horizontal:
        x = PADDING
        for label, panel in panels:
            draw.text((x, 2), label, fill=(0, 0, 0))
            sheet.paste(panel, (x, LABEL_HEIGHT))
            x += panel.width + PADDING
    else:
        y = 0
        for label, panel in panels:
            draw.text((4, y + 2), label, fill=(0, 0, 0))
            y += LABEL_HEIGHT
            sheet.paste(panel, (0, y))
            y += panel.height

    return sheet


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=Path, help="directory of .png screenshots")
    parser.add_argument("destination", type=Path, help="where to write the sheets")
    parser.add_argument("--width", type=int, default=420,
                        help="target width per panel for ordinary images (default 420)")
    parser.add_argument("--max-height", type=int, default=420,
                        help="cap on panel height (default 420)")
    parser.add_argument("--dark", default="dark,wallpaper_dark,night",
                        help="comma-separated variant names to composite over black")

    args = parser.parse_args(argv)

    if not args.source.is_dir():
        print(f"No such directory: {args.source}", file=sys.stderr)
        return 2

    dark_variants = {v.strip() for v in args.dark.split(",") if v.strip()}
    args.destination.mkdir(parents=True, exist_ok=True)

    groups = group_by_subject(args.source)
    if not groups:
        print(f"No .png files in {args.source}", file=sys.stderr)
        return 1

    for subject, entries in groups.items():
        panels: list[tuple[str, Image.Image]] = []
        horizontal = False

        for variant, path in entries:
            background = DARK_BACKGROUND if variant in dark_variants else LIGHT_BACKGROUND
            image = load(path, background)

            # A tall sliver is capped by height and the sheet runs sideways; anything else is
            # scaled to the target width.
            if image.height > image.width * TALL_RATIO:
                horizontal = True
                scale = min(args.max_height / image.height, 1.0)
            else:
                scale = min(args.width / image.width, args.max_height / image.height, 1.0)

            image = image.resize(
                (max(1, int(image.width * scale)), max(1, int(image.height * scale))),
                Image.LANCZOS)

            panels.append((variant or subject, image))

        sheet = build_sheet(panels, horizontal)
        target = args.destination / f"{subject}.png"
        sheet.save(target, optimize=True)

        print(f"{target}  {sheet.width}x{sheet.height}  ({len(panels)} panel(s))")

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
