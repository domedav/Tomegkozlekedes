# Tömegközlekedés (MÁV Jegy port)

Ez az alkalmazás a hivatalos **MÁV (Magyar Államvasutak) „MÁV Jegy”** jegy- és bérletvásárló
mobilalkalmazás **nem hivatalos, harmadik féltől származó portolása / újraimplementálása**
(„portja”). Lehetővé teszi magyar utasok számára, hogy a hivatalos alkalmazás nélkül,
egy tisztán natív (Jetpack Compose) felületen keresztül kezeljék és vásárolják meg a
vonatjegyeiket és bérleteiket (jegy / bérlet).

Az alkalmazás **nem áll kapcsolatban a MÁV Zrt.-vel**, nem tőlük származik, és a MÁV semmilyen
formában nem támogatja, nem jóváhagyja vagy nem hitelesíti. A projekt célja kizárólag az, hogy
egy nyílt, a hivatalos klienstől független alternatív felületet biztosítson a már meglévő,
hivatalos MÁV webes vásárlási folyamat eléréséhez Androidon.

---

## Fontos jogi nyilatkozat (disclaimer)

> **Figyelmeztetés:** Ez a szoftver harmadik fél által készített, nem hivatalos eszköz.
> Semmilyen kapcsolatban nem áll a Magyar Államvasutak Zrt.-vel (MÁV), a MÁV Széchenyi
> Pihenő Kártya vagy bármely állami/állami tulajdonú társasággal. A „MÁV”, a „MÁV Jegy”
> és azok logói a jogtulajdonosok bejegyzett védjegyei; ezen névhasználat kizárólag a
> csatlakoztatott hivatalos szolgáltatás leírását szolgálja.

- Az alkalmazást **saját felelősségedre** használod. A fejlesztő nem vállal felelősséget
  semmilyen kárért, elveszett jegyért, téves vásárlásért, adatvesztésért vagy bármilyen
  egyéb következményért, amely az alkalmazás használatából ered.
- A jegyvásárlást az alkalmazás **nem saját háttérrendszeren** keresztül végzi, hanem az
  **hivatalos MÁV weboldalt (`https://jegy.mav.hu`) egy beágyazott Android WebView-ban
  jeleníti meg**, és a weboldal saját, hivatalos vásárlási folyamatát (bejelentkezés,
  fizetés, visszaigazolás) használja. Az app nem kerüli meg, nem módosítja és nem
  helyettesíti a MÁV hitelesítését vagy fizetési rendszerét.
- Az alkalmazás **tiszteletben tartja a MÁV használati feltételeit**: közvetlenül a
  hivatalos felületet tölti be, és nem végez automatizált (bot) vásárlást, nem manipulálja
  az árakat és nem kerüli meg a biztonsági ellenőrzéseket.
- Az app **nem használható semmilyen törvénytelen célra** (pl. jogosulatlan hozzáférés,
  csalás, hamisítás). A használattal elfogadod, hogy kizárólag a saját, valós utazásaidhoz
  vásárolsz jegyet/bérletet a saját MÁV fiókodban.
- A bejelentkezési adatok és a vásárlási munkamenet a készüléken, helyben kerül
  tárolásra (lásd a *Biztonsági megjegyzés* és a *WebViewSession* szakaszt). A fejlesztő
  nem fér hozzá ezekhez az adatokhoz.

---

## Funkciók

### Jegy- és bérletvásárlás beágyazott WebView-n keresztül
A „Vásárlás” fül egy teljes képernyős `WebView`-t tölt be a `https://jegy.mav.hu` címre
(`BUY_URL` – `BuyScreen.kt:49`). A WebView engedélyezi a JavaScriptet, a DOM-tárolót
(`domStorageEnabled`) és az adatbázist, hogy a hivatalos oldal teljes funkcionalitással
működjön. A felhasználó itt pontosan ugyanazt a folyamatot látja és használja, mint a
MÁV saját webes felületén: bejelentkezés, útvonal/termék kiválasztása, fizetés, majd a
jegy/bérlet megvásárlása. Az alkalmazás csak „befoglalja” ezt a folyamatot, nem
reimplementálja a MÁV szerveroldali logikáját.

