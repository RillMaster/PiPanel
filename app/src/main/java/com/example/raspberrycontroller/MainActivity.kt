@file:Suppress("UNUSED_VALUE")

package com.example.raspberrycontroller

import android.Manifest
import android.content.pm.PackageManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager
import android.os.Bundle
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.raspberrycontroller.ui.theme.RaspberryControllerTheme
import kotlinx.coroutines.*
import sh.calvin.reorderable.ReorderableColumn
import org.json.JSONObject
import java.net.URL

// ══════════════════════════════════════════════════════════════════════════════
//  Modèle de données pour les stats système
// ══════════════════════════════════════════════════════════════════════════════
data class SystemStats(
    val tempCelsius: Double,
    val cpuPercent : Int,
    val ramUsedMb  : Int,
    val ramTotalMb : Int,
)

private val SYSTEM_STATS_SCRIPT = """
import re, time

meminfo = open('/proc/meminfo').read()
def mi(k):
    m = re.search(r'^' + k + r':\s+(\d+)', meminfo, re.MULTILINE)
    return int(m.group(1)) if m else 0

mem_total = mi('MemTotal')
mem_avail = mi('MemAvailable')
mem_used  = mem_total - mem_avail

def read_cpu():
    vals = list(map(int, open('/proc/stat').readline().split()[1:]))
    return vals[3], sum(vals)

idle1, total1 = read_cpu()
time.sleep(0.5)
idle2, total2 = read_cpu()
dt = total2 - total1
cpu_pct = round((1.0 - (idle2 - idle1) / dt) * 100.0, 1) if dt > 0 else 0.0

temp = int(open('/sys/class/thermal/thermal_zone0/temp').read()) / 1000.0

print(str(round(temp,1))+','+str(cpu_pct)+','+str(mem_used//1024)+','+str(mem_total//1024))
""".trimIndent()

suspend fun fetchSystemStats(settings: SettingsManager): SystemStats? =
    withContext(Dispatchers.IO) {
        try {
            val b64 = android.util.Base64.encodeToString(
                SYSTEM_STATS_SCRIPT.toByteArray(), android.util.Base64.NO_WRAP
            )
            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                "echo '$b64' | base64 -d | python3",
                settings.sshTimeoutMs
            )
            val parts = raw.trim().split(",")
            if (parts.size < 4) return@withContext null
            SystemStats(
                tempCelsius = parts[0].toDouble(),
                cpuPercent  = parts[1].toDouble().toInt().coerceIn(0, 100),
                ramUsedMb   = parts[2].toInt(),
                ramTotalMb  = parts[3].toInt()
            )
        } catch (_: Exception) { null }
    }

// ══════════════════════════════════════════════════════════════════════════════
//  MainApp — gestion de la navigation entre écrans
// ══════════════════════════════════════════════════════════════════════════════
enum class Screen {
    CONTROL, SETTINGS, TERMINAL, DOCKER, MONITORING,
    PIHOLE, PIHOLE_CONFIG, WIREGUARD, NOTIFS, PWM, GPIO_PLANNER, SENSORS, ABOUT,
    EASTER_EGG_OCTOPUS, LOGS_VIEWER, FAIL2BAN, UFW, FILE_MANAGER
}

data class DrawerItemData(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val screen: Screen? = null
)

// ══════════════════════════════════════════════════════════════════════════════
//  MainActivity
// ══════════════════════════════════════════════════════════════════════════════
class MainActivity : FragmentActivity() {

    private val versionUrl =
        "https://raw.githubusercontent.com/RillMaster/RaspberryController/main/version.json"
    private val changelogUrl =
        "https://raw.githubusercontent.com/RillMaster/RaspberryController/main/changelog.md"

    private var downloadProgress = mutableIntStateOf(-2)

