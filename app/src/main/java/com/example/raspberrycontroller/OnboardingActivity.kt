package com.example.raspberrycontroller

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Package WireGuard
// ─────────────────────────────────────────────────────────────────────────────
private const val WG_PACKAGE = "com.wireguard.android"
private const val WG_PLAY    = "market://details?id=$WG_PACKAGE"

// ─────────────────────────────────────────────────────────────────────────────
// Étapes de l'onboarding
// ─────────────────────────────────────────────────────────────────────────────
private enum class OnboardingStep {
    WELCOME,
    NETWORK,
    WIREGUARD,
    SSH_CONFIG,
    TEST,
    DONE
}

// ─────────────────────────────────────────────────────────────────────────────
// Composable principal
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun OnboardingScreen(
    @Suppress("unused") activity: FragmentActivity,
    settings  : SettingsManager,
    onFinished: () -> Unit,
) {
    var step         by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var useWireGuard by remember { mutableStateOf(value = false) }

    var host     by remember { mutableStateOf(settings.host.ifEmpty { "" }) }
    var port     by remember { mutableStateOf(if (settings.port != 0) settings.port.toString() else "22") }
    var username by remember { mutableStateOf(settings.username.ifEmpty { "pi" }) }
    var password by remember { mutableStateOf(settings.password) }

    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }

    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    val currentDot = when (step) {
        OnboardingStep.WELCOME    -> 0
        OnboardingStep.NETWORK,
        OnboardingStep.WIREGUARD  -> 1
        OnboardingStep.SSH_CONFIG -> 2
        OnboardingStep.TEST       -> 3
        OnboardingStep.DONE       -> 4
    }

    // ✅ FIX THÈME : Box avec background du thème pour couvrir tout l'écran
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            StepIndicator(current = currentDot, total = 5)

            Spacer(Modifier.height(32.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it } + fadeOut())
                },
                label = "onboarding"
            ) { currentStep ->
                when (currentStep) {

                    OnboardingStep.WELCOME -> WelcomeStep { step = OnboardingStep.NETWORK }

                    OnboardingStep.NETWORK -> NetworkStep(
                        useWireGuard = useWireGuard,
                        onToggleWg   = { useWireGuard = it },
                        onBack       = { step = OnboardingStep.WELCOME },
                        onNext       = {
                            step = if (useWireGuard) OnboardingStep.WIREGUARD
                            else              OnboardingStep.SSH_CONFIG
                        }
                    )

                    OnboardingStep.WIREGUARD -> WireGuardStep(
                        onBack   = { step = OnboardingStep.NETWORK },
                        onNext   = { step = OnboardingStep.SSH_CONFIG },
                        onOpenWg = {
                            val launchIntent = context.packageManager
                                .getLaunchIntentForPackage(WG_PACKAGE)
                            if (launchIntent != null) context.startActivity(launchIntent)
                            else context.startActivity(Intent(Intent.ACTION_VIEW, WG_PLAY.toUri()))
                        }
                    )

                    OnboardingStep.SSH_CONFIG -> SshConfigStep(
                        host     = host,     onHostChange     = { host     = it },
                        port     = port,     onPortChange     = { port     = it },
                        username = username, onUsernameChange = { username = it },
                        password = password, onPasswordChange = { password = it },
                        onBack   = {
                            step = if (useWireGuard) OnboardingStep.WIREGUARD
                            else OnboardingStep.NETWORK
                        },
                        onNext = {
                            settings.host     = host
                            settings.port     = port.toIntOrNull() ?: 22
                            settings.username = username
                            settings.password = password
                            testState         = TestState.Idle
                            step              = OnboardingStep.TEST
                        }
                    )

                    OnboardingStep.TEST -> TestStep(
                        state       = testState,
                        onStartTest = {
                            testState = TestState.Loading
                            scope.launch {
                                val result = SshClient.execute(
                                    host     = settings.host,
                                    port     = settings.port,
                                    user     = settings.username,
                                    password = settings.password,
                                    command  = "echo __ok__",
                                    context  = context
                                )
                                testState = if (result.contains("__ok__")) TestState.Success
                                else TestState.Error(result)
                            }
                        },
                        onBack = { step = OnboardingStep.SSH_CONFIG },
                        onNext = { step = OnboardingStep.DONE }
                    )

                    OnboardingStep.DONE -> DoneStep(
                        onFinish = {
                            settings.isFirstLaunch = false
                            onFinished()
                        }
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Indicateurs de progression
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StepIndicator(current: Int, @Suppress("SameParameterValue") total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val active = i <= current
            Box(
                modifier = Modifier
                    .size(if (i == current) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else        MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// État du test de connexion
// ─────────────────────────────────────────────────────────────────────────────
private sealed class TestState {
    object Idle    : TestState()
    object Loading : TestState()
    object Success : TestState()
    data class Error(val message: String) : TestState()
}

// ─────────────────────────────────────────────────────────────────────────────
// ÉTAPE 0 — Bienvenue
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
        }

        Text(
            text      = stringResource(R.string.onboarding_welcome_title),
            style     = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Text(
            text      = stringResource(R.string.onboarding_welcome_desc),
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        val features = listOf(
            "💡" to stringResource(R.string.onboarding_feature_gpio),
            "🖥️" to stringResource(R.string.onboarding_feature_terminal),
            "🌡️" to stringResource(R.string.onboarding_feature_sensors),
            "⚡" to stringResource(R.string.onboarding_feature_shortcuts)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier            = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                features.forEach { (emoji, label) ->
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(emoji, fontSize = 20.sp)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_action_start))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ÉTAPE 1 — Choix réseau
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun NetworkStep(
    useWireGuard: Boolean,
    onToggleWg  : (Boolean) -> Unit,
    onBack      : () -> Unit,
    onNext      : () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StepHeader(step = stringResource(R.string.onboarding_step_1_4), title = stringResource(R.string.onboarding_network_title))

        Text(
            text  = stringResource(R.string.onboarding_network_question),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        NetworkOptionCard(
            selected    = !useWireGuard,
            emoji       = "📶",
            title       = stringResource(R.string.onboarding_network_local_title),
            description = stringResource(R.string.onboarding_network_local_desc),
            onClick     = { onToggleWg(false) }
        )

        NetworkOptionCard(
            selected    = useWireGuard,
            emoji       = "🔒",
            title       = stringResource(R.string.onboarding_network_wg_title),
            description = stringResource(R.string.onboarding_network_wg_desc),
            onClick     = { onToggleWg(true) }
        )

        if (useWireGuard) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text     = stringResource(R.string.onboarding_network_wg_info),
                    modifier = Modifier.padding(12.dp),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        NavigationButtons(onBack = onBack, onNext = onNext)
    }
}

@Composable
private fun NetworkOptionCard(
    selected   : Boolean,
    emoji      : String,
    title      : String,
    description: String,
    onClick    : () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else          MaterialTheme.colorScheme.outlineVariant
    val bgColor     = if (selected) MaterialTheme.colorScheme.primaryContainer
    else          MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier              = Modifier.padding(14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(emoji, fontSize = 28.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(title,       style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ÉTAPE 1b — WireGuard
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun WireGuardStep(
    onBack  : () -> Unit,
    onNext  : () -> Unit,
    onOpenWg: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StepHeader(step = stringResource(R.string.onboarding_step_1_4_vpn), title = stringResource(R.string.onboarding_wg_config_title))

        Text(
            text  = stringResource(R.string.onboarding_wg_config_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.onboarding_wg_rpi_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    text       = "sudo apt install wireguard\nwg genkey | tee priv | wg pubkey > pub",
                    fontFamily = FontFamily.Monospace,
                    style      = MaterialTheme.typography.bodySmall,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.onboarding_wg_phone_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    text  = stringResource(R.string.onboarding_wg_phone_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick  = onOpenWg,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF88171A))
        ) {
            Text(stringResource(R.string.onboarding_wg_open_action))
        }

        Text(
            text      = stringResource(R.string.onboarding_wg_play_store_info),
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier  = Modifier.fillMaxWidth()
        )

        NavigationButtons(onBack = onBack, onNext = onNext, nextLabel = stringResource(R.string.onboarding_wg_next_label))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ÉTAPE 2 — Config SSH
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SshConfigStep(
    host: String,     onHostChange    : (String) -> Unit,
    port: String,     onPortChange    : (String) -> Unit,
    username: String, onUsernameChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepHeader(step = stringResource(R.string.onboarding_step_2_4), title = stringResource(R.string.onboarding_ssh_title))

        Text(
            text  = stringResource(R.string.onboarding_ssh_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value         = host,
            onValueChange = onHostChange,
            label         = { Text(stringResource(R.string.onboarding_ssh_host_label)) },
            placeholder   = { Text(stringResource(R.string.onboarding_ssh_host_hint)) },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = port, onValueChange = onPortChange,
                label = { Text(stringResource(R.string.onboarding_ssh_port_label)) },
                modifier = Modifier.width(120.dp), singleLine = true
            )
            OutlinedTextField(
                value = username, onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.onboarding_ssh_user_label)) }, placeholder = { Text("pi") },
                modifier = Modifier.weight(1f), singleLine = true
            )
        }

        OutlinedTextField(
            value                = password,
            onValueChange        = onPasswordChange,
            label                = { Text(stringResource(R.string.onboarding_ssh_pass_label)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier             = Modifier.fillMaxWidth(),
            singleLine           = true
        )

        NavigationButtons(onBack = onBack, onNext = onNext,
            nextEnabled = host.isNotEmpty() && username.isNotEmpty())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ÉTAPE 3 — Test connexion
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TestStep(
    state      : TestState,
    onStartTest: () -> Unit,
    onBack     : () -> Unit,
    onNext     : () -> Unit
) {
    LaunchedEffect(Unit) {
        if (state is TestState.Idle) onStartTest()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepHeader(step = stringResource(R.string.onboarding_step_3_4), title = stringResource(R.string.onboarding_test_title))
        Spacer(Modifier.height(8.dp))

        when (state) {
            is TestState.Idle, TestState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(56.dp))
                Text(stringResource(R.string.onboarding_test_loading), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.onboarding_test_attempt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is TestState.Success -> {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp))
                }
                Text(stringResource(R.string.onboarding_test_success), style = MaterialTheme.typography.titleMedium)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CheckRow(stringResource(R.string.onboarding_test_ssh_ok))
                        CheckRow(stringResource(R.string.onboarding_test_pi_accessible))
                        CheckRow(stringResource(R.string.onboarding_test_creds_saved))
                    }
                }

                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_test_finish))
                }
            }

            is TestState.Error -> {
                Text("❌", fontSize = 48.sp)
                Text(stringResource(R.string.onboarding_test_failed), style = MaterialTheme.typography.titleMedium)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(text = state.message, modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_modify)) }
                    Button(onClick = onStartTest, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Check, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ÉTAPE 4 — Terminé
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DoneStep(onFinish: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("🎉", fontSize = 64.sp, textAlign = TextAlign.Center)
        Text(stringResource(R.string.onboarding_done_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            text      = stringResource(R.string.onboarding_done_desc),
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_action_finish))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composants réutilisables
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StepHeader(step: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = step, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun NavigationButtons(
    onBack      : () -> Unit,
    onNext      : () -> Unit,
    nextLabel   : String  = stringResource(R.string.onboarding_next),
    nextEnabled : Boolean = true
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.wrapContentWidth()) { Text(stringResource(R.string.onboarding_back)) }
        Button(onClick = onNext, modifier = Modifier.weight(1f), enabled = nextEnabled) { Text(nextLabel) }
    }
}