#!/usr/bin/env python3
"""play_white v6 - user recept: cim fent / forgatott telefon / alul semmi,
chipek overdraw sticker-modon a kepre dobalva. Csak kodban igazolt feature-ok.

Kod-forras (subagent, app/src/main):
- Tabs: Jegyeim / Jegyvasarlas / Hirek. Jegyeim: 'Jegyeim', Frissites ikon,
  Kijelentkezes, ures: 'Jelenleg nincs ervenyes jegyed vagy berleted.' +
  'Jegy vasarlasa' gomb; kartya: nev/Berlet, ervenyesseg, ar Ft,
  'Jegy megosztasa', kattintas -> reszletezo.
- Reszletezo: 'Szerver jegykep' pinch-zoom/pan, also panel utas-foto/neev/
  szul./azonosito/utastipus-chip/datum/nap-badge, 'Tulajdonosi adatok
  szerkesztese' (Teljes nev, Szuletesi datum, Azonosito, Mentes/Megse).
- Login: 'Email', 'A MAV fiodok email cime', Tovabb, 'Fiok letrehozasa',
  'Elfelejtett jelszo?'; Jelszo: 'A MAV fiodok jelszava', Bejelentkezes.
- Register (LoginScreen steppek): 'Fiok letrehozasa', Vezeteknev/Keresztnev,
  email-visszaigazolas, Szuletesi datum picker, min 8 karakter jelszo,
  'Regisztracio', 'Sikeres regisztracio!'.
- Hirek: 'Hirek' + Frissites, 'Kereses a hirekben...', kategoriak:
  Vonat/Busz/Helyi busz/Egyeb, popup, long-press pin, 'Nincsenek talalatok.',
  'Ez minden - X talalat'.
- Jegyvasarlas tab: WebView jegy.mav.hu, 'Jelentkezz be az oldal tetejen!'.

1080x1920, alpha nelkul, csak src/ -> out/. Anonimizalas NINCS (user dontes).
"""
import math
import pathlib
import random
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE / "src"
OUT = HERE / "out"
FONT = HERE / "Roboto-Bold.ttf"
FONT_REG_CANDS = [
    "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf",
    "/home/linux/.local/share/fonts/DejaVuSans.ttf",
]

W, H = 1080, 1920
BG = (240, 244, 248)
INK = (40, 40, 50)
ACCENT = (26, 115, 232)
RATIO = 1470 / 720  # forras arany -> nincs vagas

ICONS = HERE / "icons" / "png"  # Phosphor Icons (MIT), bold suly, feherre szinezve
_ICON_CACHE = {}


def phosphor(name, px):
    """Feher Phosphor-ikon adott pixelmeretben (cache-elve)."""
    key = (name, px)
    if key not in _ICON_CACHE:
        im = Image.open(ICONS / f"{name}.png").convert("RGBA")
        _ICON_CACHE[key] = im.resize((px, px), Image.LANCZOS)
    return _ICON_CACHE[key]

# (src, cim, layout, szog)
PLANS = [
    ("jegyek-page.jpg", "Jegyeid egy helyen", "one", -8),
    ("jegykep-full.jpg", "Jegykép közelről", "one", 7),
    (("login-page.jpg", "register-page.jpg"), "Könnyű kezdés", "duo", 0),
    ("news-page.jpg", "Hírek neked", "one", 8),
    ("jegyek-page.jpg", "Utazz szabadon", "one", -6),
    ("jegykep-full.jpg", "Bérlet zsebben", "one", 6),
]

