package com.secrethero.neurocode.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSecretStore(context: Context) {
    private val directory = File(context.filesDir, "secure").apply { mkdirs() }
    private val keyAlias = "neurocode-api-secrets-v1"

    @Synchronized
    fun put(name: String, value: String) {
        if (value.isBlank()) {
            delete(name)
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        secretFile(name).writeBytes(
            byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted,
        )
    }

    @Synchronized
    fun get(name: String): String? {
        val file = secretFile(name)
        if (!file.exists()) return null
        return runCatching {
            val payload = file.readBytes()
            val ivSize = payload.first().toInt() and 0xff
            require(ivSize in 12..16 && payload.size > ivSize + 1)
            val iv = payload.copyOfRange(1, ivSize + 1)
            val encrypted = payload.copyOfRange(ivSize + 1, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun delete(name: String) {
        secretFile(name).delete()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun secretFile(name: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.bin")
    }
}
