package com.example.raspberrycontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.UUID

// ── Data models ───────────────────────────────────────────────────────────────

data class GpioSchedule(
    val id      : String = UUID.randomUUID().toString(),
    val label   : String,
    val pin     : Int,
    val action  : PinAction,
    val hour    : Int,
    val minute  : Int,
    val days    : Set<WeekDay>,   // empty = every day
    val enabled : Boolean = true
)

enum class PinAction(val labelRes: Int, val gpioValue: Int) {
    ON(R.string.action_turn_on, 1),
    OFF(R.string.action_turn_off, 0)
}

enum class WeekDay(val shortRes: Int, val cronVal: String) {
    MON(R.string.day_mon, "1"), TUE(R.string.day_tue, "2"), WED(R.string.day_wed, "3"),
    THU(R.string.day_thu, "4"), FRI(R.string.day_fri, "5"), SAT(R.string.day_sat, "6"), SUN(R.string.day_sun, "0")
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpioScheduleScreen(
    settings: SettingsManager,
    onClose : () -> Unit,
    onOpenMenu: () -> Unit
) {
    val context           = androidx.compose.ui.platform.LocalContext.current
    val scope             = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var schedules    by remember { mutableStateOf(listOf<GpioSchedule>()) }
    val showAddDialog = remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_gpio_planner), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                SshClient.execute(
                                    settings.host, settings.port,
                                    settings.username, settings.password,
                                    "crontab -l", settings.sshTimeoutMs
                                )
                                snackbarHostState.showSnackbar(context.getString(R.string.sync_success))
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("❌ ${e.message}")
                            }
                        }
                    }) {
                        Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.action_sync))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick          = { showAddDialog.value = true },
                icon             = { Icon(Icons.Default.Add, contentDescription = null) },
                text             = { Text(stringResource(R.string.schedule_new_rule)) },
                containerColor   = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        if (schedules.isEmpty()) {
            Box(
                modifier         = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier           = Modifier.size(64.dp),
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Text(
                        stringResource(R.string.schedule_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        stringResource(R.string.schedule_help_tap_plus),
                        fontSize = 12.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier            = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(schedules, key = { it.id }) { schedule ->
                    ScheduleCard(
                        schedule = schedule,
                        onToggle = { enabled ->
                            scope.launch {
                                schedules = schedules.map {
                                    if (it.id == schedule.id) it.copy(enabled = enabled) else it
                                }
                                applyCrontab(settings, schedules.filter { it.enabled })
                                snackbarHostState.showSnackbar(
                                    if (enabled) context.getString(R.string.rule_enabled) else context.getString(R.string.rule_disabled)
                                )
                            }
                        },
                        onDelete = {
                            scope.launch {
                                schedules = schedules.filter { it.id != schedule.id }
                                applyCrontab(settings, schedules.filter { it.enabled })
                                snackbarHostState.showSnackbar(context.getString(R.string.rule_deleted))
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showAddDialog.value) {
        AddScheduleDialog(
            onDismiss = { showAddDialog.value = false },
            onConfirm = { newSchedule ->
                showAddDialog.value = false
                scope.launch {
                    schedules = schedules + newSchedule
                    try {
                        applyCrontab(settings, schedules.filter { it.enabled })
                        snackbarHostState.showSnackbar(context.getString(R.string.rule_added))
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_crontab_prefix, e.message ?: ""))
                    }
                }
            }
        )
    }
}

// ── Schedule card ─────────────────────────────────────────────────────────────

@Composable
private fun ScheduleCard(
    schedule: GpioSchedule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val actionColor = if (schedule.action == PinAction.ON) Color(0xFF4CAF50) else Color(0xFFF44336)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (schedule.enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier         = Modifier.size(42.dp).clip(CircleShape)
                    .background(actionColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = if (schedule.action == PinAction.ON) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = null,
                    tint               = actionColor,
                    modifier           = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(schedule.label, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.schedule_summary,
                        schedule.pin,
                        stringResource(schedule.action.labelRes),
                        "${"${schedule.hour}".padStart(2,'0')}:${"${schedule.minute}".padStart(2,'0')}"
                    ),
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (schedule.days.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        schedule.days.forEach { day ->
                            Text(
                                stringResource(day.shortRes),
                                fontSize = 10.sp,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.schedule_every_day), fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Switch(checked = schedule.enabled, onCheckedChange = onToggle)

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
        }
    }
}

// ── Add dialog ────────────────────────────────────────────────────────────────

@Composable
private fun AddScheduleDialog(
    onDismiss: () -> Unit,
    onConfirm: (GpioSchedule) -> Unit
) {
    var label        by remember { mutableStateOf("") }
    var pin          by remember { mutableStateOf("17") }
    var action       by remember { mutableStateOf(PinAction.ON) }
    var hour         by remember { mutableIntStateOf(8) }
    var minute       by remember { mutableIntStateOf(0) }
    var selectedDays by remember { mutableStateOf(setOf<WeekDay>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_new_rule)) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = label,
                    onValueChange = { label = it },
                    label         = { Text(stringResource(R.string.schedule_rule_name)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = pin,
                    onValueChange = { if (it.all { c -> c.isDigit() }) pin = it },
                    label         = { Text(stringResource(R.string.schedule_pin_bcm_label)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PinAction.entries.forEach { pa ->
                        FilterChip(
                            selected = action == pa,
                            onClick  = { action = pa },
                            label    = { Text(stringResource(pa.labelRes)) }
                        )
                    }
                }
                Text(stringResource(R.string.schedule_time_label, "${hour.toString().padStart(2,'0')}:${minute.toString().padStart(2,'0')}"),
                    fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("H", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(value = hour.toFloat(),
                            onValueChange = { hour = it.toInt() },
                            valueRange = 0f..23f, steps = 22)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Min", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(value = minute.toFloat(),
                            onValueChange = { minute = (it / 5).toInt() * 5 },
                            valueRange = 0f..59f, steps = 11)
                    }
                }
                Text(stringResource(R.string.schedule_days_help), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Actually I should just add the string for "Days (empty = every day)"
                // I'll add it to strings.xml in a moment.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WeekDay.entries.forEach { day ->
                        val selected = day in selectedDays
                        FilterChip(
                            selected = selected,
                            onClick  = {
                                selectedDays = if (selected) selectedDays - day else selectedDays + day
                            },
                            label = { Text(stringResource(day.shortRes), fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (label.isNotBlank() && pin.isNotBlank()) {
                        onConfirm(GpioSchedule(
                            label   = label,
                            pin     = pin.toIntOrNull() ?: 17,
                            action  = action,
                            hour    = hour,
                            minute  = minute,
                            days    = selectedDays
                        ))
                    }
                }
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

// ── SSH / crontab helper ──────────────────────────────────────────────────────

private suspend fun applyCrontab(settings: SettingsManager, enabledSchedules: List<GpioSchedule>) {
    val lines = enabledSchedules.joinToString("\\n") { s ->
        val daysPart = if (s.days.isEmpty()) "*" else s.days.joinToString(",") { it.cronVal }
        val gpioCmd  =
            "python3 -c \\\"import RPi.GPIO as GPIO;" +
                    "GPIO.setmode(GPIO.BCM);" +
                    "GPIO.setup(${s.pin},GPIO.OUT);" +
                    "GPIO.output(${s.pin},${s.action.gpioValue})\\\""
        "${s.minute} ${s.hour} * * $daysPart $gpioCmd  # RaspberryController"
    }
    val cmd = "(crontab -l 2>/dev/null | grep -v '# RaspberryController'; printf '$lines') | crontab -"
    SshClient.execute(
        settings.host, settings.port,
        settings.username, settings.password,
        cmd, settings.sshTimeoutMs
    )
}