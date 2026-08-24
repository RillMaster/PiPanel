package com.rillmaster.pipanel

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Vector

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
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

object SshClient {

    /**
     * Traduit une exception JSch en message lisible par l'utilisateur.
     */
    fun parseError(context: Context, e: Throwable): String {
        val msg = e.message ?: "???"
        return when {
            (e is JSchException) && (msg.contains("Auth fail", ignoreCase = true)
                    || msg.contains("auth cancel", ignoreCase = true)) ->
                context.getString(R.string.ssh_error_auth)

            (e is JSchException) && (msg.contains("UnknownHost", ignoreCase = true)
                    || msg.contains("unable to resolve", ignoreCase = true)
                    || msg.contains("nodename nor servname", ignoreCase = true)) ->
                context.getString(R.string.ssh_error_host, msg)

            (e is JSchException) && (msg.contains("timeout", ignoreCase = true)
                    || msg.contains("timed out", ignoreCase = true)) ->
                context.getString(R.string.ssh_error_timeout)

            (e is JSchException) && msg.contains("Connection refused", ignoreCase = true) ->
                context.getString(R.string.ssh_error_refused)

            (e is JSchException) && msg.contains("No route to host", ignoreCase = true) ->
                context.getString(R.string.ssh_error_unreachable)

            (e is JSchException) && (msg.contains("Connection reset", ignoreCase = true)
                    || msg.contains("Broken pipe", ignoreCase = true)) ->
                context.getString(R.string.ssh_error_broken)

            (e is JSchException) && msg.contains("channel is not opened", ignoreCase = true) ->
                context.getString(R.string.ssh_error_channel)

            msg.contains("ECONNREFUSED", ignoreCase = true) ->
                context.getString(R.string.ssh_error_refused)
            msg.contains("ETIMEDOUT", ignoreCase = true) ->
                context.getString(R.string.ssh_error_timeout)
            msg.contains("ENETUNREACH", ignoreCase = true) ->
                context.getString(R.string.ssh_error_unreachable)

            (e is SftpException) -> "SFTP Error: ${e.message} (Code: ${e.id})"

            else -> context.getString(R.string.ssh_error_generic, msg)
        }
    }

    /**
     * Crée une session JSch authentifiée — par clé privée si fournie,
     * sinon par mot de passe.
     */
    private fun createSession(
        jsch: JSch,
        user: String,
        host: String,
        port: Int,
        password: String,
        privateKey: String = "",
        keyPassphrase: String = ""
    ): Session {
        if (privateKey.isNotBlank()) {
            jsch.addIdentity(
                "pipanel_key",
                privateKey.toByteArray(Charsets.UTF_8),
                null,
                if (keyPassphrase.isBlank()) null else keyPassphrase.toByteArray(Charsets.UTF_8)
            )
        }
        val session = jsch.getSession(user, host, port)
        if (privateKey.isBlank()) {
            @Suppress("DEPRECATION")
            session.setPassword(password)
        }
        session.setConfig("StrictHostKeyChecking", "no")
        // Auth par clé uniquement si clé présente (évite les prompts interactifs)
        if (privateKey.isNotBlank()) {
            session.setConfig("PreferredAuthentications", "publickey")
        }
        return session
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
        android.util.Log.e("SSH", "SSH: Tentative connexion $host:$port ($user)...")
        try {
            val jsch = JSch()
            val session = createSession(jsch, user, host, port, password, privateKey, keyPassphrase)
            session.connect(timeoutMs)

            android.util.Log.e("SSH", "SSH: Connecté ! Exécution: ${command.take(60)}...")
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
                Thread.sleep(100)
            }

            channel.disconnect()
            session.disconnect()

            val result = output.toString().trim()
            android.util.Log.e("SSH", "SSH: Terminé. Sortie=${if (result.length > 50) result.take(50) + "..." else result}")
            result

        } catch (e: Exception) {
            val errorMsg = context?.let { parseError(it, e) } ?: (e.message ?: "SSH Error")
            android.util.Log.e("SSH", "SSH: ERREUR - $errorMsg")
            if (errorMsg.startsWith("[err]")) errorMsg else "[err] $errorMsg"
        }
    }

    // ─── Ouverture d'un shell interactif ─────────────────────────────────────────
    suspend fun openShell(
        host: String,
        port: Int = 22,
        user: String,
        password: String,
        privateKey: String = "",
        keyPassphrase: String = ""
    ): Result<ShellSession> = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            val session = createSession(jsch, user, host, port, password, privateKey, keyPassphrase)
            session.setServerAliveInterval(30000)
            session.connect(8000)

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm")
            channel.setPtySize(80, 24, 0, 0)

            val ins = channel.inputStream
            val outs = channel.outputStream
            
            session.setServerAliveInterval(30000)
            session.setServerAliveCountMax(3)
            
            channel.connect(10000)

            ShellSession(session, channel, ins, outs)
        }
    }

    // ─── SFTP ────────────────────────────────────────────────────────────────────
    suspend fun <T> sftpAction(
        settings: SettingsManager,
        action: suspend (ChannelSftp) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelSftp? = null
        try {
            val jsch = JSch()
            session = createSession(
                jsch, settings.username, settings.host, settings.port,
                settings.password, settings.privateKey, settings.keyPassphrase
            )
            session.connect(8000)

            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect()

            Result.success(action(channel))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
    }

    /**
     * Ouvre un flux SFTP pour lecture (Streaming).
     */
    suspend fun getInputStream(settings: SettingsManager, remotePath: String): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val jsch = JSch()
            val session = createSession(
                jsch, settings.username, settings.host, settings.port,
                settings.password, settings.privateKey, settings.keyPassphrase
            )
            session.connect(8000)

            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect()

            val stream = channel.get(remotePath)
            // On renvoie un InputStream qui fermera la session SFTP à la fermeture
            val wrappedStream = object : java.io.FilterInputStream(stream) {
                override fun close() {
                    super.close()
                    channel.disconnect()
                    session.disconnect()
                }
            }
            Result.success(wrappedStream)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}