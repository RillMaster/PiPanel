@file:Suppress("UNUSED_VALUE", "SpellCheckingInspection")

package com.rillmaster.pipanel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.rillmaster.pipanel.ui.theme.PiPanelTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.json.JSONObject
import sh.calvin.reorderable.ReorderableColumn
import java.net.URL
import android.view.animation.AnticipateInterpolator
import android.animation.ObjectAnimator
import android.view.View
import androidx.core.animation.doOnEnd

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
            if (raw.startsWith("[err]")) return@withContext null
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
    EASTER_EGG_OCTOPUS, LOGS_VIEWER, FAIL2BAN, UFW, FILE_MANAGER, SERVICES, PROFILES, NETWORK_SCANNER, CRON_SCHEDULER, CHARTS
}

data class DrawerItemData(
    val label: String,
    val icon: Any,
    val color: Color,
    val screen: Screen? = null
)

data class DashboardTileData(
    val icon: Any,
    val label: String,
    val color: Color,
    val onClick: () -> Unit
)

// ══════════════════════════════════════════════════════════════════════════════
//  MainActivity
// ══════════════════════════════════════════════════════════════════════════════
class MainActivity : FragmentActivity() {

    private val versionUrl =
        "https://raw.githubusercontent.com/RillMaster/PiPanel/main/version.json"
    private val changelogUrl =
        "https://raw.githubusercontent.com/RillMaster/PiPanel/main/changelog.md"

    private var downloadProgress = mutableIntStateOf(-2)

    companion object {
        private const val REQUEST_CODE_NOTIF = 1001

        /** Extra d'intent pour ouvrir l'app directement sur un écran (ex : widgets). */
        const val EXTRA_NAVIGATE_TO = "navigate_to"
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        // Maintenir le splash affiché le temps que l'animation de dessin se termine (~4000ms total)
        val splashStartTime = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - splashStartTime
            elapsed < 4000L
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Personnalisation de l'animation de sortie du splash screen
        splashScreen.setOnExitAnimationListener { splashScreenProvider ->
            val splashScreenView = splashScreenProvider.view
            val iconView = splashScreenProvider.iconView

            // On s'assure que le pivot de l'animation est bien au centre de l'icône
            iconView.pivotX = iconView.width / 2f
            iconView.pivotY = iconView.height / 2f

            // Animation du logo : zoom arrière avec AnticipateInterpolator + fondu
            val scaleX = ObjectAnimator.ofFloat(iconView, View.SCALE_X, 1f, 0f)
            val scaleY = ObjectAnimator.ofFloat(iconView, View.SCALE_Y, 1f, 0f)
            val alpha = ObjectAnimator.ofFloat(splashScreenView, View.ALPHA, 1f, 0f)

            // Utilisation de AnticipateInterpolator pour un effet de "pop" fluide
            scaleX.interpolator = AnticipateInterpolator()
            scaleY.interpolator = AnticipateInterpolator()

            // Durées optimisées (500ms)
            scaleX.duration = 500L
            scaleY.duration = 500L
            alpha.duration = 500L

            alpha.doOnEnd { splashScreenProvider.remove() }

            scaleX.start()
            scaleY.start()
            alpha.start()
        }

        NotificationHelper.createChannels(this)
        requestNotificationPermissionIfNeeded()
        val settings = SettingsManager(this)

        if (settings.backgroundActivityEnabled) {
            if (settings.notificationsEnabled) {
                MonitoringWorker.schedule(this)
            }
            // Widget stats
            UpdateStatsWorker.schedulePeriodic(this)
            WidgetUpdateService.start(this)
        } else {
            WorkManager.getInstance(this).cancelAllWork()
            WidgetUpdateService.stop(this)
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val context   = LocalContext.current
            val settingsR = remember { SettingsManager(context) }
            var themePref by remember { mutableStateOf(settingsR.theme) }

            val darkTheme = when (themePref) {
                "light" -> false
                "dark"  -> true
                else    -> isSystemInDarkTheme()
            }

            PiPanelTheme(darkTheme = darkTheme) {
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
                    windowSizeClass = windowSizeClass,
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
                        lifecycleScope.launch { delay(3.seconds); downloadProgress.intValue = -2 }
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
        windowSizeClass: WindowSizeClass,
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
            val isAuthenticated = rememberSaveable { mutableStateOf(!settings.biometricEnabled) }
            val authError       = remember { mutableStateOf<String?>(null) }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START && settings.biometricEnabled) {
                        isAuthenticated.value = false
                        authError.value = null
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

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
                    windowSizeClass     = windowSizeClass,
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
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
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
        windowSizeClass   : WindowSizeClass,
        onThemeChanged    : (String) -> Unit,
        onBiometricEnabled: () -> Unit
    ) {
        val currentScreen = remember {
            // Navigation profonde depuis un widget (ex : widget Docker)
            val requested = activity.intent?.getStringExtra(EXTRA_NAVIGATE_TO)
                ?.let { name -> runCatching { Screen.valueOf(name) }.getOrNull() }
            mutableStateOf(requested ?: if (settings.isConfigured()) Screen.CONTROL else Screen.SETTINGS)
        }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded &&
                windowSizeClass.heightSizeClass != WindowHeightSizeClass.Compact

        val drawerContent = @Composable {
            val isCompact = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
            AppDrawerContent(
                currentScreen = currentScreen.value,
                settings      = settings,
                isCompactHeight = isCompact,
                onNavigate    = { screen ->
                    currentScreen.value = screen
                    scope.launch { drawerState.close() }
                }
            )
        }

        val drawerWidth = 300.dp

        // Gestion du bouton retour système
        BackHandler(enabled = currentScreen.value != Screen.CONTROL || drawerState.isOpen) {
            if (drawerState.isOpen) {
                scope.launch { drawerState.close() }
            } else {
                currentScreen.value = Screen.CONTROL
            }
        }

        if (isExpanded) {
            PermanentNavigationDrawer(
                drawerContent = {
                    PermanentDrawerSheet(
                        modifier = Modifier.width(drawerWidth),
                        drawerShape = RoundedCornerShape(0.dp),
                        drawerContainerColor = MaterialTheme.colorScheme.surface
                    ) {
                        drawerContent()
                    }
                }
            ) {
                MainLayoutContent(
                    currentScreen = currentScreen.value,
                    settings = settings,
                    activity = activity,
                    drawerState = drawerState,
                    windowSizeClass = windowSizeClass,
                    onNavigate = { currentScreen.value = it },
                    onThemeChanged = onThemeChanged,
                    onBiometricEnabled = onBiometricEnabled,
                    isExpanded = true
                )
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(320.dp),
                        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                        drawerContainerColor = MaterialTheme.colorScheme.surface
                    ) {
                        drawerContent()
                    }
                },
                gesturesEnabled = currentScreen.value != Screen.EASTER_EGG_OCTOPUS
            ) {
                MainLayoutContent(
                    currentScreen = currentScreen.value,
                    settings = settings,
                    activity = activity,
                    drawerState = drawerState,
                    windowSizeClass = windowSizeClass,
                    onNavigate = { currentScreen.value = it },
                    onThemeChanged = onThemeChanged,
                    onBiometricEnabled = onBiometricEnabled,
                    isExpanded = false
                )
            }
        }
    }

