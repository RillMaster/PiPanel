@file:Suppress("SpellCheckingInspection")
package com.rillmaster.pipanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Couleurs WireGuard ────────────────────────────────────────────────────────
private val WgGreen  = Color(0xFF4CAF50)
private val WgBlue   = Color(0xFF2196F3)
private val WgOrange = Color(0xFFFF9800)
private val WgGrey   = Color(0xFF9E9E9E)

// ── Modèles de données ────────────────────────────────────────────────────────
data class WgPeer(
    val name         : String,
    val publicKey    : String,
    val endpoint     : String,
    val allowedIPs   : String,
    val lastHandshake: String,
    val rxBytes      : Long,
    val txBytes      : Long,
    val isOnline     : Boolean
)

data class WgStatus(
    val interfaceName: String,
    val isUp         : Boolean,
    val publicKey    : String,
    val listenPort   : Int,
    val peers        : List<WgPeer>
)

// ── Scripts SSH ──────────────────────────────────────────────────────────────
private val WG_STATUS_SCRIPT = """
python3 -c "
import subprocess, re, time, os

try:
    out = subprocess.check_output(['sudo', 'wg', 'show', 'all', 'dump'], text=True, stderr=subprocess.DEVNULL)
    
    peer_names = {}
    ifaces = set()
    lines = [l.strip() for l in out.strip().split('\n') if l.strip()]
    for line in lines:
        parts = line.split('\t')
        if parts: ifaces.add(parts[0])
    
    for iface in ifaces:
        try:
            conf = subprocess.check_output(['sudo', 'cat', f'/etc/wireguard/{iface}.conf'], text=True, stderr=subprocess.DEVNULL)
            parts = re.split(r'(\[Peer\])', conf, flags=re.IGNORECASE)
            for i in range(1, len(parts), 2):
                body = parts[i+1]
                pub_match = re.search(r'PublicKey\s*=\s*(.*?)\n', body, re.IGNORECASE)
                if pub_match:
                    pub = pub_match.group(1).strip()
                    name_match = re.search(r'#\s*(.*?)\n', body)
                    name = name_match.group(1).strip() if name_match else ''
                    if not name:
                        prev_part = parts[i-1].strip().split('\n')
                        if prev_part and prev_part[-1].strip().startswith('#'):
                            name = prev_part[-1].strip()[1:].strip()
                    if pub: peer_names[pub] = name
        except: pass

    now = int(time.time())
    for line in lines:
        parts = line.split('\t')
        if len(parts) == 5 and parts[0] != 'off':
            print('IFACE:' + parts[0] + ':' + parts[2] + ':' + parts[3])
        elif len(parts) == 9:
            peer_pub = parts[1]
            name = peer_names.get(peer_pub, '')
            endpoint = parts[3] if parts[3] != '(none)' else ''
            allowed  = parts[4]
            try:
                hs = int(parts[5])
                age_s = now - hs if hs > 0 else -1
                online = (0 <= age_s < 180)
            except: age_s = -2; online = False
            try: rx = int(parts[6]); tx = int(parts[7])
            except: rx = 0; tx = 0
            print('PEER:' + name + '|' + peer_pub + '|' + endpoint + '|' + allowed + '|' + str(age_s) + '|' + str(rx) + '|' + str(tx) + '|' + str(1 if online else 0))
except Exception as e:
    print('ERROR:' + str(e))
"
""".trimIndent()

internal suspend fun fetchWgStatus(settings: SettingsManager): WgStatus? {
    return try {
        val raw = SshClient.execute(settings.host, settings.port, settings.username, settings.password, WG_STATUS_SCRIPT, settings.sshTimeoutMs).trim()
        if (raw.startsWith("ERROR") || raw == "NO_INTERFACE") return null
        val lines = raw.lines()
        var iface = "wg0"; var pub = ""; var port = 51820; val peers = mutableListOf<WgPeer>()
        for (line in lines) {
            if (line.startsWith("IFACE:")) {
                val p = line.removePrefix("IFACE:").split(":")
                iface = p.getOrElse(0){"wg0"}; pub = p.getOrElse(1){""}; port = p.getOrElse(2){"51820"}.toIntOrNull() ?: 51820
            } else if (line.startsWith("PEER:")) {
                val p = line.removePrefix("PEER:").split("|")
                if (p.size >= 8) {
                    val name = p[0].ifBlank { p[1].take(8) + "…" }
                    val ageS = p[4].toLongOrNull() ?: -2L
                    val hsStr = when {
                        ageS == -1L -> "never"
                        ageS < 0    -> "?"
                        ageS < 60   -> "${ageS}s"
                        ageS < 3600 -> "${ageS / 60}min"
                        else        -> "${ageS / 3600}h"
                    }
                    peers.add(WgPeer(name, p[1], p[2].ifBlank{"never"}, p[3], hsStr, p[5].toLongOrNull()?:0L, p[6].toLongOrNull()?:0L, p[7]=="1"))
                }
            }
        }
        val isUp = (SshClient.execute(settings.host, settings.port, settings.username, settings.password, "ip link show $iface 2>/dev/null | grep -c 'state UP' || echo 0", settings.sshTimeoutMs).trim().toIntOrNull() ?: 0) > 0
        WgStatus(iface, isUp || peers.isNotEmpty(), pub, port, peers)
    } catch (_: Exception) { null }
}

