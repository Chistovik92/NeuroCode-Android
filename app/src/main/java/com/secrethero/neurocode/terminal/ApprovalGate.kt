package com.secrethero.neurocode.terminal

import com.secrethero.neurocode.model.ToolApprovalRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class ApprovalGate {
    private val mutex = Mutex()
    private val _request = MutableStateFlow<ToolApprovalRequest?>(null)
    val request: StateFlow<ToolApprovalRequest?> = _request.asStateFlow()

    /**
     * Читается из UI-потока (`resolve`), а пишется из корутин агентного цикла —
     * без `@Volatile` подтверждение могло потеряться из-за видимости.
     */
    @Volatile
    private var decision: CompletableDeferred<Boolean>? = null

    /**
     * Ожидание решения с тайм-аутом: если диалог подтверждения потерян (пересоздание
     * экрана, Edge-to-edge пересборка), `ask` больше не держит мьютекс вечно — иначе
     * завис бы и каждый следующий tool-call с подтверждением.
     */
    suspend fun ask(request: ToolApprovalRequest): Boolean = mutex.withLock {
        val pending = CompletableDeferred<Boolean>()
        decision = pending
        _request.value = request
        try {
            withTimeoutOrNull(DECISION_TIMEOUT_MS) { pending.await() } ?: false
        } finally {
            decision = null
            _request.value = null
        }
    }

    fun resolve(approved: Boolean) {
        decision?.complete(approved)
    }

    private companion object {
        const val DECISION_TIMEOUT_MS = 10 * 60_000L
    }
}
