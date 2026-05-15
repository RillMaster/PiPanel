package com.example.raspberrycontroller

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream

class ShellSession(
    private val session: Session,
    private val channel: ChannelShell,
) {
    private val writer: BufferedWriter = channel.outputStream.bufferedWriter(Charsets.UTF_8)

    // Stream brut pour les caractères de contrôle (Ctrl+C, flèches, etc.)
    private val rawOut: OutputStream = channel.outputStream

    val inputStream: InputStream = channel.inputStream
    val isConnected get() = channel.isConnected && !channel.isClosed

    /** Envoie une commande texte suivie d'un retour à la ligne. */
    @Suppress("unused")
    suspend fun send(command: String) = withContext(Dispatchers.IO) {
        runCatching {
            writer.write(command + "\n")
            writer.flush()
        }.onFailure { it.printStackTrace() }
    }

    /**
     * Envoie des octets bruts sans newline — pour les séquences de contrôle
     * (Ctrl+C = \u0003, flèche haut = \u001B[A, Tab = \u0009, etc.)
     */
    suspend fun sendRaw(bytes: String) = withContext(Dispatchers.IO) {
        runCatching {
            rawOut.write(bytes.toByteArray(Charsets.UTF_8))
            rawOut.flush()
        }.onFailure { it.printStackTrace() }
    }

    fun close() {
        runCatching { writer.close() }
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

            else -> context.getString(R.string.ssh_error_generic, msg)
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
        context: Context? = null
    ): String = withContext(Dispatchers.IO) {
        android.util.Log.e("SSH", "SSH: Tentative connexion $host:$port ($user)...")
        try {
            val jsch = JSch()
            val session = jsch.getSession(user, host, port)
            @Suppress("DEPRECATION")
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(timeoutMs)

            android.util.Log.e("SSH", "SSH: Connecté ! Exécution: ${command.take(60)}...")
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stdout = channel.inputStream
            val stderr = channel.errStream
            channel.connect()

            val output = StringBuilder()
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 10_000

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
            val errorMsg = if (context != null) parseError(context, e) else e.message ?: "SSH Error"
            android.util.Log.e("SSH", "SSH: ERREUR - $errorMsg")
            errorMsg
        }
    }

    // ─── Ouverture d'un shell interactif ─────────────────────────────────────────
    suspend fun openShell(
        host: String,
        port: Int = 22,
        user: String,
        password: String,
    ): Result<ShellSession> = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            val session = jsch.getSession(user, host, port)
            @Suppress("DEPRECATION")
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(8000)

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("vt100")
            channel.setPtySize(200, 50, 1000, 500)
            channel.connect()

            ShellSession(session, channel)
        }
    }
}