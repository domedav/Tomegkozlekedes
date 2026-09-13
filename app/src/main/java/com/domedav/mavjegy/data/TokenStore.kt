package com.domedav.mavjegy.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            createSecure(context)
        } catch (_: Throwable) {
            // sérült keystore / prefs: töröljük és újrapróbáljuk
            context.getSharedPreferences("mavjegy_secure_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()
            val filesDir = context.filesDir
            java.io.File(filesDir.parent, "shared_prefs/mavjegy_secure_prefs.xml").delete()
            runCatching { createSecure(context) }.getOrElse {
                context.getSharedPreferences("mavjegy_prefs_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createSecure(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            "mavjegy_secure_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun getPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    fun setCredentials(email: String, password: String) {
        prefs.edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun setUserId(id: String) {
        prefs.edit().putString(KEY_USER_ID, id).apply()
    }

    fun getUaid(): String = prefs.getString(KEY_UAID, null) ?: ""

    fun hasUaid(): Boolean = !getUaid().isNullOrBlank()

    fun setUaid(id: String) {
        prefs.edit().putString(KEY_UAID, id).apply()
    }

    // --- Demo mód (Demo / Demo belépés) ---
    fun isDemo(): Boolean = prefs.getBoolean(KEY_DEMO, false)

    fun setDemo(demo: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, demo).apply()
    }

    // --- VIM (MobileServiceS) session – GetJegykep-hoz ---
    fun getVimToken(): String? = prefs.getString(KEY_VIM_TOKEN, null)

    fun setVimToken(token: String) {
        prefs.edit().putString(KEY_VIM_TOKEN, token).apply()
    }

    /** VIM token lejárata, epoch millis; 0 = ismeretlen */
    fun getVimTokenExpiry(): Long = prefs.getLong(KEY_VIM_EXPIRY, 0L)

    fun setVimTokenExpiry(expiryMillis: Long) {
        prefs.edit().putLong(KEY_VIM_EXPIRY, expiryMillis).apply()
    }

    /** Utolsó sikeres login időpontja, epoch millis; 0 = ismeretlen (régi telepítés) */
    fun getLoginTime(): Long = prefs.getLong(KEY_LOGIN_TIME, 0L)

    fun setLoginTime(millis: Long) {
        prefs.edit().putLong(KEY_LOGIN_TIME, millis).apply()
    }

    /** 1 login 1 napig érvényes — utána auto relogin kell */
    fun isLoginExpired(now: Long = System.currentTimeMillis()): Boolean =
        now - getLoginTime() >= LOGIN_VALIDITY_MS

    fun hasToken(): Boolean = !getToken().isNullOrBlank()

    // --- PAPI auth (mvapi.mav.hu) ---
    fun getAuthToken(): String? = prefs.getString(KEY_AUTH_TOKEN, null)

    fun setAuthToken(token: String) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun hasAuthToken(): Boolean = !getAuthToken().isNullOrBlank()

    fun getUserGuid(): String? = prefs.getString(KEY_USER_GUID, null)

    fun setUserGuid(guid: String) {
        prefs.edit().putString(KEY_USER_GUID, guid).apply()
    }

    fun getOrCreateDeviceInstance(): String {
        var v = prefs.getString(KEY_DEVICE_INSTANCE, null)
        if (v.isNullOrBlank()) {
            v = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_INSTANCE, v).apply()
        }
        return v!!
    }

    fun getOrCreatePartnerSession(): String {
        var v = prefs.getString(KEY_PARTNER_SESSION, null)
        if (v.isNullOrBlank()) {
            v = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_PARTNER_SESSION, v).apply()
        }
        return v!!
    }

    fun hasCredentials(): Boolean =
        !getEmail().isNullOrBlank() && !getPassword().isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        /** 1 login 1 napig érvényes — utána auto relogin */
        const val LOGIN_VALIDITY_MS = 24 * 60 * 60 * 1000L

        private const val KEY_TOKEN = "userTokenXml"
        private const val KEY_LOGIN_TIME = "loginTime"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_USER_ID = "felhasznaloAzonosito"
        private const val KEY_UAID = "uaid"
        private const val KEY_DEMO = "demoMode"
        private const val KEY_VIM_TOKEN = "vimToken"
        private const val KEY_VIM_EXPIRY = "vimTokenExpiry"
        private const val KEY_AUTH_TOKEN = "authToken"
        private const val KEY_USER_GUID = "userGuid"
        private const val KEY_DEVICE_INSTANCE = "deviceInstance"
        private const val KEY_PARTNER_SESSION = "partnerSession"
    }
}
