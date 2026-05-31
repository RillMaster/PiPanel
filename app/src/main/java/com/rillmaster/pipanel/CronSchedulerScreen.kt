package com.rillmaster.pipanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.*

data class CronTask(
    val id: String = UUID.randomUUID().toString(),
    val minute: String = "*",
    val hour: String = "*",
    val dom: String = "*",
    val month: String = "*",
    val dow: String = "*",
    val command: String,
    val rawLine: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronSchedulerScreen(settings: SettingsManager, onOpenMenu: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tasks by remember { mutableStateOf<List<CronTask>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<CronTask?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun refreshTasks() {
        scope.launch {
            loading = true
            val raw = SshClient.execute(settings.host, settings.port, settings.username, settings.password, "crontab -l", 10000)
            if (raw.startsWith("[err]") || raw.contains("no crontab")) {
                tasks = emptyList()
            } else {
                val list = mutableListOf<CronTask>()
                raw.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split(Regex("\\s+"), limit = 6)
                        if (parts.size >= 6) {
                            list.add(CronTask(
                                minute = parts[0],
                                hour = parts[1],
                                dom = parts[2],
                                month = parts[3],
                                dow = parts[4],
                                command = parts[5],
                                rawLine = trimmed
                            ))
                        }
                    }
                }
                tasks = list
            }
            loading = false
        }
    }

    fun saveCrontab(updatedTasks: List<CronTask>) {
        val successMsg = context.getString(R.string.cron_save_success)
        scope.launch {
            loading = true
            val crontabContent = updatedTasks.joinToString("\\n") { 
                "${it.minute} ${it.hour} ${it.dom} ${it.month} ${it.dow} ${it.command}"
            }
            // Use a temporary file to update crontab safely
            val cmd = "printf \"$crontabContent\\n\" | crontab -"
            val res = SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd)
            if (!res.startsWith("[err]")) {
                snackbarHostState.showSnackbar(successMsg)
                refreshTasks()
            } else {
                snackbarHostState.showSnackbar("❌ $res")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshTasks()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cron_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTasks() }, enabled = !loading) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading && tasks.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (tasks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.EventNote, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.cron_empty))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tasks) { task ->
                        CronTaskCard(
                            task = task,
                            onClick = { taskToEdit = task },
                            onDelete = {
                                saveCrontab(tasks.filter { it.id != task.id })
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        CronEditDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { newTask ->
                saveCrontab(tasks + newTask)
                showAddDialog = false
            }
        )
    }

    taskToEdit?.let { task ->
        CronEditDialog(
            initialTask = task,
            onDismiss = { taskToEdit = null },
            onConfirm = { updatedTask ->
                saveCrontab(tasks.map { if (it.id == task.id) updatedTask else it })
                taskToEdit = null
            }
        )
    }
}

@Composable
fun CronTaskCard(task: CronTask, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.command,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${task.minute} ${task.hour} ${task.dom} ${task.month} ${task.dow}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = getNaturalLanguage(task),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun CronEditDialog(initialTask: CronTask? = null, onDismiss: () -> Unit, onConfirm: (CronTask) -> Unit) {
    var minute by remember { mutableStateOf(initialTask?.minute ?: "*") }
    var hour by remember { mutableStateOf(initialTask?.hour ?: "*") }
    var dom by remember { mutableStateOf(initialTask?.dom ?: "*") }
    var month by remember { mutableStateOf(initialTask?.month ?: "*") }
    var dow by remember { mutableStateOf(initialTask?.dow ?: "*") }
    var command by remember { mutableStateOf(initialTask?.command ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialTask == null) stringResource(R.string.cron_add_title) else stringResource(R.string.cron_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = minute, onValueChange = { minute = it }, label = { Text(stringResource(R.string.cron_minute)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = hour, onValueChange = { hour = it }, label = { Text(stringResource(R.string.cron_hour)) }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = dom, onValueChange = { dom = it }, label = { Text("Dom") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = month, onValueChange = { month = it }, label = { Text("Month") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = dow, onValueChange = { dow = it }, label = { Text("Dow") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.cron_command)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
                
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        text = getNaturalLanguage(CronTask(minute=minute, hour=hour, dom=dom, month=month, dow=dow, command=command)),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(CronTask(minute=minute, hour=hour, dom=dom, month=month, dow=dow, command=command)) }, enabled = command.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
fun getNaturalLanguage(task: CronTask): String {
    if (task.minute == "*" && task.hour == "*" && task.dom == "*" && task.month == "*" && task.dow == "*") {
        return stringResource(R.string.cron_preview_every_minute)
    }
    
    // Simplistic conversion for common cases
    if (task.dom == "*" && task.month == "*" && task.dow == "*") {
        if (task.minute.toIntOrNull() != null && task.hour.toIntOrNull() != null) {
            return stringResource(R.string.cron_preview_daily, task.hour.padStart(2, '0'), task.minute.padStart(2, '0'))
        }
    }
    
    if (task.dom == "*" && task.month == "*" && task.dow.toIntOrNull() != null) {
        val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val dayName = days.getOrNull(task.dow.toInt()) ?: task.dow
        return stringResource(R.string.cron_preview_weekly, dayName, task.hour.padStart(2, '0'), task.minute.padStart(2, '0'))
    }

    return "Custom schedule"
}

fun String.padStart(length: Int, padChar: Char): String {
    if (this.length >= length) return this
    return padChar.toString().repeat(length - this.length) + this
}
