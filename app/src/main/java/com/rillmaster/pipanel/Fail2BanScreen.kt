package com.rillmaster.pipanel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Fail2BanScreen(
    settings: SettingsManager,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val context = LocalContext.current
    var jails by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedJail by remember { mutableStateOf<String?>(null) }
    var bannedIps by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Fonction unique pour tout rafraîchir
    suspend fun refreshData() {
        try {
            // 1. Récupérer la liste des jails
            val jailResult = withContext(Dispatchers.IO) {
                SshClient.execute(
                    settings.host, settings.port, settings.username, settings.password,
                    "sudo fail2ban-client status",
                    settings.sshTimeoutMs
                )
            }
            
            val jailLine = jailResult.lines().find { it.contains("Jail list", ignoreCase = true) }
            val detectedJails = jailLine?.substringAfter(":")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            
            jails = detectedJails
            if (selectedJail == null && jails.isNotEmpty()) {
                selectedJail = jails.first()
            }

            // 2. Si une jail est sélectionnée, récupérer ses IPs
            val currentJail = selectedJail
            if (currentJail != null) {
                val detailResult = withContext(Dispatchers.IO) {
                    SshClient.execute(
                        settings.host, settings.port, settings.username, settings.password,
                        "sudo fail2ban-client status $currentJail",
                        settings.sshTimeoutMs
                    )
                }
                // Parsing plus flexible pour la liste d'IPs
                val ipLine = detailResult.lines().find { it.contains("Banned IP list", ignoreCase = true) }
                val rawIps = ipLine?.substringAfter(":")?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() && it != "None" } ?: emptyList()
                bannedIps = rawIps
            } else {
                bannedIps = emptyList()
            }
            
            errorMessage = null
        } catch (e: Exception) {
            errorMessage = context.getString(R.string.error_fetch_prefix, e.message ?: "")
        } finally {
            isLoading = false
        }
    }

    // Boucle de rafraîchissement automatique
    LaunchedEffect(Unit) {
        while (true) {
            refreshData()
            delay(5000)
        }
    }

    // Rafraîchir immédiatement si on change de jail manuellement
    LaunchedEffect(selectedJail) {
        if (selectedJail != null) {
            isLoading = true
            refreshData()
        }
    }

    fun unbanIp(ip: String) {
        val jail = selectedJail ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SshClient.execute(
                        settings.host, settings.port, settings.username, settings.password,
                        "sudo fail2ban-client set $jail unbanip $ip",
                        settings.sshTimeoutMs
                    )
                }
                refreshData()
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.error_unban_failed, e.message ?: "")
            }
        }
    }

    fun banIp(jail: String, ip: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SshClient.execute(
                        settings.host, settings.port, settings.username, settings.password,
                        "sudo fail2ban-client set $jail banip $ip",
                        settings.sshTimeoutMs
                    )
                }
                if (selectedJail == null) selectedJail = jail
                refreshData()
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.error_ban_failed, e.message ?: "")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.nav_fail2ban),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { isLoading = true; refreshData() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_ban_ip))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (jails.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = jails.indexOf(selectedJail).coerceAtLeast(0),
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    divider = {}
                ) {
                    jails.forEach { jail ->
                        Tab(
                            selected = selectedJail == jail,
                            onClick = { selectedJail = jail },
                            text = { Text(jail) }
                        )
                    }
                }
            }

            if (isLoading && jails.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (errorMessage != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            ) {
                                Text(
                                    text = errorMessage!!,
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = if (selectedJail != null) stringResource(R.string.label_banned_ips_in, selectedJail!!) else stringResource(R.string.label_no_jail_selected),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    if (bannedIps.isEmpty()) {
                        item {
                            Text(
                                text = if (selectedJail != null) stringResource(R.string.label_no_ips_banned) else stringResource(R.string.label_select_jail_first),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(bannedIps) { ip ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        ip, 
                                        modifier = Modifier.weight(1f), 
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(onClick = { unbanIp(ip) }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_unban), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var ipToBan by remember { mutableStateOf("") }
        var manualJail by remember { mutableStateOf(selectedJail ?: "sshd") }
        
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.action_ban_ip)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = manualJail,
                        onValueChange = { manualJail = it },
                        label = { Text(stringResource(R.string.label_jail_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = jails.isEmpty()
                    )
                    OutlinedTextField(
                        value = ipToBan,
                        onValueChange = { ipToBan = it },
                        label = { Text(stringResource(R.string.label_ip_address)) },
                        placeholder = { Text("ex: 1.2.3.4") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (ipToBan.isNotBlank() && manualJail.isNotBlank()) {
                            banIp(manualJail.trim(), ipToBan.trim())
                            showAddDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_ban))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
