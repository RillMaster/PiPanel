@file:Suppress("UNUSED_VALUE", "SpellCheckingInspection")

package com.rillmaster.pipanel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.compose.material.icons.automirrored.filled.ShowChart
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
import com.wireguard.android.backend.GoBackend

// ══════════════════════════════════════════════════════════════════════════════
//  MainActivity
// ══════════════════════════════════════════════════════════════════════════════
class MainActivity : FragmentActivity() {

    private var downloadProgress = mutableIntStateOf(-2)

    /** Navigation demandée par un widget alors que l'app tourne déjà (via onNewIntent). */
    private val pendingNavigation = mutableStateOf<Screen?>(null)

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_NOTIF = 1001

        /** Extra d'intent pour ouvrir l'app directement sur un écran (ex : widgets). */
        const val EXTRA_NAVIGATE_TO = "navigate_to"
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        // Le splash ne reste affiché qu'un court instant : l'animation de sortie (500ms)
        // suffit à la transition visuelle, sans bloquer l'utilisateur inutilement.
        val splashStartTime = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            System.currentTimeMillis() - splashStartTime < 500L
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

        if (settings.backgroundActivityEnabled && !settings.isFirstLaunch) {
            if (settings.notificationsEnabled) {
                MonitoringWorker.schedule(this)
            }
            // Widget stats
            UpdateStatsWorker.schedulePeriodic(this)
            WidgetUpdateService.start(this)
        } else {
            // Nettoyage immédiat si on est en onboarding ou si désactivé
            WorkManager.getInstance(this).cancelAllWork()
            WidgetUpdateService.stop(this)
            MonitoringWorker.cancel(this)
        }

        startService(Intent(this, VpnCleanupService::class.java))

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
                        
                        // Auto-connect VPN au lancement si configuré
                        if (settingsR.vpnConfig.isNotBlank()) {
                            lifecycleScope.launch {
                                try {
                                    val vpnManager = PiPanelApp.instance.vpnManager
                                    vpnManager.refreshState()
                                    // On ne lance que si c'est actuellement DOWN
                                    // Note: GoBackend.VpnService.prepare(this) doit être null pour lancer sans intent
                                    if (GoBackend.VpnService.prepare(this@MainActivity) == null) {
                                        vpnManager.startTunnel(settingsR.vpnConfig)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Navigation profonde (widgets) quand l'app est déjà ouverte
        intent.getStringExtra(EXTRA_NAVIGATE_TO)
            ?.let { name -> runCatching { Screen.valueOf(name) }.getOrNull() }
            ?.let { pendingNavigation.value = it }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ne couper le VPN que si l'activité est réellement terminée (pas une simple
        // rotation/recréation). On utilise applicationScope car lifecycleScope est
        // annulé dès onDestroy et la coroutine risquerait de ne jamais s'exécuter.
        if (isFinishing && !isChangingConfigurations) {
            PiPanelApp.instance.applicationScope.launch {
                try {
                    PiPanelApp.instance.vpnManager.stopTunnel()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop VPN tunnel on destroy", e)
                }
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
            val requested = activity.intent?.getStringExtra(EXTRA_NAVIGATE_TO)
                ?.let { name -> runCatching { Screen.valueOf(name) }.getOrNull() }
            mutableStateOf(requested ?: if (settings.isConfigured()) Screen.CONTROL else Screen.SETTINGS)
        }

        val pending by pendingNavigation
        LaunchedEffect(pending) {
            pending?.let {
                currentScreen.value = it
                pendingNavigation.value = null
            }
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
}


