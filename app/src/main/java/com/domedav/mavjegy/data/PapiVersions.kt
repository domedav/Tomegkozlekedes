package com.domedav.mavjegy.data

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PAPI service-path térkép.
 *
 * A MÁV PAPI minden szolgáltatása verziózott path alatt fut:
 * `UserProfile/4_13_0_33/`, `Order/4_13_0_17/`, ... A szerver a login /
 * szolgáltatás-URL válaszban közölheti az aktuális URL-eket
 * (userProfileServiceUrl, orderServiceUrl, ...); amit megkapunk, azt
 * eltároljuk (SharedPreferences) és a hardcoded fallback helyett használjuk.
 *
 * BIZONYTALANSÁG: a fallback verziók élő próbával frissítve (2026-09-13,
 * probe_papi.py, VersionInfo/GetVersionInfo válasza); a szerver küldhet újabb
 * service-URL térképet, ami felülírja őket.
 */
object PapiVersions {

    const val USER_PROFILE = "userProfile"
    const val ORDER = "order"
    const val MANAGEMENT = "management"
    const val BASE_DATA = "baseData"
    const val LOG = "log"
    const val OFFER = "offer"
    const val REFUND = "refund"
    const val SCHEDULER = "scheduler"

    /** JSON mezőnév -> service-név. */
    private val jsonFields = mapOf(
        "userProfileServiceUrl" to USER_PROFILE,
        "orderServiceUrl" to ORDER,
        "managementServiceUrl" to MANAGEMENT,
        "baseDataServiceUrl" to BASE_DATA,
        "logServiceUrl" to LOG,
        "offerServiceUrl" to OFFER,
        "refundServiceUrl" to REFUND,
        "schedulerServiceUrl" to SCHEDULER
    )

    /** Fallback service-pathek (mindig van trailing "/"). Élő VersionInfo alapján (2026-09-13). */
    private val defaults = mapOf(
        USER_PROFILE to "UserProfile/4_13_0_47/",
        ORDER to "Order/4_13_0_23/",
        MANAGEMENT to "Management/4_13_0_11/",
        BASE_DATA to "BaseData/4_13_0_12/",
        LOG to "Log/4_13_0_10/",
        OFFER to "Offer/4_13_0_10/",
        REFUND to "Refund/4_13_0_10/",
        SCHEDULER to "Scheduler/4_13_0_5/"
    )

    @Volatile
    private var overrides: Map<String, String> = emptyMap()

    /** Service-path trailing "/"-rel, pl. "UserProfile/4_13_0_33/". */
    fun servicePath(name: String): String =
        overrides[name] ?: defaults[name] ?: "$name/"

    /**
     * Szerver által küldött service-URL JSON feldolgozása.
     * Rekurzívan keresi a fenti mezőneveket, a talált URL-ekből path-ot
     * vág ("/IN/PROD/" utáni rész, különben host utáni path) és megjegyzi.
     * @return az alkalmazott service -> path párok.
     */
    fun updateFromPapiUrlJson(root: JsonElement): Map<String, String> {
        val applied = mutableMapOf<String, String>()
        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    for ((k, v) in el) {
                        val service = jsonFields[k]
                        if (service != null && v is JsonPrimitive && v.content.isNotBlank()) {
                            urlToPath(v.content)?.let { applied[service] = it }
                        }
                        walk(v)
                    }
                }
                is JsonArray -> el.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        if (applied.isNotEmpty()) {
            overrides = overrides + applied
        }
        return applied
    }

    /** Teljes service-URL-ből relatív path, pl. ".../IN/PROD/Order/4_13_0_17/" -> "Order/4_13_0_17/". */
    fun urlToPath(url: String): String? {
        val trimmed = url.trim().trim('"').ifBlank { return null }
        var path = when {
            "/IN/PROD/" in trimmed -> trimmed.substringAfter("/IN/PROD/")
            "://" in trimmed -> trimmed.substringAfter("://").substringAfter("/", "")
            else -> trimmed.trimStart('/')
        }
        if (path.isBlank()) return null
        if (!path.endsWith("/")) path += "/"
        // Biztonság: csak "Service/x_y_z/" alakot fogadunk el.
        if (!Regex("[A-Za-z]+/[0-9_.]+/").matches(path)) return null
        return path
    }

    // ---- SharedPreferences perzisztencia ----

    private const val PREFS = "papi_service_versions"

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = mutableMapOf<String, String>()
        for (service in defaults.keys) {
            prefs.getString("path_$service", null)?.takeIf { it.isNotBlank() }?.let {
                loaded[service] = it
            }
        }
        if (loaded.isNotEmpty()) overrides = overrides + loaded
    }

    fun save(context: Context) {
        val current = overrides
        if (current.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            for ((service, path) in current) putString("path_$service", path)
            apply()
        }
    }
}
