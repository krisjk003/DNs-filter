package com.zeroads.blocker.ui.theme

import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroads.blocker.VpnCoordinator
import com.zeroads.blocker.VpnMode
import com.zeroads.blocker.WireGuardManager
import com.zeroads.blocker.vpn.AdBlockVpnService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard() {
    val context = LocalContext.current
    val stats by AdBlockVpnService.stats.collectAsState()
    val wgStats by WireGuardManager.wgStats.collectAsState()
    val wgState by WireGuardManager.connectionState.collectAsState()
    val wgError by WireGuardManager.lastError.collectAsState()
    val currentMode by VpnCoordinator.currentMode.collectAsState()
    val scope = rememberCoroutineScope()

    var showConfigDialog by remember { mutableStateOf(false) }
    var configText by remember { mutableStateOf("") }
    var configError by remember { mutableStateOf<String?>(null) }

    var selectedMode by remember { mutableStateOf(VpnCoordinator.preferredMode.value) }
    val isConnected = currentMode == VpnMode.MODE_A_DNS_ONLY || wgState == com.zeroads.blocker.WireGuardConnectionState.CONNECTED

    VpnCoordinator.init(context)

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            VpnCoordinator.setMode(context, selectedMode)
        }
    }

    if (showConfigDialog) {
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("Set up Secure Tunnel") },
            text = {
                Column {
                    Text("A remote WireGuard server, endpoint, peer, and private key are required. ZeroAds will not create or guess these values.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = configText,
                        onValueChange = { configText = it; configError = null },
                        label = { Text("Paste .conf file content") },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        maxLines = 10
                    )
                    if (configError != null) {
                        Text(configError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        val inputStream = configText.byteInputStream()
                        val parsedConfig = WireGuardManager.parseConfig(inputStream)
                        if (parsedConfig != null) {
                            WireGuardManager.saveConfig(context, parsedConfig)
                            showConfigDialog = false
                        } else {
                            configError = "The profile could not be parsed."
                        }
                    } catch (e: Exception) {
                        configError = e.message ?: "The profile is missing a valid server endpoint or tunnel address."
                    }
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ZEROADS DASHBOARD",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp, top = 16.dp)
        )

        // Mode Selector
        Text("Operating Mode", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedMode == VpnMode.MODE_A_DNS_ONLY,
                onClick = { if (!isConnected) { selectedMode = VpnMode.MODE_A_DNS_ONLY; VpnCoordinator.setPreferredMode(context, selectedMode) } },
                label = { Text("DNS Only") }
            )
            FilterChip(
                selected = selectedMode == VpnMode.MODE_B_WIREGUARD_ONLY,
                onClick = { if (!isConnected) { selectedMode = VpnMode.MODE_B_WIREGUARD_ONLY; VpnCoordinator.setPreferredMode(context, selectedMode) } },
                label = { Text("Secure Tunnel") }
            )
            FilterChip(
                selected = selectedMode == VpnMode.MODE_C_WIREGUARD_PLUS_DNS,
                onClick = { if (!isConnected) { selectedMode = VpnMode.MODE_C_WIREGUARD_PLUS_DNS; VpnCoordinator.setPreferredMode(context, selectedMode) } },
                label = { Text("Tunnel + AdBlock") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (selectedMode != VpnMode.MODE_A_DNS_ONLY) {
            TextButton(onClick = { showConfigDialog = true }, enabled = !isConnected) {
                Text(if (WireGuardManager.savedConfig != null) "✓ WireGuard Profile Loaded (Tap to Change)" else "Import WireGuard Profile")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Protection Status",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "VPN: ${when {
                wgState == com.zeroads.blocker.WireGuardConnectionState.CONNECTING -> "CONNECTING"
                wgState == com.zeroads.blocker.WireGuardConnectionState.RECONNECTING -> "RECONNECTING"
                wgState == com.zeroads.blocker.WireGuardConnectionState.ERROR -> "ERROR"
                isConnected -> "CONNECTED"
                else -> "DISCONNECTED"
            }}",
            color = if (isConnected) Color(0xFF4CAF50) else Color.Gray,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (stats.lastError != null || wgError != null) {
            Text(
            text = "Last Error: ${wgError ?: stats.lastError}",
                color = Color.Red,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        Button(
            onClick = {
                if (isConnected) {
                    VpnCoordinator.setMode(context, VpnMode.DISCONNECTED)
                } else if (selectedMode != VpnMode.MODE_A_DNS_ONLY && WireGuardManager.savedConfig == null) {
                    configError = null
                    showConfigDialog = true
                } else {
                    val vpnIntent = VpnService.prepare(context)
                    if (vpnIntent != null) {
                        vpnLauncher.launch(vpnIntent)
                    } else {
                        VpnCoordinator.setMode(context, selectedMode)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isConnected) "STOP CONNECTION" else "CONNECT")
        }

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        // Connection Stats Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Connection Details", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                if (currentMode == VpnMode.MODE_B_WIREGUARD_ONLY || currentMode == VpnMode.MODE_C_WIREGUARD_PLUS_DNS) {
                    DetailRow("Endpoint", wgStats.endpoint ?: "N/A")
                    DetailRow("Assigned IP", wgStats.assignedIp ?: "N/A")
                    DetailRow("Data Rx", "${wgStats.rxBytes} bytes")
                    DetailRow("Data Tx", "${wgStats.txBytes} bytes")
                    if (currentMode == VpnMode.MODE_C_WIREGUARD_PLUS_DNS) {
                        Text("DNS Filtering: Remote (via WireGuard VPS)", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(top=8.dp))
                    }
                } else {
                    Text("DNS Local Filtering Active", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // DNS Stats
        if (currentMode == VpnMode.MODE_A_DNS_ONLY || selectedMode == VpnMode.MODE_A_DNS_ONLY) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Local DNS Statistics", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    DetailRow("Requests Processed", "${stats.totalRequests}")
                    DetailRow("Ads Blocked", "${stats.blockedRequests}")
                    DetailRow("Requests Allowed", "${stats.allowedRequests}")
                    DetailRow("Upstream Failures", "${stats.failedUpstreamRequests}")
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
