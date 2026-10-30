package com.shinydiscoballsdev.kifossk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import android.webkit.WebSettings

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var gestureDetector: GestureDetector
    private lateinit var backToBoard: Button
    // Mikrofon-Anfrage des Boards, die auf die Android-Berechtigung wartet
    private var pendingMicRequest: PermissionRequest? = null
    private var refreshHandler: Handler? = null
    private var refreshRunnable: Runnable? = null

    // Zurück ist im Kiosk erlaubt, solange es WebView-History gibt. Der sichtbare
    // „Board“-Knopf führt dagegen immer deterministisch zur Startseite zurück.
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (this@MainActivity::webView.isInitialized && webView.canGoBack()) {
                webView.goBack()
            }
        }
    }

    // Gesture hardening fields
    private var lastSettingsOpenTime = 0L
    private val SETTINGS_COOLDOWN_MS = 10_000L  // 10 seconds
    private var currentUrl = ""  // Track URL for retry

    private val navHandler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null
    private var reprobeRunnable: Runnable? = null
    companion object {
        private const val REQUEST_MIC = 42
        private const val PAGE_LOAD_TIMEOUT_MS = 20_000L
        private const val REPROBE_DELAY_MS = 5_000L
        private const val BREAKER_OPEN_THRESHOLD = 3

        // Bounded self-healing: after the breaker opens, exactly ONE
        // re-attempt is permitted per cooldown window. Worst-case duty
        // cycle: 3 quick strikes, then 10 minutes of silence.
        private const val BREAKER_COOLDOWN_MS = 10 * 60_000L

        private const val WITNESS_JS =
            "(function(){" +
                    "var doc=document.documentElement;" +
                    "if(doc&&doc.getAttribute&&doc.getAttribute('data-kiosk')==='waiting'){return 'waiting';};" +
                    "if(!document.body){return 'empty'};" +
                    "var hasText = document.body.innerText.trim().length > 0;" +
                    "var hasVisual = document.querySelector('canvas, svg, img, iframe, video') !== null;" +
                    "return (hasText || hasVisual) ? 'ok' : 'empty'" +
                    "})()"
    }

    private var breakerResetRunnable: Runnable? = null

    // debounce — onReceivedError, empty-body probe, and watchdog can all
    // fire for one failure event; one fallback per event, no stacked loops
    private var lastWaitingPageAt = 0L

    // Issue #2: consecutive render-failure counter — breaks the
    // waiting-page <-> empty-site reload loop on engines too old for the site
    private var consecutiveRenderFailures = 0

    private fun applyKeepScreenOnFlag() {
        val keepScreenOn = KioskPrefs.getScreenOn(this)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Issue #2: diagnose WebView engine for future reports
        WebViewCensus.refresh(this)
        // Wake screen and show over lockscreen (API 27+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Issue #3: respect user's Keep Screen On preference
        applyKeepScreenOnFlag()

        // Zurück darf innerhalb der WebView-History arbeiten (Google → Board).
        // Am Anfang der History bleibt die Geste wirkungslos, damit der Kiosk nicht verlassen wird.
        onBackPressedDispatcher.addCallback(this, backCallback)

        val isFirstRun = KioskPrefs.isFirstRun(this)

        if (isFirstRun) {
            // Don't clear first_run yet - wait until URL is validated
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        } else {
            setupWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "MissingPermission")
    private fun setupWebView() {

        val url = KioskPrefs.getUrl(this)
        currentUrl = url
        val orientation = KioskPrefs.getOrientation(this)

        when (orientation) {
            "landscape" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "portrait" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "auto" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        hideSystemUI()

        webView = WebView(this)
        webView.setBackgroundColor(android.graphics.Color.parseColor("#1a1a2e"))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.setOnLongClickListener { true }

        // Mikrofon nur für die eingestellte Board-Adresse (Spracheingabe im Familienboard).
        // Fremde Seiten wie Rezepte oder Google bekommen keinen Zugriff.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    when {
                        !wantsMic || !isBoardOrigin(request.origin) -> request.deny()
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        else -> {
                            // Erst die Android-Berechtigung einholen, dann die WebView-Anfrage beantworten
                            pendingMicRequest?.deny()
                            pendingMicRequest = request
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                        }
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingMicRequest == request) pendingMicRequest = null
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                updateBackButton(url)

                if (url.startsWith("file:") || url.startsWith("about:") || url.startsWith("data:")) return

                // Re-arm: one watchdog per navigation, never stacked
                watchdogRunnable?.let { navHandler.removeCallbacks(it) }

                watchdogRunnable = Runnable {
                    android.util.Log.i("KioskLoad", "WATCHDOG: no page completion for $url after ${PAGE_LOAD_TIMEOUT_MS / 1000}s — observing, not intervening (v1.0.3 patience parity)")
                    currentUrl = KioskPrefs.getUrl(this@MainActivity)
                }.also { navHandler.postDelayed(it, PAGE_LOAD_TIMEOUT_MS) }
            }


            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                updateBackButton(url)

                // Load completed — stand down the watchdog
                watchdogRunnable?.let { navHandler.removeCallbacks(it) }
                watchdogRunnable = null

                // GUARD: Don't run probe on chrome-error:// or waiting page
                // These show text but it's not evidence of health — just exculpatory noise
                if (url.startsWith("chrome-error://") || url.startsWith("file:///android_asset/")) {
                    // Skip the witness probe entirely on these URLs
                    setupAutoRefresh()
                    return
                }

                // Guard JS injection — documentElement can be null on about:blank and chrome-error:// pages
                view.evaluateJavascript(
                    "if (document.documentElement) {" +
                            "document.documentElement.style.webkitUserSelect = 'none';" +
                            "document.documentElement.style.userSelect = 'none';" +
                            "}",
                    null
                )

                // Empty-content watchdog. two-witness probe, delayed conviction
                if (!url.startsWith("file:") && !url.startsWith("about:") && !url.startsWith("data:")) {
                    // Stand down any pending re-probe from a previous page
                    reprobeRunnable?.let { navHandler.removeCallbacks(it) }
                    reprobeRunnable = null

                    view.evaluateJavascript(WITNESS_JS) { result ->
                        if (result == "\"empty\"") {
                            // One snapshot proves nothing. Re-probe after 5s. Only
                            // persistent emptiness counts as a strike.
                            reprobeRunnable = Runnable {
                                view.evaluateJavascript(WITNESS_JS) { second ->
                                    if (second == "\"empty\"") {
                                        handleEmptyRender(url)
                                    } else if (second == "\"ok\"") {
                                        android.util.Log.i("KioskLoad",
                                            "PROBE OK — counter reset (was $consecutiveRenderFailures) url=$url")
                                        consecutiveRenderFailures = 0
                                    }
                                }
                            }.also { navHandler.postDelayed(it, REPROBE_DELAY_MS) }
                        } else if (result == "\"ok\"") {
                            android.util.Log.i("KioskLoad",
                                "PROBE OK — counter reset (was $consecutiveRenderFailures) url=$url")
                            consecutiveRenderFailures = 0
                        }
                        // result == null OR "\"waiting\"": silence, count preserved
                    }
                }
                // Auto-refresh setup (runs on every page load)
                setupAutoRefresh()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateBackButton(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                // Self-immunity: never react to errors on our own waiting page
                val urlStr = request?.url?.toString() ?: ""
                if (urlStr.startsWith("about:") || urlStr.startsWith("file:///android_asset/")) return

                // only main-frame failures trigger the waiting page;
                // subresource 404s must not hijack the kiosk
                if (request?.isForMainFrame == true) {
                    currentUrl = KioskPrefs.getUrl(this@MainActivity)
                    registerRenderStrike("onReceivedError")
                }
            }
        }
        val container = FrameLayout(this)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        backToBoard = Button(this).apply {
            text = "‹ Board"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(18), dp(10), dp(18), dp(10))
            elevation = 8f * resources.displayMetrics.density
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(230, 22, 24, 32))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            visibility = View.GONE
            setOnClickListener {
                webView.loadUrl(KioskPrefs.getUrl(this@MainActivity))
            }
        }
        container.addView(
            backToBoard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
        )
        setContentView(container)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                return true  // Required: signals GestureDetector to track this gesture sequence
            }

            override fun onLongPress(e: MotionEvent) {
                requestSettings()
            }
        })

        // Network check
        val isConnected = try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        } catch (e: Exception) {
            false
        }

        if (isConnected) {
            webView.loadUrl(url)
        } else {
            loadWaitingPage(url)
        }
    }

    // Long-Press öffnet die Einstellungen nur nach Admin-PIN. Beim ersten Mal wird
    // die PIN festgelegt; danach wird sie abgefragt.
    private fun requestSettings() {
        val now = System.currentTimeMillis()
        if (now - lastSettingsOpenTime < SETTINGS_COOLDOWN_MS) return
        lastSettingsOpenTime = now
        if (KioskPrefs.hasAdminPin(this)) {
            AdminPin.showEnterPin(this) { openSettings() }
        } else {
            AdminPin.showSetPin(this) { openSettings() }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // „Board“-Knopf nur auf externen Seiten (Google, Cloudflare-Login, …). Er lädt
    // die konfigurierte Startseite direkt und ist damit unabhängig von Redirect-History.
    private fun updateBackButton(url: String?) {
        if (!this::webView.isInitialized || !this::backToBoard.isInitialized) return
        backCallback.isEnabled = webView.canGoBack()
        val external = url != null &&
            !url.startsWith("file:///android_asset/") &&
            !url.startsWith("about:") &&
            baseUrl(url) != baseUrl(KioskPrefs.getUrl(this))
        backToBoard.visibility = if (external) View.VISIBLE else View.GONE
    }

    private fun baseUrl(raw: String?): String =
        (raw ?: "").substringBefore('#').substringBefore('?').trimEnd('/')

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    // ─── AUTO-REFRESH LOGIC ─────────────────────────────────────────────
    private fun setupAutoRefresh() {
        // FIX #2: clear ALL pending posts unconditionally, then schedule
        // exactly one — kills the double-reschedule drift
        refreshHandler?.removeCallbacksAndMessages(null)

        if (!KioskPrefs.isAutoRefreshEnabled(this)) return

        val intervalSeconds = KioskPrefs.getAutoRefreshInterval(this)
        val intervalMs = intervalSeconds * 1000L

        if (refreshHandler == null) refreshHandler = Handler(Looper.getMainLooper())

        refreshRunnable = object : Runnable {
            override fun run() {
                if (KioskPrefs.isAutoRefreshEnabled(this@MainActivity) &&
                    consecutiveRenderFailures < 3) {
                    // Read URL fresh each tick — no stale captured copies
                    currentUrl = KioskPrefs.getUrl(this@MainActivity)
                    webView.loadUrl(currentUrl)
                    refreshHandler?.postDelayed(this, intervalMs)  // simple self-reschedule
                }
            }
        }

        refreshHandler?.postDelayed(refreshRunnable!!, intervalMs)
    }

    private fun stopAutoRefresh() {
        refreshHandler?.removeCallbacksAndMessages(null)
        refreshHandler = null
        refreshRunnable = null
    }

    override fun onPause() {
        stopAutoRefresh()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Refresh loop resumes in onPageFinished() after page loads
        applyKeepScreenOnFlag()
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            gestureDetector.onTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onDestroy() {
        stopAutoRefresh()
        navHandler.removeCallbacksAndMessages(null)
        NetworkRetryHelper.stopWaiting()
        NetworkRetryHelper.stopHostRetry()
        super.onDestroy()
    }

    // Shared strike recorder, the empty-body probe, and
    // onReceivedError all testify through this one door.
    private fun registerRenderStrike(source: String) {

        // ─── v1.1.4 ROUTING GUARD ─────────────────────────────────────
        // Legacy engines (Chromium <= 71) and unsupported API levels (< 24):
        // pre-1.1.0 behavior — no strikes, no breaker, no waiting page.
        // The loop cannot form because nothing increments. Chromium's own
        // error handling owns the display; NetworkRetryHelper still owns
        // recovery via the loadWaitingPage legacy branch.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || WebViewCensus.isLegacy) {
            android.util.Log.i("KioskLoad",
                "render strike ignored (api=${Build.VERSION.SDK_INT} " +
                        "engine=${WebViewCensus.versionName ?: "unknown"} legacy) ($source)")
            return
        }
        // ───────────────────────────────────────────────────────────────

        consecutiveRenderFailures++
        android.util.Log.i("KioskLoad", "render strike #$consecutiveRenderFailures ($source)")

        if (consecutiveRenderFailures >= BREAKER_OPEN_THRESHOLD) {
            android.util.Log.w("KioskLoad",
                "circuit breaker open after $consecutiveRenderFailures empty renders ($source) — " +
                        "holding waiting page, re-attempt in ${BREAKER_COOLDOWN_MS / 60_000L}min")
            NetworkRetryHelper.stopHostRetry()
            lastWaitingPageAt = 0L   // deliberate bypass — one waiting page per event

            // Breaker terminal: no recovery watcher; the cooldown is the only re-attempt
            loadWaitingPage(currentUrl, failedOverNetwork = false, recoveryArmed = false,
                terminal = true)

            scheduleBreakerCooldownReset()
        } else {
            loadWaitingPage(currentUrl, failedOverNetwork = true)
        }
    }

    private fun scheduleBreakerCooldownReset() {
        breakerResetRunnable?.let { navHandler.removeCallbacks(it) }
        breakerResetRunnable = Runnable {
            if (consecutiveRenderFailures >= BREAKER_OPEN_THRESHOLD) {
                android.util.Log.i("KioskLoad",
                    "breaker cooldown elapsed — resetting counter (was $consecutiveRenderFailures), one re-attempt")
                consecutiveRenderFailures = 0
                currentUrl = KioskPrefs.getUrl(this)
                webView.loadUrl(currentUrl)
            }
        }.also { navHandler.postDelayed(it, BREAKER_COOLDOWN_MS) }
    }

    private fun handleEmptyRender(url: String) {
        android.util.Log.i("KioskLoad",
            "onPageFinished: empty body x${consecutiveRenderFailures + 1} for $url")
        registerRenderStrike("empty-body")
    }

    @SuppressLint("SetTextI18n")
    private fun loadWaitingPage(
        targetUrl: String,
        failedOverNetwork: Boolean = false,
        recoveryArmed: Boolean = true,
        terminal: Boolean = false
    ) {
        // debounce — onReceivedError, empty-body probe, and the watchdog
        // can all fire for one failure event; one fallback per event
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWaitingPageAt < 3_000L) return
        lastWaitingPageAt = now

        // ─── v1.1.4 LEGACY / UNSUPPORTED ROUTE (pre-1.1.0 behavior) ──
        // Old engines and API < 24: never show the waiting page. Load the
        // target URL and let Chromium render its own (localized, working)
        // error page. Recovery stays fully armed and decoupled.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || WebViewCensus.isLegacy) {
            android.util.Log.i("KioskLoad",
                "legacy route — Chromium native error handling (engine=" +
                        "${WebViewCensus.versionName ?: "unknown"})")
            if (recoveryArmed) {
                if (failedOverNetwork) {
                    NetworkRetryHelper.startHostRetry(targetUrl) { url: String -> webView.loadUrl(url) }
                } else {
                    NetworkRetryHelper.startWaitingForNetwork(this, targetUrl) { url: String -> webView.loadUrl(url) }
                }
            }
            webView.loadUrl(targetUrl)
            return
        }
        // ───────────────────────────────────────────────────────────────

        webView.setBackgroundColor(android.graphics.Color.parseColor("#1a1a2e"))

        val encodedUrl = android.net.Uri.encode(targetUrl)
        val engineMajor = WebViewCensus.major ?: -1
        webView.loadUrl(
            "file:///android_asset/waiting.html?url=$encodedUrl" +
                    "&engine=$engineMajor" +
                    (if (terminal) "&state=terminal" else "")
        )

        if (!recoveryArmed) return

        if (failedOverNetwork) {
            NetworkRetryHelper.startHostRetry(targetUrl) { url: String -> webView.loadUrl(url) }
        } else {
            NetworkRetryHelper.startWaitingForNetwork(this, targetUrl) { url: String -> webView.loadUrl(url) }
        }
    }

    /** Nur die eingestellte Board-Adresse darf das Mikrofon anfordern (Schema, Host und Port). */
    private fun isBoardOrigin(origin: Uri): Boolean {
        val board = Uri.parse(KioskPrefs.getUrl(this))
        val host = origin.host ?: return false
        val boardHost = board.host ?: return false
        fun port(uri: Uri): Int = when {
            uri.port != -1 -> uri.port
            uri.scheme == "https" -> 443
            uri.scheme == "http" -> 80
            else -> -1
        }
        return origin.scheme == board.scheme &&
            host.equals(boardHost, ignoreCase = true) &&
            port(origin) == port(board)
    }

    @Deprecated("Activity Result API wäre moderner, reicht hier aber")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        val request = pendingMicRequest ?: return
        pendingMicRequest = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request.deny()
        }
    }
}
