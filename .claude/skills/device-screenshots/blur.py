#!/usr/bin/env python3
"""Blur a horizontal band of a screenshot, for images that go on the wiki.

The AMap key dialog prints the package name and signing fingerprint of whatever build took
the screenshot, because that is exactly what AMap asks you to paste into its console. On a
debug build those identify one developer's machine, and they have no business on a public
page - the reader needs to see *that the dialog shows them*, not what mine are.

Blurring rather than cropping on purpose: a cropped screenshot looks like the dialog has two
fields, and the next person wonders where the other lines went.

    python scripts/blur_wiki_lines.py shot.png --band 0.42 0.55

--band takes fractions of the image height, so it does not depend on the device's resolution.
Repeat it for more than one band. Writes in place unless --out is given.
"""
import argparse
import sys

try:
    # Suppressed for the reason spelled out in sheet.py beside this.
    from PIL import Image, ImageFilter  # pyright: ignore[reportMissingImports]
except ImportError:
    sys.exit("needs Pillow: py -3 -m pip install pillow")


def blur_band(image, top, bottom, radius, left=0.0, right=1.0):
    """Blur one strip, given as fractions of the height (and optionally the width).

    Bounding it horizontally matters more than it sounds. A band across the full width of a
    screenshot of a *dialog* also blurs the dimmed page behind it, and the result is a pale
    rectangle running off both sides of the dialog - which reads as a rendering fault rather
    than as a redaction.
    """
    box = (int(image.width * left), int(image.height * top),
           int(image.width * right), int(image.height * bottom))
    region = image.crop(box)

    # Downscale and back up before blurring. A Gaussian blur alone leaves text legible to
    # anything that sharpens it, and the point here is that the characters are gone, not
    # merely soft.
    small = region.resize((max(1, region.width // 12), max(1, region.height // 12)))
    region = small.resize(region.size, Image.NEAREST).filter(ImageFilter.GaussianBlur(radius))

    image.paste(region, box)
    return image


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("image")
    parser.add_argument("--band", nargs=2, type=float, action="append", metavar=("TOP", "BOTTOM"),
                        required=True, help="fractions of image height, e.g. --band 0.42 0.55")
    parser.add_argument("--x", nargs=2, type=float, metavar=("LEFT", "RIGHT"), default=[0.0, 1.0],
                        help="fractions of the width to bound every band to, e.g. --x 0.1 0.9")
    parser.add_argument("--radius", type=float, default=6.0)
    parser.add_argument("--out", help="defaults to overwriting the input")
    args = parser.parse_args()

    left, right = args.x
    if not 0.0 <= left < right <= 1.0:
        sys.exit(f"--x {left} {right} is not a fraction of the width, low to high")

    image = Image.open(args.image).convert("RGB")
    for top, bottom in args.band:
        if not 0.0 <= top < bottom <= 1.0:
            sys.exit(f"band {top}-{bottom} is not a fraction of the height, low to high")
        blur_band(image, top, bottom, args.radius, left, right)

    out = args.out or args.image
    image.save(out)
    print(f"blurred {len(args.band)} band(s) -> {out}")


if __name__ == "__main__":
    main()
