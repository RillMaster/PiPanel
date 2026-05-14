package com.example.raspberrycontroller

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════════════════════
//  Modèle de données
// ══════════════════════════════════════════════════════════════════════════════
data class DockerStats(
    val cpuUsage: String = "0.00%",
    val memUsage: String = "0MiB",
    val memLimit: String = "0MiB",
    val memPerc: String = "0.00%"
)

data class DockerContainer(
    val id    : String,
    val name  : String,
    val status: String,
    val image : String,
    val stats : DockerStats = DockerStats()
) {
    val isRunning: Boolean get() = status.startsWith("Up", ignoreCase = true)
}

// ══════════════════════════════════════════════════════════════════════════════
//  Récupération des données via SSH
// ══════════════════════════════════════════════════════════════════════════════
suspend fun fetchDockerContainersWithStats(settings: SettingsManager): Result<List<DockerContainer>> {
    // 1. Liste des containers
    val rawList = SshClient.execute(
        host      = settings.host,
        port      = settings.port,
        user      = settings.username,
        password  = settings.password,
        command   = "sudo docker ps -a --format \"{{.ID}}\t{{.Names}}\t{{.Status}}\t{{.Image}}\"",
        timeoutMs = settings.sshTimeoutMs
    )
    if (rawList.startsWith("[err]") || rawList.isBlank()) return Result.failure(Exception(rawList))

    // 2. Statistiques (stats --no-stream)
    val rawStats = SshClient.execute(
        host      = settings.host,
        port      = settings.port,
        user      = settings.username,
        password  = settings.password,
        command   = "sudo docker stats --no-stream --format \"{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\"",
        timeoutMs = settings.sshTimeoutMs
    )
    
    val statsMap = rawStats.lines().associate { line ->
        val parts = line.split("\t")
        if (parts.size >= 4) {
            val memSplit = parts[2].split(" / ")
            parts[0] to DockerStats(
                cpuUsage = parts[1],
                memUsage = memSplit.getOrNull(0) ?: "0MiB",
                memLimit = memSplit.getOrNull(1) ?: "0MiB",
                memPerc  = parts[3]
            )
        } else "" to DockerStats()
    }

    val containers = rawList.lines().mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size >= 4) {
            val name = parts[1]
            DockerContainer(
                id     = parts[0].take(12),
                name   = name,
                status = parts[2],
                image  = parts[3],
                stats  = statsMap[name] ?: DockerStats()
            )
        } else null
    }
    return Result.success(containers)
}

