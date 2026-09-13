# HTML Scraper - Egyszerűsített

## Amit le kell kérni

| Mező | Honnan | Regex |
|------|--------|-------|
| **Cím** | Lista oldal | `<a href="([^"]*)"[^>]*>(.*?)</a>` |
| **Link** | Lista oldal | Ugyanaz, `href` csoport |
| **Kategória** | Lista oldal | `src="[^"]*/(vonat_ikon\|volan-busz_ikon\|helyi-busz_ikon)\.png"` |
| **Leírás** | Részletes oldal | `class="field-body">` → első yellowbox vagy `<p>` |

## Kategória meghatározás

| Icon fájl | Kategória |
|-----------|-----------|
| `vonat_ikon.png` | `vonat` |
| `volan-busz_ikon.png` | `busz` |
| `helyi-busz_ikon.png` | `helyi_busz` |
| Egyik sem | `ismeretlen` |

## Folyamat

1. `GET /mavinform` → 10 item / oldal
2. Item-ek: cím + link + kategória (lista oldalról)
3. Minden linkre `GET` → leírás (yellowbox/első bekezdés)

## Kotlin

```kotlin
fun detectCategory(block: String): String = when {
    "vonat_ikon" in block.lowercase() -> "vonat"
    "volan-busz_ikon" in block.lowercase() -> "busz"
    "helyi-busz_ikon" in block.lowercase() -> "helyi_busz"
    else -> "ismeretlen"
}

val titleRegex = Regex("""<a href="([^"]*)"[^>]*>(.*?)</a>""", DOT_MATCHES_ALL)
val bodyRegex = Regex("""class="field-body">(.*)""", DOT_MATCHES_ALL)
val yellowRegex = Regex("""class="yellowbox">(.*?)</div>""", DOT_MATCHES_ALL)
```
