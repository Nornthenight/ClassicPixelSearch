package com.pixel.classicsearch

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var hasAutoRequestedShizuku = false
    @Volatile private var isGrantingViaShizuku = false
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var txtShizukuStatus: TextView
    private lateinit var txtWriteSecureStatus: TextView
    private lateinit var txtA11yStatus: TextView
    private lateinit var btnRequestShizuku: Button
    private lateinit var txtDockStatus: TextView
    private lateinit var switchReplaceDock: MaterialSwitch

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuHelper.SHIZUKU_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku permission granted", Toast.LENGTH_SHORT).show()
                grantAndEnableAsync(showSuccessToast = true)
            } else {
                Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_SHORT).show()
                updateStatusUi()
            }
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateStatusUi() }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateStatusUi() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        txtShizukuStatus = findViewById(R.id.txt_shizuku_status)
        txtWriteSecureStatus = findViewById(R.id.txt_write_secure_status)
        txtA11yStatus = findViewById(R.id.txt_a11y_status)
        btnRequestShizuku = findViewById(R.id.btn_request_shizuku)
        switchReplaceDock = findViewById(R.id.switch_replace_dock)
        txtDockStatus = findViewById(R.id.txt_dock_status)

        val rgFrameStyle = findViewById<RadioGroup>(R.id.rg_frame_style)
        val rgIconStyle = findViewById<RadioGroup>(R.id.rg_icon_style)
        val seekOpacity = findViewById<SeekBar>(R.id.seek_opacity)
        val txtOpacityValue = findViewById<TextView>(R.id.txt_opacity_value)
        val btnPinWidget = findViewById<Button>(R.id.btn_pin_widget)
        val btnSave = findViewById<Button>(R.id.btn_save_config)

        // Live preview views
        val previewOuter = findViewById<View>(R.id.search_outer_container)
        val previewLogo = findViewById<ImageView>(R.id.img_google_logo)
        val previewMic = findViewById<ImageView>(R.id.btn_voice_search)
        val previewGemini = findViewById<ImageView>(R.id.btn_lens_search)
        val previewAi = findViewById<ImageView>(R.id.btn_ai_mode)

        // Register Shizuku listeners
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Throwable) {
            // Shizuku provider not yet initialized
        }

        // Configure btn_request_shizuku click action
        btnRequestShizuku.setOnClickListener {
            handleShizukuAction()
        }

        // Load saved preferences
        when (WidgetPrefs.getSurfaceStyle(this)) {
            WidgetPrefs.SURFACE_DEEP_RING -> rgFrameStyle.check(R.id.rb_frame_deep)
            WidgetPrefs.SURFACE_PASTEL -> rgFrameStyle.check(R.id.rb_frame_pastel)
            WidgetPrefs.SURFACE_RICH_ACCENT -> rgFrameStyle.check(R.id.rb_frame_rich)
        }

        when (WidgetPrefs.getIconStyle(this)) {
            WidgetPrefs.ICON_ADAPTIVE_EMERALD -> rgIconStyle.check(R.id.rb_icon_ref)
            WidgetPrefs.ICON_DYNAMIC_MONET -> rgIconStyle.check(R.id.rb_icon_mono)
            WidgetPrefs.ICON_CLASSIC_COLOR -> rgIconStyle.check(R.id.rb_icon_color)
        }

        val initialOpacity = WidgetPrefs.getOpacityPercent(this)
        seekOpacity.progress = initialOpacity
        txtOpacityValue.text = "$initialOpacity%"

        fun getSelectedSurfaceStyle(): Int = when (rgFrameStyle.checkedRadioButtonId) {
            R.id.rb_frame_pastel -> WidgetPrefs.SURFACE_PASTEL
            R.id.rb_frame_rich -> WidgetPrefs.SURFACE_RICH_ACCENT
            else -> WidgetPrefs.SURFACE_DEEP_RING
        }

        fun getSelectedIconStyle(): Int = when (rgIconStyle.checkedRadioButtonId) {
            R.id.rb_icon_mono -> WidgetPrefs.ICON_DYNAMIC_MONET
            R.id.rb_icon_color -> WidgetPrefs.ICON_CLASSIC_COLOR
            else -> WidgetPrefs.ICON_ADAPTIVE_EMERALD
        }

        fun refreshStylePreview() {
            when (getSelectedSurfaceStyle()) {
                WidgetPrefs.SURFACE_DEEP_RING -> previewOuter.setBackgroundResource(R.drawable.bg_outer_pill)
                WidgetPrefs.SURFACE_PASTEL -> previewOuter.setBackgroundResource(R.drawable.bg_outer_pill_pastel)
                WidgetPrefs.SURFACE_RICH_ACCENT -> previewOuter.setBackgroundResource(R.drawable.bg_outer_pill_rich)
            }

            when (getSelectedIconStyle()) {
                WidgetPrefs.ICON_ADAPTIVE_EMERALD -> {
                    previewLogo.setImageResource(R.drawable.ic_google_logo_ref)
                    previewMic.setImageResource(R.drawable.ic_mic_ref)
                    previewGemini.setImageResource(R.drawable.ic_gemini_ref)
                    previewAi.setImageResource(R.drawable.ic_ai_mode_ref)
                }
                WidgetPrefs.ICON_DYNAMIC_MONET -> {
                    previewLogo.setImageResource(R.drawable.ic_google_logo_mono)
                    previewMic.setImageResource(R.drawable.ic_mic_mono)
                    previewGemini.setImageResource(R.drawable.ic_gemini_mono)
                    previewAi.setImageResource(R.drawable.ic_ai_mode_mono)
                }
                WidgetPrefs.ICON_CLASSIC_COLOR -> {
                    previewLogo.setImageResource(R.drawable.ic_google_logo_color)
                    previewMic.setImageResource(R.drawable.ic_mic_color)
                    previewGemini.setImageResource(R.drawable.ic_gemini_color)
                    previewAi.setImageResource(R.drawable.ic_ai_mode_color)
                }
            }
        }

        fun refreshOpacityPreview(progress: Int = seekOpacity.progress) {
            txtOpacityValue.text = "$progress%"
            previewOuter.alpha = progress / 100f
        }

        refreshStylePreview()
        refreshOpacityPreview(initialOpacity)

        rgFrameStyle.setOnCheckedChangeListener { _, _ -> refreshStylePreview() }
        rgIconStyle.setOnCheckedChangeListener { _, _ -> refreshStylePreview() }
        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshOpacityPreview(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnPinWidget.setOnClickListener {
            val appWidgetManager = AppWidgetManager.getInstance(this)
            val provider = ComponentName(this, ClassicSearchWidgetProvider::class.java)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                appWidgetManager.requestPinAppWidget(provider, null, null)
            } else {
                Toast.makeText(
                    this,
                    "Long-press on home screen -> Widgets -> Pixel Search",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        switchReplaceDock.isChecked = WidgetPrefs.isReplaceDockEnabled(this)

        btnSave.setOnClickListener {
            val replaceDock = switchReplaceDock.isChecked
            WidgetPrefs.setSurfaceStyle(this, getSelectedSurfaceStyle())
            WidgetPrefs.setIconStyle(this, getSelectedIconStyle())
            WidgetPrefs.setOpacityPercent(this, seekOpacity.progress)
            WidgetPrefs.setReplaceDockEnabled(this, replaceDock)

            ClassicSearchWidgetProvider.updateAllWidgets(this)

            val appCtx = applicationContext
            val targetValue = if (replaceDock) packageName else null
            ioExecutor.execute {
                try {
                    Settings.Secure.putString(
                        appCtx.contentResolver,
                        ServiceHelper.KEY_SELECTED_SEARCH_ENGINE,
                        targetValue
                    )
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(
                                this,
                                if (replaceDock) {
                                    "Search dock updated!"
                                } else {
                                    "Restored stock search dock"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: SecurityException) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(
                                this,
                                "Missing WRITE_SECURE_SETTINGS permission",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }

            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                setResult(Activity.RESULT_OK, resultValue)
                finish()
            }
        }

        updateStatusUi()
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Throwable) {
            // Ignore
        }
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun grantAndEnableAsync(showSuccessToast: Boolean) {
        if (isGrantingViaShizuku) return
        isGrantingViaShizuku = true
        val appCtx = applicationContext
        ioExecutor.execute {
            val granted = ShizukuHelper.grantWriteSecureSettings(appCtx)
            if (granted) {
                ServiceHelper.ensureAccessibilityServiceEnabled(appCtx)
                ShizukuHelper.enableAccessibilityService(appCtx)
            }
            runOnUiThread {
                isGrantingViaShizuku = false
                if (!isFinishing && !isDestroyed) {
                    if (showSuccessToast) {
                        if (granted) {
                            Toast.makeText(this, "Permissions granted successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Failed to grant permission via Shizuku", Toast.LENGTH_LONG).show()
                        }
                    }
                    updateStatusUi()
                }
            }
        }
    }

    private fun updateStatusUi() {
        val isInstalled = ShizukuHelper.isInstalled(this)
        val isRunning = ShizukuHelper.isRunning()
        val isShizukuGranted = ShizukuHelper.isPermissionGranted()
        val hasWriteSecure = ShizukuHelper.hasWriteSecureSettings(this)
        val isA11yActive = ShizukuHelper.isAccessibilityServiceEnabled(this)

        // Auto-grant WRITE_SECURE_SETTINGS asynchronously if Shizuku is running and permission is granted
        if (!hasWriteSecure && isRunning && isShizukuGranted) {
            grantAndEnableAsync(showSuccessToast = false)
        }

        // Auto-request Shizuku permission once on launch if Shizuku is running
        if (isRunning && !isShizukuGranted && !hasAutoRequestedShizuku) {
            hasAutoRequestedShizuku = true
            ShizukuHelper.requestPermission(ShizukuHelper.SHIZUKU_REQUEST_CODE)
        }

        // 1. Shizuku Status
        when {
            !isInstalled -> {
                txtShizukuStatus.text = "Not Installed"
                txtShizukuStatus.setTextColor(Color.parseColor("#757575"))
            }
            !isRunning -> {
                txtShizukuStatus.text = "Not Running"
                txtShizukuStatus.setTextColor(Color.parseColor("#BA1A1A"))
            }
            isShizukuGranted -> {
                txtShizukuStatus.text = "Running (Permission Granted)"
                txtShizukuStatus.setTextColor(Color.parseColor("#2E7D32"))
            }
            else -> {
                txtShizukuStatus.text = "Running (Permission Required)"
                txtShizukuStatus.setTextColor(Color.parseColor("#E65100"))
            }
        }

        // 2. WRITE_SECURE_SETTINGS Status
        if (hasWriteSecure) {
            txtWriteSecureStatus.text = "Granted (Active)"
            txtWriteSecureStatus.setTextColor(Color.parseColor("#2E7D32"))
            ServiceHelper.ensureAccessibilityServiceEnabled(this)
        } else {
            txtWriteSecureStatus.text = "Not Granted"
            txtWriteSecureStatus.setTextColor(Color.parseColor("#E65100"))
        }

        // 3. All Apps Search Service (Accessibility)
        if (isA11yActive) {
            txtA11yStatus.text = "Active"
            txtA11yStatus.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            txtA11yStatus.text = "Inactive"
            txtA11yStatus.setTextColor(Color.parseColor("#757575"))
        }

        // 4. Dock Switch Status (respect saved user preference)
        switchReplaceDock.isChecked = WidgetPrefs.isReplaceDockEnabled(this)
        txtDockStatus.text = if (hasWriteSecure) {
            "WRITE_SECURE_SETTINGS active — Dynamic Search Sync & Classic Dock Ready"
        } else {
            "Grant WRITE_SECURE_SETTINGS via Shizuku or ADB to replace bottom dock"
        }

        // 5. Button Text and Action State
        when {
            hasWriteSecure && isA11yActive -> {
                btnRequestShizuku.text = "All Permissions Active"
                btnRequestShizuku.isEnabled = false
            }
            isRunning && isShizukuGranted && !hasWriteSecure -> {
                btnRequestShizuku.text = "Grant System Permission via Shizuku"
                btnRequestShizuku.isEnabled = true
            }
            isRunning && !isShizukuGranted -> {
                btnRequestShizuku.text = "Request Shizuku Permission"
                btnRequestShizuku.isEnabled = true
            }
            !isRunning && isInstalled -> {
                btnRequestShizuku.text = "Open Shizuku App"
                btnRequestShizuku.isEnabled = true
            }
            !isInstalled -> {
                btnRequestShizuku.text = "Install Shizuku"
                btnRequestShizuku.isEnabled = true
            }
            else -> {
                btnRequestShizuku.text = "Configure via Shizuku"
                btnRequestShizuku.isEnabled = true
            }
        }
    }

    private fun handleShizukuAction() {
        val isInstalled = ShizukuHelper.isInstalled(this)
        val isRunning = ShizukuHelper.isRunning()
        val isShizukuGranted = ShizukuHelper.isPermissionGranted()
        val hasWriteSecure = ShizukuHelper.hasWriteSecureSettings(this)

        when {
            !isInstalled -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Install Shizuku from Play Store or GitHub", Toast.LENGTH_LONG).show()
                }
            }
            !isRunning -> {
                val intent = packageManager.getLaunchIntentForPackage(ShizukuHelper.SHIZUKU_PACKAGE)
                if (intent != null) {
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(this, "Start Shizuku service and return here", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Start Shizuku service and return here", Toast.LENGTH_LONG).show()
                }
            }
            !isShizukuGranted -> {
                ShizukuHelper.requestPermission(ShizukuHelper.SHIZUKU_REQUEST_CODE)
            }
            !hasWriteSecure -> {
                grantAndEnableAsync(showSuccessToast = true)
            }
            else -> {
                grantAndEnableAsync(showSuccessToast = true)
            }
        }
    }
}
