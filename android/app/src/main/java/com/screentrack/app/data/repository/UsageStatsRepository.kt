package com.screentrack.app.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.screentrack.app.data.model.AppUsageInfo
import com.screentrack.app.data.model.DailyAnalyticsPoint
import com.screentrack.app.data.model.ScreenTimeSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed class PermissionState {
    object Granted : PermissionState()
    object Required : PermissionState()
    object Unavailable : PermissionState()
}

/**
 * Digital-Wellbeing-accurate screen-time repository.
 *
 * Key accuracy improvements over standard UsageStats queries:
 * 1. Tracks foreground intervals at Activity level (packageName + className) so internal
 *    Activity transitions (where Activity A logs ACTIVITY_PAUSED right after Activity B logs
 *    ACTIVITY_RESUMED) never prematurely terminate an ongoing app session.
 * 2. Looks back 2 hours prior to midnight (00:00) and clamps session start to 00:00 so apps
 *    already in use across midnight are counted accurately from 00:00.
 * 3. Excludes default Home Launchers, SystemUI, Lockscreen, PermissionController, and Keyboards
 *    just like Android's built-in Digital Wellbeing.
 * 4. Avoids merging raw queryUsageStats(INTERVAL_DAILY) buckets onto UsageEvents, eliminating
 *    yesterday's UTC bucket bleed-over.
 */
class UsageStatsRepository(private val context: Context) {

    private val appContext = context.applicationContext

