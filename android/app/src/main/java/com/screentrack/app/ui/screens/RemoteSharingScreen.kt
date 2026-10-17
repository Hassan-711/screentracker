package com.screentrack.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentrack.app.data.model.AuthorizedViewer
import com.screentrack.app.data.model.PairingCodeResult
import com.screentrack.app.data.model.SharingRequest
import com.screentrack.app.data.repository.SupabaseRepository
import com.screentrack.app.ui.components.DeviceChatSheet
import com.screentrack.app.ui.theme.AccentEmerald
import com.screentrack.app.ui.theme.AccentRose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun RemoteSharingScreen() {
    val context = LocalContext.current
    val supabaseRepo = remember { SupabaseRepository(context) }
    val scope = rememberCoroutineScope()

    var deviceName by remember { mutableStateOf(supabaseRepo.getDeviceName()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(deviceName) }

    var pairingResult by remember { mutableStateOf<PairingCodeResult?>(null) }
    var secondsRemaining by remember { mutableIntStateOf(0) }
    var isGeneratingCode by remember { mutableStateOf(false) }

    var pendingRequests by remember { mutableStateOf<List<SharingRequest>>(emptyList()) }
    var authorizedViewers by remember { mutableStateOf<List<AuthorizedViewer>>(emptyList()) }
    var isLoadingLists by remember { mutableStateOf(true) }

    var viewerToRevoke by remember { mutableStateOf<AuthorizedViewer?>(null) }
    var activeChatTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun refreshLists(showSpinner: Boolean = false) {
        if (showSpinner) isLoadingLists = true
        val reqs = supabaseRepo.getPendingSharingRequests().getOrDefault(emptyList())
        val viewers = supabaseRepo.getAuthorizedViewers().getOrDefault(emptyList())
        pendingRequests = reqs
        authorizedViewers = viewers
        isLoadingLists = false
    }

    // Auto-poll every 8 seconds so incoming requests from Person B appear automatically
    LaunchedEffect(Unit) {
        refreshLists(showSpinner = true)
        while (isActive) {
            delay(8_000L)
            refreshLists(showSpinner = false)
        }
    }

    LaunchedEffect(pairingResult) {
        if (pairingResult != null) {
            secondsRemaining = pairingResult?.validitySeconds ?: 600
            while (secondsRemaining > 0) {
                delay(1000L)
                secondsRemaining -= 1
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        text = "Share This Phone",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Generate a 6-digit code so Person B can view this phone remotely",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }

                IconButton(onClick = { scope.launch { refreshLists(showSpinner = true) } }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Phone Name / Nickname Card (Crucial when Person A has 2+ phones!)
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "THIS PHONE'S LABEL (FOR MULTI-PHONE VIEW)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tip: Name your phones 'Phone 1' and 'Phone 2' so Person B can tell them apart.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    IconButton(onClick = {
                        nameInput = deviceName
                        showRenameDialog = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename Phone", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // Generate 6-Digit Pairing Code Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "6-Digit Pairing Code",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Enter this code on Person B's phone under 'Remote View -> Pair Phone'",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (pairingResult?.code != null && secondsRemaining > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(vertical = 16.dp, horizontal = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = pairingResult?.code ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 6.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Pairing Code", pairingResult?.code))
                                    Toast.makeText(context, "Pairing code copied", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val minutes = secondsRemaining / 60
                        val seconds = secondsRemaining % 60
                        Text(
                            text = "Expires in: %02d:%02d".format(minutes, seconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentRose,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Button(
                        onClick = {
                            isGeneratingCode = true
                            errorMessage = null
                            scope.launch {
                                val res = supabaseRepo.generatePairingCode()
                                isGeneratingCode = false
                                if (res.isSuccess) {
                                    pairingResult = res.getOrNull()
                                } else {
                                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to generate pairing code"
                                }
                            }
                        },
                        enabled = !isGeneratingCode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isGeneratingCode) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text(if (pairingResult?.code != null && secondsRemaining > 0) "Generate New 6-Digit Code" else "Generate 6-Digit Pairing Code")
                        }
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Pending Access Requests Section
        item {
            Text(
                text = "Pending Approval Requests (${pendingRequests.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (pendingRequests.isEmpty() && !isLoadingLists) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No pending pairing requests right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(pendingRequests, key = { it.id }) { req ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = req.viewerEmail,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Wants to view $deviceName's live screen time",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        supabaseRepo.respondToSharingRequest(req.id, true)
                                        Toast.makeText(context, "Viewer approved!", Toast.LENGTH_SHORT).show()
                                        refreshLists(showSpinner = false)
                                    }
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(AccentEmerald.copy(alpha = 0.2f), CircleShape)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Approve", tint = AccentEmerald, modifier = Modifier.size(20.dp))
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        supabaseRepo.respondToSharingRequest(req.id, false)
                                        refreshLists(showSpinner = false)
                                    }
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(AccentRose.copy(alpha = 0.2f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Reject", tint = AccentRose, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }

        // Authorized Viewers Section
        item {
            Text(
                text = "Authorized Viewers for This Phone (${authorizedViewers.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (authorizedViewers.isEmpty() && !isLoadingLists) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No remote viewers authorized for this phone yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(authorizedViewers, key = { it.id }) { viewer ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = viewer.viewerEmail,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Active Viewer",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    activeChatTarget = Pair(viewer.deviceId, viewer.viewerEmail)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Forum, contentDescription = "Chat", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Chat", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            OutlinedButton(
                                onClick = { viewerToRevoke = viewer },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRose),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Revoke", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Rename Phone Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Set Phone Nickname") },
            text = {
                Column {
                    Text(
                        text = "Give this phone a clear name (e.g., 'Phone 1 - Work' or 'Phone 2 - Personal') so remote viewers can easily distinguish your phones.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Phone Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    supabaseRepo.setDeviceName(nameInput)
                    deviceName = supabaseRepo.getDeviceName()
                    showRenameDialog = false
                    scope.launch {
                        supabaseRepo.ensureDeviceRegistered()
                    }
                    Toast.makeText(context, "Phone name updated to $deviceName", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Revocation Confirmation Dialog
    if (viewerToRevoke != null) {
        AlertDialog(
            onDismissRequest = { viewerToRevoke = null },
            title = { Text("Revoke Remote Access?") },
            text = {
                Text("Remove access for ${viewerToRevoke?.viewerEmail}? They will immediately stop seeing this phone's screen time.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val v = viewerToRevoke
                        viewerToRevoke = null
                        if (v != null) {
                            scope.launch {
                                supabaseRepo.revokeViewer(v.viewerId)
                                refreshLists(showSpinner = false)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRose)
                ) {
                    Text("Revoke Access")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewerToRevoke = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (activeChatTarget != null) {
        DeviceChatSheet(
            deviceId = activeChatTarget!!.first,
            deviceOrViewerName = activeChatTarget!!.second,
            isOwner = true,
            onDismiss = { activeChatTarget = null }
        )
    }
}
