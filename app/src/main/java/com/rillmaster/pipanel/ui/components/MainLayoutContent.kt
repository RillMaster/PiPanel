package com.rillmaster.pipanel.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.DrawerState
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.IntOffset
import androidx.fragment.app.FragmentActivity
import com.rillmaster.pipanel.*
import com.rillmaster.pipanel.model.Screen
import com.rillmaster.pipanel.ui.screens.*
import kotlinx.coroutines.launch

/**
 * Gestionnaire d'affichage du contenu de l'écran principal avec transitions animées.
 */
@Composable
fun MainLayoutContent(
    currentScreen: Screen,
    settings: SettingsManager,
    activity: FragmentActivity,
    drawerState: DrawerState,
    windowSizeClass: WindowSizeClass,
    onNavigate: (Screen) -> Unit,
    onThemeChanged: (String) -> Unit,
    onBiometricEnabled: () -> Unit,
    isExpanded: Boolean
) {
    val isWide = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    val scope = rememberCoroutineScope()
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            val animationSpec = tween<IntOffset>(500, easing = FastOutSlowInEasing)
            val fadeSpec = tween<Float>(500)
            
            if (targetState == Screen.CONTROL || targetState == Screen.ABOUT) {
                (slideInHorizontally(animationSpec) { -it / 3 } + fadeIn(fadeSpec))
                    .togetherWith(slideOutHorizontally(animationSpec) { it } + fadeOut(fadeSpec))
            } else {
                (slideInHorizontally(animationSpec) { it } + fadeIn(fadeSpec))
                    .togetherWith(slideOutHorizontally(animationSpec) { -it / 3 } + fadeOut(fadeSpec))
            }
        },
        label = "screen_transition"
    ) { screen ->
        when (screen) {
            Screen.SETTINGS -> SettingsScreen(
                settings           = settings,
                activity           = activity,
                onThemeChanged     = onThemeChanged,
                onBiometricEnabled = onBiometricEnabled,
                onSave             = { onNavigate(Screen.CONTROL) },
                onOpenMenu         = { if (!isExpanded) scope.launch { drawerState.open() } },
                isExpanded         = isExpanded,
                onOpenVpnEditor    = { onNavigate(Screen.WIREGUARD_EDITOR) }
            )
            Screen.TERMINAL -> TerminalScreen(
                settings           = settings,
                onClose            = { onNavigate(Screen.CONTROL) },
                onOpenMenu         = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.DOCKER -> DockerScreen(
                settings           = settings,
                onClose            = { onNavigate(Screen.CONTROL) },
                onOpenMenu         = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.CHARTS -> ChartsScreen(
                settings           = settings,
                onOpenMenu         = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.MONITORING -> MonitoringScreen(
                settings           = settings,
                onOpenMenu         = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded,
                isWide = isWide
            )
            Screen.PIHOLE_CONFIG -> PiHoleConfigScreen(
                settings = settings,
                onClose  = { onNavigate(Screen.PIHOLE) },
                onSaved  = { onNavigate(Screen.PIHOLE) }
            )
            Screen.PIHOLE -> PiHoleScreen(
                settings     = settings,
                onClose      = { onNavigate(Screen.CONTROL) },
                onOpenConfig = { onNavigate(Screen.PIHOLE_CONFIG) },
                onOpenMenu   = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.WIREGUARD -> WireGuardScreen(
                settings   = settings,
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded,
                onOpenEditor = { onNavigate(Screen.WIREGUARD_EDITOR) }
            )
            Screen.WIREGUARD_EDITOR -> WireGuardEditorScreen(
                settings = settings,
                onBack = { onNavigate(Screen.WIREGUARD) },
                onSave = { onNavigate(Screen.WIREGUARD) }
            )
            Screen.NOTIFS -> NotificationSettingsScreen(
                settings   = settings,
                onBack     = { onNavigate(Screen.CONTROL) },
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.PWM -> PwmSliderScreen(
                settings   = settings,
                onClose    = { onNavigate(Screen.CONTROL) },
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.GPIO_PLANNER -> GpioScheduleScreen(
                settings   = settings,
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.SENSORS -> SensorDashboardScreen(
                settings   = settings,
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.LOGS_VIEWER -> LogsViewerScreen(
                settings   = settings,
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.FAIL2BAN -> Fail2BanScreen(
                settings   = settings,
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.UFW -> UfwScreen(
                settings   = settings,
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.FILE_MANAGER -> FileManagerScreen(
                settings   = settings,
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.SERVICES -> ServicesScreen(
                settings   = settings,
                onClose    = { onNavigate(Screen.CONTROL) },
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.NETWORK_SCANNER -> NetworkScannerScreen(
                settings   = settings,
                onClose    = { onNavigate(Screen.CONTROL) },
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.CRON_SCHEDULER -> CronSchedulerScreen(
                settings   = settings,
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.WIFI_MANAGEMENT -> WifiManagementScreen(
                settings   = settings,
                onClose    = { onNavigate(Screen.CONTROL) },
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.PROFILES -> ProfilesScreen(
                settings   = settings,
                onClose    = { onNavigate(Screen.CONTROL) },
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                showNavigationIcon = !isExpanded
            )
            Screen.ABOUT -> AboutScreen(
                onLaunchEasterEgg = { onNavigate(Screen.EASTER_EGG_OCTOPUS) },
                onOpenMenu        = { if (!isExpanded) scope.launch { drawerState.open() } },
                isExpanded        = isExpanded
            )
            Screen.EASTER_EGG_OCTOPUS -> OctopusEasterEggScreen(
                onClose = { onNavigate(Screen.ABOUT) }
            )
            Screen.CONTROL -> ControlScreen(
                settings              = settings,
                onOpenSettings        = { onNavigate(Screen.SETTINGS) },
                onOpenProfiles        = { onNavigate(Screen.PROFILES) },
                onOpenTerminal        = { onNavigate(Screen.TERMINAL) },
                onOpenDocker          = { onNavigate(Screen.DOCKER) },
                onOpenMonitoring      = { onNavigate(Screen.MONITORING) },
                onOpenPiHole          = { onNavigate(Screen.PIHOLE) },
                onOpenWireGuard       = { onNavigate(Screen.WIREGUARD) },
                onOpenPwmSlider       = { onNavigate(Screen.PWM) },
                onOpenGpioSchedule    = { onNavigate(Screen.GPIO_PLANNER) },
                onOpenSensorDashboard = { onNavigate(Screen.SENSORS) },
                onOpenNetworkScanner  = { onNavigate(Screen.NETWORK_SCANNER) },
                onOpenCronScheduler   = { onNavigate(Screen.CRON_SCHEDULER) },
                onOpenCharts          = { onNavigate(Screen.CHARTS) },
                onOpenMenu            = { if (!isExpanded) scope.launch { drawerState.open() } },
                isExpanded            = isWide
            )
            Screen.SSH_KEYS -> SshKeysScreen(
                settings   = settings,
                onOpenMenu = { if (!isExpanded) scope.launch { drawerState.open() } },
                isExpanded = isExpanded
            )
        }
    }
}
