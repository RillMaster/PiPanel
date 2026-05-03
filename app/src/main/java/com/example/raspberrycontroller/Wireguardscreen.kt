package com.example.raspberrycontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                age_s = now - hs
                if hs == 0: hs_str = 'Jamais'; online = False
                elif age_s < 60: hs_str = str(age_s) + 's'; online = True
                elif age_s < 3600: hs_str = str(age_s // 60) + 'min'; online = age_s < 180
                else: hs_str = str(age_s // 3600) + 'h'; online = False
            except: hs_str = '?'; online = False
            try: rx = int(parts[6]); tx = int(parts[7])
            except: rx = 0; tx = 0
            print('PEER:' + name + '|' + peer_pub + '|' + endpoint + '|' + allowed + '|' + hs_str + '|' + str(rx) + '|' + str(tx) + '|' + str(1 if online else 0))
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
                    peers.add(WgPeer(name, p[1], p[2].ifBlank{"Jamais"}, p[3], p[4], p[5].toLongOrNull()?:0L, p[6].toLongOrNull()?:0L, p[7]=="1"))
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

internal suspend fun restartWireGuard(settings: SettingsManager, ifaceName: String): Boolean {
    val cmd = "systemctl restart wg-quick@$ifaceName"
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

internal suspend fun addWgPeer(settings: SettingsManager, iface: String, clientName: String, dns: String, allowed: String, endpointHost: String, endpointPort: Int): Result<String> {
    val script = """
python3 -c "
import subprocess, re, sys
def run(cmd): return subprocess.check_output(cmd, shell=True, text=True, stderr=subprocess.DEVNULL).strip()
try:
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
    
    android.util.Log.e("WireGuard", "Tentative de création de client...")
    val res = SshClient.execute(settings.host, settings.port, settings.username, settings.password, script, settings.sshTimeoutMs).trim()
    android.util.Log.e("WireGuard", "Réponse création: $res")

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
    
    // Si l'hôte contient déjà un port (ex: 1.2.3.4:5678), on ne rajoute pas le port par défaut
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

// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardScreen(settings: SettingsManager, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var wgStatus by remember { mutableStateOf<WgStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var toggling by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var snackMsg by remember { mutableStateOf<String?>(null) }
    val snackState = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<WgPeer?>(null) }
    var showConfigDialog by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch { loading = true; wgStatus = fetchWgStatus(settings); loading = false }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(snackMsg) { snackMsg?.let { snackState.showSnackbar(it); snackMsg = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WireGuard", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            if (wgStatus != null) {
                FloatingActionButton(onClick = { showAddDialog = true }, containerColor = WgGreen, contentColor = Color.White) {
                    Icon(Icons.Default.Add, "Ajouter un client")
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
                                delay(1000); refresh()
                                snackMsg = if (!s.isUp) "Démarré" else "Arrêté"
                            } else snackMsg = "Erreur"
                            toggling = false
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WgStatCard("Configurés", s.peers.size.toString(), Icons.Default.Group, WgBlue, Modifier.weight(1f))
                        WgStatCard("Connectés", s.peers.count { it.isOnline }.toString(), Icons.Default.Wifi, WgGreen, Modifier.weight(1f))
                    }
                    Text("Clients (${s.peers.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.peers.forEach { peer ->
                        WgPeerCard(peer, onRename = { showRenameDialog = peer }) {
                            scope.launch {
                                restarting = true
                                if (deleteWgPeer(settings, s.interfaceName, peer.publicKey)) {
                                    if (restartWireGuard(settings, s.interfaceName)) {
                                        delay(1000)
                                        refresh()
                                        snackMsg = "Client supprimé et WireGuard redémarré"
                                    } else {
                                        snackMsg = "Client supprimé mais erreur au redémarrage"
                                    }
                                } else {
                                    snackMsg = "Erreur lors de la suppression"
                                }
                                restarting = false
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
                            Text("Redémarrage de WireGuard...", fontWeight = FontWeight.Medium)
                            Text("Veuillez patienter...", style = MaterialTheme.typography.bodySmall)
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
        ) { name, dns, allowed, endpoint, port ->
            showAddDialog = false; loading = true
            scope.launch {
                val result = addWgPeer(settings, wgStatus?.interfaceName ?: "wg0", name, dns, allowed, endpoint, port)
                result.onSuccess { config ->
                    showConfigDialog = config
                    refresh()
                }.onFailure { 
                    snackMsg = it.message ?: "Erreur lors de la création"
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
                    snackMsg = "Client renommé"
                } else {
                    snackMsg = "Erreur lors du renommage"
                }
                loading = false
            }
        }
    }

    showConfigDialog?.let { config ->
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { showConfigDialog = null },
            title = { Text("Configuration Client") },
            text = { Text(config, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            confirmButton = {
                Button(onClick = {
                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("WG Config", config))
                    showConfigDialog = null
                    
                    scope.launch {
                        restarting = true
                        if (restartWireGuard(settings, wgStatus?.interfaceName ?: "wg0")) {
                            delay(1000)
                            refresh()
                            snackMsg = "Config copiée et WireGuard redémarré"
                        } else {
                            snackMsg = "Config copiée mais erreur au redémarrage"
                        }
                        restarting = false
                    }
                }) { Text("Copier et Fermer") }
            }

        )
    }
}

@Composable
fun AddPeerDialog(defaultEndpoint: String, defaultPort: Int, onDismiss: () -> Unit, onConfirm: (String, String, String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var dns by remember { mutableStateOf("10.0.0.1") }
    var allowed by remember { mutableStateOf("0.0.0.0/0") }
    var endpoint by remember { mutableStateOf(defaultEndpoint) }
    var port by remember { mutableStateOf(defaultPort.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau Client") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom du client") },
                    placeholder = { Text("ex: Pc RillMaster") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = endpoint, 
                    onValueChange = { endpoint = it }, 
                    label = { Text("Endpoint (IP Publique ou DNS)") },
                    placeholder = { Text("ex: 82.225.223.88") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (it.all { char -> char.isDigit() }) port = it },
                    label = { Text("Port de l'interface (Serveur)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dns, 
                    onValueChange = { dns = it }, 
                    label = { Text("DNS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = allowed, 
                    onValueChange = { allowed = it }, 
                    label = { Text("Allowed IPs (Client)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { 
            Button(
                onClick = { onConfirm(name, dns, allowed, endpoint, port.toIntOrNull() ?: defaultPort) },
                enabled = endpoint.isNotEmpty() && port.isNotEmpty() && name.isNotEmpty()
            ) { Text("Créer") } 
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun RenamePeerDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier le nom") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nouveau nom") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotEmpty()) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun WgInterfaceCard(status: WgStatus, toggling: Boolean, onToggle: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (status.isUp) WgGreen.copy(0.3f) else WgGrey.copy(0.2f))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(if (status.isUp) WgGreen else WgGrey))
                    Column {
                        Text(status.interfaceName, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                        Text("Port : ${status.listenPort}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(peer.name, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(if (peer.isOnline) "Actif · ${peer.lastHandshake}" else "Inactif · ${peer.lastHandshake}", style = MaterialTheme.typography.labelSmall, color = color)
                }
                IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "Renommer", modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Supprimer", tint = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
            WgInfoRow(Icons.Default.Router, "Endpoint", peer.endpoint)
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
        Text("$label: $value", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun WgStatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f Go".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f Mo".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.1f Ko".format(bytes / 1_024.0)
    else                    -> "$bytes o"
}
