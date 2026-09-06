# play_white – fehér hátteres Play screenshotok

PdfToolApp-recept: fehér `#F0F4F8` + kék connected-dots hálóminta.

## Használat (csak ebben a mappában)

```bash
python3 generate.py
# -> out/ (6 db, 1080x1920, alpha nélkül, Play-kész)
```

## Képek (v6 recept: cím fent / forgatott telefon / alul üres + overdraw chip)

| Fájl | Cím | Forrás | Chipek (kódban igazolt feature) |
|---|---|---|---|
| 01_one_jegyek-page | Jegyeid egy helyen | src/jegyek-page.jpg | Jegy megosztása, Jegy vásárlása |
| 02_one_jegykep-full | Jegykép közelről | src/jegykep-full.jpg | Nagyítható jegykép, Szerkeszthető adatok |
| 03_duo_login-register | Könnyű kezdés | src/login-page.jpg + src/register-page.jpg | Belépés MÁV-fiókkal, Regisztráció lépésekben |
| 04_one_news-page | Hírek neked | src/news-page.jpg | Keresés a hírekben, Kitűzhető hírek |
| 05_one_jegyek-page | Utazz szabadon | src/jegyek-page.jpg | Jegy vásárlása, Kattintásra részletek |
| 06_one_jegykep-full | Bérlet zsebben | src/jegykep-full.jpg | Utasprofil képpel, Érvényességi dátumok |

- Teljes képernyő vágás/zoom nélkül (forrás ~0,49 arány → pontos resize).
- Anonimizálás NINCS (user-döntés, saját felelősségre publikus).
- Eredeti fájlokhoz nem nyúl (csak `src/` másolatokat olvas).

## Ikonok

[Phosphor Icons](https://phosphoricons.com) (MIT © Phosphor Icons),
bold súly: `icons/*.svg` → fehér PNG: `icons/png/`.
