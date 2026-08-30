# GooglePlay – áruházi assetek

Ez a mappa tartalmazza a Google Play Áruházban megjelenő **felhasználók által látott** elemeket. A Play Console részleteit (Data Safety, célzások, aláírás) te kezeled — itt csak az alapok vannak előkészítve.

## Struktúra

```
GooglePlay/
├── icon.png                 # 512×512, 32-bit PNG (placeholder — cseréld a véglegesre)
├── featureGraphic.png       # 1024×500, JPEG/PNG alpha nélkül (placeholder)
├── phoneScreenshots/        # 1080×1920, min 2, max 8 (placeholderek)
│   ├── 01_jegyeim.png
│   ├── 02_jegykep_zoom.png
│   ├── 03_vasarlas.png
│   ├── 04_login.png
│   ├── 05_pill.png
│   └── 06_offline.png
├── raw/                     # nyers adb screencap-ek ide (nem kerül áruházba)
├── title.txt                # max 30 karakter
├── short_description.txt    # max 80 karakter
└── full_description.txt     # max 4000 karakter (Play hosszú leírás)
```

## Használat

1. Készíts nyers képernyőképeket: `adb exec-out screencap -p > GooglePlay/raw/01.png` (Demo/Demo módban is megy — `DemoData.kt:12`)
2. Futtasd a mockup-generátort: `pip install -r tools/mockup/requirements.txt && python tools/mockup/compose.py` — a keretezett, headline-es képek ide kerülnek: `GooglePlay/phoneScreenshots/`
3. Ha kész a végleges ikon/feature graphic, cseréld a placeholder PNG-ket (méretnek pontosan egyeznie kell)
4. Play Console → Store listing → feltöltés (drag & drop, sorrend számít)

## Méretek (Play 2026)

- Ikon: **512×512**, 32-bit PNG, ≤1024 KB, ne kerekíts (Google maszkol)
- Feature graphic: **1024×500**, JPEG/24-bit PNG **alpha nélkül**
- Phone screenshot: **1080×1920** (9:16), JPEG/24-bit PNG alpha nélkül, oldal 320–3840

## Színek

A mockupok az app palettáját használják: `Theme.kt:21` — primary `#006D3B` / `#6CDBA0`, teal `#00696B`, surface `#F7FBF4`. Háttér ne legyen stock gradiens.

## Tipp

A `GooglePlay/phoneScreenshots/` sorrendje a Playen is ez a sorrend. Az első 2–3 kép a legfontosabb (keresőben is látszik).