### Bejelentkezési session megőrzése
Ez a port legfontosabb technikai újítása a hivatalos megközelítéshez képest:

- **Tab-váltáskor:** A `WebView` példány nincs újra létrehozva minden alkalommal, amikor a
  felhasználó a „Jegyek” és a „Vásárlás” fül között lépked. A `WebView` referenciáját az
  `AppRoot` (a `MainActivity` fő composable-ja) egy `mutableStateOf<WebView?>` változóban
  tartja életben (`MainActivity.kt:164`), és ugyanazt az objektumot adja át újra a
  `BuyScreen`-nek. Így a bejelentkezett session, a DOM és a lapállapot megmarad, amikor
  ideiglenesen elhagyod a vásárlási fület.
- **App-újraindításkor:** A `WebView` munkamenete (a `localStorage`, a `sessionStorage`
  és a HTTP-sütik/cookie-k) fájlba kerül mentésre (`web_session.json` a belső tárhelyen,
  lásd `WebViewSession.kt`), és az app következő indításakor visszaállításra kerül. Ezt
  azért vezették be, mert az eredeti viselkedés minden navigáláskor/kilépéskor elvesztette
  a bejelentkezést. A mentés megtörténik a `Lifecycle` `ON_PAUSE`/`ON_STOP` eseményeinél,
  a composable `onDispose`-ában, az oldal betöltődésekor (`onPageFinished`), valamint
  **20 másodpercenként rendszeresen** (`BuyScreen.kt:172`).

### Megvásárolt jegyek és bérletek listázása natív UI-ban
A „Jegyek” fül (`TicketsScreen.kt`) egy natív Jetpack Compose lista, amely lekéri és
megjeleníti a felhasználó érvényes vásárlásait a `MavApi.getPurchases()` híváson keresztül.
Minden tétel egy `PurchaseCard` kártyában jelenik meg:

- **Jegy** esetén a kártya `tertiaryContainer` színű, vonatikonnal (`Icons.Rounded.Train`),
  és az érvényesség kezdetét (`validFrom`) mutatja az időikon (`Icons.Rounded.Schedule`)
  mellett.
- **Bérlet** esetén a kártya `primaryContainer` színű, naptárikonnal
  (`Icons.Rounded.CalendarMonth`), és a hátralévő érvényességi napokat számolja ki
  (`X napig érvényes`, `ValiditySubtitle`).
- Lejárt vagy érvénytelen (`status != "Ervenyes"`) tételek szürkén, eltérő kerekítéssel
  jelennek meg, és a listából valamint a helyi gyorsítótárból is eltávolítódnak.
- A lista tetején egy frissítés gomb (forgó ikon) található; a hálózati hiba esetén egy
  globális snackbar jelenik meg.

### Navigációs pill (nav pill)
A két fő képernyő közötti váltást egy Material 3 Expressive stílusú, lebegő,
**jobb- vagy baloldali függőleges „pill”** vezérli (`MainActivity.kt:196`–`350`). A pill
a képernyő aljához rögzített, és vízszintesen áthúzható az egyik szélről a másikra
(elengedéskor a legközelebbi oldalhoz „tapad”, sosem marad középen). Függőleges húzással
a két ikon („Jegyek” = `ConfirmationNumber`, „Vásárlás” = `ShoppingCart`) között lehet
váltani; a kiválasztó karika folyamatos színátmenettel jelzi az átmenetet. A pill oldala
és az utoljára kiválasztott fül a `SettingsStore` segítségével megmarad újraindítás után.

### Globális snackbar értesítések
Az alkalmazás egyetlen, **globális snackbar-overlayt** használ (`SnackbarHost.kt`,
`DismissibleSnackbar.kt`), amely a composable-fa *mellett*, egy `Popup`-ban jelenik meg a
képernyő alján – így minden más felületi elem fölött úszik. Jellemzők:

- **5 másodperc** után automatikusan eltűnik (`DismissibleSnackbar.kt:53`).
- Hiba esetén `errorContainer`/`onErrorContainer` színnel, siker/infó esetén
  `inverseSurface`/`inverseOnSurface` színnel jelenik meg (a `isError` paraméter dönt).
