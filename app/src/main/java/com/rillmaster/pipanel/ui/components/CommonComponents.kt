package com.rillmaster.pipanel.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.model.DrawerItemData
import com.rillmaster.pipanel.model.SystemStats
import com.rillmaster.pipanel.model.Screen
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem
import com.rillmaster.pipanel.DashboardPrefs
import com.rillmaster.pipanel.DashboardSection
import com.rillmaster.pipanel.SshClient
import com.rillmaster.pipanel.fetchPiHoleStatus
import kotlinx.coroutines.launch

@Composable
fun SectionTitle(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.titleMedium,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

fun tempColor(celsius: Double): Color = when {
    celsius >= 75.0 -> Color(0xFFEF5350)
    celsius >= 60.0 -> Color(0xFFFF9800)
    celsius >= 45.0 -> Color(0xFFFFEB3B)
    else            -> Color(0xFF66BB6A)
}

@Composable
fun SystemStatusBar(settings: SettingsManager, stats: SystemStats?, loading: Boolean) {
    val cpuColor = when {
        stats == null       -> MaterialTheme.colorScheme.outline
        stats.cpuPercent > 80 -> Color(0xFFEF5350)
        stats.cpuPercent > 50 -> Color(0xFFFF9800)
        else                  -> Color(0xFF66BB6A)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(24.dp),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (stats != null) Color(0xFF4CAF50) else if (loading) Color.Gray else Color.Red)
                    )
                    Text(
                        text       = settings.host,
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f, fill = false)
                    )
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (stats != null) {
                    Text(
                        stringResource(R.string.status_online),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF4CAF50),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(Color(0xFF4CAF50).copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            AnimatedContent(
                targetState = stats,
                transitionSpec = { fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500)) },
                label = "stats_dashboard"
            ) { s ->
                if (s == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                imageVector = if (loading) Icons.Default.Refresh else Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = if (loading) MaterialTheme.colorScheme.primary else Color(0xFFEF5350)
                            )
                            Text(
                                if (loading) stringResource(R.string.status_syncing) else stringResource(R.string.status_lost_connection),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (loading) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFEF5350)
                            )
                            if (!loading) {
                                Button(onClick = { /* Refresh logic already in LaunchedEffect */ }) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        }
                    }
                } else {
                    Column {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatBlock(Icons.Default.Thermostat, "%.1f°C".format(s.tempCelsius), stringResource(R.string.stat_avg), tempColor(s.tempCelsius))
                            StatBlock(Icons.Default.Memory, "${s.cpuPercent}%", stringResource(R.string.cpu_load), cpuColor)

                            val ramPct = if (s.ramTotalMb > 0) s.ramUsedMb.toFloat() / s.ramTotalMb else 0f
                            val ramColor = if (ramPct > 0.85f) Color(0xFFEF5350) else Color(0xFF66BB6A)
                            StatBlock(Icons.Default.Storage, "${s.ramUsedMb} ${stringResource(R.string.unit_mb)}", stringResource(R.string.ram_memory), ramColor)
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // CPU
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.cpu_load), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${s.cpuPercent}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                LinearProgressIndicator(
                                    progress   = { (s.cpuPercent / 100f).coerceIn(0f, 1f) },
                                    modifier   = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                                    color      = cpuColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }

                            // RAM
                            val ramPct = if (s.ramTotalMb > 0) s.ramUsedMb.toFloat() / s.ramTotalMb else 0f
                            val ramColor = if (ramPct > 0.85f) Color(0xFFEF5350) else MaterialTheme.colorScheme.primary
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.ram_memory), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${(ramPct * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                LinearProgressIndicator(
                                    progress   = { ramPct.coerceIn(0f, 1f) },
                                    modifier   = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                                    color      = ramColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatBlock(icon: ImageVector, value: String, label: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = color,
            modifier           = Modifier.size(20.dp)
        )
        Text(
            text       = value,
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color      = color
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        Text(
            text  = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DrawerSectionLabel(text: String) {
    Text(
        text     = text.uppercase(),
        style    = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color    = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        modifier = Modifier.padding(
            start = 20.dp,
            end = 20.dp,
            top = 4.dp,
            bottom = 2.dp
        ),
        letterSpacing = 1.sp
    )
}

@Composable
fun DrawerNavItem(
    item: DrawerItemData,
    selected: Boolean,
    onClick: () -> Unit,
    settings: SettingsManager? = null
) {
    val isInstalled = item.screen?.let { settings?.isServiceInstalled(it) } ?: true

    NavigationDrawerItem(
        icon = {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) item.color.copy(alpha = 0.2f) else item.color.copy(alpha = 0.12f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (val icon = item.icon) {
                        is ImageVector -> Icon(
                            imageVector = icon,
                            contentDescription = item.label,
                            tint = if (isInstalled) item.color else item.color.copy(alpha = 0.3f),
                            modifier = Modifier.size(24.dp)
                        )
                        is Int -> Icon(
                            painter = painterResource(id = icon),
                            contentDescription = item.label,
                            tint = if (isInstalled) item.color else item.color.copy(alpha = 0.3f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        label = {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
                color = if (isInstalled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        selected = selected,
        onClick = { if (isInstalled) onClick() },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Color.Transparent,
            selectedContainerColor = item.color.copy(alpha = 0.1f)
        )
    )
}

@Composable
fun DashboardEditList(
    config  : DashboardPrefs.DashboardConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    // Ordre local pendant le drag, persisté au relâchement
    var editOrder by remember(config.order) { mutableStateOf(config.order) }

    ReorderableColumn(
        list  = editOrder,
        onSettle = { from, to ->
            val moved = editOrder.toMutableList().apply { add(to, removeAt(from)) }
            editOrder = moved
            scope.launch { DashboardPrefs.saveOrder(context, moved) }
        },
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { _, section, isDragging ->
        ReorderableItem {
            val label = when (section) {
                DashboardSection.STATS    -> stringResource(R.string.dash_section_stats)
                DashboardSection.GPIO     -> stringResource(R.string.section_gpio)
                DashboardSection.SERVICES -> stringResource(R.string.section_services)
                DashboardSection.TERMINAL -> stringResource(R.string.dash_section_terminal)
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = if (isDragging) MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
                        else MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {}) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = stringResource(R.string.dash_drag_section),
                            modifier = Modifier.draggableHandle(),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Switch(
                        checked = section !in config.hidden,
                        onCheckedChange = { visible ->
                            scope.launch { DashboardPrefs.setHidden(context, section, !visible) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun GlanceCard(settings: SettingsManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var piholeText   by remember { mutableStateOf<String?>(null) }
    var dockerText   by remember { mutableStateOf<String?>(null) }
    var showRebootDialog by remember { mutableStateOf(false) }
    val unavailable = stringResource(R.string.glance_unavailable)
    val piholeTpl  = stringResource(R.string.glance_pihole_blocked_fmt)
    val dockerTpl  = stringResource(R.string.glance_docker_count_fmt)
    val piholeOff  = stringResource(R.string.status_inactive)

    LaunchedEffect(Unit) {
        launch {
            runCatching { fetchPiHoleStatus(settings, settings.piHolePassword) }
                .getOrNull()
                ?.let { stats ->
                    piholeText = if (stats.enabled)
                        String.format(piholeTpl, stats.domainsBlocked)
                    else piholeOff
                }
        }
        launch {
            runCatching {
                val running = SshClient.execute(
                    settings.host, settings.port, settings.username, settings.password,
                    "docker ps -q | wc -l", settings.sshTimeoutMs
                ).trim().toInt()
                val total = SshClient.execute(
                    settings.host, settings.port, settings.username, settings.password,
                    "docker ps -aq | wc -l", settings.sshTimeoutMs
                ).trim().toInt()
                dockerText = context.getString(R.string.glance_docker_count, running, total)
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Visibility, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                Text(
                    stringResource(R.string.glance_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(painterResource(R.drawable.ic_widget_pihole), null, modifier = Modifier.size(16.dp), tint = Color(0xFFE53935))
                Text(piholeText ?: unavailable, style = MaterialTheme.typography.bodyMedium)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(painterResource(R.drawable.docker), null, modifier = Modifier.size(16.dp), tint = Color(0xFF0288D1))
                Text(dockerText ?: unavailable, style = MaterialTheme.typography.bodyMedium)
            }

            FilledTonalButton(
                onClick = { showRebootDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.glance_reboot))
            }
        }
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text(stringResource(R.string.glance_reboot_title)) },
            text  = { Text(stringResource(R.string.glance_reboot_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showRebootDialog = false
                    scope.launch {
                        runCatching {
                            SshClient.execute(
                                settings.host, settings.port, settings.username, settings.password,
                                "sudo reboot", settings.sshTimeoutMs
                            )
                        }
                    }
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun GpioTile(
    icon    : Any,
    label   : String,
    color   : Color,
    onClick : () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.94f else 1f, label = "scale")

    Card(
        onClick  = onClick,
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            awaitRelease()
                        } finally {
                            isPressed = false
                        }
                    },
                    onTap = { onClick() }
                )
            },
        shape    = RoundedCornerShape(20.dp),
        colors   = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.12f))
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = color.copy(alpha = 0.15f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (icon) {
                        is ImageVector -> Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = color,
                            modifier = Modifier.size(24.dp)
                        )
                        is Int -> Icon(
                            painter = painterResource(id = icon),
                            contentDescription = label,
                            tint = color,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Text(
                text       = label,
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color      = color.copy(alpha = 0.9f),
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ServiceTile(
    icon    : Any,
    label   : String,
    color   : Color,
    onClick : () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "scale")

    Card(
        onClick  = onClick,
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            awaitRelease()
                        } finally {
                            isPressed = false
                        }
                    },
                    onTap = { onClick() }
                )
            },
        shape    = RoundedCornerShape(24.dp),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.12f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (icon) {
                        is ImageVector -> Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(24.dp)
                        )
                        is Int -> Icon(
                            painter = painterResource(id = icon),
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Text(
                text       = label,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
                lineHeight = 16.sp
            )
        }
    }
}
