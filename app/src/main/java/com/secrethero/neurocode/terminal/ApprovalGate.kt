package com.secrethero.neurocode.terminal

import com.secrethero.neurocode.model.ToolApprovalRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ApprovalGate {
    private val mutex = Mutex()
    private val _request = MutableStateFlow<ToolApprovalRequest?>(null)
    val request: StateFlow<ToolApprovalRequest?> = _request.asStateFlow()
    private var decision: CompletableDeferred<Boolean>? = null

    suspend fun ask(request: ToolApprovalRequest): Boolean = mutex.withLock {
        val pending = CompletableDeferred<Boolean>()
        decision = pending
        _request.value = request
        try {
            pending.await()
        } finally {
            decision = null
            _request.value = null
        }
    }

    fun resolve(approved: Boolean) {
        decision?.complete(approved)
    }
}
