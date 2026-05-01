package com.example.raspberrycontroller

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ── Data models ───────────────────────────────────────────────────────────────

data class SensorReading(
    val value      : Float,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class SensorState(
    val name       : String,
    val isOnline   : Boolean = false,
    val lastReading: SensorReading? = null,
    val history    : List<SensorReading> = emptyList(),
    val error      : String? = null,
)

// ── Wiring diagram data ───────────────────────────────────────────────────────

data class PinRow(val sensorPin: String, val rpiPin: String)

data class WiringSection(
    val title      : String,
    val description: String,
    val color      : Color,
    val pins       : List<PinRow>,
    val note       : String,
)

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDashboardScreen(
    settings: SettingsManager,
    onClose : () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    var pollingEnabled  by remember { mutableStateOf(value = false) }
    var pollIntervalSec by remember { mutableIntStateOf(5) }
    val showWiring      = remember { mutableStateOf(value = false) }

    var ds18b20  by remember { mutableStateOf(SensorState("DS18B20 — Température sonde")) }
    var dht22Tmp by remember { mutableStateOf(SensorState("DHT22 — Température")) }
    var dht22Hum by remember { mutableStateOf(SensorState("DHT22 — Humidité")) }

    // ── Affichage schéma câblage ──────────────────────────────────────────────
    if (showWiring.value) {
        WiringDiagramScreen { showWiring.value = false }
        return
    }

    // ── Polling loop ──────────────────────────────────────────────────────────
    LaunchedEffect(pollingEnabled, pollIntervalSec) {
        if (!pollingEnabled) return@LaunchedEffect
        while (isActive) {
            // DS18B20
            try {
                val raw  = SshClient.execute(
                    settings.host, settings.port,
                    settings.username, settings.password,
                    "cat /sys/bus/w1/devices/28-*/w1_slave | grep 't=' | sed 's/.*t=//'",
                    settings.sshTimeoutMs
                )
                val temp    = raw.trim().toFloat() / 1000f
                val reading = SensorReading(temp)
                ds18b20 = ds18b20.copy(
                    isOnline    = true,
                    lastReading = reading,
                    history     = (ds18b20.history + reading).takeLast(60),
                    error       = null
                )
            } catch (e: Exception) {
                ds18b20 = ds18b20.copy(isOnline = false, error = e.message)
            }

            // DHT22
            try {
                val raw = SshClient.execute(
                    settings.host, settings.port,
                    settings.username, settings.password,
                    "python3 -c \"import Adafruit_DHT; h,t=Adafruit_DHT.read_retry(Adafruit_DHT.DHT22,4); print(str(round(t,1))+','+str(round(h,1)))\"",
                    settings.sshTimeoutMs
                )
                val parts = raw.trim().split(",")
                val t = parts[0].toFloat()
                val h = parts[1].toFloat()
                val now = System.currentTimeMillis()

                val tr = SensorReading(t, now)
                dht22Tmp = dht22Tmp.copy(
                    isOnline    = true,
                    lastReading = tr,
                    history     = (dht22Tmp.history + tr).takeLast(60),
                    error       = null
                )
                val hr = SensorReading(h, now)
                dht22Hum = dht22Hum.copy(
                    isOnline    = true,
                    lastReading = hr,
                    history     = (dht22Hum.history + hr).takeLast(60),
                    error       = null
                )
            } catch (e: Exception) {
                dht22Tmp = dht22Tmp.copy(isOnline = false, error = e.message)
                dht22Hum = dht22Hum.copy(isOnline = false, error = e.message)
            }

            delay(pollIntervalSec * 1000L)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Capteurs temps réel", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    // Bouton schéma câblage
                    IconButton(onClick = { showWiring.value = true }) {
                        Icon(
                            imageVector        = Icons.Default.Cable,
                            contentDescription = "Schéma câblage",
                            tint               = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Bouton play/pause polling
                    IconButton(onClick = { pollingEnabled = !pollingEnabled }) {
                        Icon(
                            imageVector        = if (pollingEnabled) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = if (pollingEnabled) "Arrêter" else "Démarrer",
                            tint               = if (pollingEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PollingStatusBanner(pollingEnabled, pollIntervalSec) { pollIntervalSec = it }

            SensorCard(sensor = ds18b20,  unit = "°C", color = Color(0xFFFF5722))
            SensorCard(sensor = dht22Tmp, unit = "°C", color = Color(0xFFFF9800))
            SensorCard(sensor = dht22Hum, unit = "%",  color = Color(0xFF2196F3))
        }
    }
}

// ── Polling banner ────────────────────────────────────────────────────────────

@Composable
private fun PollingStatusBanner(
    active          : Boolean,
    intervalSec     : Int,
    onIntervalChange: (Int) -> Unit
) {
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue  = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active)
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier              = Modifier.padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) Color(0xFF4CAF50).copy(alpha = pulseAlpha)
                        else Color.Gray
                    )
            )
            Text(
                if (active) "Polling actif toutes les ${intervalSec}s" else "Polling arrêté",
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            listOf(2, 5, 10, 30).forEach { sec ->
                FilterChip(
                    selected = intervalSec == sec,
                    onClick  = { onIntervalChange(sec) },
                    label    = { Text("${sec}s", fontSize = 10.sp) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }
    }
}

// ── Sensor card ───────────────────────────────────────────────────────────────

@Composable
private fun SensorCard(
    sensor: SensorState,
    unit  : String,
    color : Color
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier            = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (sensor.isOnline) Color(0xFF4CAF50) else Color.Gray)
                )
                Text(sensor.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (sensor.isOnline) "En ligne" else "Hors ligne",
                    fontSize = 11.sp,
                    color    = if (sensor.isOnline) Color(0xFF4CAF50) else Color.Gray
                )
            }

            if (sensor.lastReading != null) {
                Row(
                    verticalAlignment     = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "%.1f".format(sensor.lastReading.value),
                        fontSize   = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color      = color
                    )
                    Text(
                        unit,
                        fontSize = 20.sp,
                        color    = color.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
            } else {
                Text(
                    sensor.error ?: "En attente de données…",
                    color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
            }

            if (sensor.history.size >= 2) {
                SensorSparkline(
                    readings = sensor.history,
                    color    = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                )
                val values = sensor.history.map { it.value }
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Min: ${"%.1f".format(values.min())}$unit",
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Max: ${"%.1f".format(values.max())}$unit",
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Sparkline ─────────────────────────────────────────────────────────────────

@Composable
private fun SensorSparkline(
    readings: List<SensorReading>,
    color   : Color,
    modifier: Modifier = Modifier
) {
    val values = readings.map { it.value }
    val minVal = values.min()
    val maxVal = values.max()
    val range  = (maxVal - minVal).coerceAtLeast(0.1f)

    Canvas(modifier = modifier) {
        val w    = size.width
        val h    = size.height
        val step = w / (values.size - 1).toFloat()

        val fillPath = Path()
        fillPath.moveTo(0f, h)
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = h - (((v - minVal) / range) * h)
            fillPath.lineTo(x, y)
        }
        fillPath.lineTo((values.size - 1) * step, h)
        fillPath.close()
        drawPath(fillPath, color = color.copy(alpha = 0.15f))

        val linePath = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = h - (((v - minVal) / range) * h)
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }
        drawPath(linePath, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        val lastX = (values.size - 1) * step
        val lastY = h - (((values.last() - minVal) / range) * h)
        drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(lastX, lastY))
    }
}

// ── Wiring diagram screen ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiringDiagramScreen(onClose: () -> Unit) {

    val sections = listOf(
        WiringSection(
            title       = "DS18B20 — Température sonde",
            description = "Capteur waterproof numérique 1-Wire. Une résistance pull-up de 4.7 kΩ entre DATA et VCC est obligatoire.",
            color       = Color(0xFFFF5722),
            pins        = listOf(
                PinRow("VCC  (fil rouge)",  "3.3V  — Pin physique 1"),
                PinRow("GND  (fil noir)",   "GND   — Pin physique 6"),
                PinRow("DATA (fil jaune)",  "GPIO4 — Pin physique 7"),
            ),
            note = "Activer le bus 1-Wire : ajouter dtoverlay=w1-gpio dans /boot/config.txt puis redémarrer."
        ),
        WiringSection(
            title       = "DHT22 — Température & Humidité",
            description = "Capteur digital AM2302. Une résistance pull-up de 10 kΩ entre DATA et VCC est recommandée.",
            color       = Color(0xFF2196F3),
            pins        = listOf(
                PinRow("Pin 1 — VCC",  "3.3V  — Pin physique 1"),
                PinRow("Pin 2 — DATA", "GPIO4 — Pin physique 7"),
                PinRow("Pin 3 — NC",   "Non connecté"),
                PinRow("Pin 4 — GND",  "GND   — Pin physique 6"),
            ),
            note = "Le numéro GPIO est configurable dans le code Python (actuellement GPIO4)."
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schéma câblage", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Carte intro GPIO Raspberry Pi
            RpiGpioBanner()

            sections.forEach { section ->
                WiringSectionCard(section)
            }

            // Rappel résistances
            ResistorReminderCard()
        }
    }
}

// ── Bannière GPIO Raspberry Pi ────────────────────────────────────────────────

@Composable
private fun RpiGpioBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E2E)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Raspberry Pi — Référence GPIO",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Mini-représentation des pins utilisés
            val usedPins = listOf(
                Triple("3.3V",  "Pin 1",  Color(0xFFFF5722)),
                Triple("GPIO4", "Pin 7",  Color(0xFF4CAF50)),
                Triple("GND",   "Pin 6",  Color(0xFF9E9E9E)),
            )
            usedPins.forEach { (label, pin, color) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Text(
                        "$pin — $label",
                        color    = Color.White,
                        fontSize = 13.sp
                    )
                }
            }

            Text(
                "💡 Commande utile : pinout (dans le terminal du RPi)",
                color    = Color(0xFFAAAAAA),
                fontSize = 11.sp
            )
        }
    }
}

