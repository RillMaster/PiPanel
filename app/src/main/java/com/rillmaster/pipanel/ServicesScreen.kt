package com.rillmaster.pipanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class LinuxService(
    val name: String,
    val load: String,
    val active: String,
    val sub: String,
    val description: String
) {
    val isRunning: Boolean get() = active == "active"
    val isFailed: Boolean get() = active == "failed"
}

suspend fun fetchServices(settings: SettingsManager): Result<List<LinuxService>> {
    val raw = SshClient.execute(
        host = settings.host,
        port = settings.port,
        user = settings.username,
        password = settings.password,
        command = "systemctl list-units --type=service --all --no-legend --no-pager",
        timeoutMs = settings.sshTimeoutMs
    )
    if (raw.startsWith("[err]")) return Result.failure(Exception(raw))
    if (raw.isBlank()) return Result.success(emptyList())

    val services = raw.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), 5)
        if (parts.size >= 5) {
            LinuxService(
                name = parts[0],
                load = parts[1],
                active = parts[2],
                sub = parts[3],
                description = parts[4]
            )
        } else null
    }
    return Result.success(services)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    settings: SettingsManager,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit,
    showNavigationIcon: Boolean = true
) {
    val scope = rememberCoroutineScope()
    var services by remember { mutableStateOf<List<LinuxService>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var actionLoading by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedLogService by remember { mutableStateOf<LinuxService?>(null) }

    fun refresh() {
        scope.launch {
            isLoading = true
            fetchServices(settings).fold(
                onSuccess = { services = it; fetchError = null },
                onFailure = { if (services.isEmpty()) fetchError = it.message }
            )
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    val filteredServices = services.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
    }

    fun runAction(service: LinuxService, action: String) {
        scope.launch {
            actionLoading = actionLoading + (service.name to action)
            val cmd = "sudo systemctl $action ${service.name}"
            SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                cmd, settings.sshTimeoutMs
            )
            actionLoading = actionLoading - service.name
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.services_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (showNavigationIcon) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !isLoading) {
                        if (isLoading && services.isEmpty()) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text(stringResource(R.string.services_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            if (isLoading && services.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (fetchError != null && services.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(fetchError!!, color = MaterialTheme.colorScheme.error)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredServices) { service ->
                        ServiceCard(
                            service = service,
                            loadingAction = actionLoading[service.name],
                            onStart = { runAction(service, "start") },
                            onStop = { runAction(service, "stop") },
                            onRestart = { runAction(service, "restart") },
                            onLogs = { selectedLogService = service }
                        )
                    }
                    if (filteredServices.isEmpty() && !isLoading) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.services_no_result), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedLogService != null) {
        ServiceLogsDialog(
            service = selectedLogService!!,
            settings = settings,
            onDismiss = { selectedLogService = null }
        )
    }
}

@Composable
fun ServiceCard(
    service: LinuxService,
    loadingAction: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onLogs: () -> Unit
) {
    val busy = loadingAction != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        service.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        service.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ServiceStatusBadge(service)
            }

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!service.isRunning) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.services_start),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.services_stop),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onLogs,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .size(40.dp)
                ) {
                    Icon(Icons.Default.ListAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }

                Button(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.services_restart),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ServiceStatusBadge(service: LinuxService) {
    val (color, label) = when {
        service.isRunning -> Color(0xFF4CAF50) to stringResource(R.string.services_status_running)
        service.isFailed -> Color(0xFFF44336) to stringResource(R.string.services_status_failed)
        else -> Color.Gray to stringResource(R.string.services_status_stopped)
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun ServiceLogsDialog(
    service: LinuxService,
    settings: SettingsManager,
    onDismiss: () -> Unit
) {
    var logs by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isLive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Effet pour scroller vers le bas quand les logs changent en mode direct
    LaunchedEffect(logs) {
        if (isLive && logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    LaunchedEffect(service.name, isLive) {
        if (isLive) {
            while (true) {
                val raw = SshClient.execute(
                    settings.host, settings.port, settings.username, settings.password,
                    "sudo journalctl -u ${service.name} -n 100 --no-pager",
                    settings.sshTimeoutMs
                )
                logs = raw.ifBlank { "" }
                isLoading = false
                delay(3000)
            }
        } else {
            isLoading = true
            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                "sudo journalctl -u ${service.name} -n 100 --no-pager",
                settings.sshTimeoutMs
            )
            logs = raw.ifBlank { "" }
            isLoading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.services_logs_title, service.name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isLive) {
                            Surface(
                                color = Color.Red.copy(alpha = 0.15f),
                                shape = CircleShape,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color.Red))
                                    Text("LIVE", color = Color.Red, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(stringResource(R.string.services_logs_live), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = isLive,
                        onCheckedChange = { isLive = it },
                        modifier = Modifier.scale(0.8f),
                        thumbContent = if (isLive) {
                            { Icon(Icons.Default.Sensors, null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                        } else null
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .padding(8.dp)
                ) {
                    if (isLoading && logs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    } else {
                        if (logs.isNotEmpty()) {
                            Text(
                                text = logs,
                                color = Color(0xFF00FF00),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.verticalScroll(scrollState)
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.services_logs_empty), color = Color.Gray)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}
