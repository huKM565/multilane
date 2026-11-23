#!/usr/bin/env python3
from PIL import Image, ImageDraw, ImageFont
import json
import math
import os
import sys

SS = 8

BG = "#07080D"
CYAN = "#22D3EE"
CYAN_BRIGHT = "#67E8F9"
CYAN_MID = "#0891B2"
CORAL = "#FF8A65"

DENSITIES = {
    "hdpi": (72, 162, 36),
    "xhdpi": (96, 216, 48),
    "xxhdpi": (144, 324, 72),
    "xxxhdpi": (192, 432, 96),
}

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

LANE_ANGLE_DEG = 32.0
STREAMS = (
    (-5.6, -9.2, 4.2, 2.5, CYAN_MID, 0.78),
    (0.0, -10.4, 8.2, 2.85, CYAN, 1.0),
    (5.6, -6.2, 9.6, 2.5, CORAL, 1.0),
)

INK_SPAN = 25.0
INK_CENTRE_Y = 16.0
INK_RADIUS = 13.0

ADAPTIVE_SAFE_DIAMETER = (72.0 / 108.0) * 0.96
ADAPTIVE_INK_FRACTION = ADAPTIVE_SAFE_DIAMETER * INK_SPAN / (2 * INK_RADIUS)


def _hex_rgb(colour: str) -> tuple[int, int, int]:
    return tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))


def _blend(colour: str, alpha: float) -> tuple[int, int, int, int]:
    c = _hex_rgb(colour)
    b = _hex_rgb(BG)
    return tuple(round(b[i] + (c[i] - b[i]) * alpha) for i in range(3)) + (255,)


def _rounded_line(d: ImageDraw.ImageDraw, p0, p1, width: int, fill) -> None:
    d.line([p0, p1], fill=fill, width=width)
    r = width / 2.0
    for x, y in (p0, p1):
        d.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def _mark(d: ImageDraw.ImageDraw, u: float, cx: float, cy: float, mono: bool) -> None:
    ang = math.radians(LANE_ANGLE_DEG)
    dx, dy = math.cos(ang), -math.sin(ang)
    px, py = -dy, dx

    for off, t0, t1, stroke, col, alpha in STREAMS:
        ox, oy = px * off * u, py * off * u
        w = max(1, round(stroke * u))
        p0 = (cx + dx * t0 * u + ox, cy + dy * t0 * u + oy)
        p1 = (cx + dx * t1 * u + ox, cy + dy * t1 * u + oy)
        fill = (255, 255, 255, round(255 * alpha)) if mono else _blend(col, alpha)
        _rounded_line(d, p0, p1, w, fill)

        trail = (cx + dx * (t0 - 2.6) * u + ox, cy + dy * (t0 - 2.6) * u + oy)
        tr = (stroke * 0.44) * u
        tfill = (255, 255, 255, round(255 * 0.42)) if mono else _blend(col, 0.38)
        d.ellipse([trail[0] - tr, trail[1] - tr, trail[0] + tr, trail[1] + tr], fill=tfill)

    dot_r = 1.75 * u
    ox, oy = px * (-5.6) * u, py * (-5.6) * u
    hx = cx + dx * 8.4 * u + ox
    hy = cy + dy * 8.4 * u + oy
    hfill = (255, 255, 255, 255) if mono else _hex_rgb(CYAN_BRIGHT) + (255,)
    d.ellipse([hx - dot_r, hy - dot_r, hx + dot_r, hy + dot_r], fill=hfill)


def tile(size: int) -> Image.Image:
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = s / 32.0
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=7 * u, fill=BG)
    d.rounded_rectangle(
        [0.6 * u, 0.6 * u, s - 1 - 0.6 * u, s - 1 - 0.6 * u],
        radius=6.5 * u,
        outline=(34, 211, 238, 32),
        width=max(1, round(0.35 * u)),
    )
    _mark(d, u, s / 2.0, s / 2.0, mono=False)
    return img.resize((size, size), Image.LANCZOS)


def adaptive_background(size: int) -> Image.Image:
    return Image.new("RGBA", (size, size), BG)


