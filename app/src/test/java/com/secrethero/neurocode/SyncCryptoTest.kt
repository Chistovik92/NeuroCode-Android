package com.secrethero.neurocode

import android.util.Base64
import com.secrethero.neurocode.sync.SyncCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_PADDING or Base64.NO_WRAP)

    @Test
    fun encryptDecryptRoundTrip() {
        val key = SyncCrypto.generateKey()
        val plaintext = "диалог с агентом: привет, мир".toByteArray(Charsets.UTF_8)
        val envelope = SyncCrypto.encrypt(key, plaintext)
        val decrypted = SyncCrypto.decrypt(key, envelope)
        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)
        assertTrue(envelope.contains(SyncCrypto.ENVELOPE_VERSION))
    }

    @Test
    fun wrongKeyFailsToDecrypt() {
        val envelope = SyncCrypto.encrypt(SyncCrypto.generateKey(), "secret".toByteArray())
        assertNull(SyncCrypto.decrypt(SyncCrypto.generateKey(), envelope))
    }

    @Test
    fun samePlaintextDifferentCiphertext() {
        val key = SyncCrypto.generateKey()
        val plain = "x".toByteArray()
        assertNotEquals(SyncCrypto.encrypt(key, plain), SyncCrypto.encrypt(key, plain))
    }

    @Test
    fun keyPhraseRoundTrip() {
        val key = SyncCrypto.generateKey()
        val phrase = SyncCrypto.encodeKey(key)
        // Группы по 4 символа через пробел — 43 символа base64url без padding.
        assertTrue(phrase.contains(" "))
        assertEquals(43, phrase.replace(" ", "").length)
        assertArrayEquals(key, SyncCrypto.decodeKey(phrase))
    }

    @Test
    fun decodeKeyToleratesWhitespaceOnly() {
        val key = SyncCrypto.generateKey()
        val phrase = SyncCrypto.encodeKey(key)
        // Пробелы при вводе игнорируются; регистр важен (base64url регистрозависим).
        assertArrayEquals(key, SyncCrypto.decodeKey(phrase.replace(" ", "")))
        assertArrayEquals(key, SyncCrypto.decodeKey("  $phrase  "))
    }

    @Test
    fun decodeKeyRejectsGarbage() {
        assertNull(SyncCrypto.decodeKey("не ключ"))
        assertNull(SyncCrypto.decodeKey(""))
    }
}
