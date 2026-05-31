package com.rillmaster.pipanel

import android.content.Context
import android.os.UserManager
import android.util.Log
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
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object WireGuardWidgetKeys {
    val enabled       = booleanPreferencesKey("wg_enabled")
    val interfaceName = stringPreferencesKey("wg_interface")
    val port          = intPreferencesKey("wg_port")
    val clients       = intPreferencesKey("wg_clients")
    val lastUpdate    = stringPreferencesKey("wg_last_update")
    val ignoreUntil   = longPreferencesKey("wg_ignore_until")
}

class WireGuardWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetContent()
        }
    }

    @Composable
    private fun WidgetContent() {
        val prefs = currentState<Preferences>()
        val enabled = prefs[WireGuardWidgetKeys.enabled] ?: false
        val iface   = prefs[WireGuardWidgetKeys.interfaceName] ?: "wg0"
        val port    = prefs[WireGuardWidgetKeys.port] ?: 51820
        val clients = prefs[WireGuardWidgetKeys.clients] ?: 0
        val time    = prefs[WireGuardWidgetKeys.lastUpdate] ?: "--:--"

        val wgGreen = Color(0xFF4CAF50)
        val wgGrey  = Color(0xFF9E9E9E)
        val cardBg  = Color(0xFF161C22)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(cardBg)
                .cornerRadius(16.dp)
                .clickable(actionRunCallback<ToggleWireGuardActionCallback>())
                .padding(12.dp),
            horizontalAlignment = Alignment.Horizontal.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .size(10.dp)
                        .cornerRadius(5.dp)
                        .background(if (enabled) wgGreen else wgGrey),
                    content = {}
                )
                Spacer(GlanceModifier.width(8.dp))
                Column {
                    Text(
                        text = iface,
                        style = TextStyle(color = ColorProvider(day = Color.White, night = Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Port : $port",
                        style = TextStyle(color = ColorProvider(day = Color.White.copy(alpha = 0.6f), night = Color.White.copy(alpha = 0.6f)), fontSize = 10.sp)
                    )
                }
                Spacer(GlanceModifier.defaultWeight())
                // Custom Switch representation
                Box(
                    modifier = GlanceModifier
                        .width(34.dp)
                        .height(20.dp)
                        .cornerRadius(10.dp)
                        .background(if (enabled) wgGreen else Color.Gray),
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

            Spacer(GlanceModifier.height(16.dp))

            Row(modifier = GlanceModifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f)).cornerRadius(12.dp).padding(12.dp)) {
                StatSmall("VPN Status", if (enabled) "UP" else "DOWN", if (enabled) wgGreen else wgGrey, GlanceModifier.defaultWeight())
                StatSmall("Clients", clients.toString(), wgGreen, GlanceModifier.defaultWeight())
            }

            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = "Mise à jour : $time",
                style = TextStyle(color = ColorProvider(day = Color.White.copy(alpha = 0.4f), night = Color.White.copy(alpha = 0.4f)), fontSize = 9.sp)
            )
        }
    }

    @Composable
    private fun StatSmall(label: String, value: String, color: Color, modifier: GlanceModifier) {
        Column(modifier = modifier, horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            Text(text = value, style = TextStyle(color = ColorProvider(day = color, night = color), fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Text(text = label, style = TextStyle(color = ColorProvider(day = Color.White.copy(alpha = 0.6f), night = Color.White.copy(alpha = 0.6f)), fontSize = 10.sp))
        }
    }
}

class ToggleWireGuardActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Log.d("Widget", "WireGuardWidget: Clic reçu")

        // Check if user is unlocked to avoid IllegalStateException: User 0 must be unlocked
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.w("Widget", "WireGuardWidget: User is locked, skipping toggle")
            return
        }

        val settings = SettingsManager(context)
        val manager  = GlanceAppWidgetManager(context)
        val ids      = manager.getGlanceIds(WireGuardWidget::class.java)

        if (ids.isEmpty()) return

        var newVal   = false
        var iface    = "wg0"
        val now = System.currentTimeMillis()

        // 1. Déterminer le nouvel état (une seule fois pour toutes les instances)
        updateAppWidgetState(context, ids.first()) { prefs ->
            val current = prefs[WireGuardWidgetKeys.enabled] ?: false
            iface = prefs[WireGuardWidgetKeys.interfaceName] ?: "wg0"
            newVal = !current
        }

        // 2. Appliquer l'état et verrouiller TOUTES les instances immédiatement
        ids.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs[WireGuardWidgetKeys.enabled] = newVal
                prefs[WireGuardWidgetKeys.ignoreUntil] = now + 30_000
                Log.e("Widget", "WireGuardWidget: Verrouillage posé sur $id, nouvel état: $newVal")
            }
            WireGuardWidget().update(context, id)
        }

        Log.e("Widget", "WireGuardWidget: Envoi commande SSH toggle -> $newVal sur $iface")
        val success = toggleWireGuard(settings, iface, newVal)
        Log.e("Widget", "WireGuardWidget: Résultat commande SSH: $success")

        if (!success) {
            Log.e("Widget", "WireGuardWidget: ÉCHEC du toggle, retour arrière")
            ids.forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[WireGuardWidgetKeys.enabled] = !newVal
                    prefs[WireGuardWidgetKeys.ignoreUntil] = 0L
                }
                WireGuardWidget().update(context, id)
            }
        } else {
            Log.d("Widget", "WireGuardWidget: SUCCÈS, attente 2s avant refresh")
            kotlinx.coroutines.delay(2000)
            WidgetUpdateService.start(context)
        }
    }
}

class WireGuardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WireGuardWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateService.start(context)
    }
}
