package com.rillmaster.pipanel

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.rillmaster.pipanel.ssh.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.util.UUID

data class BackupData(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val profiles: List<PiProfile>,
    val sshKeys: List<SshKey>,
    val globalShortcuts: List<SshShortcut>
)

object BackupManager {
    private val gson = Gson()

    suspend fun exportConfig(context: Context, uri: Uri, settings: SettingsManager): Boolean = withContext(Dispatchers.IO) {
        try {
            val data = BackupData(
                profiles = settings.profiles,
                sshKeys = settings.sshKeys,
                globalShortcuts = settings.sshShortcuts
            )
            val json = gson.toJson(data)
            // On peut chiffrer avec un mot de passe utilisateur si besoin, 
            // mais ici on utilise déjà le chiffrement interne pour les profils/clés.
            // Pour l'export externe, on pourrait demander un mdp.
            
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os).use { writer ->
                    writer.write(json)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun importConfig(context: Context, uri: Uri, settings: SettingsManager): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@withContext false
            val data = gson.fromJson(json, BackupData::class.java)
            
            // On fusionne ou on remplace ? Ici on remplace pour simplifier (Restore)
            settings.profiles = data.profiles
            settings.sshKeys = data.sshKeys
            settings.sshShortcuts = data.globalShortcuts
            true
        } catch (e: Exception) {
            false
        }
    }
}
