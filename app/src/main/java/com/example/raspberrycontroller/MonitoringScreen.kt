package com.example.raspberrycontroller

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ══════════════════════════════════════════════════════════════════════════════
//  Constantes
// ══════════════════════════════════════════════════════════════════════════════

private const val MAX_HISTORY = 60   // 60 points → ~10 min à 10 s/point

// ══════════════════════════════════════════════════════════════════════════════
//  Modèles de données
// ══════════════════════════════════════════════════════════════════════════════

data class DiskPartition(
    val mountPoint : String,
    val totalMb    : Long,
    val usedMb     : Long,
    val availMb    : Long,
    val usedPercent: Int,
)

data class ExtendedStats(
    val base           : SystemStats,
    val disks          : List<DiskPartition>,
    val netRxBytes     : Long,
    val netTxBytes     : Long
)

// ══════════════════════════════════════════════════════════════════════════════
//  Script Python — parsing robuste via regex
//  - RAM  : re.search sur /proc/meminfo (évite les bugs startswith)
//  - CPU  : moyenne sur 0.5 s via /proc/stat (plus fiable que loadavg)
//  - Temp : /sys/class/thermal
//  - GPU  : vcgencmd measure_temp
// ══════════════════════════════════════════════════════════════════════════════

private val STATS_SCRIPT = """
import re, subprocess, time

# ── RAM via regex sur /proc/meminfo ──────────────────────────────────────────
meminfo = open('/proc/meminfo').read()
def mi(k):
    m = re.search(r'^' + k + r':\s+(\d+)', meminfo, re.MULTILINE)
    return int(m.group(1)) if m else 0

mem_total = mi('MemTotal')
mem_avail = mi('MemAvailable')
mem_used  = mem_total - mem_avail

# ── CPU via /proc/stat (delta sur 0.5 s) ─────────────────────────────────────
def read_cpu():
    line = open('/proc/stat').readline()
    vals = list(map(int, line.split()[1:]))
    idle  = vals[3]
    total = sum(vals)
    return idle, total

idle1, total1 = read_cpu()
time.sleep(0.5)
idle2, total2 = read_cpu()
d_total = total2 - total1
d_idle  = idle2  - idle1
cpu_pct = round((1.0 - d_idle / d_total) * 100.0, 1) if d_total > 0 else 0.0

# ── Température CPU ───────────────────────────────────────────────────────────
temp = int(open('/sys/class/thermal/thermal_zone0/temp').read()) / 1000.0

# ── Network ──────────────────────────────────────────────────────────────────
net = open('/proc/net/dev').readlines()
rx = 0
tx = 0
for line in net:
    if ':' in line and 'lo:' not in line:
        parts = line.split(':')[1].split()
        rx += int(parts[0])
        tx += int(parts[8])

print(
    str(round(temp, 1)) + ',' +
    str(round(cpu_pct, 1)) + ',' +
    str(mem_used  // 1024) + ',' +
    str(mem_total // 1024) + ',' +
    str(rx) + ',' +
    str(tx)
)
""".trimIndent()

// ══════════════════════════════════════════════════════════════════════════════
//  Fetch SSH (withContext IO + script passé via base64)
// ══════════════════════════════════════════════════════════════════════════════

suspend fun fetchExtendedStats(settings: SettingsManager): ExtendedStats? =
    withContext(Dispatchers.IO) {
        try {
            val b64 = android.util.Base64.encodeToString(
                STATS_SCRIPT.toByteArray(), android.util.Base64.NO_WRAP
            )
            val cmd = "echo '$b64' | base64 -d | python3" +
                    " && echo '---DISK---'" +
                    " && df -BM --output=target,size,used,avail,pcent 2>/dev/null | grep '^/'"

            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                cmd, settings.sshTimeoutMs
            )

            val sections  = raw.split("---DISK---")
            val statLine  = sections.getOrNull(0)?.trim() ?: return@withContext null
            val statParts = statLine.split(",")
            if (statParts.size < 6) return@withContext null

            val base = SystemStats(
                tempCelsius = statParts[0].toDouble(),
                cpuPercent  = statParts[1].toDouble().toInt().coerceIn(0, 100),
                ramUsedMb   = statParts[2].toInt(),
                ramTotalMb  = statParts[3].toInt()
            )

            val netRx = statParts[4].toLongOrNull() ?: 0L
            val netTx = statParts[5].toLongOrNull() ?: 0L

            val disks = sections.getOrNull(1)
                ?.lines()
                ?.asSequence()
                ?.filter { it.isNotBlank() }
                ?.mapNotNull { line ->
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size >= 5) {
                        val mount = p[0]
                        // Filtre pour ne garder que les partitions "réelles"
                        val isSystem = mount.startsWith("/run") || mount.startsWith("/dev") ||
                                       mount.startsWith("/proc") || mount.startsWith("/sys") || mount == "/tmp"
                        val isImportant = mount == "/" || mount.startsWith("/boot") || 
                                          mount.startsWith("/media") || mount.startsWith("/mnt")

                        if (isSystem && !isImportant) return@mapNotNull null

                        fun mb(s: String) = s.trimEnd('M').toLongOrNull() ?: 0L
                        DiskPartition(
                            mountPoint  = mount,
                            totalMb     = mb(p[1]),
                            usedMb      = mb(p[2]),
                            availMb     = mb(p[3]),
                            usedPercent = p[4].trimEnd('%').toIntOrNull() ?: 0
                        )
                    } else null
                }?.toList() ?: emptyList()

            ExtendedStats(base, disks, netRx, netTx)
        } catch (_: Exception) {
            null
        }
    }

