package com.example.raspberrycontroller

import android.annotation.SuppressLint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PiHoleRed   = Color(0xFFEF5350)
private val PiHoleGreen = Color(0xFF66BB6A)
private val PiHoleBlue  = Color(0xFF42A5F5)
private val PiHoleAmber = Color(0xFFFFA726)

data class PiHoleStats(
    val enabled         : Boolean,
    val domainsBlocked  : Int,
    val dnsQueriesToday : Int,
    val adsBlockedToday : Int,
    val adsPercentage   : Double,
    val uniqueDomains   : Int,
    val queriesCached   : Int,
    val clientsEverSeen : Int
)

@Suppress("SpellCheckingInspection")
fun buildFetchScript(password: String): String {
    val esc = password.replace("\\", "\\\\").replace("'", "\\'")

    return """python3 << 'PYEOF'
import urllib.request, json

base = 'http://localhost/api'
pwd  = '$esc'

def do_post(path, data):
    body = json.dumps(data).encode()
    req  = urllib.request.Request(
        base + path,
        data=body,
        headers={'Content-Type': 'application/json'}
    )
    return json.loads(urllib.request.urlopen(req, timeout=5).read())

def do_get(path, sid, csrf):
    req = urllib.request.Request(base + path)
    req.add_header('X-FTL-SID', sid)
    req.add_header('X-FTL-CSRF', csrf)
    return json.loads(urllib.request.urlopen(req, timeout=5).read())

try:
    auth = do_post('/auth', {'password': pwd})
    session = auth.get('session', {})

    sid   = session.get('sid', '')
    csrf  = session.get('csrf', '')
    valid = session.get('valid', False)

    if not valid or not sid or not csrf:
        print('auth_error|invalid_credentials')
        exit(1)

    stats = do_get('/stats/summary', sid, csrf)
    block = do_get('/dns/blocking', sid, csrf)

    enabled = block.get('blocking', 'unknown')

    queries = stats.get('queries', {})
    gravity = stats.get('gravity', {})
    clients = stats.get('clients', {})

    print(
        str(enabled) + '|' +
        str(gravity.get('domains_being_blocked', 0)) + '|' +
        str(queries.get('total', 0)) + '|' +
        str(queries.get('blocked', 0)) + '|' +
        str(queries.get('percent_blocked', 0.0)) + '|' +
        str(queries.get('unique_domains', 0)) + '|' +
        str(queries.get('cached', 0)) + '|' +
        str(clients.get('total', 0))
    )

except Exception as e:
    print('error|' + str(e))
PYEOF
"""
}

@Suppress("SpellCheckingInspection")
internal fun buildToggleScript(settings: SettingsManager, enable: Boolean): String {
    val sshEsc = settings.password.replace("'", "'\\''")
    val piEsc  = settings.piHolePassword.replace("\\", "\\\\").replace("'", "\\'")
    val pythonBool = if (enable) "True" else "False"
    val cliCmd = if (enable) "enable" else "disable"

    return """python3 << 'PYEOF' || (echo '$sshEsc' | sudo -S pihole $cliCmd && echo 'toggle_ok')
import urllib.request, json, sys
try:
    base = 'http://localhost/api'
    pwd  = '$piEsc'
    
    auth_data = json.dumps({'password': pwd}).encode()
    auth_req = urllib.request.Request(base + '/auth', data=auth_data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(auth_req, timeout=5) as r:
        auth = json.loads(r.read())
    
    sid = auth.get('session', {}).get('sid')
    csrf = auth.get('session', {}).get('csrf')
    
    if sid and csrf:
        toggle_data = json.dumps({'blocking': $pythonBool}).encode()
        toggle_req = urllib.request.Request(
            base + '/dns/blocking',
            data=toggle_data,
            method='POST',
            headers={
                'Content-Type': 'application/json',
                'X-FTL-SID': sid,
                'X-FTL-CSRF': csrf
            }
        )
        with urllib.request.urlopen(toggle_req, timeout=5) as r:
            res = r.read().decode()
        print('API_V6_OK|' + res)
        print('toggle_ok')
        sys.exit(0)
    else:
        print('API_V6_AUTH_FAILED')
except Exception as e:
    print('API_V6_EXCEPTION:' + str(e))
sys.exit(1)
PYEOF
"""
}

internal suspend fun fetchPiHoleStatus(settings: SettingsManager, password: String): PiHoleStats? {
    return try {
        val raw = SshClient.execute(
            settings.host, settings.port, settings.username, settings.password,
            buildFetchScript(password), settings.sshTimeoutMs
        ).trim()
        if (raw.startsWith("auth_error") || raw.startsWith("no_sid") || raw.startsWith("stats_error"))
            return null
        parseStatsOrNull(raw)
    } catch (_: Exception) { null }
}

