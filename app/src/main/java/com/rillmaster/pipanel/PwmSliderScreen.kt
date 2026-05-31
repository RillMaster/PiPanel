package com.rillmaster.pipanel

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class PwmDevice(
    val name : String,
    val pin  : Int,
    val icon : ImageVector,
    val unit : String,
    val color: Color,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PwmSliderScreen(
    settings: SettingsManager,
    onClose : () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val context           = androidx.compose.ui.platform.LocalContext.current
    val scope             = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val devices = listOf(
        PwmDevice(stringResource(R.string.pwm_led_label), 18, Icons.Default.Lightbulb, "%",   Color(0xFFFFD700)),
        PwmDevice(stringResource(R.string.pwm_fan_label),    12, Icons.Default.Air,       "RPM", Color(0xFF00BFFF))
    )

    val dutyCycles = remember { mutableStateListOf(0f, 0f) }
    val isApplying = remember { mutableStateListOf(false, false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.nav_pwm), 
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                stringResource(R.string.pwm_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            devices.forEachIndexed { idx, device ->
                PwmDeviceCard(
                    device        = device,
                    dutyCycle     = dutyCycles[idx],
                    isApplying    = isApplying[idx],
                    onValueChange = { dutyCycles[idx] = it },
                    onApply = {
                        scope.launch {
                            isApplying[idx] = true
                            try {
                                val dc  = dutyCycles[idx].toInt()
                                SshClient.execute(
                                    settings.host, settings.port,
                                    settings.username, settings.password,
                                    buildPwmCommand(device.pin, dc),
                                    settings.sshTimeoutMs
                                )
                                snackbarHostState.showSnackbar(context.getString(R.string.pwm_apply_success, device.name, dc, device.unit))
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("❌ ${e.message}")
                            } finally {
                                isApplying[idx] = false
                            }
                        }
                    }
                ) {
                    scope.launch {
                        isApplying[idx] = true
                        try {
                            SshClient.execute(
                                settings.host, settings.port,
                                settings.username, settings.password,
                                stopPwmCommand(device.pin),
                                settings.sshTimeoutMs
                            )
                            dutyCycles[idx] = 0f
                            snackbarHostState.showSnackbar(context.getString(R.string.pwm_stop_success, device.name))
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("❌ ${e.message}")
                        } finally {
                            isApplying[idx] = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PwmDeviceCard(
    device       : PwmDevice,
    dutyCycle    : Float,
    isApplying   : Boolean,
    onValueChange: (Float) -> Unit,
    onApply      : () -> Unit,
    onStop       : () -> Unit,
) {
    val intensity  = dutyCycle / 100f
    val glowAlpha by animateFloatAsState(
        targetValue   = if (dutyCycle > 0) 0.25f * intensity else 0f,
        animationSpec = tween(300),
        label         = "glow"
    )

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(device.color.copy(alpha = glowAlpha), Color.Transparent)
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier         = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(device.color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        device.icon,
                        contentDescription = null,
                        tint = device.color,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        device.name, 
                        fontWeight = FontWeight.SemiBold, 
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.pwm_pin_bcm, device.pin), 
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(device.color.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "${dutyCycle.toInt()}${device.unit}",
                        fontWeight = FontWeight.Bold,
                        color      = device.color,
                        fontSize   = 18.sp
                    )
                }
            }

            Slider(
                value         = dutyCycle,
                onValueChange = onValueChange,
                valueRange    = 0f..100f,
                steps         = 19,
                colors        = SliderDefaults.colors(
                    thumbColor       = device.color,
                    activeTrackColor = device.color
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier              = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = !isApplying,
                    colors  = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.action_stop)) }

                Button(
                    onClick = onApply,
                    enabled = !isApplying,
                    colors  = ButtonDefaults.buttonColors(containerColor = device.color)
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color       = Color.White
                        )
                    } else {
                        Text(stringResource(R.string.action_apply), color = Color.White)
                    }
                }
            }
        }
    }
}

private fun buildPwmCommand(pin: Int, dutyCycle: Int): String =
    "python3 -c \"" +
            "import RPi.GPIO as GPIO, time; " +
            "GPIO.setmode(GPIO.BCM); " +
            "GPIO.setup($pin, GPIO.OUT); " +
            "p = GPIO.PWM($pin, 1000); " +
            "p.start($dutyCycle); " +
            "time.sleep(0.5); " +
            "p.stop(); " +
            "GPIO.cleanup($pin)" +
            "\""

private fun stopPwmCommand(pin: Int): String =
    "python3 -c \"" +
            "import RPi.GPIO as GPIO; " +
            "GPIO.setmode(GPIO.BCM); " +
            "GPIO.setup($pin, GPIO.OUT); " +
            "GPIO.output($pin, GPIO.LOW); " +
            "GPIO.cleanup($pin)" +
            "\""