// ══════════════════════════════════════════════════════════════════════════════
//  Composable : Sparkline Canvas
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun SparklineChart(
    modifier: Modifier = Modifier,
    values  : List<Float>,
    color   : Color,
    maxValue: Float = 100f,
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val w    = size.width
        val h    = size.height
        val step = w / (MAX_HISTORY - 1).toFloat()

        fun xAt(i: Int) = ((MAX_HISTORY - values.size) + i) * step
        fun yAt(v: Float) = h - ((v / maxValue).coerceIn(0f, 1f) * h)

        val fillPath = Path().apply {
            moveTo(xAt(0), h)
            lineTo(xAt(0), yAt(values[0]))
            for (i in 1 until values.size) lineTo(xAt(i), yAt(values[i]))
            lineTo(xAt(values.size - 1), h)
            close()
        }
        drawPath(
            path  = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
                startY = 0f,
                endY   = h
            )
        )

        val linePath = Path().apply {
            moveTo(xAt(0), yAt(values[0]))
            for (i in 1 until values.size) lineTo(xAt(i), yAt(values[i]))
        }
        drawPath(
            path  = linePath,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        drawCircle(
            color  = color,
            radius = 3.dp.toPx(),
            center = Offset(xAt(values.size - 1), yAt(values.last()))
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Composable : Carte graphique avec stats Min/Moy/Max
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun ChartCard(
    title   : String,
    values  : List<Float>,
    color   : Color,
    maxValue: Float = 100f,
    unit    : String = "%"
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier            = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            ) {
                Column(
                    modifier            = Modifier
                        .width(34.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        "${maxValue.toInt()}$unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "0$unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(6.dp))
                SparklineChart(
                    values   = values,
                    color    = color,
                    maxValue = maxValue,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            // FIX : formater le nombre séparément puis concaténer l'unité
            // pour éviter UnknownFormatConversionException quand unit="%"
            if (values.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MiniStat(stringResource(R.string.stat_min), "${"%.0f".format(values.min())}$unit",              Color(0xFF66BB6A))
                    MiniStat(stringResource(R.string.stat_avg), "${"%.0f".format(values.average().toFloat())}$unit", color)
                    MiniStat(stringResource(R.string.stat_max), "${"%.0f".format(values.max())}$unit",              Color(0xFFEF5350))
                }
            }
        }
    }
}

@Composable
fun NetworkCard(rxSpeed: Long, txSpeed: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Network Speed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MiniStat("Download", formatSpeed(rxSpeed), Color(0xFF4CAF50))
                MiniStat("Upload", formatSpeed(txSpeed), Color(0xFF2196F3))
            }
        }
    }
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec < 1024) return "$bytesPerSec B/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024) return String.format("%.1f KB/s", kb)
    val mb = kb / 1024.0
    return String.format("%.1f MB/s", mb)
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge,
            color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Composable : Barre de partition disque
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun DiskBar(disk: DiskPartition) {
    val barColor = when {
        disk.usedPercent >= 90 -> Color(0xFFEF5350)
        disk.usedPercent >= 70 -> Color(0xFFFF9800)
        else                   -> Color(0xFF66BB6A)
    }
    @Composable
    fun Long.fmt() = if (this >= 1024) {
        "${"%.1f".format(this / 1024.0)} ${stringResource(R.string.unit_gb)}"
    } else {
        "$this ${stringResource(R.string.unit_mb)}"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            disk.mountPoint,
            fontFamily = FontFamily.Monospace,
            style      = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                "${disk.usedMb.fmt()} / ${disk.totalMb.fmt()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${disk.usedPercent}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = barColor
            )
        }
        LinearProgressIndicator(
            progress   = { (disk.usedPercent / 100f).coerceIn(0f, 1f) },
            modifier   = Modifier.fillMaxWidth().height(8.dp),
            color      = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Écran principal : Monitoring avancé
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringScreen(settings: SettingsManager, onClose: () -> Unit, onOpenMenu: () -> Unit) {

    val cpuHistory  = remember { mutableStateListOf<Float>() }
    val ramHistory  = remember { mutableStateListOf<Float>() }
    val tempHistory = remember { mutableStateListOf<Float>() }

    var current by remember { mutableStateOf<ExtendedStats?>(null) }
    var loading by remember { mutableStateOf(value = true) }
    var error   by remember { mutableStateOf(value = false) }

    var lastNetRx by remember { mutableLongStateOf(0L) }
    var lastNetTx by remember { mutableLongStateOf(0L) }
    var rxSpeed by remember { mutableLongStateOf(0L) }
    var txSpeed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            val stats = fetchExtendedStats(settings)

            if (stats != null) {
                if (lastNetRx > 0) {
                    val deltaRx = stats.netRxBytes - lastNetRx
                    val deltaTx = stats.netTxBytes - lastNetTx
                    val interval = settings.tempRefreshMs / 1000.0
                    rxSpeed = if (deltaRx >= 0) (deltaRx / interval).toLong() else 0
                    txSpeed = if (deltaTx >= 0) (deltaTx / interval).toLong() else 0
                }
                lastNetRx = stats.netRxBytes
                lastNetTx = stats.netTxBytes

                current = stats
                loading = false
                error   = false

                fun <T> MutableList<T>.push(v: T) { add(v); if (size > MAX_HISTORY) removeAt(0) }

                cpuHistory .push(stats.base.cpuPercent.toFloat())
                ramHistory .push(
                    if (stats.base.ramTotalMb > 0)
                        stats.base.ramUsedMb.toFloat() / stats.base.ramTotalMb * 100f
                    else 0f
                )
                tempHistory.push(stats.base.tempCelsius.toFloat())
            } else {
                loading = false
                error   = (current == null)
            }
            delay(settings.tempRefreshMs.toLong())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text(stringResource(R.string.monitoring_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = loading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "monitoring_main_transition"
        ) { isLoading ->
            if (isLoading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
                        Text(
                            stringResource(R.string.loading_stats),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(
                    modifier            = Modifier
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(Modifier.height(8.dp))

                    if (error) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                                Text(
                                    stringResource(R.string.error_fetch_stats),
                                    color    = MaterialTheme.colorScheme.onErrorContainer,
                                    style    = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    // ── CPU ──────────────────────────────────────────────────────
                    AnimatedVisibility(visible = cpuHistory.isNotEmpty()) {
                        ChartCard(
                            title    = stringResource(R.string.cpu_load_title, current?.base?.cpuPercent ?: 0),
                            values   = cpuHistory.toList(),
                            color    = Color(0xFF2196F3),
                            maxValue = 100f,
                            unit     = "%"
                        )
                    }

                    // ── RAM ──────────────────────────────────────────────────────
                    AnimatedVisibility(visible = ramHistory.isNotEmpty()) {
                        val ramPct = current?.let {
                            if (it.base.ramTotalMb > 0) it.base.ramUsedMb * 100 / it.base.ramTotalMb else 0
                        } ?: 0
                        ChartCard(
                            title    = stringResource(
                                R.string.ram_usage_title,
                                current?.base?.ramUsedMb ?: 0,
                                current?.base?.ramTotalMb ?: 0,
                                ramPct
                            ),
                            values   = ramHistory.toList(),
                            color    = Color(0xFF9C27B0),
                            maxValue = 100f,
                            unit     = "%"
                        )
                    }

                    // ── Température CPU ──────────────────────────────────────────
                    AnimatedVisibility(visible = tempHistory.isNotEmpty()) {
                        ChartCard(
                            title    = stringResource(R.string.temp_title, current?.base?.tempCelsius ?: 0.0),
                            values   = tempHistory.toList(),
                            color    = Color(0xFFF44336),
                            maxValue = 90f,
                            unit     = "°C"
                        )
                    }

                    // ── Réseau ───────────────────────────────────────────────────
                    NetworkCard(rxSpeed, txSpeed)

                    // ── Disques ──────────────────────────────────────────────────
                    val disks = current?.disks
                    if (!disks.isNullOrEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier            = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                disks.forEach { DiskBar(it) }
                            }
                        }
                    }

                    // ── Légende refresh ──────────────────────────────────────────
                    val intervalSec = settings.tempRefreshMs / 1000
                    Text(
                        stringResource(R.string.refresh_history_legend, intervalSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
        }
    }
}
