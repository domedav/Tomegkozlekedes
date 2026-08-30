#!/usr/bin/env python3
"""Showcase — kék háttér, flat 3D thumbok."""
import pathlib
from PIL import Image, ImageDraw, ImageFont, ImageFilter
ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "GooglePlay" / "phoneScreenshots"
OUT = ROOT / "docs" / "showcase.png"
def bg(w,h):
    TOP=(8,35,66); MID=(14,75,138); BOT=(26,115,232)
    def lerp(a,b,t): return int(a+(b-a)*t)
    img=Image.new("RGB",(w,h),TOP)
    d=ImageDraw.Draw(img)
    for y in range(h):
        t=y/h
        if t<0.6: tt=t/0.6; r=lerp(TOP[0],MID[0],tt); g=lerp(TOP[1],MID[1],tt); b=lerp(TOP[2],MID[2],tt)
        else: tt=(t-0.6)/0.4; r=lerp(MID[0],BOT[0],tt); g=lerp(MID[1],BOT[1],tt); b=lerp(MID[2],BOT[2],tt)
        d.line([(0,y),(w,y)],fill=(r,g,b))
    glow=Image.new("RGBA",(w,h),(0,0,0,0)); gd=ImageDraw.Draw(glow)
    for r,a in [(520,16),(360,10)]: gd.ellipse([w-380-r,90-r,w-380+r,90+r],fill=(130,177,255,a))
    glow=glow.filter(ImageFilter.GaussianBlur(32))
    return Image.alpha_composite(img.convert("RGBA"),glow).convert("RGB")
def main():
    shots=sorted(SRC.glob("*.png"))
    if not shots: print(f"Nincs kép: {SRC}"); return
    thumb_w, thumb_h = 246, 436
    gap=20; n=len(shots)
    out_w = n*thumb_w + (n-1)*gap + 64
    out_h = thumb_h + 140
    canvas=bg(out_w, out_h); d=ImageDraw.Draw(canvas)
    try: f=ImageFont.truetype("/system/fonts/Roboto-Bold.ttf",28); f2=ImageFont.truetype("/system/fonts/Roboto-Regular.ttf",14)
    except: f=ImageFont.load_default(); f2=f
    title="Tömegközlekedés — MÁV Jegy port"
    tw=d.textbbox((0,0),title,font=f)[2]; d.text(((out_w-tw)//2,36),title,fill="white",font=f)
    sub="Trackermentes • Offline • Nagyítható jegykép • Material You"
    sw=d.textbbox((0,0),sub,font=f2)[2]; d.text(((out_w-sw)//2,72),sub,fill="#D1E4FF",font=f2)
    d.rounded_rectangle([(out_w-40)//2,98,(out_w+40)//2,101],radius=2,fill="#82B1FF")
    x=32; y=118
    for p in shots:
        img=Image.open(p).convert("RGB"); thumb=img.resize((thumb_w,thumb_h), Image.LANCZOS)
        sh=Image.new("RGBA",(thumb_w+18,thumb_h+18),(0,0,0,0)); sd=ImageDraw.Draw(sh)
        sd.rounded_rectangle([0,0,thumb_w+18,thumb_h+18],radius=18,fill=(0,0,0,60))
        sh=sh.filter(ImageFilter.GaussianBlur(10))
        canvas.paste(sh,(x-9,y-6),sh)
        mask=Image.new("L",(thumb_w,thumb_h),0); md=ImageDraw.Draw(mask); md.rounded_rectangle([0,0,thumb_w,thumb_h],radius=16,fill=255)
        holder=Image.new("RGB",(thumb_w,thumb_h),"white"); holder.paste(thumb,mask=mask)
        canvas.paste(holder,(x,y)); x+=thumb_w+gap
    OUT.parent.mkdir(parents=True,exist_ok=True); canvas.save(OUT,"PNG"); print(f"showcase -> {OUT} ({out_w}x{out_h}) {n} képpel")
if __name__=="__main__": main()
