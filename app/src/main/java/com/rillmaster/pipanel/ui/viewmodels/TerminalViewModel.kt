package com.rillmaster.pipanel.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.ShellSession
import com.rillmaster.pipanel.SshClient
import com.rillmaster.pipanel.SshShortcut
import com.rillmaster.pipanel.ui.terminal.CommandHistory
import com.rillmaster.pipanel.ui.terminal.TerminalEmulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

data class TerminalUiState(
    val status: String = "",
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    val renderTick: Int = 0
)

class TerminalViewModel(
    private val settings: SettingsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private var session: ShellSession? = null
    val emulator = TerminalEmulator(80, 24)
    val commandHistory = CommandHistory()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val profileId = settings.currentProfileId ?: "default"
        settings.getCommandHistory(profileId).forEach { commandHistory.add(it) }
    }
    
    private var connectJob: Job? = null
    private var readJob: Job? = null
    private var userClosedManually = false

    private val MAX_RECONNECT_ATTEMPTS = 10
    private val BASE_RECONNECT_DELAY_MS = 2000L
    private val MAX_RECONNECT_DELAY_MS = 30000L

    private fun getNextDelay(attempt: Int): Long {
        val exponentialDelay = (BASE_RECONNECT_DELAY_MS * Math.pow(2.0, (attempt - 1).toDouble())).toLong()
        val cappedDelay = exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
        val jitter = (cappedDelay * 0.2 * (Math.random() * 2 - 1)).toLong()
        return cappedDelay + jitter
    }

    fun connect(context: Context) {
        userClosedManually = false
        _uiState.update { it.copy(status = context.getString(R.string.status_connecting)) }
        connectSsh(context)
    }

    fun disconnect() {
        userClosedManually = true
        connectJob?.cancel()
        readJob?.cancel()
        session?.close()
        session = null
        _uiState.update { it.copy(isConnected = false, isReconnecting = false) }
    }

    private fun connectSsh(context: Context) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val result = SshClient.openShell(
                host = settings.host,
                port = settings.port,
                user = settings.username,
                password = settings.password,
                privateKey = settings.privateKey,
                keyPassphrase = settings.keyPassphrase
            )

            result.onSuccess { sh ->
                session = sh
                _uiState.update { it.copy(
                    status = context.getString(R.string.status_connected_terminal),
                    isConnected = true,
                    isReconnecting = false,
                    reconnectAttempt = 0
                ) }
                startReading(sh, context)
            }.onFailure { err ->
                _uiState.update { it.copy(isConnected = false) }
                session = null
                if (!userClosedManually && _uiState.value.reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                    _uiState.update { it.copy(
                        isReconnecting = true,
                        status = context.getString(R.string.status_reconnecting, it.reconnectAttempt + 1, MAX_RECONNECT_ATTEMPTS)
                    ) }
                    val currentAttempt = _uiState.value.reconnectAttempt + 1
                    delay(getNextDelay(currentAttempt).milliseconds)
                    if (!userClosedManually) {
                        _uiState.update { it.copy(reconnectAttempt = currentAttempt) }
                        connectSsh(context)
                    }
                } else if (!userClosedManually) {
                    _uiState.update { it.copy(
                        isReconnecting = false,
                        status = context.getString(R.string.status_error)
                    ) }
                    emulator.process(SshClient.parseError(context, err) + "\r\n")
                    emulator.process(context.getString(R.string.msg_check_ssh_settings) + "\r\n")
                    _uiState.update { it.copy(renderTick = it.renderTick + 1) }
                }
            }
        }
    }

    private fun startReading(sh: ShellSession, context: Context) {
        readJob?.cancel()
        readJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(16384)
            val inputStream = sh.inputStream
            
            while (sh.isConnected) {
                val n = try { inputStream.read(buffer) } catch (_: Exception) { -1 }
                if (n < 0) break
                if (n > 0) {
                    val text = String(buffer, 0, n, Charsets.UTF_8)
                    withContext(Dispatchers.Main) {
                        emulator.process(text)
                        _uiState.update { it.copy(renderTick = it.renderTick + 1) }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isConnected = false) }
                session = null
                if (!userClosedManually && _uiState.value.reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                    _uiState.update { it.copy(
                        isReconnecting = true,
                        status = context.getString(R.string.status_reconnecting, it.reconnectAttempt + 1, MAX_RECONNECT_ATTEMPTS)
                    ) }
                    val currentAttempt = _uiState.value.reconnectAttempt + 1
                    delay(getNextDelay(currentAttempt).milliseconds)
                    if (!userClosedManually) {
                        _uiState.update { it.copy(reconnectAttempt = currentAttempt) }
                        connectSsh(context)
                    }
                } else {
                    val msgReconnFailed = context.getString(R.string.msg_reconnect_failed, _uiState.value.reconnectAttempt)
                    _uiState.update { it.copy(
                        isReconnecting = false,
                        status = context.getString(R.string.status_disconnected)
                    ) }
                    emulator.process("\r\n\u001B[31m$msgReconnFailed\u001B[0m\r\n")
                    _uiState.update { it.copy(renderTick = it.renderTick + 1) }
                }
            }
        }
    }

    fun sendRaw(bytes: String) {
        val sess = session
        if (sess != null && _uiState.value.isConnected) {
            viewModelScope.launch { sess.sendRaw(bytes) }
        }
    }

    fun sendCommand(command: String) {
        val sess = session
        if (command.isNotBlank()) {
            commandHistory.add(command)
            val profileId = settings.currentProfileId ?: "default"
            settings.saveCommandHistory(profileId, commandHistory.entries)
        }
        if (sess != null && _uiState.value.isConnected) {
            viewModelScope.launch { sess.send(command) }
        }
    }

    fun runShortcut(shortcut: SshShortcut) {
        if (!_uiState.value.isConnected) return
        viewModelScope.launch {
            shortcut.commands.forEach { cmd ->
                if (cmd.contains("{{input}}")) {
                    // Logic for input handled in UI for now
                } else {
                    session?.send(cmd)
                }
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        emulator.resize(cols, rows)
        session?.setWindowSize(cols, rows)
        _uiState.update { it.copy(renderTick = it.renderTick + 1) }
    }
    
    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
