package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.AttachmentKind
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.MessageRole

/** Что умеет модель, для которой готовится история. */
data class TargetCapabilities(
    val supportsTools: Boolean,
    val supportsImages: Boolean,
    /** Окно контекста в токенах; null — неизвестно, история не сокращается. */
    val contextTokens: Int?,
)

data class AdaptedHistory(
    val messages: List<ChatMessage>,
    /** Сколько старых сообщений не поместилось в окно контекста. */
    val droppedMessages: Int,
    /** Картинки убраны, потому что модель их не принимает. */
    val imagesRemoved: Boolean,
)

/**
 * Приводит историю диалога к виду, который понимает новая модель после переключения посреди диалога:
 * размышления не повторяются, результаты инструментов предыдущего провайдера сворачиваются в
 * текстовую сводку (если модель не умеет вызывать инструменты), история сокращается под окно контекста.
 */
object HistoryAdapter {
    private const val CHARS_PER_TOKEN = 3
    private const val MESSAGE_OVERHEAD_TOKENS = 4
    private const val CONTEXT_SHARE_PERCENT = 75
    private const val TOOL_SUMMARY_CHARS = 600

    fun estimateTokens(message: ChatMessage): Int =
        message.content.length / CHARS_PER_TOKEN + MESSAGE_OVERHEAD_TOKENS

    fun adapt(history: List<ChatMessage>, target: TargetCapabilities): AdaptedHistory {
        var imagesRemoved = false
        val converted = history.map { message ->
            var item = message.copy(reasoning = null, generatedBy = null)
            if (item.role == MessageRole.TOOL && !target.supportsTools) {
                item = item.copy(
                    role = MessageRole.USER,
                    content = "[сводка: результат инструмента ${item.toolName ?: "?"}] " +
                        item.content.take(TOOL_SUMMARY_CHARS),
                    toolName = null,
                    toolCallId = null,
                )
            }
            if (!target.supportsImages && item.attachments.any { it.kind == AttachmentKind.IMAGE }) {
                imagesRemoved = true
                val names = item.attachments.filter { it.kind == AttachmentKind.IMAGE }.joinToString { it.name }
                item = item.copy(
                    attachments = item.attachments.filterNot { it.kind == AttachmentKind.IMAGE },
                    content = item.content + "\n[изображения недоступны для этой модели: $names]",
                )
            }
            item
        }
        val fitted = fit(converted, target.contextTokens)
        return AdaptedHistory(fitted, converted.size - fitted.size, imagesRemoved)
    }

    /** Оставляет самые свежие сообщения, которые помещаются в долю окна контекста; последнее — всегда. */
    private fun fit(messages: List<ChatMessage>, contextTokens: Int?): List<ChatMessage> {
        if (contextTokens == null || messages.isEmpty()) return messages
        val budget = contextTokens * CONTEXT_SHARE_PERCENT / PERCENT
        var used = 0
        var start = messages.size
        for (index in messages.indices.reversed()) {
            used += estimateTokens(messages[index])
            if (used > budget && index < messages.size - 1) break
            start = index
        }
        return messages.subList(start, messages.size)
    }

    /**
     * Прежний диалог одним текстом — для локальной модели, у которой своего контекста ещё нет
     * (например, после перехода с облачной модели). Берутся самые свежие сообщения в пределах [maxChars].
     */
    fun transcript(messages: List<ChatMessage>, maxChars: Int): String {
        val lines = messages.mapNotNull { message ->
            when (message.role) {
                MessageRole.USER -> "Пользователь: ${message.content}"
                MessageRole.ASSISTANT -> "Ассистент: ${message.content}"
                MessageRole.TOOL -> "Результат инструмента ${message.toolName ?: "?"}: " +
                    message.content.take(TOOL_SUMMARY_CHARS)
                MessageRole.SYSTEM -> null
            }
        }
        val kept = ArrayDeque<String>()
        var total = 0
        for (line in lines.asReversed()) {
            if (total + line.length > maxChars && kept.isNotEmpty()) break
            kept.addFirst(line.take(maxChars))
            total += line.length
        }
        return kept.joinToString("\n")
    }

    private const val PERCENT = 100
}
