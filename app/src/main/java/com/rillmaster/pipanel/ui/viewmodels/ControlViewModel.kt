package com.rillmaster.pipanel.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.model.SystemStats
import com.rillmaster.pipanel.model.fetchSystemStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class ControlUiState(
    val systemStats: SystemStats? = null,
    val statsLoading: Boolean = true,
    val connectionAttempts: Int = 0
)

class ControlViewModel(
    private val settings: SettingsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

    init {
        startStatsJob()
    }

    private fun startStatsJob() {
        viewModelScope.launch {
            while (true) {
                if (_uiState.value.systemStats == null && _uiState.value.connectionAttempts < 2) {
                    _uiState.update { it.copy(statsLoading = true) }
                }

                val result = fetchSystemStats(settings)

                if (result != null) {
                    _uiState.update { it.copy(
                        systemStats = result,
                        connectionAttempts = 0,
                        statsLoading = false
                    ) }
                } else {
                    val newAttempts = _uiState.value.connectionAttempts + 1
                    _uiState.update { it.copy(
                        connectionAttempts = newAttempts,
                        systemStats = if (newAttempts >= 2) null else it.systemStats,
                        statsLoading = false
                    ) }
                }
                delay(settings.tempRefreshMs.milliseconds)
            }
        }
    }
}
