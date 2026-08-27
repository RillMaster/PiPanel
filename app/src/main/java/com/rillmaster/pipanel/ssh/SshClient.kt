package com.rillmaster.pipanel.ssh

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Utility object for SSH operations using JSch.
 */
object SshClient {

    /**
     * Parses an SSH exception into a user-friendly error message.
     * @param context Android context for string resources (can be null)
     * @param e The exception/throwable to parse
     * @return User-friendly error message
     */
    fun parseError(context: Context?, e: Throwable): String {
        val msg = e.message ?: "Unknown error"
        return when (e) {
            is JSchException -> {
                when {
                    msg.contains("Auth fail", ignoreCase = true) -> context?.getString(R.string.ssh_error_auth) ?: "Authentication failed"
                    msg.contains("connect", ignoreCase = true) -> context?.getString(R.string.ssh_error_unreachable) ?: "Connection failed"
                    msg.contains("timeout", ignoreCase = true) -> context?.getString(R.string.ssh_error_timeout) ?: "Connection timeout"
                    else -> context?.getString(R.string.ssh_error_generic, msg) ?: "SSH error: $msg"
                }
            }
            is SftpException -> {
                when (e.id) {
                    ChannelSftp.SSH_FX_NO_SUCH_FILE -> "File not found"
                    ChannelSftp.SSH_FX_PERMISSION_DENIED -> "Permission denied"
                    else -> context?.getString(R.string.ssh_error_generic, msg) ?: "SFTP error: ${e.id}"
                }
            }
            else -> context?.getString(R.string.ssh_error_generic, msg) ?: "Error: $msg"
        }
    }

    // ─── Exécution d'une commande unique ─────────────────────────────────────────
    suspend fun execute(
        host: String,
        port: Int = 22,
        user: String,
        password: String,
        command: String,
        timeoutMs: Int = 8000,
        context: Context? = null,
        privateKey: String = "",
        keyPassphrase: String = ""
    ): String = withContext(Dispatchers.IO) {
        try {
            val session = SshSessionManager.getOrCreateSession(
                host, port, user, password, privateKey, keyPassphrase, timeoutMs
            )

            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stdout = channel.inputStream
            val stderr = channel.errStream
            channel.connect()

            val output = StringBuilder()
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(10_000)

            while (System.currentTimeMillis() < deadline) {
                while (stdout.available() > 0) {
                    val n = stdout.read(buffer)
                    if (n > 0) output.append(String(buffer, 0, n, Charsets.UTF_8))
                }
                while (stderr.available() > 0) {
                    val n = stderr.read(buffer)
                    if (n > 0) output.append("[err] ${String(buffer, 0, n, Charsets.UTF_8)}")
                }
                if ((channel.isClosed) && (stdout.available() == 0)) break
                delay(100)
            }

            channel.disconnect()
            output.toString().trim()

        } catch (e: Exception) {
            val errorMsg = parseError(context, e)
            Log.e("SSH", "SSH: ERREUR - $errorMsg")
            if (errorMsg.startsWith("[err]")) errorMsg else "[err] $errorMsg"
        }
    }

    // ─── Ouverture d'un shell interactif ───────────────────────────────────────
    suspend fun openShell(
        host: String,
        port: Int = 22,
        user: String,
        password: String,
        privateKey: String = "",
        keyPassphrase: String = "",
        context: Context? = null,
        cols: Int = 80,
        rows: Int = 24,
        initialCols: Int = cols,
        initialRows: Int = rows
    ): Result<ShellSession> = withContext(Dispatchers.IO) {
        try {
            val session = SshSessionManager.getOrCreateSession(
                host, port, user, password, privateKey, keyPassphrase, 10_000
            )

            val channel = session.openChannel("shell") as ChannelShell
            val stdin = channel.outputStream
            val stdout = channel.inputStream
            channel.setPtySize(initialCols, initialRows, 0, 0)
            channel.connect()

            val shellSession = ShellSession(session, channel, stdout, stdin)
            Result.success(shellSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── Opérations SFTP ───────────────────────────────────────────────────────
    suspend fun <T> sftpAction(
        settings: SettingsManager,
        block: suspend (ChannelSftp) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val session = SshSessionManager.getOrCreateSession(
                settings.host,
                settings.port,
                settings.username,
                settings.password,
                settings.privateKey,
                settings.keyPassphrase,
                settings.sshTimeoutMs
            )

            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect()

            try {
                val result = block(channel)
                Result.success(result)
            } finally {
                channel.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── Téléchargement de fichier via SFTP ────────────────────────────────────
    suspend fun getInputStream(
        settings: SettingsManager,
        remotePath: String
    ): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val session = SshSessionManager.getOrCreateSession(
                settings.host,
                settings.port,
                settings.username,
                settings.password,
                settings.privateKey,
                settings.keyPassphrase,
                settings.sshTimeoutMs
            )

            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect()

            try {
                val input = channel.get(remotePath)
                Result.success(object : FilterInputStream(input) {
                    override fun close() {
                        super.close()
                        channel.disconnect()
                    }
                })
            } catch (e: SftpException) {
                channel.disconnect()
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Session shell interactive avec flux d'entrée/sortie.
     */
    class ShellSession(
        private val session: Session,
        private val channel: ChannelShell,
        val inputStream: InputStream,
        private val rawOut: OutputStream
    ) {
        val isConnected get() = channel.isConnected && !channel.isClosed && session.isConnected

        /** Envoie une commande texte suivie d'un retour à la ligne. */
        suspend fun send(command: String) = sendRaw(command + "\n")

        /**
         * Envoie des octets bruts sans newline — pour les séquences de contrôle
         * (Ctrl+C = \u0003, flèche haut = \u001B[A, Tab = \u0009, etc.)
         */
        suspend fun sendRaw(bytes: String) = withContext(Dispatchers.IO) {
            if (!isConnected) return@withContext
            runCatching {
                rawOut.write(bytes.toByteArray(Charsets.UTF_8))
                rawOut.flush()
            }.onFailure { 
                Log.e("ShellSession", "Failed to send data: ${it.message}")
            }
        }

        fun setWindowSize(cols: Int, rows: Int) {
            if (!isConnected) return
            try {
                channel.setPtySize(cols, rows, 0, 0)
            } catch (_: Exception) {}
        }

        fun close() {
            try {
                channel.disconnect()
                session.disconnect()
            } catch (_: Exception) {}
        }
    }
}