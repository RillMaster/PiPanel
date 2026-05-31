package com.rillmaster.pipanel

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val CfgRed   = Color(0xFFEF5350)
private val CfgGreen = Color(0xFF66BB6A)
private val CfgBlue  = Color(0xFF42A5F5)
private val CfgAmber = Color(0xFFFFA726)

sealed class PiHoleTestState {
    object Idle                                : PiHoleTestState()
    object Testing                             : PiHoleTestState()
    data class Success(val stats: PiHoleStats) : PiHoleTestState()
    data class Failure(val reason: String)     : PiHoleTestState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiHoleConfigScreen(
    settings : SettingsManager,
    onClose  : () -> Unit,
    onSaved  : () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var piPassword   by remember { mutableStateOf(settings.piHolePassword) }
    var showPassword by remember { mutableStateOf(false) }
    var autoRefresh  by remember { mutableStateOf(settings.piHoleAutoRefresh) }
    var refreshDelay by remember { mutableIntStateOf(settings.piHoleRefreshDelaySec) }
    var testState    by remember { mutableStateOf<PiHoleTestState>(PiHoleTestState.Idle) }
    var debugInfo    by remember { mutableStateOf<String?>(null) }

    val snackState = remember { SnackbarHostState() }
    val sshOk      = settings.isConfigured()
    val formOk     = piPassword.isNotBlank() && sshOk

    fun saveConfig() {
        settings.piHolePassword        = piPassword
        settings.piHoleAutoRefresh     = autoRefresh
        settings.piHoleRefreshDelaySec = refreshDelay
        scope.launch {
            snackState.showSnackbar(context.getString(R.string.pihole_config_save_success))
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null,
                            tint = CfgRed, modifier = Modifier.size(22.dp))
                        Text(
                            stringResource(R.string.pihole_config_title), 
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_action))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackState) }
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

            if (!sshOk) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CfgAmber.copy(alpha = 0.12f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CfgAmber.copy(0.4f))
                ) {
                    Row(modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = CfgAmber)
                        Column {
                            Text(stringResource(R.string.pihole_config_ssh_required_title), fontWeight = FontWeight.Bold, color = CfgAmber)
                            Text(stringResource(R.string.pihole_config_ssh_required_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            CfgSectionLabel(icon = Icons.Default.Terminal, title = stringResource(R.string.settings_ssh_title))

            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Computer, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (sshOk) "${settings.username}@${settings.host}:${settings.port}" else stringResource(R.string.pihole_config_ssh_not_configured),
                            fontWeight = FontWeight.SemiBold, 
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (sshOk) stringResource(R.string.pihole_config_ssh_hint_configured) else stringResource(R.string.pihole_config_ssh_hint_not_configured),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = if (sshOk) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (sshOk) CfgGreen else CfgRed)
                }
            }

            CfgSectionLabel(icon = Icons.Default.Key, title = stringResource(R.string.pihole_config_auth_section))

            OutlinedTextField(
                value         = piPassword,
                onValueChange = { piPassword = it; testState = PiHoleTestState.Idle; debugInfo = null },
                label         = { Text(stringResource(R.string.pihole_config_password_label)) },
                placeholder   = { Text(stringResource(R.string.pihole_config_password_placeholder)) },
                leadingIcon   = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon  = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Masquer" else "Afficher")
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine      = true,
                modifier        = Modifier.fillMaxWidth()
            )

            if (settings.piHolePassword.isNotBlank()) {
                Text(
                    stringResource(R.string.pihole_config_password_saved, "*".repeat(settings.piHolePassword.length), settings.piHolePassword.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    stringResource(R.string.pihole_config_password_none),
                    style = MaterialTheme.typography.labelSmall,
                    color = CfgAmber
                )
            }

            CfgInfoCard(
                icon  = Icons.Default.Info,
                color = CfgBlue,
                text  = stringResource(R.string.pihole_config_v6_info)
            )

            CfgSectionLabel(icon = Icons.Default.Tune, title = stringResource(R.string.pihole_config_advanced_options))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.pihole_config_auto_refresh),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.pihole_config_auto_refresh_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
                    }

                    AnimatedVisibility(visible = autoRefresh) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.pihole_config_refresh_interval, refreshDelay),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    when {
                                        refreshDelay <= 15 -> stringResource(R.string.pihole_config_refresh_frequent)
                                        refreshDelay <= 45 -> stringResource(R.string.pihole_config_refresh_normal)
                                        else               -> stringResource(R.string.pihole_config_refresh_eco)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        refreshDelay <= 15 -> CfgAmber
                                        refreshDelay <= 45 -> CfgGreen
                                        else               -> CfgBlue
                                    }
                                )
                            }
                            Slider(
                                value         = refreshDelay.toFloat(),
                                onValueChange = { refreshDelay = it.toInt() },
                                valueRange    = 10f..120f,
                                steps         = 10,
                                modifier      = Modifier.fillMaxWidth()
                            )
                            Row(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("10s", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("120s", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Column {
                            Text(stringResource(R.string.pihole_config_endpoint_label), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("http://localhost/api  (via SSH)",
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                }
            }

            debugInfo?.let { info ->
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.pihole_config_raw_response),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(info, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }

            AnimatedVisibility(
                visible = testState !is PiHoleTestState.Idle,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut()
            ) {
                CfgTestResultCard(state = testState)
            }

            Spacer(Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            testState = PiHoleTestState.Testing
                            debugInfo = null
                            val raw = try {
                                SshClient.execute(
                                    settings.host, settings.port,
                                    settings.username, settings.password,
                                    buildFetchScript(piPassword),
                                    settings.sshTimeoutMs,
                                    context = context
                                ).trim()
                            } catch (e: Exception) {
                                "Exception SSH : ${e.message}"
                            }
                            debugInfo = raw
                            testState = when {
                                raw.startsWith("auth_error") || raw.startsWith("no_sid") ->
                                    PiHoleTestState.Failure(context.getString(R.string.error_pihole_password) + "\nReponse : $raw")
                                raw.startsWith("stats_error") ->
                                    PiHoleTestState.Failure(context.getString(R.string.error_stats_prefix, raw.substringAfter("|")))
                                raw.startsWith("Exception") ->
                                    PiHoleTestState.Failure(raw)
                                else -> {
                                    val parsed = parseStatsRaw(raw)
                                    if (parsed != null) PiHoleTestState.Success(parsed)
                                    else PiHoleTestState.Failure(context.getString(R.string.error_unexpected_response, raw))
                                }
                            }
                        }
                    },
                    enabled  = formOk && testState !is PiHoleTestState.Testing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (testState is PiHoleTestState.Testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pihole_config_test_ongoing))
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pihole_config_test_btn))
                    }
                }

                Button(
                    onClick  = { saveConfig() },
                    enabled  = formOk,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pihole_config_save_btn))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// FIX : Pi-hole v6 retourne "enabled"/"disabled", pas "true"/"false"
