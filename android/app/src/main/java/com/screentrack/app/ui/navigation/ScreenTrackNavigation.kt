package com.screentrack.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    data object Onboarding : Screen("onboarding", "Welcome")
    data object Auth : Screen("auth", "Sign In")
    data object Dashboard : Screen("dashboard", "My Phone", Icons.Default.PhoneAndroid)
    data object RemoteMonitor : Screen("remote_monitor", "Remote View", Icons.Default.Devices)
    data object AppUsage : Screen("app_usage", "All Apps")
    data object Analytics : Screen("analytics", "7-Day History", Icons.Default.BarChart)
    data object RemoteSharing : Screen("remote_sharing", "Share Code", Icons.Default.QrCode2)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)

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
