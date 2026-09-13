package com.domedav.mavjegy.data

import android.os.Build
import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/**
 * PAPI fejlécek (eredeti minta: jadx C3998a.java 35-65).
 * Minden kérésre ráteszi a MÁV Plusz mobil app fejléceit.
 */
class PapiHeaders(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // .header() felülír, így nem duplikálunk
        if (original.body?.contentType() == null) {
            builder.header("Content-Type", "application/json")
        }
        builder.header("Accept", "text/plain")
        builder.header("language", "hu")
        builder.header("je-api-key", API_KEY)
        builder.header("je-partner-name", PARTNER_NAME)
        builder.header("partner-session-name", tokenStore.getOrCreatePartnerSession())
        builder.header("device-instance", tokenStore.getOrCreateDeviceInstance())
        builder.header("correlation-id", UUID.randomUUID().toString())
        builder.header("Referer", "mavpluszmobile")
        builder.header(
            "User-Agent",
            "MAVApp/2.5.18-prod (hu.mav.emmapp; build: 874; Android ${Build.VERSION.RELEASE})"
        )

        val guid = tokenStore.getUserGuid()
        if (!guid.isNullOrBlank()) {
            builder.header("user-guid", guid)
        }
        val auth = tokenStore.getAuthToken()
        if (!auth.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $auth")
        }

        return chain.proceed(builder.build())
    }

    companion object {
        const val API_KEY = "232AC6FE-46BE-479B-894D-BFC96E548945"
        const val PARTNER_NAME = "App2025"
    }
}
