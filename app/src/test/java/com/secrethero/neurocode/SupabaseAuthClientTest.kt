package com.secrethero.neurocode

import com.secrethero.neurocode.sync.AuthProvider
import com.secrethero.neurocode.sync.Pkce
import com.secrethero.neurocode.sync.SupabaseAuthClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseAuthClientTest {

    private val client = SupabaseAuthClient(
        http = OkHttpClient(),
        baseUrl = "https://ref.supabase.co",
        anonKey = "anon",
    )

    @Test
    fun authorizeUrlContainsProviderAndPkce() {
        val url = client.authorizeUrl(AuthProvider.GITHUB, "neurocode://oauth", "challenge123")
        assertTrue(url.startsWith("https://ref.supabase.co/auth/v1/authorize"))
        assertTrue(url.contains("provider=github"))
        assertTrue(url.contains("redirect_to=neurocode%3A%2F%2Foauth"))
        assertTrue(url.contains("code_challenge=challenge123"))
        assertTrue(url.contains("code_challenge_method=s256"))
    }

    @Test
    fun verifierIsBase64UrlWithoutPadding() {
        val verifier = Pkce.newVerifier()
        assertEquals(86, verifier.length)
        assertTrue(verifier.none { it == '=' || it == '+' || it == '/' })
    }

    @Test
    fun challengeMatchesRfc7636ExampleShape() {
        val verifier = Pkce.newVerifier()
        val challenge = Pkce.challenge(verifier)
        // SHA-256 → 32 байта → 43 символа base64url.
        assertEquals(43, challenge.length)
        // Повторный вызов детерминирован.
        assertEquals(challenge, Pkce.challenge(verifier))
    }
}
