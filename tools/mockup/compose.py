#!/usr/bin/env python3
"""
Modern kék Play mockup — 2026 prémium, nem fapados.

- ÓRIÁSI headline (90-96pt) + 36pt subtitle — messziről, thumbnailben is olvasható (Play 30%-os teszt)
- Nem teljes screen: csak a felső részlet (top 58%) látszik a telefonban — kártyák, fejléc
- Prémium Pixel-szerű keret: vékony sötét bezel, kamera-sziget, oldalgombok, highlight, dupla soft shadow, flat 3D extrudálás
- Kék Material You gradient (#082342 → #1A73E8) + glow + grain
"""
import argparse
import pathlib
import random
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / "GooglePlay" / "raw"
OUT_DIR = ROOT / "GooglePlay" / "phoneScreenshots"

W, H = 1080, 1920

GRAD_TOP = (8, 35, 66)
GRAD_MID = (14, 75, 138)
GRAD_BOT = (26, 115, 232)
GLOW = (130, 177, 255)
GLOW2 = (209, 228, 255)

# Részlet: a screenshotnak csak a felső hányada látszik — felül a lényeg
CROP_TOP_RATIO = 0.58  # 58% — pont a lista teteje / jegykártyák

BATCH_MAP = [
    ("01", "Jegyeim", "Natív lista"),
    ("02", "Nagyítható", "0,15–5× jegykép"),
    ("03", "Vásárlás", "jegy.mav.hu"),
    ("04", "Bejelentkezés", "Animált 3 lépés"),
    ("05", "Lebegő pill", "Material You"),
    ("06", "Offline is", "Minden jegy"),
]


