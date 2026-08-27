package com.rillmaster.pipanel.ssh

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.util.UUID

data class SshKey(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val privateKey: String,
    val publicKey: String,
    val type: String
)

object SshKeyManager {
    
    fun generateKeyPair(name: String, type: Int = KeyPair.ED25519): SshKey {
        val jsch = JSch()
        
        val keyTypeStr: String
        val privOut = ByteArrayOutputStream()
        val pubOut = ByteArrayOutputStream()

        val kp = if (type == KeyPair.RSA) {
            val k = KeyPair.genKeyPair(jsch, type, 4096)
            keyTypeStr = "RSA"
            k
        } else {
            val k = KeyPair.genKeyPair(jsch, type)
            keyTypeStr = "ED25519"
            k
        }
        
        kp.writePrivateKey(privOut)
        kp.writePublicKey(pubOut, name)
        
        val key = SshKey(
            name = name,
            privateKey = privOut.toString("UTF-8"),
            publicKey = pubOut.toString("UTF-8"),
            type = keyTypeStr
        )
        kp.dispose()
        return key
    }

    // Storage logic will be integrated into SettingsManager or a separate KeyStore-backed file
}
