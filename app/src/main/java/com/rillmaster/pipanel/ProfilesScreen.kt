@file:Suppress("SpellCheckingInspection")
package com.rillmaster.pipanel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    settings: SettingsManager,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit,
    showNavigationIcon: Boolean = true
) {
    var profiles by remember { mutableStateOf(settings.profiles) }
    var currentId by remember { mutableStateOf(settings.currentProfileId) }
    var showAddDialog by remember { mutableStateOf(false) }
    var profileToEdit by remember { mutableStateOf<PiProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_profiles)) },
                navigationIcon = {
                    if (showNavigationIcon) {
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
        if (profiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.onboarding_welcome_desc), modifier = Modifier.padding(horizontal = 32.dp))
                    Button(onClick = { showAddDialog = true }, modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.action_add))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(profiles) { profile ->
                    ProfileItem(
                        profile = profile,
                        isActive = profile.id == currentId,
                        onSelect = {
                            settings.currentProfileId = profile.id
                            currentId = profile.id
                            onClose()
                        },
                        onEdit = { profileToEdit = profile },
                        onDelete = {
                            settings.deleteProfile(profile.id)
                            profiles = settings.profiles
                            currentId = settings.currentProfileId
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        ProfileEditDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { newProfile ->
                settings.addProfile(newProfile)
                profiles = settings.profiles
                currentId = settings.currentProfileId
                showAddDialog = false
            }
        )
    }

    profileToEdit?.let { profile ->
        ProfileEditDialog(
            initialProfile = profile,
            onDismiss = { profileToEdit = null },
            onConfirm = { updatedProfile ->
                val list = settings.profiles.toMutableList()
                val index = list.indexOfFirst { it.id == profile.id }
                if (index != -1) {
                    list[index] = updatedProfile
                    settings.profiles = list
                }
                profiles = settings.profiles
                profileToEdit = null
            }
        )
    }
}

@Composable
fun ProfileItem(
    profile: PiProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isActive) RowDefaults.borderStrokeSelected() else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("${profile.username}@${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall)
            }
            if (isActive) {
                Text(
                    stringResource(R.string.profile_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.profile_edit_title))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

object RowDefaults {
    @Composable
    fun borderStrokeSelected() = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
}

@Composable
fun ProfileEditDialog(
    initialProfile: PiProfile? = null,
    onDismiss: () -> Unit,
    onConfirm: (PiProfile) -> Unit
) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var host by remember { mutableStateOf(initialProfile?.host ?: "") }
    var port by remember { mutableStateOf(initialProfile?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(initialProfile?.username ?: "pi") }
    var password by remember { mutableStateOf(initialProfile?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialProfile == null) stringResource(R.string.profile_add_title) else stringResource(R.string.profile_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.profile_name_label)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.settings_ip_label)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.settings_port_label)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(R.string.settings_user_label)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_pass_label)) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) stringResource(R.string.action_hide) else stringResource(R.string.action_show)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        PiProfile(
                            id = initialProfile?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name,
                            host = host,
                            port = port.toIntOrNull() ?: 22,
                            username = username,
                            password = password
                        )
                    )
                },
                enabled = name.isNotBlank() && host.isNotBlank() && username.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