internal suspend fun toggleWireGuard(settings: SettingsManager, ifaceName: String, enable: Boolean): Boolean {
    val action = if (enable) "start" else "stop"
    val cmd = "systemctl $action wg-quick@$ifaceName"
    val res = SshClient.execute(settings.host, settings.port, settings.username, settings.password, "echo '${settings.password}' | sudo -S $cmd && echo 'ok'", settings.sshTimeoutMs).trim()
    return res.split("\n").any { it.trim() == "ok" }
}

internal suspend fun deleteWgPeer(settings: SettingsManager, iface: String, pubKey: String): Boolean {
    val script = """
python3 -c "
import sys, re, subprocess
conf_path = f'/etc/wireguard/$iface.conf'
try:
    content = subprocess.check_output(['sudo', 'cat', conf_path], text=True)
    sections = re.split(r'(\[Peer\])', content, flags=re.IGNORECASE)
    new_parts = [sections[0]]
    found = False
    for i in range(1, len(sections), 2):
        header = sections[i]
        body = sections[i+1]
        if '$pubKey' not in body:
            new_parts.append(header)
            new_parts.append(body)
        else:
            found = True
    
    if found:
        new_content = ''.join(new_parts)
        process = subprocess.Popen(['sudo', 'tee', conf_path], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, text=True)
        process.communicate(input=new_content)
        subprocess.run(['sudo', 'wg', 'set', '$iface', 'peer', '$pubKey', 'remove'])
        print('ok')
    else:
        print('NOT_FOUND')
except Exception as e:
    print('ERROR:' + str(e))
"
""".trimIndent()
    val res = SshClient.execute(settings.host, settings.port, settings.username, settings.password, script, settings.sshTimeoutMs).trim()
    return res.split("\n").any { it.trim() == "ok" }
}

internal suspend fun renameWgPeer(settings: SettingsManager, iface: String, pubKey: String, newName: String): Boolean {
    val script = """
python3 -c "
import sys, re, subprocess
conf_path = f'/etc/wireguard/$iface.conf'
try:
    content = subprocess.check_output(['sudo', 'cat', conf_path], text=True)
    sections = re.split(r'(\[Peer\])', content, flags=re.IGNORECASE)
    new_parts = [sections[0]]
    found = False
    for i in range(1, len(sections), 2):
        header = sections[i]
        body = sections[i+1]
        if '$pubKey' in body:
            body = re.sub(r'^\s*#.*?\n', '', body, flags=re.MULTILINE)
            body = f'\n# $newName\n' + body.lstrip()
            found = True
        new_parts.append(header)
        new_parts.append(body)
    
    if found:
        new_content = ''.join(new_parts)
        process = subprocess.Popen(['sudo', 'tee', conf_path], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, text=True)
        process.communicate(input=new_content)
        print('ok')
    else:
        print('NOT_FOUND')
except Exception as e:
    print('ERROR:' + str(e))
"
""".trimIndent()
    val res = SshClient.execute(settings.host, settings.port, settings.username, settings.password, script, settings.sshTimeoutMs).trim()
    return res.split("\n").any { it.trim() == "ok" }
}

