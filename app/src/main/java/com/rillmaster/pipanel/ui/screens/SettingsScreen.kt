package com.rillmaster.pipanel.ui.screens

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkManager
import com.rillmaster.pipanel.MainActivity
import com.rillmaster.pipanel.MonitoringWorker
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.SshShortcut
import com.rillmaster.pipanel.UpdateStatsWorker
import com.rillmaster.pipanel.WidgetUpdateService
import com.rillmaster.pipanel.ui.components.SectionTitle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings          : SettingsManager,
    activity          : FragmentActivity,
    onThemeChanged    : (String) -> Unit,
    onBiometricEnabled: () -> Unit,
    onSave            : () -> Unit,
    onOpenMenu         : () -> Unit,
    isExpanded: Boolean = false
) {
    var host     by remember { mutableStateOf(settings.host) }
    var port     by remember { mutableIntStateOf(settings.port) }
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf(settings.password) }
    var passwordVisible by remember { mutableStateOf(false) }

    var biometricEnabled by remember { mutableStateOf(settings.biometricEnabled) }
    val biometricAvailable = remember {
        BiometricManager.from(activity)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    var backgroundActivity by remember { mutableStateOf(settings.backgroundActivityEnabled) }
    val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    var isIgnoringBattery by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(activity.packageName)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBattery = pm.isIgnoringBatteryOptimizations(activity.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val themeOptions    = listOf(
        "system" to stringResource(R.string.settings_theme_system),
        "light" to stringResource(R.string.settings_theme_light),
        "dark" to stringResource(R.string.settings_theme_dark)
    )
    var selectedTheme   by remember { mutableStateOf(settings.theme) }
    val refreshOptions  = listOf(1000 to "1 s", 2000 to "2 s", 5000 to "5 s", 10000 to "10 s")
    var selectedRefresh by remember { mutableIntStateOf(settings.tempRefreshMs) }
    val timeoutOptions  = listOf(5000 to "5 s", 8000 to "8 s", 15000 to "15 s", 30000 to "30 s")
    var selectedTimeout by remember { mutableIntStateOf(settings.sshTimeoutMs) }
    var shortcuts       by remember { mutableStateOf(settings.sshShortcuts) }
    val showAddDialog   = remember { mutableStateOf(false) }
    val editShortcutIdx = remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = {
                    if (!isExpanded) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog.value = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier            = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle(stringResource(R.string.settings_ssh_title))
            OutlinedTextField(value = host, onValueChange = { host = it },
                label = { Text(stringResource(R.string.settings_ip_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = port.toString(),
                onValueChange = { port = it.toIntOrNull() ?: 22 },
                label = { Text(stringResource(R.string.settings_port_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = username, onValueChange = { username = it },
                label = { Text(stringResource(R.string.settings_user_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it },
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
                modifier = Modifier.fillMaxWidth())

            SectionTitle(stringResource(R.string.settings_timeout_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                timeoutOptions.forEach { (ms, label) ->
                    FilterChip(
                        selected = selectedTimeout == ms,
                        onClick  = { selectedTimeout = ms; settings.sshTimeoutMs = ms },
                        label    = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SectionTitle(stringResource(R.string.settings_theme_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                themeOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = selectedTheme == value,
                        onClick  = { selectedTheme = value; onThemeChanged(value) },
                        label    = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SectionTitle(stringResource(R.string.settings_security_title))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_biometric_label), modifier = Modifier.weight(1f))
                Switch(
                    checked         = biometricEnabled,
                    onCheckedChange = {
                        biometricEnabled          = it
                        settings.biometricEnabled = it
                        if (it) onBiometricEnabled()
                    },
                    enabled = biometricAvailable
                )
            }

            SectionTitle(stringResource(R.string.settings_background_title))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_background_label), modifier = Modifier.weight(1f))
                    Switch(
                        checked = backgroundActivity,
                        onCheckedChange = {
                            backgroundActivity = it
                            settings.backgroundActivityEnabled = it
                            if (it) {
                                if (settings.notificationsEnabled) MonitoringWorker.schedule(activity)
                                UpdateStatsWorker.schedulePeriodic(activity)
                                WidgetUpdateService.start(activity)
                            } else {
                                WorkManager.getInstance(activity).cancelAllWork()
                                WidgetUpdateService.stop(activity)
                            }
                        }
                    )
                }

                if (backgroundActivity) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isIgnoringBattery)
                                Color(0xFF4CAF50).copy(alpha = 0.1f)
                            else
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (isIgnoringBattery) Color(0xFF4CAF50).copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                if (isIgnoringBattery) Icons.Default.CheckCircle else Icons.Default.BatteryAlert,
                                contentDescription = null,
                                tint = if (isIgnoringBattery) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isIgnoringBattery)
                                        stringResource(R.string.settings_battery_optimized_off)
                                    else
                                        stringResource(R.string.settings_battery_optimized_on),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isIgnoringBattery)
                                        stringResource(R.string.settings_battery_desc_ok)
                                    else
                                        stringResource(R.string.settings_battery_desc_needed),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!isIgnoringBattery) {
                                TextButton(onClick = {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    activity.startActivity(intent)
                                }) {
                                    Text(stringResource(R.string.action_fix))
                                }
                            }
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_refresh_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                refreshOptions.forEach { (ms, label) ->
                    FilterChip(
                        selected = selectedRefresh == ms,
                        onClick  = { selectedRefresh = ms; settings.tempRefreshMs = ms },
                        label    = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SectionTitle(stringResource(R.string.settings_shortcuts_title))
            ReorderableColumn(
                list                = shortcuts,
                onSettle            = { fromIndex, toIndex ->
                    val updated        = shortcuts.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                    shortcuts          = updated
                    settings.sshShortcuts = updated
                },
                modifier            = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) { index, shortcut, isDragging ->
                key(shortcut.id) {
                    ReorderableItem {
                        val elevation by animateDpAsState(
                            targetValue = if (isDragging) 8.dp else 0.dp,
                            label       = "shortcut_elevation"
                        )
                        Card(
                            modifier  = Modifier.fillMaxWidth().clickable { editShortcutIdx.value = index },
                            colors    = CardDefaults.cardColors(
                                containerColor = if (isDragging)
                                    MaterialTheme.colorScheme.surface
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = elevation)
                        ) {
                            Row(
                                modifier          = Modifier.fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector        = Icons.Default.DragHandle,
                                    contentDescription = stringResource(R.string.action_move),
                                    modifier           = Modifier.draggableHandle().padding(end = 12.dp),
                                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val icon = when(shortcut.icon) {
                                    "Refresh" -> Icons.Default.Refresh
                                    "Power"    -> Icons.Default.PowerSettingsNew
                                    "Settings"-> Icons.Default.Settings
                                    "Storage" -> Icons.Default.Storage
                                    "Bolt"    -> Icons.Default.Bolt
                                    "Info"    -> Icons.Default.Info
                                    else      -> Icons.Default.Terminal
                                }
                                Icon(icon, null, modifier = Modifier.size(18.dp), tint = Color(shortcut.color))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(shortcut.label,
                                        style      = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines   = 1,
                                        overflow   = TextOverflow.Ellipsis)
                                    Text(shortcut.commands.joinToString(" && "),
                                        style      = MaterialTheme.typography.bodySmall,
                                        color      = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines   = 1,
                                        overflow   = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = {
                                    val updated        = shortcuts.toMutableList().apply { removeAt(index) }
                                    shortcuts          = updated
                                    settings.sshShortcuts = updated
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete),
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick  = {
                    settings.host     = host
                    settings.port     = port
                    settings.username = username
                    settings.password = password
                    onSave()
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            ) { Text(stringResource(R.string.action_save)) }
        }
    }

    if (showAddDialog.value) {
        ShortcutDialog(
            title          = stringResource(R.string.action_add),
            onConfirm      = { newShortcut ->
                val updated        = shortcuts + newShortcut
                shortcuts          = updated
                settings.sshShortcuts = updated
                showAddDialog.value = false
            },
            onDismiss = { showAddDialog.value = false }
        )
    }

    editShortcutIdx.value?.let { idx ->
        if (idx < shortcuts.size) {
            ShortcutDialog(
                initialShortcut = shortcuts[idx],
                title           = stringResource(R.string.profile_edit_title),
                onConfirm       = { updatedShortcut ->
                    val updated = shortcuts.toMutableList().apply { this[idx] = updatedShortcut }
                    shortcuts = updated
                    settings.sshShortcuts = updated
                    editShortcutIdx.value = null
                },
                onDismiss = { editShortcutIdx.value = null }
            )
        }
    }
}

@Composable
fun ShortcutDialog(
    initialShortcut: SshShortcut? = null,
    title          : String,
    onConfirm     : (SshShortcut) -> Unit,
    onDismiss     : () -> Unit
) {
    var label    by remember { mutableStateOf(initialShortcut?.label ?: "") }
    var commands by remember { mutableStateOf(initialShortcut?.commands?.joinToString("\n") ?: "") }
    var iconName by remember { mutableStateOf(initialShortcut?.icon ?: "Terminal") }

    val icons = listOf("Terminal", "Refresh", "Power", "Settings", "Storage", "Bolt", "Info")

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title) },
        text             = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = label,
                    onValueChange = { label = it },
                    label         = { Text(stringResource(R.string.shortcut_dialog_label)) },
                    placeholder   = { Text(stringResource(R.string.shortcut_example_label)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = commands,
                    onValueChange = { commands = it },
                    label         = { Text(stringResource(R.string.shortcut_dialog_command)) },
                    placeholder   = { Text(stringResource(R.string.shortcut_example_command)) },
                    supportingText = { Text(stringResource(R.string.shortcut_dialog_macro_hint)) },
                    minLines      = 2,
                    modifier      = Modifier.fillMaxWidth(),
                    textStyle     = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )

                Text(stringResource(R.string.shortcut_dialog_icon), style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(icons) { name ->
                        val icon = when(name) {
                            "Refresh" -> Icons.Default.Refresh
                            "Power"   -> Icons.Default.PowerSettingsNew
                            "Settings"-> Icons.Default.Settings
                            "Storage" -> Icons.Default.Storage
                            "Bolt"    -> Icons.Default.Bolt
                            "Info"    -> Icons.Default.Info
                            else      -> Icons.Default.Terminal
                        }
                        FilterChip(
                            selected = iconName == name,
                            onClick = { iconName = name },
                            label = { Icon(icon, null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = {
                    if (label.isNotEmpty() && commands.isNotEmpty()) {
                        onConfirm(SshShortcut(
                            id = initialShortcut?.id ?: java.util.UUID.randomUUID().toString(),
                            label = label,
                            commands = commands.split("\n").filter { it.isNotBlank() },
                            icon = iconName,
                            color = initialShortcut?.color ?: 0xFF39FF14
                        ))
                    }
                },
                enabled  = label.isNotEmpty() && commands.isNotEmpty()
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
