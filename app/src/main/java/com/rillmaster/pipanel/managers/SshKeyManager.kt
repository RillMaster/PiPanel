package com.rillmaster.pipanel.managers

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rillmaster.pipanel.CryptoManager
import com.rillmaster.pipanel.ssh.SshKey

class SshKeyManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("ssh_keys", Context.MODE_PRIVATE)
    private val gson = Gson()

    var keys: List<SshKey>
        get() {
            val json = prefs.getString("keys_list", null) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<SshKey>>() {}.type
                val decrypted = CryptoManager.decrypt(json)
                gson.fromJson(decrypted, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val json = CryptoManager.encrypt(gson.toJson(value))
            prefs.edit { putString("keys_list", json) }
        }

    fun addKey(key: SshKey) {
        val list = keys.toMutableList()
        list.add(key)
        keys = list
    }

    fun removeKey(id: String) {
        val list = keys.toMutableList()
        list.removeAll { it.id == id }
        keys = list
    }

    fun findKeyById(id: String): SshKey? {
        return keys.find { it.id == id }
    }

    fun updateKey(id: String, block: (SshKey) -> SshKey) {
        val list = keys.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list[index] = block(list[index])
            keys = list
        }
    }
}