#!/usr/bin/env python3
"""Feature graphic (kiemelo kep) 1024x500, alpha nelkul, Play-kesz.

Stilus: play_white szett (feher #F0F4F8 + connected-dots), balra ikon +
brand + tagline, jobbra dontott mini telefon overdraw chippekkel.
Focal point kozepen, szelen 40px biztonsagi margo.
"""
import math
import pathlib
import random
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from generate import device, glass_chip, font_bold, tw, draw_3d_text  # noqa: E402

W, H = 1024, 500
BG = (240, 244, 248)
INK = (40, 40, 50)
SUB = (90, 110, 140)
OUT = HERE / "out" / "featureGraphic.png"
ICON = HERE / "icon.png"
SRC = HERE / "src" / "jegykep-full.jpg"


def pattern():
    rnd = random.Random(7)
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img, "RGBA")
    pts = [(rnd.randint(-40, W + 40), rnd.randint(-40, H + 40)) for _ in range(46)]
    for a in pts:
        for b in pts:
            dist = math.hypot(a[0] - b[0], a[1] - b[1])
            if 30 < dist < 170:
                alpha = int(255 * (1 - dist / 170) * 0.3)
                d.line([a, b], fill=(100, 150, 200, alpha), width=2)
    for p in pts:
        r = rnd.randint(4, 9)
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=(100, 150, 255, 100))
        d.ellipse([p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2], fill=(255, 255, 255, 255))
    return img


def main():
    canvas = pattern().convert("RGBA")
    d = ImageDraw.Draw(canvas)

    # bal: app ikon + brand + tagline (szelessege merve, hogy ne erjen a chipzonaba)
    icon = Image.open(ICON).convert("RGBA").resize((140, 140), Image.LANCZOS)
    mask = Image.new("L", (140, 140), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 140, 140], radius=32, fill=255)
    canvas.paste(icon, (40, 180), mask)

    fsize = 72
    f_brand = font_bold(fsize)
    bw, _ = tw(d, "Tömegközlekedés", f_brand)
    while bw > 410 and fsize > 40:
        fsize -= 4
        f_brand = font_bold(fsize)
        bw, _ = tw(d, "Tömegközlekedés", f_brand)
    draw_3d_text(d, (216, 155), "Tömegközlekedés", f_brand)
    f_tag = font_bold(38)
    d.text((218, 278), "Jegyeid egy helyen", font=f_tag, fill=SUB)

    # jobb: nagyobb dontott telefon jegykep-full tartalommal + 2 chip egymas alatt
    # elhelyezes mert meretekbol: jobb margoval, fuggolegesen kozepre
    raw = Image.open(SRC).convert("RGB")
    dev = device(raw, 170).rotate(-8, expand=True, resample=Image.BICUBIC)
    px, py = W - dev.width - 44, (H - dev.height) // 2
    assert px >= 0 and py >= 0, f"telefon nem fer ki: {dev.size}"
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sh.paste(dev, (px + 8, py + 10), dev)
    canvas = Image.alpha_composite(canvas, sh.filter(ImageFilter.GaussianBlur(6)))
    canvas.paste(dev, (px, py), dev)

    c1 = glass_chip("Offline jegyek", "wifi-slash", fsize=24).rotate(-6, expand=True,
                                                                     resample=Image.BICUBIC)
    c2 = glass_chip("Nagyítható jegykép", "magnifying-glass", fsize=24).rotate(
        5, expand=True, resample=Image.BICUBIC)
    x1, y1 = min(px - 70, W - 8 - c1.width), 175
    x2, y2 = min(px - 80, W - 8 - c2.width), 340
    assert x1 >= 628 and x2 >= 600 and y2 + 150 <= H, \
        f"pozicio hiba: {(x1, y1)} {(x2, y2)} {c1.size} {c2.size}"
    canvas.paste(c1, (x1, y1), c1)
    canvas.paste(c2, (x2, y2), c2)

    img = canvas.convert("RGB")
    assert img.size == (1024, 500)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"OK {OUT} {img.size}")


if __name__ == "__main__":
    main()