def _glyph_only(size: int, fraction: float, mono: bool) -> Image.Image:
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = (s * fraction) / INK_SPAN
    _mark(d, u, s / 2.0, s / 2.0 + (16.0 - INK_CENTRE_Y) * u, mono=mono)
    return img.resize((size, size), Image.LANCZOS)


def adaptive_foreground(size: int) -> Image.Image:
    return _glyph_only(size, fraction=ADAPTIVE_INK_FRACTION, mono=False)


def qs_tile(size: int) -> Image.Image:
    return _glyph_only(size, fraction=0.90, mono=True)


def ios_icon(size: int) -> Image.Image:
    s = size * SS
    img = Image.new("RGB", (s, s), BG)
    d = ImageDraw.Draw(img)
    u = (s * 0.72) / INK_SPAN
    _mark(d, u, s / 2.0, s / 2.0 + (16.0 - INK_CENTRE_Y) * u, mono=False)
    return img.resize((size, size), Image.LANCZOS)


def tv_banner() -> Image.Image:
    w, h = 320, 180
    ss = 4
    img = Image.new("RGB", (w * ss, h * ss), BG)
    d = ImageDraw.Draw(img)
    u = (h * ss * 0.48) / 32.0
    _mark(d, u, 74 * ss, h * ss / 2.0, mono=False)

    font = None
    for path in (
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ):
        if os.path.exists(path):
            font = ImageFont.truetype(path, 34 * ss)
            break
    if font is None:
        font = ImageFont.load_default()
    bbox = d.textbbox((0, 0), "MultiLane", font=font)
    th = bbox[3] - bbox[1]
    ty = (h * ss - th) / 2.0 - bbox[1]
    d.text((122 * ss, ty), "MultiLane", fill="#F1F5F9", font=font)
    return img.resize((w, h), Image.LANCZOS)


def save(img: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print(f"wrote {path} {img.size}")


def main(root: str) -> None:
    res = os.path.join(root, "androidApp/src/main/res")

    for dpi, (legacy_px, adaptive_px, tile_px) in DENSITIES.items():
        base = os.path.join(res, f"mipmap-{dpi}")
        save(tile(legacy_px), os.path.join(base, "ic_launcher.png"))
        save(adaptive_background(adaptive_px), os.path.join(base, "ic_launcher_background.png"))
        save(adaptive_foreground(adaptive_px), os.path.join(base, "ic_launcher_foreground.png"))
        save(qs_tile(tile_px), os.path.join(base, "ic_qs_tile.png"))

    xml_dir = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(xml_dir, exist_ok=True)
    xml_path = os.path.join(xml_dir, "ic_launcher.xml")
    with open(xml_path, "w") as fh:
        fh.write(ADAPTIVE_XML)
    print(f"wrote {xml_path}")

    save(tile(512), os.path.join(res, "playstore_icon.png"))
    save(tv_banner(), os.path.join(res, "drawable-xhdpi/tv_banner.png"))

    icons = os.path.join(root, "desktopApp/appIcons")
    save(tile(512), os.path.join(icons, "LinuxIcon.png"))

    master = tile(1024)
    ico_path = os.path.join(icons, "WindowsIcon.ico")
    master.save(
        ico_path,
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )
    print(f"wrote {ico_path}")

    icns_path = os.path.join(icons, "MacosIcon.icns")
    master.save(icns_path)
    print(f"wrote {icns_path}")

    appiconset = os.path.join(root, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
    save(ios_icon(1024), os.path.join(appiconset, "AppIcon1024.png"))

    with open(os.path.join(root, "iosApp/iosApp/Assets.xcassets/Contents.json"), "w") as fh:
        json.dump({"info": {"author": "xcode", "version": 1}}, fh, indent=2)
        fh.write("\n")
    with open(os.path.join(appiconset, "Contents.json"), "w") as fh:
        json.dump(
            {
                "images": [
                    {
                        "filename": "AppIcon1024.png",
                        "idiom": "universal",
                        "platform": "ios",
                        "size": "1024x1024",
                    }
                ],
                "info": {"author": "xcode", "version": 1},
            },
            fh,
            indent=2,
        )
        fh.write("\n")
    print("wrote iOS asset catalog")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
