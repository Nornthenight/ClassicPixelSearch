package com.pixel.classicsearch

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * High-speed in-memory cache of launchable applications and their icons.
 * Eliminates PackageManager IPC latency during live typing in QuickSearchActivity
 * and Enter key interception in LauncherStateService.
 */
object AppLaunchCache {

    private const val CACHE_TTL_MS = 120_000L // 2 minutes TTL before background refresh

    @Volatile
    private var cachedCandidates: List<AppSearchCandidate> = emptyList()

    @Volatile
    private var lastUpdateRealtimeMs: Long = 0L

    @Volatile
    private var isRefreshing: Boolean = false

    private val iconCache = ConcurrentHashMap<String, Drawable>()
    private val bgExecutor = Executors.newSingleThreadExecutor()

    /**
     * Returns cached candidates immediately if available (triggering async refresh if stale),
     * or loads synchronously on cold start.
     */
    fun getCandidates(context: Context): List<AppSearchCandidate> {
        val current = cachedCandidates
        val now = SystemClock.elapsedRealtime()
        if (current.isNotEmpty()) {
            if (now - lastUpdateRealtimeMs > CACHE_TTL_MS) {
                warmUpAsync(context)
            }
            return current
        }
        return refreshSync(context.applicationContext)
    }

    /**
     * Preloads launchable applications in a background thread.
     */
    fun warmUpAsync(context: Context) {
        if (isRefreshing) return
        isRefreshing = true
        val appCtx = context.applicationContext
        bgExecutor.execute {
            try {
                refreshSync(appCtx)
            } finally {
                isRefreshing = false
            }
        }
    }

    @Synchronized
    fun refreshSync(context: Context): List<AppSearchCandidate> {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val list = resolveInfos
                .mapNotNull { info ->
                    val actInfo = info.activityInfo ?: return@mapNotNull null
                    val appInfo = actInfo.applicationInfo ?: return@mapNotNull null
                    if (!actInfo.enabled || !appInfo.enabled) return@mapNotNull null
                    val pkg = actInfo.packageName ?: return@mapNotNull null
                    val label = info.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: pkg
                    AppSearchCandidate(packageName = pkg, label = label)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }

            cachedCandidates = list
            lastUpdateRealtimeMs = SystemClock.elapsedRealtime()
            list
        } catch (_: Exception) {
            cachedCandidates
        }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        iconCache[packageName]?.let { return it }
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            iconCache[packageName] = drawable
            drawable
        } catch (_: Exception) {
            null
        }
    }
}