def load_font(size: int, bold: bool = False):
    cands = [
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/data/data/com.termux/files/usr/share/fonts/TTF/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    bolds = [
        "/system/fonts/Roboto-Bold.ttf",
        "/system/fonts/NotoSans-Bold.ttf",
        "/data/data/com.termux/files/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ]
    for p in (bolds if bold else cands):
        if pathlib.Path(p).exists():
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def text_size(draw, text, font):
    bbox = draw.textbbox((0, 0), text, font=font)
    return bbox[2] - bbox[0], bbox[3] - bbox[1]


def lerp(a, b, t):
    return int(a + (b - a) * t)


def make_background():
    img = Image.new("RGB", (W, H), GRAD_TOP)
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / H
        if t < 0.55:
            tt = t / 0.55
            r = lerp(GRAD_TOP[0], GRAD_MID[0], tt); g = lerp(GRAD_TOP[1], GRAD_MID[1], tt); b = lerp(GRAD_TOP[2], GRAD_MID[2], tt)
        else:
            tt = (t - 0.55) / 0.45
            r = lerp(GRAD_MID[0], GRAD_BOT[0], tt); g = lerp(GRAD_MID[1], GRAD_BOT[1], tt); b = lerp(GRAD_MID[2], GRAD_BOT[2], tt)
        d.line([(0, y), (W, y)], fill=(r, g, b))
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for r, a in [(560, 20), (440, 14), (320, 9)]:
        gd.ellipse([W - 360 - r, 110 - r, W - 360 + r, 110 + r], fill=(GLOW[0], GLOW[1], GLOW[2], a))
    for r, a in [(500, 14), (380, 10), (260, 7)]:
        gd.ellipse([160 - r, H - 380 - r, 160 + r, H - 380 + r], fill=(GLOW2[0], GLOW2[1], GLOW2[2], a))
    for r, a in [(300, 8)]:
        gd.ellipse([W//2 - r, H//2 - r, W//2 + r, H//2 + r], fill=(GLOW[0], GLOW[1], GLOW[2], a))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=42))
    img = Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB")
    grain = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    random.seed(42)
    for _ in range(4200):
        x = random.randint(0, W - 1); y = random.randint(0, H - 1)
        v = random.randint(0, 255); a = random.randint(2, 7)
        grain.putpixel((x, y), (v, v, v, a))
    img = Image.alpha_composite(img.convert("RGBA"), grain).convert("RGB")
    return img


def draw_flat3d_sides(canvas, fx, fy, fw, fh, dx=16, dy=16):
    d = ImageDraw.Draw(canvas)
    # jobb oldal — sötétebb kék-fekete
    right = [(fx+fw, fy+56), (fx+fw+dx, fy+56-dy//2), (fx+fw+dx, fy+fh-56+dy//2), (fx+fw, fy+fh-56)]
    d.polygon(right, fill="#07152B")
    # alsó oldal
    bottom = [(fx+56, fy+fh), (fx+56+dx, fy+fh+dy), (fx+fw-56+dx, fy+fh+dy), (fx+fw-56, fy+fh)]
    d.polygon(bottom, fill="#0A1E3A")
    # sarok
    corner = [(fx+fw, fy+fh-56), (fx+fw+dx, fy+fh-56+dy//2), (fx+fw-56+dx, fy+fh+dy), (fx+fw-56, fy+fh)]
    d.polygon(corner, fill="#060F22")
    # oldalgombok a jobb oldalra (flat 3D-n is látszik)
    # hangerő + bekapcsoló — kis kiemelkedés
    for by in [fy+280, fy+380, fy+520]:
        d.rounded_rectangle([fx+fw+dx-2, by, fx+fw+dx+4, by+78], radius=2, fill="#0F2550")


def compose(screenshot_path: pathlib.Path, title: str, subtitle: str, output_path: pathlib.Path):
    # Csak a felső részlet kell — top 58% crop
    raw_full = Image.open(screenshot_path).convert("RGB")
    crop_h = int(raw_full.height * CROP_TOP_RATIO)
    raw = raw_full.crop((0, 0, raw_full.width, crop_h))

    canvas = make_background()
    draw = ImageDraw.Draw(canvas)

    # ÓRIÁSI tipográfia — 92-96pt headline, 36pt subtitle — thumbnail 30%-ban is olvasható
    f_title = load_font(92, bold=True)
    f_sub = load_font(36)
    tw, th = text_size(draw, title, f_title)
    if tw > W - 64:
        f_title = load_font(78, bold=True)
        tw, th = text_size(draw, title, f_title)
    if tw > W - 64:
        f_title = load_font(66, bold=True)
        tw, th = text_size(draw, title, f_title)

    title_y = 162
    # finom szöveg-árnyék a kontraszthoz
    draw.text(((W - tw)//2 + 2, title_y + 2), title, fill=(0,0,0,50), font=f_title)
    draw.text(((W - tw)//2, title_y), title, fill="white", font=f_title)

    line_w = 72
    line_y = title_y + th + 22
    draw.rounded_rectangle([(W - line_w)//2, line_y, (W + line_w)//2, line_y+5], radius=3, fill="#82B1FF")

    sw, sh = text_size(draw, subtitle, f_sub)
    if sw > W - 80:
        f_sub = load_font(30)
        sw, sh = text_size(draw, subtitle, f_sub)
    draw.text(((W - sw)//2, line_y + 24), subtitle, fill="#D1E4FF", font=f_sub)

    # sorszám diszkrét
    f_num = load_font(18)
    try:
        num = int(output_path.stem.split("_")[0])
        num_label = f"{num:02d} — 06"
    except:
        num_label = "06"
    nw, _ = text_size(draw, num_label, f_num)
    draw.text((W - nw - 36, 38), num_label, fill="white", font=f_num)
    draw.ellipse([W - nw - 52, 43, W - nw - 42, 53], fill="#82B1FF")

    # Eszköz — nagyobb, hangsúlyosabb, flat 3D
    fw, fh = 780, 1180  # alacsonyabb, mert csak felső részletet mutatunk — nem kell teljes 1480
    fx = (W - fw)//2
    fy = 520

    # Dupla soft shadow — mélyebb
    shadow_img = Image.new("RGBA", (W, H), (0,0,0,0))
    sdraw = ImageDraw.Draw(shadow_img)
    sdraw.rounded_rectangle([fx+12, fy+24, fx+fw+16, fy+fh+36], radius=54, fill=(0,0,0,100))
    sdraw.rounded_rectangle([fx+22, fy+34, fx+fw+6, fy+fh+18], radius=50, fill=(0,0,0,65))
    shadow_img = shadow_img.filter(ImageFilter.GaussianBlur(radius=36))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), shadow_img).convert("RGB")
    draw = ImageDraw.Draw(canvas)

    # Flat 3D oldalak
    draw_flat3d_sides(canvas, fx, fy, fw, fh, dx=16, dy=16)

    # Keret — prémium, vékony
    draw.rounded_rectangle([fx, fy, fx+fw, fy+fh], radius=54, fill="#0B0F14", outline="#1E3A5F", width=1)
    draw.rounded_rectangle([fx+10, fy+10, fx+fw-10, fy+fh-10], radius=46, fill="#0B0F14")
    # felső highlight
    draw.rounded_rectangle([fx+24, fy+14, fx+fw-24, fy+19], radius=2, fill="#1E3A5F")
    # oldalsó highlight (bal él fény)
    draw.rounded_rectangle([fx+12, fy+40, fx+16, fy+fh-40], radius=2, fill="#162A4A")

    # Screen — csak felső részlet, alul finom fade (hogy nem vágott élesen)
    inner_w, inner_h = fw - 28, fh - 28
    inner_x, inner_y = fx + 14, fy + 14

    raw_ratio = raw.width / raw.height
    inner_ratio = inner_w / inner_h
    if raw_ratio > inner_ratio:
        new_h = inner_h
        new_w = int(new_h * raw_ratio)
    else:
        new_w = inner_w
        new_h = int(new_w / raw_ratio)
    raw_resized = raw.resize((new_w, new_h), Image.LANCZOS)
    left = (new_w - inner_w)//2
    top = 0  # felülről, nem középről — a teteje a lényeg
    raw_cropped = raw_resized.crop((left, top, left+inner_w, top+inner_h))

    # lekerekített maszk
    mask = Image.new("L", (inner_w, inner_h), 0)
    mdraw = ImageDraw.Draw(mask)
    mdraw.rounded_rectangle([0,0,inner_w, inner_h], radius=38, fill=255)
    mdraw.rounded_rectangle([inner_w//2 - 44, 0, inner_w//2 + 44, 18], radius=9, fill=0)
    screen = Image.new("RGB", (inner_w, inner_h), "white")
    screen.paste(raw_cropped, mask=mask)
    # notch
    ndraw = ImageDraw.Draw(screen)
    ndraw.rounded_rectangle([inner_w//2 - 44, 0, inner_w//2 + 44, 18], radius=9, fill="#0B0F14")
    ndraw.ellipse([inner_w//2 - 6, 5, inner_w//2 + 6, 13], fill="#1E3A5F")
    ndraw.ellipse([inner_w//2 - 2, 7, inner_w//2 + 2, 11], fill="#0A2F5A")
    # alul finom fade — fehérből átlátszóba (hogy ne vágott legyen)
    fade = Image.new("RGBA", (inner_w, 120), (255,255,255,0))
    fdraw = ImageDraw.Draw(fade)
    for y in range(120):
        a = int(255 * (y / 120) * 0.92)
        fdraw.line([(0, y), (inner_w, y)], fill=(255,255,255, a))
    # keverjük a screen aljára
    screen_rgba = screen.convert("RGBA")
    # alul 120px sávot halványítjuk
    for y in range(120):
        alpha = int(255 * (1 - y/120 * 0.65))
        for x in range(inner_w):
            r,g,b,a = screen_rgba.getpixel((x, inner_h-120+y))
            screen_rgba.putpixel((x, inner_h-120+y), (r,g,b, alpha))
    # visszarakjuk fehér háttérre
    bg_white = Image.new("RGB", (inner_w, inner_h), "white")
    bg_white.paste(Image.new("RGB",(inner_w,inner_h),"white"), mask=mask)
    # egyszerű: paste screen maszkkal
    canvas.paste(screen, (inner_x, inner_y), mask)

    # alsó extra: kis kék pötty jelzi hogy részlet
    f_tip = load_font(16)
    tip = "Tömegközlekedés  •  MÁV Jegy port"
    tw2, _ = text_size(draw, tip, f_tip)
    draw.text(((W - tw2)//2, H - 46), tip, fill="#AECBFA", font=f_tip)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output_path, "PNG")
    print(f"  -> {output_path} ({W}x{H})")


def main():
    ap = argparse.ArgumentParser(description="Modern kék flat 3D — nagy szöveg, részlet")
    ap.add_argument("--screenshot", type=pathlib.Path)
    ap.add_argument("--title", type=str, default="Jegyeim")
    ap.add_argument("--subtitle", type=str, default="")
    ap.add_argument("--output", type=pathlib.Path)
    ap.add_argument("--batch", action="store_true")
    args = ap.parse_args()
    if args.batch:
        raws = sorted(RAW_DIR.glob("*.png"))
        if not raws:
            print(f"Nincs raw: {RAW_DIR}")
            return
        for i, raw in enumerate(raws):
            prefix, title, subtitle = BATCH_MAP[i % len(BATCH_MAP)]
            out = OUT_DIR / f"{prefix}_{raw.stem}.png"
            compose(raw, title, subtitle, out)
        return
    if not args.screenshot or not args.output:
        ap.error("--screenshot és --output kötelező (vagy --batch)")
    compose(args.screenshot, args.title, args.subtitle, args.output)

if __name__ == "__main__":
    main()
