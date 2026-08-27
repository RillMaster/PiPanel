package com.rillmaster.pipanel

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WifiNetwork(
    val ssid: String,
    val signal: Int,
    val security: String,
    val bars: String,
    val isConnected: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiManagementScreen(
    settings: SettingsManager,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit,
    showNavigationIcon: Boolean = true
) {
    var networks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var connectingSsid by remember { mutableStateOf<String?>(null) }
    var showPasswordDialog by remember { mutableStateOf<WifiNetwork?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val scanErrorMsg = stringResource(R.string.wifi_scan_error)

    fun scan() {
        scope.launch {
            scanning = true
            errorMessage = null
            val result = fetchWifiNetworks(settings, context)
            if (result != null) {
                networks = result
            } else {
                errorMessage = scanErrorMsg
            }
            scanning = false
        }
    }

    LaunchedEffect(Unit) {
        scan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wifi_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (showNavigationIcon) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = null)
                        }
                    } else {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scan() }, enabled = !scanning) {
                        if (scanning) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (networks.isEmpty() && !scanning) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.WifiOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.wifi_scan_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { scan() }, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.net_scan_retry))
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(networks) { network ->
                        WifiNetworkItem(
                            network = network,
                            isConnecting = connectingSsid == network.ssid,
                            onClick = {
                                if (network.isConnected) {
                                    // Option to forget or just show info
                                } else {
                                    showPasswordDialog = network
                                }
                            }
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = {
                        TextButton(onClick = { errorMessage = null }) { Text("OK") }
                    }
                ) { Text(errorMessage!!) }
            }
        }
    }

    if (showPasswordDialog != null) {
        WifiPasswordDialog(
            ssid = showPasswordDialog!!.ssid,
            onDismiss = { showPasswordDialog = null },
            onConfirm = { password ->
                val ssid = showPasswordDialog!!.ssid
                showPasswordDialog = null
                scope.launch {
                    connectingSsid = ssid
                    if (connectToWifi(settings, ssid, password, context)) {
                        errorMessage = context.getString(R.string.wifi_connect_success, ssid)
                        delay(1000)
                        scan()
                    } else {
                        errorMessage = context.getString(R.string.wifi_connect_error, ssid)
                    }
                    connectingSsid = null
                }
            }
        )
    }
}

@Composable
fun WifiNetworkItem(
    network: WifiNetwork,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isConnecting) { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = if (network.isConnected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                 else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (network.isConnected) Icons.Default.Wifi else Icons.Default.NetworkWifi,
                contentDescription = null,
                tint = if (network.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(network.ssid, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.wifi_security, network.security.ifEmpty { "Open" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(network.bars, style = MaterialTheme.typography.labelLarge)
                    Text("${network.signal}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun WifiPasswordDialog(
    ssid: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wifi_password_label, ssid)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = password.length >= 8 || password.isEmpty()) {
                Text(stringResource(R.string.wifi_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

suspend fun fetchWifiNetworks(settings: SettingsManager, context: android.content.Context): List<WifiNetwork>? = withContext(Dispatchers.IO) {
    try {
        val scanCmd = "sudo nmcli -t -f SSID,SIGNAL,SECURITY,BARS dev wifi list --rescan yes"
        val statusCmd = "nmcli -t -f TYPE,STATE,CONNECTION device"
        
        val scanRaw = SshClient.execute(settings.host, settings.port, settings.username, settings.password, scanCmd, settings.sshTimeoutMs, context)
        val statusRaw = SshClient.execute(settings.host, settings.port, settings.username, settings.password, statusCmd, settings.sshTimeoutMs, context)
        
        if (scanRaw.startsWith("[err]")) return@withContext null
        
        val connectedSsid = statusRaw.lines()
            .find { it.startsWith("wifi:connected:") }
            ?.split(":")?.getOrNull(2)

        scanRaw.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(":")
                if (parts.size >= 4) {
                    val ssid = parts[0]
                    if (ssid.isEmpty()) return@mapNotNull null
                    WifiNetwork(
                        ssid = ssid,
                        signal = parts[1].toIntOrNull() ?: 0,
                        security = parts[2],
                        bars = parts[3],
                        isConnected = ssid == connectedSsid
                    )
                } else null
            }
            .distinctBy { it.ssid }
            .sortedByDescending { it.isConnected }
    } catch (e: Exception) {
        null
    }
}

suspend fun connectToWifi(settings: SettingsManager, ssid: String, password: String, context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
    try {
        val cmd = if (password.isEmpty()) {
            "sudo nmcli dev wifi connect '$ssid'"
        } else {
            "sudo nmcli dev wifi connect '$ssid' password '$password'"
        }
        val result = SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd, 20000, context)
        !result.startsWith("[err]") && result.contains("successfully")
    } catch (e: Exception) {
        false
    }
}
