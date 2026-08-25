package com.rillmaster.pipanel

import android.content.Context
import com.rillmaster.pipanel.model.Screen
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

// ── État du Widget ───────────────────────────────────────────────────────────
object DockerWidgetKeys {
    val running    = intPreferencesKey("docker_running")
    val total      = intPreferencesKey("docker_total")
    val lastUpdate = stringPreferencesKey("docker_last_update")
}

class DockerWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetContent()
        }
    }

    @Composable
    private fun WidgetContent() {
        val prefs   = currentState<Preferences>()
        val running = prefs[DockerWidgetKeys.running] ?: 0
        val total   = prefs[DockerWidgetKeys.total] ?: 0
        val time    = prefs[DockerWidgetKeys.lastUpdate] ?: "--:--"

        val dockerBlue = Color(0xFF1D63ED)
        val okGreen    = Color(0xFF66BB6A)
        val cardBg     = Color(0xFF161C22)
        val allUp      = total > 0 && running == total

        // Tap → ouvre l'app sur l'écran Docker
        val openAppIntent = Intent(LocalContext.current, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, Screen.DOCKER.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(cardBg)
                .cornerRadius(16.dp)
                .clickable(actionStartActivity(openAppIntent))
                .padding(12.dp),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .size(8.dp)
                        .cornerRadius(4.dp)
                        .background(if (allUp) okGreen else dockerBlue),
                    content = {}
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = "Docker",
                    style = TextStyle(
                        color = ColorProvider(day = Color.White, night = Color.White),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Spacer(GlanceModifier.defaultWeight())

            // Compteur running / total
            Text(
                text = "$running / $total",
                style = TextStyle(
                    color = ColorProvider(day = if (allUp) okGreen else dockerBlue, night = if (allUp) okGreen else dockerBlue),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "conteneurs actifs",
                style = TextStyle(
                    color = ColorProvider(day = Color.White.copy(alpha = 0.7f), night = Color.White.copy(alpha = 0.7f)),
                    fontSize = 11.sp
                )
            )

            Spacer(GlanceModifier.height(8.dp))

            LinearProgressIndicator(
                progress = if (total > 0) running.toFloat() / total else 0f,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = ColorProvider(day = dockerBlue, night = dockerBlue),
                backgroundColor = ColorProvider(day = Color.White.copy(alpha = 0.1f), night = Color.White.copy(alpha = 0.1f))
            )

            Spacer(GlanceModifier.defaultWeight())

            Text(
                text = "Mise à jour : $time",
                style = TextStyle(
                    color = ColorProvider(day = Color.White.copy(alpha = 0.4f), night = Color.White.copy(alpha = 0.4f)),
                    fontSize = 9.sp
                )
            )
        }
    }
}

// ── Receiver ──────────────────────────────────────────────────────────────────
class DockerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DockerWidget()

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetUpdateService.start(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateService.start(context)
    }
}
