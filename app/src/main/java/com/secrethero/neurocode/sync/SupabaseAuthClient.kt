package com.secrethero.neurocode.sync

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Провайдеры входа 0.9.5: Google и GitHub ( Supabase называет их «google»/«github»). */
enum class AuthProvider(val supabaseName: String) {
    GOOGLE("google"),
    GITHUB("github"),
}

/** Успешный ответ обмена кода или обновления токена. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String,
    val email: String?,
    val provider: String?,
)

sealed interface AuthResult {
    data class Success(val session: AuthSession) : AuthResult
    data class Failure(val message: String) : AuthResult
}

/**
 * Клиент Supabase Auth (GoTrue) без SDK: URL авторизации с PKCE, обмен кода из редиректа,
 * обновление токена, выход. Работает поверх уже имеющегося OkHttp; anon-ключ публичный
 * по дизайну Supabase.
 */
class SupabaseAuthClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val anonKey: String,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun authorizeUrl(
        provider: AuthProvider,
        redirectUri: String,
        codeChallenge: String,
    ): String =
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("auth/v1/authorize")
            .addQueryParameter("provider", provider.supabaseName)
            .addQueryParameter("redirect_to", redirectUri)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "s256")
            .build()
            .toString()

    /** Обмен кода из редиректа `neurocode://oauth#…&code=…` на сессию (PKCE, RFC 7636). */
    suspend fun exchangeCode(code: String, verifier: String): AuthResult =
        tokenRequest(
            grantType = "pkce",
            fields = mapOf("auth_code" to code, "code_verifier" to verifier),
            errorPrefix = "Обмен кода не удался",
        )

    suspend fun refresh(refreshToken: String): AuthResult =
        tokenRequest(
            grantType = "refresh_token",
            fields = mapOf("refresh_token" to refreshToken),
            errorPrefix = "Обновление токена не удалось",
        )

    /**
     * Выход на сервере. Успех/неуспех здесь не влияет на локальный выход:
     * локальные токены стираются в любом случае (см. AccountRepository.signOut).
     */
    suspend fun signOut(accessToken: String): AuthResult {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("auth/v1/logout")
            .build()
            .toString()
        val request = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .post(EMPTY_BODY)
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    AuthResult.Success(dummySession())
                } else {
                    AuthResult.Failure("Выход не удался: HTTP ${response.code}")
                }
            }
        }.getOrElse { AuthResult.Failure("Выход не удался: ${it.message}") }
    }

    private suspend fun tokenRequest(
        grantType: String,
        fields: Map<String, String>,
        errorPrefix: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        runCatching {
            val builder = FormBody.Builder()
            fields.forEach { (name, value) -> builder.add(name, value) }
            val request = Request.Builder()
                .url(tokenUrl(grantType))
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .post(builder.build())
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AuthResult.Failure(describeError(response.code, text))
                } else {
                    AuthResult.Success(parseSession(JSONObject(text)))
                }
            }
        }.getOrElse { AuthResult.Failure("$errorPrefix: ${it.message}") }
    }

    private fun tokenUrl(grantType: String): String =
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("auth/v1/token")
            .addQueryParameter("grant_type", grantType)
            .build()
            .toString()

    private fun parseSession(json: JSONObject): AuthSession {
        val user = json.optJSONObject("user")
        return AuthSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAtMillis = now() + json.optLong("expires_in", 3600) * 1000,
            userId = user?.optString("id").orEmpty(),
            email = user?.optString("email")?.takeIf { it.isNotEmpty() },
            provider = user?.optString("app_metadata_provider")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun describeError(code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = json?.optString("error_description")?.takeIf { it.isNotEmpty() }
            ?: json?.optString("msg")?.takeIf { it.isNotEmpty() }
            ?: json?.optString("error")?.takeIf { it.isNotEmpty() }
            ?: body.take(200)
        return "HTTP $code: $message"
    }

    /** Заглушка для результата signOut: реальная сессия после выхода не нужна. */
    private fun dummySession() = AuthSession("", "", 0, "", null, null)

    private companion object {
        val EMPTY_BODY: okhttp3.RequestBody = FormBody.Builder().build()
    }
}

/** PKCE (RFC 7636): verifier — 64 случайных байта в base64url, challenge — SHA-256. */
object Pkce {
    fun newVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
