package com.rillmaster.pipanel.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rillmaster.pipanel.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

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
