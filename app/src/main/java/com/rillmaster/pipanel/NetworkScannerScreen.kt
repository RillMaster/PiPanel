package com.rillmaster.pipanel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class NetworkDevice(
    val ip: String,
    val hostname: String,
    val mac: String,
    val vendor: String
)

private enum class SortMode { BY_IP, BY_NAME }

private fun buildScanCommand(): String = """
subnets=${'$'}(ip -4 -o addr show scope global | awk '{print ${'$'}4}')
for sn in ${'$'}subnets; do
  sudo -n nmap -sn "${'$'}sn" 2>/dev/null || nmap -sn "${'$'}sn" 2>/dev/null
done
""".trimIndent()

private val REPORT_REGEX =
    Regex("""Nmap scan report for (?:(\S+) \((\d+\.\d+\.\d+\.\d+)\)|(\d+\.\d+\.\d+\.\d+))""")
private val MAC_REGEX =
    Regex("""MAC Address: ([0-9A-F:]{17})(?: \((.+?)\))?""")

fun parseNmapOutput(output: String): List<NetworkDevice> {
    val devices = mutableListOf<NetworkDevice>()
    var currentIp = ""
    var currentHostname = ""

    fun flush() {
        if (currentIp.isNotEmpty()) {
            devices.add(NetworkDevice(ip = currentIp, hostname = currentHostname, mac = "", vendor = ""))
        }
    }

    output.lines().forEach { line ->
        val reportMatch = REPORT_REGEX.find(line)
        if (reportMatch != null) {
            flush()
            val hostnameRaw = reportMatch.groupValues[1]
            val ipFromHostname = reportMatch.groupValues[2]
            val ipDirect = reportMatch.groupValues[3]
            currentIp = ipFromHostname.ifEmpty { ipDirect }
            currentHostname = hostnameRaw.removeSuffix(".home").removeSuffix(".local")
            return@forEach
        }

        val macMatch = MAC_REGEX.find(line)
        if (macMatch != null && currentIp.isNotEmpty()) {
            val mac = macMatch.groupValues[1]
            val vendor = macMatch.groupValues[2]
            devices.add(NetworkDevice(ip = currentIp, hostname = currentHostname, mac = mac, vendor = vendor))
            currentIp = ""
            currentHostname = ""
        }
    }
    flush()

    // Un même hôte peut apparaître deux fois si plusieurs sous-réseaux se chevauchent
    return devices.distinctBy { it.ip }
}

private fun isGatewayIp(ip: String): Boolean = ip.endsWith(".1") || ip.endsWith(".254")

private fun sortDevices(devices: List<NetworkDevice>, mode: SortMode): List<NetworkDevice> {
    // La passerelle reste toujours en tête, quel que soit le tri choisi
    val (gateway, rest) = devices.partition { isGatewayIp(it.ip) }
    val sortedRest = when (mode) {
        SortMode.BY_IP -> rest.sortedBy { dev ->
            dev.ip.split(".").mapNotNull { it.toIntOrNull() }
                .fold(0L) { acc, part -> acc * 256 + part }
        }
        SortMode.BY_NAME -> rest.sortedBy { it.hostname.ifBlank { it.ip }.lowercase() }
    }
    return gateway.sortedBy { it.ip } + sortedRest
}

