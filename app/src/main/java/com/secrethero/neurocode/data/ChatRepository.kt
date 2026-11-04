package com.secrethero.neurocode.data

import android.content.Context
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.ai.SessionEvent
import com.secrethero.neurocode.ai.SessionJournal
import com.secrethero.neurocode.ai.SpillStore
import com.secrethero.neurocode.model.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ChatRepository(private val context: Context, onCorrupt: (File) -> Unit = {}) {
    private val storage = ChatStorage(context.filesDir.resolve("state"), onCorrupt)
    private val journals = context.filesDir.resolve("state/session-events")
    private val spill = SpillStore(context.filesDir.resolve(".neurocode/spill"))
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    suspend fun initialize() {
        _sessions.value = storage.loadAll().sortedByDescending { it.updatedAt }
    }

    /** Добавляет готовый диалог (например, копию) в начало списка. */
    suspend fun add(session: ChatSession) {
        persist(listOf(session) + _sessions.value)
    }

    /** Запоминает скрытую рабочую папку диалога без проекта. */
    suspend fun setSandbox(sessionId: String, projectId: String) {
        update(sessionId) { it.copy(sandboxProjectId = projectId) }
    }

    suspend fun create(projectId: String?, providerId: String?): ChatSession {
        val session = ChatSession(
            projectId = projectId,
            providerId = providerId,
            title = context.getString(R.string.default_session_title),
        )
        persist(listOf(session) + _sessions.value)
        return session
    }

    suspend fun append(sessionId: String, message: ChatMessage) {
        val spillName = if (message.role == MessageRole.TOOL) {
            spill.save("$sessionId-${message.id}", message.content)
        } else {
            null
        }
        val persistedMessage = if (spillName != null) {
            val inline = message.content.take(SPILL_INLINE_CHARS)
            message.copy(content = "$inline\n[полный результат: .neurocode/spill/$spillName]")
        } else {
            message
        }
        SessionJournal(File(journals, "$sessionId.jsonl")).append(
            SessionEvent(
                type = when (message.role) {
                    com.secrethero.neurocode.model.MessageRole.TOOL -> "tool_result"
                    com.secrethero.neurocode.model.MessageRole.ASSISTANT -> "assistant_message"
                    com.secrethero.neurocode.model.MessageRole.USER -> "user_message"
                    com.secrethero.neurocode.model.MessageRole.SYSTEM -> "system_message"
                },
                messageId = message.id,
                role = message.role.name,
                toolName = message.toolName,
                content = persistedMessage.content,
                modelKey = message.generatedBy?.key,
            ),
        )
        update(sessionId) { session ->
            val firstUser = (session.messages + persistedMessage).firstOrNull {
                it.role.name == "USER"
            }?.content
            val defaults = setOf(
                LEGACY_DEFAULT_TITLE,
                context.getString(R.string.default_session_title),
            )
            val title = if (session.title in defaults && !firstUser.isNullOrBlank()) {
                firstUser.lineSequence().first().take(52)
            } else {
                session.title
            }
            session.copy(
                title = title,
                updatedAt = System.currentTimeMillis(),
                messages = session.messages + persistedMessage,
            )
        }
    }

    /** Переопределение модели диалога; null возвращает диалог к модели проекта. */
    suspend fun setModel(sessionId: String, model: ModelRef?) {
        update(sessionId) { it.copy(model = model) }
    }

    suspend fun replaceMessages(sessionId: String, messages: List<ChatMessage>) {
        update(sessionId) {
            it.copy(messages = messages, updatedAt = System.currentTimeMillis())
        }
    }

    suspend fun delete(sessionId: String) {
        persist(_sessions.value.filterNot { it.id == sessionId })
    }

    fun get(sessionId: String?): ChatSession? =
        _sessions.value.firstOrNull { it.id == sessionId }

    fun search(query: String, projectId: String? = null): List<ChatSession> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return _sessions.value.filter { session ->
            (projectId == null || session.projectId == projectId) &&
                (session.title.contains(needle, true) || session.messages.any {
                    it.content.contains(needle, true) || it.toolName?.contains(needle, true) == true ||
                        it.generatedBy?.key?.contains(needle, true) == true
                })
        }
    }

    private suspend fun update(sessionId: String, block: (ChatSession) -> ChatSession) {
        val updated = _sessions.value.map { if (it.id == sessionId) block(it) else it }
            .sortedByDescending { it.updatedAt }
        persist(updated)
    }

    /** Пишет только изменившиеся диалоги и удаляет исчезнувшие: запись одного диалога не трогает остальные. */
    private suspend fun persist(sessions: List<ChatSession>) {
        val previous = _sessions.value.associateBy { it.id }
        val changed = sessions.filter { previous[it.id] != it }
        val removed = previous.keys - sessions.map { it.id }.toSet()
        storage.apply(sessions, changed, removed)
        _sessions.value = sessions
    }

    private companion object {
        const val LEGACY_DEFAULT_TITLE = "Новый диалог"
        const val SPILL_INLINE_CHARS = 8_000
    }
}
