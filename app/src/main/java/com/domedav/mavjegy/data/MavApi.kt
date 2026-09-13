package com.domedav.mavjegy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class Purchase(
    val id: String,
    val validFrom: String?,
    val validTo: String?,
    val startStation: String?,
    val endStation: String?,
    val status: String,
    val takenOver: Boolean,
    val amount: Double,
    val currency: String,
    val name: String? = null,
    /** Bérletigazolvány azonosító (NevesitesAzonosito) – pl. 1234567890 */
    val passHolderId: String? = null
)

/**
 * Bérlet-detektálás: nincs vonaladat (startStation null) VAGY a név bérletre utal
 * (pl. Országbérlet, Diákbérlet, Budapest–Szeged bérlet).
 */
fun Purchase.isPassTicket(): Boolean =
    startStation == null || name?.contains("bérlet", ignoreCase = true) == true

private const val VALID_PURCHASE_STATUS = "Ervenyes"

/** Név-enrich párhuzamossági limit (GetPreviousPurchaseDetails batch). */
private const val MAX_NAME_ENRICH_PARALLEL = 8

val Purchase.isValidTicket: Boolean
    get() = status.trim().let { it.equals(VALID_PURCHASE_STATUS, ignoreCase = true) || it.equals("Valid", ignoreCase = true) }

/** PAPI PreviousPurchaseStateApiEnum ("Valid"/"VALID"/"Expired"/...) -> magyar státusz. */
private fun mapPapiState(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "valid", "ervenyes" -> "Ervenyes"
    "expired", "lejart" -> "Lejart"
    "refunded", "partrefunded", "visszateritett" -> "Visszateritett"
    "senttorefund" -> "VisszateritesAlatt"
    "refundrejected" -> "VisszateritesElutasitva"
    null, "" -> ""
    else -> raw.trim()
}