- Eltüntethető jobbra-balra húzással **vagy** lefelé húzással; **felfelé nem** húzható
  (`offsetY` nem lehet negatív).
- A `LocalSnackbar` `CompositionLocal` teszi elérhetővé bármely képernyő számára
  (be van kötve a `MainActivity`-ben).

### Jegyrészletező képernyő (popup-szerű szerkesztési lehetőségekkel)
Egy jegy/bérlet kártyára koppintva megnyílik a `TicketDetailScreen`, ahol a jegy
részletes adatai, a vonalkód/QR kód megtekinthető, valamint szerkesztő/„módosító”
párbeszédablakok (pl. utas adatainak módosítása, `AlertDialog`) érhetők el
(`Icons.Rounded.Edit`, `TicketDetailScreen.kt`). A rendszer „vissza” gombje a jegy
listájába tér vissza az appból való kilépés helyett.

### Bejelentkezés és fiókkezelés
A `LoginScreen.kt` natív bejelentkező és regisztrációs folyamatot biztosít (lépésről
lépésre haladó animált képernyőkkel): email + jelszó bejelentkezés, új fiók létrehozása
(vezetéknév, keresztnév, email, születési dátum dátumválasztóval, jelszó), valamint
„elfelejtett jelszó” párbeszédablak. A bejelentkezés állapota a `TokenStore` alapján
marad meg az `AppRoot` újraépítésekor.

---

## Technológiai stack

| Elem | Érték |
|------|-------|
| Nyelv | **Kotlin 2.1.0** |
| UI | **100% Jetpack Compose** (nincs XML layout a képernyőkhöz) |
| Téma | Material 3 (Material Expressive stílusú komponensek, dinamikus szín `dynamicColor = true`) |
| `namespace` | `com.domedav.mavjegy` |
| `applicationId` | `com.domedav.mavjegy` |
| `minSdk` | **26** (Android 8.0) |
| `targetSdk` / `compileSdk` | **36** |
| `versionName` / `versionCode` | **1.0.0** / **1** |
| JVM cél | Java 17 (`sourceCompatibility`/`jvmTarget = 17`) |
| Fő függőségek | Compose BOM `2024.12.01`, `material3`, `material-icons-extended`, `lifecycle-viewmodel-compose`, `kotlinx-coroutines`, `kotlinx-serialization-json`, `okhttp3`, `security-crypto`, `zxing` (vonalkód) |

### Architektúra áttekintés
Az alkalmazás két világ találkozása:

1. **Natív Compose UI** – a teljes felhasználói felület (bejelentkezés, jegylista,
   navigációs pill, snackbar, részletező képernyő) tiszta Jetpack Compose-ban íródott.
2. **Beágyazott WebView (hibrid)** – a tényleges vásárlás a hivatalos `jegy.mav.hu`
   weboldal egy `WebView`-jában zajlik. A `WebView` példány **felemelve (hoisted)**
   az `AppRoot`-ba él, így a tab-váltás nem semmisíti meg, és a `WebViewSession`
   objektum gondoskodik a `localStorage`/`sessionStorage`/cookie perzisztenciáról
   fájlba, így az app újraindítása után is él a bejelentkezés.

Az adatréteg (`data/` csomag: `MavApi`, `TokenStore`, `PurchaseCache`, `OfflineStore`,
`TicketCache`, `SettingsStore`) Kotlin coroutine-okon és OkHttp-n keresztül kommunikál a
MÁV API-val, míg a gyorsítótár és a session helyileg, a belső tárhelyen tárolódik.

---

## Projekt struktúra / fő fájlok

