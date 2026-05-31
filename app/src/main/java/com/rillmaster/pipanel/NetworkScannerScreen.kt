package com.rillmaster.pipanel

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class NetworkDevice(
    val ip: String,
    val hostname: String,
    val mac: String,
    val vendor: String
)

// Détecte le subnet via eth0, lance nmap -sn dessus
// Fonctionne sans sudo, sans Python, sans escaping complexe
// Script Python pour scanner le réseau local de manière robuste
// Retourne: ip|host|mac|vendor
private val SCAN_PYTHON_SCRIPT = """
import subprocess, re, base64

def run(cmd):
    try: return subprocess.check_output(cmd, shell=True, text=True, stderr=subprocess.STDOUT)
    except: return ''

# Récupère tous les CIDR IPv4 locaux (ex: 192.168.1.0/24)
subnets = re.findall(r'inet\s+(\d+\.\d+\.\d+\.\d+/\d+)', run('ip -4 addr show'))
subnets = [s for s in subnets if not s.startswith('127.')]

for sn in subnets:
    # Scan nmap (on essaie sans sudo car souvent suffisant pour -sn)
    out = run('nmap -sn ' + sn + ' 2>/dev/null')
    
    # Découpage par rapport
    for block in re.split(r'Nmap scan report for ', out)[1:]:
        lines = block.split('\n')
        header = lines[0].strip()
        
        # Format: "hostname (192.168.1.46)" ou juste "192.168.1.85"
        m = re.search(r'(.*?) \((.*?)\)', header)
        if m:
            host, ip = m.group(1), m.group(2)
        else:
            host, ip = '', header
            
        mac, vendor = '', ''
        for line in lines:
            if 'MAC Address:' in line:
                m_mac = re.search(r'MAC Address: ([0-9A-F:]{17})(?: \((.+?)\))?', line, re.I)
                if m_mac:
                    mac, vendor = m_mac.group(1), m_mac.group(2) or ''
        
        print(f"{ip}|{host}|{mac}|{vendor}")
""".trimIndent()

private val REPORT_REGEX =
    Regex("""Nmap scan report for (?:(\S+) \((\d+\.\d+\.\d+\.\d+)\)|(\d+\.\d+\.\d+\.\d+))""")
private val MAC_REGEX =
    Regex("""MAC Address: ([0-9A-F:]{17})(?: \((.+?)\))?""")

fun parseNmapOutput(output: String): List<NetworkDevice> {
    val devices = mutableListOf<NetworkDevice>()
    var currentIp = ""
    var currentHostname = ""

    output.lines().forEach { line ->
        val reportMatch = REPORT_REGEX.find(line)
        if (reportMatch != null) {
            if (currentIp.isNotEmpty() && (devices.isEmpty() || devices.last().ip != currentIp)) {
                devices.add(NetworkDevice(ip = currentIp, hostname = currentHostname, mac = "", vendor = ""))
            }
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
            return@forEach
        }
    }
    if (currentIp.isNotEmpty() && (devices.isEmpty() || devices.last().ip != currentIp)) {
        devices.add(NetworkDevice(ip = currentIp, hostname = currentHostname, mac = "", vendor = ""))
    }
    return devices
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScannerScreen(settings: SettingsManager, onClose: () -> Unit, onOpenMenu: () -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<NetworkDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var nmapInstalled by remember { mutableStateOf(true) }
    var installingNmap by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    fun checkNmapAndScan() {
        scope.launch {
            loading = true
            errorMessage = ""

            // Vérifie la présence de nmap
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

            // Encode le script en Base64 pour l'envoyer proprement
            val b64Script = android.util.Base64.encodeToString(
                SCAN_PYTHON_SCRIPT.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            
            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                "echo '$b64Script' | base64 -d | python3",
                30000
            )

            if (raw.isBlank() || raw.startsWith("[err]")) {
                // Si erreur, on tente un scan nmap direct très simple sans Python
                val simpleScan = SshClient.execute(
                    settings.host, settings.port, settings.username, settings.password,
                    "nmap -sn 192.168.1.0/24", // Fallback sur un range commun
                    30000
                )
                if (simpleScan.isNotBlank() && !simpleScan.startsWith("[err]")) {
                    devices = parseNmapOutput(simpleScan)
                } else {
                    errorMessage = raw.ifBlank { "Empty response from Pi" }
                    devices = emptyList()
                }
            } else {
                val list = mutableListOf<NetworkDevice>()
                raw.lines().forEach { line ->
                    val parts = line.split("|")
                    if (parts.size >= 4) {
                        list.add(NetworkDevice(
                            ip = parts[0],
                            hostname = parts[1].removeSuffix(".home").removeSuffix(".local"),
                            mac = parts[2],
                            vendor = parts[3]
                        ))
                    }
                }
                devices = list.sortedWith(compareBy {
                    it.ip.split(".").last().toIntOrNull() ?: 0
                })
            }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.net_scan_title), fontWeight = FontWeight.Bold)
                        if (devices.isNotEmpty() && !loading) {
                            Text(
                                "${devices.size} ${stringResource(R.string.net_scan_devices_found)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                    }
                },
                actions = {
                    IconButton(onClick = { checkNmapAndScan() }, enabled = !loading && nmapInstalled) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                // nmap non installé
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
                        Text(
                            stringResource(R.string.net_scan_nmap_not_found),
                            textAlign = TextAlign.Center
                        )
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

                // Chargement initial
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

                // Erreur SSH
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
                    }
                }

                // Aucun appareil trouvé
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

                // Liste des appareils
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(devices) { device ->
                            DeviceCard(device)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: NetworkDevice) {
    val hostname = device.hostname.ifBlank { stringResource(R.string.net_scan_unknown_host) }
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
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

                        // IP de gateway (.254 ou .1)
                        device.ip.endsWith(".254") || device.ip.endsWith(".1") -> Icons.Default.Router

                        else -> Icons.Default.Devices
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hostname,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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