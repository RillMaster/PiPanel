package com.example.raspberrycontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfwScreen(
    settings: SettingsManager,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit
) {
    var rules by remember { mutableStateOf<List<UfwRule>>(emptyList()) }
    var status by remember { mutableStateOf("Unknown") }
    var isLoading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun fetchUfwStatus() {
        scope.launch {
            isLoading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    SshClient.execute(
                        settings.host, settings.port, settings.username, settings.password,
                        "sudo ufw status numbered",
                        settings.sshTimeoutMs
                    )
                }
                // Parse status: "Status: active"
                status = result.lines().firstOrNull()?.substringAfter("Status: ") ?: "Unknown"
                
                val newRules = mutableListOf<UfwRule>()
                // Parse numbered rules: "[ 1] 22/tcp                     ALLOW IN    Anywhere"
                result.lines().forEach { line ->
                    if (line.startsWith("[") && line.contains("]")) {
                        val number = line.substringAfter("[").substringBefore("]").trim().toIntOrNull()
                        val content = line.substringAfter("]").trim()
                        val parts = content.split(Regex("\\s{2,}")).filter { it.isNotBlank() }
                        if (parts.size >= 2 && number != null) {
                            newRules.add(UfwRule(number, parts[0], parts[1], parts.getOrNull(2) ?: ""))
                        }
                    }
                }
                rules = newRules
            } catch (_: Exception) {
                status = "Error"
            } finally {
                isLoading = false
            }
        }
    }

    fun deleteRule(number: Int) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // ufw delete <number> asks for confirmation, use --force or echo y
                    SshClient.execute(
                        settings.host, settings.port, settings.username, settings.password,
                        "echo 'y' | sudo ufw delete $number",
                        settings.sshTimeoutMs
                    )
                }
                fetchUfwStatus()
            } catch (_: Exception) {}
        }
    }

    fun addRule(port: String, proto: String, action: String) {
        scope.launch {
            try {
                val cmd = "sudo ufw $action $port/$proto"
                withContext(Dispatchers.IO) {
                    SshClient.execute(
                        settings.host, settings.port, settings.username, settings.password,
                        cmd,
                        settings.sshTimeoutMs
                    )
                }
                fetchUfwStatus()
            } catch (_: Exception) {}
        }
    }

    fun toggleUfw() {
        scope.launch {
            try {
                val cmd = if (status == "active") "sudo ufw disable" else "sudo ufw --force enable"
                withContext(Dispatchers.IO) {
                    SshClient.execute(
                        settings.host, settings.port, settings.username, settings.password,
                        cmd,
                        settings.sshTimeoutMs
                    )
                }
                fetchUfwStatus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) { fetchUfwStatus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_ufw)) },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { fetchUfwStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (status == "active") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Firewall Status", style = MaterialTheme.typography.titleSmall)
                        Text(status.uppercase(), style = MaterialTheme.typography.headlineMedium)
                    }
                    Switch(checked = status == "active", onCheckedChange = { toggleUfw() })
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rules) { rule ->
                        UfwRuleItem(rule = rule, onDelete = { deleteRule(rule.number) })
                    }
                }
            }
        }

        if (showAddDialog) {
            AddUfwRuleDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { port, proto, action ->
                    addRule(port, proto, action)
                    showAddDialog = false
                }
            )
        }
    }
}

data class UfwRule(val number: Int, val to: String, val action: String, val from: String)

@Composable
fun UfwRuleItem(rule: UfwRule, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (rule.action.contains("ALLOW", true)) Color(0xFF4CAF50).copy(alpha = 0.2f)
                        else Color(0xFFF44336).copy(alpha = 0.2f),
                        MaterialTheme.shapes.small
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    rule.number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (rule.action.contains("ALLOW", true)) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.to, style = MaterialTheme.typography.titleMedium)
                Text("${rule.action} from ${rule.from}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun AddUfwRuleDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var port by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf("tcp") }
    var action by remember { mutableStateOf("allow") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Firewall Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port / Service") },
                    placeholder = { Text("e.g. 80 or ssh") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tcp", "udp").forEach { p ->
                        FilterChip(
                            selected = proto == p,
                            onClick = { proto = p },
                            label = { Text(p.uppercase()) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("allow", "deny").forEach { a ->
                        FilterChip(
                            selected = action == a,
                            onClick = { action = a },
                            label = { Text(a.uppercase()) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (port.isNotBlank()) onAdd(port, proto, action) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
