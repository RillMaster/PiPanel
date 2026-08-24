package com.rillmaster.pipanel

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Sections personnalisables du tableau de bord (ControlScreen). */
enum class DashboardSection { STATS, GPIO, SERVICES, TERMINAL }

private val Context.dashboardDataStore by preferencesDataStore(name = "dashboard_prefs")

/**
 * Préférences de personnalisation du dashboard :
 * ordre des sections (liste CSV) et sections masquées (set de noms).
 */
object DashboardPrefs {

    private val KEY_ORDER  = stringPreferencesKey("section_order")
    private val KEY_HIDDEN = stringSetPreferencesKey("hidden_sections")

    /** Ordre par défaut des sections. */
    val DEFAULT_ORDER: List<DashboardSection> = DashboardSection.entries.toList()

    data class DashboardConfig(
        val order : List<DashboardSection> = DEFAULT_ORDER,
        val hidden: Set<DashboardSection>  = emptySet()
    )

    /** Flux de configuration : ordre persisté + visibilité. */
    fun flow(context: Context): Flow<DashboardConfig> =
        context.dashboardDataStore.data.map { prefs ->
            val savedOrder = prefs[KEY_ORDER]
                ?.split(",")
                ?.mapNotNull { name -> runCatching { DashboardSection.valueOf(name) }.getOrNull() }
                ?: DEFAULT_ORDER
            // Sécurité : on ajoute les sections manquantes à la fin, on retire les doublons
            val order = (savedOrder + DEFAULT_ORDER).distinct()
            val hidden = prefs[KEY_HIDDEN]
                ?.mapNotNull { name -> runCatching { DashboardSection.valueOf(name) }.getOrNull() }
                ?.toSet()
                ?: emptySet()
            DashboardConfig(order, hidden)
        }

    suspend fun saveOrder(context: Context, order: List<DashboardSection>) {
        context.dashboardDataStore.edit { prefs ->
            prefs[KEY_ORDER] = order.joinToString(",") { it.name }
        }
    }

    suspend fun setHidden(context: Context, section: DashboardSection, hidden: Boolean) {
        context.dashboardDataStore.edit { prefs ->
            val current = prefs[KEY_HIDDEN]?.toMutableSet() ?: mutableSetOf()
            if (hidden) current.add(section.name) else current.remove(section.name)
            prefs[KEY_HIDDEN] = current
        }
    }
}