# chipek: (ikon, szoveg, oldal, fuggoleges pozicio) - oldal: L/R, poz: 0..1
CHIPS = {
    1: [("share-network", "Jegy megosztása", "L", 0.30),
        ("ticket", "Jegy vásárlása", "R", 0.58)],
    2: [("magnifying-glass", "Nagyítható jegykép", "R", 0.28),
        ("pencil-simple", "Szerkeszthető adatok", "L", 0.60)],
    3: [("key", "Belépés MÁV-fiókkal", "L", 0.30),
        ("user-plus", "Regisztráció lépésekben", "R", 0.58)],
    4: [("magnifying-glass", "Keresés a hírekben", "L", 0.30),
        ("push-pin", "Kitűzhető hírek", "R", 0.58)],
    5: [("ticket", "Jegy vásárlása", "R", 0.30),
        ("cursor-click", "Kattintásra részletek", "L", 0.60)],
    6: [("user", "Utasprofil képpel", "L", 0.28),
        ("calendar-blank", "Érvényességi dátumok", "R", 0.60)],
}


def font_bold(size):
    try:
        return ImageFont.truetype(str(FONT), size)
    except Exception:
        return ImageFont.load_default()


def pattern_bg(seed):
    rnd = random.Random(seed)
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img, "RGBA")
    pts = [(rnd.randint(-100, W + 100), rnd.randint(-100, H + 100))
           for _ in range(int(W * H / 26000))]
    for a in pts:
        for b in pts:
            dist = math.hypot(a[0] - b[0], a[1] - b[1])
            if 50 < dist < 300:
                alpha = int(255 * (1 - dist / 300) * 0.3)
                d.line([a, b], fill=(100, 150, 200, alpha), width=2)
    for p in pts:
        r = rnd.randint(5, 15)
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=(100, 150, 255, 100))
        d.ellipse([p[0] - 3, p[1] - 3, p[0] + 3, p[1] + 3], fill=(255, 255, 255, 255))
    return img


def tw(draw, text, f):
    bb = draw.textbbox((0, 0), text, font=f)
    return bb[2] - bb[0], bb[3] - bb[1]


def draw_3d_text(draw, pos, text, f, fill=INK):
    x, y = pos
    for dx, dy, c in [(5, 5, (255, 255, 255, 200)), (3, 3, (140, 170, 210)),
                      (1, 1, (90, 120, 160))]:
        draw.text((x + dx, y + dy), text, font=f, fill=c)
    draw.text(pos, text, font=f, fill=fill)


