package com.rillmaster.pipanel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    settings: SettingsManager,
    onBack  : () -> Unit = {},
    onOpenMenu: () -> Unit
) {
    val context = LocalContext.current

    var notifEnabled     by remember { mutableStateOf(settings.notificationsEnabled) }
    var cpuEnabled       by remember { mutableStateOf(settings.cpuAlertsEnabled) }
    var cpuThreshold     by remember { mutableFloatStateOf(settings.cpuThreshold.toFloat()) }
    var ramEnabled       by remember { mutableStateOf(settings.ramAlertsEnabled) }
    var ramThreshold     by remember { mutableFloatStateOf(settings.ramThreshold.toFloat()) }
    var watchdogEnabled  by remember { mutableStateOf(settings.watchdogEnabled) }
    var watchdogInterval by remember { mutableFloatStateOf(settings.watchdogIntervalSeconds.toFloat()) }
    var dockerEnabled    by remember { mutableStateOf(settings.dockerAlertsEnabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "🔔 Notifications",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlobalToggleCard(
                enabled  = notifEnabled,
                onToggle = {
                    notifEnabled                  = it
                    settings.notificationsEnabled = it
                    if (it) MonitoringWorker.schedule(context)
                    else    MonitoringWorker.cancel(context)
                }
            )

            if (notifEnabled) {

                // ── CPU : de 10% à 100%, pas de 5 ────────────────────────────
                AlertSectionCard(
                    icon     = Icons.Default.Memory,
                    title    = "Alerte CPU",
                    enabled  = cpuEnabled,
                    onToggle = { cpuEnabled = it; settings.cpuAlertsEnabled = it }
                ) {
                    ThresholdSlider(
                        label      = "Seuil CPU",
                        value      = cpuThreshold,
                        unit       = "%",
                        enabled    = cpuEnabled,
                        valueRange = 10f..100f,
                        step       = 5,
                        onChanged  = { cpuThreshold = it; settings.cpuThreshold = it.toInt() }
                    )
                }

                // ── RAM : de 10% à 100%, pas de 5 ────────────────────────────
                AlertSectionCard(
                    icon     = Icons.Default.Storage,
                    title    = "Alerte RAM",
                    enabled  = ramEnabled,
                    onToggle = { ramEnabled = it; settings.ramAlertsEnabled = it }
                ) {
                    ThresholdSlider(
                        label      = "Seuil RAM",
                        value      = ramThreshold,
                        unit       = "%",
                        enabled    = ramEnabled,
                        valueRange = 10f..100f,
                        step       = 5,
                        onChanged  = { ramThreshold = it; settings.ramThreshold = it.toInt() }
                    )
                }

                // ── Watchdog : de 10s à 120s, pas de 10 ──────────────────────
                AlertSectionCard(
                    icon     = Icons.Default.Wifi,
                    title    = "Watchdog Pi",
                    enabled  = watchdogEnabled,
                    onToggle = { watchdogEnabled = it; settings.watchdogEnabled = it }
                ) {
                    ThresholdSlider(
                        label      = "Intervalle de vérification",
                        value      = watchdogInterval,
                        unit       = "s",
                        enabled    = watchdogEnabled,
                        valueRange = 10f..120f,
                        step       = 10,
                        onChanged  = {
                            watchdogInterval                 = it
                            settings.watchdogIntervalSeconds = it.toInt()
                        }
                    )
                }

                // ── Docker ────────────────────────────────────────────────────
                AlertSectionCard(
                    icon     = Icons.Default.Cloud,
                    title    = "Services Docker",
                    enabled  = dockerEnabled,
                    onToggle = { dockerEnabled = it; settings.dockerAlertsEnabled = it }
                ) {
                    Text(
                        text  = "Alerte envoyée dès qu'un conteneur s'arrête de manière inattendue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Composants internes ───────────────────────────────────────────────────────

@Composable
private fun GlobalToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Notifications actives", 
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (enabled) "Surveillance en cours" else "Toutes les alertes sont désactivées",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun AlertSectionCard(
    icon    : ImageVector,
    title   : String,
    enabled : Boolean,
    onToggle: (Boolean) -> Unit,
    content : @Composable ColumnScope.() -> Unit
) {
    Card {
        Column(
            modifier            = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment      = Alignment.CenterVertically,
                    horizontalArrangement  = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        title, 
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) content()
        }
    }
}

/**
 * Slider à pas réguliers.
 *
 * @param step   Pas réel entre chaque valeur (ex 5 → 10, 15, 20…).
 *               Compose `steps` = nb de paliers intermédiaires = (étendue / pas) - 1.
 */
@Composable
private fun ThresholdSlider(
    label      : String,
    value      : Float,
    unit       : String,
    enabled    : Boolean,
    onChanged  : (Float) -> Unit,
    valueRange : ClosedFloatingPointRange<Float> = 10f..100f,
    step       : Int = 5
) {
    // Snap la valeur courante au pas (sécurité si la valeur stockée ne tombe pas pile)
    val snapped = (kotlin.math.round(value / step) * step)
        .coerceIn(valueRange)

    // Compose veut le nb de paliers INTERMÉDIAIRES (sans les deux extrémités)
    val composeSteps = ((valueRange.endInclusive - valueRange.start) / step).toInt() - 1

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label, 
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                "${snapped.toInt()}$unit",
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value         = snapped,
            onValueChange = { raw ->
                // Snap en temps réel pendant le glissement
                val stepped = (kotlin.math.round(raw / step) * step)
                    .coerceIn(valueRange)
                onChanged(stepped)
            },
            valueRange    = valueRange,
            steps         = composeSteps,
            enabled       = enabled
        )
    }
}