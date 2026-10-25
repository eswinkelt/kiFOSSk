package com.shinydiscoballsdev.kifossk

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

object KioskPrefs {
    private const val PREFS_NAME = "kiosk_prefs"
    private const val DEFAULT_URL = "http://192.168.50.152:3001"
    private const val ADMIN_PIN_HASH = "admin_pin_hash"
    private const val ADMIN_PIN_SALT = "kifossk-admin-v1"

    private var prefsInstance: SharedPreferences? = null

    fun getInstance(context: Context): SharedPreferences {
        if (prefsInstance == null) {
            prefsInstance = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        return prefsInstance!!
    }

    // BUG #1: Returns empty string if user saves "", doesn't validate
    fun getUrl(context: Context): String {
        val prefs = getInstance(context)
        val url = prefs.getString("web_url", null)  // Changed: null instead of DEFAULT_URL
        // FIX: Use UrlValidator to sanitize
        return UrlValidator.sanitizeOrDefault(url, DEFAULT_URL)
    }

    fun setUrl(context: Context, url: String) {
        getInstance(context).edit().putString("web_url", url).commit()
    }

    fun isFirstRun(context: Context): Boolean {
        return getInstance(context).getBoolean("first_run", true)
    }

    fun setFirstRun(context: Context, value: Boolean) {
        getInstance(context).edit().putBoolean("first_run", value).commit()
    }

    fun getOrientation(context: Context): String {
        return getInstance(context).getString("orientation", "landscape") ?: "landscape"
    }

    fun setOrientation(context: Context, orientation: String) {
        getInstance(context).edit().putString("orientation", orientation).commit()
    }

    fun getScreenOn(context: Context): Boolean {
        return getInstance(context).getBoolean("screen_on", true)
    }

    fun setScreenOn(context: Context, value: Boolean) {
        getInstance(context).edit().putBoolean("screen_on", value).apply()
    }

    fun isAutoRefreshEnabled(context: Context): Boolean {
        return getInstance(context).getBoolean("auto_refresh_enabled", false)
    }

    fun getAutoRefreshInterval(context: Context): Int {
        return getInstance(context).getInt("auto_refresh_interval", 30)
    }

    fun setAutoRefresh(context: Context, enabled: Boolean, intervalSeconds: Int) {
        getInstance(context).edit()
            .putBoolean("auto_refresh_enabled", enabled)
            .putInt("auto_refresh_interval", intervalSeconds)
            .apply()
    }

    // Admin-PIN für den Long-Press in die Einstellungen. Nur ein Hash wird gespeichert,
    // damit die PIN nicht im Klartext in den App-Daten steht.
    fun hasAdminPin(context: Context): Boolean =
        !getInstance(context).getString(ADMIN_PIN_HASH, null).isNullOrEmpty()

    fun setAdminPin(context: Context, pin: String) {
        getInstance(context).edit().putString(ADMIN_PIN_HASH, hashPin(pin)).commit()
    }

    fun checkAdminPin(context: Context, pin: String): Boolean {
        val stored = getInstance(context).getString(ADMIN_PIN_HASH, null) ?: return false
        return stored == hashPin(pin)
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest((ADMIN_PIN_SALT + pin).toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}