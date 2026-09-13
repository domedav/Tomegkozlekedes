# RSS Feed Részletes Elemzés

## Elérhető URL-ek

| URL | Státusz | Megjegyzés |
|-----|---------|------------|
| `https://www.mavcsoport.hu/mavinform` | HTML oldal | Drupal 7 oldal, AJAX paginációval |
| `https://www.mavcsoport.hu/feed` | 404 | Nem létezik |
| `https://www.mavcsoport.hu/rss` | 404 | Nem létezik |
| `https://www.mavcsoport.hu/rss.xml` | **200 OK** | **RSS 2.0 feed** |
| `https://www.mavcsoport.hu/atom.xml` | 404 | Nem létezik |
| `https://www.mavcsoport.hu/feed.xml` | 404 | Nem létezik |

## Feed Fejléc

```xml
<rss version="2.0" xml:base="https://www.mavcsoport.hu"
      xmlns:dc="http://purl.org/dc/elements/1.1/">
<channel>
  <title>MÁV-csoport</title>
  <link>https://www.mavcsoport.hu</link>
  <description></description>
  <language>hu</language>
```

## Item Adatstruktúra

### RSS-ben elérhető mezők

| Mező | XML elem | Típus | Példa |
|------|----------|-------|-------|
| **Cím** | `<title>` | String | "Fennakadás a Budapest-Hatvan-Miskolc vonalon" |
| **Link** | `<link>` | URL | `https://www.mavcsoport.hu/mavinform/fennakadas-...` |
| **Leírás** | `<description>` | HTML string | HTML-kódolt tartalom `<div>`, `<p>`, `<ul>` elemekkel |
| **Dátum** | `<pubDate>` | RFC 2822 | `Thu, 03 Sep 2026 18:37:00 +0000` |
| **Szerző** | `<dc:creator>` | String | `radics.levente.ciprian` |
| **Azonosító** | `<guid>` | String | `204392 at https://www.mavcsoport.hu` |

### HTML oldalon elérhető EXTRA mezők (RSS-ben NINCS)

| Mező | CSS Selector | Típus | Példa |
|------|-------------|-------|-------|
| Típus ikon | `.news-icons img` | Kép | `vonat_ikon.png`, `havaria.png` |
| Címkék | `.field-mavinform-tags` | String | "Rendkívüli változás" |
| Terület | `.field-territorial-scope` | String | "Bács-Kiskun" |
| Érvényesség kezdete | `.field-date-from` | Dátum | `2026.09.03. 22:00` |
| Érvényesség vége | `.news-date-to` | Dátum | `2026.09.04. 08:00` |
| Utolsó módosítás | `.news-last-changed` | Dátum | `2026.09.03. 22:01` |

## HTML Tartalom Megjelenítése

A `<description>` mező HTML-t tartalmaz. Főbb CSS osztályok:

| Osztály | Jelentés |
|---------|----------|
| `.field-body` | Fő tartalom |
| `.bluebox` | Előzmény/összefoglaló doboz (kék) |
| `.yellowbox` | Frissítés/aktuális állapot (sárga) |
| `.field-lead` | Bevezető szöveg |
| `.field-subtitle` | Alcím |
| `.field-outbound-link` | Külső hivatkozás |
| `.field-image` | Kép |

## Példa Item

```xml
<item>
  <title>Fennakadás a Budapest-Hatvan-Miskolc vonalon</title>
  <link>https://www.mavcsoport.hu/mavinform/fennakadas-budapest-hatvan-miskolc-vonalon-09-03</link>
  <description>
    &lt;div class="field-body"&gt;
      &lt;p&gt;20:55-től megindulhatott a közlekedés...&lt;/p&gt;
      &lt;hr&gt;
      &lt;p&gt;&lt;em&gt;Előzmény:&lt;/em&gt;&lt;/p&gt;
      &lt;p&gt;Kál-Kápolnánál hatósági intézkedés miatt...&lt;/p&gt;
    &lt;/div&gt;
  </description>
  <pubDate>Thu, 03 Sep 2026 18:37:00 +0000</pubDate>
  <dc:creator>radics.levente.ciprian</dc:creator>
  <guid isPermaLink="false">204392 at https://www.mavcsoport.hu</guid>
</item>
```

## Korlátok

1. **Nincs kép az RSS-ben** - csak a HTML oldalon jelennek meg
2. **Nincs érvényességi időintervallum** - csak publikálási dátum
3. **Nincs területi információ** - csak a HTML oldalon
4. **Nincs típus jelölés** - vonat/pótlóbusz/karbantartás csak a szövegből derül ki
5. **HTML tartalom** - szükséges a HTML parse-olás vagy egyszerű szöveggé alakítás

## HTML Scraper Lehetőség

A Drupal 7 oldal AJAX paginate-ot használ:
```javascript
"urlIsAjaxTrusted": {"/mavinform": true}
```

Szűrők ( Views module):
- `field_modalitas_value` - közlekedési mód (vonat/busz)
- `field_date_from_value` - dátumtartomány
- `field_territorial_scope_target_id` - terület
- `field_mavinform_tags_target_id` - címkék
- `title` - keresés

Ezeket az expozíciós szűrőket URL paraméterekkel lehet használni.
