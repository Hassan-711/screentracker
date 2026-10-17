package com.screentrack.app.data.repository

import android.content.Context
import android.os.Build
import com.screentrack.app.data.model.AppUsageInfo
import com.screentrack.app.data.model.AuthorizedViewer
import com.screentrack.app.data.model.DailyAnalyticsPoint
import com.screentrack.app.data.model.DeviceSyncStatus
import com.screentrack.app.data.model.PairingCodeResult
import com.screentrack.app.data.model.PairingSubmitResult
import com.screentrack.app.data.model.RegisteredDevice
import com.screentrack.app.data.model.RemoteAppUsage
import com.screentrack.app.data.model.RemoteDailySummary
import com.screentrack.app.data.model.RemoteDeviceLiveState
import com.screentrack.app.data.model.ScreenTimeSummary
import com.screentrack.app.data.model.SharingRequest
import com.screentrack.app.data.model.UserSession
import com.screentrack.app.data.model.DeviceChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Lightweight, zero-crash Supabase REST & Auth client using standard Android HttpURLConnection.
 *
 * Advantages:
 * - Zero Ktor / multiplatform-settings startup crashes across Android 8–15
 * - Supports explicit PostgREST `on_conflict` upserts (`device_id,date` and `device_id,date,package_name`)
 * - Automatic JWT access_token refresh via stored refresh_token
 * - Full support for both Sender (Person A with 2+ phones) and Remote Viewer (Person B viewing 2+ phones)
 * - Automatic 7-day history cleanup in Supabase and local preferences
 */
class SupabaseRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("screentrack_prefs", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    val supabaseUrl: String
        get() = (prefs.getString("supabase_url", null) ?: com.screentrack.app.BuildConfig.SUPABASE_URL)
            .trim()
            .trimEnd('/')

    val supabaseAnonKey: String
        get() = (prefs.getString("supabase_anon_key", null) ?: com.screentrack.app.BuildConfig.SUPABASE_ANON_KEY)
            .trim()

    val isConfigured: Boolean
        get() {
            val url = supabaseUrl
            val key = supabaseAnonKey
            return url.isNotBlank() &&
                key.isNotBlank() &&
                !url.contains("your-project") &&
                url.startsWith("http")
        }

    fun updateSupabaseConfig(url: String, anonKey: String) {
        prefs.edit()
            .putString("supabase_url", url.trim().trimEnd('/'))
            .putString("supabase_anon_key", anonKey.trim())
            .apply()
    }

    // -------------------------------------------------------------
    // Device Identity & Custom Nickname (For Person A's 2+ Phones)
    // -------------------------------------------------------------

    fun getOrCreateDeviceId(): String {
        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrBlank()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    fun getDeviceName(): String {
        val custom = prefs.getString("custom_device_name", null)?.trim()
        if (!custom.isNullOrBlank()) return custom
        return "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
    }

    fun setDeviceName(customName: String) {
        val clean = customName.trim().ifBlank {
            "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
        }
        prefs.edit().putString("custom_device_name", clean).apply()
    }

    fun hasForegroundMonitorPreferenceSet(): Boolean {
        return prefs.contains("foreground_monitor_enabled")
    }

    fun isForegroundMonitorEnabled(): Boolean {
        return prefs.getBoolean("foreground_monitor_enabled", true)
    }

    fun setForegroundMonitorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("foreground_monitor_enabled", enabled).apply()
    }

    fun getSyncIntervalMinutes(): Int {
        return prefs.getInt("sync_interval_minutes", 15).coerceAtLeast(15)
    }

    fun setSyncIntervalMinutes(minutes: Int) {
        prefs.edit().putInt("sync_interval_minutes", minutes.coerceAtLeast(15)).apply()
    }

    // -------------------------------------------------------------
    // Authentication (GoTrue REST API with Auto Token Refresh)
    // -------------------------------------------------------------

    suspend fun signUp(email: String, pass: String): Result<UserSession> = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured. Open Settings -> Configure Supabase first."))
        }
        try {
            val payload = buildJsonObject {
                put("email", email.trim())
                put("password", pass)
            }
            val responseStr = executeHttp(
                method = "POST",
                endpoint = "/auth/v1/signup",
                body = payload.toString(),
                useAuthToken = false
            )
            val obj = json.parseToJsonElement(responseStr).jsonObject
            val accessToken = obj["access_token"]?.jsonPrimitive?.content
            val refreshToken = obj["refresh_token"]?.jsonPrimitive?.content
            val userObj = obj["user"]?.jsonObject ?: obj
            val userId = userObj["id"]?.jsonPrimitive?.content
            val userEmail = userObj["email"]?.jsonPrimitive?.content ?: email.trim()

            if (!accessToken.isNullOrBlank() && !userId.isNullOrBlank()) {
                saveAuthSession(userId, userEmail, accessToken, refreshToken ?: "")
                ensureDeviceRegistered()
                Result.success(UserSession(userId, userEmail, true))
            } else if (!userId.isNullOrBlank()) {
                // If email confirmation is turned OFF in Supabase, sign in directly; if ON, notify user
                val signInAttempt = signIn(email, pass)
                if (signInAttempt.isSuccess) {
                    signInAttempt
                } else {
                    Result.failure(Exception("Account created! Please confirm your email (or disable 'Confirm email' in Supabase Auth -> Providers -> Email) and sign in."))
                }
            } else {
                Result.failure(Exception("Registration failed. Please verify your Supabase URL and Anon Key."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signIn(email: String, pass: String): Result<UserSession> = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured. Open Settings -> Configure Supabase first."))
        }
        try {
            val payload = buildJsonObject {
                put("email", email.trim())
                put("password", pass)
            }
            val responseStr = executeHttp(
                method = "POST",
                endpoint = "/auth/v1/token?grant_type=password",
                body = payload.toString(),
                useAuthToken = false
            )
            val obj = json.parseToJsonElement(responseStr).jsonObject
            val accessToken = obj["access_token"]?.jsonPrimitive?.content
            val refreshToken = obj["refresh_token"]?.jsonPrimitive?.content
            val userObj = obj["user"]?.jsonObject
            val userId = userObj?.get("id")?.jsonPrimitive?.content

            if (!accessToken.isNullOrBlank() && !userId.isNullOrBlank()) {
                val userEmail = userObj["email"]?.jsonPrimitive?.content ?: email.trim()
                saveAuthSession(userId, userEmail, accessToken, refreshToken ?: "")
                ensureDeviceRegistered()
                Result.success(UserSession(userId, userEmail, true))
            } else {
                Result.failure(Exception("Invalid email or password"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("Supabase is not configured."))
        try {
            val payload = buildJsonObject { put("email", email.trim()) }
            executeHttp("POST", "/auth/v1/recover", body = payload.toString(), useAuthToken = false)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isConfigured && getAccessToken() != null) {
                executeHttp("POST", "/auth/v1/logout", body = null, useAuthToken = true)
            }
        } catch (_: Exception) {
        } finally {
            clearLocalUser()
        }
        Result.success(Unit)
    }

    fun getCurrentSession(): UserSession? {
        val uid = prefs.getString("user_id", null) ?: return null
        val token = prefs.getString("access_token", null)
        if (uid.isBlank() || token.isNullOrBlank()) return null
        val email = prefs.getString("user_email", "") ?: ""
        return UserSession(uid, email, true)
    }

    private fun saveAuthSession(userId: String, email: String, accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString("user_id", userId)
            .putString("user_email", email)
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putLong("token_saved_at", System.currentTimeMillis())
            .apply()
    }

    private fun getAccessToken(): String? {
        val token = prefs.getString("access_token", null) ?: return null
        val tokenSavedAt = prefs.getLong("token_saved_at", 0L)
        // If access token is older than 45 minutes, proactively refresh before hitting PostgREST
        if (System.currentTimeMillis() - tokenSavedAt > 45 * 60 * 1000L) {
            val refreshed = refreshTokenBlocking()
            if (refreshed) {
                return prefs.getString("access_token", null)
            }
        }
        return token
    }

    fun clearLocalUser() {
        prefs.edit()
            .remove("user_id")
            .remove("user_email")
            .remove("access_token")
            .remove("refresh_token")
            .remove("token_saved_at")
            .apply()
    }

    private fun refreshTokenBlocking(): Boolean {
        val refresh = prefs.getString("refresh_token", null)?.trim()
        if (refresh.isNullOrBlank() || !isConfigured) {
            clearLocalUser()
            return false
        }
        return try {
            val payload = buildJsonObject { put("refresh_token", refresh) }
            val responseStr = executeHttpInternal(
                method = "POST",
                endpoint = "/auth/v1/token?grant_type=refresh_token",
                body = payload.toString(),
                bearerToken = supabaseAnonKey,
                extraHeaders = emptyMap()
            )
            val obj = json.parseToJsonElement(responseStr).jsonObject
            val newAccess = obj["access_token"]?.jsonPrimitive?.content
            val newRefresh = obj["refresh_token"]?.jsonPrimitive?.content ?: refresh
            val userObj = obj["user"]?.jsonObject
            val userId = userObj?.get("id")?.jsonPrimitive?.content ?: prefs.getString("user_id", "") ?: ""
            val email = userObj?.get("email")?.jsonPrimitive?.content ?: prefs.getString("user_email", "") ?: ""
            if (!newAccess.isNullOrBlank() && userId.isNotBlank()) {
                saveAuthSession(userId, email, newAccess, newRefresh)
                true
            } else {
                clearLocalUser()
                false
            }
        } catch (_: Throwable) {
            clearLocalUser()
            false
        }
    }

    // -------------------------------------------------------------
    // Device Registration & Custom Nickname Sync
    // -------------------------------------------------------------

    suspend fun ensureDeviceRegistered(): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("Supabase is not configured"))
        val user = getCurrentSession() ?: return@withContext Result.failure(Exception("Not signed in"))
        val deviceId = getOrCreateDeviceId()

        try {
            val deviceName = getDeviceName()
            val deviceModel = Build.MODEL ?: "Android"
            val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val nowIso = getUtcIsoTimestamp()

            val payload = buildJsonObject {
                put("id", deviceId)
                put("owner_id", user.userId)
                put("device_name", deviceName)
                put("device_model", deviceModel)
                put("os_version", osVersion)
                put("is_active", true)
                put("sync_frequency_minutes", getSyncIntervalMinutes())
                put("last_sync_at", nowIso)
                put("updated_at", nowIso)
            }

            executeHttp(
                method = "POST",
                endpoint = "/rest/v1/registered_devices?on_conflict=id",
                body = payload.toString(),
                useAuthToken = true,
                extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
            )
            Result.success(deviceId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------
    // Live Heartbeat + Full Screen-Time + 7-Day History Sync
    // -------------------------------------------------------------

    /**
     * Pushes a lightweight live heartbeat (`device_sync_status` + `daily_screen_time_summaries`)
     * and upserts today's app breakdown + optional 7-day history, with automatic >7-day cleanup.
     */
    suspend fun syncScreenTimeSummary(
        summary: ScreenTimeSummary,
        weeklyHistory: List<DailyAnalyticsPoint> = emptyList()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("Supabase is not configured"))
        if (getCurrentSession() == null) return@withContext Result.failure(Exception("Not signed in"))

        val deviceId = getOrCreateDeviceId()
        val nowIso = getUtcIsoTimestamp()

        try {
            // 1. Ensure device row exists & update last_sync_at
            ensureDeviceRegistered()

            // 2. Upsert live heartbeat status FIRST so Remote Viewers see active app changes immediately
            val statusPayload = buildJsonObject {
                put("device_id", deviceId)
                put("current_foreground_app", summary.currentForegroundApp ?: "")
                put("current_foreground_package", summary.currentForegroundPackage ?: "")
                put("last_observed_app", summary.lastObservedApp ?: summary.currentForegroundApp ?: "")
                put("last_observed_package", summary.lastObservedPackage ?: summary.currentForegroundPackage ?: "")
                put("is_screen_on", summary.isCurrentSessionActive)
                put("current_session_duration_ms", summary.currentSessionDurationMs)
                put("battery_level", summary.batteryLevel)
                put("last_sync_success", true)
                put("last_heartbeat_at", nowIso)
                put("updated_at", nowIso)
            }

            executeHttp(
                method = "POST",
                endpoint = "/rest/v1/device_sync_status?on_conflict=device_id",
                body = statusPayload.toString(),
                useAuthToken = true,
                extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
            )

            // 3. Upsert today's daily screen-time summary using explicit on_conflict=device_id,date
            val dailyPayload = buildJsonObject {
                put("device_id", deviceId)
                put("date", summary.date)
                put("total_screen_time_ms", summary.totalScreenTimeMs)
                put("most_used_app_package", summary.mostUsedAppPackage ?: "")
                put("most_used_app_name", summary.mostUsedAppName ?: "")
                put("app_count", summary.apps.size)
                put("sync_timestamp", nowIso)
                put("updated_at", nowIso)
            }

            val dailyResp = executeHttp(
                method = "POST",
                endpoint = "/rest/v1/daily_screen_time_summaries?on_conflict=device_id,date",
                body = dailyPayload.toString(),
                useAuthToken = true,
                extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=representation")
            )

            val dailySummaryId = try {
                val arr = json.parseToJsonElement(dailyResp).jsonArray
                arr.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
            } catch (_: Throwable) {
                null
            }

            // 4. Batch-upsert today's top 25 apps in a SINGLE HTTP request with on_conflict=device_id,date,package_name
            val topApps = summary.apps.take(25)
            if (topApps.isNotEmpty()) {
                val appsArray = buildJsonArray {
                    for (app in topApps) {
                        add(
                            buildJsonObject {
                                if (!dailySummaryId.isNullOrBlank()) put("daily_summary_id", dailySummaryId)
                                put("device_id", deviceId)
                                put("date", summary.date)
                                put("package_name", app.packageName)
                                put("app_name", app.appName)
                                put("usage_duration_ms", app.usageDurationMs)
                                val safePct = if (app.percentage.isNaN() || app.percentage.isInfinite()) 0.0 else app.percentage.toDouble()
                                put("usage_percentage", safePct)
                                put("last_time_used_ms", app.lastTimeUsed)
                                if (app.lastTimeUsed > 0L) {
                                    put("last_time_used", formatIsoTimestamp(app.lastTimeUsed))
                                }
                                put("category", app.category)
                                put("updated_at", nowIso)
                            }
                        )
                    }
                }

                executeHttp(
                    method = "POST",
                    endpoint = "/rest/v1/app_usage_summaries?on_conflict=device_id,date,package_name",
                    body = appsArray.toString(),
                    useAuthToken = true,
                    extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
                )
            }

            // 5. Sync past 7 days of daily summaries & their top apps so Remote Viewers see 7-day history
            if (weeklyHistory.isNotEmpty()) {
                syncWeeklyHistoryInternal(deviceId, weeklyHistory, nowIso)
            }

            // 6. Run 7-day history cleanup periodically (every 6 hours) to keep DB lightweight
            val lastCleanup = prefs.getLong("last_cleanup_timestamp", 0L)
            if (System.currentTimeMillis() - lastCleanup > 6 * 3600_000L) {
                runSevenDayCleanup()
            }

            prefs.edit().putLong("last_successful_sync", System.currentTimeMillis()).apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun syncWeeklyHistoryInternal(
        deviceId: String,
        weeklyHistory: List<DailyAnalyticsPoint>,
        nowIso: String
    ) {
        try {
            val pastDays = weeklyHistory.filter { it.totalScreenTimeMs > 0L }
            if (pastDays.isEmpty()) return

            val dailyBatch = buildJsonArray {
                for (day in pastDays) {
                    add(
                        buildJsonObject {
                            put("device_id", deviceId)
                            put("date", day.date)
                            put("total_screen_time_ms", day.totalScreenTimeMs)
                            put("most_used_app_name", day.mostUsedAppName ?: "")
                            put("app_count", day.appCount)
                            put("sync_timestamp", nowIso)
                            put("updated_at", nowIso)
                        }
                    )
                }
            }

            executeHttp(
                method = "POST",
                endpoint = "/rest/v1/daily_screen_time_summaries?on_conflict=device_id,date",
                body = dailyBatch.toString(),
                useAuthToken = true,
                extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Deletes data older than 7 days in Supabase (`daily_screen_time_summaries` & `app_usage_summaries`).
     */
    suspend fun runSevenDayCleanup(): Result<Int> = withContext(Dispatchers.IO) {
        if (!isConfigured || getCurrentSession() == null) return@withContext Result.success(0)
        try {
            val cutoffDate = getSevenDaysAgoDateString()
            val deviceId = getOrCreateDeviceId()

            // 1. Call global RPC if installed
            try {
                executeHttp(
                    method = "POST",
                    endpoint = "/rest/v1/rpc/cleanup_old_screen_time_data",
                    body = "{}",
                    useAuthToken = true
                )
            } catch (_: Throwable) {
                // Fallback direct REST delete for this device's records older than 7 days
                executeHttp(
                    method = "DELETE",
                    endpoint = "/rest/v1/app_usage_summaries?device_id=eq.$deviceId&date=lt.$cutoffDate",
                    body = null,
                    useAuthToken = true
                )
                executeHttp(
                    method = "DELETE",
                    endpoint = "/rest/v1/daily_screen_time_summaries?device_id=eq.$deviceId&date=lt.$cutoffDate",
                    body = null,
                    useAuthToken = true
                )
            }

            prefs.edit().putLong("last_cleanup_timestamp", System.currentTimeMillis()).apply()
            Result.success(1)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getLastSyncTimestamp(): Long = prefs.getLong("last_successful_sync", 0L)

    // -------------------------------------------------------------
    // Owner Pairing Workflow (Person A generates 6-digit code per phone)
    // -------------------------------------------------------------

    suspend fun generatePairingCode(): Result<PairingCodeResult> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("Configure Supabase URL & Key in Settings first."))
        val session = getCurrentSession() ?: return@withContext Result.failure(IllegalStateException("Please Sign In first to generate a pairing code."))

        try {
            ensureDeviceRegistered()
            val deviceId = getOrCreateDeviceId()

            // 1. Try database RPC first
            try {
                val params = buildJsonObject { put("p_device_id", deviceId) }
                val responseStr = executeHttp(
                    method = "POST",
                    endpoint = "/rest/v1/rpc/generate_pairing_code",
                    body = params.toString(),
                    useAuthToken = true
                )
                val obj = json.parseToJsonElement(responseStr).jsonObject
                val code = obj["code"]?.jsonPrimitive?.content
                val expiresAt = obj["expires_at"]?.jsonPrimitive?.content
                val validity = obj["validity_seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 600

                if (!code.isNullOrBlank()) {
                    return@withContext Result.success(
                        PairingCodeResult(
                            success = true,
                            code = code,
                            expiresAt = expiresAt,
                            validitySeconds = validity
                        )
                    )
                }
            } catch (rpcEx: Exception) {
                if (rpcEx.message?.contains("expired", ignoreCase = true) == true ||
                    rpcEx.message?.contains("sign in", ignoreCase = true) == true
                ) {
                    throw rpcEx
                }
                // RPC failed or gen_random_bytes not found in DB: seamless fallback
            }

            // 2. Direct resilient fallback: Generate 6-digit code locally and insert into pairing_codes table
            generatePairingCodeFallback(deviceId, session.userId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generatePairingCodeFallback(deviceId: String, ownerId: String): Result<PairingCodeResult> {
        val chars = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        val secureRandom = java.security.SecureRandom()
        val codeBuilder = StringBuilder(6)
        for (i in 0 until 6) {
            codeBuilder.append(chars[secureRandom.nextInt(chars.length)])
        }
        val code = codeBuilder.toString()
        val nowMs = System.currentTimeMillis()
        val expiresAtMs = nowMs + 10 * 60_000L
        val expiresAtIso = formatIsoTimestamp(expiresAtMs)

        return try {
            try {
                executeHttp(
                    method = "PATCH",
                    endpoint = "/rest/v1/pairing_codes?device_id=eq.$deviceId&is_used=eq.false",
                    body = """{"is_used":true}""",
                    useAuthToken = true
                )
            } catch (_: Throwable) {
            }

            val payload = buildJsonObject {
                put("device_id", deviceId)
                put("owner_id", ownerId)
                put("code", code)
                put("expires_at", expiresAtIso)
                put("is_used", false)
                put("attempts_count", 0)
            }

            val insertResp = executeHttp(
                method = "POST",
                endpoint = "/rest/v1/pairing_codes",
                body = payload.toString(),
                useAuthToken = true,
                extraHeaders = mapOf("Prefer" to "return=representation")
            )

            val arr = json.parseToJsonElement(insertResp).jsonArray
            val newId = arr.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content

            Result.success(
                PairingCodeResult(
                    success = true,
                    pairingCodeId = newId,
                    code = code,
                    expiresAt = expiresAtIso,
                    validitySeconds = 600
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPendingSharingRequests(): Result<List<SharingRequest>> = withContext(Dispatchers.IO) {
        if (!isConfigured || getCurrentSession() == null) return@withContext Result.success(emptyList())
        val deviceId = getOrCreateDeviceId()
        try {
            val responseStr = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/device_sharing_requests?device_id=eq.$deviceId&status=eq.pending&order=requested_at.desc",
                body = null,
                useAuthToken = true
            )
            val list = json.decodeFromString<List<SharingRequest>>(responseStr)
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun respondToSharingRequest(requestId: String, approved: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured || getCurrentSession() == null) return@withContext Result.failure(IllegalStateException("Not authenticated"))
        try {
            val params = buildJsonObject {
                put("p_request_id", requestId)
                put("p_approved", approved)
            }
            executeHttp(
                method = "POST",
                endpoint = "/rest/v1/rpc/respond_to_pairing_request",
                body = params.toString(),
                useAuthToken = true
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAuthorizedViewers(): Result<List<AuthorizedViewer>> = withContext(Dispatchers.IO) {
        if (!isConfigured || getCurrentSession() == null) return@withContext Result.success(emptyList())
        val deviceId = getOrCreateDeviceId()
        try {
            val responseStr = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/authorized_device_viewers?device_id=eq.$deviceId&status=eq.active&order=granted_at.desc",
                body = null,
                useAuthToken = true
            )
            val list = json.decodeFromString<List<AuthorizedViewer>>(responseStr)
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revokeViewer(viewerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured || getCurrentSession() == null) return@withContext Result.failure(IllegalStateException("Not authenticated"))
        val deviceId = getOrCreateDeviceId()
        try {
            val params = buildJsonObject {
                put("p_device_id", deviceId)
                put("p_viewer_id", viewerId)
            }
            executeHttp(
                method = "POST",
                endpoint = "/rest/v1/rpc/revoke_viewer_access",
                body = params.toString(),
                useAuthToken = true
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------
    // Remote Viewer Workflow (Person B pairs & views 2+ phones inside Android app)
    // -------------------------------------------------------------

    /**
     * Submits a 6-digit pairing code from Person B's Android app to request access to one of Person A's phones.
     */
    suspend fun submitPairingCode(code: String): Result<PairingSubmitResult> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("Configure Supabase in Settings first."))
        if (getCurrentSession() == null) return@withContext Result.failure(IllegalStateException("Please Sign In first before entering a pairing code."))

        val cleanCode = code.trim().uppercase(Locale.ROOT)
        if (cleanCode.length != 6) {
            return@withContext Result.failure(IllegalArgumentException("Pairing code must be exactly 6 characters."))
        }

        try {
            val params = buildJsonObject {
                put("p_code", cleanCode)
            }
            val responseStr = executeHttp(
                method = "POST",
                endpoint = "/rest/v1/rpc/submit_pairing_request",
                body = params.toString(),
                useAuthToken = true
            )
            val result = json.decodeFromString<PairingSubmitResult>(responseStr)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes a tracked device from Person B's Remote View, un-pairs it in Supabase, and stores it in local prefs.
     */
    suspend fun removeTrackedPhone(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val removedSet = prefs.getStringSet("locally_removed_remote_devices", emptySet())?.toMutableSet() ?: mutableSetOf()
            removedSet.add(deviceId)
            prefs.edit().putStringSet("locally_removed_remote_devices", removedSet).apply()

            val session = getCurrentSession()
            if (isConfigured && session != null) {
                try {
                    val params = buildJsonObject { put("p_device_id", deviceId) }
                    executeHttp(
                        method = "POST",
                        endpoint = "/rest/v1/rpc/unpair_remote_device",
                        body = params.toString(),
                        useAuthToken = true
                    )
                } catch (_: Throwable) {
                    try {
                        executeHttp(
                            method = "DELETE",
                            endpoint = "/rest/v1/authorized_device_viewers?device_id=eq.$deviceId&viewer_id=eq.${session.userId}",
                            body = null,
                            useAuthToken = true
                        )
                    } catch (_: Throwable) {}
                    try {
                        executeHttp(
                            method = "DELETE",
                            endpoint = "/rest/v1/device_sharing_requests?device_id=eq.$deviceId&viewer_id=eq.${session.userId}",
                            body = null,
                            useAuthToken = true
                        )
                    } catch (_: Throwable) {}
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches outgoing pending pairing requests sent by Person B so they can see approval status.
     */
    suspend fun getOutgoingPendingRequests(): Result<List<SharingRequest>> = withContext(Dispatchers.IO) {
        val session = getCurrentSession() ?: return@withContext Result.success(emptyList())
        if (!isConfigured) return@withContext Result.success(emptyList())
        try {
            val responseStr = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/device_sharing_requests?viewer_id=eq.${session.userId}&status=eq.pending&order=requested_at.desc",
                body = null,
                useAuthToken = true
            )
            val list = json.decodeFromString<List<SharingRequest>>(responseStr)
            Result.success(list)
        } catch (_: Exception) {
            Result.success(emptyList())
        }
    }

    /**
     * Fetches ALL remote phones that Person B is authorized to view (supports 2 or more phones),
     * including each phone's live foreground app, session timer, today's app breakdown, and 7-day history.
     */
    suspend fun getRemoteDevicesLiveStates(): Result<List<RemoteDeviceLiveState>> = withContext(Dispatchers.IO) {
        val session = getCurrentSession() ?: return@withContext Result.failure(Exception("Sign in required"))
        if (!isConfigured) return@withContext Result.failure(Exception("Supabase not configured"))

        try {
            val localDeviceId = getOrCreateDeviceId()
            val removedSet = prefs.getStringSet("locally_removed_remote_devices", emptySet()) ?: emptySet()

            // 1. Get device IDs explicitly shared with this viewer
            val authResp = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/authorized_device_viewers?viewer_id=eq.${session.userId}&status=eq.active&select=device_id",
                body = null,
                useAuthToken = true
            )
            val authArr = json.parseToJsonElement(authResp).jsonArray
            val authorizedIds = authArr.mapNotNull { it.jsonObject["device_id"]?.jsonPrimitive?.content }.toMutableSet()

            // 2. Also include any other devices owned by the same user account (except this physical device if other devices exist)
            val ownedResp = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/registered_devices?owner_id=eq.${session.userId}&select=id",
                body = null,
                useAuthToken = true
            )
            val ownedArr = json.parseToJsonElement(ownedResp).jsonArray
            ownedArr.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                .filter { it != localDeviceId }
                .forEach { authorizedIds.add(it) }

            // Filter out any devices the viewer explicitly removed
            val filteredAuthorizedIds = authorizedIds.filter { it !in removedSet }

            if (filteredAuthorizedIds.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val inClause = filteredAuthorizedIds.joinToString(",")
            val devicesResp = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/registered_devices?id=in.($inClause)&order=updated_at.desc",
                body = null,
                useAuthToken = true
            )
            val devices = json.decodeFromString<List<RegisteredDevice>>(devicesResp)
            if (devices.isEmpty()) return@withContext Result.success(emptyList())

            val todayStr = getCurrentDateString()
            val sevenDaysAgoStr = getSevenDaysAgoDateString()

            // Fetch live statuses, 7-day daily summaries, and 7-day app usage for all remote phones in bulk!
            val statusResp = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/device_sync_status?device_id=in.($inClause)",
                body = null,
                useAuthToken = true
            )
            val statusMap = json.decodeFromString<List<DeviceSyncStatus>>(statusResp).associateBy { it.deviceId }

            val summariesResp = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/daily_screen_time_summaries?device_id=in.($inClause)&date=gte.$sevenDaysAgoStr&order=date.asc",
                body = null,
                useAuthToken = true
            )
            val allSummaries = json.decodeFromString<List<RemoteDailySummary>>(summariesResp)
            val summariesByDevice = allSummaries.groupBy { it.deviceId }

            val appsResp = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/app_usage_summaries?device_id=in.($inClause)&date=gte.$sevenDaysAgoStr&order=usage_duration_ms.desc",
                body = null,
                useAuthToken = true
            )
            val allApps = json.decodeFromString<List<RemoteAppUsage>>(appsResp)
            val appsByDeviceAndDate = allApps.groupBy { "${it.deviceId}_${it.date}" }

            val dayOfWeekFormat = SimpleDateFormat("EEE", Locale.US)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            val resultStates = devices.map { dev ->
                val status = statusMap[dev.id]
                val devSummaries = summariesByDevice[dev.id].orEmpty().associateBy { it.date }
                val todaySummary = devSummaries[todayStr]
                val todayApps = appsByDeviceAndDate["${dev.id}_$todayStr"]
                    .orEmpty()
                    .map { it.toAppUsageInfo() }
                    .sortedByDescending { it.usageDurationMs }

                // Build 7-day history points for this remote phone
                val historyPoints = mutableListOf<DailyAnalyticsPoint>()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                for (i in 6 downTo 0) {
                    val dCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }
                    val dStr = dateFormat.format(dCal.time)
                    val dName = dayOfWeekFormat.format(dCal.time)
                    val s = devSummaries[dStr]
                    val dayApps = appsByDeviceAndDate["${dev.id}_$dStr"]
                        .orEmpty()
                        .map { it.toAppUsageInfo() }
                        .sortedByDescending { it.usageDurationMs }
                    val totalMs = s?.totalScreenTimeMs ?: dayApps.sumOf { it.usageDurationMs }
                    historyPoints.add(
                        DailyAnalyticsPoint(
                            date = dStr,
                            dayOfWeek = dName,
                            totalScreenTimeMs = totalMs,
                            mostUsedAppName = s?.mostUsedAppName ?: dayApps.firstOrNull()?.appName,
                            appCount = s?.appCount ?: dayApps.size,
                            apps = dayApps
                        )
                    )
                }

                val (isStale, lastSeenLabel) = evaluateHeartbeatFreshness(status?.lastHeartbeatAt ?: dev.lastSyncAt)

                RemoteDeviceLiveState(
                    device = dev,
                    status = status,
                    todaySummary = todaySummary,
                    todayApps = todayApps,
                    sevenDayHistory = historyPoints,
                    isStale = isStale,
                    lastSeenLabel = lastSeenLabel
                )
            }

            Result.success(resultStates)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun evaluateHeartbeatFreshness(isoTimestamp: String?): Pair<Boolean, String> {
        if (isoTimestamp.isNullOrBlank()) return Pair(true, "Awaiting first sync")
        return try {
            val clean = isoTimestamp.replace("Z", "+0000")
                .replace(Regex("\\.\\d+"), "")
                .replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            val parsed = sdf.parse(clean) ?: return Pair(false, "Recently synced")
            val diffMs = (System.currentTimeMillis() - parsed.time).coerceAtLeast(0L)
            val diffSec = diffMs / 1000L
            val diffMin = diffSec / 60L
            val label = when {
                diffSec < 30 -> "Just now (${diffSec}s ago)"
                diffMin < 1 -> "${diffSec}s ago"
                diffMin < 60 -> "${diffMin}m ago"
                else -> SimpleDateFormat("hh:mm a", Locale.getDefault()).format(parsed)
            }
            Pair(diffMin > 15, label)
        } catch (_: Throwable) {
            Pair(false, "Synced")
        }
    }

    // -------------------------------------------------------------
    // Inter-Device Messaging (Person A & Person B Chat)
    // -------------------------------------------------------------

    /**
     * Retrieves messages exchanged between the device owner and authorized viewers within the last 24 hours.
     * Older messages automatically disappear after 24 hours to keep database storage lightweight and private.
     */
    suspend fun getDeviceMessages(deviceId: String): Result<List<DeviceChatMessage>> = withContext(Dispatchers.IO) {
        if (!isConfigured || getCurrentSession() == null) return@withContext Result.success(emptyList())
        try {
            val cutoffMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
            val cutoffIso = formatIsoTimestamp(cutoffMs)
            val responseStr = executeHttp(
                method = "GET",
                endpoint = "/rest/v1/device_messages?device_id=eq.$deviceId&created_at=gte.$cutoffIso&order=created_at.asc&limit=60",
                body = null,
                useAuthToken = true
            )
            val list = json.decodeFromString<List<DeviceChatMessage>>(responseStr)
            Result.success(list.filterNot { it.isExpired24Hours })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sends a message associated with a paired device and purges any expired messages older than 24 hours.
     */
    suspend fun sendDeviceMessage(
        deviceId: String,
        messageText: String,
        senderRole: String = "viewer"
    ): Result<DeviceChatMessage> = withContext(Dispatchers.IO) {
        val session = getCurrentSession() ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("Backend not configured"))
        val trimmed = messageText.trim()
        if (trimmed.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Message cannot be empty"))

        try {
            // Proactively purge any expired messages (>24h) for this device to clean storage
            try {
                val cutoffIso = formatIsoTimestamp(System.currentTimeMillis() - (24 * 60 * 60 * 1000L))
                executeHttp(
                    method = "DELETE",
                    endpoint = "/rest/v1/device_messages?device_id=eq.$deviceId&created_at=lt.$cutoffIso",
                    body = null,
                    useAuthToken = true
                )
            } catch (_: Throwable) {
            }

            val body = buildJsonObject {
                put("device_id", deviceId)
                put("sender_id", session.userId)
                put("sender_email", session.email)
                put("sender_role", senderRole)
                put("message_text", trimmed)
            }.toString()

            val responseStr = executeHttp(
                method = "POST",
                endpoint = "/rest/v1/device_messages",
                body = body,
                useAuthToken = true,
                extraHeaders = mapOf("Prefer" to "return=representation")
            )
            val list = json.decodeFromString<List<DeviceChatMessage>>(responseStr)
            val msg = list.firstOrNull() ?: throw Exception("Failed to send message")
            Result.success(msg)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------
    // App Theme Preference
    // -------------------------------------------------------------

    fun getThemeMode(): String = prefs.getString("app_theme_mode", "clay_light") ?: "clay_light"

    fun setThemeMode(mode: String) {
        prefs.edit().putString("app_theme_mode", mode).apply()
    }

    // -------------------------------------------------------------
    // Low-Level HTTP Execution with Automatic Token Retry
    // -------------------------------------------------------------

    private fun executeHttp(
        method: String,
        endpoint: String,
        body: String?,
        useAuthToken: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val token = if (useAuthToken) (getAccessToken() ?: supabaseAnonKey) else supabaseAnonKey
        return try {
            executeHttpInternal(method, endpoint, body, token, extraHeaders)
        } catch (e: HttpStatusException) {
            val isAuthError = e.code == 401 || e.code == 403 ||
                e.errorBody.contains("jwt", ignoreCase = true) ||
                e.errorBody.contains("token", ignoreCase = true)

            if (useAuthToken && isAuthError && refreshTokenBlocking()) {
                val refreshedToken = getAccessToken() ?: supabaseAnonKey
                executeHttpInternal(method, endpoint, body, refreshedToken, extraHeaders)
            } else {
                if (isAuthError) {
                    clearLocalUser()
                    throw Exception("Login session expired. Please sign in again.")
                }
                throw Exception(parseFriendlyErrorMessage(e.errorBody, e.code))
            }
        }
    }

    private class HttpStatusException(val code: Int, val errorBody: String) : Exception(errorBody)

    private fun executeHttpInternal(
        method: String,
        endpoint: String,
        body: String?,
        bearerToken: String,
        extraHeaders: Map<String, String>
    ): String {
        val fullUrl = URL("$supabaseUrl$endpoint")
        val conn = (fullUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("apikey", supabaseAnonKey)
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
            if (body != null && (method == "POST" || method == "PATCH" || method == "PUT")) {
                doOutput = true
            }
        }

        try {
            if (body != null && (method == "POST" || method == "PATCH" || method == "PUT")) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else {
                ""
            }

            if (code !in 200..299) {
                throw HttpStatusException(code, responseText)
            }
            return responseText
        } finally {
            conn.disconnect()
        }
    }

    private fun parseFriendlyErrorMessage(rawBody: String, statusCode: Int): String {
        if (rawBody.contains("jwt expired", ignoreCase = true) || rawBody.contains("token is expired", ignoreCase = true)) {
            clearLocalUser()
            return "Login session expired. Please sign in again."
        }
        if (rawBody.isBlank()) return "Server error (HTTP $statusCode)"
        return try {
            val obj = json.parseToJsonElement(rawBody).jsonObject
            val msg = obj["message"]?.jsonPrimitive?.content
                ?: obj["msg"]?.jsonPrimitive?.content
                ?: obj["error_description"]?.jsonPrimitive?.content
                ?: obj["error"]?.jsonPrimitive?.content
                ?: "Request failed (HTTP $statusCode)"
            if (msg.contains("jwt expired", ignoreCase = true)) {
                clearLocalUser()
                "Login session expired. Please sign in again."
            } else {
                msg
            }
        } catch (_: Throwable) {
            rawBody.take(160)
        }
    }

    private fun getUtcIsoTimestamp(): String {
        return formatIsoTimestamp(System.currentTimeMillis())
    }

    private fun formatIsoTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(epochMs))
    }

    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun getSevenDaysAgoDateString(): String {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -7)
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }
}
