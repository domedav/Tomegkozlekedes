#!/usr/bin/env python3
"""Letolto gombok a play_white szett stilusaban (feher pill + kek ikon).

Kimenet: out/github_button.png + out/googleplay_button.png
(1400x420, RGBA - README-be agyazhato). Tartalom vizszintesen centrezve.
Csak grafika, kattintast a README linkje adja ra.
"""
import pathlib
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from generate import font_bold, tw, phosphor  # noqa: E402

W, H = 1400, 420
ACCENT = (26, 115, 232)

BUTTONS = [
    ("Letöltés GitHubról", "github-logo", "github_button.png"),
    ("Letöltés Google Playről", "google-play-logo", "googleplay_button.png"),
]


def make_button(text, icon_name, out_name):
    btn = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(btn)
    d.rounded_rectangle([14, 22, W - 14, H - 14], radius=110, fill=(20, 30, 50, 80))
    btn = btn.filter(ImageFilter.GaussianBlur(10))
    d = ImageDraw.Draw(btn)
    glass = Image.new("RGBA", (W - 28, H - 28), (255, 255, 255, 215))
    m = Image.new("L", (W - 28, H - 28), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, W - 28, H - 28], radius=110, fill=255)
    btn.paste(glass, (14, 14), m)
    d = ImageDraw.Draw(btn)
    d.rounded_rectangle([14, 14, W - 14, H - 14], radius=110,
                        outline=(255, 255, 255, 255), width=5)
    d.rounded_rectangle([20, 20, W - 20, H - 20], radius=104,
                        outline=(26, 115, 232, 90), width=3)

    fb = font_bold(110)
    bw, bh = tw(d, text, fb)
    dot, gap = 190, 50
    # teljes tartalom (ikon + szoveg) vizszintes center
    while dot + gap + bw > W - 220 and fb.size > 60:
        fb = font_bold(fb.size - 6)
        bw, bh = tw(d, text, fb)
    total = dot + gap + bw
    cx = (W - total) // 2
    cy = H // 2
    d.ellipse([cx, cy - dot // 2, cx + dot, cy + dot // 2], fill=ACCENT)
    ic = phosphor(icon_name, 120)
    btn.paste(ic, (cx + dot // 2 - 60, cy - 60), ic)
    # fuggolegesen centrezve (lm anchor + kis optikai korrekcio az ekezetek miatt)
    d.text((cx + dot + gap, cy + 8), text, font=fb, fill=(25, 30, 45), anchor="lm")

    p = HERE / "out" / out_name
    btn.save(p)
    print(f"OK {p} {btn.size}")


def main():
    (HERE / "out").mkdir(parents=True, exist_ok=True)
    for text, icon, out in BUTTONS:
        make_button(text, icon, out)


if __name__ == "__main__":
    main()
