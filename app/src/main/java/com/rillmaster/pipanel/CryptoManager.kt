package com.rillmaster.pipanel

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement AES/GCM appuyé sur l'Android Keystore.
 * La clé ne quitte jamais le hardware sécurisé du téléphone.
 */
object CryptoManager {

    private const val KEY_ALIAS = "pipanel_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /**
     * Chiffre [plain] et retourne "iv:cipher" encodé en Base64.
     * Retourne [plain] inchangé s'il est vide (évite de chiffrer des chaînes vides).
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "$ivB64:$dataB64"
        }.getOrElse { plain }
    }

    /**
     * Déchiffre une valeur produite par [encrypt].
     * Si la valeur n'est pas au format "iv:cipher", elle est retournée telle quelle
     * (rétro-compatibilité avec les anciennes données en clair).
     */
    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return encoded
        val parts = encoded.split(":")
        if (parts.size != 2) return encoded // ancienne donnée en clair
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            if (iv.size != GCM_IV_LENGTH) return@runCatching encoded
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrElse { encoded }
    }
}