/** PAPI businessError envelope (HTTP 200 mellett is!) üzenete, ha van. */
private fun businessErrorMessage(root: JsonObject): String? {
    val be = root["businessError"] as? JsonObject ?: return null
    return (be["details"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?.takeIf { it.isNotBlank() }
        ?: (be["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }
}

/** Állomásnév PAPI objektumból ({code, name}) vagy sima stringből. */
private fun stationName(el: kotlinx.serialization.json.JsonElement?): String? = when (el) {
    is kotlinx.serialization.json.JsonPrimitive ->
        el.content.takeIf { it.isNotBlank() && it != "null" }
    is JsonObject ->
        (el["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (el["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    else -> null
}
private fun JsonObject.strLenient(key: String): String? {
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { !it.content.isBlank() && it.content != "null" }?.content?.let { return it }
    val o = this[key] as? JsonObject ?: return null
    for (k in listOf("dateTime", "value", "iso", "text")) {
        (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
    }
    return null
}

data class TicketData(
    val serializedTicketData: String?,
    val jegySorszam: String?,
    val bizonylatTechnikaiAzonosito: String? = null
)

data class TicketDetails(
    val ticketData: TicketData?,
    val ajanlatNev: String? = null,
    val ervenyessegKezdete: String? = null,
    val ervenyessegVege: String? = null,
    /** Bérletigazolvány-szám a details passengers[].passCard.passNumber-ből (ha van). */
    val passNumber: String? = null
)

data class PassOwnerData(
    val fullName: String?,
    val birthDate: String?,
    val photoBase64: String?,
    val azonosito: String? = null
)

/** A szerver jegyképe (fallback nézethez) + a képből dekódolt hivatalos vonalkód-tartalom */
data class ServerJegyképResult(
    val imageBytes: ByteArray?,
    val barcodeText: String?,
    val fromCache: Boolean = false,
    val error: String? = null
)

class MavApi(private val tokenStore: TokenStore) {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private var lastVimError: String? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(PapiHeaders(tokenStore))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val baseUrl = "https://mvapi.mav.hu/IN/PROD/"
    private val vimBaseUrl = "https://vim.mav-start.hu/VIM/PR/20251120/MobileServiceS.svc/rest/"
    private val jsonBody = "application/json; charset=utf-8".toMediaType()

    /** PAPI URL-építés a verziózott service-pathekkel (lásd PapiVersions). */
    private fun papiUrl(service: String, op: String): String =
        baseUrl + PapiVersions.servicePath(service) + op

    /**
     * Service-verziók frissítése a szervertől (eredeti: app-startkor, DataUpdater;
     * J8/h.java + p8/n.java). Best-effort: hiba esetén a PapiVersions
     * fallbackek maradnak. forceUpgrade=true esetén false-t ad vissza.
     */
    suspend fun refreshPapiVersions(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).toString()
            val locale = java.util.Locale.getDefault()
            val lang = locale.language.ifBlank { "hu" }
            val region = if (locale.country.isNotBlank()) "${lang}_${locale.country}" else "${lang}_HU"
            val body = buildJsonObject {
                put("device", buildJsonObject {
                    put("calendar", "gregorian")
                    put("kind", "android")
                    put("localization", lang)
                    put("pushToken", null as String?)
                    put("regionFormat", region)
                    put("timeZone", java.util.TimeZone.getDefault().id)
                    put("type", "android ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    put("version", android.os.Build.VERSION.CODENAME ?: android.os.Build.VERSION.SDK_INT.toString())
                })
                put("process", buildJsonObject {
                    put("clientDate", now)
                    put("clientId", "Mav-Android-v2.5.18-prod")
                    put("clientInstanceID", tokenStore.getOrCreateDeviceInstance())
                    put("culture", lang)
                    put("httpUserAgent", USER_AGENT)
                    put("clientVersion", "2.5.18-prod")
                })
            }.toString().toRequestBody(jsonBody)
            val request = Request.Builder()
                .url(papiUrl(PapiVersions.MANAGEMENT, "VersionInfo/GetVersionInfo"))
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val root = json.parseToJsonElement(response.body!!.string()).jsonObject
                PapiVersions.updateFromPapiUrlJson(root)
                val force = (root["forceUpgrade"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                !force
            }
        }.getOrElse { false }
    }

    suspend fun login(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        // Demó belépés – teljesen offline dummy adatokkal
        if (DemoData.matches(email, password)) {
            tokenStore.setDemo(true)
            tokenStore.setCredentials(DemoData.DEMO_EMAIL, DemoData.DEMO_PASSWORD)
            tokenStore.setLoginTime(System.currentTimeMillis())
            return@withContext Result.success(Unit)
        }
        tokenStore.setDemo(false)
        // Legfrissebb service-verziók a szervertől (best-effort, fallback marad hiba esetén).
        runCatching { refreshPapiVersions() }
        runCatching {
            val body = buildJsonObject {
                put("username", email)
                put("password", password)
            }.toString().toRequestBody(jsonBody)

            val response = client.newCall(
                Request.Builder()
                    .url(papiUrl(PapiVersions.USER_PROFILE, "Authentication/Login"))
                    .post(body)
                    .build()
            ).execute()

            response.use {
                if (!it.isSuccessful) error("Login failed: HTTP ${it.code}")
                val raw = it.body!!.string()
                val root = json.parseToJsonElement(raw).jsonObject
                // A szerver küldhet service-URL térképet -> verziók frissítése (in-memory).
                runCatching { PapiVersions.updateFromPapiUrlJson(root) }
                // Új login-modell (LoginResponseModel): refreshToken + userGuid, nincs SAML.
                val authToken = findFirstString(
                    root,
                    listOf("refreshToken", "authToken", "token", "accessToken", "jwt")
                )
                val guid = findFirstString(
                    root,
                    listOf("userGuid", "guid", "userId")
                )
                if (authToken != null) tokenStore.setAuthToken(authToken)
                if (guid != null) tokenStore.setUserGuid(guid)
                if (authToken.isNullOrBlank()) {
                    Log.w("MavApi", "Login 200 but no authToken; keys=${collectKeys(root)}")
                    val msg = findFirstString(root, listOf("message"))
                    error(msg ?: "Login: missing authToken")
                }
                // VIM (GetJegykep / BerletTok*) hívásokhoz: felhasználóazonosító, ha adja a szerver
                val userId = findFirstString(
                    root,
                    listOf("felhasznaloAzonosito", "FelhasznaloAzonosito", "regisztraciosAzonosito")
                )?.takeIf { it.isNotBlank() }
                if (userId != null) tokenStore.setUserId(userId)
                tokenStore.setCredentials(email, password)
                tokenStore.setLoginTime(System.currentTimeMillis())
            }
        }
    }

    suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        if (tokenStore.isDemo()) return@withContext true
        if (!tokenStore.hasAuthToken() && !tokenStore.hasToken()) {
            return@withContext reloginIfPossible()
        }
        // 1 login 1 napig érvényes — lejárt loginnal nem refreshelünk, hanem újra belépünk
        if (tokenStore.isLoginExpired() && tokenStore.hasCredentials()) {
            return@withContext reloginIfPossible()
        }
        val refreshed = runCatching {
            val refreshValue = tokenStore.getAuthToken() ?: tokenStore.getToken()!!
            val body = buildJsonObject { put("refreshToken", refreshValue) }
                .toString().toRequestBody(jsonBody)
            val request = Request.Builder()
                .url(papiUrl(PapiVersions.USER_PROFILE, "Authentication/RefreshToken"))
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val root = json.parseToJsonElement(response.body!!.string()).jsonObject
                val token = findFirstString(
                    root,
                    listOf("refreshToken", "authToken", "token", "accessToken", "jwt", "userTokenXml")
                )
                if (!token.isNullOrBlank()) {
                    tokenStore.setAuthToken(token)
                    true
                } else {
                    false
                }
            }
        }.getOrElse { false }
        // Refresh halott (pl. hetek óta lejárt token), de van mentett jelszó -> újra-login
        if (!refreshed && tokenStore.hasCredentials()) {
            return@withContext reloginIfPossible()
        }
        refreshed
    }

    suspend fun getPurchases(): List<Purchase> = withContext(Dispatchers.IO) {
        if (tokenStore.isDemo()) return@withContext DemoData.purchases()
        // PAPI GetPreviousPurchasesRequestModel: minden mező opcionális -> üres body.
        // Auth: PapiHeaders interceptor (Bearer), nincs per-call header.
        val body = "{}".toRequestBody(jsonBody)

        fun doCall() = client.newCall(
            Request.Builder()
                .url(papiUrl(PapiVersions.ORDER, "PreviousPurchase/GetPreviousPurchases"))
                .post(body)
                .build()
        ).execute()

        if (!tokenStore.hasAuthToken() && !tokenStore.hasToken()) error("No stored token")
        var response = doCall()
        if (response.code == 401 || response.code == 403) {
            response.close()
            if (!ensureSession()) error("Session expired and re-login failed")
            response = doCall()
        }

        val base: List<Purchase> = response.use {
            if (!it.isSuccessful) error("GetPreviousPurchases failed: HTTP ${it.code}")
            val root = json.parseToJsonElement(it.body!!.string()).jsonObject
            // PAPI üzleti hiba HTTP 200 mellett is jöhet (pl. lejárt session).
            businessErrorMessage(root)?.let { msg -> error("GetPreviousPurchases: $msg") }
            // PAPI: previousPurchases[] = PreviousPurchaseBaseInfoApiModel
            // (purchaseId/state/serviceNames/schedulerDatas; nincs id/status/startStation).
            val arr = root["previousPurchases"]?.jsonArray
            (arr?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val sched = o["schedulerDatas"] as? JsonObject
                val names = o["serviceNames"] as? kotlinx.serialization.json.JsonArray
                Purchase(
                    id = o.strLenient("purchaseId") ?: o.str("id") ?: return@mapNotNull null,
                    validFrom = o.strLenient("validFrom"),
                    validTo = o.strLenient("validTo"),
                    startStation = stationName(sched?.get("startStation")) ?: stationName(o["startStation"]),
                    endStation = stationName(sched?.get("arrivalStation")) ?: stationName(o["endStation"]),
                    status = mapPapiState(o.strLenient("state") ?: o.str("status")),
                    takenOver = o.takeOverFlag(),
                    amount = o.priceAmount(),
                    currency = o.currencyKey(),
                    name = (names?.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?.takeIf { s -> s.isNotBlank() }
                        ?: listOf("name", "Name", "Nev", "nev", "ajanlatNev", "title")
                            .firstNotNullOfOrNull { k -> o.str(k) }
                        ?: findStringKey(o, "ajanlatNev")
                        ?: findStringKey(o, "name")
                        ?: findStringKey(o, "nev")
                        ?: findStringKey(o, "title")
                        ?: findStringKey(o, "megnevezes"),
                    passHolderId = o.str("passHolderId")
                )
            } ?: emptyList()).distinctBy { it.id }
        }
        if (base.isEmpty()) return@withContext base
        // Lista-elemben nincs serviceNames -> név null; passHolderId kulcs
        // a PAPI-ban nincs is (forrása: details passengers[].passCard.passNumber,
        // csak bérleteknél/nevesített jegyeknél van). Hívásszám-kímélés: név
        // nélkül mindent, passNumberért csak a bérlet-gyanúsakat enrich-elünk.
        // 401/403-t a lista-hívás fenti doCall+ensureSession+retry már rendezte,
        // a per-elem getTicketDetails saját retry-ja marad biztonsági hálónak.
        val missing = base.filter { it.name.isNullOrBlank() ||
            (it.passHolderId.isNullOrBlank() && it.isPassTicket()) }
        if (missing.isEmpty()) return@withContext base
        val names = mutableMapOf<String, String>()
        val passNumbers = mutableMapOf<String, String>()
        // supervisorScope: egy elem hibája ne kényszerítse a többi async-et.
        supervisorScope {
            for (chunk in missing.chunked(MAX_NAME_ENRICH_PARALLEL)) {
                val results = chunk.map { p ->
                    async {
                        // Könnyű details-hívás (kép nélkül) névért + passNumberért
                        // (passCardDetails=true a payloadban, képtől független).
                        p.id to runCatching { getTicketDetails(p.id, withImages = false) }.getOrNull()
                    }
                }.awaitAll()
                for ((id, d) in results) {
                    d?.ajanlatNev?.takeIf { it.isNotBlank() }?.let { names[id] = it }
                    d?.passNumber?.takeIf { it.isNotBlank() }?.let { passNumbers[id] = it }
                }
            }
        }
        if (names.isEmpty() && passNumbers.isEmpty()) return@withContext base
        base.map { p ->
            val n = names[p.id]
            val pn = passNumbers[p.id]
            var out = p
            if (n != null) {
                TicketCache.putNameMem(p.id, n)
                out = out.copy(name = n)
            }
            if (pn != null && out.passHolderId.isNullOrBlank()) out = out.copy(passHolderId = pn)
            out
        }
    }

    suspend fun getTicketDetails(id: String, withImages: Boolean = true): TicketDetails = withContext(Dispatchers.IO) {
        if (tokenStore.isDemo()) return@withContext DemoData.ticketDetails(id)
        // PAPI GetPreviousPurchaseDetailsRequestModel: purchaseId + certificateImages /
        // passCardDetails / includeRefundedItems flag-ek (élő próbával megerősítve).
        // withImages=false: könnyű hívás név/dátumokért (lista-enrich), kép nélkül.
        val payload = buildJsonObject {
            put("purchaseId", id)
            put("certificateImages", withImages)
            put("passCardDetails", true)
            put("includeRefundedItems", true)
        }.toString().toRequestBody(jsonBody)

        fun doCall() = client.newCall(
            Request.Builder()
                .url(papiUrl(PapiVersions.ORDER, "PreviousPurchase/GetPreviousPurchaseDetails"))
                .post(payload)
                .build()
        ).execute()

        if (!tokenStore.hasAuthToken() && !tokenStore.hasToken()) error("No stored token")
        var response = doCall()
        if (response.code == 401 || response.code == 403) {
            response.close()
            if (!ensureSession()) error("Session expired and re-login failed")
            response = doCall()
        }

        response.use {
            if (!it.isSuccessful) error("GetPreviousPurchaseDetails failed: HTTP ${it.code}")
            val root = json.parseToJsonElement(it.body!!.string()).jsonObject
            businessErrorMessage(root)?.let { msg -> error("GetPreviousPurchaseDetails: $msg") }
            // PAPI kulcs: purchaseDetails (PreviousPurchaseDetailsApiModel); legacy: previousPurchaseDetails.
            // ticketBase PAPI-ban LISTA (első elem: ticketBaseIdentites/ticketBaseDetails/travelDetails).
            val details = (root["purchaseDetails"] ?: root["previousPurchaseDetails"]) as? JsonObject
            val ticketBase = (details?.get("ticketBase") as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull() as? JsonObject
            val tbDetails = ticketBase?.get("ticketBaseDetails") as? JsonObject
            val names = details?.get("serviceNames") as? kotlinx.serialization.json.JsonArray
            val tickets = details?.get("ticketDatas")?.jsonArray
            val first = tickets?.firstOrNull() as? JsonObject
            // ajánlatnév / érvényesség a szolgáltatás-ajánlatokból (legacy) vagy ticketBase-ből (PAPI)
            val offer = first?.get("szolgaltatasAjanlatok")?.let { el ->
                (el as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
            }
            val papiName = (names?.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() }
                ?: tbDetails?.str("name")
            // Cert-kép memória-stash (getServerJegyKep dupla letöltés ellen):
            // withImages=true esetén ugyanaz a válasz hozza a képet is.
            if (withImages && details != null) {
                runCatching { extractFirstCertificateImageBytes(details) }
                    .getOrNull()?.let { synchronized(certImageMem) { certImageMem[id] = it } }
            }
            TicketDetails(
                ticketData = first?.let { o ->
                    TicketData(
                        serializedTicketData = o.str("serializedTicketData"),
                        jegySorszam = o.str("jegySorszam")
                            ?: details?.strLenient("purchaseId")
                            ?: details?.strLenient("orderId"),
                        bizonylatTechnikaiAzonosito = o.str("bizonylatTechnikaiAzonosito")
                            ?: details?.strLenient("orderId")
                    )
                },
                ajanlatNev = offer?.str("ajanlatNev") ?: papiName,
                ervenyessegKezdete = offer?.str("ervenyessegKezdete")
                    ?: details?.strLenient("validFrom")
                    ?: tbDetails?.strLenient("ticketValidFrom"),
                ervenyessegVege = offer?.str("ervenyessegVege")
                    ?: details?.strLenient("validTo")
                    ?: tbDetails?.strLenient("ticketValidTo"),
                passNumber = details?.let { runCatching { extractPassNumber(it) }.getOrNull() }
            )
        }
    }

    /**
     * Bérletes utas adatai (tulajdonos) – PAPI Profile/GetPassCard.
     * GetUserDetails nem jó: csak név/email/telefon van benne, fotó/születési dátum nincs.
     * A GetPassCard -> passCards[] (HPTUserPassCardApiModel: passengerFullName, bornDate,
     * passNumber, picture) hordozza a HPT-adatokat. Nincs legacy GetUserHPTDatas PAPI-ban.
     */
    suspend fun getPassOwnerData(context: android.content.Context): PassOwnerData? = withContext(Dispatchers.IO) {
        if (tokenStore.isDemo()) return@withContext DemoData.passOwner()
        PapiVersions.load(context)
        // PAPI GetPassCardRequestModel: {enableExpiredTickets}; auth: PapiHeaders interceptor.
        val body = buildJsonObject { put("enableExpiredTickets", true) }
            .toString().toRequestBody(jsonBody)

        fun doCall() = client.newCall(
            Request.Builder()
                .url(papiUrl(PapiVersions.USER_PROFILE, "Profile/GetPassCard"))
                .post(body)
                .build()
        ).execute()

        if (!tokenStore.hasAuthToken() && !tokenStore.hasToken()) return@withContext null
        var response = doCall()
        if (response.code == 401 || response.code == 403) {
            response.close()
            if (!ensureSession()) return@withContext null
            response = doCall()
        }

        val fresh: PassOwnerData? = try {
            response.use { r ->
                if (!r.isSuccessful) return@use null
                val root = json.parseToJsonElement(r.body!!.string())
                findPassOwner(root)
            }
        } catch (_: Exception) {
            null
        }
        // Agresszív offline cache: frissít, ha tud; hálózatként elérhetetlen esetén cache-ből szolgál
        if (fresh != null) {
            OfflineStore.savePassOwner(context, "global", fresh.fullName, fresh.birthDate, fresh.photoBase64, fresh.azonosito)
            fresh
        } else {
            OfflineStore.loadPassOwner(context, "global")?.let {
                PassOwnerData(it.fullName, it.birthDate, it.photoBase64, it.azonosito)
            }
        }
    }

    private fun findPassOwner(el: kotlinx.serialization.json.JsonElement): PassOwnerData? {
        when (el) {
            is JsonObject -> {
                var fullName: String? = null
                var birthDate: String? = null
                var photo: String? = null
                var azonosito: String? = null
                el.forEach { (key, v) ->
                    val prim = v as? kotlinx.serialization.json.JsonPrimitive
                    val s = prim?.content?.takeIf { it.isNotBlank() && it != "null" }
                    when {
                        s != null && key.equals("teljesNev", true) -> fullName = s
                        s != null && fullName == null && (key.equals("Nev", true) || key.equals("FullName", true) || key.equals("passengerFullName", true)) -> fullName = s
                        s != null && key.equals("szuletesiDatum", true) -> birthDate = s
                        s != null && birthDate == null && key.equals("bornDate", true) -> birthDate = s
                        // PAPI HPTUserPassCard.picture: PictureApiModel/Image-objektum
                        // {fileName, fileNameExtension, image(b64), mimeType} — a b64
                        // a beágyazott "image" kulcs alatt van, nem "picture" alatt.
                        // Hossz-őrrel: csak valódi kép-blobot fogadunk el.
                        s != null && photo == null && (key.equals("Fenykep", true) || key.equals("berletKepString", true) || key.equals("picture", true) || key.equals("content", true)) -> photo = s
                        s != null && photo == null && key.equals("image", true) && s.length > 1000 -> photo = s
                        s != null && azonosito == null && (
                            key.equals("NevesitesAzonosito", true) ||
                                key.equals("berletIgazolvanyazonosito", true) ||
                                key.equals("eszigIgazolvanyszam", true) ||
                                key.equals("passNumber", true)
                            ) -> azonosito = s
                    }
                }
                if (fullName != null || birthDate != null || photo != null || azonosito != null) {
                    return PassOwnerData(fullName, birthDate, photo, azonosito)
                }
                el.values.firstNotNullOfOrNull { findPassOwner(it) }?.let { return it }
                return null
            }
            is kotlinx.serialization.json.JsonArray ->
                el.firstNotNullOfOrNull { findPassOwner(it) }?.let { return it }
            else -> {}
        }
        return null
    }


    /** Uzenetek[] (H=hiba, M=üzenet, R=rendszerhiba) szövegek összefűzése */
    private fun extractServerMessages(el: kotlinx.serialization.json.JsonElement): String? {
        val texts = mutableListOf<String>()
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> {
                    val szoveg = (e["Szoveg"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (!szoveg.isNullOrBlank()) texts += szoveg
                    e.values.forEach { walk(it) }
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { walk(it) }
                else -> {}
            }
        }
        walk(el)
        return texts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** Rekurzív kulcskeresés a JSON fában (robusztus válaszparse). */
    private fun findStringKey(el: kotlinx.serialization.json.JsonElement, key: String): String? {
        when (el) {
            is JsonObject -> {
                (el[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { p ->
                    if (p.content.isNotBlank() && p.content != "null") return p.content
                }
                el.values.firstNotNullOfOrNull { findStringKey(it, key) }?.let { return it }
            }
            is kotlinx.serialization.json.JsonArray ->
                el.firstNotNullOfOrNull { findStringKey(it, key) }?.let { return it }
            else -> {}
        }
        return null
    }

    /**
     * Utastípus / kedvezmény kód -> emberi név térkép PAPI BaseData-ból.
     * Auth: PapiHeaders interceptor (Bearer), nincs per-call header.
     *
     * Decompile szerint (BaseDataApi.java): az op-route-ok
     * "BaseData/V_SEGMENT/BaseDataApi/<Op>" alakúak, ahol a V_SEGMENT
     * helyére futásidőben a verzió kerül — ez pontosan a mi
     * PapiVersions.servicePath(BASE_DATA) + "BaseDataApi/<Op>" sémánk
     * (pl. "BaseData/4_13_0_12/" + "BaseDataApi/GetCustomerTypes").
     * Elsődleges: GetCustomerTypes -> DetailedCustomerTypeApiModel{key, name}.
     * A parse generikus (key/id/Kod -> name/Nev/description), így bármelyik
     * BaseData lista-válaszból kinyeri a térképet. Hiba esetén üres térkép
     * + offline cache.
     */
    suspend fun getTypeNames(context: android.content.Context): Map<String, String> = withContext(Dispatchers.IO) {
        if (tokenStore.isDemo()) return@withContext DemoData.typeNames()
        PapiVersions.load(context)
        if (!tokenStore.hasToken() && !tokenStore.hasAuthToken()) return@withContext OfflineStore.loadTypeNames(context) ?: emptyMap()
        val body = "{}".toRequestBody(jsonBody)
        // Decompile-megerősített PAPI BaseData opok (sorrendben próbálva).
        val ops = listOf("BaseDataApi/GetCustomerTypes", "BaseDataApi/GetModalities", "BaseDataApi/GetBaseListData")
        for (op in ops) {
            fun doCall() = client.newCall(
                Request.Builder()
                    .url(papiUrl(PapiVersions.BASE_DATA, op))
                    .post(body)
                    .build()
            ).execute()
            val result: Map<String, String>? = try {
                var response = doCall()
                if (response.code == 401 || response.code == 403) {
                    response.close()
                    if (!ensureSession()) continue
                    response = doCall()
                }
                response.use { r ->
                    if (!r.isSuccessful) null
                    else collectTypeNames(json.parseToJsonElement(r.body!!.string()), mutableMapOf())
                        .takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) {
                null
            }
            if (!result.isNullOrEmpty()) {
                OfflineStore.saveTypeNames(context, result)
                PapiVersions.save(context)
                return@withContext result
            }
        }
        // offline fallback
        OfflineStore.loadTypeNames(context) ?: emptyMap()
    }

    private fun collectTypeNames(
        el: kotlinx.serialization.json.JsonElement,
        out: MutableMap<String, String>
    ): Map<String, String> {
        when (el) {
            is JsonObject -> {
                fun s(k: String) = (el[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.takeIf { it.isNotBlank() && it != "null" }
                // PAPI DetailedCustomerTypeApiModel: {id, key, name, description, ...};
                // legacy alak: {Kod, Nev} / {Azonosito, Nev}.
                val kod = s("Kod") ?: s("kod") ?: s("key") ?: s("Azonosito") ?: s("id")
                val nev = s("Nev") ?: s("nev") ?: s("name") ?: s("description")
                if (kod != null && nev != null) out[kod] = nev
                el.values.forEach { collectTypeNames(it, out) }
            }
            is kotlinx.serialization.json.JsonArray -> el.forEach { collectTypeNames(it, out) }
            else -> {}
        }
        return out
    }

    /**
     * Regisztráció – PAPI UserProfile/Profile/Registration.
     * Kötelező: email, firstName, lastName, password, privacyPolicyAccept,
     * termOfUseAccept, bornDate. Hiba esetén JSON {message} jön.
     */
    suspend fun register(
        email: String,
        lastName: String,
        firstName: String,
        birthDateIso: String,
        password: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        if (tokenStore.isDemo()) {
            return@withContext Result.success(null)
        }

        val body = buildJsonObject {
            put("email", email)
            put("firstName", firstName)
            put("lastName", lastName)
            put("password", password)
            put("privacyPolicyAccept", true)
            put("termOfUseAccept", true)
            put("bornDate", birthDateIso.take(10))
        }.toString().toRequestBody(jsonBody)

        runCatching {
            client.newCall(
                Request.Builder()
                    .url(papiUrl(PapiVersions.USER_PROFILE, "Profile/Registration"))
                    .post(body)
                    .build()
            ).execute().use { r ->
                val raw = r.body!!.string()
                if (!r.isSuccessful) {
                    val msg = runCatching {
                        json.parseToJsonElement(raw).jsonObject["message"]?.jsonPrimitive?.content
                    }.getOrNull()
                    error(msg ?: "Sikertelen regisztráció (HTTP ${r.code})")
                }
                null // siker: a szerver emailben küldi a megerősítést
            }
        }
    }

    /** Elfelejtett jelszó – PAPI UserProfile/Profile/ForgottenPassword (200 üres = siker). */
    suspend fun forgotPassword(email: String): Result<String?> = withContext(Dispatchers.IO) {
        if (tokenStore.isDemo()) {
            return@withContext Result.success("Demó mód – valós emailt nem küldünk")
        }

        val body = buildJsonObject { put("email", email) }
            .toString().toRequestBody(jsonBody)

        runCatching {
            client.newCall(
                Request.Builder()
                    .url(papiUrl(PapiVersions.USER_PROFILE, "Profile/ForgottenPassword"))
                    .post(body)
                    .build()
            ).execute().use { r ->
                val raw = r.body!!.string()
                if (!r.isSuccessful) {
                    val msg = runCatching {
                        json.parseToJsonElement(raw).jsonObject["message"]?.jsonPrimitive?.content
                    }.getOrNull()
                    error(msg ?: "Sikertelen kérés (HTTP ${r.code})")
                }
                null // 200 üres válasz = az új jelszó elment az email címre
            }
        }
    }

    /**
     * VIM (MobileServiceS) bejelentkezés – a GetJegykep EHHEZ a tokenhez kell,
     * NEM az IK SAML userTokenXml-hez (Bejelentkezes -> LoginResponseVO.Token).
     * FIGYELEM: magyar IP-ről működik; külföldi IP-ről a MÁV WAF blokkolja
     * a jelszavas kéréseket (hozzáférési szabályzat).
     */
    private suspend fun ensureVimSession(): Boolean = withContext(Dispatchers.IO) {
        val expiry = tokenStore.getVimTokenExpiry()
        if (!tokenStore.getVimToken().isNullOrBlank() &&
            expiry > System.currentTimeMillis() + 60_000L
        ) return@withContext true

        val email = tokenStore.getEmail() ?: return@withContext false
        val password = tokenStore.getPassword() ?: return@withContext false
        if (!tokenStore.hasUaid()) tokenStore.setUaid(generateUaid())

        val body = buildJsonObject {
            put("FelhasznaloAzonosito", email)
            put("Jelszo", password)
            put("Nyelv", "hu")
            put("UAID", tokenStore.getUaid())
        }.toString()

        return@withContext try {
            val resp = vimHttpPost("Bejelentkezes", body)
            if (resp.code != 200 || resp.contentType?.contains("json") != true) {
                lastVimError =
                    "VIM Bejelentkezes: HTTP ${resp.code}, ct=${resp.contentType}, body=${resp.body.take(200)}"
                Log.d("MAVJEGY", lastVimError ?: "")
                false
            } else {
                val root = json.parseToJsonElement(resp.body)
                val token = findStringKey(root, "Token") ?: return@withContext false
                tokenStore.setVimToken(token)
                tokenStore.setVimTokenExpiry(parseVimExpiry(findStringKey(root, "ErvenyessegVege")))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseVimExpiry(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        Regex("/Date\\((-?\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        raw.toLongOrNull()?.let { return if (it > 99_999_999_999L) it else it * 1000L }
        return try {
            java.time.ZonedDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * A SZERVER-OLDALI jegykép: első lekérés után MENTJÜK (hash-dedup + kompresszió),
     * és a KÉPEN LÉVŐ VONALKÓDOT dekódoljuk – ez az egyetlen hivatalos, scannelhető
     * bérlet-kód, amit lokálisan Aztec-ként is megjelenítünk.
     */
    suspend fun getServerJegyKep(
        purchaseId: String,
        bizonylatTechnikaiAzonosito: String?,
        context: android.content.Context,
        expired: Boolean = false
    ): ServerJegyképResult = withContext(Dispatchers.IO) {
        if (expired) {
            OfflineStore.deleteServerJegyKep(context, purchaseId)
            OfflineStore.deleteServerBarcode(context, purchaseId)
            return@withContext ServerJegyképResult(null, null, error = "A jegy lejárt – cache törölve")
        }
        // 1. Disk cache: kép + (ha van) már dekódolt kódszöveg
        val cachedImage = OfflineStore.loadServerJegyKep(context, purchaseId)
        val cachedText = OfflineStore.loadServerBarcode(context, purchaseId)
        if (cachedImage != null) {
            var text = cachedText
            if (text.isNullOrBlank()) {
                // képből utólagos dekódolás
                val bmp = android.graphics.BitmapFactory.decodeByteArray(cachedImage, 0, cachedImage.size)
                text = bmp?.let { com.domedav.mavjegy.util.BarcodeImageDecoder.decode(it) }
                if (!text.isNullOrBlank()) OfflineStore.saveServerBarcode(context, purchaseId, text)
            }
            return@withContext ServerJegyképResult(cachedImage, text, fromCache = true)
        }

        if (tokenStore.isDemo()) {
            val img = DemoData.demoTicketImage(purchaseId)
            return@withContext ServerJegyképResult(img, "DEMO-BARCODE-12345", fromCache = img != null)
        }
        // 2. PAPI-elsődleges: csak purchaseId kell (bizonylat-azonosító nem).
        // (élő próbával megerősítve). VIM-fallback megmarad (alább).
        PapiVersions.load(context)
        val papiImage = tryPapiCertificate(purchaseId)
        if (papiImage != null) {
            OfflineStore.saveServerJegyKep(context, purchaseId, papiImage)
            var text = OfflineStore.loadServerBarcode(context, purchaseId)
            if (text.isNullOrBlank()) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(papiImage, 0, papiImage.size)
                text = bmp?.let { com.domedav.mavjegy.util.BarcodeImageDecoder.decode(it) }
                if (!text.isNullOrBlank()) OfflineStore.saveServerBarcode(context, purchaseId, text)
            }
            return@withContext ServerJegyképResult(papiImage, text)
        }
        // 3. VIM-fallback (régi MobileServiceS GetJegykep): ehhez kell bizonylat-azonosító.
        if (bizonylatTechnikaiAzonosito.isNullOrBlank()) {
            return@withContext ServerJegyképResult(null, cachedText, error = "Nincs bizonylat-azonosító")
        }
        // 3. VIM-fallback (régi MobileServiceS GetJegykep) – amíg a PAPI
        // jegykép-op neve/modellje decompile-ból meg nem erősíthető.
        if (!ensureVimSession()) {
            return@withContext ServerJegyképResult(
                null, cachedText,
                error = "VIM bejelentkezés sikertelen (magyar IP szükséges)"
            )
        }

        val body = buildJsonObject {
            put("BizonylatAzonosito", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(bizonylatTechnikaiAzonosito))
            })
            put("FelhasznaloAzonosito", tokenStore.getEmail() ?: "")
            put("Nyelv", "hu")
            put("Token", tokenStore.getVimToken())
            put("UAID", tokenStore.getUaid())
        }.toString()

        return@withContext try {
            val resp = vimHttpPost("GetJegykep", body)
            val ct = resp.contentType ?: ""
            if (resp.code != 200 || !ct.contains("json")) {
                ServerJegyképResult(
                    null, cachedText,
                    error = "GetJegykep blokkolva (HTTP ${resp.code}, ct=${ct}): ${resp.body.take(200)}"
                )
            } else {
                val root = json.parseToJsonElement(resp.body)
                val b64 = findStringKey(root, "Jegykep")
                if (b64.isNullOrBlank()) {
                    ServerJegyképResult(
                        null, cachedText,
                        error = extractServerMessages(root) ?: "A szerver nem adott vissza jegyképet"
                    )
                } else {
                    val bytes = java.util.Base64.getDecoder().decode(b64)
                    OfflineStore.saveServerJegyKep(context, purchaseId, bytes)

                    var text = OfflineStore.loadServerBarcode(context, purchaseId)
                    if (text.isNullOrBlank()) {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        text = bmp?.let { com.domedav.mavjegy.util.BarcodeImageDecoder.decode(it) }
                        if (!text.isNullOrBlank()) OfflineStore.saveServerBarcode(context, purchaseId, text)
                    }
                    ServerJegyképResult(bytes, text)
                }
            }
        } catch (e: Exception) {
            ServerJegyképResult(null, cachedText, error = e.message ?: "Hálózati hiba")
        }
    }

    /**
     * PAPI jegykép: PreviousPurchase/GetPreviousPurchaseDetails certificateImages:true
     * flag-gel -> purchaseDetails.ticketCertificate[].certificateImage.image.image
     * (base64 PNG, élő próbával megerősítve: "JEGY-..."/"TAJEKOZTATO-..." fileName).
     */
    private suspend fun tryPapiCertificate(purchaseId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            // A details-hívás (getTicketDetails withImages=true) már meghozhatta
            // a képet -> ne töltsük le kétszer (közös parse-helperrel készült stash).
            synchronized(certImageMem) { certImageMem[purchaseId] }?.let { return@withContext it }
            if (!tokenStore.hasAuthToken() && !tokenStore.hasToken()) return@withContext null
            try {
                val payload = buildJsonObject {
                    put("purchaseId", purchaseId)
                    put("certificateImages", true)
                    put("passCardDetails", false)
                    put("includeRefundedItems", true)
                }.toString().toRequestBody(jsonBody)
                fun doCall() = client.newCall(
                    Request.Builder()
                        .url(papiUrl(PapiVersions.ORDER, "PreviousPurchase/GetPreviousPurchaseDetails"))
                        .post(payload)
                        .build()
                ).execute()
                var response = doCall()
                if (response.code == 401 || response.code == 403) {
                    response.close()
                    if (!ensureSession()) return@withContext null
                    response = doCall()
                }
                response.use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val root = runCatching {
                        json.parseToJsonElement(r.body!!.string()).jsonObject
                    }.getOrNull() ?: return@withContext null
                    if (businessErrorMessage(root) != null) return@withContext null
                    val details = root["purchaseDetails"] as? JsonObject ?: return@withContext null
                    // Közös helper (lásd extractFirstCertificateImageBytes).
                    extractFirstCertificateImageBytes(details)
                }
            } catch (_: Exception) {
                null
            }
        }

    /** PNG/JPEG/GIF/PDF magic-ellenőrzés. */
    private fun isImageBytes(b: ByteArray): Boolean {
        if (b.size < 8) return false
        return b[0] == 0x89.toByte() && b[1] == 0x50.toByte() // PNG
            || b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() // JPEG
            || b[0] == 0x47.toByte() && b[1] == 0x49.toByte() // GIF
            || b[0] == 0x25.toByte() && b[1] == 0x50.toByte() // %PDF
    }

    /** Base64 kép/PDF kivonása egy JSON-fából, magic-byte validációval. */
    private fun extractImageBytes(root: kotlinx.serialization.json.JsonElement): ByteArray? {
        val candidates = mutableListOf<String>()
        fun walk(el: kotlinx.serialization.json.JsonElement) {
            when (el) {
                is JsonObject -> {
                    for ((k, v) in el) {
                        if (v is kotlinx.serialization.json.JsonPrimitive && !v.content.isBlank()) {
                            val lk = k.lowercase()
                            if (lk.contains("certificate") || lk.contains("jegykep") ||
                                lk.contains("image") || lk.contains("content") ||
                                lk.contains("document") || lk.contains("pdf") ||
                                lk.contains("picture") || lk.contains("data")
                            ) candidates += v.content
                        }
                        walk(v)
                    }
                }
                is kotlinx.serialization.json.JsonArray -> el.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        for (c in candidates.sortedByDescending { it.length }) {
            val clean = c.trim()
            if (clean.length < 1000) continue
            val bytes = runCatching {
                java.util.Base64.getDecoder().decode(clean)
            }.getOrElse { runCatching {
                java.util.Base64.getMimeDecoder().decode(clean)
            }.getOrNull() } ?: continue
            if (bytes.size < 500) continue
            if (isImageOrPdf(bytes)) return bytes
        }
        return null
    }

    private fun isImageOrPdf(b: ByteArray): Boolean {
        if (b.size < 4) return false
        // PNG, JPEG, GIF, PDF magic
        return (b[0] == 0x89.toByte() && b[1] == 0x50.toByte()) ||
            (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
            (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()) ||
            (b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte())
    }

    private suspend fun reloginIfPossible(): Boolean {
        if (!tokenStore.hasCredentials()) return false
        return login(tokenStore.getEmail()!!, tokenStore.getPassword()!!).isSuccess
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.content.isBlank() && it.content != "null" }?.content

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"

    /**
     * PAPI PreviousPurchaseBaseInfo/Details.takeOverModes: List<TakeOverModeApiEnum>
     * (Mobile/LocationCenter/DelayedPrinted/Electronic/Local/HomePrinted,
     * lásd TakeOverModeApiEnumAdapter). Nem-üres lista = átvett.
     */
    private fun JsonObject.takeOverFlag(): Boolean {
        (this["takeOverModes"] as? kotlinx.serialization.json.JsonArray)
            ?.let { return !it.isEmpty() }
        return bool("takenOver")
    }

    /** Bérletigazolvány-szám: details.passengers[].passCard.passNumber (HPTUserPassCardApiModel). */
    private fun extractPassNumber(details: JsonObject): String? {
        val passengers = details["passengers"] as? kotlinx.serialization.json.JsonArray ?: return null
        for (el in passengers) {
            val p = el as? JsonObject ?: continue
            val passCard = p["passCard"] as? JsonObject ?: continue
            (passCard["passNumber"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        }
        return null
    }

    /**
     * Első jegykép-bájt a details.ticketCertificate[].certificateImage.image.image-ből
     * (base64 PNG; TicketCertificateApiModel + TicketsAndCertificatesApiModel + ImageApiModel).
     * Közös helper: getTicketDetails (memória-stash) és tryPapiCertificate (PAPI-ág) is ezt használja.
     */
    private fun extractFirstCertificateImageBytes(details: JsonObject): ByteArray? {
        val certs = details["ticketCertificate"] as? kotlinx.serialization.json.JsonArray ?: return null
        for (certEl in certs) {
            val cert = certEl as? JsonObject ?: continue
            val imgObj = ((cert["certificateImage"] as? JsonObject)?.get("image"))
                as? JsonObject ?: continue
            val b64 = (imgObj["image"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.takeIf { it.isNotBlank() } ?: continue
            val bytes = runCatching {
                java.util.Base64.getDecoder().decode(b64.trim())
            }.getOrNull() ?: continue
            if (isImageBytes(bytes)) return bytes
        }
        return null
    }

    /** getTicketDetails (withImages=true) által már meghozott cert-képek, dupla letöltés ellen. */
    private val certImageMem = mutableMapOf<String, ByteArray>()

    private fun JsonObject.priceAmount(): Double {
        val priceObj = this["price"] as? JsonObject ?: return 0.0
        val amt = priceObj["amount"] as? kotlinx.serialization.json.JsonPrimitive ?: return 0.0
        return amt.content.toDoubleOrNull() ?: 0.0
    }

    private fun JsonObject.currencyKey(): String {
        val priceObj = this["price"] as? JsonObject ?: return ""
        val cur = priceObj["currency"] as? JsonObject ?: return ""
        for (k in listOf("key", "currencyId", "name")) {
            (cur[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    // ---- VIM (MobileServiceS) hívások: HttpURLConnection + pontos eredeti-app header-ek ----
    // Azért HttpURLConnection (és nem OkHttp), hogy a platform Conscrypt TLS-ujjlenyomata
    // megegyezzen a hivatalos MÁV appéval -> a WAF (F5/FortiWeb) átengedi a /rest/ POST-okat.

    private data class VimResponse(val code: Int, val contentType: String?, val body: String)

    private suspend fun vimHttpPost(op: String, bodyJson: String): VimResponse =
        withContext(Dispatchers.IO) {
            val conn = java.net.URL(vimBaseUrl + op).openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "gzip")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                // Nincs User-Agent beállítva: a keretrendszer adja a gyári "Dalvik/..." UA-t,
                // amit a hivatalos app is küld -> a WAF ezt várja.
                conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val ct = conn.contentType
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true)
                    java.util.zip.GZIPInputStream(stream).readBytes()
                else stream.readBytes()
                VimResponse(code, ct, raw.toString(Charsets.UTF_8))
            } finally {
                conn.disconnect()
            }
        }

    /**
     * UAID generátor – portolva a hivatalos app k8/n1.smali fájljából.
     * Formátum: "0-" + 24 karakter (4x6 base62 az UUID 16 bájtjából) + 4 karakter checksum,
     * ahol a checksum a (karakterkód-összeg * 0x26f5) utolsó 4 tizes jegye, az utolsó jegy
     * helyére betűvel: chr(lastDigit + 0x61) ('a'..'j').
     */
    private fun generateUaid(): String {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        fun b62(n: Int): String {
            var x = n
            var acc = ""
            repeat(6) {
                acc = alphabet[x % 0x3e] + acc
                x /= 0x3e
            }
            return acc
        }

        fun rint(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff shl 24) or
                (b[o + 1].toInt() and 0xff shl 16) or
                (b[o + 2].toInt() and 0xff shl 2) or
                (b[o + 3].toInt() and 0xff)

        val uuid = java.util.UUID.randomUUID()
        val bytes = java.io.ByteArrayOutputStream().also {
            java.io.DataOutputStream(it).use { d ->
                d.writeLong(uuid.mostSignificantBits)
                d.writeLong(uuid.leastSignificantBits)
            }
        }.toByteArray()
        val huf = listOf(0, 4, 8, 12).joinToString("") { b62(kotlin.math.abs(rint(bytes, it))) }
        val prod = huf.sumOf { it.code } * 0x26f5
        val s = prod.toString()
        val last4 = s.substring(s.length - 4)
        val ca = last4.toCharArray()
        ca[ca.size - 1] = (last4.last().toString().toInt() + 0x61).toChar()
        return "0-$huf${String(ca)}"
    }

    suspend fun fetchMavinformList(page: Int = 0): List<MavinformItem> = withContext(Dispatchers.IO) {
        val url = if (page == 0) "https://www.mavcsoport.hu/mavinform"
                  else "https://www.mavcsoport.hu/mavinform?page=$page"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            MavinformScraper.parseList(it.body!!.string())
        }
    }

    suspend fun fetchMavinformDetail(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            MavinformScraper.parseDetail(it.body!!.string())
        }
    }

    private companion object {
        const val USER_AGENT =
            "MAVApp/2.5.18-prod (hu.mav.emmapp; build: 874; Android 14)"

        /** Rekurzív keresés a JSON-fában (a PAPI beágyazva is adhatja a tokent). */
        fun findFirstString(root: JsonObject, keys: List<String>): String? {
            val lower = keys.map { it.lowercase() }.toSet()
            val queue = ArrayDeque<JsonObject>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val obj = queue.removeFirst()
                for ((k, v) in obj) {
                    if (k.lowercase() in lower && v is kotlinx.serialization.json.JsonPrimitive) {
                        runCatching { v.content }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                    when (v) {
                        is JsonObject -> queue.add(v)
                        is kotlinx.serialization.json.JsonArray -> v.forEach { el ->
                            if (el is JsonObject) queue.add(el)
                        }
                        else -> Unit
                    }
                }
            }
            return null
        }

        fun collectKeys(root: JsonObject): Set<String> {
            val out = mutableSetOf<String>()
            val queue = ArrayDeque<JsonObject>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val obj = queue.removeFirst()
                for ((k, v) in obj) {
                    out.add(k)
                    if (v is JsonObject) queue.add(v)
                }
            }
            return out
        }
    }
}