    companion object {
        private const val REQUEST_CODE_NOTIF = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        NotificationHelper.createChannels(this)
        requestNotificationPermissionIfNeeded()
        val settings = SettingsManager(this)
        if (settings.notificationsEnabled) {
            MonitoringWorker.schedule(this)
        }

        // Widget stats
        UpdateStatsWorker.schedulePeriodic(this)
        WidgetUpdateService.start(this)

        setContent {
            val context   = LocalContext.current
            val settingsR = remember { SettingsManager(context) }
            var themePref by remember { mutableStateOf(settingsR.theme) }

            val darkTheme = when (themePref) {
                "light" -> false
                "dark"  -> true
                else    -> isSystemInDarkTheme()
            }

            RaspberryControllerTheme(darkTheme = darkTheme) {
                val progress by downloadProgress
                if (progress >= -1) {
                    DownloadProgressDialog(
                        progress  = progress,
                        onDismiss = { if (progress == -1) downloadProgress.intValue = -2 }
                    )
                }
                AppEntryPoint(
                    activity       = this@MainActivity,
                    settings       = settingsR,
                    onThemeChanged = { newTheme ->
                        settingsR.theme = newTheme
                        themePref       = newTheme
                    },
                    onAppReady = { checkForUpdates() }
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIF
                )
            }
        }
    }

