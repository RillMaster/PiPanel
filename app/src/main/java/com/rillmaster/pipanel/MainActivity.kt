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
import com.rillmaster.pipanel.update.DownloadProgressDialog
import com.rillmaster.pipanel.ui.screens.*
import com.rillmaster.pipanel.ui.components.*
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
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.animation.doOnEnd
import com.rillmaster.pipanel.model.DashboardTileData
import com.rillmaster.pipanel.model.DrawerItemData
import com.rillmaster.pipanel.model.Screen
import com.rillmaster.pipanel.model.SystemStats
import com.rillmaster.pipanel.model.fetchSystemStats

// ══════════════════════════════════════════════════════════════════════════════
//  MainActivity
// ══════════════════════════════════════════════════════════════════════════════
class MainActivity : FragmentActivity() {

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
        val settings = PiPanelApp.instance.settingsManager

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
            val settingsR = PiPanelApp.instance.settingsManager
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
                    onAppReady = {
                        UpdateManager(this@MainActivity)
                            .checkForUpdates(this@MainActivity, lifecycleScope, downloadProgress)
                    }
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
                    DrawerItemData(stringResource(R.string.nav_ssh_keys), Icons.Default.Key, Color(0xFF1976D2), Screen.SSH_KEYS),
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
                    DrawerItemData(stringResource(R.string.nav_wifi), Icons.Default.Wifi, Color(0xFF2196F3), Screen.WIFI_MANAGEMENT),
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


