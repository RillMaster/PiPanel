package com.rillmaster.pipanel

import android.content.Context
import android.os.UserManager
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PiHoleWidgetKeys {
    val enabled      = booleanPreferencesKey("pihole_enabled")
    val adsBlocked   = intPreferencesKey("pihole_ads_blocked")
    val queries      = intPreferencesKey("pihole_queries")
    val blockingPct  = doublePreferencesKey("pihole_blocking_pct")
    val lastUpdate   = stringPreferencesKey("pihole_last_update")
    val ignoreUntil  = longPreferencesKey("pihole_ignore_until")
    val pending      = booleanPreferencesKey("pihole_pending")
}

class PiHoleWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetContent()
        }
    }

    @Composable
    private fun WidgetContent() {
        val prefs = currentState<Preferences>()
        val enabled = prefs[PiHoleWidgetKeys.enabled] ?: false
        val ads     = prefs[PiHoleWidgetKeys.adsBlocked] ?: 0
        val queries = prefs[PiHoleWidgetKeys.queries] ?: 0
        val pct     = prefs[PiHoleWidgetKeys.blockingPct] ?: 0.0
        val time    = prefs[PiHoleWidgetKeys.lastUpdate] ?: "--:--"
        val pending = prefs[PiHoleWidgetKeys.pending] ?: false

        val piGreen = Color(0xFF66BB6A)
        val piRed   = Color(0xFFEF5350)
        val cardBg  = Color(0xFF161C22)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(cardBg)
                .cornerRadius(16.dp)
                .clickable(actionRunCallback<TogglePiHoleActionCallback>())
                .padding(12.dp),
            horizontalAlignment = Alignment.Horizontal.Start
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .size(10.dp)
                        .cornerRadius(5.dp)
                        .background(if (enabled) piGreen else piRed),
                    content = {}
                )
                Spacer(GlanceModifier.width(8.dp))
                Column {
                    Text(
                        text = if (enabled) "Pi-hole actif" else "Pi-hole inactif",
                        style = TextStyle(color = ColorProvider(day = Color.White, night = Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = when {
                            pending -> "Basculement en cours…"
                            enabled -> "Filtrage en cours"
                            else    -> "Filtrage arrêté"
                        },
                        style = TextStyle(color = ColorProvider(day = Color.White.copy(alpha = 0.6f), night = Color.White.copy(alpha = 0.6f)), fontSize = 10.sp)
                    )
                }
                Spacer(GlanceModifier.defaultWeight())
                // Custom Switch representation (non-interactive to avoid intercepting clicks)
                Box(
                    modifier = GlanceModifier
                        .width(34.dp)
                        .height(20.dp)
                        .cornerRadius(10.dp)
                        .background(if (enabled) piGreen else Color.Gray),
                    contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Box(
                        modifier = GlanceModifier
                            .size(16.dp)
                            .padding(2.dp)
                            .cornerRadius(8.dp)
                            .background(Color.White),
                        content = {}
                    )
                }
            }

            Spacer(GlanceModifier.height(12.dp))

            // Blocking Rate
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f))
                    .cornerRadius(8.dp)
                    .padding(8.dp)
            ) {
                Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    Text(
                        text = "Taux de blocage",
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = ColorProvider(day = Color.White, night = Color.White), fontSize = 11.sp)
                    )
                    Text(
                        text = "%.1f%%".format(pct),
                        style = TextStyle(color = ColorProvider(day = piGreen, night = piGreen), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )
                }
                Spacer(GlanceModifier.height(4.dp))
                LinearProgressIndicator(
                    progress = (pct / 100.0).toFloat().coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                    color = ColorProvider(day = piGreen, night = piGreen),
                    backgroundColor = ColorProvider(day = Color.White.copy(alpha = 0.1f), night = Color.White.copy(alpha = 0.1f))
                )
            }

            Spacer(GlanceModifier.height(12.dp))

            // Quick Stats
            Row(modifier = GlanceModifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f)).cornerRadius(8.dp).padding(8.dp)) {
                StatSmall("Requêtes", queries.toString(), GlanceModifier.defaultWeight())
                StatSmall("Bloquées", ads.toString(), GlanceModifier.defaultWeight())
            }

            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = "Mise à jour : $time",
                style = TextStyle(color = ColorProvider(day = Color.White.copy(alpha = 0.4f), night = Color.White.copy(alpha = 0.4f)), fontSize = 9.sp)
            )
        }
    }

    @Composable
    private fun StatSmall(label: String, value: String, modifier: GlanceModifier) {
        Column(modifier = modifier, horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            Text(text = value, style = TextStyle(color = ColorProvider(day = Color.White, night = Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Text(text = label, style = TextStyle(color = ColorProvider(day = Color.White.copy(alpha = 0.6f), night = Color.White.copy(alpha = 0.6f)), fontSize = 9.sp))
        }
    }
}

class TogglePiHoleActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Log.e("Widget", "PiHoleWidget: CLIC REÇU (ActionCallback)")

        // Check if user is unlocked
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.e("Widget", "PiHoleWidget: ERREUR - Téléphone verrouillé")
            return
        }

        val settings = SettingsManager(context)
        val manager  = GlanceAppWidgetManager(context)
        val ids      = manager.getGlanceIds(PiHoleWidget::class.java)
        
        if (ids.isEmpty()) {
            Log.e("Widget", "PiHoleWidget: ERREUR - Aucun ID de widget trouvé")
            return
        }

        var newVal = false
        val now = System.currentTimeMillis()

        // 1. Déterminer le nouvel état (optimiste)
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[PiHoleWidgetKeys.enabled] ?: false
            newVal = !current
            Log.e("Widget", "PiHoleWidget: Basculement $current -> $newVal")
        }

        // 2. Appliquer l'état immédiatement à tous les widgets et poser le verrou
        ids.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs[PiHoleWidgetKeys.enabled] = newVal
                prefs[PiHoleWidgetKeys.pending] = true
                prefs[PiHoleWidgetKeys.ignoreUntil] = now + 30_000 // Verrou de 30s
            }
            PiHoleWidget().update(context, id)
        }

        // 3. Exécuter la commande SSH en arrière-plan
        Log.e("Widget", "PiHoleWidget: Envoi SSH toggle...")
        val success = togglePiHole(settings, newVal)
        Log.e("Widget", "PiHoleWidget: Succès SSH = $success")
        
        if (!success) {
            Log.e("Widget", "PiHoleWidget: ÉCHEC, on remet l'ancien état")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Erreur SSH lors du basculement", Toast.LENGTH_LONG).show()
            }

            ids.forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[PiHoleWidgetKeys.enabled] = !newVal
                    prefs[PiHoleWidgetKeys.pending] = false
                    prefs[PiHoleWidgetKeys.ignoreUntil] = 0L
                }
                PiHoleWidget().update(context, id)
            }
        } else {
            Log.e("Widget", "PiHoleWidget: SSH OK, rafraîchissement global dans 2s")
            kotlinx.coroutines.delay(2000)
            ids.forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[PiHoleWidgetKeys.pending] = false
                }
                PiHoleWidget().update(context, id)
            }
            WidgetUpdateService.start(context)
        }
    }
}

class PiHoleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PiHoleWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateService.start(context)
    }
}