    // ── Dialog progression téléchargement ────────────────────────────────────
    @Composable
    fun DownloadProgressDialog(progress: Int, onDismiss: () -> Unit) {
        Dialog(onDismissRequest = { if (progress == -1) onDismiss() }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape    = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier            = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleLarge)
                    when (progress) {
                        -1 -> {
                            Icon(
                                Icons.Default.Error, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp)
                            )
                            Text(stringResource(R.string.update_error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_close))
                            }
                        }
                        100 -> {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = Color(0xFF66BB6A), modifier = Modifier.size(48.dp))
                            Text(stringResource(R.string.update_success), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.update_installing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("$progress%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.update_wait),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // ── Vérification des mises à jour ─────────────────────────────────────────
    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp         = System.currentTimeMillis()
                val jsonRaw           = URL("$versionUrl?t=$timestamp").readText().trim()
                val jsonObject        = JSONObject(jsonRaw)
                val latestVersionCode = jsonObject.getLong("versionCode")
                val latestVersionName = jsonObject.optString("versionName", getString(R.string.version_unknown))
                val apkUrl            = jsonObject.getString("url")

                val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageManager.getPackageInfo(packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
                }

                withContext(Dispatchers.Main) {
                    if (latestVersionCode > currentVersionCode) {
                        val changelog = try {
                            withContext(Dispatchers.IO) {
                                URL("$changelogUrl?t=$timestamp").readText().trim()
                            }
                        } catch (_: Exception) { "" }
                        showUpdateDialog(changelog, apkUrl, latestVersionName)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showUpdateDialog(changelog: String, downloadUrl: String, latestVersion: String) {
        val message = buildString {
            append(getString(R.string.update_message, latestVersion))
            if (changelog.isNotEmpty()) append(getString(R.string.update_changelog, changelog))
            append(getString(R.string.update_prompt))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.update_action_now)) { _, _ ->
                downloadProgress.intValue = 0
                UpdateManager(this).downloadAndInstall(downloadUrl) { progress ->
                    downloadProgress.intValue = progress
                    if (progress == 100) {
                        lifecycleScope.launch { delay(3000); downloadProgress.intValue = -2 }
                    }
                }
            }
            .setNegativeButton(getString(R.string.update_action_later), null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Point d'entrée de l'app
    // ══════════════════════════════════════════════════════════════════════════
    @Composable
    fun AppEntryPoint(
        activity      : FragmentActivity,
        settings      : SettingsManager,
        onThemeChanged: (String) -> Unit,
        onAppReady    : () -> Unit
    ) {
        val showOnboarding = remember { mutableStateOf(settings.isFirstLaunch) }

        if (showOnboarding.value) {
            OnboardingScreen(
                activity   = activity,
                settings   = settings,
                onFinished = { showOnboarding.value = false }
            )
        } else {
            val isAuthenticated = remember { mutableStateOf(!settings.biometricEnabled) }
            val authError       = remember { mutableStateOf<String?>(null) }

            if (!isAuthenticated.value) {
                LaunchedEffect(Unit) {
                    BiometricHelper.authenticate(
                        activity  = activity,
                        onSuccess = { isAuthenticated.value = true },
                        onError   = { authError.value = it }
                    )
                }
                BiometricLockScreen(
                    error   = authError.value,
                    onRetry = {
                        authError.value = null
                        BiometricHelper.authenticate(
                            activity  = activity,
                            onSuccess = { isAuthenticated.value = true },
                            onError   = { authError.value = it }
                        )
                    }
                )
            } else {
                LaunchedEffect(Unit) { onAppReady() }
                MainApp(
                    activity           = activity,
                    settings           = settings,
                    onThemeChanged     = onThemeChanged,
                    onBiometricEnabled = { isAuthenticated.value = false; authError.value = null }
                )
            }
        }
    }

    // ── Écran de verrouillage biométrique ─────────────────────────────────────
    @Composable
    fun BiometricLockScreen(error: String?, onRetry: () -> Unit) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier            = Modifier.padding(40.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = stringResource(R.string.logo_content_description),
                            modifier = Modifier.size(70.dp),
                            contentScale = ContentScale.Fit
                        )
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier
                                .size(30.dp)
                                .align(Alignment.BottomEnd)
                                .offset(x = (-5).dp, y = (-5).dp)
                                .background(MaterialTheme.colorScheme.background, CircleShape)
                                .padding(4.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text("RaspberryController", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.auth_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (error != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text     = error,
                                modifier = Modifier.padding(12.dp),
                                color    = MaterialTheme.colorScheme.onErrorContainer,
                                style    = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
    }

    @Composable
    fun MainApp(
        activity          : FragmentActivity,
        settings          : SettingsManager,
        onThemeChanged    : (String) -> Unit,
        onBiometricEnabled: () -> Unit
    ) {
        val currentScreen = remember {
            mutableStateOf(if (settings.isConfigured()) Screen.CONTROL else Screen.SETTINGS)
        }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        // Détection automatique des services au premier lancement ou après 24h
        LaunchedEffect(settings.host) {
            if (settings.isConfigured()) {
                val now = System.currentTimeMillis()
                val oneDay = 24 * 60 * 60 * 1000L
                if (settings.lastServiceScan == 0L || (now - settings.lastServiceScan) > oneDay || !settings.isServiceInstalled(Screen.UFW) || !settings.isServiceInstalled(Screen.WIREGUARD)) {
                    scope.launch(Dispatchers.IO) {
                        val services = mapOf(
                            Screen.DOCKER    to "systemctl is-active docker > /dev/null 2>&1 && echo 'ok'",
                            Screen.PIHOLE    to "systemctl is-active pihole-FTL > /dev/null 2>&1 && echo 'ok'",
                            Screen.WIREGUARD to "systemctl is-active wg-quick@wg0 > /dev/null 2>&1 && echo 'ok'",
                            Screen.FAIL2BAN  to "systemctl is-active fail2ban > /dev/null 2>&1 && echo 'ok'",
                            Screen.UFW       to "systemctl is-active ufw > /dev/null 2>&1 && echo 'ok'"
                        )
                        services.forEach { (screen, cmd) ->
                            val res = SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd)
                            settings.setServiceInstalled(screen, !res.startsWith("[err]") && res.isNotBlank())
                        }
                        settings.lastServiceScan = now
                    }
                }
            }
        }

        // Gestion du bouton retour système
        BackHandler(enabled = currentScreen.value != Screen.CONTROL) {
            currentScreen.value = Screen.CONTROL
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawerContent(
                    currentScreen = currentScreen.value,
                    settings      = settings,
                    onNavigate    = { screen ->
                        currentScreen.value = screen
                        scope.launch { drawerState.close() }
                    }
                )
            },
            gesturesEnabled = currentScreen.value != Screen.EASTER_EGG_OCTOPUS
        ) {
            AnimatedContent(
                targetState = currentScreen.value,
                transitionSpec = {
                    if (targetState == Screen.CONTROL || targetState == Screen.ABOUT) {
                        (slideInHorizontally { -it / 3 } + fadeIn())
                            .togetherWith(slideOutHorizontally { it } + fadeOut())
                    } else {
                        (slideInHorizontally { it } + fadeIn())
                            .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut())
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
                        onSave             = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu         = { scope.launch { drawerState.open() } }
                    )
                    Screen.TERMINAL -> TerminalScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.DOCKER -> DockerScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.MONITORING -> MonitoringScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.PIHOLE_CONFIG -> PiHoleConfigScreen(
                        settings = settings,
                        onClose  = { currentScreen.value = Screen.PIHOLE },
                        onSaved  = { currentScreen.value = Screen.PIHOLE }
                    )
                    Screen.PIHOLE -> PiHoleScreen(
                        settings     = settings,
                        onClose      = { currentScreen.value = Screen.CONTROL },
                        onOpenConfig = { currentScreen.value = Screen.PIHOLE_CONFIG },
                        onOpenMenu   = { scope.launch { drawerState.open() } }
                    )
                    Screen.WIREGUARD -> WireGuardScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.NOTIFS -> NotificationSettingsScreen(
                        settings   = settings,
                        onBack     = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.PWM -> PwmSliderScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.GPIO_PLANNER -> GpioScheduleScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.SENSORS -> SensorDashboardScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.LOGS_VIEWER -> LogsViewerScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.FAIL2BAN -> Fail2BanScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.UFW -> UfwScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.FILE_MANAGER -> FileManagerScreen(
                        settings   = settings,
                        onClose    = { currentScreen.value = Screen.CONTROL },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                    Screen.ABOUT -> AboutScreen(
                        onLaunchEasterEgg = { currentScreen.value = Screen.EASTER_EGG_OCTOPUS },
                        onOpenMenu        = { scope.launch { drawerState.open() } }
                    )
                    Screen.EASTER_EGG_OCTOPUS -> OctopusEasterEggScreen(
                        onClose = { currentScreen.value = Screen.ABOUT }
                    )
                    Screen.CONTROL -> ControlScreen(
                        settings              = settings,
                        onOpenSettings        = { currentScreen.value = Screen.SETTINGS },
                        onOpenTerminal        = { currentScreen.value = Screen.TERMINAL },
                        onOpenDocker          = { currentScreen.value = Screen.DOCKER },
                        onOpenMonitoring      = { currentScreen.value = Screen.MONITORING },
                        onOpenPiHole          = { currentScreen.value = Screen.PIHOLE },
                        onOpenWireGuard       = { currentScreen.value = Screen.WIREGUARD },
                        onOpenPwmSlider       = { currentScreen.value = Screen.PWM },
                        onOpenGpioSchedule    = { currentScreen.value = Screen.GPIO_PLANNER },
                        onOpenSensorDashboard = { currentScreen.value = Screen.SENSORS },
                        onOpenMenu            = { scope.launch { drawerState.open() } }
                    )
                }
            }
        }
    }

    @Composable
    private fun AppDrawerContent(
        currentScreen: Screen,
        settings     : SettingsManager,
        onNavigate   : (Screen) -> Unit
    ) {
        val context = LocalContext.current
        val versionName = remember {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } ?: "1.4.2"
            } catch (_: Exception) { "1.4.2" }
        }

        ModalDrawerSheet(
            modifier = Modifier.width(320.dp),
            drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            drawerContainerColor = MaterialTheme.colorScheme.surface
        ) {
            // ── Header du tiroir ──────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), Color.Transparent)
                        )
                    )
                    .padding(top = 48.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White,
                        shadowElevation = 4.dp
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = stringResource(R.string.logo_content_description),
                            modifier = Modifier.padding(12.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Raspberry Pi",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Text(
                                text = "${settings.username}@${settings.host}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // ── Corps scrollable du tiroir ────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))

                // Dashboard
                DrawerNavItem(
                    item = DrawerItemData(stringResource(R.string.nav_dashboard), Icons.Default.GridView, MaterialTheme.colorScheme.primary, Screen.CONTROL),
                    selected = currentScreen == Screen.CONTROL,
                    onClick = { onNavigate(Screen.CONTROL) },
                    settings = settings
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Groupe GPIO
                DrawerSectionLabel(stringResource(R.string.section_gpio))
                val gpioItems = listOf(
                    DrawerItemData(stringResource(R.string.nav_pwm), Icons.Default.Tune, Color(0xFF7C4DFF), Screen.PWM),
                    DrawerItemData(stringResource(R.string.nav_gpio_planner), Icons.Default.Schedule, Color(0xFF00897B), Screen.GPIO_PLANNER),
                    DrawerItemData(stringResource(R.string.nav_sensors), Icons.Default.Sensors, Color(0xFF1565C0), Screen.SENSORS),
                )
                gpioItems.forEach { item ->
                    DrawerNavItem(item, selected = currentScreen == item.screen, onClick = { item.screen?.let { onNavigate(it) } }, settings = settings)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Groupe Services
                DrawerSectionLabel(stringResource(R.string.section_services))
                val serviceItems = listOf(
                    DrawerItemData(stringResource(R.string.nav_monitoring), Icons.Default.BarChart, Color(0xFF2196F3), Screen.MONITORING),
                    DrawerItemData(stringResource(R.string.nav_docker), Icons.Default.Apps, Color(0xFF0288D1), Screen.DOCKER),
                    DrawerItemData(stringResource(R.string.nav_file_manager), Icons.Default.Folder, Color(0xFFFFA000), Screen.FILE_MANAGER),
                    DrawerItemData(stringResource(R.string.nav_pihole), Icons.Default.Shield, Color(0xFFE53935), Screen.PIHOLE),
                    DrawerItemData(stringResource(R.string.nav_wireguard), Icons.Default.VpnLock, Color(0xFF43A047), Screen.WIREGUARD),
                    DrawerItemData(stringResource(R.string.nav_terminal), Icons.Default.Terminal, MaterialTheme.colorScheme.onSurface, Screen.TERMINAL),
                    DrawerItemData(stringResource(R.string.nav_logs), Icons.AutoMirrored.Filled.ListAlt, Color(0xFF009688), Screen.LOGS_VIEWER),
                )
                serviceItems.forEach { item ->
                    DrawerNavItem(item, selected = currentScreen == item.screen || (item.screen == Screen.PIHOLE && currentScreen == Screen.PIHOLE_CONFIG), onClick = { item.screen?.let { onNavigate(it) } }, settings = settings)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Groupe Security
                DrawerSectionLabel(stringResource(R.string.section_security))
                val securityItems = listOf(
                    DrawerItemData(stringResource(R.string.nav_fail2ban), Icons.Default.LockPerson, Color(0xFFD32F2F), Screen.FAIL2BAN),
                    DrawerItemData(stringResource(R.string.nav_ufw), Icons.Default.Security, Color(0xFF388E3C), Screen.UFW),
                )
                securityItems.forEach { item ->
                    DrawerNavItem(item, selected = currentScreen == item.screen, onClick = { item.screen?.let { onNavigate(it) } }, settings = settings)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Groupe Utilitaires
                DrawerSectionLabel(stringResource(R.string.section_config))
                val configItems = listOf(
                    DrawerItemData(stringResource(R.string.nav_notifs), Icons.Default.Notifications, Color(0xFFFF9800), Screen.NOTIFS),
                    DrawerItemData(stringResource(R.string.nav_settings), Icons.Default.Settings, MaterialTheme.colorScheme.onSurface, Screen.SETTINGS),
                    DrawerItemData(stringResource(R.string.nav_about), Icons.Default.Info, MaterialTheme.colorScheme.onSurface, Screen.ABOUT),
                )
                configItems.forEach { item ->
                    DrawerNavItem(item, selected = currentScreen == item.screen, onClick = { item.screen?.let { onNavigate(it) } }, settings = settings)
                }

                Spacer(Modifier.height(16.dp))
            }

            // ── Footer du tiroir ──────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    Text(
                        text = stringResource(R.string.drawer_footer, settings.port, versionName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

    @OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    fun AboutScreen(onLaunchEasterEgg: () -> Unit, onOpenMenu: () -> Unit) {
        val context = LocalContext.current
        var clickCount by remember { mutableIntStateOf(0) }
        val uriHandler = LocalUriHandler.current
        val versionName = remember {
            try {
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }) ?: "1.4.2"
            } catch (_: Exception) {
                "1.4.2"
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_about)) },
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
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "Logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                Text(
                    text = "Raspberry Controller",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(R.string.version_label, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            clickCount++
                            if (clickCount >= 5) {
                                onLaunchEasterEgg()
                                clickCount = 0
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null,
                                modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(stringResource(R.string.about_developer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("RillMaster", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.clickable {
                                uriHandler.openUri("https://github.com/RillMaster/RaspberryController")
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null,
                                modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(stringResource(R.string.about_github), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("RillMaster/RaspberryController",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Easter Egg : Pieuvre Draggable
    // ══════════════════════════════════════════════════════════════════════════
    @Composable
    fun OctopusEasterEggScreen(onClose: () -> Unit) {
        var offset by remember { mutableStateOf(Offset(500f, 1000f)) }
        // La traînée contient maintenant des segments qui se suivent
        val trail = remember { mutableStateListOf<Offset>() }

        val infiniteTransition = rememberInfiniteTransition(label = "tentacle_anim")
        val time by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * kotlin.math.PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "time"
        )

        // Physique des tentacules : chaque point suit le précédent avec un décalage vers le bas
        LaunchedEffect(Unit) {
            if (trail.isEmpty()) {
                repeat(20) { trail.add(offset) }
            }
            while (true) {
                withContext(Dispatchers.Default) {
                    trail[0] = offset
                    for (i in 1 until trail.size) {
                        val targetX = trail[i - 1].x
                        val targetY = trail[i - 1].y + 25f // Distance entre segments
                        
                        // Effet élastique/amortissement
                        val newX = trail[i].x + (targetX - trail[i].x) * 0.15f
                        val newY = trail[i].y + (targetY - trail[i].y) * 0.15f
                        
                        trail[i] = Offset(newX, newY)
                    }
                }
                delay(16) // ~60 FPS
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF004D40))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offset += dragAmount
                    }
                }
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.padding(16.dp).padding(top = 32.dp).align(Alignment.TopStart)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val tentacleCount = 5
                for (i in 0 until tentacleCount) {
                    val path = Path()
                    // Départ du tentacule sous le logo
                    path.moveTo(offset.x + (i - 2) * 15f, offset.y + 30f)
                    
                    for (j in trail.indices) {
                        // Le wave varie selon la profondeur j pour un mouvement serpentin
                        val wave = kotlin.math.sin(j * 0.4f + i + time) * (10f + j * 0.5f)
                        path.lineTo(
                            trail[j].x + (i - 2) * 10f + wave,
                            trail[j].y + 30f
                        )
                    }
                    drawPath(
                        path = path,
                        color = Color.Black.copy(alpha = 0.5f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 12f - (i % 3), // Légère variation d'épaisseur
                            cap = StrokeCap.Round
                        )
                    )
                }
            }

            val density = LocalDensity.current.density
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (offset.x - 40 * density).toInt(),
                            (offset.y - 40 * density).toInt()
                        )
                    }
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFB2DFDB)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier.size(60.dp)
                )
            }
            
            Text(
                "🐙 Raspberry Octopus",
                modifier = Modifier.align(Alignment.BottomCenter).padding(48.dp),
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }

    // ── Titre de section ──────────────────────────────────────────────────────
    @Composable
    fun SectionTitle(text: String) {
        Text(
            text     = text,
            style    = MaterialTheme.typography.titleMedium,
            color    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
    }

    private fun tempColor(celsius: Double): Color = when {
        celsius >= 75.0 -> Color(0xFFEF5350)
        celsius >= 60.0 -> Color(0xFFFF9800)
        celsius >= 45.0 -> Color(0xFFFFEB3B)
        else            -> Color(0xFF66BB6A)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Barre de statut système (Dashboard moderne)
    // ══════════════════════════════════════════════════════════════════════════
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
                            fontWeight = FontWeight.Bold
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
                        Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (loading) stringResource(R.string.status_syncing) else stringResource(R.string.status_lost_connection),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
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
    private fun StatBlock(icon: ImageVector, value: String, label: String, color: Color) {
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

    // ══════════════════════════════════════════════════════════════════════════
    //  Écran Paramètres
    // ══════════════════════════════════════════════════════════════════════════
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen(
        settings          : SettingsManager,
        activity          : FragmentActivity,
        onThemeChanged    : (String) -> Unit,
        onBiometricEnabled: () -> Unit,
        onSave            : () -> Unit,
        onOpenMenu         : () -> Unit
    ) {
        var host     by remember { mutableStateOf(settings.host) }
        var port     by remember { mutableIntStateOf(settings.port) }
        var username by remember { mutableStateOf(settings.username) }
        var password by remember { mutableStateOf(settings.password) }

        var biometricEnabled by remember { mutableStateOf(settings.biometricEnabled) }
        val biometricAvailable = remember {
            BiometricManager.from(activity)
                .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
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
        var shortcuts       by remember { mutableStateOf(settings.shortcuts) }
        val showAddDialog   = remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_settings)) },
                    navigationIcon = {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
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
                    visualTransformation = PasswordVisualTransformation(),
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
                        settings.shortcuts = updated
                    },
                    modifier            = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) { index, shortcut, isDragging ->
                    key(shortcut.first) {
                        ReorderableItem {
                            val elevation by animateDpAsState(
                                targetValue = if (isDragging) 8.dp else 0.dp,
                                label       = "shortcut_elevation"
                            )
                            Card(
                                modifier  = Modifier.fillMaxWidth(),
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(shortcut.first,
                                            style      = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold)
                                        Text(shortcut.second,
                                            style      = MaterialTheme.typography.bodySmall,
                                            color      = MaterialTheme.colorScheme.primary,
                                            fontFamily = FontFamily.Monospace)
                                    }
                                    IconButton(onClick = {
                                        val updated        = shortcuts.toMutableList().apply { removeAt(index) }
                                        shortcuts          = updated
                                        settings.shortcuts = updated
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
                initialLabel   = "",
                initialCommand = "",
                title          = stringResource(R.string.action_add),
                onConfirm      = { label, cmd ->
                    val updated        = shortcuts + Pair(label, cmd)
                    shortcuts          = updated
                    settings.shortcuts = updated
                    showAddDialog.value = false
                },
                onDismiss = { showAddDialog.value = false }
            )
        }
    }

    @Composable
    fun ShortcutDialog(
        initialLabel  : String,
        initialCommand: String,
        title         : String,
        onConfirm     : (String, String) -> Unit,
        onDismiss     : () -> Unit
    ) {
        var label   by remember { mutableStateOf(initialLabel) }
        var command by remember { mutableStateOf(initialCommand) }

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
                        value         = command,
                        onValueChange = { command = it },
                        label         = { Text(stringResource(R.string.shortcut_dialog_command)) },
                        placeholder   = { Text(stringResource(R.string.shortcut_example_command)) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        textStyle     = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick  = { if (label.isNotEmpty() && command.isNotEmpty()) onConfirm(label, command) },
                    enabled  = label.isNotEmpty() && command.isNotEmpty()
                ) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = {
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Écran principal de contrôle
    // ══════════════════════════════════════════════════════════════════════════
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ControlScreen(
        settings              : SettingsManager,
        onOpenSettings        : () -> Unit,
        onOpenTerminal        : () -> Unit,
        onOpenDocker          : () -> Unit,
        onOpenMonitoring      : () -> Unit,
        onOpenPiHole          : () -> Unit,
        onOpenWireGuard       : () -> Unit,
        onOpenPwmSlider       : () -> Unit,
        onOpenGpioSchedule    : () -> Unit,
        onOpenSensorDashboard : () -> Unit,
        onOpenMenu            : () -> Unit
    ) {
        var systemStats  by remember { mutableStateOf<SystemStats?>(null) }
        var statsLoading by remember { mutableStateOf(true) }
        var connectionAttempts by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                if (systemStats == null && connectionAttempts < 2) {
                    statsLoading = true
                }
                
                val result = fetchSystemStats(settings)
                
                if (result != null) {
                    systemStats = result
                    connectionAttempts = 0
                    statsLoading = false
                } else {
                    connectionAttempts++
                    if (connectionAttempts >= 2) {
                        systemStats = null
                    }
                    statsLoading = false
                }
                delay(settings.tempRefreshMs.toLong())
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Column {
                            Text(stringResource(R.string.nav_dashboard), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(settings.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                
                // ── Stats système ─────────────────────────────────────────
                SystemStatusBar(
                    settings = settings,
                    stats    = systemStats,
                    loading  = statsLoading
                )

                // ── Section GPIO — grille 3 tuiles ────────────────
                SectionHeader(stringResource(R.string.section_gpio), Icons.Default.Bolt)

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GpioTile(
                        icon     = Icons.Default.Tune,
                        label    = stringResource(R.string.nav_pwm),
                        color    = Color(0xFF7C4DFF),
                        onClick  = onOpenPwmSlider,
                        modifier = Modifier.weight(1f)
                    )
                    GpioTile(
                        icon     = Icons.Default.Schedule,
                        label    = stringResource(R.string.nav_gpio_planner),
                        color    = Color(0xFF00897B),
                        onClick  = onOpenGpioSchedule,
                        modifier = Modifier.weight(1f)
                    )
                    GpioTile(
                        icon     = Icons.Default.Sensors,
                        label    = stringResource(R.string.nav_sensors),
                        color    = Color(0xFF1565C0),
                        onClick  = onOpenSensorDashboard,
                        modifier = Modifier.weight(1f)
                    )
                }

                // ── Section services ─────────────────────────
                SectionHeader(stringResource(R.string.section_services), Icons.Default.Dns)

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ServiceTile(
                            icon     = Icons.Default.BarChart,
                            label    = stringResource(R.string.nav_monitoring),
                            color    = Color(0xFF2196F3),
                            onClick  = onOpenMonitoring,
                            modifier = Modifier.weight(1f)
                        )
                        ServiceTile(
                            icon     = Icons.Default.Apps,
                            label    = stringResource(R.string.nav_docker),
                            color    = Color(0xFF0288D1),
                            onClick  = onOpenDocker,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ServiceTile(
                            icon     = Icons.Default.Shield,
                            label    = stringResource(R.string.nav_pihole),
                            color    = Color(0xFFE53935),
                            onClick  = onOpenPiHole,
                            modifier = Modifier.weight(1f)
                        )
                        ServiceTile(
                            icon     = Icons.Default.VpnLock,
                            label    = stringResource(R.string.nav_wireguard),
                            color    = Color(0xFF43A047),
                            onClick  = onOpenWireGuard,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ── Accès rapide terminal ─────────────────────────────────
                Card(
                    onClick = onOpenTerminal,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.terminal_card_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String, icon: ImageVector) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun DrawerSectionLabel(text: String) {
        Text(
            text     = text.uppercase(),
            style    = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color    = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 2.dp),
            letterSpacing = 1.sp
        )
    }

    // ── Item de navigation du tiroir avec icône colorée ───────────────────────
    @Composable
    private fun DrawerNavItem(
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
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (isInstalled) item.color else item.color.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            label = {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp),
                    color = if (isInstalled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
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

    // ── Tuile GPIO compacte ───────────────────────────────────────────────────
    @Composable
    private fun GpioTile(
        icon    : ImageVector,
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
                        Icon(
                            imageVector        = icon,
                            contentDescription = label,
                            tint               = color,
                            modifier           = Modifier.size(24.dp)
                        )
                    }
                }
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color      = color.copy(alpha = 0.9f)
                )
            }
        }
    }

    // ── Tuile service ─────────────────────────────────────────────────────────
    @Composable
    private fun ServiceTile(
        icon    : ImageVector,
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
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = color.copy(alpha = 0.12f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector        = icon,
                            contentDescription = null,
                            tint               = color,
                            modifier           = Modifier.size(22.dp)
                        )
                    }
                }
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
