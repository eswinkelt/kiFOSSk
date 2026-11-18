package com.shinydiscoballsdev.kifossk

import android.net.Uri
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Fällt auf eine feste Board-Adresse (IP) zurück, wenn der Name der Board-URL nicht auflöst.
 *
 * Hintergrund: MagicOS (Honor) ersetzt nach DNS-Aussetzern den per DHCP gelieferten DNS still
 * durch öffentliche Server (8.8.8.8, 208.67.222.222). Die kennen den internen Namen nicht.
 * Der Service Worker des Boards liefert die Oberfläche dann aus dem Cache, die WebView meldet
 * keinen Fehler und das Board bleibt auf „Offline“ stehen. Deshalb prüft die App den Namen selbst.
 */
object BoardFailover {
    const val FIRST_CHECK_DELAY_MS = 10_000L
    const val CHECK_INTERVAL_MS = 30_000L

    /** Zwei Fehlschläge hintereinander, damit ein einzelner Aussetzer nicht umschaltet. */
    const val FAILS_BEFORE_SWITCH = 2
    private const val CONNECT_TIMEOUT_MS = 2_000

    enum class Action { NONE, USE_FALLBACK, USE_PRIMARY }

    fun decide(
        primaryResolves: Boolean,
        fallbackReachable: Boolean,
        usingFallback: Boolean,
        consecutiveFails: Int
    ): Action = when {
        primaryResolves && usingFallback -> Action.USE_PRIMARY
        !primaryResolves && !usingFallback &&
            consecutiveFails >= FAILS_BEFORE_SWITCH && fallbackReachable -> Action.USE_FALLBACK
        else -> Action.NONE
    }

    /** Blockiert. Nur im Hintergrund aufrufen. */
    fun resolves(host: String): Boolean = try {
        InetAddress.getAllByName(host).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** Blockiert. Nur im Hintergrund aufrufen. Prüft, ob die Ersatzadresse per TCP antwortet. */
    fun reachable(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        val port = when {
            uri.port != -1 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Nur Board-Seiten werden umgeschaltet, nie eine fremde Seite wie Google oder ein Rezept. */
    fun isBoardPage(url: String?, primary: String, fallback: String): Boolean {
        if (url.isNullOrEmpty() ||
            url.startsWith("about:") ||
            url.startsWith("chrome-error:") ||
            url.startsWith("file:///android_asset/")
        ) return true
        val host = Uri.parse(url).host ?: return false
        return host.equals(Uri.parse(primary).host, ignoreCase = true) ||
            host.equals(Uri.parse(fallback).host, ignoreCase = true)
    }
}
