package com.rillmaster.pipanel

import android.content.Context
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
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SensorWidgetKeys {
    val ds18b20Temp = floatPreferencesKey("ds18b20_temp")
    val dht22Temp   = floatPreferencesKey("dht22_temp")
    val dht22Hum    = floatPreferencesKey("dht22_hum")
    val lastUpdate  = stringPreferencesKey("sensor_last_update")
}

class SensorWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetContent()
        }
    }

    @Composable
    private fun WidgetContent() {
        val prefs = currentState<Preferences>()
        val dsTemp  = prefs[SensorWidgetKeys.ds18b20Temp] ?: -999f
        val dhtTemp = prefs[SensorWidgetKeys.dht22Temp] ?: -999f
        val dhtHum  = prefs[SensorWidgetKeys.dht22Hum] ?: -999f
        val time    = prefs[SensorWidgetKeys.lastUpdate] ?: "--:--"

        val cardBg  = Color(0xFF161C22)
        val dsColor = Color(0xFFFF5722)
        val dhtTColor = Color(0xFFFF9800)
        val dhtHColor = Color(0xFF2196F3)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(cardBg)
                .cornerRadius(16.dp)
                .clickable(actionRunCallback<RefreshSensorsActionCallback>())
                .padding(12.dp),
            verticalAlignment = Alignment.Vertical.Top
        ) {
            Text(
                text = "Capteurs",
                style = TextStyle(color = ColorProvider(day = Color.White, night = Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(GlanceModifier.height(8.dp))

            SensorRow("DS18B20", if (dsTemp > -50) "%.1f°C".format(dsTemp) else "OFF", dsColor)
            Spacer(GlanceModifier.height(8.dp))
            SensorRow("DHT22 T", if (dhtTemp > -50) "%.1f°C".format(dhtTemp) else "OFF", dhtTColor)
            Spacer(GlanceModifier.height(8.dp))
            SensorRow("DHT22 H", if (dhtHum >= 0) "%.1f%%".format(dhtHum) else "OFF", dhtHColor)

            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = "Mise à jour : $time",
                style = TextStyle(color = ColorProvider(day = Color.White.copy(alpha = 0.4f), night = Color.White.copy(alpha = 0.4f)), fontSize = 9.sp)
            )
        }
    }

    @Composable
    private fun SensorRow(name: String, value: String, color: Color) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(8.dp).background(Color.White.copy(alpha = 0.05f)).cornerRadius(8.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Box(modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(color), content = {})
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = name,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = ColorProvider(day = Color.White, night = Color.White), fontSize = 12.sp)
            )
            Text(
                text = value,
                style = TextStyle(color = ColorProvider(day = color, night = color), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            )
        }
    }
}

class RefreshSensorsActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        UpdateStatsWorker.enqueue(context, force = true)
        WidgetUpdateService.start(context)
    }
}

class SensorWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SensorWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateService.start(context)
    }
}
