# MÁV Jegy (com.domedav.mavjegy)

Nem hivatalos MÁV jegy/bérlet-nézegető Android app.
- **Jegyek fül**: érvényes/lejárt bontásban, cache-elve, részletes nézet AZTEC/CODE128 vonalkód toggle-lel, 60%-os vertikális görgetés az olvasóhoz pozicionáláshoz
- **Vásárlás fül**: WebView a jegy.mav.hu-ra (cookie perzisztencia → egyszeri bejelentkezés)
- Automata token-frissítés / újrabejelentkezés a háttérben
- Material 3 Expressive, ikonvezérelt UI

## Backend
`https://jegy-a.mav.hu/IK_API_PROD/api/` — GetUserToken / RefreshUserToken /
GetPreviousPurchases / GetPreviousPurchaseDetails. Auth: `UserTokenXml` header.

## Build (Termux / PC)

```bash
# Termux: csomagok
pkg install openjdk-21 gradle aapt2 android-sdk  # vagy saját SDK setup

cd MavJegyApp
gradle assembleRelease          # vagy ./gradlew ha wrapper jar telepítve
# kimenet: app/build/outputs/apk/release/app-release.apk
```

## Release kulcs
- `release.keystore` alias `mavjegy`
- jelszavak: `keystore.properties` (`MavJegy2026Release`)
- ⚠️ A keystore-t és a properties-t NE töltsd fel nyilvános repóba!

## Struktúra
```
app/src/main/java/com/domedav/mavjegy/
├── MavJegyApp.kt            # Application (api + tokenStore)
├── MainActivity.kt          # root nav, auto session
├── data/
│   ├── MavApi.kt            # HTTP + auto re-login
│   ├── TokenStore.kt        # EncryptedSharedPreferences
│   └── PurchaseCache.kt     # fájl cache
├── ui/
│   ├── theme/Theme.kt       # M3 Expressive téma
│   └── screens/             # Login, Tickets, TicketDetail, Buy(WebView)
└── util/
    ├── BarcodeGenerator.kt  # zxing AZTEC/CODE128
    └── TicketDecoder.kt     # serializedTicketData inflate
```
