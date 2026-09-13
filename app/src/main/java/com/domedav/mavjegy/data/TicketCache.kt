package com.domedav.mavjegy.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import androidx.compose.runtime.mutableStateMapOf
import java.time.format.DateTimeFormatter

@Serializable
data class CachedTicketDetails(
    val serializedTicketData: String? = null,
    val jegySorszam: String? = null,
    val bizonylatTechnikaiAzonosito: String? = null,
    val ajanlatNev: String? = null,
    val ervenyessegKezdete: String? = null,
    val ervenyessegVege: String? = null,
    val passNumber: String? = null,
    val fetchedAt: Long = 0L
)

object TicketCache {
    private val json = Json { ignoreUnknownKeys = true }
    private const val DIR = "ticket_cache"
    private val mutex = Mutex()
    private const val STALE_DAYS = 30L
    private const val STALE_MILLIS = STALE_DAYS * 24 * 60 * 60 * 1000

    private val memoryNameCache = mutableStateMapOf<String, String>()

    fun getNameMem(id: String): String? = memoryNameCache[id]

    fun putNameMem(id: String, name: String?) {
        if (!name.isNullOrBlank() && name != "null") memoryNameCache[id] = name
        else memoryNameCache.remove(id)
    }

    // Thread-safe, immutable DateTimeFormatter instances – created once
    private val isoFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
    )

    private fun expirationMillis(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (fmt in isoFormatters) {
            try {
                val parsed = fmt.parse(s)
                return try {
                    OffsetDateTime.from(parsed).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    try {
                        LocalDateTime.from(parsed)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (_: Exception) {
                        LocalDate.from(parsed)
                            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun file(context: Context, purchaseId: String) =
        java.io.File(java.io.File(context.filesDir, DIR),
            purchaseId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Atomikus fájlírás: .tmp fájlba írás, majd rename. App kill közben a régi fájl sértetlen marad. */
    private fun atomicWrite(target: java.io.File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = java.io.File(target.parent, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    suspend fun save(context: Context, purchaseId: String, details: TicketDetails) = mutex.withLock {
        try {
            val f = file(context, purchaseId)
            atomicWrite(f, json.encodeToString(
                CachedTicketDetails.serializer(),
                CachedTicketDetails(
                    serializedTicketData = details.ticketData?.serializedTicketData,
                    jegySorszam = details.ticketData?.jegySorszam,
                    bizonylatTechnikaiAzonosito = details.ticketData?.bizonylatTechnikaiAzonosito,
                    ajanlatNev = details.ajanlatNev,
                    ervenyessegKezdete = details.ervenyessegKezdete,
                    ervenyessegVege = details.ervenyessegVege,
                    passNumber = details.passNumber,
                    fetchedAt = System.currentTimeMillis()
                )
            ))
        } catch (_: Exception) {}
    }

    suspend fun load(context: Context, purchaseId: String): TicketDetails? = mutex.withLock {
        try {
            val f = file(context, purchaseId)
            if (!f.exists()) return@withLock null
            val c = json.decodeFromString(CachedTicketDetails.serializer(), f.readText())
            // Cache lejárat: jegy lejárata VAGY 30 napnál régebbi cache
            val exp = expirationMillis(c.ervenyessegVege)
            val stale = c.fetchedAt > 0 && System.currentTimeMillis() - c.fetchedAt > STALE_MILLIS
            if ((exp != null && System.currentTimeMillis() > exp) || stale) {
                f.delete()
                return@withLock null
            }
            TicketDetails(
                ticketData = TicketData(c.serializedTicketData, c.jegySorszam, c.bizonylatTechnikaiAzonosito),
                ajanlatNev = c.ajanlatNev,
                ervenyessegKezdete = c.ervenyessegKezdete,
                ervenyessegVege = c.ervenyessegVege,
                passNumber = c.passNumber
            )
        } catch (_: Exception) {
            try { file(context, purchaseId).delete() } catch (_: Exception) {}
            null
        }
    }

    /** Csak a jegy neve, ha a jegy még nem járt le – lejárt jegyek cache-e automatikusan törlődik (párosításhoz szükséges adat) */
    suspend fun loadName(context: Context, purchaseId: String): String? = mutex.withLock {
        try {
            val f = file(context, purchaseId)
            if (!f.exists()) return@withLock null
            val c = json.decodeFromString(CachedTicketDetails.serializer(), f.readText())
            val exp = expirationMillis(c.ervenyessegVege)
            val stale = c.fetchedAt > 0 && System.currentTimeMillis() - c.fetchedAt > STALE_MILLIS
            if ((exp != null && System.currentTimeMillis() > exp) || stale) {
                f.delete()
                return@withLock null
            }
            c.ajanlatNev?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            try { file(context, purchaseId).delete() } catch (_: Exception) {}
            null
        }
    }

    /** Lejárt / eltávolított jegyek cache-ének törlése – nem létezőként kezeljük */
    suspend fun delete(context: Context, purchaseId: String) = mutex.withLock {
        try {
            file(context, purchaseId).delete()
        } catch (_: Exception) {}
    }
}
