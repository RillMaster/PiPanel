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
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.*
import java.util.concurrent.TimeUnit

// ── État du Widget ───────────────────────────────────────────────────────────
object StatsWidgetKeys {
    val temp      = doublePreferencesKey("temp")
    val cpu       = intPreferencesKey("cpu")
    val ramUsed   = intPreferencesKey("ram_used")
    val ramTotal  = intPreferencesKey("ram_total")
    val host      = stringPreferencesKey("host")
    val user      = stringPreferencesKey("user")
    val lastUpdate = stringPreferencesKey("last_update")
}

class StatsWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetContent()
        }
    }

    @Composable
    private fun WidgetContent() {
        val prefs = currentState<Preferences>()
        val temp  = prefs[StatsWidgetKeys.temp] ?: 0.0
        val cpu   = prefs[StatsWidgetKeys.cpu] ?: 0
        val ramU  = prefs[StatsWidgetKeys.ramUsed] ?: 0
        val ramT  = prefs[StatsWidgetKeys.ramTotal] ?: 0
        val host  = prefs[StatsWidgetKeys.host] ?: "pi"
        val user  = prefs[StatsWidgetKeys.user] ?: "pi"
        val time  = prefs[StatsWidgetKeys.lastUpdate] ?: "--:--"

        val terminalGreen = Color(0xFF39FF14)
        val cardBg        = Color(0xFF161C22)
        val textSecondary = Color(0xFF9E9E9E)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(cardBg)
                .cornerRadius(16.dp)
                .clickable(actionRunCallback<RefreshActionCallback>())
                .padding(12.dp),
            verticalAlignment = Alignment.Vertical.Top,
            horizontalAlignment = Alignment.Horizontal.Start
        ) {
            // Header: Dot + User@Host
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .size(8.dp)
                        .cornerRadius(4.dp)
                        .background(terminalGreen),
                    content = {}
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = "$user@$host",
                    style = TextStyle(color = ColorProvider(day = terminalGreen, night = terminalGreen), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                )
            }

            Spacer(GlanceModifier.height(12.dp))

            // Main Stats Row
            Row(
                modifier = GlanceModifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f)).cornerRadius(12.dp).padding(8.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                StatItem("🌡️", "${temp}°C", "Temp CPU", terminalGreen, GlanceModifier.defaultWeight())
                Box(
                    modifier = GlanceModifier.width(1.dp).height(35.dp).background(Color.White.copy(alpha = 0.1f)),
                    content = {}
                )
                StatItem("⚙️", "$cpu%", "CPU", terminalGreen, GlanceModifier.defaultWeight())
                Box(
                    modifier = GlanceModifier.width(1.dp).height(35.dp).background(Color.White.copy(alpha = 0.1f)),
                    content = {}
                )
                StatItem("📊", "$ramU MB", "RAM · ${if (ramT > 0) (ramU * 100 / ramT) else 0}%", terminalGreen, GlanceModifier.defaultWeight())
            }

            Spacer(GlanceModifier.height(16.dp))

            // Progress Bars Group
            Column(modifier = GlanceModifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f)).cornerRadius(12.dp).padding(8.dp)) {
                ProgressBar("CPU", cpu / 100f, terminalGreen)
                Spacer(GlanceModifier.height(8.dp))
                val ramPct = if (ramT > 0) ramU.toFloat() / ramT else 0f
                ProgressBar("RAM", ramPct, terminalGreen)
            }

            // On occupe l'espace restant pour permettre le centrage si on veut,
            Spacer(GlanceModifier.defaultWeight())

            Text(
                text = "Mise à jour toutes les 5 s · $time",
                style = TextStyle(color = ColorProvider(day = textSecondary, night = textSecondary), fontSize = 10.sp)
            )
        }
    }

    @Composable
    private fun StatItem(icon: String, value: String, label: String, color: Color, modifier: GlanceModifier = GlanceModifier) {
        val colorProvider = ColorProvider(day = color, night = color)
        val labelColor = ColorProvider(day = Color.White.copy(alpha = 0.7f), night = Color.White.copy(alpha = 0.7f))
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally
        ) {
            Text(text = icon, style = TextStyle(fontSize = 16.sp))
            Text(text = value, style = TextStyle(color = colorProvider, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Text(text = label, style = TextStyle(color = labelColor, fontSize = 10.sp))
        }
    }

    @Composable
    private fun ProgressBar(label: String, progress: Float, color: Color) {
        val colorProvider = ColorProvider(day = color, night = color)
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = GlanceModifier.width(40.dp),
                style = TextStyle(color = ColorProvider(day = Color.White, night = Color.White), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            )
            LinearProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = GlanceModifier.defaultWeight().height(8.dp),
                color = colorProvider,
                backgroundColor = ColorProvider(day = Color.White.copy(alpha = 0.1f), night = Color.White.copy(alpha = 0.1f))
            )
        }
    }
}

// ── Callback de rafraîchissement ──────────────────────────────────────────────
class RefreshActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        UpdateStatsWorker.enqueue(context, force = true)
    }
}

// ── Receiver ──────────────────────────────────────────────────────────────────
class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsWidget()

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetUpdateService.start(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        UpdateStatsWorker.enqueue(context)
        UpdateStatsWorker.schedulePeriodic(context)
        WidgetUpdateService.start(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdateService.stop(context)
    }
}

// ── Worker pour mettre à jour les stats ──────────────────────────────────────
class UpdateStatsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    companion object {
        fun enqueue(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UpdateStatsWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                "update_stats_widget_manual",
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateStatsWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "update_stats_widget_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = SettingsManager(context)
        
        if (!settings.isConfigured()) return Result.failure()

        // Check if user is unlocked to avoid IllegalStateException: User 0 must be unlocked
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.w("Widget", "UpdateStatsWorker: User is locked, skipping widget update")
            return Result.retry()
        }

        val stats = fetchSystemStats(settings) ?: return Result.retry()

        val glanceId = GlanceAppWidgetManager(context).getGlanceIds(StatsWidget::class.java).firstOrNull()
        
        if (glanceId != null) {
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[StatsWidgetKeys.temp]      = stats.tempCelsius
                prefs[StatsWidgetKeys.cpu]       = stats.cpuPercent
                prefs[StatsWidgetKeys.ramUsed]   = stats.ramUsedMb
                prefs[StatsWidgetKeys.ramTotal]  = stats.ramTotalMb
                prefs[StatsWidgetKeys.host]      = settings.host
                prefs[StatsWidgetKeys.user]      = settings.username
                prefs[StatsWidgetKeys.lastUpdate] = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            }
            StatsWidget().update(context, glanceId)
        }

        return Result.success()
    }
}
