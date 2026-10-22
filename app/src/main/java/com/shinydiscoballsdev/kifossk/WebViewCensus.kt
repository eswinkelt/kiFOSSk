package com.shinydiscoballsdev.kifossk

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView

/**
 * Shared census of the installed WebView engine.
 *
 * Semantics carried over verbatim from the MainActivity probe (v1.1.3):
 *   - legacy threshold: Chromium major < 72 (i.e. <= 71)
 *   - detection failure => "unknown" => legacy (skip intervention —
 *     the just-works default; Chromium's own error handling takes over)
 *
 * New in v1.1.4:
 *   - com.android.webview added to the probe list (pure-AOSP devices,
 *     GrapheneOS — previously undetectable on API < 26)
 *   - shared with SettingsActivity for the version-info footer
 */
object WebViewCensus {

    /** Legacy = Chromium major < 72. Matches v1.1.3 gauntlet classification. */
    const val LEGACY_THRESHOLD_MAJOR = 72

    var versionName: String? = null
        private set

    var major: Int? = null
        private set

    val isLegacy: Boolean
        get() = (major ?: 0) < LEGACY_THRESHOLD_MAJOR

    fun refresh(context: Context) {
        val vm: String? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage()?.versionName
            } else {
                val pm = context.packageManager
                listOf(
                    "com.android.webview",        // pure AOSP / GrapheneOS
                    "com.google.android.webview",
                    "com.android.chrome"
                ).firstNotNullOfOrNull { pkg ->
                    pm.getInstalledPackages(0).find { it.packageName == pkg }?.versionName
                }
            }
        } catch (_: Exception) {
            null
        }

        versionName = vm
        major = vm?.split('.')?.firstOrNull()?.toIntOrNull()
        Log.i("KioskWebView", "engine=${vm ?: "unknown"} legacy=$isLegacy")
    }
}