private fun parseStatsRaw(raw: String): PiHoleStats? {
    val parts = raw.split("|")
    if (parts.size < 8) return null
    val enabledStr = parts[0].trim()
    return PiHoleStats(
        enabled         = enabledStr == "enabled" || enabledStr == "true" || enabledStr == "True",
        domainsBlocked  = parts[1].trim().toIntOrNull() ?: 0,
        dnsQueriesToday = parts[2].trim().toIntOrNull() ?: 0,
        adsBlockedToday = parts[3].trim().toIntOrNull() ?: 0,
        adsPercentage   = parts[4].trim().replace(",", ".").toDoubleOrNull() ?: 0.0,
        uniqueDomains   = parts[5].trim().toIntOrNull() ?: 0,
        queriesCached   = parts[6].trim().toIntOrNull() ?: 0,
        clientsEverSeen = parts[7].trim().toIntOrNull() ?: 0
    )
}

@Composable
private fun CfgSectionLabel(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}

@Composable
private fun CfgInfoCard(icon: ImageVector, color: Color, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null,
                tint = color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Text(
                text, 
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CfgTestResultCard(state: PiHoleTestState) {
    val containerColor: Color
    val borderColor   : Color
    val icon          : ImageVector
    val tint          : Color
    val title         : String
    val body          : String
    val context       = LocalContext.current

    when (state) {
        is PiHoleTestState.Testing -> {
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            borderColor    = MaterialTheme.colorScheme.outline.copy(0.3f)
            icon           = Icons.Default.HourglassEmpty
            tint           = MaterialTheme.colorScheme.onSurfaceVariant
            title          = stringResource(R.string.pihole_config_test_ongoing)
            body           = stringResource(R.string.pihole_config_test_ssh_connecting)
        }
        is PiHoleTestState.Success -> {
            containerColor = CfgGreen.copy(alpha = 0.10f)
            borderColor    = CfgGreen.copy(alpha = 0.35f)
            icon           = Icons.Default.CheckCircle
            tint           = CfgGreen
            title          = stringResource(R.string.pihole_config_test_success)
            body           = stringResource(R.string.pihole_config_test_summary,
                if (state.stats.enabled) context.getString(R.string.status_active) else context.getString(R.string.status_inactive),
                state.stats.dnsQueriesToday,
                state.stats.adsPercentage,
                state.stats.domainsBlocked
            )
        }
        is PiHoleTestState.Failure -> {
            containerColor = CfgRed.copy(alpha = 0.10f)
            borderColor    = CfgRed.copy(alpha  = 0.35f)
            icon           = Icons.Default.Error
            tint           = CfgRed
            title          = stringResource(R.string.pihole_config_test_failed)
            body           = state.reason
        }
        PiHoleTestState.Idle -> return
    }

    Card(colors = CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)) {
        Row(modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                if (body.isNotBlank()) {
                    Text(body, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}