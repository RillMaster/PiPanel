package com.rillmaster.pipanel.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rillmaster.pipanel.DashboardPrefs
import com.rillmaster.pipanel.DashboardSection
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.model.fetchSystemStats
import com.rillmaster.pipanel.model.DashboardTileData
import com.rillmaster.pipanel.model.SystemStats
import com.rillmaster.pipanel.ui.components.*
import com.rillmaster.pipanel.ui.viewmodels.ControlViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(
    settings              : SettingsManager,
    onOpenSettings        : () -> Unit,
    onOpenProfiles        : () -> Unit,
    onOpenTerminal        : () -> Unit,
    onOpenDocker          : () -> Unit,
    onOpenMonitoring      : () -> Unit,
    onOpenPiHole          : () -> Unit,
    onOpenWireGuard       : () -> Unit,
    onOpenPwmSlider       : () -> Unit,
    onOpenGpioSchedule    : () -> Unit,
    onOpenSensorDashboard : () -> Unit,
    onOpenNetworkScanner  : () -> Unit,
    onOpenCronScheduler   : () -> Unit,
    onOpenCharts          : () -> Unit,
    onOpenMenu            : () -> Unit,
    isExpanded            : Boolean = false
) {
    val viewModel: ControlViewModel = remember { ControlViewModel(settings) }
    val uiState by viewModel.uiState.collectAsState()
    var editMode by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val dashConfig by DashboardPrefs.flow(context)
        .collectAsState(initial = DashboardPrefs.DashboardConfig())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { onOpenProfiles() }) {
                        Text(settings.getCurrentProfile()?.name ?: stringResource(R.string.nav_dashboard), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(settings.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                },
                navigationIcon = {
                    if (!isExpanded) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(
                            if (editMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = stringResource(
                                if (editMode) R.string.dash_customize_done else R.string.dash_customize
                            ),
                            tint = if (editMode) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                }
            )
        }
    ) { padding ->
        if (editMode) {
            DashboardEditList(
                config   = dashConfig,
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            dashConfig.order.filter { it !in dashConfig.hidden }.forEach { section ->
                when (section) {
                    DashboardSection.STATS -> {
                        SystemStatusBar(
                            settings = settings,
                            stats    = uiState.systemStats,
                            loading  = uiState.statsLoading
                        )
                        GlanceCard(settings = settings)
                    }

                    DashboardSection.GPIO -> {
                        GpioSection(isExpanded, onOpenPwmSlider, onOpenGpioSchedule, onOpenSensorDashboard)
                    }

                    DashboardSection.SERVICES -> {
                        ServicesSection(
                            isExpanded, onOpenMonitoring, onOpenCharts, onOpenDocker,
                            onOpenPiHole, onOpenWireGuard, onOpenNetworkScanner, onOpenCronScheduler
                        )
                    }

                    DashboardSection.TERMINAL -> {
                        TerminalQuickCard(onOpenTerminal)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpioSection(
    isExpanded           : Boolean,
    onOpenPwmSlider      : () -> Unit,
    onOpenGpioSchedule   : () -> Unit,
    onOpenSensorDashboard: () -> Unit
) {
    SectionHeader(stringResource(R.string.section_gpio), Icons.Default.Bolt)

    val gpioTiles = listOf(
        DashboardTileData(Icons.Default.Tune, stringResource(R.string.nav_pwm), Color(0xFF7C4DFF), onOpenPwmSlider),
        DashboardTileData(Icons.Default.Schedule, stringResource(R.string.nav_gpio_planner), Color(0xFF00897B), onOpenGpioSchedule),
        DashboardTileData(Icons.Default.Sensors, stringResource(R.string.nav_sensors), Color(0xFF1565C0), onOpenSensorDashboard)
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = if (isExpanded) 3 else 3
    ) {
        val tileModifier = Modifier.weight(1f)
        gpioTiles.forEach { data ->
            GpioTile(
                icon     = data.icon,
                label    = data.label,
                color    = data.color,
                onClick  = data.onClick,
                modifier = tileModifier
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServicesSection(
    isExpanded          : Boolean,
    onOpenMonitoring    : () -> Unit,
    onOpenCharts        : () -> Unit,
    onOpenDocker        : () -> Unit,
    onOpenPiHole        : () -> Unit,
    onOpenWireGuard     : () -> Unit,
    onOpenNetworkScanner: () -> Unit,
    onOpenCronScheduler : () -> Unit
) {
    SectionHeader(stringResource(R.string.section_services), Icons.Default.Dns)

    val serviceTiles = listOf(
        DashboardTileData(Icons.Default.BarChart, stringResource(R.string.nav_monitoring), Color(0xFF2196F3), onOpenMonitoring),
        DashboardTileData(Icons.Default.ShowChart, stringResource(R.string.nav_charts), Color(0xFF0097A7), onOpenCharts),
        DashboardTileData(R.drawable.docker, stringResource(R.string.nav_docker), Color(0xFF0288D1), onOpenDocker),
        DashboardTileData(R.drawable.ic_widget_pihole, stringResource(R.string.nav_pihole), Color(0xFFE53935), onOpenPiHole),
        DashboardTileData(R.drawable.wireguard, stringResource(R.string.nav_wireguard), Color(0xFF43A047), onOpenWireGuard),
        DashboardTileData(Icons.Default.NetworkCheck, stringResource(R.string.nav_net_scan), Color(0xFF673AB7), onOpenNetworkScanner),
        DashboardTileData(Icons.Default.Schedule, stringResource(R.string.nav_cron), Color(0xFF8E24AA), onOpenCronScheduler)
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = if (isExpanded) 3 else 2
    ) {
        serviceTiles.forEach { data ->
            ServiceTile(
                icon = data.icon,
                label = data.label,
                color = data.color,
                onClick = data.onClick,
                modifier = if (isExpanded) Modifier.weight(1f) else Modifier.fillMaxWidth(0.48f)
            )
        }
    }
}

@Composable
private fun TerminalQuickCard(onOpenTerminal: () -> Unit) {
    Card(
        onClick = onOpenTerminal,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Terminal, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.terminal_card_title),
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