internal suspend fun addWgPeer(
    settings: SettingsManager,
    iface: String,
    clientName: String,
    dns: String,
    allowed: String,
    endpointHost: String,
    endpointPort: Int,
    scannedPublicKey: String? = null
): Result<String> {
    val pubParam = scannedPublicKey ?: ""
    val script = """
python3 -c "
import subprocess, re, sys
def run(cmd): return subprocess.check_output(cmd, shell=True, text=True, stderr=subprocess.DEVNULL).strip()
try:
    pub_input = '$pubParam'
    if pub_input:
        pub = pub_input
        priv = ''
    else:
        priv = run('wg genkey')
        pub = run('echo ' + priv + ' | wg pubkey')
    
    conf_path = f'/etc/wireguard/$iface.conf'
    conf = run(f'echo \"${settings.password}\" | sudo -S cat {conf_path}')
    
    ips = re.findall(r'AllowedIPs\s*=\s*(\d+\.\d+\.\d+\.\d+)', conf)
    last_ip = '10.0.0.1'
    if ips:
        used_ips = []
        for ip in ips:
            try: used_ips.append(int(ip.split('.')[-1]))
            except: pass
        if used_ips:
            next_octet = max(used_ips) + 1
            parts = ips[0].split('.')
            parts[-1] = str(next_octet)
            last_ip = '.'.join(parts)
    
    peer_config = f'\n[Peer]\n# $clientName\nPublicKey = {pub}\nAllowedIPs = {last_ip}/32\n'
    process = subprocess.Popen(['sudo', 'tee', '-a', conf_path], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, text=True)
    process.communicate(input=peer_config)
    
    # On applique en live
    subprocess.run(['sudo', 'wg', 'set', '$iface', 'peer', pub, 'allowed-ips', f'{last_ip}/32'])

    print('OK|' + priv + '|' + pub + '|' + last_ip)
except Exception as e:
    print('ERROR|' + str(e))
"
""".trimIndent()
    
    android.util.Log.e("WireGuard", "Tentative de création de client\u00A0.")
    val res = SshClient.execute(settings.host, settings.port, settings.username, settings.password, script, settings.sshTimeoutMs).trim()
    android.util.Log.e("WireGuard", "Réponse création\u00A0: $res.")

    val lines = res.split("\n")
    val okLine = lines.find { it.trim().startsWith("OK|") }
    if (okLine == null) {
        val errorLine = lines.find { it.trim().startsWith("ERROR|") }
        if (errorLine != null) {
            return Result.failure(Exception(errorLine.substringAfter("ERROR|").trim()))
        }
        if (res.contains("❌") || res.contains("🌐") || res.contains("⏱️")) {
            return Result.failure(Exception(res))
        }
        return Result.failure(Exception("Erreur lors de la création du client (Vérifiez les permissions)"))
    }
    
    val p = okLine.trim().split("|")
    val status = fetchWgStatus(settings) ?: return Result.failure(Exception("Impossible de récupérer le statut pour finaliser la config"))
    
    // Si on a scanné une clé publique, on ne peut pas générer la config client complète (manque sa clé privée)
    if (scannedPublicKey != null && p[1].isEmpty()) {
        return Result.success("PEER_ADDED_ONLY")
    }


    val finalEndpoint = if (endpointHost.contains(":")) endpointHost else "$endpointHost:$endpointPort"

    val config = """
[Interface]
PrivateKey = ${p[1]}
Address = ${p[3]}/24
DNS = $dns

[Peer]
PublicKey = ${status.publicKey}
AllowedIPs = $allowed
Endpoint = $finalEndpoint
PersistentKeepalive = 25
""".trimIndent()
    
    return Result.success(config)
}

internal suspend fun getPublicKeyFromPrivate(settings: SettingsManager, privateKey: String): String? {
    val res = SshClient.execute(settings.host, settings.port, settings.username, settings.password, "echo '$privateKey' | wg pubkey", settings.sshTimeoutMs).trim()
    return if (res.length == 44 && res.endsWith("=")) res else null
}

// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardScreen(
    settings: SettingsManager,
    onOpenMenu: () -> Unit,
    showNavigationIcon: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wgStatus by remember { mutableStateOf<WgStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var toggling by remember { mutableStateOf(false) }
    val restarting by remember { mutableStateOf(false) }
    var snackMsg by remember { mutableStateOf<String?>(null) }
    val snackState = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<WgPeer?>(null) }
    var showConfigDialog by remember { mutableStateOf<String?>(null) }

    // Pre-fetch strings to avoid LocalContext.current warnings in lambdas
    val wgStarted = stringResource(R.string.wg_started)
    val wgStopped = stringResource(R.string.wg_stopped)
    val updateError = stringResource(R.string.update_error)
    val clientDeleted = stringResource(R.string.wg_client_deleted)
    val errorDeletion = stringResource(R.string.wg_error_deletion)
    val clientAddedSuccess = stringResource(R.string.wg_client_added_success)
    val dockerError = stringResource(R.string.docker_error)
    val clientRenamed = stringResource(R.string.wg_client_renamed)
    val errorRenaming = stringResource(R.string.wg_error_renaming)
    val configCopied = stringResource(R.string.wg_config_copied)

    fun refresh(showLoading: Boolean = true) {
        scope.launch { 
            if (showLoading) loading = true
            val status = fetchWgStatus(settings)
            wgStatus = status
            loading = false 
        }
    }
    
    // Refresh automatique toutes les 30 secondes
    LaunchedEffect(Unit) {
        refresh(showLoading = true)
        while (true) {
            delay(30.seconds)
            refresh(showLoading = false)
        }
    }
    LaunchedEffect(snackMsg) { snackMsg?.let { snackState.showSnackbar(it); snackMsg = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.nav_wireguard), 
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    ) 
                },
                navigationIcon = { 
                    if (showNavigationIcon) {
                        IconButton(onClick = onOpenMenu) { 
                            Icon(Icons.Default.Menu, stringResource(R.string.open_menu)) 
                        } 
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                    }
                }
            )
        },
        floatingActionButton = {
            if (wgStatus != null) {
                FloatingActionButton(onClick = { showAddDialog = true }, containerColor = WgGreen, contentColor = Color.White) {
                    Icon(Icons.Default.Add, stringResource(R.string.action_add_client))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackState) }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                wgStatus?.let { s ->
                    WgInterfaceCard(s, toggling || restarting) {
                        scope.launch {
                            toggling = true
                            if (toggleWireGuard(settings, s.interfaceName, !s.isUp)) {
                                delay(1.seconds); refresh()
                                snackMsg = if (!s.isUp) wgStarted else wgStopped
                            } else snackMsg = updateError
                            toggling = false
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WgStatCard(stringResource(R.string.wg_status_configured), s.peers.size.toString(), Icons.Default.Group, WgBlue, Modifier.weight(1f))
                        WgStatCard(stringResource(R.string.wg_status_connected), s.peers.count { it.isOnline }.toString(), Icons.Default.Wifi, WgGreen, Modifier.weight(1f))
                    }
                    Text(
                        stringResource(R.string.wg_clients_count, s.peers.size), 
                        style = MaterialTheme.typography.titleSmall, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    s.peers.forEach { peer ->
                        WgPeerCard(peer, onRename = { showRenameDialog = peer }) {
                            scope.launch {
                                loading = true
                                if (deleteWgPeer(settings, s.interfaceName, peer.publicKey)) {
                                    delay(500.milliseconds)
                                    refresh()
                                    snackMsg = clientDeleted
                                } else {
                                    snackMsg = errorDeletion
                                }
                                loading = false
                            }
                        }
                    }

                }
            }

            if (restarting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.wg_restarting), fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.wg_please_wait), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddPeerDialog(
            defaultEndpoint = settings.host,
            defaultPort = wgStatus?.listenPort ?: 51820,
            onDismiss = { showAddDialog = false }
        ) { name, dns, allowed, endpoint, port, pubKey ->
            showAddDialog = false; loading = true
            scope.launch {
                var finalPubKey = pubKey
                // Si on a scanné une clé privée, on en déduit la publique sur le serveur
                if (pubKey != null && pubKey.length == 44 && pubKey.endsWith("=")) {
                    // On vérifie si c'est une clé privée ou publique en demandant au serveur
                    val derived = getPublicKeyFromPrivate(settings, pubKey)
                    if (derived != null) finalPubKey = derived
                }

                val result = addWgPeer(settings, wgStatus?.interfaceName ?: "wg0", name, dns, allowed, endpoint, port, finalPubKey)
                result.onSuccess { config ->
                    if (config == "PEER_ADDED_ONLY") {
                        snackMsg = clientAddedSuccess
                    } else {
                        showConfigDialog = config
                    }
                    refresh()
                }.onFailure { 
                    snackMsg = it.message ?: dockerError
                }
                loading = false
            }
        }
    }

    showRenameDialog?.let { peer ->
        RenamePeerDialog(
            currentName = peer.name,
            onDismiss = { showRenameDialog = null }
        ) { newName ->
            showRenameDialog = null; loading = true
            scope.launch {
                if (renameWgPeer(settings, wgStatus?.interfaceName ?: "wg0", peer.publicKey, newName)) {
                    refresh()
                    snackMsg = clientRenamed
                } else {
                    snackMsg = errorRenaming
                }
                loading = false
            }
        }
    }

    showConfigDialog?.let { config ->
        var mode by remember { mutableStateOf("text") } // "text" ou "qr"

        AlertDialog(
            onDismissRequest = { showConfigDialog = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.wg_config_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Row {
                        IconButton(onClick = { mode = "text" }) {
                            Icon(Icons.Default.ContentCopy, "Texte", tint = if (mode == "text") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { mode = "qr" }) {
                            Icon(Icons.Default.QrCode, "QR Code", tint = if (mode == "qr") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (mode == "text") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                config,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        }
                    } else {
                        val qrBitmap = remember(config) { generateQrCode(config) }
                        if (qrBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR Code Configuration",
                                modifier = Modifier.size(240.dp).background(Color.White).padding(8.dp)
                            )
                        } else {
                            Text(stringResource(R.string.qr_code_error))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (mode == "text") {
                        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("WG Config", config))
                        snackMsg = configCopied
                    }
                    showConfigDialog = null
                    refresh()
                }) {
                    Text(if (mode == "text") stringResource(R.string.wg_config_copy_close) else stringResource(R.string.action_close))
                }
            }
        )
    }
}

