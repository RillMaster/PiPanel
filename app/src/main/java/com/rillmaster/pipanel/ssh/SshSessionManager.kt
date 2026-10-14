package com.rillmaster.pipanel.ssh

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Gère les sessions SSH persistantes pour éviter de multiplier les connexions.
 * Implémente un système de multiplexage simple : une session par hôte/utilisateur.
 */
object SshSessionManager {
    private const val TAG = "SshSessionManager"
    private val sessions = ConcurrentHashMap<String, Session>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastAccess = ConcurrentHashMap<String, Long>()

    /**
     * Récupère une session connectée ou en crée une nouvelle.
     */
    suspend fun getOrCreateSession(
        host: String,
        port: Int,
        user: String,
        password: String,
        privateKey: String = "",
        keyPassphrase: String = "",
        timeoutMs: Int = 8000
    ): Session = withContext(Dispatchers.IO) {
        val key = "$user@$host:$port"
        val mutex = locks.getOrPut(key) { Mutex() }

        mutex.withLock {
            val session = sessions[key]
            lastAccess[key] = System.currentTimeMillis()

            if (session != null && session.isConnected) {
                return@withLock session
            }

            // Si la session est déconnectée, on la retire
            session?.disconnect()
            sessions.remove(key)

            Log.d(TAG, "Nouvelle connexion SSH demandée pour $key")
            val jsch = JSch()
            
            if (privateKey.isNotBlank()) {
                jsch.addIdentity(
                    "pipanel_key_$key",
                    privateKey.toByteArray(Charsets.UTF_8),
                    null,
                    if (keyPassphrase.isBlank()) null else keyPassphrase.toByteArray(Charsets.UTF_8)
                )
            }

            val newSession = jsch.getSession(user, host, port)
            if (privateKey.isBlank()) {
                newSession.setPassword(password)
            }
            newSession.setConfig("StrictHostKeyChecking", "no")
            if (privateKey.isNotBlank()) {
                newSession.setConfig("PreferredAuthentications", "publickey")
            }
            
            newSession.setServerAliveInterval(30000)
            newSession.setServerAliveCountMax(3)
            
            newSession.connect(timeoutMs)
            sessions[key] = newSession
            newSession
        }
    }

    /**
     * Ferme explicitement une session.
     */
    fun closeSession(host: String, port: Int, user: String) {
        val key = "$user@$host:$port"
        sessions.remove(key)?.disconnect()
        locks.remove(key)
    }

    /**
     * Ferme toutes les sessions actives.
     */
    fun closeAll() {
        sessions.values.forEach { it.disconnect() }
        sessions.clear()
        locks.clear()
        lastAccess.clear()
    }

    /**
     * Ferme les sessions qui n'ont pas été utilisées depuis [maxIdleMs].
     */
    fun cleanIdleSessions(maxIdleMs: Long = 60_000) {
        val now = System.currentTimeMillis()
        lastAccess.forEach { (key, time) ->
            if (now - time > maxIdleMs) {
                Log.d(TAG, "Fermeture de la session idle : $key")
                sessions.remove(key)?.disconnect()
                lastAccess.remove(key)
            }
        }
    }
}
