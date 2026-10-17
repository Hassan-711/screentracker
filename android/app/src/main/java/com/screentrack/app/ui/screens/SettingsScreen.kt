package com.screentrack.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.io.File
import com.screentrack.app.BuildConfig
import com.screentrack.app.util.AppUpdateInfo
import com.screentrack.app.util.AppUpdateManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.screentrack.app.data.repository.PermissionState
import com.screentrack.app.data.repository.SupabaseRepository
import com.screentrack.app.data.repository.UsageStatsRepository
import com.screentrack.app.service.ScreenMonitorForegroundService
import com.screentrack.app.ui.theme.AccentEmerald
import com.screentrack.app.ui.theme.AccentRose
import com.screentrack.app.worker.UsageSyncWorker
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    currentThemeMode: String = "clay_light",
    onThemeModeChanged: (String) -> Unit = {},
    onNavigateToAuth: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val supabaseRepo = remember { SupabaseRepository(context) }
    val usageRepo = remember { UsageStatsRepository(context) }
    val scope = rememberCoroutineScope()

    var currentSession by remember { mutableStateOf(supabaseRepo.getCurrentSession()) }
    val permissionState = usageRepo.checkPermissionState()

    var syncIntervalMinutes by remember { mutableIntStateOf(supabaseRepo.getSyncIntervalMinutes()) }
    var isForegroundServiceEnabled by remember { mutableStateOf(supabaseRepo.isForegroundMonitorEnabled()) }

    // App Update State (Solution 1 - GitHub In-App Auto-Updater)
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var updateCheckDone by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Supabase cloud connection, account, live service & 7-day cleanup",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        // 1. Supabase Backend Connection Status
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                if (supabaseRepo.isConfigured) AccentEmerald.copy(alpha = 0.15f) else AccentRose.copy(alpha = 0.15f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (supabaseRepo.isConfigured) Icons.Default.CloudDone else Icons.Default.Security,
                            contentDescription = null,
                            tint = if (supabaseRepo.isConfigured) AccentEmerald else AccentRose,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CLOUD BACKEND CONNECTION",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (supabaseRepo.isConfigured) "Connected to Supabase Cloud" else "Cloud Not Configured",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (supabaseRepo.isConfigured) AccentEmerald else AccentRose
                        )
                        Text(
                            text = if (supabaseRepo.isConfigured) "Secure real-time sync & remote pairing active" else "Please verify build configuration",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 2. Account Status & Sign In
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "2. CLOUD ACCOUNT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentSession?.email ?: "Not signed in",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Phone label: ${supabaseRepo.getDeviceName()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    if (currentSession == null) {
                        Button(onClick = onNavigateToAuth, shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sign In")
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    supabaseRepo.signOut()
                                    currentSession = null
                                    onLogout()
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sign Out")
                        }
                    }
                }
            }
        }

        // 3. Usage Access Permission
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "USAGE ACCESS PERMISSION",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (permissionState is PermissionState.Granted) "Granted & Active" else "Permission Required",
                            fontWeight = FontWeight.Bold,
                            color = if (permissionState is PermissionState.Granted) AccentEmerald else AccentRose
                        )
                    }

                    if (permissionState !is PermissionState.Granted) {
                        Button(onClick = {
                            context.startActivity(usageRepo.createUsageAccessSettingsIntent())
                        }) {
                            Text("Grant")
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentEmerald)
                    }
                }
            }
        }

        // 4. Live Monitor & Sync Interval
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "LIVE MONITORING & BACKGROUND SYNC",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Live 10-Second Active App Broadcast", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Runs a lightweight foreground notification so remote viewers see active app switches in near real-time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = isForegroundServiceEnabled,
                            onCheckedChange = { enabled ->
                                isForegroundServiceEnabled = enabled
                                supabaseRepo.setForegroundMonitorEnabled(enabled)
                                if (enabled) {
                                    ScreenMonitorForegroundService.start(context)
                                } else {
                                    ScreenMonitorForegroundService.stop(context)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Background WorkManager fallback interval:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    listOf(15 to "15 minutes (recommended)", 30 to "30 minutes", 60 to "1 hour").forEach { (interval, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = syncIntervalMinutes == interval,
                                onClick = {
                                    syncIntervalMinutes = interval
                                    supabaseRepo.setSyncIntervalMinutes(interval)
                                    UsageSyncWorker.schedulePeriodicSync(context, interval.toLong())
                                    Toast.makeText(context, "Background sync set to $interval min", Toast.LENGTH_SHORT).show()
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // 5. 7-Day History Auto-Cleanup
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoDelete, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "7-Day History Auto-Cleanup", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Records older than 7 days are automatically purged from Supabase to keep the app and database fast and lightweight.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                supabaseRepo.runSevenDayCleanup()
                                Toast.makeText(context, "Cleaned up history older than 7 days!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Run >7-Day Cleanup Now")
                    }
                }
            }
        }

        // Theme Appearance Card (Claymorphic Styling)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "App Theme & Appearance", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose your preferred aesthetic. Warm Clay Cream provides the signature claymorphic look across all phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val themeOptions = listOf(
                        "clay_light" to "Warm Clay Cream (Signature Claymorphism)",
                        "clay_dark" to "Dark Ceramic Clay (Warm Slate & Terracotta)",
                        "system" to "System Auto"
                    )

                    themeOptions.forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = currentThemeMode == mode,
                                onClick = { onThemeModeChanged(mode) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // In-App Auto-Updater Card (GitHub Releases - Solution 1)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "In-App Updates", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Automatically checks GitHub for latest release APKs. Downloaded APKs are cleaned up post-install to conserve storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (updateInfo != null) {
                        val update = updateInfo!!
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AccentEmerald.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Update Available: v${update.versionName}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AccentEmerald
                                    )
                                }
                                if (update.releaseNotes.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = update.releaseNotes.take(120),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (isDownloadingUpdate) {
                            Column {
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Downloading update: ${(downloadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (downloadedApkFile != null && downloadedApkFile!!.exists()) {
                            Button(
                                onClick = {
                                    if (!AppUpdateManager.canInstallApk(context)) {
                                        Toast.makeText(context, "Please allow unknown app installation to proceed", Toast.LENGTH_LONG).show()
                                        AppUpdateManager.openInstallPermissionSettings(context)
                                    } else {
                                        AppUpdateManager.installApk(context, downloadedApkFile!!)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install Update Now")
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (!AppUpdateManager.canInstallApk(context)) {
                                        Toast.makeText(context, "Please allow unknown app installation first", Toast.LENGTH_SHORT).show()
                                        AppUpdateManager.openInstallPermissionSettings(context)
                                    }
                                    isDownloadingUpdate = true
                                    scope.launch {
                                        val res = AppUpdateManager.downloadApk(context, update.apkDownloadUrl) { progress ->
                                            downloadProgress = progress
                                        }
                                        isDownloadingUpdate = false
                                        if (res.isSuccess) {
                                            downloadedApkFile = res.getOrNull()
                                            Toast.makeText(context, "Update downloaded! Ready to install.", Toast.LENGTH_SHORT).show()
                                            downloadedApkFile?.let { AppUpdateManager.installApk(context, it) }
                                        } else {
                                            updateError = res.exceptionOrNull()?.localizedMessage ?: "Download failed"
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Download & Install Update")
                            }
                        }
                    } else if (updateCheckDone && !isCheckingUpdate) {
                        Text(
                            text = "You are currently running the latest version.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentEmerald,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (updateError != null) {
                        Text(
                            text = updateError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentRose
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedButton(
                        onClick = {
                            isCheckingUpdate = true
                            updateError = null
                            scope.launch {
                                val res = AppUpdateManager.checkForUpdate(BuildConfig.VERSION_NAME)
                                isCheckingUpdate = false
                                updateCheckDone = true
                                if (res.isSuccess) {
                                    updateInfo = res.getOrNull()
                                    if (updateInfo == null) {
                                        Toast.makeText(context, "App is up to date!", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    updateError = res.exceptionOrNull()?.localizedMessage
                                }
                            }
                        },
                        enabled = !isCheckingUpdate && !isDownloadingUpdate,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking GitHub...")
                        } else {
                            Text("Check for Updates")
                        }
                    }
                }
            }
        }

        // Privacy Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Privacy & Lightweight Design", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Only foreground app names, active session timers, and 7-day usage durations are shared with viewers you explicitly approve.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
