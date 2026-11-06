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
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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
        val seekOuterAlpha = findViewById<SeekBar>(R.id.seek_outer_alpha)
        val txtOuterAlphaValue = findViewById<TextView>(R.id.txt_outer_alpha_value)
        val seekRingGap = findViewById<SeekBar>(R.id.seek_ring_gap)
        val txtRingGapValue = findViewById<TextView>(R.id.txt_ring_gap_value)
        val seekCornerRadius = findViewById<SeekBar>(R.id.seek_corner_radius)
        val txtCornerRadiusValue = findViewById<TextView>(R.id.txt_corner_radius_value)
        val switchHaptic = findViewById<MaterialSwitch>(R.id.switch_haptic)
        val rgHapticStyle = findViewById<RadioGroup>(R.id.rg_haptic_style)
        val rowMicAction = findViewById<View>(R.id.row_mic_action)
        val txtMicActionDesc = findViewById<TextView>(R.id.txt_mic_action_desc)
        val rowMiddleAction = findViewById<View>(R.id.row_middle_action)
        val txtMiddleActionDesc = findViewById<TextView>(R.id.txt_middle_action_desc)
        val rowAiAction = findViewById<View>(R.id.row_ai_action)
        val txtAiActionDesc = findViewById<TextView>(R.id.txt_ai_action_desc)
        val btnPinWidget = findViewById<Button>(R.id.btn_pin_widget)
        val btnSave = findViewById<Button>(R.id.btn_save_config)

        // Live preview views
        val bgOuterLayer = findViewById<View>(R.id.bg_outer_layer)
        val bgInnerPillLayer = findViewById<View>(R.id.bg_inner_pill_layer)
        val bgAiCircleLayer = findViewById<ImageView>(R.id.bg_ai_circle_layer)
        val previewOuter = findViewById<View>(R.id.search_outer_container)
        val previewPillContainer = findViewById<View>(R.id.search_pill_container)
        val previewTouchArea = findViewById<View>(R.id.search_touch_area)
        val previewRingSpacer = findViewById<View>(R.id.ring_gap_spacer)
        val previewAiWrapper = findViewById<View>(R.id.ai_button_wrapper)
        val previewLogo = findViewById<ImageView>(R.id.img_google_logo)
        val previewMic = findViewById<ImageView>(R.id.btn_voice_search)
        val previewGemini = findViewById<ImageView>(R.id.btn_lens_search)
        val previewAi = findViewById<ImageView>(R.id.btn_ai_mode)

        var selectedMicAction = WidgetPrefs.getMicAction(this)
        var selectedMiddleAction = WidgetPrefs.getMiddleAction(this)
        var selectedAiAction = WidgetPrefs.getAiAction(this)

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
            WidgetPrefs.SURFACE_AMOLED -> rgFrameStyle.check(R.id.rb_frame_amoled)
        }

        when (WidgetPrefs.getIconStyle(this)) {
            WidgetPrefs.ICON_ADAPTIVE_EMERALD -> rgIconStyle.check(R.id.rb_icon_ref)
            WidgetPrefs.ICON_DYNAMIC_MONET -> rgIconStyle.check(R.id.rb_icon_mono)
            WidgetPrefs.ICON_CLASSIC_COLOR -> rgIconStyle.check(R.id.rb_icon_color)
        }

        val initialOpacity = WidgetPrefs.getOpacityPercent(this)
        seekOpacity.progress = initialOpacity
        txtOpacityValue.text = "$initialOpacity%"

        val initialOuterAlpha = WidgetPrefs.getOuterAlphaPercent(this)
        seekOuterAlpha.progress = initialOuterAlpha
        txtOuterAlphaValue.text = "$initialOuterAlpha%"

        val initialRingGap = WidgetPrefs.getRingGapDp(this)
        seekRingGap.progress = initialRingGap
        txtRingGapValue.text = "$initialRingGap dp"

        val initialCornerRadius = WidgetPrefs.getCornerRadiusDp(this)
        seekCornerRadius.progress = initialCornerRadius
        txtCornerRadiusValue.text = "$initialCornerRadius dp"

        switchHaptic.isChecked = WidgetPrefs.isHapticEnabled(this)
        when (WidgetPrefs.getHapticStyle(this)) {
            WidgetPrefs.HAPTIC_CONTEXT_CLICK -> rgHapticStyle.check(R.id.rb_haptic_click)
            else -> rgHapticStyle.check(R.id.rb_haptic_tick)
        }

        fun getSelectedSurfaceStyle(): Int = when (rgFrameStyle.checkedRadioButtonId) {
            R.id.rb_frame_pastel -> WidgetPrefs.SURFACE_PASTEL
            R.id.rb_frame_rich -> WidgetPrefs.SURFACE_RICH_ACCENT
            R.id.rb_frame_amoled -> WidgetPrefs.SURFACE_AMOLED
            else -> WidgetPrefs.SURFACE_DEEP_RING
        }

        fun getSelectedIconStyle(): Int = when (rgIconStyle.checkedRadioButtonId) {
            R.id.rb_icon_mono -> WidgetPrefs.ICON_DYNAMIC_MONET
            R.id.rb_icon_color -> WidgetPrefs.ICON_CLASSIC_COLOR
            else -> WidgetPrefs.ICON_ADAPTIVE_EMERALD
        }

        fun getSelectedHapticStyle(): Int = when (rgHapticStyle.checkedRadioButtonId) {
            R.id.rb_haptic_click -> WidgetPrefs.HAPTIC_CONTEXT_CLICK
            else -> WidgetPrefs.HAPTIC_CLOCK_TICK
        }

        fun performPreviewHaptic(view: View) {
            if (!switchHaptic.isChecked) return
            val constant = if (getSelectedHapticStyle() == WidgetPrefs.HAPTIC_CONTEXT_CLICK) {
                HapticFeedbackConstants.CONTEXT_CLICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        }

        fun refreshActionLabels() {
            txtMicActionDesc.text = formatActionLabel(selectedMicAction)
            txtMiddleActionDesc.text = formatActionLabel(selectedMiddleAction)
            txtAiActionDesc.text = formatActionLabel(selectedAiAction)
        }

        fun refreshStylePreview() {
            val density = resources.displayMetrics.density
            val surfaceStyle = getSelectedSurfaceStyle()
            val gapDp = seekRingGap.progress
            val gapPx = (gapDp * density).roundToInt()
            val radiusDp = seekCornerRadius.progress
            val outerRadiusPx = radiusDp * density
            val innerHeightDp = (62 - 2 * gapDp).coerceAtLeast(24)
            val innerHeightPx = (innerHeightDp * density).roundToInt()
            val innerRadiusPx = (radiusDp - gapDp).coerceIn(4, innerHeightDp / 2) * density

            bgOuterLayer.background = WidgetDrawableRenderer.createOuterDrawable(this, outerRadiusPx, surfaceStyle)
            bgInnerPillLayer.background = WidgetDrawableRenderer.createInnerDrawable(this, innerRadiusPx, surfaceStyle)
            bgAiCircleLayer.setImageDrawable(
                WidgetDrawableRenderer.createInnerDrawable(this, innerRadiusPx, surfaceStyle)
            )

            previewOuter.setPadding(gapPx, gapPx, gapPx, gapPx)
            previewRingSpacer.layoutParams = previewRingSpacer.layoutParams.apply { width = gapPx }
            previewAiWrapper.layoutParams = previewAiWrapper.layoutParams.apply { width = innerHeightPx }

            val iconStyle = getSelectedIconStyle()
            val logoRes = when (iconStyle) {
                WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_google_logo_ref
                WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_google_logo_mono
                WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_google_logo_color
                else -> R.drawable.ic_google_logo_ref
            }
            previewLogo.setImageResource(logoRes)

            val micIcon = WidgetDrawableRenderer.resolveActionIcon(
                this, selectedMicAction, iconStyle, WidgetPrefs.ACTION_VOICE_SEARCH
            )
            WidgetDrawableRenderer.applyActionIconToView(previewMic, micIcon)

            val middleIcon = WidgetDrawableRenderer.resolveActionIcon(
                this, selectedMiddleAction, iconStyle, WidgetPrefs.ACTION_GEMINI
            )
            WidgetDrawableRenderer.applyActionIconToView(previewGemini, middleIcon)

            val aiIcon = WidgetDrawableRenderer.resolveActionIcon(
                this, selectedAiAction, iconStyle, WidgetPrefs.ACTION_AI_MODE
            )
            WidgetDrawableRenderer.applyActionIconToView(previewAi, aiIcon)
        }

        fun refreshOpacityPreview(
            opacity: Int = seekOpacity.progress,
            outerAlpha: Int = seekOuterAlpha.progress
        ) {
            txtOpacityValue.text = "$opacity%"
            txtOuterAlphaValue.text = "$outerAlpha%"
            val mainAlpha = opacity / 100f
            previewOuter.alpha = mainAlpha
            bgOuterLayer.alpha = mainAlpha * (outerAlpha / 100f)
        }

        refreshActionLabels()
        refreshStylePreview()
        refreshOpacityPreview(initialOpacity, initialOuterAlpha)

        rgFrameStyle.setOnCheckedChangeListener { v, _ ->
            performPreviewHaptic(v)
            refreshStylePreview()
        }
        rgIconStyle.setOnCheckedChangeListener { v, _ ->
            performPreviewHaptic(v)
            refreshStylePreview()
        }
        rgHapticStyle.setOnCheckedChangeListener { v, _ ->
            performPreviewHaptic(v)
        }

        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshOpacityPreview(progress, seekOuterAlpha.progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekOuterAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshOpacityPreview(seekOpacity.progress, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekRingGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtRingGapValue.text = "$progress dp"
                refreshStylePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekCornerRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtCornerRadiusValue.text = "$progress dp"
                refreshStylePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        rowMicAction.setOnClickListener { v ->
            performPreviewHaptic(v)
            showActionPickerDialog(selectedMicAction) { chosen ->
                selectedMicAction = chosen
                refreshActionLabels()
                refreshStylePreview()
            }
        }
        rowMiddleAction.setOnClickListener { v ->
            performPreviewHaptic(v)
            showActionPickerDialog(selectedMiddleAction) { chosen ->
                selectedMiddleAction = chosen
                refreshActionLabels()
                refreshStylePreview()
            }
        }
        rowAiAction.setOnClickListener { v ->
            performPreviewHaptic(v)
            showActionPickerDialog(selectedAiAction) { chosen ->
                selectedAiAction = chosen
                refreshActionLabels()
                refreshStylePreview()
            }
        }

        // Interactive live preview clicks
        previewLogo.setOnClickListener { v -> performPreviewHaptic(v) }
        previewPillContainer.setOnClickListener { v -> performPreviewHaptic(v) }
        previewTouchArea.setOnClickListener { v -> performPreviewHaptic(v) }
        previewMic.setOnClickListener { v -> performPreviewHaptic(v) }
        previewGemini.setOnClickListener { v -> performPreviewHaptic(v) }
        previewAi.setOnClickListener { v -> performPreviewHaptic(v) }

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

        btnSave.setOnClickListener { v ->
            performPreviewHaptic(v)
            val replaceDock = switchReplaceDock.isChecked
            WidgetPrefs.setSurfaceStyle(this, getSelectedSurfaceStyle())
            WidgetPrefs.setIconStyle(this, getSelectedIconStyle())
            WidgetPrefs.setOpacityPercent(this, seekOpacity.progress)
            WidgetPrefs.setOuterAlphaPercent(this, seekOuterAlpha.progress)
            WidgetPrefs.setRingGapDp(this, seekRingGap.progress)
            WidgetPrefs.setCornerRadiusDp(this, seekCornerRadius.progress)
            WidgetPrefs.setHapticEnabled(this, switchHaptic.isChecked)
            WidgetPrefs.setHapticStyle(this, getSelectedHapticStyle())
            WidgetPrefs.setMicAction(this, selectedMicAction)
            WidgetPrefs.setMiddleAction(this, selectedMiddleAction)
            WidgetPrefs.setAiAction(this, selectedAiAction)
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

        val isRu = resources.configuration.locales[0].language == "ru"

        // 1. Shizuku Status
        when {
            !isInstalled -> {
                txtShizukuStatus.text = if (isRu) "Не установлено" else "Not Installed"
                txtShizukuStatus.setTextColor(Color.parseColor("#757575"))
            }
            !isRunning -> {
                txtShizukuStatus.text = if (isRu) "Не запущено" else "Not Running"
                txtShizukuStatus.setTextColor(Color.parseColor("#BA1A1A"))
            }
            isShizukuGranted -> {
                txtShizukuStatus.text = if (isRu) "Активно" else "Active"
                txtShizukuStatus.setTextColor(Color.parseColor("#2E7D32"))
            }
            else -> {
                txtShizukuStatus.text = if (isRu) "Требует доступ" else "Need Permission"
                txtShizukuStatus.setTextColor(Color.parseColor("#E65100"))
            }
        }

        // 2. WRITE_SECURE_SETTINGS Status
        if (hasWriteSecure) {
            txtWriteSecureStatus.text = if (isRu) "Предоставлено" else "Granted"
            txtWriteSecureStatus.setTextColor(Color.parseColor("#2E7D32"))
            ServiceHelper.ensureAccessibilityServiceEnabled(this)
        } else {
            txtWriteSecureStatus.text = if (isRu) "Не предоставлено" else "Not Granted"
            txtWriteSecureStatus.setTextColor(Color.parseColor("#E65100"))
        }

        // 3. All Apps Search Service (Accessibility)
        if (isA11yActive) {
            txtA11yStatus.text = if (isRu) "Активно" else "Active"
            txtA11yStatus.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            txtA11yStatus.text = if (isRu) "Неактивно" else "Inactive"
            txtA11yStatus.setTextColor(Color.parseColor("#757575"))
        }

        // 4. Dock Switch Status (respect saved user preference)
        switchReplaceDock.isChecked = WidgetPrefs.isReplaceDockEnabled(this)
        txtDockStatus.text = if (hasWriteSecure) {
            if (isRu) "Встроен в док Pixel Launcher" else "Integrated into Pixel Launcher dock"
        } else {
            if (isRu) "Требуется разрешение WRITE_SECURE_SETTINGS" else "Requires WRITE_SECURE_SETTINGS permission"
        }

        // 5. Button Text and Action State
        when {
            hasWriteSecure && isA11yActive -> {
                btnRequestShizuku.text = if (isRu) "Все разрешения активны" else "All Permissions Active"
                btnRequestShizuku.isEnabled = false
            }
            isRunning && isShizukuGranted && !hasWriteSecure -> {
                btnRequestShizuku.text = if (isRu) "Выдать разрешение" else "Grant Permission"
                btnRequestShizuku.isEnabled = true
            }
            isRunning && !isShizukuGranted -> {
                btnRequestShizuku.text = if (isRu) "Запросить доступ" else "Request Access"
                btnRequestShizuku.isEnabled = true
            }
            !isRunning && isInstalled -> {
                btnRequestShizuku.text = if (isRu) "Открыть Shizuku" else "Open Shizuku"
                btnRequestShizuku.isEnabled = true
            }
            !isInstalled -> {
                btnRequestShizuku.text = if (isRu) "Установить Shizuku" else "Install Shizuku"
                btnRequestShizuku.isEnabled = true
            }
            else -> {
                btnRequestShizuku.text = if (isRu) "Настроить Shizuku" else "Configure Shizuku"
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

    private fun formatActionLabel(action: String): String {
        return when {
            action == WidgetPrefs.ACTION_VOICE_SEARCH -> getString(R.string.action_voice_search)
            action == WidgetPrefs.ACTION_GEMINI -> getString(R.string.action_gemini)
            action == WidgetPrefs.ACTION_GOOGLE_LENS -> getString(R.string.action_google_lens)
            action == WidgetPrefs.ACTION_AI_MODE -> getString(R.string.action_ai_mode)
            action == WidgetPrefs.ACTION_CHATGPT -> getString(R.string.action_chatgpt)
            action == WidgetPrefs.ACTION_PERPLEXITY -> getString(R.string.action_perplexity)
            action == WidgetPrefs.ACTION_QR_SCANNER -> getString(R.string.action_qr_scanner)
            action == WidgetPrefs.ACTION_BROWSER_BRAVE -> getString(R.string.action_browser_brave)
            action == WidgetPrefs.ACTION_BROWSER_FIREFOX -> getString(R.string.action_browser_firefox)
            action == WidgetPrefs.ACTION_BROWSER_CROMITE -> getString(R.string.action_browser_cromite)
            action.startsWith(WidgetPrefs.PREFIX_APP) -> {
                val pkg = action.removePrefix(WidgetPrefs.PREFIX_APP)
                val appLabel = try {
                    val ai = packageManager.getApplicationInfo(pkg, 0)
                    packageManager.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    pkg
                }
                appLabel
            }
            else -> getString(R.string.action_voice_search)
        }
    }

    private fun showActionPickerDialog(currentAction: String, onSelected: (String) -> Unit) {
        val presetCodes = arrayOf(
            WidgetPrefs.ACTION_VOICE_SEARCH,
            WidgetPrefs.ACTION_GEMINI,
            WidgetPrefs.ACTION_GOOGLE_LENS,
            WidgetPrefs.ACTION_AI_MODE,
            WidgetPrefs.ACTION_CHATGPT,
            WidgetPrefs.ACTION_PERPLEXITY,
            WidgetPrefs.ACTION_QR_SCANNER,
            WidgetPrefs.ACTION_BROWSER_BRAVE,
            WidgetPrefs.ACTION_BROWSER_FIREFOX,
            WidgetPrefs.ACTION_BROWSER_CROMITE
        )
        val labels = arrayOf(
            getString(R.string.action_voice_search),
            getString(R.string.action_gemini),
            getString(R.string.action_google_lens),
            getString(R.string.action_ai_mode),
            getString(R.string.action_chatgpt),
            getString(R.string.action_perplexity),
            getString(R.string.action_qr_scanner),
            getString(R.string.action_browser_brave),
            getString(R.string.action_browser_firefox),
            getString(R.string.action_browser_cromite),
            getString(R.string.btn_choose_custom_app)
        )
        val checkedIndex = presetCodes.indexOf(currentAction).let {
            if (it >= 0) it else if (currentAction.startsWith(WidgetPrefs.PREFIX_APP)) labels.lastIndex else -1
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_select_action)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                dialog.dismiss()
                if (which == labels.lastIndex) {
                    showInstalledAppsDialog(onSelected)
                } else {
                    onSelected(presetCodes[which])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showInstalledAppsDialog(onSelected: (String) -> Unit) {
        val appCtx = applicationContext
        ioExecutor.execute {
            val pm = appCtx.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved = try {
                pm.queryIntentActivities(mainIntent, 0)
            } catch (_: Exception) {
                emptyList()
            }
            val apps = resolved
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                    label to pkg
                }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (apps.isEmpty()) {
                    Toast.makeText(this, "No launchable apps found", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val itemLabels = apps.map { "${it.first} (${it.second})" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_choose_app)
                    .setItems(itemLabels) { dialog, which ->
                        dialog.dismiss()
                        onSelected("${WidgetPrefs.PREFIX_APP}${apps[which].second}")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
