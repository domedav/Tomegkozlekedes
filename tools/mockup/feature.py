#!/usr/bin/env python3
"""Kék feature graphic — 1024×500, flat 3D jegykártya, nagy szöveg."""
import argparse, pathlib
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "GooglePlay" / "featureGraphic.png"
W, H = 1024, 500
GRAD_TOP=(8,35,66); GRAD_MID=(14,75,138); GRAD_BOT=(26,115,232)
GLOW=(130,177,255)

def load_font(s,b=False):
    import pathlib as _p
    for p in (["/system/fonts/Roboto-Bold.ttf","/system/fonts/NotoSans-Bold.ttf"] if b else ["/system/fonts/Roboto-Regular.ttf"]):
        if _p.Path(p).exists(): return ImageFont.truetype(p,s)
    return ImageFont.load_default()
def ts(d,t,f): b=d.textbbox((0,0),t,font=f); return b[2]-b[0], b[3]-b[1]
def lerp(a,b,t): return int(a+(b-a)*t)
def bg():
    img=Image.new("RGB",(W,H),GRAD_TOP)
    d=ImageDraw.Draw(img)
    for y in range(H):
        t=y/H
        if t<0.6: tt=t/0.6; r=lerp(GRAD_TOP[0],GRAD_MID[0],tt); g=lerp(GRAD_TOP[1],GRAD_MID[1],tt); b=lerp(GRAD_TOP[2],GRAD_MID[2],tt)
        else: tt=(t-0.6)/0.4; r=lerp(GRAD_MID[0],GRAD_BOT[0],tt); g=lerp(GRAD_MID[1],GRAD_BOT[1],tt); b=lerp(GRAD_MID[2],GRAD_BOT[2],tt)
        d.line([(0,y),(W,y)],fill=(r,g,b))
    glow=Image.new("RGBA",(W,H),(0,0,0,0)); gd=ImageDraw.Draw(glow)
    for r,a in [(320,16),(220,10)]: gd.ellipse([W-220-r,90-r,W-220+r,90+r],fill=(GLOW[0],GLOW[1],GLOW[2],a))
    for r,a in [(280,12),(180,8)]: gd.ellipse([180-r,H-80-r,180+r,H-80+r],fill=(GLOW[0],GLOW[1],GLOW[2],a))
    glow=glow.filter(ImageFilter.GaussianBlur(28))
    return Image.alpha_composite(img.convert("RGBA"),glow).convert("RGB")

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--title", default="Tömegközlekedés — MÁV Jegy port")
    ap.add_argument("--subtitle", default="Trackermentes  •  Offline  •  Nagyítható jegykép  •  Material You")
    ap.add_argument("--output", type=pathlib.Path, default=OUT)
    args=ap.parse_args()
    img=bg(); d=ImageDraw.Draw(img)
    tf=load_font(46,b=True); sf=load_font(19); sm=load_font(12)
    tw,th=ts(d,args.title,tf); d.text(((W-tw)//2, 116), args.title, fill="white", font=tf)
    lw=56; ly=116+th+16; d.rounded_rectangle([(W-lw)//2,ly,(W+lw)//2,ly+4],radius=2,fill="#82B1FF")
    sw,_=ts(d,args.subtitle,sf); d.text(((W-sw)//2, ly+18), args.subtitle, fill="#D1E4FF", font=sf)
    # flat 3D jegykártya
    cx,cy=W//2, H//2+92
    # shadow + extrudálás
    sh=Image.new("RGBA",(W,H),(0,0,0,0)); sd=ImageDraw.Draw(sh)
    sd.rounded_rectangle([cx-116,cy-28,cx+116,cy+28],radius=18,fill=(0,0,0,80))
    # flat 3D oldal
    sd.polygon([(cx+116,cy-12),(cx+124,cy-6),(cx+124,cy+22),(cx+116,cy+16)], fill="#070E1E")
    sd.polygon([(cx-100,cy+28),(cx-92,cy+36),(cx+124,cy+36),(cx+116,cy+28)], fill="#0A1930")
    sh=sh.filter(ImageFilter.GaussianBlur(10))
    img=Image.alpha_composite(img.convert("RGBA"),sh).convert("RGB"); d=ImageDraw.Draw(img)
    d.rounded_rectangle([cx-112,cy-26,cx+112,cy+26],radius=16,fill="white",outline="#D1E4FF",width=1)
    d.rounded_rectangle([cx-96,cy-9,cx+96,cy+9],radius=8,fill="#0B57D0")
    for x in [cx-52,cx-18,cx+18,cx+52]: d.ellipse([x-5,cy-5,x+5,cy+5],fill="white")
    d.ellipse([cx-112-7,cy-7,cx-112+7,cy+7],fill="#EFF4FF")
    d.ellipse([cx+112-7,cy-7,cx+112+7,cy+7],fill="#EFF4FF")
    info="1024×500 • alpha nélkül"; iw,_=ts(d,info,sm); d.text(((W-iw)//2,H-24),info,fill="#AECBFA",font=sm)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    img.save(args.output,"PNG"); print(f"featureGraphic -> {args.output} ({W}x{H})")
if __name__=="__main__": main()
