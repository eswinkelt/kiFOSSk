package com.shinydiscoballsdev.kifossk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.widget.SwitchCompat
import android.app.role.RoleManager
import android.os.Build
import androidx.activity.OnBackPressedCallback

class SettingsActivity : AppCompatActivity() {

    private lateinit var editTextUrl: EditText
    private lateinit var switchScreenOn: SwitchCompat
    private lateinit var spinnerOrientation: Spinner
    private lateinit var switchAutoRefresh: SwitchCompat
    private lateinit var spinnerRefreshInterval: Spinner
    private lateinit var textRefreshIntervalLabel: TextView
    private lateinit var btnSave: Button
    private lateinit var btnSetLauncher: Button
    private lateinit var textLauncherStatus: TextView
    private lateinit var textWebViewVersion: TextView
    private lateinit var textAppVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lockscreen (API 27+) — lock-screen wake only.
        // NOTE: FLAG_KEEP_SCREEN_ON deliberately NOT added here (Issue #3):
        // it ignored the screen_on preference. Timeout is governed by
        // applyKeepScreenOnFlag(), which respects the switch below.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        setContentView(R.layout.activity_settings)

        // Initialize views
        editTextUrl = findViewById(R.id.editTextUrl)
        editTextUrl.post {
            editTextUrl.clearFocus()
        }
        switchScreenOn = findViewById(R.id.switchScreenOn)
        spinnerOrientation = findViewById(R.id.spinnerOrientation)
        switchAutoRefresh = findViewById(R.id.switchAutoRefresh)
        spinnerRefreshInterval = findViewById(R.id.spinnerRefreshInterval)
        textRefreshIntervalLabel = findViewById(R.id.textRefreshIntervalLabel)
        btnSave = findViewById(R.id.buttonSave)
        btnSetLauncher = findViewById(R.id.buttonSetLauncher)
        textLauncherStatus = findViewById(R.id.textLauncherStatus)
        textWebViewVersion = findViewById(R.id.textWebViewVersion)
        textAppVersion = findViewById(R.id.textAppVersion)

        // Load existing preferences
        editTextUrl.setText(KioskPrefs.getUrl(this))
        switchScreenOn.isChecked = KioskPrefs.getScreenOn(this)

        // Issue #3: apply initial flag state, and follow the switch live
        applyKeepScreenOnFlag()
        switchScreenOn.setOnCheckedChangeListener { _, _ ->
            applyKeepScreenOnFlag()
        }

        // Orientation dropdown
        ArrayAdapter.createFromResource(
            this,
            R.array.orientation_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerOrientation.adapter = adapter

            // Sync spinner to saved orientation
            val savedOrientation = KioskPrefs.getOrientation(this)
            val orientationMap = mapOf("landscape" to 0, "portrait" to 1, "auto" to 2)
            spinnerOrientation.setSelection(orientationMap[savedOrientation] ?: 0)
        }

        // Auto-refresh toggle and interval
        switchAutoRefresh.isChecked = KioskPrefs.isAutoRefreshEnabled(this)
        val currentInterval = KioskPrefs.getAutoRefreshInterval(this)
        val intervalValues = intArrayOf(10, 30, 60, 300, 900)
        val intervalIndex = intervalValues.indexOf(currentInterval)
        spinnerRefreshInterval.setSelection(if (intervalIndex >= 0) intervalIndex else 1)
        updateRefreshIntervalSpinnerState()

        switchAutoRefresh.setOnCheckedChangeListener { _, _ ->
            updateRefreshIntervalSpinnerState()
        }

        // Check launcher status on load
        updateLauncherStatus()

        populateVersionInfo()

        // Set as Launcher button handler
        btnSetLauncher.setOnClickListener {
            setAsLauncher()
        }

        // Save button handler
        btnSave.setOnClickListener {
            saveAndReturn()
        }