// ══════════════════════════════════════════════════════════════════════════════
//  Écran Docker
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerScreen(
    settings: SettingsManager,
    onClose : () -> Unit,
    onOpenMenu: () -> Unit
) {
    val scope  = rememberCoroutineScope()

    var containers    by remember { mutableStateOf<List<DockerContainer>>(emptyList()) }
    var fetchError    by remember { mutableStateOf<String?>(null) }
    var isLoading     by remember { mutableStateOf(true) }
    var actionLoading by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var actionResult  by remember { mutableStateOf<String?>(null) }
    
    var selectedLogContainer by remember { mutableStateOf<DockerContainer?>(null) }

    // ── Chargement ────────────────────────────────────────────────────────────
    fun refresh() {
        scope.launch {
            fetchError = null
            val result = fetchDockerContainersWithStats(settings)
            result.fold(
                onSuccess = { containers = it; fetchError = null },
                onFailure = { if (containers.isEmpty()) fetchError = it.message }
            )
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(5000) // Rafraîchissement toutes les 5s pour les stats
        }
    }

    // ── Action sur un container ───────────────────────────────────────────────
    fun runAction(container: DockerContainer, action: String) {
        scope.launch {
            actionLoading = actionLoading + (container.name to action)
            actionResult  = null

            val cmd = when (action) {
                "start"   -> "sudo docker start ${container.name}"
                "stop"    -> "sudo docker stop ${container.name}"
                "restart" -> "sudo docker restart ${container.name}"
                else      -> return@launch
            }
            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                cmd, settings.sshTimeoutMs
            )
            val emoji = when (action) {
                "start"   -> "▶️"
                "stop"    -> "⏹️"
                "restart" -> "🔄"
                else      -> "✅"
            }
            actionResult  = "$emoji ${container.name} : ${raw.trim()}"
            actionLoading = actionLoading - container.name
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.docker_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !isLoading) {
                        if (isLoading && containers.isEmpty()) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.docker_refresh))
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = isLoading && containers.isEmpty(),
            label = "docker_main_state"
        ) { firstLoad ->
            if (firstLoad) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.docker_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Spacer(Modifier.height(8.dp))

                    // ── Résumé ────────────────────────────────────────────────────────
                    if (fetchError == null) {
                        val running = containers.count { it.isRunning }
                        val stopped = containers.size - running
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SummaryChip(
                                label    = pluralStringResource(R.plurals.docker_active_count, running, running),
                                icon     = Icons.Default.CheckCircle,
                                color    = Color(0xFF4CAF50),
                                modifier = Modifier.weight(1f)
                            )
                            SummaryChip(
                                label    = pluralStringResource(R.plurals.docker_stopped_count, stopped, stopped),
                                icon     = Icons.Default.PauseCircle,
                                color    = Color(0xFFF44336),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // ── Résultat de la dernière action ────────────────────────────────
                    actionResult?.let { msg ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier              = Modifier.padding(12.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text       = msg,
                                    style      = MaterialTheme.typography.labelSmall,
                                    modifier   = Modifier.weight(1f),
                                    fontFamily = FontFamily.Monospace
                                )
                                IconButton(onClick = { actionResult = null }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    // ── Liste des containers ──────────────────────────────────────────
                    containers.forEach { container ->
                        ContainerCard(
                            container     = container,
                            loadingAction = actionLoading[container.name],
                            onStart       = { runAction(container, "start") },
                            onStop        = { runAction(container, "stop") },
                            onRestart     = { runAction(container, "restart") },
                            onLogs        = { selectedLogContainer = container }
                        )
                    }
                    
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    // ── Dialogue des Logs ─────────────────────────────────────────────────────
    if (selectedLogContainer != null) {
        LogsDialog(
            container = selectedLogContainer!!,
            settings  = settings,
            onDismiss = { selectedLogContainer = null }
        )
    }
}

@Composable
private fun ContainerCard(
    container    : DockerContainer,
    loadingAction: String?,
    onStart      : () -> Unit,
    onStop       : () -> Unit,
    onRestart    : () -> Unit,
    onLogs       : () -> Unit
) {
    val isRunning = container.isRunning
    val busy      = loadingAction != null

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = container.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = container.image, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
                StatusBadge(isRunning = isRunning)
            }

            // Stats CPU/RAM
            if (isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatInfo(label = "CPU", value = container.stats.cpuUsage, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    StatInfo(label = "RAM", value = container.stats.memUsage, color = Color(0xFF9C27B0), modifier = Modifier.weight(1f))
                }
            }

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isRunning) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.docker_start))
                    }
                } else {
                    IconButton(
                        onClick = onLogs,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(Icons.Default.ListAlt, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.docker_stop))
                    }
                    Button(
                        onClick = onRestart,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.docker_restart))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatInfo(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
        }
        val progress = try { value.removeSuffix("%").toFloat() / 100f } catch(_: Exception) { 0f }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            color = color,
            trackColor = color.copy(alpha = 0.1f)
        )
    }
}

@Composable
fun LogsDialog(
    container: DockerContainer,
    settings: SettingsManager,
    onDismiss: () -> Unit
) {
    var logs by remember { mutableStateOf("Chargement des logs...") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(container.id) {
        scope.launch {
            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                "sudo docker logs --tail 100 ${container.name}",
                settings.sshTimeoutMs
            )
            logs = raw.ifBlank { "Aucun log disponible." }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Logs: ${container.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                }
                
                Spacer(Modifier.height(8.dp))
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .padding(8.dp)
                ) {
                    val scrollState = rememberScrollState()
                    Text(
                        text = logs,
                        color = Color(0xFF00FF00),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(scrollState)
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Fermer")
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(isRunning: Boolean) {
    val color = if (isRunning) Color(0xFF4CAF50) else Color(0xFFF44336)
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(
                text = if (isRunning) stringResource(R.string.docker_status_running) else stringResource(R.string.docker_status_stopped),
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun SummaryChip(label: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(16.dp),
        color    = color.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}