    @Composable
    private fun MainLayoutContent(
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
                    onSave             = { onNavigate(Screen.CONTROL) },
                    onOpenMenu         = { if (!isExpanded) scope.launch { drawerState.open() } },
                    isExpanded         = isExpanded
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
                    showNavigationIcon = !isExpanded
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
            }
        }
    }

    @Composable
    private fun AppDrawerContent(
        currentScreen: Screen,
        settings     : SettingsManager,
        isCompactHeight: Boolean = false,
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
                } ?: "1.5.2"
            } catch (_: Exception) { "1.5.2" }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header du tiroir ──────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), Color.Transparent)
                        )
                    )
                    .padding(
                        top = if (isCompactHeight) 16.dp else 48.dp,
                        bottom = if (isCompactHeight) 12.dp else 24.dp,
                        start = 24.dp, end = 24.dp
                    )
            ) {
                if (isCompactHeight) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White,
                            shadowElevation = 2.dp
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.logo),
                                contentDescription = null,
                                modifier = Modifier.padding(6.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = settings.host,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                } else {
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
                                text = stringResource(R.string.app_name),
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
                                    text = "${settings.getCurrentProfile()?.name ?: settings.username}@${settings.host}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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

                DrawerNavItem(
                    item = DrawerItemData(stringResource(R.string.nav_profiles), Icons.Default.Devices, MaterialTheme.colorScheme.secondary, Screen.PROFILES),
                    selected = currentScreen == Screen.PROFILES,
                    onClick = { onNavigate(Screen.PROFILES) },
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
                    DrawerItemData(stringResource(R.string.nav_charts), Icons.Default.ShowChart, Color(0xFF0097A7), Screen.CHARTS),
                    DrawerItemData(stringResource(R.string.nav_docker), R.drawable.docker, Color(0xFF0288D1), Screen.DOCKER),
                    DrawerItemData(stringResource(R.string.nav_services), Icons.Default.SettingsSuggest, Color(0xFF607D8B), Screen.SERVICES),
                    DrawerItemData(stringResource(R.string.nav_file_manager), Icons.Default.Folder, Color(0xFFFFA000), Screen.FILE_MANAGER),
                    DrawerItemData(stringResource(R.string.nav_pihole), R.drawable.ic_widget_pihole, Color(0xFFE53935), Screen.PIHOLE),
                    DrawerItemData(stringResource(R.string.nav_wireguard), R.drawable.wireguard, Color(0xFF43A047), Screen.WIREGUARD),
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
                    DrawerItemData(stringResource(R.string.nav_net_scan), Icons.Default.NetworkCheck, Color(0xFF673AB7), Screen.NETWORK_SCANNER),
                    DrawerItemData(stringResource(R.string.nav_cron), Icons.Default.Schedule, Color(0xFF8E24AA), Screen.CRON_SCHEDULER),
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AboutScreen(onLaunchEasterEgg: () -> Unit, onOpenMenu: () -> Unit, isExpanded: Boolean = false) {
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
                    if (!isExpanded) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
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
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = stringResource(R.string.version_label, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                            uriHandler.openUri("https://github.com/RillMaster/PiPanel")
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null,
                            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.about_github), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("RillMaster/PiPanel",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
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
            delay(16.milliseconds) // ~60 FPS
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
                    style = Stroke(
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

// ══════════════════════════════════════════════════════════════════════════
//  Écran principal de contrôle
// ══════════════════════════════════════════════════════════════════════════
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
    var systemStats  by remember { mutableStateOf<SystemStats?>(null) }
    var statsLoading by remember { mutableStateOf(true) }
    var connectionAttempts by remember { mutableIntStateOf(0) }

    // ── Personnalisation du dashboard (ordre + visibilité, DataStore) ──
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var editMode by remember { mutableStateOf(false) }
    val dashConfig by DashboardPrefs.flow(context)
        .collectAsState(initial = DashboardPrefs.DashboardConfig())

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
            delay(settings.tempRefreshMs.milliseconds)
        }
    }

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
                    // Mode édition : réordonner / masquer les sections
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
            // ── Mode édition : liste réordonnable avec poignées + switches ──
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

            // Sections visibles, dans l'ordre persisté
            dashConfig.order.filter { it !in dashConfig.hidden }.forEach { section ->
                when (section) {
                    DashboardSection.STATS -> {
                        // ── Stats système + coup d'œil ────────────────────
                        SystemStatusBar(
                            settings = settings,
                            stats    = systemStats,
                            loading  = statsLoading
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

// ── Sections du dashboard (extraites pour la réorganisation) ─────────────

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
                val tileModifier = if (isExpanded) Modifier.weight(1f) else Modifier.weight(1f)
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

// ── Mode édition : réorganisation + visibilité des sections ──────────────

@Composable
private fun DashboardEditList(
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

// ── Carte « Coup d'œil » : Pi-hole, Docker, redémarrage rapide ───────────

@Composable
private fun GlanceCard(settings: SettingsManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var piholeText   by remember { mutableStateOf<String?>(null) }
    var dockerText   by remember { mutableStateOf<String?>(null) }
    var showRebootDialog by remember { mutableStateOf(false) }
    val unavailable = stringResource(R.string.glance_unavailable)
    // Gabarits récupérés en composition pour éviter context.getString dans la coroutine
    val piholeTpl  = stringResource(R.string.glance_pihole_blocked_fmt)
    val dockerTpl  = stringResource(R.string.glance_docker_count_fmt)
    val piholeOff  = stringResource(R.string.status_inactive)

    LaunchedEffect(Unit) {
        // Pi-hole : nombre de domaines bloqués (non bloquant)
        launch {
            runCatching { fetchPiHoleStatus(settings, settings.piHolePassword) }
                .getOrNull()
                ?.let { stats ->
                    piholeText = if (stats.enabled)
                        String.format(piholeTpl, stats.domainsBlocked)
                    else piholeOff
                }
        }
        // Docker : conteneurs actifs / total (non bloquant)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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

// ── Tuile GPIO compacte ───────────────────────────────────────────────────
@Composable
private fun GpioTile(
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

// ── Tuile service ─────────────────────────────────────────────────────────
@Composable
private fun ServiceTile(
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