        // Handle back press — save settings and return to kiosk
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAndReturn()
            }
        })
    }

    // Issue #3: FLAG_KEEP_SCREEN_ON driven by the switch, not forced on
    private fun applyKeepScreenOnFlag() {
        if (switchScreenOn.isChecked) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateRefreshIntervalSpinnerState() {
        val isEnabled = switchAutoRefresh.isChecked
        spinnerRefreshInterval.isEnabled = isEnabled
        textRefreshIntervalLabel.isEnabled = isEnabled
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOnFlag()
        // Refresh launcher status when returning from system settings
        updateLauncherStatus()
    }

    /**
     * Open system default home settings page
     */
    private fun setAsLauncher() {
        // Save current settings before leaving (defensive)
        KioskPrefs.setUrl(this, editTextUrl.text.toString())
        KioskPrefs.setScreenOn(this, switchScreenOn.isChecked)
        val intervalValues = intArrayOf(10, 30, 60, 300, 900)
        KioskPrefs.setAutoRefresh(this, switchAutoRefresh.isChecked, intervalValues[spinnerRefreshInterval.selectedItemPosition])
        requestBatteryOptimizationExemption()

        Toast.makeText(this, "Settings saved! Opening launcher selection...", Toast.LENGTH_SHORT).show()

        val intent = Intent(Settings.ACTION_HOME_SETTINGS)
        startActivity(intent)
    }

    /**
     * Check if kiFOSSk is the default home launcher and update UI
     */
    private fun isDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            return roleManager?.isRoleHeld(RoleManager.ROLE_HOME) ?: false
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resInfo = packageManager.resolveActivity(homeIntent, 0)
        return resInfo?.activityInfo?.packageName == packageName
    }

    private fun updateLauncherStatus() {
        val isDefault = isDefaultLauncher()

        if (isDefault) {
            textLauncherStatus.text = "✅ Launcher: Active (Boots on startup)"
            textLauncherStatus.setTextColor(getColor(android.R.color.holo_green_light))
            btnSetLauncher.text = "Switch to Different Launcher"
        } else {
            textLauncherStatus.text = "⚠️ Launcher: Not Set (Won't boot to foreground)"
            textLauncherStatus.setTextColor(getColor(android.R.color.holo_red_light))
            btnSetLauncher.text = "Set as Home Launcher"
        }
    }

    /**
     * v1.1.4: read-only version footer. One glance at a wall-mounted
     * device should answer "which engine is this running" and the
     * legacy annotation explains why extended error handling is absent
     * on that device.
     */
    private fun populateVersionInfo() {
        WebViewCensus.refresh(this)

        // Installed version — PackageManager, not BuildConfig (AGP 8.x
        // disables BuildConfig generation by default; this avoids the
        // opt-in and reports the true installed versionName)
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        textAppVersion.text = if (appVersion != null) {
            "kiFOSSk v$appVersion"
        } else {
            "kiFOSSk"
        }

        val engine = WebViewCensus.versionName
        textWebViewVersion.text = if (engine != null) {
            "WebView: $engine"
        } else {
            "WebView version unavailable"
        }

        if (WebViewCensus.isLegacy) {
            textWebViewVersion.append(" (legacy engine — extended error handling disabled)")
        }
    }

    /**
     * Request battery optimization exemption for background execution.
     * Shows system dialog: "Allow kiFOSSk to always run in background?"
     */
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        // Save preferences when activity loses focus (defensive backup)
        KioskPrefs.setUrl(this, editTextUrl.text.toString())
        KioskPrefs.setScreenOn(this, switchScreenOn.isChecked)
        val orientationMap = mapOf(0 to "landscape", 1 to "portrait", 2 to "auto")
        KioskPrefs.setOrientation(this, orientationMap[spinnerOrientation.selectedItemPosition]!!)
        val intervalValues = intArrayOf(10, 30, 60, 300, 900)
        KioskPrefs.setAutoRefresh(this, switchAutoRefresh.isChecked, intervalValues[spinnerRefreshInterval.selectedItemPosition])
    }

    private fun saveAndReturn() {
        KioskPrefs.setUrl(this, editTextUrl.text.toString())
        KioskPrefs.setScreenOn(this, switchScreenOn.isChecked)

        val orientationMap = mapOf(0 to "landscape", 1 to "portrait", 2 to "auto")
        KioskPrefs.setOrientation(this, orientationMap[spinnerOrientation.selectedItemPosition]!!)

        val intervalValues = intArrayOf(10, 30, 60, 300, 900)
        KioskPrefs.setAutoRefresh(this, switchAutoRefresh.isChecked, intervalValues[spinnerRefreshInterval.selectedItemPosition])

        // FIX Bug #4: Clear first_run only after URL is validated and saved
        KioskPrefs.setFirstRun(this, false)

        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()

        // Return to MainActivity
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}