// ── Carte section câblage ─────────────────────────────────────────────────────

@Composable
private fun WiringSectionCard(section: WiringSection) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier            = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Titre avec pastille couleur
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(section.color)
                )
                Text(
                    section.title,
                    fontWeight = FontWeight.Bold,
                    style      = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                section.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // En-tête tableau
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Capteur",
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 12.sp,
                    modifier   = Modifier.weight(1f),
                    color      = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Raspberry Pi",
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 12.sp,
                    modifier   = Modifier.weight(1f),
                    color      = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Lignes du tableau
            section.pins.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        row.sensorPin,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint     = section.color.copy(alpha = 0.6f)
                    )
                    Text(
                        row.rpiPin,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        color    = section.color,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (row != section.pins.last()) {
                    HorizontalDivider(
                        modifier  = Modifier.padding(vertical = 2.dp),
                        thickness = 0.5.dp,
                        color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
            }

            HorizontalDivider()

            // Note / avertissement
            Row(
                verticalAlignment     = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp).padding(top = 1.dp)
                )
                Text(
                    section.note,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ── Rappel résistances ────────────────────────────────────────────────────────

@Composable
private fun ResistorReminderCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF8E1)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.Top
        ) {
            Icon(
                Icons.Default.Lightbulb,
                contentDescription = null,
                tint     = Color(0xFFF57F17),
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Résistances pull-up",
                    fontWeight = FontWeight.SemiBold,
                    color      = Color(0xFFF57F17),
                    fontSize   = 13.sp
                )
                Text(
                    "• DS18B20 : 4.7 kΩ entre DATA et VCC (obligatoire)\n" +
                            "• DHT22   : 10 kΩ entre DATA et VCC (recommandé)\n\n" +
                            "Sans ces résistances, les capteurs peuvent être instables ou ne pas répondre.",
                    fontSize = 12.sp,
                    color    = Color(0xFF5D4037)
                )
            }
        }
    }
}