package com.pixel.classicsearch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings

enum class DeepActionCategory {
    SETTINGS,
    APP_FUNCTION,
    GOOGLE_ECOSYSTEM
}

data class DeepSearchCandidate(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: DeepActionCategory,
    val packageName: String,
    val keywords: List<String>,
    val intentProvider: (Context, String) -> Intent
)

/**
 * Extended deep-search catalog covering:
 * 1. Android System Settings sub-pages (Wi-Fi, Bluetooth, Display, Battery, Sound, Storage, VPN, NFC, Developer Options, etc.)
 * 2. Installed App Functions / Shortcuts (Telegram Saved/New chat, Camera Selfie/Video, Clock Timer/Alarm, Browser Incognito, Play My Apps, etc.)
 * 3. Google Ecosystem Actions with live query handoff (Google Maps, Google Translate, Play Store, YouTube, Gmail, Drive, Calendar, Photos, Keep, Contacts)
 */
object DeepSearchCatalog {

    private val STATIC_CANDIDATES: List<DeepSearchCandidate> by lazy {
        buildStaticCatalog()
    }

    /**
     * Resolves parameterized command triggers (e.g. "перевод hello", "карта аптека", "маркет shazam",
     * "таймер 10", "будильник 07:30", "ютуб lofi") that should immediately become the primary active chip.
     */
    fun resolveParameterizedTrigger(context: Context, rawQuery: String): DeepSearchCandidate? {
        val trimmed = rawQuery.trim()
        if (trimmed.length < 2) return null
        val lower = trimmed.lowercase()

        // 1. Timer trigger: "таймер 10", "timer 5"
        val timerMatch = Regex("^(?:таймер|timer|засеки)\\s+(\\d{1,3})(?:\\s*(?:м|мин|min|m|с|сек|sec|s))?$", RegexOption.IGNORE_CASE)
            .find(lower)
        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toIntOrNull() ?: 5
            val isSeconds = lower.endsWith("с") || lower.endsWith("сек") || lower.endsWith("sec") || lower.endsWith("s")
            val seconds = if (isSeconds) amount else amount * 60
            val unitLabel = if (isSeconds) "$amount сек" else "$amount мин"
            return DeepSearchCandidate(
                id = "trigger_timer_$seconds",
                title = "Таймер: $unitLabel",
                subtitle = "Часы • Запустить сразу",
                category = DeepActionCategory.APP_FUNCTION,
                packageName = "com.google.android.deskclock",
                keywords = emptyList()
            ) { _, _ ->
                Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        // 2. Alarm trigger: "будильник 07:30", "alarm 8:00"
        val alarmMatch = Regex("^(?:будильник|alarm)\\s+(\\d{1,2})[:.\\s](\\d{2})$", RegexOption.IGNORE_CASE)
            .find(lower)
        if (alarmMatch != null) {
            val h = (alarmMatch.groupValues[1].toIntOrNull() ?: 7).coerceIn(0, 23)
            val m = (alarmMatch.groupValues[2].toIntOrNull() ?: 30).coerceIn(0, 59)
            val timeStr = String.format("%02d:%02d", h, m)
            return DeepSearchCandidate(
                id = "trigger_alarm_${h}_$m",
                title = "Будильник на $timeStr",
                subtitle = "Часы • Установить",
                category = DeepActionCategory.APP_FUNCTION,
                packageName = "com.google.android.deskclock",
                keywords = emptyList()
            ) { _, _ ->
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, h)
                    putExtra(AlarmClock.EXTRA_MINUTES, m)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        // 3. Google Translate trigger: "перевод ...", "переведи ...", "translate ...", "tr ..."
        val trPrefixes = listOf("перевод ", "переведи ", "перевести ", "translate ", "tr ")
        for (prefix in trPrefixes) {
            if (lower.startsWith(prefix) && trimmed.length > prefix.length) {
                val payload = trimmed.substring(prefix.length).trim()
                if (payload.isNotEmpty()) {
                    return DeepSearchCandidate(
                        id = "trigger_translate",
                        title = "Перевести «$payload»",
                        subtitle = "Google Переводчик",
                        category = DeepActionCategory.GOOGLE_ECOSYSTEM,
                        packageName = "com.google.android.apps.translate",
                        keywords = emptyList()
                    ) { _, _ ->
                        createGoogleTranslateIntent(payload)
                    }
                }
            }
        }

        // 4. Google Maps trigger: "карта ...", "карты ...", "маршрут ...", "где ...", "maps ...", "map "
        val mapPrefixes = listOf("карта ", "карты ", "маршрут ", "навигатор ", "где ", "maps ", "map ")
        for (prefix in mapPrefixes) {
            if (lower.startsWith(prefix) && trimmed.length > prefix.length) {
                val payload = trimmed.substring(prefix.length).trim()
                if (payload.isNotEmpty()) {
                    return DeepSearchCandidate(
                        id = "trigger_maps",
                        title = "На карте: «$payload»",
                        subtitle = "Google Карты • Навигация",
                        category = DeepActionCategory.GOOGLE_ECOSYSTEM,
                        packageName = "com.google.android.apps.maps",
                        keywords = emptyList()
                    ) { _, _ ->
                        createGoogleMapsIntent(payload)
                    }
                }
            }
        }

        // 5. Google Play Store trigger: "маркет ...", "плей ...", "скачать ...", "play ...", "market "
        val playPrefixes = listOf("маркет ", "плей ", "скачать ", "установить ", "play ", "market ")
        for (prefix in playPrefixes) {
            if (lower.startsWith(prefix) && trimmed.length > prefix.length) {
                val payload = trimmed.substring(prefix.length).trim()
                if (payload.isNotEmpty()) {
                    return DeepSearchCandidate(
                        id = "trigger_play",
                        title = "Play Маркет: «$payload»",
                        subtitle = "Поиск приложений и игр",
                        category = DeepActionCategory.GOOGLE_ECOSYSTEM,
                        packageName = "com.android.vending",
                        keywords = emptyList()
                    ) { _, _ ->
                        createPlayStoreSearchIntent(payload)
                    }
                }
            }
        }

        // 6. YouTube search trigger: "ютуб ...", "видео ...", "youtube ...", "yt "
        val ytPrefixes = listOf("ютуб ", "ютьюб ", "видео ", "youtube ", "yt ")
        for (prefix in ytPrefixes) {
            if (lower.startsWith(prefix) && trimmed.length > prefix.length) {
                val payload = trimmed.substring(prefix.length).trim()
                if (payload.isNotEmpty()) {
                    return DeepSearchCandidate(
                        id = "trigger_youtube",
                        title = "YouTube: «$payload»",
                        subtitle = "Поиск видео в YouTube",
                        category = DeepActionCategory.GOOGLE_ECOSYSTEM,
                        packageName = "com.google.android.youtube",
                        keywords = emptyList()
                    ) { _, _ ->
                        createYouTubeSearchIntent(payload)
                    }
                }
            }
        }

        return null
    }

    /**
     * Searches Android System Settings, App Shortcuts/Functions, and Google Ecosystem actions.
     */
    fun findDeepMatches(
        context: Context,
        rawQuery: String?,
        limit: Int = 4
    ): List<DeepSearchCandidate> {
        val query = rawQuery?.trim()?.lowercase() ?: return emptyList()
        if (query.isEmpty() || query.startsWith("!") || limit <= 0) return emptyList()

        val results = LinkedHashSet<DeepSearchCandidate>()

        // 1. Check parameterized trigger first (e.g. "перевод hello", "таймер 10")
        resolveParameterizedTrigger(context, rawQuery)?.let { results.add(it) }
        if (results.size >= limit) return results.take(limit)

        // Build query variants (including QWERTY <-> JCUKEN and phonetic transliteration from AppSearchMatcher)
        val variants = AppSearchMatcher.buildQueryVariants(query)

        // 2. Prefix match on candidate title or primary keyword
        for (v in variants) {
            for (cand in STATIC_CANDIDATES) {
                val titleLower = cand.title.lowercase()
                val matchesTitlePrefix = titleLower.startsWith(v) ||
                        titleLower.split(" ", "-", "/", "•", "(", ")").any { it.startsWith(v) }
                val matchesKeywordPrefix = cand.keywords.any { kw ->
                    kw.startsWith(v) || (v.length >= 3 && kw.contains(v))
                }
                if (matchesTitlePrefix || matchesKeywordPrefix) {
                    if (isCandidateAvailable(context, cand)) {
                        results.add(cand)
                        if (results.size >= limit) return results.take(limit)
                    }
                }
            }
        }

        return results.take(limit)
    }

    /**
     * Ecosystem quick-search targets shown on the right side of the dock for any live query
     * (alongside AI / Web search engines) so the user can one-tap dispatch the query into
     * YouTube, Google Maps, Google Translate, or Google Play Store.
     */
    fun getEcosystemQueryTargets(query: String): List<DeepSearchCandidate> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return listOf(
            DeepSearchCandidate(
                id = "eco_youtube",
                title = "YouTube",
                subtitle = "Видео «$q»",
                category = DeepActionCategory.GOOGLE_ECOSYSTEM,
                packageName = "com.google.android.youtube",
                keywords = emptyList()
            ) { _, liveQuery -> createYouTubeSearchIntent(liveQuery) },
            DeepSearchCandidate(
                id = "eco_maps",
                title = "Карты",
                subtitle = "Маршрут «$q»",
                category = DeepActionCategory.GOOGLE_ECOSYSTEM,
                packageName = "com.google.android.apps.maps",
                keywords = emptyList()
            ) { _, liveQuery -> createGoogleMapsIntent(liveQuery) },
            DeepSearchCandidate(
                id = "eco_translate",
                title = "Перевод",
                subtitle = "Перевести «$q»",
                category = DeepActionCategory.GOOGLE_ECOSYSTEM,
                packageName = "com.google.android.apps.translate",
                keywords = emptyList()
            ) { _, liveQuery -> createGoogleTranslateIntent(liveQuery) },
            DeepSearchCandidate(
                id = "eco_play",
                title = "Play Маркет",
                subtitle = "Приложения «$q»",
                category = DeepActionCategory.GOOGLE_ECOSYSTEM,
                packageName = "com.android.vending",
                keywords = emptyList()
            ) { _, liveQuery -> createPlayStoreSearchIntent(liveQuery) }
        )
    }

    private fun isCandidateAvailable(context: Context, candidate: DeepSearchCandidate): Boolean {
        if (candidate.category == DeepActionCategory.SETTINGS) return true
        // Google ecosystem items have web fallbacks, so they always work even if the app isn't installed
        if (candidate.category == DeepActionCategory.GOOGLE_ECOSYSTEM) return true
        return try {
            context.packageManager.getPackageInfo(candidate.packageName, 0)
            true
        } catch (_: Exception) {
            // Clock / Settings intents work via standard Android system actions
            candidate.packageName == "com.google.android.deskclock" ||
                    candidate.packageName == "com.android.settings"
        }
    }

    fun createYouTubeSearchIntent(query: String): Intent {
        val encoded = Uri.encode(query.trim())
        val uri = if (encoded.isNotEmpty()) {
            Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        } else {
            Uri.parse("https://www.youtube.com")
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createGoogleMapsIntent(query: String): Intent {
        val encoded = Uri.encode(query.trim())
        val uri = if (encoded.isNotEmpty()) {
            Uri.parse("geo:0,0?q=$encoded")
        } else {
            Uri.parse("https://maps.google.com")
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createGoogleTranslateIntent(query: String): Intent {
        val encoded = Uri.encode(query.trim())
        val uri = if (encoded.isNotEmpty()) {
            Uri.parse("https://translate.google.com/?sl=auto&tl=ru&text=$encoded&op=translate")
        } else {
            Uri.parse("https://translate.google.com")
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createPlayStoreSearchIntent(query: String): Intent {
        val encoded = Uri.encode(query.trim())
        val uri = if (encoded.isNotEmpty()) {
            Uri.parse("market://search?q=$encoded&c=apps")
        } else {
            Uri.parse("market:// home")
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun settingsIntent(action: String): (Context, String) -> Intent = { _, _ ->
        Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun buildStaticCatalog(): List<DeepSearchCandidate> {
        val list = mutableListOf<DeepSearchCandidate>()

        // =====================================================================
        // 1. DEEP ANDROID SYSTEM SETTINGS
        // =====================================================================
        list += DeepSearchCandidate(
            id = "set_wifi",
            title = "Wi-Fi и Интернет",
            subtitle = "Настройки • Сеть",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("wifi", "wi-fi", "вайфай", "вай-фай", "интернет", "сеть", "internet", "network"),
            intentProvider = settingsIntent(Settings.ACTION_WIFI_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_bluetooth",
            title = "Bluetooth",
            subtitle = "Настройки • Устройства",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("bluetooth", "блютуз", "блютус", "наушники", "устройства", "bt"),
            intentProvider = settingsIntent(Settings.ACTION_BLUETOOTH_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_display",
            title = "Экран и яркость",
            subtitle = "Настройки • Дисплей, 120 Гц, Тёмная тема",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("экран", "дисплей", "яркость", "темная тема", "display", "brightness", "dark", "герцовка"),
            intentProvider = settingsIntent(Settings.ACTION_DISPLAY_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_battery",
            title = "Батарея и заряд",
            subtitle = "Настройки • Энергосбережение",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("батарея", "аккумулятор", "заряд", "энергосбережение", "battery", "power"),
            intentProvider = settingsIntent(Intent.ACTION_POWER_USAGE_SUMMARY)
        )
        list += DeepSearchCandidate(
            id = "set_sound",
            title = "Звук и вибрация",
            subtitle = "Настройки • Громкость, мелодии",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("звук", "вибрация", "громкость", "мелодия", "рингтон", "sound", "volume", "audio"),
            intentProvider = settingsIntent(Settings.ACTION_SOUND_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_storage",
            title = "Хранилище и память",
            subtitle = "Настройки • Свободное место",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("память", "хранилище", "место", "диск", "очистка", "storage", "memory"),
            intentProvider = settingsIntent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_apps",
            title = "Все приложения",
            subtitle = "Настройки • Управление приложениями",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("приложения", "программы", "разрешения", "apps", "applications"),
            intentProvider = settingsIntent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_vpn",
            title = "VPN и прокси",
            subtitle = "Настройки • Безопасное подключение",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("vpn", "впн", "прокси", "proxy", "туннель"),
            intentProvider = settingsIntent(Settings.ACTION_VPN_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_nfc",
            title = "NFC и бесконтактная оплата",
            subtitle = "Настройки • Mir Pay / Google Pay",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("nfc", "нфс", "оплата", "карта", "бесконтактная", "pay"),
            intentProvider = settingsIntent(Settings.ACTION_NFC_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_hotspot",
            title = "Точка доступа и модем",
            subtitle = "Настройки • Раздача Wi-Fi",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("точка доступа", "модем", "раздача", "хотспот", "hotspot", "tethering"),
            intentProvider = settingsIntent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_accessibility",
            title = "Специальные возможности",
            subtitle = "Настройки • Сервисы доступности",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("спецвозможности", "специальные", "доступность", "accessibility"),
            intentProvider = settingsIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_dev",
            title = "Для разработчиков",
            subtitle = "Настройки • Отладка по USB, ADB",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("разработчик", "для разработчиков", "отладка", "adb", "usb", "developer"),
            intentProvider = settingsIntent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_location",
            title = "Геолокация (GPS)",
            subtitle = "Настройки • Местоположение",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("геолокация", "локация", "местоположение", "gps", "гпс", "location"),
            intentProvider = settingsIntent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_security",
            title = "Безопасность и пароль",
            subtitle = "Настройки • Отпечаток, блокировка",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("безопасность", "пароль", "отпечаток", "блокировка", "security", "biometric"),
            intentProvider = settingsIntent(Settings.ACTION_SECURITY_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_notifications",
            title = "Уведомления и Не беспокоить",
            subtitle = "Настройки • История уведомлений",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("уведомления", "не беспокоить", "пуши", "notifications", "dnd"),
            intentProvider = settingsIntent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_language",
            title = "Язык и ввод (Клавиатура)",
            subtitle = "Настройки • Gboard, языки системы",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("язык", "клавиатура", "ввод", "language", "keyboard", "gboard"),
            intentProvider = settingsIntent(Settings.ACTION_LOCALE_SETTINGS)
        )
        list += DeepSearchCandidate(
            id = "set_about",
            title = "О телефоне / Обновление системы",
            subtitle = "Настройки • Версия Android, сборка",
            category = DeepActionCategory.SETTINGS,
            packageName = "com.android.settings",
            keywords = listOf("о телефоне", "о планшете", "обновление", "версия", "android", "about", "update"),
            intentProvider = settingsIntent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        )

        // =====================================================================
        // 2. INSTALLED APP FUNCTIONS & DEEP SHORTCUTS
        // =====================================================================
        list += DeepSearchCandidate(
            id = "fn_clock_timer",
            title = "Таймер",
            subtitle = "Часы • Запустить таймер",
            category = DeepActionCategory.APP_FUNCTION,
            packageName = "com.google.android.deskclock",
            keywords = listOf("таймер", "засечь", "секундомер", "timer", "stopwatch")
        ) { _, _ ->
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "fn_clock_alarm",
            title = "Новый будильник",
            subtitle = "Часы • Установить будильник",
            category = DeepActionCategory.APP_FUNCTION,
            packageName = "com.google.android.deskclock",
            keywords = listOf("будильник", "разбуди", "утро", "alarm", "wake")
        ) { _, _ ->
            Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "fn_camera_selfie",
            title = "Камера • Фото / Селфи",
            subtitle = "Быстрый снимок",
            category = DeepActionCategory.APP_FUNCTION,
            packageName = "com.google.android.GoogleCamera",
            keywords = listOf("селфи", "сфоткать", "фотография", "снимок", "selfie", "photo")
        ) { _, _ ->
            Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "fn_camera_video",
            title = "Видеокамера",
            subtitle = "Камера • Запись видео",
            category = DeepActionCategory.APP_FUNCTION,
            packageName = "com.google.android.GoogleCamera",
            keywords = listOf("видеокамера", "запись видео", "снять видео", "record video")
        ) { _, _ ->
            Intent(android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "fn_tg_saved",
            title = "Избранное (Saved Messages)",
            subtitle = "Telegram • Личные заметки",
            category = DeepActionCategory.APP_FUNCTION,
            packageName = "org.telegram.messenger",
            keywords = listOf("избранное", "сохраненки", "saved", "saved messages", "заметки тг")
        ) { context, _ ->
            val launch = context.packageManager.getLaunchIntentForPackage("org.telegram.messenger")
            (launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve"))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "fn_play_updates",
            title = "Мои приложения и обновления",
            subtitle = "Google Play • Обновить приложения",
            category = DeepActionCategory.APP_FUNCTION,
            packageName = "com.android.vending",
            keywords = listOf("обновления", "мои приложения", "обновить приложения", "my apps", "updates")
        ) { _, _ ->
            Intent(Intent.ACTION_VIEW, Uri.parse("market://myapps")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "fn_gmail_compose",
            title = "Написать письмо",
            subtitle = "Gmail • Новое сообщение",
            category = DeepActionCategory.APP_FUNCTION,
            packageName = "com.google.android.gm",
            keywords = listOf("письмо", "написать письмо", "почта", "email", "compose", "mail")
        ) { _, _ ->
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "fn_calendar_event",
            title = "Новое событие / Встреча",
            subtitle = "Google Календарь • Создать",
            category = DeepActionCategory.APP_FUNCTION,
            packageName = "com.google.android.calendar",
            keywords = listOf("событие", "встреча", "напоминание", "календарь создать", "new event", "meeting")
        ) { _, _ ->
            Intent(Intent.ACTION_INSERT).apply {
                data = android.provider.CalendarContract.Events.CONTENT_URI
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "fn_widget_settings",
            title = "Настройки Classic Pixel Search",
            subtitle = "Кастомизация виджета и цветов",
            category = DeepActionCategory.APP_FUNCTION,
            packageName = "com.pixel.classicsearch",
            keywords = listOf("виджет", "настройки виджета", "classic search", "pixel search")
        ) { context, _ ->
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // =====================================================================
        // 3. GOOGLE ECOSYSTEM DEEP ACTIONS
        // =====================================================================
        list += DeepSearchCandidate(
            id = "goog_translate",
            title = "Google Переводчик",
            subtitle = "Экосистема Google • Перевод текста",
            category = DeepActionCategory.GOOGLE_ECOSYSTEM,
            packageName = "com.google.android.apps.translate",
            keywords = listOf("перевод", "переводчик", "перевести", "translate", "translator")
        ) { _, _ -> createGoogleTranslateIntent("") }

        list += DeepSearchCandidate(
            id = "goog_maps",
            title = "Google Карты",
            subtitle = "Экосистема Google • Навигатор и места",
            category = DeepActionCategory.GOOGLE_ECOSYSTEM,
            packageName = "com.google.android.apps.maps",
            keywords = listOf("карты", "карта", "маршрут", "навигатор", "пробки", "maps", "navigation")
        ) { _, _ -> createGoogleMapsIntent("") }

        list += DeepSearchCandidate(
            id = "goog_lens",
            title = "Google Объектив (Lens)",
            subtitle = "Экосистема Google • Поиск по камере и перевод",
            category = DeepActionCategory.GOOGLE_ECOSYSTEM,
            packageName = "com.google.ar.lens",
            keywords = listOf("объектив", "ленс", "поиск по фото", "камера перевод", "lens", "google lens")
        ) { context, _ ->
            Intent(context, SearchTapActivity::class.java).apply {
                putExtra(SearchTapActivity.EXTRA_TAP_TARGET, SearchTapActivity.TARGET_MIDDLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "goog_drive",
            title = "Google Диск (Drive)",
            subtitle = "Экосистема Google • Облачные файлы и документы",
            category = DeepActionCategory.GOOGLE_ECOSYSTEM,
            packageName = "com.google.android.apps.docs",
            keywords = listOf("гугл диск", "облако", "документы", "таблицы", "drive", "google drive", "docs")
        ) { context, _ ->
            val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.docs")
            (launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com"))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "goog_photos",
            title = "Google Фото",
            subtitle = "Экосистема Google • Галерея и альбомы",
            category = DeepActionCategory.GOOGLE_ECOSYSTEM,
            packageName = "com.google.android.apps.photos",
            keywords = listOf("гугл фото", "галерея", "альбомы", "снимки", "photos", "google photos", "gallery")
        ) { context, _ ->
            val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
            (launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com"))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "goog_keep",
            title = "Google Keep (Заметки)",
            subtitle = "Экосистема Google • Быстрые заметки и списки",
            category = DeepActionCategory.GOOGLE_ECOSYSTEM,
            packageName = "com.google.android.keep",
            keywords = listOf("заметки", "кип", "список покупок", "записи", "keep", "notes", "google keep")
        ) { context, _ ->
            val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.keep")
            (launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://keep.google.com"))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        list += DeepSearchCandidate(
            id = "goog_contacts",
            title = "Контакты и Телефон",
            subtitle = "Экосистема Google • Адресная книга",
            category = DeepActionCategory.GOOGLE_ECOSYSTEM,
            packageName = "com.google.android.contacts",
            keywords = listOf("контакты", "телефонная книга", "номера", "contacts", "phonebook")
        ) { _, _ ->
            Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return list
    }
}