@Composable
fun AddPeerDialog(
    defaultEndpoint: String,
    defaultPort: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, Int, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var dns by remember { mutableStateOf("10.0.0.1") }
    var allowed by remember { mutableStateOf("0.0.0.0/0") }
    var endpoint by remember { mutableStateOf(defaultEndpoint) }
    var port by remember { mutableStateOf(defaultPort.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wg_new_client_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.wg_client_name_label)) },
                    placeholder = { Text(stringResource(R.string.wg_client_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = endpoint, 
                    onValueChange = { endpoint = it }, 
                    label = { Text(stringResource(R.string.wg_endpoint_label)) },
                    placeholder = { Text(stringResource(R.string.wg_endpoint_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (it.all { char -> char.isDigit() }) port = it },
                    label = { Text(stringResource(R.string.wg_port_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dns, 
                    onValueChange = { dns = it }, 
                    label = { Text(stringResource(R.string.wg_dns_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = allowed, 
                    onValueChange = { allowed = it }, 
                    label = { Text(stringResource(R.string.wg_allowed_ips_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { 
            Button(
                onClick = { onConfirm(name, dns, allowed, endpoint, port.toIntOrNull() ?: defaultPort, null) },
                enabled = endpoint.isNotEmpty() && port.isNotEmpty() && name.isNotEmpty()
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
fun RenamePeerDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wg_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.wg_new_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotEmpty()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun WgInterfaceCard(status: WgStatus, toggling: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (status.isUp) WgGreen.copy(0.3f) else WgGrey.copy(0.2f))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(if (status.isUp) WgGreen else WgGrey))
                    Column {
                        Text(status.interfaceName, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                        Text(stringResource(R.string.wg_port_display, status.listenPort), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (toggling) CircularProgressIndicator(modifier = Modifier.size(32.dp))
                else Switch(checked = status.isUp, onCheckedChange = { onToggle() })
            }
            if (status.publicKey.isNotEmpty()) {
                Surface(onClick = {
                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("WG Pub", status.publicKey))
                }, color = Color.Transparent) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Key, null, modifier = Modifier.size(14.dp))
                        Text(status.publicKey, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WgPeerCard(peer: WgPeer, onRename: () -> Unit, onDelete: () -> Unit) {
    val color = if (peer.isOnline) WgGreen else WgGrey
    val neverStr = stringResource(R.string.wg_never)
    val handshake = if (peer.lastHandshake == "never") neverStr else peer.lastHandshake
    val endpoint = if (peer.endpoint == "never") neverStr else peer.endpoint

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        peer.name, 
                        fontWeight = FontWeight.Bold, 
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (peer.isOnline) stringResource(R.string.wg_active_handshake, handshake) else stringResource(R.string.wg_inactive_handshake, handshake), 
                        style = MaterialTheme.typography.labelSmall, 
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRename) { Icon(Icons.Default.Edit, stringResource(R.string.action_move), modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
            WgInfoRow(Icons.Default.Router, "Endpoint", endpoint)
            WgInfoRow(Icons.Default.AccountTree, "IPs", peer.allowedIPs)
            if (peer.rxBytes > 0 || peer.txBytes > 0) {
                Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(0.5f), RoundedCornerShape(8.dp)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(14.dp), tint = WgBlue)
                        Text(formatBytes(peer.rxBytes), style = MaterialTheme.typography.labelMedium, color = WgBlue, fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(14.dp), tint = WgOrange)
                        Text(formatBytes(peer.txBytes), style = MaterialTheme.typography.labelMedium, color = WgOrange, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun WgInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "$label\u00A0: $value",
            style = MaterialTheme.typography.labelSmall, 
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WgStatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}

private fun generateQrCode(text: String): Bitmap? {
    return try {
        val size = 512
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    this[x, y] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}
