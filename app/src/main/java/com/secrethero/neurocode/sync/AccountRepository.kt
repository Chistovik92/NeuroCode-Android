package com.secrethero.neurocode.sync

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.secrethero.neurocode.data.CredentialKey
import com.secrethero.neurocode.data.CredentialStore
import com.secrethero.neurocode.data.SecureSecretStore
import com.secrethero.neurocode.model.AppSettings
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Кто вошёл — для экрана «Аккаунт». */
data class AccountUser(
    val userId: String,
    val email: String?,
    val provider: String?,
)

/** Состояние аккаунта: экран «Аккаунт» и SyncRepository подписываются на него. */
sealed interface AccountState {
    data object SignedOut : AccountState
    data object SigningIn : AccountState
    data class SignedIn(val user: AccountUser) : AccountState
    data class Error(val message: String) : AccountState
}

/** OAuth-поток 0.9.5: Custom Tab → `neurocode://oauth` → обмен кода → refresh-токен в Keystore. */
class AccountRepository(
    private val context: Context,
    private val credentials: CredentialStore,
    private val settingsProvider: suspend () -> AppSettings,
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
) {
    private val _state = MutableStateFlow<AccountState>(AccountState.SignedOut)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** Текущий пользователь, если сессия активна. */
    fun currentUser(): AccountUser? = (_state.value as? AccountState.SignedIn)?.user

    /** Verifier текущего OAuth-обмена: живёт в памяти только пока идёт вход. */
    private var pendingVerifier: String? = null
    private val mutex = Mutex()
    private var client: SupabaseAuthClient? = null

    /** Настроен ли бэкенд (URL + anon-ключ введены). */
    suspend fun isConfigured(): Boolean {
        val settings = settingsProvider()
        return settings.syncSupabaseUrl.isNotBlank() && settings.syncSupabaseAnonKey.isNotBlank()
    }

    private suspend fun authClient(): SupabaseAuthClient? {
        val settings = settingsProvider()
        if (!isConfigured()) return null
        return client ?: SupabaseAuthClient(
            http = okhttp3.OkHttpClient.Builder().build(),
            baseUrl = settings.syncSupabaseUrl.trimEnd('/'),
            anonKey = settings.syncSupabaseAnonKey,
        ).also { client = it }
    }

    /** Настройки бэкенда менялись — старый клиент сбрасывается. */
    fun invalidateClient() {
        client = null
    }

    /**
     * Ключ синхронизации: существующий или сгенерированный. Фраза показывается один раз,
     * вводится на втором устройстве; сервер ключа не получает.
     */
    suspend fun syncKeyPhrase(): String = mutex.withLock {
        val stored = credentials.get(CredentialKey.SyncKey)
        if (stored != null) {
            val existing = SyncCrypto.decodeKey(stored)
            if (existing != null) return SyncCrypto.encodeKey(existing)
            // Повреждённая запись: перегенерируем (ключ не был на сервере — данных нет).
        }
        val key = SyncCrypto.generateKey()
        credentials.put(CredentialKey.SyncKey, SyncCrypto.encodeKey(key))
        SyncCrypto.encodeKey(key)
    }

    /** Ввод ключа со второго устройства: проверяем, что фраза корректна, и сохраняем. */
    suspend fun importSyncKeyPhrase(phrase: String): Boolean {
        val key = SyncCrypto.decodeKey(phrase) ?: return false
        credentials.put(CredentialKey.SyncKey, SyncCrypto.encodeKey(key))
        return true
    }

    suspend fun syncKeyBytes(): ByteArray? {
        val stored = credentials.get(CredentialKey.SyncKey) ?: return null
        // Храним уже в кодировке фразы (encodeKey), чтобы не путать форматы.
        return SyncCrypto.decodeKey(stored)
    }

    /** Начало входа: открывает Custom Tab со страницей провайдера. */
    suspend fun signIn(provider: AuthProvider) {
        val auth = authClient() ?: run {
            _state.value = AccountState.Error("Синхронизация не настроена: укажите URL и anon-ключ")
            return
        }
        val verifier = Pkce.newVerifier()
        val challenge = Pkce.challenge(verifier)
        pendingVerifier = verifier
        _state.value = AccountState.SigningIn
        val redirect = "${context.packageName}://oauth"
        val url = auth.authorizeUrl(provider, redirect, challenge)
        val intent = CustomTabsIntent.Builder().build()
        intent.launchUrl(context, Uri.parse(url))
    }

    /**
     * Редирект `neurocode://oauth#…&code=…&state=…` — вызывается из MainActivity
     * (onCreate/onNewIntent). Завершает обмен кода и сохраняет refresh-токен.
     */
    suspend fun onOauthRedirect(uri: Uri) {
        if (uri.host != "oauth") return
        val fragment = uri.fragment.orEmpty()
        val params = fragment.split('&')
            .filter { it.contains('=') }
            .associate {
                val idx = it.indexOf('=')
                it.substring(0, idx) to Uri.decode(it.substring(idx + 1))
            }
        val error = params["error"]
        if (error != null) {
            _state.value = AccountState.Error(params["error_description"] ?: error)
            pendingVerifier = null
            return
        }
        val code = params["code"]
        val verifier = pendingVerifier
        if (code == null || verifier == null) {
            _state.value = AccountState.Error("Вход не завершён: нет кода подтверждения")
            return
        }
        val auth = authClient() ?: run {
            _state.value = AccountState.Error("Синхронизация не настроена")
            return
        }
        when (val result = auth.exchangeCode(code, verifier)) {
            is AuthResult.Success -> {
                pendingVerifier = null
                applySession(result.session)
            }
            is AuthResult.Failure -> {
                _state.value = AccountState.Error(result.message)
                pendingVerifier = null
            }
        }
    }

    /** Отменённый вход: сброс состояния, verifier остаётся в памяти до конца входа. */
    fun cancelSignIn() {
        pendingVerifier = null
        _state.value = AccountState.SignedOut
    }

    private suspend fun applySession(session: AuthSession) {
        credentials.put(CredentialKey.Account, session.refreshToken)
        _state.value = AccountState.SignedIn(
            AccountUser(session.userId, session.email, session.provider),
        )
    }

    /**
     * Гарантированный access-токен для запросов к бэкенду: обновляет по refresh-токену,
     * если истекает в ближайшую минуту. Refresh-токен хранится в Keystore и никогда
     * не покидает устройство.
     */
    suspend fun accessToken(): String? = mutex.withLock {
        val auth = authClient() ?: return null
        val stored = credentials.get(CredentialKey.Account) ?: return null
        val cached = accessTokenCache
        if (cached != null && cached.expiresAtMillis > now() + 60_000) {
            return cached.accessToken
        }
        when (val result = auth.refresh(stored)) {
            is AuthResult.Success -> {
                accessTokenCache = result.session
                credentials.put(CredentialKey.Account, result.session.refreshToken)
                _state.value = AccountState.SignedIn(
                    AccountUser(
                        result.session.userId,
                        result.session.email,
                        result.session.provider,
                    ),
                )
                result.session.accessToken
            }
            is AuthResult.Failure -> {
                // Refresh-токен просрочен/отозван: локальный выход, данные не трогаем.
                signOutLocal()
                null
            }
        }
    }

    private var accessTokenCache: AuthSession? = null
    private fun now(): Long = System.currentTimeMillis()

    /** Восстановление сессии при старте приложения: тихо, без ошибок в UI. */
    suspend fun restore() {
        val auth = authClient() ?: return
        if (credentials.get(CredentialKey.Account) == null) return
        // Пробуем тихо обновить токен; при неудаче просто остаёмся SignedOut.
        accessToken()
    }

    /** Выход: стираем токены локально и сообщаем серверу (неуспех сервера игнорируем). */
    suspend fun signOut() {
        val access = accessTokenCache?.accessToken
        val auth = authClient()
        if (auth != null && access != null) {
            auth.signOut(access)
        }
        signOutLocal()
    }

    private suspend fun signOutLocal() {
        credentials.delete(CredentialKey.Account)
        accessTokenCache = null
        pendingVerifier = null
        _state.value = AccountState.SignedOut
    }

    /** «Удалить данные аккаунта»: выход + очистка ключа синхронизации. */
    suspend fun deleteAccountData() {
        signOut()
        credentials.delete(CredentialKey.SyncKey)
    }

    /** Новый deviceId для этого устройства (вызывается один раз при настройке синка). */
    fun newDeviceId(): String = UUID.randomUUID().toString()

    companion object {
        /** Разбор deep link для тестов: параметры после `#`. */
        fun parseRedirectParams(uri: Uri): Map<String, String> {
            val fragment = uri.fragment.orEmpty()
            return fragment.split('&')
                .filter { it.contains('=') }
                .associate {
                    val idx = it.indexOf('=')
                    it.substring(0, idx) to Uri.decode(it.substring(idx + 1))
                }
        }
    }
}
