package com.screentrack.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentrack.app.data.model.AppUsageInfo
import com.screentrack.app.data.model.DailyAnalyticsPoint
import com.screentrack.app.data.model.RemoteDeviceLiveState
import com.screentrack.app.data.model.SharingRequest
import com.screentrack.app.data.repository.SupabaseRepository
import com.screentrack.app.ui.components.DeviceChatSheet
import com.screentrack.app.ui.theme.AccentAmber
import com.screentrack.app.ui.theme.AccentEmerald
import com.screentrack.app.ui.theme.AccentRose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun RemoteMonitorScreen(
    onNavigateToAuth: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val supabaseRepo = remember { SupabaseRepository(context) }
    val scope = rememberCoroutineScope()

    var remoteDevices by remember { mutableStateOf<List<RemoteDeviceLiveState>>(emptyList()) }
    var outgoingPending by remember { mutableStateOf<List<SharingRequest>>(emptyList()) }
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var detailTab by remember { mutableIntStateOf(0) }
    var selectedHistoryDay by remember { mutableStateOf<DailyAnalyticsPoint?>(null) }

    var showPairDialog by remember { mutableStateOf(false) }
    var pairingCodeInput by remember { mutableStateOf("") }
    var isSubmittingCode by remember { mutableStateOf(false) }
    var pairingFeedback by remember { mutableStateOf<String?>(null) }
    var isPairingSuccess by remember { mutableStateOf(false) }

    var deviceToRemove by remember { mutableStateOf<com.screentrack.app.data.model.RegisteredDevice?>(null) }
    var isRemovingDevice by remember { mutableStateOf(false) }
    var chatTargetDevice by remember { mutableStateOf<com.screentrack.app.data.model.RegisteredDevice?>(null) }

    suspend fun loadRemoteData(showSpinner: Boolean = false) {
        if (!supabaseRepo.isConfigured || supabaseRepo.getCurrentSession() == null) {
            isLoading = false
            return
        }
        if (showSpinner) isLoading = true
        val res = supabaseRepo.getRemoteDevicesLiveStates()
        val pendingRes = supabaseRepo.getOutgoingPendingRequests()
        outgoingPending = pendingRes.getOrDefault(emptyList())

        if (res.isSuccess) {
            val list = res.getOrDefault(emptyList())
            remoteDevices = list
            if (list.isNotEmpty() && (selectedDeviceId == null || list.none { it.device.id == selectedDeviceId })) {
                selectedDeviceId = list.first().device.id
            }
            errorMessage = null
        } else {
            errorMessage = res.exceptionOrNull()?.localizedMessage
        }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        loadRemoteData(showSpinner = true)
        while (isActive) {
            delay(10_000L)
            loadRemoteData(showSpinner = false)
        }
    }

    val selectedDeviceState = remoteDevices.find { it.device.id == selectedDeviceId } ?: remoteDevices.firstOrNull()

    LaunchedEffect(selectedDeviceState?.device?.id) {
        selectedHistoryDay = selectedDeviceState?.sevenDayHistory?.lastOrNull()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Remote Phones Live",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Monitor live active app, usage & 7-day history across 2+ phones",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { loadRemoteData(showSpinner = true) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                    }
                    Button(
                        onClick = {
                            pairingCodeInput = ""
                            pairingFeedback = null
                            showPairDialog = true
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pair Phone", fontSize = 12.sp)
                    }
                }
            }
        }

        if (!supabaseRepo.isConfigured) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Connect Supabase Backend First", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "To view remote phones live, configure your Supabase URL and Anon Key in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(onClick = onNavigateToSettings, shape = RoundedCornerShape(10.dp)) {
                            Text("Open Settings")
                        }
                    }
                }
            }
            return@LazyColumn
        }

        if (supabaseRepo.getCurrentSession() == null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Sign In to Monitor Remote Phones", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Sign in or create a viewer account to pair with Person A's phones using 6-digit pairing codes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(onClick = onNavigateToAuth, shape = RoundedCornerShape(10.dp)) {
                            Text("Sign In / Create Account")
                        }
                    }
                }
            }
            return@LazyColumn
        }

        if (outgoingPending.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AccentAmber.copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Waiting for Owner Approval (${outgoingPending.size} pending)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = AccentAmber
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ask the phone owner to open the 'Share Code' tab on their phone and tap the green Approve button.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        if (isLoading && remoteDevices.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Fetching connected remote phones...", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (remoteDevices.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Remote Phones Connected Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "To monitor 1, 2, or more phones remotely:\n1. Open ScreenTrack on Person A's Phone 1 -> 'Share Code' tab -> Generate 6-digit code.\n2. Tap 'Pair Phone' below and enter that 6-digit code.\n3. Repeat for Person A's Phone 2!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                pairingCodeInput = ""
                                pairingFeedback = null
                                showPairDialog = true
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enter 6-Digit Pairing Code")
                        }
                    }
                }
            }
        } else {
            if (remoteDevices.size >= 2) {
                item {
                    val combinedMs = remoteDevices.sumOf {
                        it.todaySummary?.totalScreenTimeMs ?: it.todayApps.sumOf { a -> a.usageDurationMs }
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "ALL ${remoteDevices.size} CONNECTED PHONES (COMBINED)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = AppUsageInfo.formatDuration(combinedMs),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "Tap any phone below",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                remoteDevices.forEach { devState ->
                                    val isSelected = devState.device.id == selectedDeviceState?.device?.id
                                    val isScreenOn = devState.status?.isScreenOn == true && !devState.isStale
                                    val activeApp = if (isScreenOn) {
                                        devState.status?.currentForegroundApp?.ifBlank { "Home / Active" } ?: "Home / Active"
                                    } else {
                                        "Idle (Last: ${devState.status?.lastObservedApp?.ifBlank { "None" } ?: "None"})"
                                    }
                                    val devTodayMs = devState.todaySummary?.totalScreenTimeMs
                                        ?: devState.todayApps.sumOf { it.usageDurationMs }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.surface
                                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                                            )
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable { selectedDeviceId = devState.device.id }
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(
                                                        if (isScreenOn) AccentEmerald else Color.Gray.copy(alpha = 0.5f),
                                                        CircleShape
                                                    )
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = devState.device.deviceName,
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = activeApp,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isScreenOn) AccentEmerald else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = AppUsageInfo.formatDuration(devTodayMs),
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = devState.lastSeenLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    remoteDevices.forEach { devState ->
                        val selected = devState.device.id == selectedDeviceState?.device?.id
                        FilterChip(
                            selected = selected,
                            onClick = { selectedDeviceId = devState.device.id },
                            label = {
                                Text(
                                    text = devState.device.deviceName,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            if (selectedDeviceState != null) {
                val dev = selectedDeviceState.device
                val status = selectedDeviceState.status
                val isScreenOn = status?.isScreenOn == true && !selectedDeviceState.isStale
                val todayTotalMs = selectedDeviceState.todaySummary?.totalScreenTimeMs
                    ?: selectedDeviceState.todayApps.sumOf { it.usageDurationMs }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dev.deviceName,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${dev.deviceModel} • Synced ${selectedDeviceState.lastSeenLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (status?.batteryLevel != null && status.batteryLevel in 1..100) {
                                        Icon(
                                            imageVector = Icons.Default.BatteryFull,
                                            contentDescription = null,
                                            tint = AccentEmerald,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "${status.batteryLevel}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    IconButton(
                                        onClick = { chatTargetDevice = dev },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Forum,
                                            contentDescription = "Chat with Phone",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))

                                    IconButton(
                                        onClick = { deviceToRemove = dev },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove Device",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isScreenOn) AccentEmerald.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(
                                                if (isScreenOn) AccentEmerald else Color.Gray,
                                                CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isScreenOn) "CURRENTLY ACTIVE APP (LIVE)" else "SCREEN LOCKED • LAST OBSERVED APP",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isScreenOn) AccentEmerald else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                        Text(
                                            text = if (isScreenOn) {
                                                status?.currentForegroundApp?.ifBlank { "On Home Screen" } ?: "On Home Screen"
                                            } else {
                                                status?.lastObservedApp?.ifBlank {
                                                    status.currentForegroundApp?.ifBlank { "No recent app" } ?: "No recent app"
                                                } ?: "No recent app"
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        if (isScreenOn && (status?.currentSessionDurationMs ?: 0L) > 0L) {
                                            Text(
                                                text = "Active session duration: ${AppUsageInfo.formatDuration(status?.currentSessionDurationMs ?: 0L)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "TODAY'S TOTAL SCREEN TIME",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = AppUsageInfo.formatDuration(todayTotalMs),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "MOST USED TODAY",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = selectedDeviceState.todaySummary?.mostUsedAppName?.ifBlank {
                                            selectedDeviceState.todayApps.firstOrNull()?.appName ?: "None"
                                        } ?: selectedDeviceState.todayApps.firstOrNull()?.appName ?: "None",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = detailTab == 0,
                            onClick = { detailTab = 0 },
                            label = { Text("Today's App Usage (${selectedDeviceState.todayApps.size})") },
                            leadingIcon = { Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = detailTab == 1,
                            onClick = { detailTab = 1 },
                            label = { Text("7-Day History") },
                            leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }

                if (detailTab == 0) {
                    if (selectedDeviceState.todayApps.isEmpty()) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "No app usage recorded for today on ${dev.deviceName} yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(18.dp)
                                )
                            }
                        }
                    } else {
                        items(selectedDeviceState.todayApps, key = { "${dev.id}_${it.packageName}" }) { app ->
                            AppUsageRowItem(app = app)
                        }
                    }
                } else {
                    val history = selectedDeviceState.sevenDayHistory
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "${dev.deviceName} — Last 7 Days",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tap any day bar below to view that day's app usage breakdown",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.height(20.dp))

                                val maxDuration = (history.maxOfOrNull { it.totalScreenTimeMs } ?: 1L).coerceAtLeast(1L)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    history.forEach { point ->
                                        val rawRatio = point.totalScreenTimeMs.toFloat() / maxDuration.toFloat()
                                        val heightFraction = if (rawRatio.isNaN() || rawRatio.isInfinite()) 0.06f else rawRatio.coerceIn(0.06f, 1f)
                                        val isSelectedDay = selectedHistoryDay?.date == point.date

                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { selectedHistoryDay = point }
                                        ) {
                                            Text(
                                                text = AppUsageInfo.formatDuration(point.totalScreenTimeMs),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                color = if (isSelectedDay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight(heightFraction)
                                                    .fillMaxWidth(0.55f)
                                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                                    .background(
                                                        if (isSelectedDay) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                                    )
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = point.dayOfWeek,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (isSelectedDay) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelectedDay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val activeDay = selectedHistoryDay ?: history.lastOrNull()
                    if (activeDay != null) {
                        item {
                            Text(
                                text = "${activeDay.dayOfWeek} (${activeDay.date}) — Total: ${activeDay.formattedDuration}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (activeDay.apps.isEmpty()) {
                            item {
                                Text(
                                    text = "No detailed app records for ${activeDay.date}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                )
                            }
                        } else {
                            items(activeDay.apps, key = { "${dev.id}_${activeDay.date}_${it.packageName}" }) { app ->
                                AppUsageRowItem(app = app)
                            }
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { deviceToRemove = dev },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Remove ${dev.deviceName} from Remote View", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = { Text("Pair Remote Phone (6-Digit Code)") },
            text = {
                Column {
                    Text(
                        text = "Enter the 6-character code generated on Person A's phone (under 'Share Code'). You can pair 2 or more phones one by one.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pairingCodeInput,
                        onValueChange = { pairingCodeInput = it.uppercase(Locale.ROOT).take(6) },
                        label = { Text("6-Digit Pairing Code") },
                        placeholder = { Text("e.g. 7K2M9X") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (pairingFeedback != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = pairingFeedback!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPairingSuccess) AccentEmerald else AccentRose
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pairingCodeInput.trim().length != 6) {
                            isPairingSuccess = false
                            pairingFeedback = "Please enter a valid 6-character code."
                            return@Button
                        }
                        isSubmittingCode = true
                        pairingFeedback = null
                        scope.launch {
                            val res = supabaseRepo.submitPairingCode(pairingCodeInput)
                            isSubmittingCode = false
                            if (res.isSuccess) {
                                isPairingSuccess = true
                                pairingFeedback = res.getOrNull()?.message
                                    ?: "Request sent! Approve it on the target phone."
                                pairingCodeInput = ""
                                loadRemoteData(showSpinner = false)
                            } else {
                                isPairingSuccess = false
                                pairingFeedback = res.exceptionOrNull()?.localizedMessage ?: "Failed to pair"
                            }
                        }
                    },
                    enabled = !isSubmittingCode
                ) {
                    if (isSubmittingCode) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("Send Pairing Request")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Remove Tracked Device Confirmation Dialog
    if (deviceToRemove != null) {
        val targetDev = deviceToRemove!!
        AlertDialog(
            onDismissRequest = { if (!isRemovingDevice) deviceToRemove = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Remove Tracked Phone?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove \"${targetDev.deviceName}\" from your Remote View?\n\nYou will stop receiving live screen time and app usage for this phone unless you pair it again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idToRemove = targetDev.id
                        val nameToRemove = targetDev.deviceName
                        isRemovingDevice = true
                        scope.launch {
                            val res = supabaseRepo.removeTrackedPhone(idToRemove)
                            isRemovingDevice = false
                            deviceToRemove = null
                            if (res.isSuccess) {
                                remoteDevices = remoteDevices.filter { it.device.id != idToRemove }
                                if (selectedDeviceId == idToRemove) {
                                    selectedDeviceId = remoteDevices.firstOrNull()?.device?.id
                                }
                                Toast.makeText(context, "\"$nameToRemove\" removed from Remote View.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    res.exceptionOrNull()?.localizedMessage ?: "Failed to remove device",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isRemovingDevice
                ) {
                    if (isRemovingDevice) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("Remove Phone", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deviceToRemove = null },
                    enabled = !isRemovingDevice
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (chatTargetDevice != null) {
        DeviceChatSheet(
            deviceId = chatTargetDevice!!.id,
            deviceOrViewerName = chatTargetDevice!!.deviceName,
            isOwner = false,
            onDismiss = { chatTargetDevice = null }
        )
    }
}
