package com.domedav.mavjegy.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * Demó mód adatai – Demo / Demo belépéssel elérhető, teljesen offline.
 * A serialized JSON pontosan olyan struktúrájú, mint az éles API válasz,
 * így a TicketDecoder / vonalkód-generátor / tulajdonos-kinyerés valódi útvonalon fut.
 */
internal object DemoData {

    const val DEMO_EMAIL = "Demo"
    const val DEMO_PASSWORD = "Demo"

    private val dayMs = 24L * 60 * 60 * 1000

    private fun iso(offsetDays: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis += offsetDays * dayMs }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
        return fmt.format(cal.time)
    }

    /** raw-deflate + base64 – a TicketDecoder.decodeSerialized pontosan ezt várja */
    private fun deflateB64(json: String): String = try {
        val input = json.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(input)
        deflater.finish()
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        Base64.getEncoder().encodeToString(out.toByteArray())
    } catch (_: Exception) {
        ""
    }

    private fun ticketJson(
        kod: String,
        jegySorszam: String,
        nev: String,
        szuletesiDatum: String,
        nevesitesAzonosito: String
    ): String = """
        {
          "JegySorszam": "$jegySorszam",
          "UtazoNeve": "$nev",
          "SzuletesiDatum": "$szuletesiDatum",
          "NevesitesAzonosito": "$nevesitesAzonosito",
          "Kod": "$kod",
          "Ar": {"Osszeg": 0}
        }
    """.trimIndent().replace("\n", "").replace("  ", "")

    fun purchases(): List<Purchase> = listOf(
        // --- Bérletek ---
        Purchase(
            id = "DEMO_BERLET_1",
            validFrom = iso(-15),
            validTo = iso(15),
            startStation = null,
            endStation = null,
            status = "Ervenyes",
            takenOver = true,
            amount = 11450.0,
            currency = "HUF",
            name = "Havi bérlet – Budapest 100+"
        ),
        Purchase(
            id = "DEMO_BERLET_2",
            validFrom = iso(-60),
            validTo = iso(305),
            startStation = null,
            endStation = null,
            status = "Ervenyes",
            takenOver = true,
            amount = 9450.0,
            currency = "HUF",
            name = "Diákbérlet – Országos"
        ),
        // --- Érvényes jegyek ---
        Purchase(
            id = "DEMO_JEGY_1",
            validFrom = iso(0),
            validTo = iso(0),
            startStation = "Budapest-Keleti",
            endStation = "Szeged",
            status = "Ervenyes",
            takenOver = false,
            amount = 4280.0,
            currency = "HUF",
            name = "IC jegy 2. osztály"
        ),
        Purchase(
            id = "DEMO_JEGY_2",
            validFrom = iso(3),
            validTo = iso(3),
            startStation = "Budapest-Déli",
            endStation = "Pécs",
            status = "Ervenyes",
            takenOver = false,
            amount = 6950.0,
            currency = "HUF",
            name = "IC jegy 1. osztály"
        ),
        Purchase(
            id = "DEMO_JEGY_3",
            validFrom = iso(7),
            validTo = iso(7),
            startStation = "Győr",
            endStation = "Budapest-Keleti",
            status = "Ervenyes",
            takenOver = false,
            amount = 3120.0,
            currency = "HUF",
            name = "IC jegy kedvezményesen"
        ),
        // --- Lejárt jegy ---
        Purchase(
            id = "DEMO_JEGY_4",
            validFrom = iso(-5),
            validTo = iso(-4),
            startStation = "Eger",
            endStation = "Füzesabony",
            status = "Lejart",
            takenOver = false,
            amount = 1250.0,
            currency = "HUF",
            name = "Személyvonat jegy"
        )
    )

    private data class Passenger(val nev: String, val szuletes: String, val azonosito: String)

    private val passengers = listOf(
        Passenger("Kovács Anna", "2003-04-12", "1000123456"),
        Passenger("Nagy Péter", "1995-11-30", "1000987654"),
        Passenger("Szabó Eszter", "1998-07-22", "1000555123"),
        Passenger("Tóth Márton", "1990-01-15", "1000777890")
    )

    fun ticketDetails(id: String): TicketDetails {
        val p = purchases().firstOrNull { it.id == id } ?: return TicketDetails(null)
        val isPass = p.startStation == null
        val px = when (p.id) {
            "DEMO_BERLET_1" -> passengers[0]
            "DEMO_BERLET_2" -> passengers[1]
            "DEMO_JEGY_1" -> passengers[0]
            "DEMO_JEGY_2" -> passengers[2]
            "DEMO_JEGY_3" -> passengers[3]
            "DEMO_JEGY_4" -> passengers[1]
            else -> passengers[0]
        }
        val kod = when (p.id) {
            "DEMO_BERLET_1" -> "HU-HAVIBERLET-2024-001234"
            "DEMO_BERLET_2" -> "HU-DIAKBERLET-2024-005678"
            "DEMO_JEGY_1" -> "HU-IC-2024-EL001234"
            "DEMO_JEGY_2" -> "HU-IC-2024-EL005678"
            "DEMO_JEGY_3" -> "HU-IC-2024-KEDV009012"
            "DEMO_JEGY_4" -> "HU-SZEM-2024-003456"
            else -> "HU-DEMO-000000"
        }
        val sorszam = when (p.id) {
            "DEMO_BERLET_1" -> "1000777001"
            "DEMO_BERLET_2" -> "1000888002"
            "DEMO_JEGY_1" -> "1001122003"
            "DEMO_JEGY_2" -> "1001556004"
            "DEMO_JEGY_3" -> "1001334005"
            "DEMO_JEGY_4" -> "1001998006"
            else -> "1000000000"
        }
        val serialized = deflateB64(
            ticketJson(
                kod = kod,
                jegySorszam = sorszam,
                nev = px.nev,
                szuletesiDatum = px.szuletes,
                nevesitesAzonosito = px.azonosito
            )
        )
        return TicketDetails(
            ticketData = TicketData(
                serializedTicketData = serialized,
                jegySorszam = sorszam
            ),
            ajanlatNev = p.name,
            ervenyessegKezdete = p.validFrom,
            ervenyessegVege = p.validTo
        )
    }

    fun passOwner(): PassOwnerData = PassOwnerData(
        fullName = "Kovács Anna",
        birthDate = "2003-04-12",
        photoBase64 = null,
        azonosito = "1000123456"
    )

    fun typeNames(): Map<String, String> = mapOf(
        "HU_Felnott" to "Felnőtt",
        "HU_DIak" to "Diák",
        "HU_Nyugdijas" to "Nyugdíjas",
        "HU_Gyermek" to "Gyermek",
        "HU_Csaladtag" to "Családtag",
        "HU_Kisero" to "Kísérő"
    )

    /** Demó jegykép generálás – egyszerű szöveges ticket placeholder */
    fun demoTicketImage(purchaseId: String): ByteArray? {
        val p = purchases().firstOrNull { it.id == purchaseId } ?: return null
        val w = 1080
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Háttér
        canvas.drawColor(Color.WHITE)

        val bgPaint = Paint().apply { color = Color.rgb(0xF5, 0xF5, 0xF5) }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Felső sáv
        val headerPaint = Paint().apply { color = Color.rgb(0x1B, 0x5E, 0x20) }
        canvas.drawRect(0f, 0f, w.toFloat(), 200f, headerPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("MÁV", 60f, 120f, titlePaint)

        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
        }
        canvas.drawText("Jegy / Bérlet", 60f, 170f, subtitlePaint)

        // Jegy neve
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(p.name ?: "", 60f, 300f, namePaint)

        // Viszonylat
        val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x42, 0x42, 0x42)
            textSize = 40f
        }
        val route = if (p.startStation != null && p.endStation != null) {
            "${p.startStation} → ${p.endStation}"
        } else {
            "Országos érvényű"
        }
        canvas.drawText(route, 60f, 380f, routePaint)

        // Elválasztó vonal
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xBD, 0xBD, 0xBD)
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        canvas.drawLine(60f, 430f, (w - 60).toFloat(), 430f, linePaint)

        // Ár
        val pricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x1B, 0x5E, 0x20)
            textSize = 72f
            typeface = Typeface.DEFAULT_BOLD
        }
        val priceText = "%,d Ft".format(p.amount.toInt()).replace(",", " ")
        canvas.drawText(priceText, 60f, 560f, pricePaint)

        // Adatok
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x75, 0x75, 0x75)
            textSize = 30f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 36f
        }

        var y = 660f
        canvas.drawText("Érvényes", 60f, y, labelPaint)
        y += 40f
        val df = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.forLanguageTag("hu"))
        val validFrom = try { df.format(java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(p.validFrom ?: "") ?: java.util.Date()) } catch (_: Exception) { "-" }
        val validTo = try { df.format(java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(p.validTo ?: "") ?: java.util.Date()) } catch (_: Exception) { "-" }
        canvas.drawText("$validFrom – $validTo", 60f, y, valuePaint)

        y += 80f
        canvas.drawText("Állapot", 60f, y, labelPaint)
        y += 40f
        canvas.drawText(p.status, 60f, y, valuePaint)

        y += 80f
        canvas.drawText("Sorszám", 60f, y, labelPaint)
        y += 40f
        val sorszam = when (p.id) {
            "DEMO_BERLET_1" -> "1000777001"
            "DEMO_BERLET_2" -> "1000888002"
            "DEMO_JEGY_1" -> "1001122003"
            "DEMO_JEGY_2" -> "1001556004"
            "DEMO_JEGY_3" -> "1001334005"
            "DEMO_JEGY_4" -> "1001998006"
            else -> "0000000000"
        }
        canvas.drawText(sorszam, 60f, y, valuePaint)

        y += 80f
        canvas.drawText("Utas", 60f, y, labelPaint)
        y += 40f
        val utas = when (p.id) {
            "DEMO_BERLET_1", "DEMO_JEGY_1" -> "Kovács Anna"
            "DEMO_BERLET_2", "DEMO_JEGY_4" -> "Nagy Péter"
            "DEMO_JEGY_2" -> "Szabó Eszter"
            "DEMO_JEGY_3" -> "Tóth Márton"
            else -> "Kovács Anna"
        }
        canvas.drawText(utas, 60f, y, valuePaint)

        // Alsó dekorációs vonal
        canvas.drawLine(60f, (h - 300).toFloat(), (w - 60).toFloat(), (h - 300).toFloat(), linePaint)

        // Alsó szöveg
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xBD, 0xBD, 0xBD)
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Demó jegykép – nem érvényes utazásra", w / 2f, (h - 240).toFloat(), footerPaint)
        canvas.drawText("MÁV-Volán Egyesített Pénztár", w / 2f, (h - 200).toFloat(), footerPaint)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    fun matches(email: String, password: String): Boolean =
        email.trim().equals(DEMO_EMAIL, ignoreCase = true) &&
            password.trim().equals(DEMO_PASSWORD, ignoreCase = true)
}