internal suspend fun togglePiHole(settings: SettingsManager, enable: Boolean): Boolean {
    return try {
        val script = buildToggleScript(settings, enable)
        val result = SshClient.execute(
            settings.host, settings.port, settings.username, settings.password,
            script, settings.sshTimeoutMs
        ).trim()
        android.util.Log.e("PiHole", "Toggle result: $result")
        result.contains("toggle_ok")
    } catch (e: Exception) {
        android.util.Log.e("PiHole", "Toggle exception: ${e.message}")
        false
    }
}

// FIX : Pi-hole v6 retourne "enabled"/"disabled", pas "true"/"false"
private fun parseStatsOrNull(raw: String): PiHoleStats? {
    val parts = raw.split("|")
    if (parts.size < 8) return null
    val enabledStr = parts[0].trim()
    return PiHoleStats(
        enabled         = enabledStr == "enabled" || enabledStr == "true" || enabledStr == "True",
        domainsBlocked  = parts[1].trim().toIntOrNull() ?: 0,
        dnsQueriesToday = parts[2].trim().toIntOrNull() ?: 0,
        adsBlockedToday = parts[3].trim().toIntOrNull() ?: 0,
        adsPercentage   = parts[4].trim().replace(",", ".").toDoubleOrNull() ?: 0.0,
        uniqueDomains   = parts[5].trim().toIntOrNull() ?: 0,
        queriesCached   = parts[6].trim().toIntOrNull() ?: 0,
        clientsEverSeen = parts[7].trim().toIntOrNull() ?: 0
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiHoleScreen(
    settings     : SettingsManager,
    onClose      : () -> Unit,
    onOpenConfig : () -> Unit,
    onOpenMenu   : () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var stats      by remember { mutableStateOf<PiHoleStats?>(null) }
    var loading    by remember { mutableStateOf(true) }
    var toggling   by remember { mutableStateOf(false) }
    var errorRes   by remember { mutableStateOf<Int?>(null) }
    var errorArg   by remember { mutableStateOf<String?>(null) }
    var authError  by remember { mutableStateOf(false) }
    var snackRes   by remember { mutableStateOf<Int?>(null) }
    val snackState = remember { SnackbarHostState() }

    // 🔥 ANTI-SPAM GLOBAL (IMPORTANT)
    var lastRequestTime by remember { mutableLongStateOf(0L) }
    val minDelay = 2500L

    fun refresh() {
        scope.launch {

            val now = System.currentTimeMillis()
            if (now - lastRequestTime < minDelay) return@launch
            lastRequestTime = now

            loading = true
            errorRes = null
            errorArg = null
            authError = false

            val pwd = settings.piHolePassword

            val raw = try {
                SshClient.execute(
                    settings.host,
                    settings.port,
                    settings.username,
                    settings.password,
                    buildFetchScript(pwd),
                    settings.sshTimeoutMs
                ).trim()
            } catch (_: Exception) {
                null
            }

            when {
                raw == null ->
                    errorRes = R.string.error_pihole_connection

                raw.startsWith("auth_error") || raw.startsWith("no_sid") -> {
                    authError = true
                    errorRes = R.string.error_pihole_password
                }

                raw.startsWith("stats_error") -> {
                    errorRes = R.string.error_stats_prefix
                    errorArg = raw.substringAfter("|")
                }

                else -> {
                    val parsed = parseStatsOrNull(raw)
                    if (parsed != null) {
                        stats = parsed
                        errorRes = null
                        errorArg = null
                        authError = false
                    } else {
                        errorRes = R.string.error_unexpected_response
                        errorArg = raw
                    }
                }
            }

            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 🔥 AUTO REFRESH PROTÉGÉ
    LaunchedEffect(Unit) {
        while (true) {
            val delaySec = settings.piHoleRefreshDelaySec.toLong().coerceAtLeast(15L)
            delay(delaySec * 1000L)

            val now = System.currentTimeMillis()

            if (settings.piHoleAutoRefresh &&
                !toggling &&
                now - lastRequestTime > minDelay) {

                lastRequestTime = now

                val result = fetchPiHoleStatus(settings, settings.piHolePassword)
                if (result != null) {
                    stats = result
                    errorRes = null
                    errorArg = null
                }
            }
        }
    }

    LaunchedEffect(snackRes) {
        snackRes?.let {
            snackState.showSnackbar(context.run { getString(it) })
            snackRes = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.pihole_title), fontWeight = FontWeight.Bold)
                        stats?.let { s ->
                            Box(modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (s.enabled) PiHoleGreen.copy(0.15f) else PiHoleRed.copy(0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (s.enabled) stringResource(R.string.status_active) else stringResource(R.string.status_inactive),
                                    fontSize = 10.sp,
                                    color = if (s.enabled) PiHoleGreen else PiHoleRed,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenConfig) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.pihole_config_desc))
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.docker_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                loading && stats == null -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.connecting_to_pihole), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                errorRes != null -> {
                    val message = errorArg?.let { stringResource(errorRes!!, it) } ?: stringResource(errorRes!!)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Column {
                                Text(stringResource(R.string.error_fetch_stats), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    if (authError) {
                        Button(onClick = onOpenConfig, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.pihole_config_password))
                        }
                    } else {
                        Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }

                stats != null -> {
                    val s = stats!!

                    PiHoleToggleCard(
                        enabled  = s.enabled,
                        toggling = toggling,
                        onToggle = {
                            scope.launch {
                                toggling = true
                                val success = togglePiHole(settings, !s.enabled)
                                if (success) {
                                    delay(1500)
                                    val newStats = fetchPiHoleStatus(settings, settings.piHolePassword)
                                    if (newStats != null) stats = newStats
                                    snackRes = if (!s.enabled) R.string.pihole_active_msg else R.string.pihole_inactive_msg
                                } else {
                                    snackRes = R.string.error_state_change
                                }
                                toggling = false
                            }
                        }
                    )

                    PiHoleBlockingCard(s)

                    Text(stringResource(R.string.stats_today),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PiHoleStatCard(stringResource(R.string.dns_queries),        formatNumber(s.dnsQueriesToday), Icons.Default.Dns,    PiHoleBlue,  Modifier.weight(1f))
                        PiHoleStatCard(stringResource(R.string.ads_blocked), formatNumber(s.adsBlockedToday), Icons.Default.Block,  PiHoleRed,   Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PiHoleStatCard(stringResource(R.string.cached_domains), formatNumber(s.queriesCached),  Icons.Default.Memory,  PiHoleAmber, Modifier.weight(1f))
                        PiHoleStatCard(stringResource(R.string.clients_seen),       s.clientsEverSeen.toString(),   Icons.Default.Devices, PiHoleGreen, Modifier.weight(1f))
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null,
                                tint = PiHoleRed, modifier = Modifier.size(28.dp))
                            Column {
                                Text(stringResource(R.string.blocking_list), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.domains_blocked_count, formatNumber(s.domainsBlocked)),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.unique_domains_seen, formatNumber(s.uniqueDomains)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PiHoleToggleCard(enabled: Boolean, toggling: Boolean, onToggle: () -> Unit) {
    val bgColor     by animateColorAsState(if (enabled) PiHoleGreen.copy(0.12f) else PiHoleRed.copy(0.08f), label = "bg")
    val borderColor by animateColorAsState(if (enabled) PiHoleGreen.copy(0.4f)  else PiHoleRed.copy(0.25f), label = "border")

    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)) {
        Row(modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue = 0.6f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "alpha")
                Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(
                    (if (enabled) PiHoleGreen else PiHoleRed).copy(alpha = if (enabled) pulse else 1f)))
                Column {
                    Text(if (enabled) stringResource(R.string.pihole_status_active) else stringResource(R.string.pihole_status_inactive),
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(if (enabled) stringResource(R.string.pihole_filtering_active) else stringResource(R.string.pihole_filtering_inactive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (toggling) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            } else {
                Switch(checked = enabled, onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Color.White, checkedTrackColor   = PiHoleGreen,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = PiHoleRed.copy(0.4f)))
            }
        }
    }
}

@Composable
private fun PiHoleBlockingCard(s: PiHoleStats) {
    val pct   = (s.adsPercentage / 100.0).toFloat().coerceIn(0f, 1f)
    val color = when {
        s.adsPercentage >= 30 -> PiHoleGreen
        s.adsPercentage >= 10 -> PiHoleAmber
        else                  -> PiHoleRed
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.blocking_rate), style = MaterialTheme.typography.titleSmall)
                Text("%.1f%%".format(s.adsPercentage), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = color)
            }
            LinearProgressIndicator(
                progress   = { pct },
                modifier   = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color      = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(stringResource(R.string.blocking_summary, formatNumber(s.adsBlockedToday), formatNumber(s.dnsQueriesToday)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PiHoleStatCard(
    label   : String,
    value   : String,
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    color   : Color,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatNumber(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000     -> "%.1fk".format(n / 1_000.0)
    else           -> n.toString()
}
