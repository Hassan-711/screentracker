package com.screentrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val usageDurationMs: Long,
    val percentage: Float = 0f,
    val lastTimeUsed: Long = 0L,
    val category: String = "Application"
) {
    val formattedDuration: String
        get() = formatDuration(usageDurationMs)

    val formattedLastUsed: String
        get() = if (lastTimeUsed > 0L) {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(lastTimeUsed))
        } else {
            ""
        }

    val safeProgress: Float
        get() = if (percentage.isNaN() || percentage.isInfinite()) 0f else (percentage / 100f).coerceIn(0f, 1f)

    companion object {
        fun formatDuration(durationMs: Long): String {
            if (durationMs <= 0L) return "0s"
            val totalSeconds = durationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}

@Serializable
data class ScreenTimeSummary(
    val date: String, // YYYY-MM-DD
    val totalScreenTimeMs: Long,
    val apps: List<AppUsageInfo> = emptyList(),
    val mostUsedAppName: String? = null,
    val mostUsedAppPackage: String? = null,
    val currentForegroundApp: String? = null,
    val currentForegroundPackage: String? = null,
    val isCurrentSessionActive: Boolean = false,
    val lastObservedApp: String? = null,
    val lastObservedPackage: String? = null,
    val currentSessionDurationMs: Long = 0L,
    val batteryLevel: Int = 100,
    val lastSyncTimestampMs: Long = 0L,
    val isPermissionGranted: Boolean = false
) {
    val formattedTotalScreenTime: String
        get() = AppUsageInfo.formatDuration(totalScreenTimeMs)

    val formattedCurrentSession: String
        get() = AppUsageInfo.formatDuration(currentSessionDurationMs)
}

@Serializable
data class DailyAnalyticsPoint(
    val date: String,
    val dayOfWeek: String,
    val totalScreenTimeMs: Long,
    val mostUsedAppName: String? = null,
    val appCount: Int = 0,
    val apps: List<AppUsageInfo> = emptyList()
) {
    val formattedDuration: String
        get() = AppUsageInfo.formatDuration(totalScreenTimeMs)
}

@Serializable
data class RegisteredDevice(
    val id: String,
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("device_name") val deviceName: String = "Android Phone",
    @SerialName("device_model") val deviceModel: String = "",
    @SerialName("os_version") val osVersion: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("sync_frequency_minutes") val syncFrequencyMinutes: Int = 15,
    @SerialName("last_sync_at") val lastSyncAt: String? = null
)

@Serializable
data class DeviceSyncStatus(
    @SerialName("device_id") val deviceId: String,
    @SerialName("current_foreground_app") val currentForegroundApp: String? = null,
    @SerialName("current_foreground_package") val currentForegroundPackage: String? = null,
    @SerialName("last_observed_app") val lastObservedApp: String? = null,
    @SerialName("last_observed_package") val lastObservedPackage: String? = null,
    @SerialName("current_session_duration_ms") val currentSessionDurationMs: Long = 0L,
    @SerialName("is_screen_on") val isScreenOn: Boolean = false,
    @SerialName("battery_level") val batteryLevel: Int? = null,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: String? = null
)

@Serializable
data class RemoteDailySummary(
    val id: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val date: String = "",
    @SerialName("total_screen_time_ms") val totalScreenTimeMs: Long = 0L,
    @SerialName("most_used_app_package") val mostUsedAppPackage: String? = null,
    @SerialName("most_used_app_name") val mostUsedAppName: String? = null,
    @SerialName("app_count") val appCount: Int = 0,
    @SerialName("sync_timestamp") val syncTimestamp: String? = null
)

@Serializable
data class RemoteAppUsage(
    val id: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val date: String = "",
    @SerialName("package_name") val packageName: String = "",
    @SerialName("app_name") val appName: String = "",
    @SerialName("usage_duration_ms") val usageDurationMs: Long = 0L,
    @SerialName("usage_percentage") val usagePercentage: Float = 0f,
    @SerialName("last_time_used_ms") val lastTimeUsedMs: Long = 0L,
    val category: String = "Application"
) {
    fun toAppUsageInfo(): AppUsageInfo = AppUsageInfo(
        packageName = packageName,
        appName = appName,
        usageDurationMs = usageDurationMs,
        percentage = if (usagePercentage.isNaN() || usagePercentage.isInfinite()) 0f else usagePercentage,
        lastTimeUsed = lastTimeUsedMs,
        category = category
    )
}

data class RemoteDeviceLiveState(
    val device: RegisteredDevice,
    val status: DeviceSyncStatus?,
    val todaySummary: RemoteDailySummary?,
    val todayApps: List<AppUsageInfo>,
    val sevenDayHistory: List<DailyAnalyticsPoint>,
    val isStale: Boolean,
    val lastSeenLabel: String
)

@Serializable
data class PairingCodeResult(
    val success: Boolean,
    @SerialName("pairing_code_id") val pairingCodeId: String? = null,
    val code: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("validity_seconds") val validitySeconds: Int = 600
)

@Serializable
data class PairingSubmitResult(
    val success: Boolean = false,
    val status: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
    val message: String = ""
)

@Serializable
data class SharingRequest(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("viewer_id") val viewerId: String = "",
    @SerialName("viewer_email") val viewerEmail: String = "",
    val status: String = "pending",
    @SerialName("requested_at") val requestedAt: String = ""
)

@Serializable
data class AuthorizedViewer(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("viewer_id") val viewerId: String,
    @SerialName("viewer_email") val viewerEmail: String,
    val status: String = "active",
    @SerialName("granted_at") val grantedAt: String = ""
)

@Serializable
data class UserSession(
    val userId: String,
    val email: String,
    val isAuthenticated: Boolean
)

@Serializable
data class DeviceChatMessage(
    val id: String = "",
    @SerialName("device_id") val deviceId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("sender_email") val senderEmail: String = "",
    @SerialName("sender_role") val senderRole: String = "viewer", // "owner" or "viewer"
    @SerialName("message_text") val messageText: String,
    @SerialName("created_at") val createdAt: String = ""
) {
    val formattedTime: String
        get() {
            return try {
                if (createdAt.isBlank()) return ""
                val clean = createdAt.replace("Z", "+00:00")
                val format = if (clean.contains(".")) {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                } else {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                }
                val date = format.parse(clean.substringBefore("+").substringBefore(".")) ?: Date()
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date)
            } catch (_: Exception) {
                ""
            }
        }
}
