# GooglePlay – áruházi assetek

Ez a mappa tartalmazza a Google Play Áruházban megjelenő **felhasználók által látott** elemeket. A Play Console részleteit (Data Safety, célzások, aláírás) te kezeled — itt csak az alapok vannak előkészítve.

## Struktúra

```
GooglePlay/
├── icon.png                 # 512×512, 32-bit PNG (placeholder — cseréld a véglegesre)
├── featureGraphic.png       # 1024×500, JPEG/PNG alpha nélkül (placeholder)
├── phoneScreenshots/        # 1080×1920, min 2, max 8 — feltöltéskor a tools/play_white/out/ képek másolata kerül ide
├── raw/                     # nyers adb screencap-ek ide (nem kerül áruházba)
├── title.txt                # max 30 karakter
├── short_description.txt    # max 80 karakter
└── full_description.txt     # max 4000 karakter (Play hosszú leírás)
```

## Használat

1. Készíts nyers képernyőképeket: `adb exec-out screencap -p > GooglePlay/raw/01.png` (Demo/Demo módban is megy — `DemoData.kt:12`), majd tedd be másolatként a `tools/play_white/src/` mappába
2. Futtasd a play_white-generátort: `python3 tools/play_white/generate.py` — a 6 kész, 1080×1920-as kép ide kerül: `tools/play_white/out/`
3. Feltöltés előtt másold át őket: `cp tools/play_white/out/*.png GooglePlay/phoneScreenshots/` (sorrend számít)
4. Ha kész a végleges ikon/feature graphic, cseréld a placeholder PNG-ket (méretnek pontosan egyeznie kell)
5. Play Console → Store listing → feltöltés (drag & drop, sorrend számít)

## Méretek (Play 2026)

- Ikon: **512×512**, 32-bit PNG, ≤1024 KB, ne kerekíts (Google maszkol)
- Feature graphic: **1024×500**, JPEG/24-bit PNG **alpha nélkül**
- Phone screenshot: **1080×1920** (9:16), JPEG/24-bit PNG alpha nélkül, oldal 320–3840

## Dizájn (play_white)

Fehér `#F0F4F8` + kék connected-dots háttér, recept: cím fent / forgatott telefon teljes képernyővel / alul üres + overdraw üveg-chip popupok Phosphor-ikonokkal (lásd `tools/play_white/README.md`).

## Tipp

A `GooglePlay/phoneScreenshots/` sorrendje a Playen is ez a sorrend. Az első 2–3 kép a legfontosabb (keresőben is látszik).
