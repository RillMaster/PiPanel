package com.rillmaster.pipanel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.ssh.SshKey
import com.rillmaster.pipanel.ssh.SshKeyManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeysScreen(
    settings: SettingsManager,
    onOpenMenu: () -> Unit,
    isExpanded: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var keys by remember { mutableStateOf(settings.sshKeys) }
    var showAddDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_ssh_keys)) },
                navigationIcon = {
                    if (!isExpanded) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
                    }
                }
            )
        }
    ) { padding ->
        if (keys.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Key, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.ssh_keys_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { showAddDialog = true }) {
                        Text(stringResource(R.string.ssh_keys_generate_first))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(keys) { key ->
                    KeyItem(
                        key = key,
                        onCopyPublic = {
                            clipboard.setText(AnnotatedString(key.publicKey))
                        },
                        onDelete = {
                            val newList = keys.filter { it.id != key.id }
                            keys = newList
                            settings.sshKeys = newList
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddKeyDialog(
            onConfirm = { name, type ->
                scope.launch {
                    try {
                        val keyType = if (type == "RSA") com.jcraft.jsch.KeyPair.RSA else com.jcraft.jsch.KeyPair.ED25519
                        val newKey = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            SshKeyManager.generateKeyPair(name, keyType)
                        }
                        val newList = keys + newKey
                        keys = newList
                        settings.sshKeys = newList
                        showAddDialog = false
                    } catch (e: UnsupportedOperationException) {
                        e.printStackTrace()
                        snackbarHostState.showSnackbar("Error: Algorithme non supporté sur cet appareil")
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        snackbarHostState.showSnackbar("Error: ${e.message ?: e.toString()}")
                    }
                }
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
fun KeyItem(
    key: SshKey,
    onCopyPublic: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(key.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Text(key.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = onCopyPublic) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Public Key")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    key.publicKey,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun AddKeyDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("ED25519") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_keys_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_keys_name_label)) },
                    placeholder = { Text("ex: Mon Phone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Column {
                    Text("Type de clé", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedType == "RSA", onClick = { selectedType = "RSA" })
                        Text("RSA (4096 bits)", modifier = Modifier.clickable { selectedType = "RSA" })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedType == "ED25519", onClick = { selectedType = "ED25519" })
                        Text("ED25519 (Moderne)", modifier = Modifier.clickable { selectedType = "ED25519" })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, selectedType) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
