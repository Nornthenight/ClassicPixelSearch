package com.pixel.classicsearch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.math.roundToInt

/**
 * Pixel QuickSearch Overlay (v1.5.0-alpha2) — Material 3 Expressive Edition.
 *
 * Guarantees a 100% seamless "Type -> See Highlighted Match with Expressive Outline -> Press Enter -> Open" flow:
 * 1. Unified selectable result cards (Calculator, App Matches #1..#4, and Search/AI fallback).
 * 2. Active target is highlighted with a bold 2.5dp Material 3 Expressive colorPrimary outline,
 *    elevated container tint, spring scale animation, and filled "Enter ↵" pill badge.
 * 3. Instant Enter / Gboard IME_ACTION_SEARCH execution of the highlighted card, plus ▼ / ▲ / Tab
 *    switching between top app match and web/AI search.
 * 4. Active AI Engine chip always sorted first with M3 Expressive pill geometry, plus Quick Launch
 *    recent apps bar on empty input.
 */
class QuickSearchActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var lastDismissRealtimeMs: Long = 0L
    }

    private sealed class ResultItem {
        data class CalculatorItem(val expression: String, val result: String) : ResultItem()
        data class AppItem(val candidate: AppSearchCandidate, val isPrimary: Boolean) : ResultItem()
        data class SearchAiItem(val title: String, val querySubtitle: String, val bang: BangRoute?) : ResultItem()
    }

    private lateinit var overlayRoot: View
    private lateinit var panelContainer: LinearLayout
    private lateinit var expressiveShell: MaterialCardView
    private lateinit var outerRing: FrameLayout
    private lateinit var innerPill: LinearLayout
    private lateinit var imgLogo: ImageView
    private lateinit var editSearch: EditText
    private lateinit var btnClearOrMic: ImageView
    private lateinit var scrollAiEngines: HorizontalScrollView
    private lateinit var chipGroupEngines: ChipGroup

    private lateinit var containerRecentApps: LinearLayout
    private lateinit var rowRecentApps: LinearLayout
    private lateinit var listExpressiveResults: LinearLayout

    private var activeAiEngine: String = WidgetPrefs.SEARCH_ENGINE_GOOGLE
    private val currentItems = mutableListOf<ResultItem>()
    private val itemCards = mutableListOf<MaterialCardView>()
    private var selectedIndex: Int = 0

    private data class EngineOption(val code: String, val labelRes: Int)

    private val engineOptions = listOf(
        EngineOption(WidgetPrefs.SEARCH_ENGINE_PERPLEXITY, R.string.action_perplexity),
        EngineOption(WidgetPrefs.SEARCH_ENGINE_CHATGPT, R.string.action_chatgpt),
        EngineOption(WidgetPrefs.SEARCH_ENGINE_GEMINI, R.string.action_gemini),
        EngineOption(WidgetPrefs.SEARCH_ENGINE_AI_MODE, R.string.action_ai_mode),
        EngineOption(WidgetPrefs.SEARCH_ENGINE_CLAUDE, R.string.action_claude),
        EngineOption(WidgetPrefs.SEARCH_ENGINE_COPILOT, R.string.action_copilot),
        EngineOption(WidgetPrefs.SEARCH_ENGINE_GOOGLE, R.string.action_search_google)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        suppressTransitions()
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        setContentView(R.layout.activity_quick_search)

        AppLaunchCache.warmUpAsync(this)

        bindViews()
        applyUserWidgetStyling()
        setupEngineChips()
        setupListeners()
        populateRecentApps()

        applyIncomingIntent(intent)
        focusSearchInput()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupEngineChips()
        populateRecentApps()
        applyIncomingIntent(intent)
        focusSearchInput()
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
            evaluateLiveQuery("")
        }
    }

    private fun focusSearchInput() {
        editSearch.requestFocus()
        editSearch.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 35L)
    }

    private fun bindViews() {
        overlayRoot = findViewById(R.id.quick_overlay_root)
        panelContainer = findViewById(R.id.quick_panel_container)
        expressiveShell = findViewById(R.id.card_expressive_shell)
        outerRing = findViewById(R.id.quick_outer_ring)
        innerPill = findViewById(R.id.quick_inner_pill)
        imgLogo = findViewById(R.id.img_quick_logo)
        editSearch = findViewById(R.id.edit_quick_search)
        btnClearOrMic = findViewById(R.id.btn_quick_clear_mic)
        scrollAiEngines = findViewById(R.id.scroll_ai_engines)
        chipGroupEngines = findViewById(R.id.chip_group_ai_engines)

        containerRecentApps = findViewById(R.id.container_recent_apps)
        rowRecentApps = findViewById(R.id.row_recent_apps)
        listExpressiveResults = findViewById(R.id.list_expressive_results)
    }

    private fun applyUserWidgetStyling() {
        val density = resources.displayMetrics.density
        val surfaceStyle = WidgetPrefs.getSurfaceStyle(this)
        val iconStyle = WidgetPrefs.getIconStyle(this)
        val gapDp = WidgetPrefs.getRingGapDp(this)
        val gapPx = (gapDp * density).roundToInt()
        val radiusDp = WidgetPrefs.getCornerRadiusDp(this)
        val outerRadiusPx = radiusDp * density
        val innerHeightDp = (62 - 2 * gapDp).coerceAtLeast(24)
        val innerRadiusPx = (radiusDp - gapDp).coerceIn(4, innerHeightDp / 2) * density

        outerRing.background = WidgetDrawableRenderer.createOuterDrawable(this, outerRadiusPx, surfaceStyle)
        innerPill.background = WidgetDrawableRenderer.createInnerDrawable(this, innerRadiusPx, surfaceStyle)
        outerRing.setPadding(gapPx, gapPx, gapPx, gapPx)

        val logoRes = when (iconStyle) {
            WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_google_logo_ref
            WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_google_logo_mono
            WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_google_logo_color
            else -> R.drawable.ic_google_logo_ref
        }
        imgLogo.setImageResource(logoRes)
        updateClearOrMicIcon(isEmpty = true)

        // Constrain max panel width on tablets (e.g. Pixel Tablet)
        val screenWidthDp = resources.configuration.screenWidthDp
        if (screenWidthDp >= 600) {
            val maxPx = (580 * density).roundToInt()
            panelContainer.layoutParams = (panelContainer.layoutParams as FrameLayout.LayoutParams).apply {
                width = maxPx
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }

        // Expressive entrance animation on shell
        expressiveShell.scaleX = 0.97f
        expressiveShell.scaleY = 0.97f
        expressiveShell.alpha = 0f
        expressiveShell.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(150L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun updateClearOrMicIcon(isEmpty: Boolean) {
        if (isEmpty) {
            val iconStyle = WidgetPrefs.getIconStyle(this)
            val micRes = when (iconStyle) {
                WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_mic_ref
                WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_mic_mono
                WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_mic_color
                else -> R.drawable.ic_mic_ref
            }
            btnClearOrMic.setImageResource(micRes)
        } else {
            btnClearOrMic.setImageResource(R.drawable.ic_close_24)
        }
    }

    /**
     * Builds Material 3 Expressive AI Engine Chips, always placing the active engine first
     * with a bold primary outline and filled secondary container.
     */
    private fun setupEngineChips() {
        activeAiEngine = WidgetPrefs.getDefaultSearchAi(this)
        chipGroupEngines.removeAllViews()

        val density = resources.displayMetrics.density
        val colorPrimary = resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
        val colorSecondaryContainer = resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)
        val colorOnSecondaryContainer = resolveThemeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
        val colorSurfaceHigh = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val colorOnSurfaceVariant = resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val colorOutlineVariant = resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant)

        // Sort so activeAiEngine is always #1 on the left
        val sortedOptions = engineOptions.sortedByDescending { it.code == activeAiEngine }

        for (option in sortedOptions) {
            val isSelected = (option.code == activeAiEngine)
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = if (isSelected) "✦ ${getString(option.labelRes)}" else getString(option.labelRes)
                isCheckable = true
                isClickable = true
                isChecked = isSelected
                isCheckedIconVisible = false
                chipCornerRadius = 20f * density
                chipBackgroundColor = ColorStateList.valueOf(
                    if (isSelected) colorSecondaryContainer else colorSurfaceHigh
                )
                setTextColor(if (isSelected) colorOnSecondaryContainer else colorOnSurfaceVariant)
                chipStrokeColor = ColorStateList.valueOf(
                    if (isSelected) colorPrimary else colorOutlineVariant
                )
                chipStrokeWidth = if (isSelected) (2f * density) else (1f * density)
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tag = option.code
            }
            chip.setOnClickListener { v ->
                performHaptic(v)
                val currentQuery = editSearch.text?.toString()?.trim() ?: ""
                if (currentQuery.isNotEmpty()) {
                    activeAiEngine = option.code
                    WidgetPrefs.setDefaultSearchAi(this, activeAiEngine)
                    dispatchToAiOrSearch(activeAiEngine, currentQuery)
                } else {
                    if (activeAiEngine == option.code) {
                        dispatchToAiOrSearch(activeAiEngine, "")
                    } else {
                        activeAiEngine = option.code
                        WidgetPrefs.setDefaultSearchAi(this, activeAiEngine)
                        setupEngineChips()
                        scrollAiEngines.smoothScrollTo(0, 0)
                    }
                }
            }
            chipGroupEngines.addView(chip)
        }
        updateSearchHintForEngine()
    }

    private fun updateSearchHintForEngine() {
        val engineLabel = getEngineDisplayName(activeAiEngine)
        editSearch.hint = getString(R.string.quick_search_hint_dynamic, engineLabel)
    }

    private fun getEngineDisplayName(engine: String): String {
        val match = engineOptions.firstOrNull { it.code == engine }
        return if (match != null) getString(match.labelRes) else getString(R.string.action_search_google)
    }

    /**
     * Populates the Material 3 Expressive Quick Launch row with up to 5 recent or top installed apps.
     */
    private fun populateRecentApps() {
        rowRecentApps.removeAllViews()
        val candidates = AppLaunchCache.getCandidates(this)
        if (candidates.isEmpty()) {
            containerRecentApps.visibility = View.GONE
            return
        }

        val byPkg = candidates.associateBy { it.packageName }
        val recentPkgs = WidgetPrefs.getRecentApps(this)
        val selectedApps = mutableListOf<AppSearchCandidate>()

        for (pkg in recentPkgs) {
            byPkg[pkg]?.let { selectedApps.add(it) }
        }

        if (selectedApps.size < 5) {
            val preferredDefaults = listOf(
                "com.avito.android",
                "org.telegram.messenger",
                "com.google.android.youtube",
                "com.android.chrome",
                "com.spotify.music",
                "com.android.settings"
            )
            for (pkg in preferredDefaults) {
                if (selectedApps.size >= 5) break
                val candidate = byPkg[pkg]
                if (candidate != null && selectedApps.none { it.packageName == pkg }) {
                    selectedApps.add(candidate)
                }
            }
            for (candidate in candidates) {
                if (selectedApps.size >= 5) break
                if ( candidate.packageName != packageName &&
                    selectedApps.none { it.packageName == candidate.packageName }
                ) {
                    selectedApps.add(candidate)
                }
            }
        }

        val density = resources.displayMetrics.density
        val colorSurfaceHigh = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val colorOutlineVariant = resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant)
        val colorOnSurface = resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)

        for ((idx, app) in selectedApps.take(5).withIndex()) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = (6 * density).roundToInt()
                }
                radius = 22f * density
                cardElevation = 0f
                setCardBackgroundColor(colorSurfaceHigh)
                strokeColor = colorOutlineVariant
                strokeWidth = (1f * density).roundToInt()
                isClickable = true
                isFocusable = true
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val padV = (10 * density).roundToInt()
                val padH = (6 * density).roundToInt()
                setPadding(padH, padV, padH, padV)
            }

            val iconSize = (36 * density).roundToInt()
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val drawable = AppLaunchCache.getAppIcon(this@QuickSearchActivity, app.packageName)
                if (drawable != null) setImageDrawable(drawable) else setImageResource(R.drawable.ic_browser)
            }

            val lbl = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (5 * density).roundToInt()
                }
                text = app.label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(colorOnSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                gravity = Gravity.CENTER
            }

            col.addView(img)
            col.addView(lbl)
            card.addView(col)
            card.setOnClickListener { v ->
                performHaptic(v)
                launchApp(app.packageName)
            }
            rowRecentApps.addView(card)
        }
    }

    private fun setupListeners() {
        overlayRoot.setOnClickListener {
            closeOverlay()
        }

        panelContainer.setOnClickListener { /* consume */ }
        expressiveShell.setOnClickListener { /* consume */ }

        imgLogo.setOnClickListener { v ->
            performHaptic(v)
            val currentQuery = editSearch.text?.toString()?.trim() ?: ""
            dispatchToAiOrSearch(WidgetPrefs.SEARCH_ENGINE_GOOGLE, currentQuery)
        }

        btnClearOrMic.setOnClickListener { v ->
            performHaptic(v)
            val currentText = editSearch.text?.toString() ?: ""
            if (currentText.isNotEmpty()) {
                editSearch.setText("")
            } else {
                launchVoiceSearch()
            }
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                updateClearOrMicIcon(query.isEmpty())
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
        if (event.action == KeyEvent.ACTION_DOWN && currentItems.isNotEmpty()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB -> {
                    moveSelection(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
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
        if (currentItems.isEmpty()) return
        selectedIndex = (selectedIndex + delta + currentItems.size) % currentItems.size
        performHaptic(editSearch)
        updateSelectionOutlines(animateActive = true)
    }

    /**
     * Evaluates the live query and builds the unified Material 3 Expressive stack of selectable cards.
     * By default (selectedIndex = 0), the top match (Calculator, or #1 App e.g. "Авито", or Search/AI)
     * automatically gets the bold 2.5dp colorPrimary outline and "Enter ↵" pill badge.
     */
    private fun evaluateLiveQuery(rawQuery: String) {
        val trimmed = rawQuery.trim()
        currentItems.clear()
        itemCards.clear()
        listExpressiveResults.removeAllViews()
        selectedIndex = 0

        if (trimmed.isEmpty()) {
            listExpressiveResults.visibility = View.GONE
            containerRecentApps.visibility = View.VISIBLE
            return
        }

        containerRecentApps.visibility = View.GONE
        listExpressiveResults.visibility = View.VISIBLE

        // 1. Check !bang command
        val bang = QuickCalculator.parseBangCommand(trimmed)

        // 2. Check math calculator
        val mathRes = if (bang == null) QuickCalculator.evaluate(trimmed) else null
        if (mathRes != null) {
            currentItems.add(ResultItem.CalculatorItem(trimmed, mathRes))
        }

        // 3. Match installed applications via AppLaunchCache + AppSearchMatcher
        val candidates = AppLaunchCache.getCandidates(this)
        val matches = if (bang == null) {
            AppSearchMatcher.findMatches(candidates, trimmed, limit = 4)
        } else {
            emptyList()
        }

        for ((idx, app) in matches.withIndex()) {
            currentItems.add(ResultItem.AppItem(app, isPrimary = (idx == 0)))
        }

        // 4. Always add Search / AI option card at the bottom so user can either press Enter
        //    (when no app matched) or press ▼ / tap to search web/AI instead of opening the app
        if (bang != null) {
            val title = getString(R.string.quick_ask_ai_format, bang.displayTitle)
            val sub = if (bang.cleanQuery.isNotEmpty()) "«${bang.cleanQuery}»" else bang.prefix
            currentItems.add(ResultItem.SearchAiItem(title, sub, bang))
        } else {
            val engineName = getEngineDisplayName(activeAiEngine)
            val title = getString(R.string.quick_ask_ai_format, engineName)
            currentItems.add(ResultItem.SearchAiItem(title, "«$trimmed»", null))
        }

        renderExpressiveResultCards()
        updateSelectionOutlines(animateActive = true)
    }

    private fun renderExpressiveResultCards() {
        val density = resources.displayMetrics.density

        for ((index, item) in currentItems.withIndex()) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (6 * density).roundToInt()
                }
                cardElevation = 0f
                isClickable = true
                isFocusable = true
            }

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = (16 * density).roundToInt()
                val padV = (12 * density).roundToInt()
                setPadding(padH, padV, padH, padV)
            }

            val iconView = ImageView(this).apply {
                val sizePx = (40 * density).roundToInt()
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val textCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (14 * density).roundToInt()
                    marginEnd = (10 * density).roundToInt()
                }
                orientation = LinearLayout.VERTICAL
            }

            val titleView = TextView(this).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                typeface = Typeface.DEFAULT_BOLD
                tag = "title"
            }

            val subView = TextView(this).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                alpha = 0.85f
                tag = "subtitle"
            }

            val badgeView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val bPadH = (12 * density).roundToInt()
                val bPadV = (6 * density).roundToInt()
                setPadding(bPadH, bPadV, bPadH, bPadV)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                text = "Enter ↵"
                tag = "badge"
            }

            when (item) {
                is ResultItem.CalculatorItem -> {
                    iconView.setImageResource(R.drawable.ic_ai_mode_ref)
                    titleView.text = "= ${item.result}"
                    titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    subView.text = getString(R.string.quick_calc_copy_hint)
                }
                is ResultItem.AppItem -> {
                    val drawable = AppLaunchCache.getAppIcon(this, item.candidate.packageName)
                    if (drawable != null) {
                        iconView.setImageDrawable(drawable)
                    } else {
                        iconView.setImageResource(R.drawable.ic_browser)
                    }
                    titleView.text = item.candidate.label
                    subView.text = getString(R.string.quick_top_app_hint)
                }
                is ResultItem.SearchAiItem -> {
                    iconView.setImageResource(R.drawable.ic_ai_mode_ref)
                    val pad = (5 * density).roundToInt()
                    iconView.setPadding(pad, pad, pad, pad)
                    titleView.text = item.title
                    subView.text = item.querySubtitle
                }
            }

            textCol.addView(titleView)
            textCol.addView(subView)

            row.addView(iconView)
            row.addView(textCol)
            row.addView(badgeView)
            card.addView(row)

            card.setOnClickListener { v ->
                performHaptic(v)
                selectedIndex = index
                updateSelectionOutlines(animateActive = false)
                executeResultItem(item)
            }

            itemCards.add(card)
            listExpressiveResults.addView(card)
        }
    }

    /**
     * Applies Material 3 Expressive active outline (2.5dp colorPrimary stroke + container fill +
     * filled colorPrimary "Enter ↵" pill) to the currently selected card, and subtle surface styling
     * to secondary cards.
     */
    private fun updateSelectionOutlines(animateActive: Boolean) {
        val density = resources.displayMetrics.density
        val colorPrimary = resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
        val colorOnPrimary = resolveThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val colorSecondaryContainer = resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)
        val colorOnSecondaryContainer = resolveThemeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
        val colorSurfaceHigh = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val colorOnSurface = resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val colorOnSurfaceVariant = resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val colorOutlineVariant = resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant)

        for ((idx, card) in itemCards.withIndex()) {
            val isSelected = (idx == selectedIndex)
            val item = currentItems.getOrNull(idx)

            val titleView = card.findViewWithTag<TextView>("title")
            val subView = card.findViewWithTag<TextView>("subtitle")
            val badgeView = card.findViewWithTag<TextView>("badge")

            if (isSelected) {
                // Bold Material 3 Expressive Active Selection Outline ("Обводка")
                card.radius = 24f * density
                card.strokeWidth = (2.6f * density).roundToInt()
                card.strokeColor = colorPrimary
                card.setCardBackgroundColor(colorSecondaryContainer)

                titleView?.setTextColor(colorOnSecondaryContainer)
                subView?.setTextColor(colorOnSecondaryContainer)
                if (item is ResultItem.AppItem) {
                    subView?.text = getString(R.string.quick_top_app_hint)
                }

                badgeView?.apply {
                    visibility = View.VISIBLE
                    text = "Enter ↵"
                    setTextColor(colorOnPrimary)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 16f * density
                        setColor(colorPrimary)
                    }
                }

                if (animateActive) {
                    card.scaleX = 0.985f
                    card.scaleY = 0.985f
                    card.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(130L)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                }
            } else {
                // Secondary unselected card
                card.radius = 20f * density
                card.strokeWidth = (1f * density).roundToInt()
                card.strokeColor = colorOutlineVariant
                card.setCardBackgroundColor(colorSurfaceHigh)

                titleView?.setTextColor(colorOnSurface)
                subView?.setTextColor(colorOnSurfaceVariant)
                if (item is ResultItem.AppItem) {
                    subView?.text = getString(R.string.quick_secondary_app_hint)
                }

                badgeView?.apply {
                    visibility = View.VISIBLE
                    text = "▼"
                    setTextColor(colorOnSurfaceVariant)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 14f * density
                        setStroke((1f * density).roundToInt(), colorOutlineVariant)
                        setColor(0x00000000)
                    }
                }
            }
        }
    }

    private fun handleEnterSubmit() {
        performHaptic(editSearch)
        val rawQuery = editSearch.text?.toString()?.trim() ?: ""

        if (currentItems.isNotEmpty()) {
            val chosen = currentItems.getOrElse(selectedIndex) { currentItems.first() }
            executeResultItem(chosen)
            return
        }

        dispatchToAiOrSearch(activeAiEngine, rawQuery)
    }

    private fun executeResultItem(item: ResultItem) {
        when (item) {
            is ResultItem.CalculatorItem -> copyMathResult(item.result)
            is ResultItem.AppItem -> launchApp(item.candidate.packageName)
            is ResultItem.SearchAiItem -> {
                if (item.bang != null) {
                    executeBangRoute(item.bang)
                } else {
                    val rawQuery = editSearch.text?.toString()?.trim() ?: ""
                    dispatchToAiOrSearch(activeAiEngine, rawQuery)
                }
            }
        }
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

    private fun launchVoiceSearch() {
        val primary = Intent("com.google.android.googlequicksearchbox.action.CLASSIC_GSA_VOICE_SEARCH").apply {
            addCategory("com.google.android.googlequicksearchbox.category.SEARCH_WIDGET")
            component = ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.googlequicksearchbox.ImplicitGsaVoiceSearchEntrypointInternal"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fallback = Intent(RecognizerIntent.ACTION_WEB_SEARCH).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val resolved = if (packageManager.resolveActivity(primary, 0) != null) primary else fallback
        safeLaunchAndFinish(resolved)
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
        closeOverlay()
    }

    override fun onStop() {
        super.onStop()
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        if (!isFinishing) {
            finish()
            suppressTransitions()
        }
    }

    private fun closeOverlay() {
        lastDismissRealtimeMs = SystemClock.elapsedRealtime()
        finish()
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
