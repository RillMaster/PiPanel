package com.rillmaster.pipanel.ui.components

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rillmaster.pipanel.BuildConfig
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.model.DrawerItemData
import com.rillmaster.pipanel.model.Screen

/**
 * Contenu du tiroir de navigation latéral (Navigation Drawer).
 */
@Composable
fun AppDrawerContent(
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
            } ?: BuildConfig.VERSION_NAME
        } catch (_: Exception) { BuildConfig.VERSION_NAME }
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
            var itemIndex = 0
            Spacer(Modifier.height(8.dp))

            // Dashboard
            DrawerNavItem(
                item = DrawerItemData(stringResource(R.string.nav_dashboard), Icons.Default.GridView, MaterialTheme.colorScheme.primary, Screen.CONTROL),
                selected = currentScreen == Screen.CONTROL,
                index = itemIndex++,
                onClick = { onNavigate(Screen.CONTROL) },
                settings = settings
            )

            DrawerNavItem(
                item = DrawerItemData(stringResource(R.string.nav_profiles), Icons.Default.Devices, MaterialTheme.colorScheme.secondary, Screen.PROFILES),
                selected = currentScreen == Screen.PROFILES,
                index = itemIndex++,
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
                DrawerNavItem(item, selected = currentScreen == item.screen, index = itemIndex++, onClick = { item.screen?.let { onNavigate(it) } }, settings = settings)
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Groupe Services
            DrawerSectionLabel(stringResource(R.string.section_services))
            val serviceItems = listOf(
                DrawerItemData(stringResource(R.string.nav_monitoring), Icons.Default.BarChart, Color(0xFF2196F3), Screen.MONITORING),
                DrawerItemData(stringResource(R.string.nav_charts), Icons.AutoMirrored.Filled.ShowChart, Color(0xFF0097A7), Screen.CHARTS),
                DrawerItemData(stringResource(R.string.nav_docker), R.drawable.docker, Color(0xFF0288D1), Screen.DOCKER),
                DrawerItemData(stringResource(R.string.nav_services), Icons.Default.SettingsSuggest, Color(0xFF607D8B), Screen.SERVICES),
                DrawerItemData(stringResource(R.string.nav_file_manager), Icons.Default.Folder, Color(0xFFFFA000), Screen.FILE_MANAGER),
                DrawerItemData(stringResource(R.string.nav_pihole), R.drawable.ic_widget_pihole, Color(0xFFE53935), Screen.PIHOLE),
                DrawerItemData(stringResource(R.string.nav_wireguard), R.drawable.wireguard, Color(0xFF43A047), Screen.WIREGUARD),
                DrawerItemData(stringResource(R.string.nav_terminal), Icons.Default.Terminal, MaterialTheme.colorScheme.onSurface, Screen.TERMINAL),
                DrawerItemData(stringResource(R.string.nav_logs), Icons.AutoMirrored.Filled.ListAlt, Color(0xFF009688), Screen.LOGS_VIEWER),
            )
            serviceItems.forEach { item ->
                DrawerNavItem(item, selected = currentScreen == item.screen || (item.screen == Screen.PIHOLE && currentScreen == Screen.PIHOLE_CONFIG), index = itemIndex++, onClick = { item.screen?.let { onNavigate(it) } }, settings = settings)
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
                DrawerNavItem(item, selected = currentScreen == item.screen, index = itemIndex++, onClick = { item.screen?.let { onNavigate(it) } }, settings = settings)
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
                DrawerNavItem(item, selected = currentScreen == item.screen, index = itemIndex++, onClick = { item.screen?.let { onNavigate(it) } }, settings = settings)
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
