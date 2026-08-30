#!/usr/bin/env python3
"""
Keret generátor — ha saját eszközkeretet szeretnél (opcionális).
A compose.py anélkül is működik (rajzolt keret), ez csak extra.

Használat:
  python tools/mockup/generate_frame.py
  → tools/mockup/assets/frame.png (720×1280 belső muskterület)
"""
import pathlib
from PIL import Image, ImageDraw

OUT = pathlib.Path(__file__).resolve().parent / "assets" / "frame.png"


def main():
    w, h = 720, 1280
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    # Külső keret
    draw.rounded_rectangle([0, 0, w, h], radius=40, fill="white", outline="#e5e7eb", width=3)
    # Belső kivágás jelzés (szaggatott)
    draw.rounded_rectangle([16, 16, w - 16, h - 16], radius=28, outline="#d1d5db", width=1)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT, "PNG")
    print(f"frame -> {OUT} ({w}x{h})")


if __name__ == "__main__":
    main()
