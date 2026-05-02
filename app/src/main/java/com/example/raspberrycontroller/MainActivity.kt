@file:Suppress("UNUSED_VALUE")

package com.example.raspberrycontroller

import android.Manifest
import android.content.pm.PackageManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager
import android.os.Bundle
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.raspberrycontroller.ui.theme.RaspberryControllerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.calvin.reorderable.ReorderableColumn
import java.net.URL

// ══════════════════════════════════════════════════════════════════════════════
//  Modèle de données pour les stats système
// ══════════════════════════════════════════════════════════════════════════════
data class SystemStats(
    val tempCelsius: Double,
    val cpuPercent : Int,
    val ramUsedMb  : Int,
    val ramTotalMb : Int
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
                    Text("Mise à jour", style = MaterialTheme.typography.titleLarge)
                    when (progress) {
                        -1 -> {
                            Icon(Icons.Default.Error, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                            Text("Erreur lors du téléchargement",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                                Text("Fermer")
                            }
                        }
                        100 -> {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = Color(0xFF66BB6A), modifier = Modifier.size(48.dp))
                            Text("Téléchargement terminé !", style = MaterialTheme.typography.bodyMedium)
                            Text("L'installation va démarrer…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            Text("Téléchargement en cours…", style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("$progress%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Text("Veuillez patienter…",
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
                val latestVersionName = jsonObject.optString("versionName", "Inconnue")
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
            append("Une nouvelle version est disponible : v$latestVersion")
            if (changelog.isNotEmpty()) append("\n\n📋 Nouveautés :\n$changelog")
            append("\n\nVoulez-vous l'installer ?")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Mise à jour disponible")
            .setMessage(message)
            .setPositiveButton("Mettre à jour") { _, _ ->
                downloadProgress.intValue = 0
                UpdateManager(this).downloadAndInstall(downloadUrl) { progress ->
                    downloadProgress.intValue = progress
                    if (progress == 100) {
                        lifecycleScope.launch { delay(3000); downloadProgress.intValue = -2 }
                    }
                }
            }
            .setNegativeButton("Plus tard", null)
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
                            contentDescription = "Logo",
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
                        "Authentification requise pour accéder à l'application.",
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
                        Text("Réessayer")
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MainApp — gestion de la navigation entre écrans
    // ══════════════════════════════════════════════════════════════════════════
    private enum class Screen {
        CONTROL, SETTINGS, TERMINAL, DOCKER, MONITORING,
        PIHOLE, PIHOLE_CONFIG, WIREGUARD, NOTIFS, PWM, GPIO_PLANNER, SENSORS, ABOUT
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

        AnimatedContent(
            targetState = currentScreen.value,
            transitionSpec = {
                if (targetState == Screen.CONTROL) {
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
                    onSave             = { currentScreen.value = Screen.CONTROL }
                )
                Screen.TERMINAL -> TerminalScreen(
                    settings = settings,
                    onClose  = { currentScreen.value = Screen.CONTROL }
                )
                Screen.DOCKER -> DockerScreen(
                    settings = settings,
                    onClose  = { currentScreen.value = Screen.CONTROL }
                )
                Screen.MONITORING -> MonitoringScreen(
                    settings = settings,
                    onClose  = { currentScreen.value = Screen.CONTROL }
                )
                Screen.PIHOLE_CONFIG -> PiHoleConfigScreen(
                    settings = settings,
                    onClose  = { currentScreen.value = Screen.CONTROL },
                    onSaved  = { currentScreen.value = Screen.CONTROL }
                )
                Screen.PIHOLE -> PiHoleScreen(
                    settings     = settings,
                    onClose      = { currentScreen.value = Screen.CONTROL },
                    onOpenConfig = { currentScreen.value = Screen.PIHOLE_CONFIG }
                )
                Screen.WIREGUARD -> WireGuardScreen(
                    settings = settings,
                    onClose  = { currentScreen.value = Screen.CONTROL }
                )
                Screen.NOTIFS -> NotificationSettingsScreen(
                    settings = settings,
                    onBack   = { currentScreen.value = Screen.CONTROL }
                )
                Screen.PWM -> PwmSliderScreen(
                    settings = settings,
                    onClose  = { currentScreen.value = Screen.CONTROL }
                )
                Screen.GPIO_PLANNER -> GpioScheduleScreen(
                    settings = settings,
                    onClose  = { currentScreen.value = Screen.CONTROL }
                )
                Screen.SENSORS -> SensorDashboardScreen(
                    settings = settings,
                    onClose  = { currentScreen.value = Screen.CONTROL }
                )
                Screen.ABOUT -> AboutScreen(
                    onBack = { currentScreen.value = Screen.CONTROL }
                )
                Screen.CONTROL -> ControlScreen(
                    settings              = settings,
                    onOpenSettings        = { currentScreen.value = Screen.SETTINGS },
                    onOpenTerminal        = { currentScreen.value = Screen.TERMINAL },
                    onOpenDocker          = { currentScreen.value = Screen.DOCKER },
                    onOpenMonitoring      = { currentScreen.value = Screen.MONITORING },
                    onOpenPiHole          = { currentScreen.value = Screen.PIHOLE },
                    onOpenWireGuard       = { currentScreen.value = Screen.WIREGUARD },
                    onOpenNotifSettings   = { currentScreen.value = Screen.NOTIFS },
                    onOpenPwmSlider       = { currentScreen.value = Screen.PWM },
                    onOpenGpioSchedule    = { currentScreen.value = Screen.GPIO_PLANNER },
                    onOpenSensorDashboard = { currentScreen.value = Screen.SENSORS },
                    onOpenAbout           = { currentScreen.value = Screen.ABOUT }
                )
            }
        }
    }

    // ── Écran À propos ────────────────────────────────────────────────────────
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AboutScreen(onBack: () -> Unit) {
        val context = LocalContext.current
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        val versionName = remember {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }
            } catch (_: Exception) {
                "1.0.0"
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("À propos") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
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
                    text = "Version $versionName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                Text("Développeur", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("Dépôt GitHub", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    text = "© 2026 RillMaster",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
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
    //  Barre de statut système (temp + CPU + RAM)
    // ══════════════════════════════════════════════════════════════════════════
    @Composable
    fun SystemStatusBar(settings: SettingsManager, stats: SystemStats?, loading: Boolean) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(20.dp),
            colors   = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                // En-tête connexion
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .let { if (stats != null) it else it }
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (stats != null) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                            modifier = Modifier.fillMaxSize()
                        ) {}
                    }
                    Text(
                        text       = "${settings.username}@${settings.host}:${settings.port}",
                        style      = MaterialTheme.typography.labelSmall,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                when {
                    loading -> Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "Connexion au Raspberry Pi…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    stats == null -> Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.WifiOff,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Impossible de joindre le Raspberry Pi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    else -> {
                        // Trois métriques côte à côte
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatBlock(
                                icon  = Icons.Default.Thermostat,
                                value = "%.1f°C".format(stats.tempCelsius),
                                label = "Temp CPU",
                                color = tempColor(stats.tempCelsius)
                            )
                            VerticalDivider(
                                modifier = Modifier.height(52.dp),
                                color    = MaterialTheme.colorScheme.outlineVariant
                            )
                            val cpuColor = when {
                                stats.cpuPercent >= 80 -> Color(0xFFEF5350)
                                stats.cpuPercent >= 50 -> Color(0xFFFF9800)
                                else                   -> Color(0xFF66BB6A)
                            }
                            StatBlock(
                                icon  = Icons.Default.Memory,
                                value = "${stats.cpuPercent}%",
                                label = "CPU",
                                color = cpuColor
                            )
                            VerticalDivider(
                                modifier = Modifier.height(52.dp),
                                color    = MaterialTheme.colorScheme.outlineVariant
                            )
                            val ramPercent = if (stats.ramTotalMb > 0)
                                stats.ramUsedMb * 100 / stats.ramTotalMb else 0
                            val ramColor = when {
                                ramPercent >= 85 -> Color(0xFFEF5350)
                                ramPercent >= 65 -> Color(0xFFFF9800)
                                else             -> Color(0xFF66BB6A)
                            }
                            StatBlock(
                                icon  = Icons.Default.Storage,
                                value = "${stats.ramUsedMb} Mo",
                                label = "RAM · $ramPercent%",
                                color = ramColor
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Barres de progression CPU + RAM
                        val cpuColor = when {
                            stats.cpuPercent >= 80 -> Color(0xFFEF5350)
                            stats.cpuPercent >= 50 -> Color(0xFFFF9800)
                            else                   -> Color(0xFF66BB6A)
                        }
                        val ramPct = if (stats.ramTotalMb > 0)
                            stats.ramUsedMb.toFloat() / stats.ramTotalMb else 0f
                        val ramBarColor = when {
                            (ramPct * 100).toInt() >= 85 -> Color(0xFFEF5350)
                            (ramPct * 100).toInt() >= 65 -> Color(0xFFFF9800)
                            else                         -> Color(0xFF66BB6A)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "CPU",
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(32.dp)
                                )
                                LinearProgressIndicator(
                                    progress   = { (stats.cpuPercent / 100f).coerceIn(0f, 1f) },
                                    modifier   = Modifier.weight(1f).height(5.dp),
                                    color      = cpuColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "RAM",
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(32.dp)
                                )
                                LinearProgressIndicator(
                                    progress   = { ramPct.coerceIn(0f, 1f) },
                                    modifier   = Modifier.weight(1f).height(5.dp),
                                    color      = ramBarColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Actualisation toutes les ${settings.tempRefreshMs / 1000} s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
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
        onSave            : () -> Unit
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

        val themeOptions    = listOf("system" to "Système", "light" to "Clair", "dark" to "Sombre")
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
                    title = { Text("Paramètres") },
                    navigationIcon = {
                        if (settings.isConfigured()) {
                            IconButton(onClick = onSave) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showAddDialog.value = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Ajouter un raccourci")
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
                SectionTitle("Connexion SSH")
                OutlinedTextField(value = host, onValueChange = { host = it },
                    label = { Text("Adresse IP") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port.toString(),
                    onValueChange = { port = it.toIntOrNull() ?: 22 },
                    label = { Text("Port SSH") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text("Nom d'utilisateur") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())

                SectionTitle("Timeout SSH")
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

                SectionTitle("Thème")
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

                SectionTitle("Sécurité")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Biométrie", modifier = Modifier.weight(1f))
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

                SectionTitle("Rafraîchissement température")
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

                SectionTitle("Raccourcis terminal")
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
                                        contentDescription = "Déplacer",
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
                                        Icon(Icons.Default.Delete, contentDescription = "Supprimer",
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
                ) { Text("Enregistrer la configuration") }
            }
        }

        if (showAddDialog.value) {
            ShortcutDialog(
                initialLabel   = "",
                initialCommand = "",
                title          = "Ajouter",
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
                        label         = { Text("Libellé du bouton") },
                        placeholder   = { Text("ex : reboot") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value         = command,
                        onValueChange = { command = it },
                        label         = { Text("Commande SSH") },
                        placeholder   = { Text("ex : sudo reboot") },
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
                ) { Text("Ajouter") }
            },
            dismissButton = {
                OutlinedButton(onClick = onDismiss) { Text("Annuler") }
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
        onOpenNotifSettings   : () -> Unit,
        onOpenPwmSlider       : () -> Unit,
        onOpenGpioSchedule    : () -> Unit,
        onOpenSensorDashboard : () -> Unit,
        onOpenAbout           : () -> Unit
    ) {
        var systemStats  by remember { mutableStateOf<SystemStats?>(null) }
        var statsLoading by remember { mutableStateOf(true) }

        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope       = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            while (true) {
                statsLoading = systemStats == null
                systemStats  = fetchSystemStats(settings)
                statsLoading = false
                delay(settings.tempRefreshMs.toLong())
            }
        }

        // ── Définition des groupes du tiroir ──────────────────────────────────
        data class DrawerItem(
            val label  : String,
            val icon   : ImageVector,
            val color  : Color,
            val onClick: () -> Unit
        )

        val gpioItems = listOf(
            DrawerItem("Contrôle PWM",      Icons.Default.Tune,     Color(0xFF7C4DFF), onOpenPwmSlider),
            DrawerItem("Planificateur GPIO", Icons.Default.Schedule, Color(0xFF00897B), onOpenGpioSchedule),
            DrawerItem("Capteurs",           Icons.Default.Sensors,  Color(0xFF1565C0), onOpenSensorDashboard),
        )
        val serviceItems = listOf(
            DrawerItem("Monitoring",    Icons.Default.BarChart,      Color(0xFF2196F3), onOpenMonitoring),
            DrawerItem("Docker",        Icons.Default.Apps,          Color(0xFF0288D1), onOpenDocker),
            DrawerItem("Pi-hole",       Icons.Default.Shield,        Color(0xFFE53935), onOpenPiHole),
            DrawerItem("WireGuard",     Icons.Default.VpnLock,       Color(0xFF43A047), onOpenWireGuard),
            DrawerItem("Terminal",      Icons.Default.Terminal,      MaterialTheme.colorScheme.onSurface, onOpenTerminal),
        )
        val utilItems = listOf(
            DrawerItem("Notifications", Icons.Default.Notifications, MaterialTheme.colorScheme.onSurface, onOpenNotifSettings),
            DrawerItem("Paramètres",    Icons.Default.Settings,      MaterialTheme.colorScheme.onSurface, onOpenSettings),
            DrawerItem("À propos",      Icons.Default.Info,          MaterialTheme.colorScheme.onSurface, onOpenAbout),
        )

        ModalNavigationDrawer(
            drawerState   = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(300.dp)
                ) {
                    // ── Header du tiroir ──────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 20.dp, vertical = 32.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Logo en image
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.logo),
                                    contentDescription = "App Logo",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text       = "Raspberry Controller",
                                    style      = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color      = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Indicateur de connexion
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (systemStats != null) Color(0xFF4CAF50)
                                                else Color(0xFFBDBDBD)
                                            )
                                    )
                                    Text(
                                        text       = "${settings.username}@${settings.host}",
                                        style      = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color      = MaterialTheme.colorScheme.onSurfaceVariant
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

                        // Groupe GPIO
                        DrawerSectionLabel("GPIO")
                        gpioItems.forEach { item ->
                            DrawerNavItem(
                                item    = item,
                                onClick = { scope.launch { drawerState.close() }; item.onClick() }
                            )
                        }

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // Groupe Services
                        DrawerSectionLabel("Services")
                        serviceItems.forEach { item ->
                            DrawerNavItem(
                                item    = item,
                                onClick = { scope.launch { drawerState.close() }; item.onClick() }
                            )
                        }

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // Groupe Utilitaires
                        DrawerSectionLabel("Général")
                        utilItems.forEach { item ->
                            DrawerNavItem(
                                item    = item,
                                onClick = { scope.launch { drawerState.close() }; item.onClick() }
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                    }

                    // ── Footer du tiroir ──────────────────────────────────────
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text  = "Port SSH : ${settings.port}  ·  Timeout : ${settings.sshTimeoutMs / 1000} s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Raspberry Controller") },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Ouvrir le menu")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── Stats système ─────────────────────────────────────────
                    SystemStatusBar(
                        settings = settings,
                        stats    = systemStats,
                        loading  = statsLoading
                    )

                    // ── Section GPIO — grille 3 tuiles ────────────────
                    Text(
                        text  = "GPIO",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GpioTile(
                            icon     = Icons.Default.Tune,
                            label    = "PWM",
                            color    = Color(0xFF7C4DFF),
                            onClick  = onOpenPwmSlider,
                            modifier = Modifier.weight(1f)
                        )
                        GpioTile(
                            icon     = Icons.Default.Schedule,
                            label    = "Planning",
                            color    = Color(0xFF00897B),
                            onClick  = onOpenGpioSchedule,
                            modifier = Modifier.weight(1f)
                        )
                        GpioTile(
                            icon     = Icons.Default.Sensors,
                            label    = "Capteurs",
                            color    = Color(0xFF1565C0),
                            onClick  = onOpenSensorDashboard,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // ── Section services — grille 2×2 ─────────────────────────
                    Text(
                        text  = "Services",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ServiceTile(
                            icon     = Icons.Default.BarChart,
                            label    = "Monitoring",
                            color    = Color(0xFF2196F3),
                            onClick  = onOpenMonitoring,
                            modifier = Modifier.weight(1f)
                        )
                        ServiceTile(
                            icon     = Icons.Default.Apps,
                            label    = "Docker",
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
                            label    = "Pi-hole",
                            color    = Color(0xFFE53935),
                            onClick  = onOpenPiHole,
                            modifier = Modifier.weight(1f)
                        )
                        ServiceTile(
                            icon     = Icons.Default.VpnLock,
                            label    = "WireGuard",
                            color    = Color(0xFF43A047),
                            onClick  = onOpenWireGuard,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // ── Accès rapide terminal ─────────────────────────────────
                    OutlinedButton(
                        onClick  = onOpenTerminal,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Ouvrir le terminal SSH")
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // ── Label de section dans le tiroir ──────────────────────────────────────
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
        item   : Any,
        onClick: () -> Unit
    ) {
        val clazz  = item::class
        val labelV  = clazz.members.firstOrNull { it.name == "label"  }?.call(item) as? String      ?: ""
        val iconV   = clazz.members.firstOrNull { it.name == "icon"   }?.call(item) as? ImageVector
        val colorV  = clazz.members.firstOrNull { it.name == "color"  }?.call(item) as? Color       ?: MaterialTheme.colorScheme.onSurface

        NavigationDrawerItem(
            icon = {
                if (iconV != null) {
                    Surface(
                        shape    = RoundedCornerShape(10.dp),
                        color    = colorV.copy(alpha = 0.12f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector        = iconV,
                                contentDescription = labelV,
                                tint               = colorV,
                                modifier           = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            label    = {
                Text(
                    text       = labelV,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier   = Modifier.padding(start = 8.dp)
                )
            },
            selected = false,
            onClick  = onClick,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent
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
        Card(
            onClick  = onClick,
            modifier = modifier,
            shape    = RoundedCornerShape(16.dp),
            colors   = CardDefaults.cardColors(
                containerColor = color.copy(alpha = 0.12f)
            )
        ) {
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = label,
                    tint               = color,
                    modifier           = Modifier.size(26.dp)
                )
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = color
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
        Card(
            onClick  = onClick,
            modifier = modifier,
            shape    = RoundedCornerShape(20.dp),
            colors   = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = color.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector        = icon,
                            contentDescription = null,
                            tint               = color,
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.bodyMedium,
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
}