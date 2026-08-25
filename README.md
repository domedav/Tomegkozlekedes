# Tömegközlekedés

Android alkalmazás magyar tömegközlekedési jegyek és bérletek (jegy/bérlet) kezelésére, Jetpack Compose-szal készítve.

- **Csomag / névtér:** `com.domedav.mavjegy`
- **Verzió:** `1.0.0` (versionCode 1)
- **minSdk:** 26 · **targetSdk:** 36 · **compileSdk:** 36
- **Nyelv és keretrendszer:** Kotlin, Jetpack Compose, Material 3

## Megvalósított funkciók

- Jegyek és bérletek listája
- Vásárlás beágyazott WebView-vel, perzisztens sessionnel (megmarad tab-váltáskor és app-újraindítás után is)
- Jegy tulajdonosi adatok szerkesztése (név, születési dátum, azonosító, fotó) és vonalkód megjelenítése
- Globális snackbar overlay (alsó pozíció, 5 mp után automatikus eltűnés, csak le/bal/jobb irányba húzható el)
- Navigációs pill oldalra rögzítése (bal/jobb oldal)

## Build

A `buildRelease.sh` szkript Termux alatt reproducible release buildet készít: letölti a Gradle-t és a minimális Android SDK-t, valamint proxy-t állít be.

Használat:

```bash
bash buildRelease.sh assembleRelease
```

(Debug buildhez használd az `assembleDebug` argumentumot.)

A kész release APK helye:

```
app/build/outputs/apk/release/app-release.apk
```

## Biztonság és közreműködés

A `release.keystore` és a `keystore.properties` (az aláíró kulcs és a jelszavak) **nincsenek** a repóban, a `.gitignore` kizárja őket. Saját aláíró kulcsot kell biztosítani a release buildhez. Ne commitolj titkokat.

## Licenc

A projektet „ahogy van” (as-is) biztosítjuk, konkrét licenc nem került megadásra.
