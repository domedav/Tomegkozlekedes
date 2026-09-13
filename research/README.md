# MÁV Mavinform RSS Feed Integráció - Kutatás

## Összefoglalás

A `https://www.mavcsoport.hu/mavinform` oldal integrálása mint feed az alkalmazásba.
**Az alkalmazás kódja NEM módosítható!** Ez egy független kutatási dokumentáció.

## Adatforrás

| Tulajdonság | Érték |
|-------------|-------|
| Forrás URL | `https://www.mavcsoport.hu/rss.xml` |
| Formátum | RSS 2.0 (dc:creator namespace) |
| Nyelv | Magyar |
| CMS | Drupal 7 |
| Frissítés | Élő, valós idejű |

## A feed tartalma

Valós idejű vasúti közlekedési információk:
- Fennakadások, késések
- Pótlóbuszos közlekedés
- Rendkívüli változások
- Karbantartások
- �j vonalak, szolgáltatások

## Fájlok

| Fájl | Leírás |
|------|--------|
| `README.md` | Ez a fájl |
| `feed-analysis.md` | A feed részletes elemzése |
| `integration-plan.md` | Integrációs terv és kódtervek |
| `sample-rss.xml` | Valós minta adat a feedből |

## Státusz

- [x] Feed felderítése
- [x] Feed elemzése
- [x] HTML scraper készítése (működik!)
- [x] Kategória szűrés (vonat/busz/helyi_busz)
- [x] Cím + leírás kinyerése
- [ ] Integráció az alkalmazásba (NEM módosítjuk)
