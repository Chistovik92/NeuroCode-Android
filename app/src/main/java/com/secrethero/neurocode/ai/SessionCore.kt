package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.MessageRole
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val CURRENT_VERSION = 1

/** Версионированное событие журнала сессии. Секреты и API-ключи сюда не попадают. */
@Serializable
data class SessionEvent(
    val version: Int = CURRENT_VERSION,
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val messageId: String? = null,
    val role: String? = null,
    val toolName: String? = null,
    val content: String? = null,
    val modelKey: String? = null,
)

/** Append-only журнал: восстановление сообщений остаётся за ChatRepository. */
class SessionJournal(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun append(event: SessionEvent) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(event) + "\n")
    }

    fun read(): List<SessionEvent> = if (!file.isFile) emptyList() else file.useLines { lines ->
        lines.mapNotNull { line -> runCatching { json.decodeFromString<SessionEvent>(line) }.getOrNull() }.toList()
    }

}

/** Большие результаты инструментов выносятся из журнала, в контекст попадает ссылка и начало. */
class SpillStore(private val directory: File) {
    fun save(id: String, text: String, threshold: Int = DEFAULT_THRESHOLD): String? {
        if (text.length <= threshold) return null
        directory.mkdirs()
        val file = File(directory, "$id.txt")
        file.writeText(text)
        return file.name
    }

    fun read(name: String, maxChars: Int = DEFAULT_READ_CHARS): String? =
        File(directory, name).takeIf {
            it.isFile && it.name == name && it.name.endsWith(".txt")
        }?.readText()?.take(maxChars)

    /** Файлы диалога растут без предела, если их не чистить при удалении диалога. */
    fun removeSession(sessionId: String) {
        directory.listFiles { file -> file.isFile && file.name.startsWith("$sessionId-") }
            .orEmpty()
            .forEach { runCatching { it.delete() } }
    }

    /**
     * Чистит spill-файлы, оставшиеся от удалённых диалогов или устаревшие: без этого каталог
     * `.neurocode/spill` рос бы неограниченно (spill пишется при каждом крупном tool-результате,
     * а удаление диалога чистит только его собственные файлы — осиротевшие копились бы вечно).
     */
    fun cleanup(
        olderThanMillis: Long = DEFAULT_MAX_AGE_MILLIS,
        liveSessionIds: Set<String> = emptySet(),
    ) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        directory.listFiles { file -> file.isFile }
            .orEmpty()
            .filter { file ->
                file.lastModified() < cutoff && liveSessionIds.none { file.name.startsWith("$it-") }
            }
            .forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 8_000
        const val DEFAULT_READ_CHARS = 12_000

        /** Spill-файлы старше этого возраста (7 дней) считаются мусором и удаляются при старте. */
        const val DEFAULT_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

data class ContextBuildResult(val messages: List<ChatMessage>, val dropped: Int, val spills: Int)

/** Единая точка сборки контекста: история, инструменты, вложения и бюджет модели. */
object SessionContextBuilder {
    fun build(history: List<ChatMessage>, target: TargetCapabilities): ContextBuildResult {
        val adapted = HistoryAdapter.adapt(history, target)
        return ContextBuildResult(adapted.messages, adapted.droppedMessages, 0)
    }

    fun compact(history: List<ChatMessage>, maxMessages: Int): List<ChatMessage> {
        if (history.size <= maxMessages) return history
        val old = history.dropLast(maxMessages - 1).filter { it.role != MessageRole.SYSTEM }
        val summary = old.joinToString("\n") { "${it.role}: ${it.content.take(SUMMARY_CHAR_LIMIT)}" }
        return listOf(ChatMessage(role = MessageRole.SYSTEM, content = "[Сводка старых ходов]\n$summary")) +
            history.takeLast(maxMessages - 1)
    }

    private const val SUMMARY_CHAR_LIMIT = 400
}
