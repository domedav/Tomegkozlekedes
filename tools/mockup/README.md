# Mockup generátor

Pillow-alapú, profi Play Áruház screenshot-generátor. Nincs ImageMagick, nincs külső bináris — Termuxon is fut.

## Telepítés

```bash
pip install -r tools/mockup/requirements.txt
```

## Használat

### 1) Nyers screenshotok

```bash
adb exec-out screencap -p > GooglePlay/raw/01_jegyeim.png
# vagy Demo módban: Demo / Demo bejelentkezés után screenshot
```

Raw méret: `1080×2400` is lehet, a `compose.py` vágja 1080×1920-ra.

### 2) Keretezett, headline-es Play-képek generálása

```bash
# egy kép
python tools/mockup/compose.py \
  --screenshot GooglePlay/raw/01_jegyeim.png \
  --title "JEGYEIM" \
  --subtitle "Natív lista, gyorsítótárazva" \
  --output GooglePlay/phoneScreenshots/01_jegyeim.png

# összes raw feldolgozása
python tools/mockup/compose.py --batch
```

### 3) Feature graphic

```bash
python tools/mockup/feature.py
# → GooglePlay/featureGraphic.png (1024×500, alpha nélkül)
```

### 4) Showcase (README-hez)

```bash
python tools/mockup/showcase.py
# → docs/showcase.png (összes phone screenshot egymás mellett)
```

## Dizájn

- Paletta: `Theme.kt:21` — `#006D3B` primary, `#00696B` teal, `#F7FBF4` surface
- Betű: Roboto / NotoSans / DejaVuSans (rendszeren ami van)
- Keret: lekerekítés 40px, shadow 28 alpha, skála 0.70 (screenshot-framer minta)
- Minden kimenet JPEG/PNG **alpha nélkül** (Play követelmény)

## Fájlok

```
tools/mockup/
├── requirements.txt
├── compose.py        # 1080×1920 phone screenshot → keretezett Play-kép
├── feature.py        # 1024×500 feature graphic
├── generate_frame.py # keret PNG generálása (ha kell)
└── showcase.py       # összes kép montázsa
```