private fun formatElapsedSince(timestampMs: Long): String {
    val diff = (System.currentTimeMillis() - timestampMs) / 1000
    return when {
        diff < 5 -> "à l'instant"
        diff < 60 -> "il y a ${diff}s"
        diff < 3600 -> "il y a ${diff / 60}min"
        else -> "il y a ${diff / 3600}h"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NetworkScannerScreen(
    settings: SettingsManager,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit,
    showNavigationIcon: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var devices by remember { mutableStateOf<List<NetworkDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var nmapInstalled by remember { mutableStateOf(true) }
    var installingNmap by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var lastScanAt by remember { mutableStateOf<Long?>(null) }
    var lastScanDurationMs by remember { mutableStateOf<Long?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.BY_IP) }
    var autoRefresh by remember { mutableStateOf(false) }

    fun checkNmapAndScan() {
        scope.launch {
            loading = true
            errorMessage = ""
            val startedAt = System.currentTimeMillis()

            val check = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                "which nmap"
            )
            if (check.isBlank() || check.startsWith("[err]")) {
                nmapInstalled = false
                loading = false
                return@launch
            }
            nmapInstalled = true

            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                buildScanCommand(),
                45000
            )

            if (raw.isBlank() || raw.startsWith("[err]")) {
                errorMessage = raw.ifBlank { "Aucune réponse du Raspberry Pi" }
                devices = emptyList()
            } else {
                devices = parseNmapOutput(raw)
                if (devices.isEmpty() && raw.isNotBlank()) {
                    errorMessage = ""
                }
            }
            lastScanAt = System.currentTimeMillis()
            lastScanDurationMs = lastScanAt!! - startedAt
            loading = false
        }
    }

    fun installNmap() {
        scope.launch {
            installingNmap = true
            SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                "sudo apt-get update -qq && sudo apt-get install -y nmap"
            )
            installingNmap = false
            checkNmapAndScan()
        }
    }

    LaunchedEffect(Unit) {
        checkNmapAndScan()
    }

    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            delay(30_000)
            if (!loading) checkNmapAndScan()
        }
    }

    val filteredDevices = remember(devices, searchQuery, sortMode) {
        val base = if (searchQuery.isBlank()) {
            devices
        } else {
            val q = searchQuery.trim().lowercase()
            devices.filter {
                it.ip.contains(q) || it.hostname.lowercase().contains(q) ||
                        it.mac.lowercase().contains(q) || it.vendor.lowercase().contains(q)
            }
        }
        sortDevices(base, sortMode)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { data ->
            Snackbar(snackbarData = data)
        } },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.net_scan_title), fontWeight = FontWeight.Bold)
                            if (!loading) {
                                val subtitle = buildString {
                                    append("${filteredDevices.size}")
                                    append(if (filteredDevices.size != devices.size) "/${devices.size}" else "")
                                    append(" ${stringResource(R.string.net_scan_devices_found)}")
                                    lastScanAt?.let { append(" • ${formatElapsedSince(it)}") }
                                }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (showNavigationIcon) {
                            IconButton(onClick = onOpenMenu) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(
                                if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Rechercher"
                            )
                        }
                        IconButton(onClick = {
                            sortMode = if (sortMode == SortMode.BY_IP) SortMode.BY_NAME else SortMode.BY_IP
                        }) {
                            Icon(Icons.Default.SortByAlpha, contentDescription = "Trier")
                        }
                        IconButton(onClick = { autoRefresh = !autoRefresh }) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = "Actualisation auto",
                                tint = if (autoRefresh) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { checkNmapAndScan() }, enabled = !loading && nmapInstalled) {
                            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                )
                AnimatedVisibility(visible = showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("IP, nom, MAC, fabricant…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = null)
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !nmapInstalled -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.SearchOff, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.net_scan_nmap_not_found), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { installNmap() }, enabled = !installingNmap) {
                            if (installingNmap) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.net_scan_install_nmap))
                            } else {
                                Text("Installer nmap")
                            }
                        }
                    }
                }

                loading && devices.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.net_scan_loading))
                    }
                }

                errorMessage.isNotEmpty() && devices.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.SearchOff, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            errorMessage,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { checkNmapAndScan() }) { Text(stringResource(R.string.action_refresh)) }
                    }
                }

                devices.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.DevicesOther, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.net_scan_empty))
                    }
                }

                filteredDevices.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.SearchOff, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Aucun appareil ne correspond à « $searchQuery »")
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredDevices, key = { it.ip }) { device ->
                            DeviceCard(
                                device = device,
                                isGateway = isGatewayIp(device.ip),
                                onCopyIp = {
                                    clipboard.setText(AnnotatedString(device.ip))
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Adresse IP copiée : ${device.ip}")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceCard(
    device: NetworkDevice,
    isGateway: Boolean = false,
    onCopyIp: () -> Unit = {}
) {
    val hostname = device.hostname.ifBlank { stringResource(R.string.net_scan_unknown_host) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onCopyIp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGateway) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isGateway -> Icons.Default.Router

                        hostname.contains("phone", ignoreCase = true) ||
                                hostname.contains("android", ignoreCase = true) ||
                                hostname.contains("s24", ignoreCase = true) ||
                                hostname.contains("samsung", ignoreCase = true) ||
                                device.vendor.contains("Samsung", ignoreCase = true) -> Icons.Default.Smartphone

                        hostname.contains("laptop", ignoreCase = true) ||
                                hostname.contains("macbook", ignoreCase = true) ||
                                hostname.contains("nitro", ignoreCase = true) -> Icons.Default.Laptop

                        hostname.contains("raspberry", ignoreCase = true) ||
                                hostname.contains("rillmaster", ignoreCase = true) ||
                                hostname.contains("pi", ignoreCase = true) -> Icons.Default.Memory

                        device.vendor.contains("Apple", ignoreCase = true) -> Icons.Default.LaptopMac

                        else -> Icons.Default.Devices
                    },
                    contentDescription = null,
                    tint = if (isGateway) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = hostname,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isGateway) {
                        Spacer(Modifier.width(6.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                            Text("Passerelle", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
                Text(
                    text = device.ip,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
                if (device.mac.isNotEmpty()) {
                    Text(
                        text = buildString {
                            append(device.mac)
                            if (device.vendor.isNotEmpty()) append(" • ${device.vendor}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Badge(
                containerColor = Color(0xFF4CAF50),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    stringResource(R.string.net_scan_device_online),
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}