def header(canvas, title):
    d = ImageDraw.Draw(canvas)
    ft = font_bold(110)
    w, h = tw(d, title, ft)
    while w > W - 80 and ft.size > 56:
        ft = font_bold(ft.size - 8)
        w, h = tw(d, title, ft)
    draw_3d_text(d, ((W - w) // 2, 110), title, ft)
    return 110 + h


def load_src(name):
    return Image.open(SRC / name).convert("RGB")


def device(raw, w=620):
    h = int(w * RATIO)
    shot = raw.resize((w, h), Image.LANCZOS)
    fw, fh = w + 32, h + 32
    layer = Image.new("RGBA", (fw + 20, fh + 20), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle([10, 10, 10 + fw, 10 + fh], radius=52, fill=(235, 238, 244),
                        outline=(195, 200, 212), width=4)
    d.rounded_rectangle([18, 18, 10 + fw - 8, 10 + fh - 8], radius=44, fill=(15, 15, 18))
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, shot.width, shot.height], radius=34, fill=255)
    layer.paste(shot, (26, 26), mask)
    return layer


def drop_shadow(canvas, box, blur=26):
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(
        [box[0] + 20, box[1] + 24, box[2] + 20, box[3] + 24],
        radius=48, fill=(20, 30, 50, 90))
    return Image.alpha_composite(canvas, sh.filter(ImageFilter.GaussianBlur(blur)))


def glass_chip(text, kind="train", fsize=38):
    """Sticker-chip: uveglap + ikon + szoveg, sajat arnyekkal."""
    fb = font_bold(fsize)
    tmp = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    bw, bh = tw(tmp, text, fb)
    pad_x, dot, gap = 26, 60, 16
    cw, chh = pad_x + dot + gap + bw + pad_x, 104
    chip = Image.new("RGBA", (cw + 40, chh + 44), (0, 0, 0, 0))
    d = ImageDraw.Draw(chip)
    d.rounded_rectangle([20, 28, 20 + cw, 28 + chh], radius=52, fill=(20, 30, 50, 80))
    glass = Image.new("RGBA", (cw, chh), (255, 255, 255, 210))
    m = Image.new("L", (cw, chh), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, cw, chh], radius=52, fill=255)
    chip.paste(glass, (20, 20), m)
    d = ImageDraw.Draw(chip)
    d.rounded_rectangle([20, 20, 20 + cw, 20 + chh], radius=52,
                        outline=(255, 255, 255, 255), width=3)
    icx, icy = 20 + pad_x + dot // 2, 20 + chh // 2
    d.ellipse([icx - dot // 2, icy - dot // 2, icx + dot // 2, icy + dot // 2], fill=ACCENT)
    ic = phosphor(kind, 38)
    chip.paste(ic, (icx - 19, icy - 19), ic)
    d.text((20 + pad_x + dot + gap, 20 + (chh - bh) // 2 - 4), text, font=fb, fill=(25, 30, 45))
    return chip


def place(canvas, layer, x, y):
    canvas.paste(layer, (int(x), int(y)), layer)
    return canvas


def overdraw_chips(canvas, idx, chips_cfg, px, py, pw, ph):
    """Chipek sticker-modon a telefon testere dobalva, enyhen megforgatva."""
    for n, (kind, text, side, frac) in enumerate(chips_cfg):
        chip = glass_chip(text, kind).rotate(-6 if n % 2 == 0 else 6, expand=True,
                                             resample=Image.BICUBIC)
        y = py + ph * frac - chip.height / 2
        if side == "L":
            x = px - chip.width * 0.45
        else:
            x = px + pw - chip.width * 0.55
        x = max(8, min(W - chip.width - 8, x))
        place(canvas, chip, x, y)
    return canvas


def layout_one(idx, raw, angle):
    canvas = pattern_bg(100 + idx).convert("RGBA")
    header(canvas, PLANS[idx - 1][1])
    dev = device(raw, 620).rotate(angle, expand=True, resample=Image.BICUBIC)
    px, py = (W - dev.width) // 2, 300
    canvas = drop_shadow(canvas, (px + 10, py + 10, px + dev.width - 10, py + dev.height - 10))
    place(canvas, dev, px, py)
    overdraw_chips(canvas, idx, CHIPS[idx], px, py, dev.width, dev.height)
    return canvas.convert("RGB")


def layout_duo(idx, raw1, raw2):
    canvas = pattern_bg(100 + idx).convert("RGBA")
    header(canvas, PLANS[idx - 1][1])
    d1 = device(raw1, 500).rotate(-3, expand=True, resample=Image.BICUBIC)
    d2 = device(raw2, 500).rotate(3, expand=True, resample=Image.BICUBIC)
    overlap = 60
    x1 = (W - (d1.width + d2.width - overlap)) // 2
    x2 = x1 + d1.width - overlap
    py = 480
    canvas = drop_shadow(canvas, (x1 + 10, py + 10, x1 + d1.width - 10, py + d1.height - 10))
    canvas = drop_shadow(canvas, (x2 + 10, py + 10, x2 + d2.width - 10, py + d2.height - 10))
    place(canvas, d1, x1, py)
    place(canvas, d2, x2, py)
    overdraw_chips(canvas, idx, CHIPS[idx], x1, py, d1.width + d2.width - overlap, d1.height)
    return canvas.convert("RGB")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for i, (src, title, layout, angle) in enumerate(PLANS, start=1):
        if layout == "duo":
            img = layout_duo(i, load_src(src[0]), load_src(src[1]))
            stem = "login-register"
        else:
            img = layout_one(i, load_src(src), angle)
            stem = pathlib.Path(src).stem
        p = OUT / f"{i:02d}_{layout}_{stem}.png"
        img.save(p)
        print(f"OK {p} {img.size}")


if __name__ == "__main__":
    main()