```
MavJegyApp/
├── app/
│   ├── build.gradle.kts                 # namespace, SDK verziók, Kotlin 2.1.0, sign. config
│   └── src/main/
│       ├── AndroidManifest.xml          # INTERNET + ACCESS_NETWORK_STATE jogosultságok
│       ├── res/
│       │   ├── values/strings.xml       # app_name = "Tömegközlekedés"
│       │   └── drawable/ic_launcher_monochrome.xml  # monokróm launcher ikon (jegy motívum)
│       └── java/com/domedav/mavjegy/
│           ├── MainActivity.kt          # AppRoot: WebView hoist, nav pill, Snackbar Local
│           ├── MavJegyApp.kt             # Application osztály (tokenStore, api)
│           ├── data/                     # MavApi, TokenStore, cache, SettingsStore stb.
│           ├── ui/
│           │   ├── screens/
│           │   │   ├── BuyScreen.kt      # WebView (jegy.mav.hu), session mentés
│           │   │   ├── WebViewSession.kt  # localStorage/sessionStorage/cookie <-> web_session.json
│           │   │   ├── TicketsScreen.kt  # PurchaseCard, jegy/bérlet lista
│           │   │   ├── LoginScreen.kt    # bejelentkezés/regisztráció/popup
│           │   │   └── TicketDetailScreen.kt  # jegy részletek + szerkesztő dialog
│           │   ├── components/
│           │   │   ├── SnackbarHost.kt   # globális Popup overlay + LocalSnackbar
│           │   │   └── DismissibleSnackbar.kt  # 5 mp auto-elvtűnés, swipe kezelés
│           │   └── theme/Theme.kt        # MavJegyTheme (Material3 Expressive)
│           └── util/                     # vonalkód/barcode generátor és dekódoló
├── buildRelease.sh                       # reproducible release build (Termux)
├── README.md
└── LICENSE
```

---

## Build és futtatás

A release (telepíthető APK) előállításához a projekt gyökerében található segédszkript
használható:

```bash
bash buildRelease.sh assembleRelease
```

A szkript (`buildRelease.sh`) önmagában biztosítja a Gradle, az Android SDK platform
(`android-36`) és a szükséges build-eszközök letöltését/hivatkozását (Termux környezetre
optimalizálva), és a `build.gradle.kts` beállításai alapján a release build bekapcsolja a
kódzsugorítást (`minifyEnabled = true`) és az erőforrás-tömörítést (`shrinkResources = true`)
ProGuard használatával.

**Kimeneti APK helye:**

```
app/build/outputs/apk/release/app-release.apk
```

### Release aláírás követelményei
A release build **saját aláíró kulcsot** igényel. A `build.gradle.kts` egy `keystore.properties`
fájlból olvassa be az aláírási adatokat a projekt gyökerében:

```kotlin
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// signingConfigs.release: storeFile, storePassword, keyAlias, keyPassword
```

Tehát a release fordításhoz a következőkre van szükség:

- `keystore.properties` a projekt gyökérkönyvtárában, amely tartalmazza a
  `storeFile`, `storePassword`, `keyAlias` és `keyPassword` kulcsokat.
- A hivatkozott `release.keystore` (vagy más néven nevezett) kulcstároló fájl.

---

## Biztonsági megjegyzés

> ⚠️ **Az aláíró kulcs (`release.keystore`) és a jelszavakat tartalmazó `keystore.properties`
> fájl NINCS a repository-ban.** Ezeket a verziókövetés kihagyja (gitignored), és **helyben,
> a saját gépeden kell tartanod**. A saját release aláíráshoz neked kell biztosítanod őket.
> **Soha ne commitold** és ne töltsd fel nyilvános helyre ezeket a fájlokat, mert bárki,
> aki hozzájuk fér, a te kulcsoddal tudna aláírt alkalmazást készíteni a nevedben.

Ugyancsak fontos: a WebView session (bejelentkezési állapot) a `web_session.json`
fájlban, a belső tárhelyen tárolódik. Ez a fájl a készülékeden marad, de érzékeny
adataidat (session/cookie) tartalmazza – ne oszd meg, és használj képernyőzárat a
készülékeden.

---

## Licenc

Ez a szoftver **MIT licenc** alatt áll. A licenc szövege a `LICENSE` fájlban található.
Röviden: az alkalmazást szabadon használhatod, módosíthatod és terjesztheted, de
**SAJÁT FELELŐSSÉGRE**, minden garancia nélkül. A szerzőtől elvont felelősségvállalás
nem várható el.

Lásd a `LICENSE` fájlt (MIT licenc).
