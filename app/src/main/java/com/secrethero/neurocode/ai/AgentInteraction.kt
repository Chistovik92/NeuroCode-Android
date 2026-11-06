package com.secrethero.neurocode.ai

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class AgentQuestionOption(
    val label: String,
    val description: String = "",
)

data class AgentQuestion(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val options: List<AgentQuestionOption> = emptyList(),
)

/** Одно видимое уточнение; тайм-аут означает отсутствие ответа, а не случайный выбор. */
class AgentInteraction {
    private val mutex = Mutex()
    private val _question = MutableStateFlow<AgentQuestion?>(null)
    val question: StateFlow<AgentQuestion?> = _question.asStateFlow()

    @Volatile
    private var answer: CompletableDeferred<String?>? = null

    suspend fun ask(question: AgentQuestion, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? =
        mutex.withLock {
            require(question.text.isNotBlank()) { "Пустой вопрос" }
            require(timeoutMs > 0) { "Тайм-аут должен быть положительным" }
            val pending = CompletableDeferred<String?>()
            answer = pending
            _question.value = question
            try {
                withTimeoutOrNull(timeoutMs.coerceAtMost(DEFAULT_TIMEOUT_MS)) { pending.await() }
            } finally {
                answer = null
                _question.value = null
            }
        }

    fun respond(text: String) {
        val value = text.trim().take(MAX_ANSWER_CHARS)
        if (value.isNotEmpty()) answer?.complete(value)
    }

    fun skip() {
        answer?.complete(null)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
        const val MAX_ANSWER_CHARS = 2_000
        const val MAX_OPTIONS = 4
    }
}
