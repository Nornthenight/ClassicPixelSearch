package com.pixel.classicsearch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

/**
 * Pixel QuickSearch Overlay — Reference #5: Minimalist Icon Dock Morph.
 *
 * Design principles:
 * 1. Empty state (""): shows ONLY the 1:1 replica of the user's home screen search widget
 *    (concentric double ring + Google G + Mic + Middle button + right AI Circle) over a
 *    44dp hardware-blurred desktop backdrop. Zero extra boxes or cards.
 * 2. Typing state (1+ chars): right widget buttons fold into a clean "✕" button while a single
 *    oval Icon Dock Pill unfolds above the search bar.
 * 3. Inside the Icon Dock Pill: matched apps + divider + AI/Google search circles.
 *    The active Enter target expands into a glowing capsule ([Icon + Title + Enter ↵]) with a
 *    2.6dp colorPrimary outline, while secondary items stay clean circular icons.
 * 4. Adaptive placement: anchored at the bottom right above Gboard on phones, and centered
 *    in the available workspace on tablets.
 */
class QuickSearchActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var lastDismissRealtimeMs: Long = 0L
    }

    private sealed class DockItem {
        data class Calculator(val expression: String, val result: String) : DockItem()
        data class App(val candidate: AppSearchCandidate) : DockItem()
        data class Bang(val route: BangRoute) : DockItem()
        data class SearchEngine(val engineCode: String, val label: String, val isSparkleIcon: Boolean) : DockItem()
    }

    private lateinit var overlayRoot: View
    private lateinit var panelContainer: LinearLayout
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

    private val currentDockItems = mutableListOf<DockItem>()
    private var selectedIndex: Int = 0
    private var wasDockVisible: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        suppressTransitions()
        super.onCreate(savedInstanceState)
        configureWindowBlurAndInput()
        setContentView(R.layout.activity_quick_search)

        AppLaunchCache.warmUpAsync(this)

        bindViews()
        applyUserWidgetStyling()
        setupListeners()

        if (intent?.getBooleanExtra("from_widget", false) == true) {
            performHaptic(outerRing)
        }
        applyIncomingIntent(intent)
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
            blurBehindRadius = (44 * density).roundToInt()
            dimAmount = 0.30f
        }
        try {
            window.setBackgroundBlurRadius((28 * density).roundToInt())
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        suppressTransitions()
        super.onNewIntent(intent)
        setIntent(intent)

        applyUserWidgetStyling()
        if (intent.getBooleanExtra("from_widget", false)) {
            performHaptic(outerRing)
        }
        applyIncomingIntent(intent)
        playSeamlessEntrance()
        focusSearchInput()
    }

    private fun playSeamlessEntrance() {
        val density = resources.displayMetrics.density
        panelContainer.animate().cancel()
        panelContainer.translationY = 10f * density
        panelContainer.scaleX = 0.985f
        panelContainer.scaleY = 0.985f
        panelContainer.alpha = 0.80f
        panelContainer.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(90L)
            .setInterpolator(OvershootInterpolator(1.05f))
            .start()
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
            imm?.showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 16L)
    }

    private fun bindViews() {
        overlayRoot = findViewById(R.id.quick_overlay_root)
        panelContainer = findViewById(R.id.quick_panel_container)
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

    /**
     * Styles the search capsule as a 1:1 visual clone of the user's home screen widget,
     * and sets adaptive screen placement (bottom above keyboard on phones, centered on tablets).
     */
    private fun applyUserWidgetStyling() {
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

        outerRing.background = WidgetDrawableRenderer.createOuterDrawable(this, outerRadiusPx, surfaceStyle)
        innerPill.background = WidgetDrawableRenderer.createInnerDrawable(this, innerRadiusPx, surfaceStyle)
        aiCircleWrapper.background = WidgetDrawableRenderer.createInnerDrawable(this, innerRadiusPx, surfaceStyle)

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

        // Adaptive placement:
        // - Smartphones (< 600dp): anchored at bottom right above the keyboard
        // - Tablets (>= 600dp): centered in the available workspace
        val screenWidthDp = resources.configuration.screenWidthDp
        val lp = panelContainer.layoutParams as FrameLayout.LayoutParams
        if (screenWidthDp >= 600) {
            lp.width = (560 * density).roundToInt()
            lp.gravity = Gravity.CENTER
            lp.bottomMargin = 0
        } else {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (14 * density).roundToInt()
        }
        panelContainer.layoutParams = lp
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
            closeOverlay()
        }

        panelContainer.setOnClickListener { /* consume */ }
        cardIconDock.setOnClickListener { /* consume */ }
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
        if (event.action == KeyEvent.ACTION_DOWN && currentDockItems.isNotEmpty()) {
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
        return super.dispatchKeyEvent(event)
    }

    private fun moveSelection(delta: Int) {
        if (currentDockItems.isEmpty()) return
        selectedIndex = (selectedIndex + delta + currentDockItems.size) % currentDockItems.size
        performHaptic(editSearch)
        renderIconDockItems(animateMorph = true)
    }

    /**
     * Evaluates the live query and populates the Minimalist Icon Dock Pill.
     * When the query is empty, the Icon Dock Pill is completely hidden (true minimalism).
     */
    private fun evaluateLiveQuery(rawQuery: String) {
        val trimmed = rawQuery.trim()
        currentDockItems.clear()
        selectedIndex = 0

        if (trimmed.isEmpty()) {
            wasDockVisible = false
            cardIconDock.visibility = View.GONE
            rowDockItems.removeAllViews()
            return
        }

        // 1. Check !bang command
        val bang = QuickCalculator.parseBangCommand(trimmed)

        // 2. Check math calculator
        val mathRes = if (bang == null) QuickCalculator.evaluate(trimmed) else null
        if (mathRes != null) {
            currentDockItems.add(DockItem.Calculator(trimmed, mathRes))
        }

        // 3. Match installed applications via AppLaunchCache + AppSearchMatcher
        val candidates = AppLaunchCache.getCandidates(this)
        val matches = if (bang == null) {
            AppSearchMatcher.findMatches(candidates, trimmed, limit = 4)
        } else {
            emptyList()
        }

        for (app in matches) {
            currentDockItems.add(DockItem.App(app))
        }

        // 4. Right-side Search & AI circles inside the dock
        if (bang != null) {
            currentDockItems.add(DockItem.Bang(bang))
        } else {
            val defaultEngine = WidgetPrefs.getDefaultSearchAi(this)
            if (defaultEngine == WidgetPrefs.SEARCH_ENGINE_GOOGLE) {
                currentDockItems.add(
                    DockItem.SearchEngine(
                        WidgetPrefs.SEARCH_ENGINE_GOOGLE,
                        getEngineDisplayName(WidgetPrefs.SEARCH_ENGINE_GOOGLE),
                        isSparkleIcon = false
                    )
                )
                currentDockItems.add(
                    DockItem.SearchEngine(
                        WidgetPrefs.SEARCH_ENGINE_AI_MODE,
                        getEngineDisplayName(WidgetPrefs.SEARCH_ENGINE_AI_MODE),
                        isSparkleIcon = true
                    )
                )
            } else {
                currentDockItems.add(
                    DockItem.SearchEngine(
                        defaultEngine,
                        getEngineDisplayName(defaultEngine),
                        isSparkleIcon = true
                    )
                )
                currentDockItems.add(
                    DockItem.SearchEngine(
                        WidgetPrefs.SEARCH_ENGINE_GOOGLE,
                        getEngineDisplayName(WidgetPrefs.SEARCH_ENGINE_GOOGLE),
                        isSparkleIcon = false
                    )
                )
            }
        }

        val animateUnfold = !wasDockVisible
        wasDockVisible = true
        cardIconDock.visibility = View.VISIBLE
        if (animateUnfold) {
            val density = resources.displayMetrics.density
            cardIconDock.translationY = 10f * density
            cardIconDock.scaleX = 0.92f
            cardIconDock.scaleY = 0.92f
            cardIconDock.alpha = 0f
            cardIconDock.animate()
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(140L)
                .setInterpolator(OvershootInterpolator(1.25f))
                .start()
        }

        renderIconDockItems(animateMorph = false)
    }

    /**
     * Renders the items inside the Minimalist Icon Dock Pill:
     * - The item at [selectedIndex] morphs into an expanded capsule ([Icon + Title + Enter ↵])
     *   with a 2.6dp colorPrimary outline and colorSecondaryContainer fill.
     * - All other items render as clean 48dp circular icons.
     */
    private fun renderIconDockItems(animateMorph: Boolean) {
        rowDockItems.removeAllViews()
        val density = resources.displayMetrics.density

        val colorPrimary = resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
        val colorSecondaryContainer = resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)
        val colorOnSecondaryContainer = resolveThemeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
        val colorSurfaceHighest = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
        val colorOutlineVariant = resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant)

        val hasLeftMatchItems = currentDockItems.any { it is DockItem.Calculator || it is DockItem.App }
        var dividerInserted = false

        for ((index, item) in currentDockItems.withIndex()) {
            val isSearchOrBang = item is DockItem.SearchEngine || item is DockItem.Bang
            if (hasLeftMatchItems && isSearchOrBang && !dividerInserted) {
                dividerInserted = true
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (1.5f * density).roundToInt().coerceAtLeast(1),
                        (36 * density).roundToInt()
                    ).apply {
                        marginStart = (10 * density).roundToInt()
                        marginEnd = (4 * density).roundToInt()
                    }
                    setBackgroundColor(colorOutlineVariant)
                }
                rowDockItems.addView(divider)
            }

            val isSelected = (index == selectedIndex)
            if (isSelected) {
                // Expanded Active Pill with 2.6dp colorPrimary outline ("Авито / Enter ↵")
                val pillCard = MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (62 * density).roundToInt()
                    ).apply {
                        if (index > 0) marginStart = (6 * density).roundToInt()
                    }
                    radius = 31f * density
                    cardElevation = 0f
                    strokeWidth = (2.6f * density).roundToInt()
                    strokeColor = colorPrimary
                    setCardBackgroundColor(colorSecondaryContainer)
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
                        (16 * density).roundToInt(),
                        0
                    )
                }

                val iconSizePx = (42 * density).roundToInt()
                val iconView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                bindItemIcon(iconView, item, density)

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
                    text = getItemTitle(item)
                    maxLines = 1
                    maxWidth = (180 * density).roundToInt()
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(colorOnSecondaryContainer)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                    typeface = Typeface.DEFAULT_BOLD
                }

                val subView = TextView(this).apply {
                    text = "Enter ↵"
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
                    executeDockItem(item)
                }

                if (animateMorph) {
                    pillCard.scaleX = 0.92f
                    pillCard.scaleY = 0.92f
                    pillCard.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(130L)
                        .setInterpolator(OvershootInterpolator(1.35f))
                        .start()
                }

                rowDockItems.addView(pillCard)
            } else {
                // Compact Circular Icon (48dp)
                val circleSizePx = (48 * density).roundToInt()
                val circleHolder = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(circleSizePx, circleSizePx).apply {
                        if (index > 0) marginStart = (8 * density).roundToInt()
                    }
                    isClickable = true
                    isFocusable = true
                }

                if (item is DockItem.SearchEngine || item is DockItem.Bang || item is DockItem.Calculator) {
                    circleHolder.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colorSurfaceHighest)
                        setStroke((1.2f * density).roundToInt(), colorOutlineVariant)
                    }
                }

                val iconSizePx = if (item is DockItem.App) (44 * density).roundToInt() else (24 * density).roundToInt()
                val iconView = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                bindItemIcon(iconView, item, density)

                circleHolder.addView(iconView)
                circleHolder.setOnClickListener { v ->
                    performHaptic(v)
                    selectedIndex = index
                    executeDockItem(item)
                }

                rowDockItems.addView(circleHolder)
            }
        }
    }

    private fun bindItemIcon(iconView: ImageView, item: DockItem, density: Float) {
        when (item) {
            is DockItem.App -> {
                val drawable = AppLaunchCache.getAppIcon(this, item.candidate.packageName)
                if (drawable != null) {
                    iconView.setImageDrawable(drawable)
                } else {
                    iconView.setImageResource(R.drawable.ic_browser)
                }
            }
            is DockItem.Calculator -> {
                val pad = (6 * density).roundToInt()
                iconView.setPadding(pad, pad, pad, pad)
                iconView.setImageResource(R.drawable.ic_ai_mode_ref)
            }
            is DockItem.Bang -> {
                val pad = (6 * density).roundToInt()
                iconView.setPadding(pad, pad, pad, pad)
                iconView.setImageResource(R.drawable.ic_ai_mode_ref)
            }
            is DockItem.SearchEngine -> {
                val pad = (6 * density).roundToInt()
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
        }
    }

    private fun getItemTitle(item: DockItem): String {
        return when (item) {
            is DockItem.Calculator -> "= ${item.result}"
            is DockItem.App -> item.candidate.label
            is DockItem.Bang -> item.route.displayTitle
            is DockItem.SearchEngine -> item.label
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

        if (currentDockItems.isNotEmpty()) {
            val chosen = currentDockItems.getOrElse(selectedIndex) { currentDockItems.first() }
            executeDockItem(chosen)
            return
        }

        val defaultEngine = WidgetPrefs.getDefaultSearchAi(this)
        dispatchToAiOrSearch(defaultEngine, rawQuery)
    }

    private fun executeDockItem(item: DockItem) {
        when (item) {
            is DockItem.Calculator -> copyMathResult(item.result)
            is DockItem.App -> launchApp(item.candidate.packageName)
            is DockItem.Bang -> executeBangRoute(item.route)
            is DockItem.SearchEngine -> {
                val rawQuery = editSearch.text?.toString()?.trim() ?: ""
                dispatchToAiOrSearch(item.engineCode, rawQuery)
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
            val encoded = Uri.encode(bang.cleanQuery)
            val uri = if (bang.cleanQuery.isNotEmpty()) {
                Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            } else {
                Uri.parse("https://www.youtube.com")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
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
        closeOverlay()
    }

    private fun safeLaunchAndFinish(intent: Intent) {
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        try {
            startActivity(intent)
        } catch (_: Exception) {}
        closeOverlay()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        resetInputAndHideIme()
    }

    override fun onStop() {
        super.onStop()
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        resetInputAndHideIme()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        closeOverlay()
    }

    private fun resetInputAndHideIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editSearch.windowToken, 0)
        if (!editSearch.text.isNullOrEmpty()) {
            editSearch.setText("")
        }
    }

    private fun closeOverlay() {
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        resetInputAndHideIme()
        moveTaskToBack(true)
        suppressTransitions()
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
