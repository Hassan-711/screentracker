package com.screentrack.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

import android.net.Uri

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    data object Onboarding : Screen("onboarding", "Welcome")
    data object Auth : Screen("auth", "Sign In")
    data object Dashboard : Screen("dashboard", "My Phone", Icons.Default.PhoneAndroid)
    data object RemoteMonitor : Screen("remote_monitor", "Remote View", Icons.Default.Devices)
    data object AppUsage : Screen("app_usage", "All Apps")
    data object Analytics : Screen("analytics", "7-Day History", Icons.Default.BarChart)
    data object RemoteSharing : Screen("remote_sharing", "Share Code", Icons.Default.QrCode2)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object Chat : Screen("chat/{deviceId}/{partnerId}/{partnerEmail}/{title}", "Chat") {
        fun createRoute(deviceId: String, partnerId: String, partnerEmail: String, title: String): String {
            val encDeviceId = Uri.encode(deviceId.ifBlank { "none" })
            val encPartnerId = Uri.encode(partnerId.ifBlank { "none" })
            val encPartnerEmail = Uri.encode(partnerEmail.ifBlank { "none" })
            val encTitle = Uri.encode(title.ifBlank { "Chat" })
            return "chat/$encDeviceId/$encPartnerId/$encPartnerEmail/$encTitle"
        }
    }

    companion object {
        val bottomNavItems: List<Screen>
            get() = listOf(
                Dashboard,
                RemoteMonitor,
                Analytics,
                RemoteSharing,
                Settings
            )
    }
}
