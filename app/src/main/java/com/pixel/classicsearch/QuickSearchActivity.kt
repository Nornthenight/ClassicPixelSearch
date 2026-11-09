package com.pixel.classicsearch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
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
 * Pixel QuickSearch Overlay (v1.5.0-alpha1).
 *
 * Instant Material You overlay providing:
 * 1. Real-time app matching with bidirectional RU <-> EN transliteration and visual card highlighting.
 * 2. Guaranteed Enter / Gboard IME_ACTION_SEARCH handling (launches #1 highlighted app or sends to AI).
 * 3. Instant math calculator expression evaluation with one-tap / Enter clipboard copy.
 * 4. Interactive AI Engine chips (Perplexity, ChatGPT, Gemini, AI Mode, Claude, Copilot, Google)
 *    and !bang command routing (!p, !c, !g, !y, !gem, !cl).
 */
class QuickSearchActivity : AppCompatActivity() {

    private lateinit var overlayRoot: View
    private lateinit var panelContainer: LinearLayout
    private lateinit var outerRing: FrameLayout
    private lateinit var innerPill: LinearLayout
    private lateinit var imgLogo: ImageView
    private lateinit var editSearch: EditText
    private lateinit var btnClearOrMic: ImageView
    private lateinit var chipGroupEngines: ChipGroup

    private lateinit var cardCalc: MaterialCardView
    private lateinit var txtCalcResult: TextView

    private lateinit var cardTopApp: MaterialCardView
    private lateinit var imgTopAppIcon: ImageView
    private lateinit var txtTopAppLabel: TextView
    private lateinit var txtTopAppSub: TextView
    private lateinit var badgeTopAppEnter: TextView

    private lateinit var cardSecondaryApps: MaterialCardView
    private lateinit var listSecondaryApps: LinearLayout

    private lateinit var cardAskAi: MaterialCardView
    private lateinit var imgAskAiIcon: ImageView
    private lateinit var txtAskAiTitle: TextView
    private lateinit var txtAskAiQuery: TextView
    private lateinit var badgeAskAiEnter: TextView

    private var activeAiEngine: String = WidgetPrefs.SEARCH_ENGINE_GOOGLE
    private var topMatchedApp: AppSearchCandidate? = null
    private var currentMathResult: String? = null
    private var currentBangRoute: BangRoute? = null

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

        val initialQuery = intent?.getStringExtra("query") ?: ""
        if (initialQuery.isNotEmpty()) {
            editSearch.setText(initialQuery)
            editSearch.setSelection(initialQuery.length)
        }

        editSearch.requestFocus()
        editSearch.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 40L)
    }

    private fun bindViews() {
        overlayRoot = findViewById(R.id.quick_overlay_root)
        panelContainer = findViewById(R.id.quick_panel_container)
        outerRing = findViewById(R.id.quick_outer_ring)
        innerPill = findViewById(R.id.quick_inner_pill)
        imgLogo = findViewById(R.id.img_quick_logo)
        editSearch = findViewById(R.id.edit_quick_search)
        btnClearOrMic = findViewById(R.id.btn_quick_clear_mic)
        chipGroupEngines = findViewById(R.id.chip_group_ai_engines)

        cardCalc = findViewById(R.id.card_quick_calc)
        txtCalcResult = findViewById(R.id.txt_calc_result)

        cardTopApp = findViewById(R.id.card_top_app_match)
        imgTopAppIcon = findViewById(R.id.img_top_app_icon)
        txtTopAppLabel = findViewById(R.id.txt_top_app_label)
        txtTopAppSub = findViewById(R.id.txt_top_app_sub)
        badgeTopAppEnter = findViewById(R.id.badge_top_app_enter)

        cardSecondaryApps = findViewById(R.id.card_secondary_apps)
        listSecondaryApps = findViewById(R.id.list_secondary_apps)

        cardAskAi = findViewById(R.id.card_ask_ai)
        imgAskAiIcon = findViewById(R.id.img_ask_ai_icon)
        txtAskAiTitle = findViewById(R.id.txt_ask_ai_title)
        txtAskAiQuery = findViewById(R.id.txt_ask_ai_query)
        badgeAskAiEnter = findViewById(R.id.badge_ask_ai_enter)
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
            val maxPx = (560 * density).roundToInt()
            panelContainer.layoutParams = (panelContainer.layoutParams as FrameLayout.LayoutParams).apply {
                width = maxPx
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
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

    private fun setupEngineChips() {
        activeAiEngine = WidgetPrefs.getDefaultSearchAi(this)
        chipGroupEngines.removeAllViews()

        for (option in engineOptions) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = getString(option.labelRes)
                isCheckable = true
                isClickable = true
                isChecked = (option.code == activeAiEngine)
                tag = option.code
            }
            chip.setOnClickListener { v ->
                performHaptic(v)
                val currentQuery = editSearch.text?.toString()?.trim() ?: ""
                if (currentQuery.isNotEmpty()) {
                    // Immediately dispatch current query to the tapped AI engine!
                    activeAiEngine = option.code
                    WidgetPrefs.setDefaultSearchAi(this, activeAiEngine)
                    dispatchToAiOrSearch(activeAiEngine, currentQuery)
                } else {
                    if (activeAiEngine == option.code) {
                        // Tapping already active chip on empty query opens that AI directly
                        dispatchToAiOrSearch(activeAiEngine, "")
                    } else {
                        activeAiEngine = option.code
                        WidgetPrefs.setDefaultSearchAi(this, activeAiEngine)
                        chip.isChecked = true
                        updateSearchHintForEngine()
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

    private fun setupListeners() {
        overlayRoot.setOnClickListener {
            closeOverlay()
        }

        // Prevent clicks inside the top panel from closing the overlay
        panelContainer.setOnClickListener { /* consume */ }

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

        cardCalc.setOnClickListener { v ->
            performHaptic(v)
            currentMathResult?.let { copyMathResult(it) }
        }

        cardTopApp.setOnClickListener { v ->
            performHaptic(v)
            topMatchedApp?.let { launchApp(it.packageName) }
        }

        cardAskAi.setOnClickListener { v ->
            performHaptic(v)
            val rawQuery = editSearch.text?.toString()?.trim() ?: ""
            val bang = currentBangRoute
            if (bang != null) {
                executeBangRoute(bang)
            } else {
                dispatchToAiOrSearch(activeAiEngine, rawQuery)
            }
        }
    }

    private fun evaluateLiveQuery(rawQuery: String) {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) {
            topMatchedApp = null
            currentMathResult = null
            currentBangRoute = null
            cardCalc.visibility = View.GONE
            cardTopApp.visibility = View.GONE
            cardSecondaryApps.visibility = View.GONE
            cardAskAi.visibility = View.GONE
            return
        }

        // 1. Check !bang command
        val bang = QuickCalculator.parseBangCommand(trimmed)
        currentBangRoute = bang

        // 2. Check math calculator
        val mathRes = if (bang == null) QuickCalculator.evaluate(trimmed) else null
        currentMathResult = mathRes
        if (mathRes != null) {
            txtCalcResult.text = "= $mathRes"
            cardCalc.visibility = View.VISIBLE
        } else {
            cardCalc.visibility = View.GONE
        }

        // 3. Match installed applications via AppLaunchCache + AppSearchMatcher
        val candidates = AppLaunchCache.getCandidates(this)
        val matches = if (bang == null) {
            AppSearchMatcher.findMatches(candidates, trimmed, limit = 5)
        } else {
            emptyList()
        }

        val firstApp = matches.firstOrNull()
        topMatchedApp = firstApp

        if (firstApp != null) {
            cardTopApp.visibility = View.VISIBLE
            txtTopAppLabel.text = firstApp.label
            txtTopAppSub.text = getString(R.string.quick_top_app_hint)
            badgeTopAppEnter.visibility = View.VISIBLE
            val icon = AppLaunchCache.getAppIcon(this, firstApp.packageName)
            if (icon != null) {
                imgTopAppIcon.setImageDrawable(icon)
            } else {
                imgTopAppIcon.setImageResource(R.drawable.ic_browser)
            }

            // Populate secondary matches (#2..#5)
            val secondary = matches.drop(1)
            if (secondary.isNotEmpty()) {
                populateSecondaryApps(secondary)
                cardSecondaryApps.visibility = View.VISIBLE
            } else {
                cardSecondaryApps.visibility = View.GONE
            }
        } else {
            cardTopApp.visibility = View.GONE
            cardSecondaryApps.visibility = View.GONE
        }

        // 4. Always show direct AI / Web Search card below so user can ask AI or see Enter target
        cardAskAi.visibility = View.VISIBLE
        if (bang != null) {
            txtAskAiTitle.text = getString(R.string.quick_ask_ai_format, bang.displayTitle)
            txtAskAiQuery.text = if (bang.cleanQuery.isNotEmpty()) "«${bang.cleanQuery}»" else bang.prefix
            badgeAskAiEnter.visibility = View.VISIBLE
        } else {
            val engineName = getEngineDisplayName(activeAiEngine)
            txtAskAiTitle.text = getString(R.string.quick_ask_ai_format, engineName)
            txtAskAiQuery.text = "«$trimmed»"
            // Highlight Enter badge on Ask AI card when no app and no calc result is intercepting Enter
            badgeAskAiEnter.visibility = if (firstApp == null && mathRes == null) View.VISIBLE else View.GONE
        }
    }

    private fun populateSecondaryApps(secondary: List<AppSearchCandidate>) {
        listSecondaryApps.removeAllViews()
        val density = resources.displayMetrics.density
        val iconSizePx = (32 * density).roundToInt()
        val padH = (16 * density).roundToInt()
        val padV = (9 * density).roundToInt()

        for (candidate in secondary) {
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, padV, padH, padV)
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                isClickable = true
                isFocusable = true
            }

            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val drawable = AppLaunchCache.getAppIcon(this@QuickSearchActivity, candidate.packageName)
                if (drawable != null) setImageDrawable(drawable) else setImageResource(R.drawable.ic_browser)
            }

            val titleView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * density).roundToInt()
                }
                text = candidate.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }

            row.addView(iconView)
            row.addView(titleView)
            row.setOnClickListener { v ->
                performHaptic(v)
                launchApp(candidate.packageName)
            }
            listSecondaryApps.addView(row)
        }
    }

    private fun handleEnterSubmit() {
        performHaptic(editSearch)
        val rawQuery = editSearch.text?.toString()?.trim() ?: ""

        // 1. !bang command takes explicit priority
        val bang = currentBangRoute
        if (bang != null) {
            executeBangRoute(bang)
            return
        }

        // 2. Highlighted #1 app match launches immediately on Enter
        val matchedApp = topMatchedApp
        if (matchedApp != null) {
            launchApp(matchedApp.packageName)
            return
        }

        // 3. Math expression copies result on Enter
        val mathRes = currentMathResult
        if (mathRes != null) {
            copyMathResult(mathRes)
            return
        }

        // 4. Otherwise dispatch question/query (or empty tap) to active AI engine
        dispatchToAiOrSearch(activeAiEngine, rawQuery)
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
        try {
            startActivity(intent)
        } catch (_: Exception) {}
        closeOverlay()
    }

    private fun closeOverlay() {
        finish()
        suppressTransitions()
    }

    override fun finish() {
        super.finish()
        suppressTransitions()
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