    private val usageStatsManager: UsageStatsManager? by lazy {
        appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    private val powerManager: PowerManager? by lazy {
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    private val packageManager: PackageManager = appContext.packageManager

    companion object {
        private val appNameCache = ConcurrentHashMap<String, String>()
        @Volatile
        private var cachedExcludedPackages: Set<String>? = null
        private var lastExcludedCacheTime = 0L

        private val STATIC_SYSTEM_EXCLUDED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.android.wallpaper",
            "com.google.android.apps.wallpaper",
            "com.samsung.android.app.aodservice",
            "com.miui.aod",
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.android.launcher",
            "com.android.launcher3",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.oneplus.launcher",
            "com.huawei.android.launcher"
        )
    }

    /**
     * Checks whether the user has granted PACKAGE_USAGE_STATS permission safely across API 26-35.
     */
    fun checkPermissionState(): PermissionState {
        return try {
            val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return PermissionState.Unavailable

            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appContext.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appContext.packageName
                )
            }

            when (mode) {
                AppOpsManager.MODE_ALLOWED -> PermissionState.Granted
                AppOpsManager.MODE_DEFAULT -> {
                    // Some OEMs (Xiaomi/Samsung) return MODE_DEFAULT when granted via Settings; probe events
                    val now = System.currentTimeMillis()
                    val events = usageStatsManager?.queryEvents(now - 60_000L, now)
                    val stats = usageStatsManager?.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        now - 86_400_000L,
                        now
                    )
                    if ((events != null && events.hasNextEvent()) || !stats.isNullOrEmpty()) {
                        PermissionState.Granted
                    } else {
                        PermissionState.Required
                    }
                }
                else -> PermissionState.Required
            }
        } catch (e: Throwable) {
            PermissionState.Required
        }
    }

    fun createUsageAccessSettingsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Dynamically resolves Home Launchers and Input Method (Keyboard) packages to exclude them
     * just like Digital Wellbeing.
     */
    private fun getExcludedSystemPackages(): Set<String> {
        val now = System.currentTimeMillis()
        val existing = cachedExcludedPackages
        if (existing != null && (now - lastExcludedCacheTime) < 5 * 60_000L) {
            return existing
        }

        val excluded = HashSet<String>(STATIC_SYSTEM_EXCLUDED_PACKAGES)

        // 1. Exclude all installed Home Launchers
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = packageManager.queryIntentActivities(homeIntent, 0)
            for (info in resolveInfos) {
                val pkg = info.activityInfo?.packageName
                if (!pkg.isNullOrBlank()) {
                    excluded.add(pkg)
                }
            }
        } catch (_: Throwable) {}

        // 2. Exclude all enabled Input Methods (Keyboards)
        try {
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { imi ->
                if (!imi.packageName.isNullOrBlank()) {
                    excluded.add(imi.packageName)
                }
            }
        } catch (_: Throwable) {}

        cachedExcludedPackages = excluded
        lastExcludedCacheTime = now
        return excluded
    }

    private fun shouldExcludePackage(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        val excluded = getExcludedSystemPackages()
        if (packageName in excluded) return true
        if (packageName.endsWith(".launcher") || packageName.contains(".systemui")) return true
        return false
    }

    /**
     * Retrieves today's screen-time summary matching Digital Wellbeing.
     */
    suspend fun getTodayScreenTimeSummary(): ScreenTimeSummary = withContext(Dispatchers.IO) {
        val todayStr = getCurrentDateString()
        if (checkPermissionState() !is PermissionState.Granted || usageStatsManager == null) {
            return@withContext ScreenTimeSummary(
                date = todayStr,
                totalScreenTimeMs = 0L,
                apps = emptyList(),
                isPermissionGranted = false
            )
        }

        try {
            val now = System.currentTimeMillis()
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = calendar.timeInMillis

            val dayResult = parseDayUsageEvents(
                dayStartMs = startOfDay,
                dayEndMs = now,
                isToday = true
            )

            val appList = buildAppUsageList(dayResult.appDurations, dayResult.lastTimeUsedMap)
            val totalScreenTimeMs = appList.sumOf { it.usageDurationMs }
            val mostUsed = appList.firstOrNull()

            ScreenTimeSummary(
                date = todayStr,
                totalScreenTimeMs = totalScreenTimeMs,
                apps = appList,
                mostUsedAppName = mostUsed?.appName,
                mostUsedAppPackage = mostUsed?.packageName,
                currentForegroundApp = dayResult.currentActiveAppPkg?.let { getApplicationLabel(it) },
                currentForegroundPackage = dayResult.currentActiveAppPkg,
                isCurrentSessionActive = dayResult.isCurrentActive,
                lastObservedApp = dayResult.lastObservedPkg?.let { getApplicationLabel(it) },
                lastObservedPackage = dayResult.lastObservedPkg,
                currentSessionDurationMs = dayResult.activeSessionDurationMs,
                batteryLevel = getCurrentBatteryLevel(),
                lastSyncTimestampMs = now,
                isPermissionGranted = true
            )
        } catch (e: Throwable) {
            ScreenTimeSummary(
                date = todayStr,
                totalScreenTimeMs = 0L,
                apps = emptyList(),
                isPermissionGranted = true
            )
        }
    }

    data class DayEventsResult(
        val appDurations: Map<String, Long>,
        val lastTimeUsedMap: Map<String, Long>,
        val currentActiveAppPkg: String?,
        val lastObservedPkg: String?,
        val activeSessionDurationMs: Long,
        val isCurrentActive: Boolean
    )

    /**
     * Parses UsageEvents for a specific day [dayStartMs, dayEndMs] with a 2-hour lookback
     * before [dayStartMs] to accurately capture sessions crossing midnight.
     */
    private fun parseDayUsageEvents(
        dayStartMs: Long,
        dayEndMs: Long,
        isToday: Boolean
    ): DayEventsResult {
        val appDurations = HashMap<String, Long>()
        val lastTimeUsedMap = HashMap<String, Long>()

        val lookbackStart = (dayStartMs - 2 * 3600_000L).coerceAtLeast(0L)
        val events = try {
            usageStatsManager?.queryEvents(lookbackStart, dayEndMs)
        } catch (e: Throwable) {
            null
        } ?: return DayEventsResult(emptyMap(), emptyMap(), null, null, 0L, false)

        val event = UsageEvents.Event()
        var activePkg: String? = null
        var activeClass: String? = null
        var activeStartTime = 0L
        var lastObservedUserPkg: String? = null
        var isScreenInteractive = true

        fun flushActiveInterval(untilTimestamp: Long) {
            val pkg = activePkg ?: return
            if (activeStartTime <= 0L) return
            if (untilTimestamp > dayStartMs) {
                val clampedStart = activeStartTime.coerceAtLeast(dayStartMs)
                val duration = (untilTimestamp - clampedStart).coerceAtLeast(0L)
                // Sanity cap: single continuous session <= 16 hours
                if (duration in 1L..(16 * 3600_000L) && !shouldExcludePackage(pkg)) {
                    appDurations[pkg] = (appDurations[pkg] ?: 0L) + duration
                    lastTimeUsedMap[pkg] = untilTimestamp
                }
            }
        }

        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                val cls = event.className
                val ts = event.timeStamp

                when (event.eventType) {
                    1 -> { // UsageEvents.Event.ACTIVITY_RESUMED
                        // Close whatever activity was previously in the foreground
                        if (activePkg != null && activeStartTime > 0L) {
                            flushActiveInterval(ts)
                        }
                        activePkg = pkg
                        activeClass = cls
                        activeStartTime = ts
                        isScreenInteractive = true

                        if (!shouldExcludePackage(pkg) && ts >= dayStartMs) {
                            lastObservedUserPkg = pkg
                            lastTimeUsedMap[pkg] = ts
                        }
                    }

                    2, 23 -> { // UsageEvents.Event.ACTIVITY_PAUSED (2), ACTIVITY_STOPPED (23)
                        // Only close the foreground session if this exact activity is the active one!
                        // Prevents Activity A's delayed onPause from closing Activity B's active session.
                        if (activePkg == pkg && (activeClass == null || cls == null || activeClass == cls)) {
                            flushActiveInterval(ts)
                            activePkg = null
                            activeClass = null
                            activeStartTime = 0L
                        }
                    }

                    16, 17, 26 -> { // SCREEN_NON_INTERACTIVE (16), KEYGUARD_SHOWN (17), DEVICE_SHUTDOWN (26)
                        if (activePkg != null && activeStartTime > 0L) {
                            flushActiveInterval(ts)
                            activePkg = null
                            activeClass = null
                            activeStartTime = 0L
                        }
                        isScreenInteractive = false
                    }

                    15, 18 -> { // SCREEN_INTERACTIVE (15), KEYGUARD_HIDDEN (18)
                        isScreenInteractive = true
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // Handle ongoing foreground session at dayEndMs
        val isDeviceInteractiveNow = if (isToday) {
            try {
                powerManager?.isInteractive ?: true
            } catch (_: Throwable) {
                true
            }
        } else {
            false
        }

        var activeSessionDuration = 0L
        var isCurrentActive = false
        var currentActiveUserPkg: String? = null

        if (isToday && isDeviceInteractiveNow && isScreenInteractive && activePkg != null && activeStartTime > 0L) {
            val currentPkg = activePkg!!
            flushActiveInterval(dayEndMs)
            if (!shouldExcludePackage(currentPkg)) {
                activeSessionDuration = (dayEndMs - activeStartTime).coerceAtLeast(0L)
                isCurrentActive = true
                currentActiveUserPkg = currentPkg
                lastObservedUserPkg = currentPkg
            }
        } else if (!isToday && activePkg != null && activeStartTime > 0L && isScreenInteractive) {
            flushActiveInterval(dayEndMs)
        }

        return DayEventsResult(
            appDurations = appDurations,
            lastTimeUsedMap = lastTimeUsedMap,
            currentActiveAppPkg = currentActiveUserPkg,
            lastObservedPkg = lastObservedUserPkg,
            activeSessionDurationMs = activeSessionDuration,
            isCurrentActive = isCurrentActive
        )
    }

    private fun buildAppUsageList(
        appDurations: Map<String, Long>,
        lastTimeUsedMap: Map<String, Long>
    ): List<AppUsageInfo> {
        // Filter out micro-noise (< 2 seconds) just like Digital Wellbeing
        val validEntries = appDurations.filter { (pkg, duration) ->
            duration >= 2_000L && !shouldExcludePackage(pkg)
        }
        val totalMs = validEntries.values.sum().coerceAtLeast(1L)

        val list = ArrayList<AppUsageInfo>(validEntries.size)
        for ((pkg, duration) in validEntries) {
            val pct = ((duration.toDouble() / totalMs.toDouble()) * 100.0).toFloat()
            val safePct = if (pct.isNaN() || pct.isInfinite()) 0f else pct.coerceIn(0f, 100f)
            list.add(
                AppUsageInfo(
                    packageName = pkg,
                    appName = getApplicationLabel(pkg),
                    usageDurationMs = duration,
                    percentage = safePct,
                    lastTimeUsed = lastTimeUsedMap[pkg] ?: 0L,
                    category = determineCategory(pkg)
                )
            )
        }
        list.sortByDescending { it.usageDurationMs }
        return list
    }

    /**
     * Retrieves accurate 7-day daily history (including per-app breakdown for each day).
     * Only retains the last 7 days to keep memory and storage lightweight.
     */
    suspend fun getWeeklyAnalytics(): List<DailyAnalyticsPoint> = withContext(Dispatchers.IO) {
        if (checkPermissionState() !is PermissionState.Granted || usageStatsManager == null) {
            return@withContext emptyList()
        }

        val result = mutableListOf<DailyAnalyticsPoint>()
        val dayFormat = SimpleDateFormat("EEE", Locale.US)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = System.currentTimeMillis()

        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        for (i in 6 downTo 0) {
            val dayCal = (todayCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -i)
            }
            val startDay = dayCal.timeInMillis
            val endDay = if (i == 0) {
                now
            } else {
                (dayCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis - 1L
            }

            val dayResult = parseDayUsageEvents(
                dayStartMs = startDay,
                dayEndMs = endDay,
                isToday = (i == 0)
            )

            val dayApps = buildAppUsageList(dayResult.appDurations, dayResult.lastTimeUsedMap)
            val dayTotalMs = dayApps.sumOf { it.usageDurationMs }
            val mostUsed = dayApps.firstOrNull()?.appName

            result.add(
                DailyAnalyticsPoint(
                    date = dateFormat.format(Date(startDay)),
                    dayOfWeek = dayFormat.format(Date(startDay)),
                    totalScreenTimeMs = dayTotalMs,
                    mostUsedAppName = mostUsed,
                    appCount = dayApps.size,
                    apps = dayApps.take(15)
                )
            )
        }

        result
    }

    fun getCurrentBatteryLevel(): Int {
        return try {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level in 0..100) {
                level
            } else {
                val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val l = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val s = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (l >= 0 && s > 0) ((l * 100) / s).coerceIn(0, 100) else 100
            }
        } catch (_: Throwable) {
            100
        }
    }

    /**
     * Resolves human-readable application label from package name safely.
     */
    fun getApplicationLabel(packageName: String): String {
        if (packageName.isBlank()) return "Unknown App"
        appNameCache[packageName]?.let { return it }

        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                packageManager.getApplicationInfo(packageName, 0)
            }
            val rawLabel = packageManager.getApplicationLabel(appInfo).toString().trim()
            val label = if (rawLabel.isNotBlank()) rawLabel else fallbackAppName(packageName)
            appNameCache[packageName] = label
            label
        } catch (_: Throwable) {
            val clean = fallbackAppName(packageName)
            appNameCache[packageName] = clean
            clean
        }
    }

    private fun fallbackAppName(packageName: String): String {
        val knownMap = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.instagram.android" to "Instagram",
            "com.google.android.youtube" to "YouTube",
            "com.android.chrome" to "Chrome",
            "com.google.android.gm" to "Gmail",
            "com.spotify.music" to "Spotify",
            "org.telegram.messenger" to "Telegram",
            "com.facebook.katana" to "Facebook",
            "com.twitter.android" to "X (Twitter)",
            "com.snapchat.android" to "Snapchat",
            "com.netflix.mediaclient" to "Netflix",
            "com.google.android.apps.maps" to "Google Maps",
            "com.google.android.apps.photos" to "Google Photos",
            "com.android.settings" to "Settings",
            "com.google.android.dialer" to "Phone",
            "com.samsung.android.dialer" to "Phone",
            "com.google.android.apps.messaging" to "Messages"
        )
        knownMap[packageName]?.let { return it }
        val suffix = packageName.substringAfterLast('.').ifBlank { packageName }
        return suffix.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun determineCategory(packageName: String): String {
        val pkg = packageName.lowercase(Locale.ROOT)
        return when {
            pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox") || pkg.contains("opera") || pkg.contains("brave") -> "Browser"
            pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("spotify") || pkg.contains("hotstar") || pkg.contains("primevideo") || pkg.contains("vlc") -> "Entertainment"
            pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("instagram") || pkg.contains("twitter") || pkg.contains("facebook") || pkg.contains("snapchat") || pkg.contains("messaging") -> "Social & Chat"
            pkg.contains("gmail") || pkg.contains("mail") || pkg.contains("slack") || pkg.contains("docs") || pkg.contains("notion") || pkg.contains("teams") || pkg.contains("zoom") -> "Productivity"
            pkg.contains("game") || pkg.contains("pubg") || pkg.contains("bgmi") || pkg.contains("freefire") || pkg.contains("ludo") || pkg.contains("clash") -> "Gaming"
            pkg.contains("maps") || pkg.contains("uber") || pkg.contains("ola") || pkg.contains("rapido") || pkg.contains("zomato") || pkg.contains("swiggy") -> "Travel & Food"
            else -> "App"
        }
    }

    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
