package com.secrethero.neurocode.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * E2E-шифрование синхронизации: AES-256-GCM на ключе синхронизации, который сервер
 * не получает никогда. Ключ — 32 случайных байта, поэтому PBKDF2 не нужен
 * (см. docs/PLAN-0.9.5.md).
 *
 * Формат конверта `sync-envelope-v1`: JSON с base64 IV и шифртекстом; версия позволяет
 * менять схему, не ломая старые записи.
 */
object SyncCrypto {
    const val ENVELOPE_VERSION = "sync-envelope-v1"
    private const val KEY_SIZE_BYTES = 32
    private const val IV_SIZE_BYTES = 12
    private const val TAG_BITS = 128

    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Envelope(val version: String, val iv: String, val ciphertext: String)

    /** Новый ключ синхронизации: 32 байта SecureRandom. */
    fun generateKey(): ByteArray = ByteArray(KEY_SIZE_BYTES).also {
        SecureRandom().nextBytes(it)
    }

    /**
     * Ключ в виде фразы для показа и ввода: base64url без padding, разбитый на группы
     * по 4 символа через пробел — проще читать вслух и вводить.
     */
    fun encodeKey(key: ByteArray): String {
        require(key.size == KEY_SIZE_BYTES) { "Ключ синхронизации должен быть 32 байта" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            .chunked(4)
            .joinToString(" ")
    }

    /** Обратная операция к [encodeKey]; пробелы и регистр игнорируются. */
    fun decodeKey(phrase: String): ByteArray? = runCatching {
        val compact = phrase.replace(" ", "").trim()
        require(compact.length == 43)
        Base64.getUrlDecoder().decode(compact)
            .also { require(it.size == KEY_SIZE_BYTES) }
    }.getOrNull()

    fun encrypt(key: ByteArray, plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = Envelope(
            version = ENVELOPE_VERSION,
            iv = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        )
        return json.encodeToString(Envelope.serializer(), envelope)
    }

    fun decrypt(key: ByteArray, envelope: String): ByteArray? = runCatching {
        val parsed = json.decodeFromString(Envelope.serializer(), envelope)
        require(parsed.version == ENVELOPE_VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(parsed.iv)),
        )
        cipher.doFinal(Base64.getDecoder().decode(parsed.ciphertext))
    }.getOrNull()
}
