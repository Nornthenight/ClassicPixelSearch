package com.pixel.classicsearch

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

/**
 * Pixel QuickSearch Overlay — Reference #5: Dual-Block Frosted Glass Dock Morph.
 *
 * Key architecture:
 * 1. Frosted Acrylic Glass (Google Android 15 SystemUI style):
 *    - 56dp hardware windowBlurBehind + 36dp window.setBackgroundBlurRadius
 *    - Semi-translucent frosted glass pills (68% surface alpha + 38% specular highlight border)
 * 2. Context-Aware Placement & Ordering:
 *    - From Home Screen Widget: search capsule sits at BOTTOM right above Gboard;
 *      Tier 1 (Apps Dock) unfolds directly above the capsule, Tier 2 (Deep/Contacts Chips) above Tier 1.
 *    - From App Drawer (All Apps): search capsule sits at TOP directly covering the native All Apps
 *      search bar (`sourceBounds.top`); Tier 1 (Apps Dock) unfolds BELOW the capsule, Tier 2 below Tier 1.
 * 3. Zero Unmatched Clutter:
 *    - Tier 1 shows ONLY matched apps + 1 single default Search/AI icon.
 *    - Tier 2 appears ONLY when there are real matches in Contacts, Android Settings, App Shortcuts,
 *      or explicit Google Ecosystem triggers.
 */
class QuickSearchActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var lastDismissRealtimeMs: Long = 0L
        private const val REQ_READ_CONTACTS = 701
    }

    private sealed class SelectableTarget {
        // Tier 1: Primary Icon Dock items
        data class Calculator(val expression: String, val result: String) : SelectableTarget()
        data class TriggerAction(val candidate: DeepSearchCandidate) : SelectableTarget()
        data class App(val candidate: AppSearchCandidate) : SelectableTarget()
        data class Bang(val route: BangRoute) : SelectableTarget()
        data class SearchEngine(val engineCode: String, val label: String, val isSparkleIcon: Boolean) : SelectableTarget()

        // Tier 2: Compact Deep & Contact Chips
        data class Contact(val candidate: ContactCandidate) : SelectableTarget()
        data class DeepChip(val candidate: DeepSearchCandidate) : SelectableTarget()
        object RequestContactsPermission : SelectableTarget()
    }

    private lateinit var overlayRoot: View
    private lateinit var panelContainer: LinearLayout

    private lateinit var cardDeepChips: MaterialCardView
    private lateinit var scrollDeepChips: HorizontalScrollView
    private lateinit var rowDeepChips: LinearLayout

    private lateinit var cardIconDock: MaterialCardView
    private lateinit var scrollIconDock: HorizontalScrollView
    private lateinit var rowDockItems: LinearLayout

    private lateinit var outerRing: LinearLayout
    private lateinit var innerPill: LinearLayout
    private lateinit var imgLogo: ImageView
    private lateinit var editSearch: EditText
    private lateinit var btnMic: ImageView
    private lateinit var btnMiddle: ImageView
    private lateinit var btnClear: ImageView
    private lateinit var ringGapSpacer: View
    private lateinit var aiCircleWrapper: FrameLayout
    private lateinit var btnAi: ImageView

    private val tier1Items = mutableListOf<SelectableTarget>()
    private val tier2Items = mutableListOf<SelectableTarget>()
    private val allSelectableTargets = mutableListOf<SelectableTarget>()
    private var selectedGlobalIndex: Int = 0

    private var wasTier1Visible: Boolean = false
    private var wasTier2Visible: Boolean = false
    private var isClosingAnimating: Boolean = false
    private var isFromAllApps: Boolean = false
    private var activeSourceBounds: Rect? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        suppressTransitions()
        super.onCreate(savedInstanceState)
        configureWindowBlurAndInput()
        setContentView(R.layout.activity_quick_search)

        AppLaunchCache.warmUpAsync(this)
        ContactSearchHelper.warmUpAsync(this)

        bindViews()
        setupListeners()
        setupBackNavigation()

        handleLaunchConfiguration(intent)
        playSeamlessEntrance()
        focusSearchInput()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        configureWindowBlurAndInput()
    }

    private fun configureWindowBlurAndInput() {
        val density = resources.displayMetrics.density
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        window.addFlags(
            WindowManager.LayoutParams.FLAG_BLUR_BEHIND or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
        window.attributes = window.attributes.apply {
            blurBehindRadius = (56 * density).roundToInt()
            dimAmount = 0.25f
            windowAnimations = R.style.Style_NoWindowAnimation
        }
        try {
            window.setBackgroundBlurRadius((36 * density).roundToInt())
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        suppressTransitions()
        super.onNewIntent(intent)
        setIntent(intent)

        isClosingAnimating = false
        resetMorphTransforms()
        ContactSearchHelper.warmUpAsync(this)
        handleLaunchConfiguration(intent)
        playSeamlessEntrance()
        focusSearchInput()
    }

    private fun handleLaunchConfiguration(incoming: Intent?) {
        isFromAllApps = incoming?.getBooleanExtra("from_all_apps", false) == true
        val bounds = incoming?.sourceBounds
        if (bounds != null && !bounds.isEmpty) {
            activeSourceBounds = Rect(bounds)
            if (!isFromAllApps) {
                WidgetPrefs.saveWidgetBounds(this, bounds)
            }
        } else if (!isFromAllApps) {
            activeSourceBounds = WidgetPrefs.getSavedWidgetBounds(this)
        } else {
            activeSourceBounds = null
        }

        applyFrostedGlassAndPlacement()
        if (incoming?.getBooleanExtra("from_widget", false) == true) {
            performHaptic(outerRing)
        }
        applyIncomingIntent(incoming)
    }

    private fun resetMorphTransforms() {
        panelContainer.animate().cancel()
        outerRing.animate().cancel()
        cardIconDock.animate().cancel()
        cardDeepChips.animate().cancel()

        panelContainer.alpha = 1f
        panelContainer.translationX = 0f
        panelContainer.translationY = 0f
        panelContainer.scaleX = 1f
        panelContainer.scaleY = 1f

        outerRing.alpha = 1f
        outerRing.translationX = 0f
        outerRing.translationY = 0f
        outerRing.scaleX = 1f
        outerRing.scaleY = 1f

        cardIconDock.alpha = 1f
        cardIconDock.translationY = 0f
        cardIconDock.scaleX = 1f
        cardIconDock.scaleY = 1f

        cardDeepChips.alpha = 1f
        cardDeepChips.translationY = 0f
        cardDeepChips.scaleX = 1f
        cardDeepChips.scaleY = 1f
    }

    private fun playSeamlessEntrance() {
        isClosingAnimating = false
        resetMorphTransforms()
        val density = resources.displayMetrics.density

        window.attributes = window.attributes.apply {
            blurBehindRadius = (56 * density).roundToInt()
            dimAmount = 0.25f
            windowAnimations = R.style.Style_NoWindowAnimation
        }

        val targetBounds = activeSourceBounds
        if (!isFromAllApps && targetBounds != null && !targetBounds.isEmpty) {
            outerRing.post {
                if (isClosingAnimating) return@post
                val loc = IntArray(2)
                outerRing.getLocationOnScreen(loc)
                val ringW = outerRing.width.coerceAtLeast(1)
                val ringH = outerRing.height.coerceAtLeast(1)
                val ringCenterX = loc[0] + ringW / 2f
                val ringCenterY = loc[1] + ringH / 2f

                val startDx = targetBounds.exactCenterX() - ringCenterX
                val startDy = targetBounds.exactCenterY() - ringCenterY
                val startScaleX = (targetBounds.width().toFloat() / ringW).coerceIn(0.65f, 1.25f)
                val startScaleY = (targetBounds.height().toFloat() / ringH).coerceIn(0.65f, 1.25f)

                outerRing.translationX = startDx
                outerRing.translationY = startDy
                outerRing.scaleX = startScaleX
                outerRing.scaleY = startScaleY
                outerRing.alpha = 0.88f

                outerRing.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(150L)
                    .setInterpolator(OvershootInterpolator(0.95f))
                    .start()
            }
        } else {
            // In All Apps mode, the search capsule appears immediately right over the native search bar
            outerRing.alpha = 0.92f
            outerRing.scaleX = 0.985f
            outerRing.scaleY = 0.985f
            outerRing.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(95L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun applyIncomingIntent(incoming: Intent?) {
        val initialQuery = incoming?.getStringExtra("query")
            ?: incoming?.getStringExtra("extra_initial_query")
            ?: ""
        if (initialQuery.isNotEmpty()) {
            editSearch.setText(initialQuery)
            editSearch.setSelection(initialQuery.length)
        } else if (!editSearch.text.isNullOrEmpty()) {
            editSearch.setText("")
        } else {
            updateRightCapsuleButtons(isEmpty = true)
            evaluateLiveQuery("")
        }
    }

    private fun focusSearchInput() {
        editSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(editSearch, 0)
        editSearch.postDelayed({
            if (!isClosingAnimating) {
                imm?.showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 16L)
    }

    private fun bindViews() {
        overlayRoot = findViewById(R.id.quick_overlay_root)
        panelContainer = findViewById(R.id.quick_panel_container)

        cardDeepChips = findViewById(R.id.card_deep_chips)
        scrollDeepChips = findViewById(R.id.scroll_deep_chips)
        rowDeepChips = findViewById(R.id.row_deep_chips)

        cardIconDock = findViewById(R.id.card_icon_dock)
        scrollIconDock = findViewById(R.id.scroll_icon_dock)
        rowDockItems = findViewById(R.id.row_dock_items)

        outerRing = findViewById(R.id.quick_outer_ring)
        innerPill = findViewById(R.id.quick_inner_pill)
        imgLogo = findViewById(R.id.img_quick_logo)
        editSearch = findViewById(R.id.edit_quick_search)
        btnMic = findViewById(R.id.btn_quick_mic)
        btnMiddle = findViewById(R.id.btn_quick_middle)
        btnClear = findViewById(R.id.btn_quick_clear)
        ringGapSpacer = findViewById(R.id.quick_ring_gap_spacer)
        aiCircleWrapper = findViewById(R.id.quick_ai_circle_wrapper)
        btnAi = findViewById(R.id.btn_quick_ai)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeOverlay(animateToWidget = true)
            }
        })
    }

    /**
     * Styles the search capsule & floating result pills with Google Android 15 Frosted Acrylic Glass,
     * and configures context-aware placement:
     * - Home Screen (`!isFromAllApps`): search bar anchored at BOTTOM right above keyboard;
     *   order = [cardDeepChips (top), cardIconDock (middle), outerRing (bottom)].
     * - All Apps Drawer (`isFromAllApps`): search bar anchored at TOP covering the native All Apps
     *   search bar (`activeSourceBounds.top`);
     *   order = [outerRing (top), cardIconDock (middle), cardDeepChips (bottom)].
     */
    private fun applyFrostedGlassAndPlacement() {
        val density = resources.displayMetrics.density
        val surfaceStyle = WidgetPrefs.getSurfaceStyle(this)
        val iconStyle = WidgetPrefs.getIconStyle(this)
        val gapDp = WidgetPrefs.getRingGapDp(this)
        val gapPx = (gapDp * density).roundToInt()
        val radiusDp = WidgetPrefs.getCornerRadiusDp(this)
        val outerRadiusPx = radiusDp * density
        val innerHeightDp = (62 - 2 * gapDp).coerceAtLeast(24)
        val innerHeightPx = (innerHeightDp * density).roundToInt()
        val innerRadiusPx = (radiusDp - gapDp).coerceIn(4, innerHeightDp / 2) * density

        // Frosted glass outer & inner drawables for the search capsule (78% translucent glass + specular stroke)
        val baseOuterColor = WidgetDrawableRenderer.getOuterColor(this, surfaceStyle)
        val baseInnerColor = WidgetDrawableRenderer.getInnerColor(this, surfaceStyle)
        val colorOnSurface = resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val glassSpecularStroke = ColorUtils.setAlphaComponent(colorOnSurface, 58) // ~23% specular edge highlight

        outerRing.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = outerRadiusPx
            setColor(ColorUtils.setAlphaComponent(baseOuterColor, 200))
            setStroke((1.1f * density).roundToInt().coerceAtLeast(1), glassSpecularStroke)
        }
        innerPill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = innerRadiusPx
            setColor(ColorUtils.setAlphaComponent(baseInnerColor, 224))
            setStroke((1f * density).roundToInt().coerceAtLeast(1), glassSpecularStroke)
        }
        aiCircleWrapper.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = innerRadiusPx
            setColor(ColorUtils.setAlphaComponent(baseInnerColor, 224))
            setStroke((1f * density).roundToInt().coerceAtLeast(1), glassSpecularStroke)
        }

        // Frosted Acrylic Glass styling for Tier 1 (cardIconDock) and Tier 2 (cardDeepChips)
        val colorSurfaceHigh = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val frostedPillFill = ColorUtils.setAlphaComponent(colorSurfaceHigh, 178) // 70% frosted glass translucency
        val frostedPillStroke = ColorUtils.setAlphaComponent(colorOnSurface, 72) // 28% specular glass border

        cardIconDock.setCardBackgroundColor(frostedPillFill)
        cardIconDock.strokeColor = frostedPillStroke
        cardIconDock.strokeWidth = (1.2f * density).roundToInt().coerceAtLeast(1)

        cardDeepChips.setCardBackgroundColor(frostedPillFill)
        cardDeepChips.strokeColor = frostedPillStroke
        cardDeepChips.strokeWidth = (1.2f * density).roundToInt().coerceAtLeast(1)

        outerRing.setPadding(gapPx, gapPx, gapPx, gapPx)
        ringGapSpacer.layoutParams = ringGapSpacer.layoutParams.apply {
            width = gapPx
        }
        aiCircleWrapper.layoutParams = aiCircleWrapper.layoutParams.apply {
            width = innerHeightPx
        }

        val logoRes = when (iconStyle) {
            WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_google_logo_ref
            WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_google_logo_mono
            WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_google_logo_color
            else -> R.drawable.ic_google_logo_ref
        }
        imgLogo.setImageResource(logoRes)

        val micIcon = WidgetDrawableRenderer.resolveActionIcon(
            this, WidgetPrefs.getMicAction(this), iconStyle, WidgetPrefs.ACTION_VOICE_SEARCH
        )
        WidgetDrawableRenderer.applyActionIconToView(btnMic, micIcon)

        val middleIcon = WidgetDrawableRenderer.resolveActionIcon(
            this, WidgetPrefs.getMiddleAction(this), iconStyle, WidgetPrefs.ACTION_GEMINI
        )
        WidgetDrawableRenderer.applyActionIconToView(btnMiddle, middleIcon)

        val aiIcon = WidgetDrawableRenderer.resolveActionIcon(
            this, WidgetPrefs.getAiAction(this), iconStyle, WidgetPrefs.ACTION_AI_MODE
        )
        WidgetDrawableRenderer.applyActionIconToView(btnAi, aiIcon)

        // Context-Aware Ordering & Placement:
        // Reorder child views inside panelContainer so the search capsule is at the bottom when
        // launched from the home screen, and at the top (covering the All Apps search bar) when
        // launched from the App Drawer.
        panelContainer.removeAllViews()
        val lp = panelContainer.layoutParams as FrameLayout.LayoutParams
        val screenWidthDp = resources.configuration.screenWidthDp
        lp.width = if (screenWidthDp >= 600) {
            (580 * density).roundToInt()
        } else {
            FrameLayout.LayoutParams.MATCH_PARENT
        }

        if (isFromAllApps) {
            // Top-anchored over the All Apps search bar; results unfold BELOW the search capsule
            val dockLp = (cardIconDock.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = (10 * density).roundToInt()
                bottomMargin = 0
            }
            cardIconDock.layoutParams = dockLp

            val deepLp = (cardDeepChips.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = (8 * density).roundToInt()
                bottomMargin = 0
            }
            cardDeepChips.layoutParams = deepLp

            panelContainer.addView(outerRing)
            panelContainer.addView(cardIconDock)
            panelContainer.addView(cardDeepChips)

            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val statusBarOffset = getStatusBarHeightPx()
            val bounds = activeSourceBounds
            val rawTopPx = if (bounds != null && !bounds.isEmpty) {
                (bounds.top - statusBarOffset).coerceAtLeast((6 * density).roundToInt())
            } else {
                (12 * density).roundToInt()
            }
            lp.topMargin = rawTopPx
            lp.bottomMargin = 0
        } else {
            // Home Screen Widget launch: anchored at BOTTOM right above the keyboard;
            // results unfold ABOVE the search capsule
            val deepLp = (cardDeepChips.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 0
                bottomMargin = (8 * density).roundToInt()
            }
            cardDeepChips.layoutParams = deepLp

            val dockLp = (cardIconDock.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 0
                bottomMargin = (10 * density).roundToInt()
            }
            cardIconDock.layoutParams = dockLp

            panelContainer.addView(cardDeepChips)
            panelContainer.addView(cardIconDock)
            panelContainer.addView(outerRing)

            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.topMargin = 0
            lp.bottomMargin = (14 * density).roundToInt()
        }
        panelContainer.layoutParams = lp
    }

    private fun getStatusBarHeightPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun updateRightCapsuleButtons(isEmpty: Boolean) {
        if (isEmpty) {
            btnMic.visibility = View.VISIBLE
            btnMiddle.visibility = View.VISIBLE
            ringGapSpacer.visibility = View.VISIBLE
            aiCircleWrapper.visibility = View.VISIBLE
            btnClear.visibility = View.GONE
        } else {
            btnMic.visibility = View.GONE
            btnMiddle.visibility = View.GONE
            ringGapSpacer.visibility = View.GONE
            aiCircleWrapper.visibility = View.GONE
            btnClear.visibility = View.VISIBLE
        }
    }

    private fun setupListeners() {
        overlayRoot.setOnClickListener {
            closeOverlay(animateToWidget = true)
        }

        panelContainer.setOnClickListener { /* consume */ }
        cardIconDock.setOnClickListener { /* consume */ }
        cardDeepChips.setOnClickListener { /* consume */ }
        outerRing.setOnClickListener { editSearch.requestFocus() }

        imgLogo.setOnClickListener { v ->
            performHaptic(v)
            val currentQuery = editSearch.text?.toString()?.trim() ?: ""
            if (currentQuery.isNotEmpty()) {
                dispatchToAiOrSearch(WidgetPrefs.SEARCH_ENGINE_GOOGLE, currentQuery)
            } else {
                triggerWidgetTarget(SearchTapActivity.TARGET_LOGO)
            }
        }

        btnMic.setOnClickListener { v ->
            performHaptic(v)
            triggerWidgetTarget(SearchTapActivity.TARGET_MIC)
        }

        btnMiddle.setOnClickListener { v ->
            performHaptic(v)
            triggerWidgetTarget(SearchTapActivity.TARGET_MIDDLE)
        }

        aiCircleWrapper.setOnClickListener { v ->
            performHaptic(v)
            triggerWidgetTarget(SearchTapActivity.TARGET_AI)
        }

        btnClear.setOnClickListener { v ->
            performHaptic(v)
            editSearch.setText("")
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isClosingAnimating) return
                val query = s?.toString() ?: ""
                updateRightCapsuleButtons(query.isEmpty())
                evaluateLiveQuery(query)
            }
        })

        editSearch.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = event != null &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_SEND

            if (isSearchAction || isEnterKey) {
                handleEnterSubmit()
                true
            } else {
                false
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK) {
                closeOverlay(animateToWidget = true)
                return true
            }
            if (allSelectableTargets.isNotEmpty()) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_TAB -> {
                        moveSelection(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveSelection(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        handleEnterSubmit()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveSelection(delta: Int) {
        if (allSelectableTargets.isEmpty()) return
        selectedGlobalIndex = (selectedGlobalIndex + delta + allSelectableTargets.size) % allSelectableTargets.size
        performHaptic(editSearch)
        renderDualFrostedBlocks(animateMorph = true)
    }

    /**
     * Evaluates the live query into the Compact Dual-Block structure with ZERO unmatched clutter:
     * - Tier 1 (Primary Icon Dock):
     *   - Parameterized command trigger OR Calculator OR Matched Apps (up to 4)
     *   - + ONLY 1 single default Search/AI icon on the right (never unmatched ecosystem icons!)
     * - Tier 2 (Compact Deep & Contact Chips):
     *   - Real device Contacts matching the query (with direct 📞 Call button)
     *   - Matched Android System Settings sub-pages (`⚙`) & App Functions (`⚡`)
     *   - Hidden completely if there are 0 matches in Tier 2!
     */
    private fun evaluateLiveQuery(rawQuery: String) {
        val trimmed = rawQuery.trim()
        tier1Items.clear()
        tier2Items.clear()
        allSelectableTargets.clear()
        selectedGlobalIndex = 0

        if (trimmed.isEmpty()) {
            wasTier1Visible = false
            wasTier2Visible = false
            cardIconDock.visibility = View.GONE
            cardDeepChips.visibility = View.GONE
            rowDockItems.removeAllViews()
            rowDeepChips.removeAllViews()
            return
        }

        val bang = QuickCalculator.parseBangCommand(trimmed)
        val paramTrigger = if (bang == null) {
            DeepSearchCatalog.resolveParameterizedTrigger(this, trimmed)
        } else null
        val mathRes = if (bang == null && paramTrigger == null) QuickCalculator.evaluate(trimmed) else null

        // --- TIER 1: Primary App Icon Dock + 1 Default Search/AI Target ---
        if (paramTrigger != null) {
            tier1Items.add(SelectableTarget.TriggerAction(paramTrigger))
        }
        if (mathRes != null) {
            tier1Items.add(SelectableTarget.Calculator(trimmed, mathRes))
        }

        val appCandidates = AppLaunchCache.getCandidates(this)
        val appMatches = if (bang == null && paramTrigger == null) {
            AppSearchMatcher.findMatches(appCandidates, trimmed, limit = 4)
        } else {
            emptyList()
        }
        for (app in appMatches) {
            tier1Items.add(SelectableTarget.App(app))
        }

        // Add ONLY 1 single default Search/AI item at the end of Tier 1 (zero unmatched icon clutter!)
        if (bang != null) {
            tier1Items.add(SelectableTarget.Bang(bang))
        } else {
            val defaultEngine = WidgetPrefs.getDefaultSearchAi(this)
            val isSparkle = defaultEngine != WidgetPrefs.SEARCH_ENGINE_GOOGLE
            tier1Items.add(
                SelectableTarget.SearchEngine(
                    engineCode = defaultEngine,
                    label = getEngineDisplayName(defaultEngine),
                    isSparkleIcon = isSparkle
                )
            )
        }

        // --- TIER 2: Compact Contacts & Deep Settings/App-Functions Chips ---
        if (bang == null && paramTrigger == null) {
            // 1. Live Contacts search
            if (ContactSearchHelper.hasContactsPermission(this)) {
                val matchedContacts = ContactSearchHelper.findMatchingContacts(this, trimmed, limit = 3)
                for (c in matchedContacts) {
                    tier2Items.add(SelectableTarget.Contact(c))
                }
            } else if (shouldOfferContactsPermissionChip(trimmed, appMatches.isEmpty())) {
                tier2Items.add(SelectableTarget.RequestContactsPermission)
            }

            // 2. Deep Android Settings & App Shortcuts/Functions (excluding generic ecosystem clutter)
            val deepMatches = DeepSearchCatalog.findDeepMatches(this, trimmed, limit = 3)
            for (deep in deepMatches) {
                tier2Items.add(SelectableTarget.DeepChip(deep))
            }
        }

        // Build unified Tab/Arrow navigation order:
        // If Tier 1 has real matches (Apps/Calc/Trigger), Tier 1 comes first;
        // if Tier 1 has 0 apps but Tier 2 has a matched Contact or Setting, select the first Tier 2 match first!
        val hasTier1DeviceMatches = tier1Items.any {
            it is SelectableTarget.App || it is SelectableTarget.Calculator || it is SelectableTarget.TriggerAction
        }
        val hasTier2RealMatches = tier2Items.any {
            it is SelectableTarget.Contact || it is SelectableTarget.DeepChip
        }

        allSelectableTargets.addAll(tier1Items)
        allSelectableTargets.addAll(tier2Items)

        selectedGlobalIndex = if (!hasTier1DeviceMatches && hasTier2RealMatches) {
            tier1Items.size // Highlight the matched Contact or Setting immediately for Enter ↵!
        } else {
            0
        }

        val density = resources.displayMetrics.density
        val slideDir = if (isFromAllApps) -1f else 1f

        // Show & animate Tier 1
        val animateTier1 = !wasTier1Visible
        wasTier1Visible = true
        cardIconDock.visibility = View.VISIBLE
        if (animateTier1) {
            cardIconDock.translationY = 8f * density * slideDir
            cardIconDock.scaleX = 0.94f
            cardIconDock.scaleY = 0.94f
            cardIconDock.alpha = 0f
            cardIconDock.animate()
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(130L)
                .setInterpolator(OvershootInterpolator(1.15f))
                .start()
        }

        // Show & animate Tier 2 ONLY when non-empty
        if (tier2Items.isNotEmpty()) {
            val animateTier2 = !wasTier2Visible
            wasTier2Visible = true
            cardDeepChips.visibility = View.VISIBLE
            if (animateTier2) {
                cardDeepChips.translationY = 8f * density * slideDir
                cardDeepChips.scaleX = 0.94f
                cardDeepChips.scaleY = 0.94f
                cardDeepChips.alpha = 0f
                cardDeepChips.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(140L)
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .start()
            }
        } else {
            wasTier2Visible = false
            cardDeepChips.visibility = View.GONE
            rowDeepChips.removeAllViews()
        }

        renderDualFrostedBlocks(animateMorph = false)
    }

    private fun shouldOfferContactsPermissionChip(query: String, noAppsMatched: Boolean): Boolean {
        val lower = query.lowercase()
        val contactKeywords = listOf("контакт", "телефон", "позвон", "номер", "contact", "call", "мама", "папа")
        if (contactKeywords.any { lower.startsWith(it) || it.startsWith(lower) }) return true
        // Also offer when the user types a 3+ letter name that doesn't match any installed app
        return noAppsMatched && query.length >= 3 && query.all { it.isLetter() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_CONTACTS &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            ContactSearchHelper.invalidateCache()
            ContactSearchHelper.warmUpAsync(this)
            val currentQuery = editSearch.text?.toString() ?: ""
            editSearch.postDelayed({
                evaluateLiveQuery(currentQuery)
            }, 120L)
        }
    }

    /**
     * Renders both Frosted Glass tiers:
     * - Tier 1 (`rowDockItems`): App icons + 1 Search/AI icon, where the selected item expands
     *   into `[Icon + Title + Enter ↵]` with a `2.6dp colorPrimary` outline.
     * - Tier 2 (`rowDeepChips`): Compact pill chips for matched Contacts (with separate 📞 Call icon
     *   on the right) and Deep Settings / App Functions.
     */
    private fun renderDualFrostedBlocks(animateMorph: Boolean) {
        rowDockItems.removeAllViews()
        rowDeepChips.removeAllViews()

        val density = resources.displayMetrics.density
        val colorPrimary = resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
        val colorOnPrimary = resolveThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val colorSecondaryContainer = resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)
        val colorOnSecondaryContainer = resolveThemeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
        val colorSurfaceHighest = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
        val colorOnSurface = resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val colorOnSurfaceVariant = resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val colorOutlineVariant = resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant)

        // =====================================================================
        // RENDER TIER 1: Primary App Icon Dock
        // =====================================================================
        val hasLeftAppMatches = tier1Items.any {
            it is SelectableTarget.App || it is SelectableTarget.Calculator || it is SelectableTarget.TriggerAction
        }
        var dividerInserted = false

        for ((idx, item) in tier1Items.withIndex()) {
            val globalIdx = idx
            val isSelected = (globalIdx == selectedGlobalIndex)
            val isRightSearch = item is SelectableTarget.SearchEngine || item is SelectableTarget.Bang

            if (hasLeftAppMatches && isRightSearch && !dividerInserted) {
                dividerInserted = true
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (1.5f * density).roundToInt().coerceAtLeast(1),
                        (34 * density).roundToInt()
                    ).apply {
                        marginStart = (10 * density).roundToInt()
                        marginEnd = (4 * density).roundToInt()
                    }
                    setBackgroundColor(ColorUtils.setAlphaComponent(colorOutlineVariant, 160))
                }
                rowDockItems.addView(divider)
            }

            if (isSelected) {
                val pillCard = MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (58 * density).roundToInt()
                    ).apply {
                        if (idx > 0) marginStart = (6 * density).roundToInt()
                    }
                    radius = 29f * density
                    cardElevation = 0f
                    strokeWidth = (2.5f * density).roundToInt()
                    strokeColor = colorPrimary
                    setCardBackgroundColor(ColorUtils.setAlphaComponent(colorSecondaryContainer, 225))
                    isClickable = true
                    isFocusable = true
                }

                val pillRow = LinearLayout(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        (9 * density).roundToInt(),
                        0,
                        (15 * density).roundToInt(),
                        0
                    )
                }

                val iconSizePx = (40 * density).roundToInt()
                val iconView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                bindTier1Icon(iconView, item, density)

                val textCol = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (10 * density).roundToInt()
                    }
                    orientation = LinearLayout.VERTICAL
                }

                val titleView = TextView(this).apply {
                    text = getTier1Title(item)
                    maxLines = 1
                    maxWidth = (200 * density).roundToInt()
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(colorOnSecondaryContainer)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                }

                val subView = TextView(this).apply {
                    text = getTier1Subtitle(item)
                    maxLines = 1
                    setTextColor(colorPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.DEFAULT_BOLD
                }

                textCol.addView(titleView)
                textCol.addView(subView)
                pillRow.addView(iconView)
                pillRow.addView(textCol)
                pillCard.addView(pillRow)

                pillCard.setOnClickListener { v ->
                    performHaptic(v)
                    executeTarget(item)
                }

                if (animateMorph) {
                    pillCard.scaleX = 0.92f
                    pillCard.scaleY = 0.92f
                    pillCard.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(125L)
                        .setInterpolator(OvershootInterpolator(1.3f))
                        .start()
                }

                rowDockItems.addView(pillCard)
            } else {
                val circleSizePx = (46 * density).roundToInt()
                val circleHolder = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(circleSizePx, circleSizePx).apply {
                        if (idx > 0) marginStart = (8 * density).roundToInt()
                    }
                    isClickable = true
                    isFocusable = true
                }

                if (item !is SelectableTarget.App) {
                    circleHolder.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ColorUtils.setAlphaComponent(colorSurfaceHighest, 195))
                        setStroke((1.1f * density).roundToInt(), ColorUtils.setAlphaComponent(colorOutlineVariant, 150))
                    }
                }

                val iconSizePx = if (item is SelectableTarget.App) (42 * density).roundToInt() else (24 * density).roundToInt()
                val iconView = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                bindTier1Icon(iconView, item, density)

                circleHolder.addView(iconView)
                circleHolder.setOnClickListener { v ->
                    performHaptic(v)
                    selectedGlobalIndex = globalIdx
                    executeTarget(item)
                }

                rowDockItems.addView(circleHolder)
            }
        }

        // =====================================================================
        // RENDER TIER 2: Compact Frosted Chips (Contacts with Call button, Settings, App Shortcuts)
        // =====================================================================
        for ((idx, item) in tier2Items.withIndex()) {
            val globalIdx = tier1Items.size + idx
            val isSelected = (globalIdx == selectedGlobalIndex)

            val chipCard = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (44 * density).roundToInt()
                ).apply {
                    if (idx > 0) marginStart = (6 * density).roundToInt()
                }
                radius = 22f * density
                cardElevation = 0f
                strokeWidth = if (isSelected) (2.3f * density).roundToInt() else (1f * density).roundToInt()
                strokeColor = if (isSelected) colorPrimary else ColorUtils.setAlphaComponent(colorOutlineVariant, 140)
                setCardBackgroundColor(
                    if (isSelected) {
                        ColorUtils.setAlphaComponent(colorSecondaryContainer, 225)
                    } else {
                        ColorUtils.setAlphaComponent(colorSurfaceHighest, 185)
                    }
                )
                isClickable = true
                isFocusable = true
            }

            val chipRow = LinearLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (8 * density).roundToInt(),
                    0,
                    (10 * density).roundToInt(),
                    0
                )
            }

            when (item) {
                is SelectableTarget.Contact -> {
                    // Avatar circle with contact initial
                    val avatarSize = (28 * density).roundToInt()
                    val initial = item.candidate.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "👤"
                    val avatarView = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
                        gravity = Gravity.CENTER
                        text = initial
                        setTextColor(colorOnPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        typeface = Typeface.DEFAULT_BOLD
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(colorPrimary)
                        }
                    }
                    chipRow.addView(avatarView)

                    val nameText = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginStart = (8 * density).roundToInt()
                        }
                        text = if (isSelected) "${item.candidate.displayName} • ↵" else item.candidate.displayName
                        maxLines = 1
                        maxWidth = (155 * density).roundToInt()
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(if (isSelected) colorOnSecondaryContainer else colorOnSurface)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    chipRow.addView(nameText)

                    // Separate circular Call button ("📞") on the right of the contact chip
                    if (!item.candidate.phoneNumber.isNullOrBlank()) {
                        val callSize = (30 * density).roundToInt()
                        val callBtn = TextView(this).apply {
                            layoutParams = LinearLayout.LayoutParams(callSize, callSize).apply {
                                marginStart = (8 * density).roundToInt()
                            }
                            gravity = Gravity.CENTER
                            text = "📞"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(ColorUtils.setAlphaComponent(colorPrimary, 45))
                                setStroke((1f * density).roundToInt(), colorPrimary)
                            }
                            setOnClickListener { v ->
                                performHaptic(v)
                                val dialIntent = ContactSearchHelper.createDialContactIntent(item.candidate)
                                safeLaunchAndFinish(dialIntent)
                            }
                        }
                        chipRow.addView(callBtn)
                    }
                }

                is SelectableTarget.RequestContactsPermission -> {
                    val labelView = TextView(this).apply {
                        text = "👤 Разрешить поиск контактов"
                        maxLines = 1
                        setTextColor(colorPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding((4 * density).roundToInt(), 0, (4 * density).roundToInt(), 0)
                    }
                    chipRow.addView(labelView)
                }

                is SelectableTarget.DeepChip -> {
                    val iconSize = (24 * density).roundToInt()
                    val appDrawable = AppLaunchCache.getAppIcon(this, item.candidate.packageName)
                    if (appDrawable != null) {
                        val iv = ImageView(this).apply {
                            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(appDrawable)
                        }
                        chipRow.addView(iv)
                    } else {
                        val badgeEmoji = when (item.candidate.category) {
                            DeepActionCategory.SETTINGS -> "⚙"
                            DeepActionCategory.APP_FUNCTION -> "⚡"
                            DeepActionCategory.GOOGLE_ECOSYSTEM -> "G"
                        }
                        val tvBadge = TextView(this).apply {
                            text = badgeEmoji
                            setTextColor(colorPrimary)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        chipRow.addView(tvBadge)
                    }

                    val titleView = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginStart = (7 * density).roundToInt()
                            marginEnd = (2 * density).roundToInt()
                        }
                        text = if (isSelected) "${item.candidate.title} • ↵" else item.candidate.title
                        maxLines = 1
                        maxWidth = (185 * density).roundToInt()
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(if (isSelected) colorOnSecondaryContainer else colorOnSurface)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.2f)
                        typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    chipRow.addView(titleView)
                }

                else -> {}
            }

            chipCard.addView(chipRow)
            chipCard.setOnClickListener { v ->
                performHaptic(v)
                selectedGlobalIndex = globalIdx
                executeTarget(item)
            }

            rowDeepChips.addView(chipCard)
        }
    }

    private fun bindTier1Icon(iconView: ImageView, item: SelectableTarget, density: Float) {
        when (item) {
            is SelectableTarget.App -> {
                val drawable = AppLaunchCache.getAppIcon(this, item.candidate.packageName)
                if (drawable != null) {
                    iconView.setImageDrawable(drawable)
                } else {
                    iconView.setImageResource(R.drawable.ic_browser)
                }
            }
            is SelectableTarget.TriggerAction -> {
                val drawable = AppLaunchCache.getAppIcon(this, item.candidate.packageName)
                if (drawable != null) {
                    iconView.setImageDrawable(drawable)
                } else {
                    iconView.setImageResource(R.drawable.ic_google_logo_color)
                }
            }
            is SelectableTarget.Calculator, is SelectableTarget.Bang -> {
                val pad = (5 * density).roundToInt()
                iconView.setPadding(pad, pad, pad, pad)
                iconView.setImageResource(R.drawable.ic_ai_mode_ref)
            }
            is SelectableTarget.SearchEngine -> {
                val pad = (5 * density).roundToInt()
                iconView.setPadding(pad, pad, pad, pad)
                val iconStyle = WidgetPrefs.getIconStyle(this)
                if (item.isSparkleIcon) {
                    val res = when (iconStyle) {
                        WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_ai_mode_ref
                        WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_ai_mode_mono
                        WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_ai_mode_color
                        else -> R.drawable.ic_ai_mode_ref
                    }
                    iconView.setImageResource(res)
                } else {
                    val res = when (iconStyle) {
                        WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_google_logo_ref
                        WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_google_logo_mono
                        WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_google_logo_color
                        else -> R.drawable.ic_google_logo_ref
                    }
                    iconView.setImageResource(res)
                }
            }
            else -> {}
        }
    }

    private fun getTier1Title(item: SelectableTarget): String {
        return when (item) {
            is SelectableTarget.Calculator -> "= ${item.result}"
            is SelectableTarget.TriggerAction -> item.candidate.title
            is SelectableTarget.App -> item.candidate.label
            is SelectableTarget.Bang -> item.route.displayTitle
            is SelectableTarget.SearchEngine -> item.label
            else -> ""
        }
    }

    private fun getTier1Subtitle(item: SelectableTarget): String {
        return when (item) {
            is SelectableTarget.TriggerAction -> "${item.candidate.subtitle} • Enter ↵"
            is SelectableTarget.Calculator -> "Скопировать • Enter ↵"
            else -> "Enter ↵"
        }
    }

    private fun getEngineDisplayName(engine: String): String {
        return when (engine) {
            WidgetPrefs.SEARCH_ENGINE_PERPLEXITY -> getString(R.string.action_perplexity)
            WidgetPrefs.SEARCH_ENGINE_CHATGPT -> getString(R.string.action_chatgpt)
            WidgetPrefs.SEARCH_ENGINE_GEMINI -> getString(R.string.action_gemini)
            WidgetPrefs.SEARCH_ENGINE_AI_MODE -> getString(R.string.action_ai_mode)
            WidgetPrefs.SEARCH_ENGINE_CLAUDE -> getString(R.string.action_claude)
            WidgetPrefs.SEARCH_ENGINE_COPILOT -> getString(R.string.action_copilot)
            else -> getString(R.string.action_search_google)
        }
    }

    private fun handleEnterSubmit() {
        performHaptic(editSearch)
        val rawQuery = editSearch.text?.toString()?.trim() ?: ""

        if (allSelectableTargets.isNotEmpty()) {
            val chosen = allSelectableTargets.getOrElse(selectedGlobalIndex) { allSelectableTargets.first() }
            executeTarget(chosen)
            return
        }

        val defaultEngine = WidgetPrefs.getDefaultSearchAi(this)
        dispatchToAiOrSearch(defaultEngine, rawQuery)
    }

    private fun executeTarget(target: SelectableTarget) {
        val rawQuery = editSearch.text?.toString()?.trim() ?: ""
        when (target) {
            is SelectableTarget.Calculator -> copyMathResult(target.result)
            is SelectableTarget.TriggerAction -> {
                val intent = target.candidate.intentProvider(this, rawQuery)
                safeLaunchAndFinish(intent)
            }
            is SelectableTarget.App -> launchApp(target.candidate.packageName)
            is SelectableTarget.Bang -> executeBangRoute(target.route)
            is SelectableTarget.SearchEngine -> dispatchToAiOrSearch(target.engineCode, rawQuery)
            is SelectableTarget.Contact -> {
                val openIntent = ContactSearchHelper.createOpenContactIntent(target.candidate)
                safeLaunchAndFinish(openIntent)
            }
            is SelectableTarget.DeepChip -> {
                val intent = target.candidate.intentProvider(this, rawQuery)
                safeLaunchAndFinish(intent)
            }
            is SelectableTarget.RequestContactsPermission -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_CONTACTS),
                    REQ_READ_CONTACTS
                )
            }
        }
    }

    private fun triggerWidgetTarget(target: String) {
        val intent = Intent(this, SearchTapActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(SearchTapActivity.EXTRA_TAP_TARGET, target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        safeLaunchAndFinish(intent)
    }

    private fun executeBangRoute(bang: BangRoute) {
        if (bang.isYouTube) {
            val intent = DeepSearchCatalog.createYouTubeSearchIntent(bang.cleanQuery)
            safeLaunchAndFinish(intent)
        } else {
            dispatchToAiOrSearch(bang.engineOrTarget, bang.cleanQuery)
        }
    }

    private fun dispatchToAiOrSearch(engine: String, query: String) {
        val intent = AiSearchDispatcher.createSearchIntent(this, engine, query)
        safeLaunchAndFinish(intent)
    }

    private fun launchApp(packageName: String) {
        WidgetPrefs.recordRecentApp(this, packageName)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launchIntent != null) {
            safeLaunchAndFinish(launchIntent)
        }
    }

    private fun copyMathResult(result: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Calculator Result", result))
        Toast.makeText(this, getString(R.string.quick_calc_copied, result), Toast.LENGTH_SHORT).show()
        closeOverlay(animateToWidget = true)
    }

    private fun safeLaunchAndFinish(intent: Intent) {
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        try {
            startActivity(intent)
        } catch (_: Exception) {
            val rawQuery = editSearch.text?.toString()?.trim() ?: ""
            if (rawQuery.isNotEmpty()) {
                try {
                    startActivity(AiSearchDispatcher.createSearchIntent(this, WidgetPrefs.SEARCH_ENGINE_GOOGLE, rawQuery))
                } catch (_: Exception) {}
            }
        }
        closeOverlay(animateToWidget = false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        resetInputAndHideIme()
    }

    override fun onStop() {
        super.onStop()
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        isClosingAnimating = false
        resetInputAndHideIme()
        resetMorphTransforms()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        closeOverlay(animateToWidget = true)
    }

    private fun resetInputAndHideIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editSearch.windowToken, 0)
        if (!editSearch.text.isNullOrEmpty()) {
            editSearch.setText("")
        }
        updateRightCapsuleButtons(isEmpty = true)
        wasTier1Visible = false
        wasTier2Visible = false
        cardIconDock.visibility = View.GONE
        cardDeepChips.visibility = View.GONE
        rowDockItems.removeAllViews()
        rowDeepChips.removeAllViews()
    }

    /**
     * Smoothly folds both Frosted Glass result blocks and morphs the search capsule back into
     * its origin (the home screen widget bounds or the All Apps search bar) while dissolving blur.
     */
    private fun closeOverlay(animateToWidget: Boolean = true) {
        if (isClosingAnimating) return
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editSearch.windowToken, 0)

        if (!animateToWidget) {
            resetInputAndHideIme()
            moveTaskToBack(true)
            suppressTransitions()
            return
        }

        isClosingAnimating = true
        val density = resources.displayMetrics.density
        val foldDir = if (isFromAllApps) -1f else 1f

        updateRightCapsuleButtons(isEmpty = true)

        if (cardDeepChips.visibility == View.VISIBLE) {
            cardDeepChips.animate()
                .translationY(14f * density * foldDir)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .alpha(0f)
                .setDuration(105L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        if (cardIconDock.visibility == View.VISIBLE) {
            cardIconDock.animate()
                .translationY(14f * density * foldDir)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .alpha(0f)
                .setDuration(115L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        val targetBounds = activeSourceBounds
        val loc = IntArray(2)
        outerRing.getLocationOnScreen(loc)
        val ringW = outerRing.width.coerceAtLeast(1)
        val ringH = outerRing.height.coerceAtLeast(1)
        val ringCenterX = loc[0] + ringW / 2f
        val ringCenterY = loc[1] + ringH / 2f

        val targetDx: Float
        val targetDy: Float
        val targetScaleX: Float
        val targetScaleY: Float

        if (!isFromAllApps && targetBounds != null && !targetBounds.isEmpty) {
            targetDx = targetBounds.exactCenterX() - ringCenterX
            targetDy = targetBounds.exactCenterY() - ringCenterY
            targetScaleX = (targetBounds.width().toFloat() / ringW).coerceIn(0.65f, 1.25f)
            targetScaleY = (targetBounds.height().toFloat() / ringH).coerceIn(0.65f, 1.25f)
        } else {
            // In All Apps mode, capsule is already right on top of the native search bar
            targetDx = 0f
            targetDy = 0f
            targetScaleX = 0.97f
            targetScaleY = 0.97f
        }

        val startBlur = (56 * density).roundToInt()
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 155L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                try {
                    window.attributes = window.attributes.apply {
                        dimAmount = 0.25f * fraction
                        blurBehindRadius = (startBlur * fraction).roundToInt().coerceAtLeast(1)
                        windowAnimations = R.style.Style_NoWindowAnimation
                    }
                } catch (_: Exception) {}
            }
            start()
        }

        outerRing.animate()
            .translationX(targetDx)
            .translationY(targetDy)
            .scaleX(targetScaleX)
            .scaleY(targetScaleY)
            .alpha(0f)
            .setDuration(155L)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    outerRing.animate().setListener(null)
                    isClosingAnimating = false
                    resetInputAndHideIme()
                    resetMorphTransforms()
                    moveTaskToBack(true)
                    suppressTransitions()
                }
            })
            .start()
    }

    override fun finish() {
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        super.finish()
        suppressTransitions()
    }

    private fun resolveThemeColor(attrRes: Int): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(attrRes, typedValue, true)) {
            typedValue.data
        } else {
            0xFF8AB4F8.toInt()
        }
    }

    private fun performHaptic(view: View) {
        if (!WidgetPrefs.isHapticEnabled(this)) return
        val constant = if (WidgetPrefs.getHapticStyle(this) == WidgetPrefs.HAPTIC_CONTEXT_CLICK) {
            HapticFeedbackConstants.CONTEXT_CLICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        try {
            view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        } catch (_: Exception) {}
    }

    private fun suppressTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
