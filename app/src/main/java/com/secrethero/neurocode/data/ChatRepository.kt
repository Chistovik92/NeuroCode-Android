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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ChatRepository(private val context: Context, onCorrupt: (File) -> Unit = {}) {
    private val storage = ChatStorage(context.filesDir.resolve("state"), onCorrupt)
    private val journals = context.filesDir.resolve("state/session-events")
    private val spill = SpillStore(context.filesDir.resolve(".neurocode/spill"))
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    /**
     * Все мутации списка диалогов идут под одним мьютексом: параллельные `append`
     * (сообщение пользователя, ответ, каждый tool-результат пишутся из разных корутин)
     * раньше могли терять друг друга на suspend-точке записи в storage.
     */
    private val mutex = Mutex()

    /** Один журнал на сессию: `@Synchronized` на свежих экземплярах взаимного исключения не давал. */
    private val journalLock = Any()
    private val journalsBySession = HashMap<String, SessionJournal>()

    private fun journal(sessionId: String): SessionJournal =
        synchronized(journalLock) {
            journalsBySession.getOrPut(sessionId) { SessionJournal(File(journals, "$sessionId.jsonl")) }
        }

    suspend fun initialize() = mutex.withLock {
        _sessions.value = storage.loadAll().sortedByDescending { it.updatedAt }
        // Spill-файлы удалённых диалогов и устаревшие — на IO при старте, иначе каталог рос бы вечно.
        withContext(Dispatchers.IO) { spill.cleanup() }
    }

    /** Добавляет готовый диалог (например, копию) в начало списка. */
    suspend fun add(session: ChatSession) = mutex.withLock {
        persistLocked(listOf(session) + _sessions.value)
    }

    /** Запоминает скрытую рабочую папку диалога без проекта. */
    suspend fun setSandbox(sessionId: String, projectId: String) {
        update(sessionId) { it.copy(sandboxProjectId = projectId) }
    }

    suspend fun create(projectId: String?, providerId: String?): ChatSession = mutex.withLock {
        val session = ChatSession(
            projectId = projectId,
            providerId = providerId,
            title = context.getString(R.string.default_session_title),
        )
        persistLocked(listOf(session) + _sessions.value)
        session
    }

    suspend fun append(sessionId: String, message: ChatMessage) {
        // Диск (spill и журнал) — вне мьютекса и вне главного потока; порядок с update не важен.
        val persistedMessage = withContext(Dispatchers.IO) {
            val spillName = if (message.role == MessageRole.TOOL) {
                spill.save("$sessionId-${message.id}", message.content)
            } else {
                null
            }
            val persisted = if (spillName != null) {
                val inline = message.content.take(SPILL_INLINE_CHARS)
                message.copy(content = "$inline\n[полный результат: .neurocode/spill/$spillName]")
            } else {
                message
            }
            journal(sessionId).append(
                SessionEvent(
                    type = when (message.role) {
                        MessageRole.TOOL -> "tool_result"
                        MessageRole.ASSISTANT -> "assistant_message"
                        MessageRole.USER -> "user_message"
                        MessageRole.SYSTEM -> "system_message"
                    },
                    messageId = message.id,
                    role = message.role.name,
                    toolName = message.toolName,
                    content = persisted.content,
                    modelKey = message.generatedBy?.key,
                ),
            )
            persisted
        }
        update(sessionId) { session ->
            val firstUser = (session.messages + persistedMessage).firstOrNull {
                it.role == MessageRole.USER
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

    /** Сбрасывает переопределение модели диалогов, ссылающихся на удаляемую локальную GGUF. */
    suspend fun resetLocalModel(localPath: String, localName: String?) = mutex.withLock {
        val sessions = _sessions.value
        val uses = { ref: com.secrethero.neurocode.model.ModelRef? ->
            ref != null && ref.kind == com.secrethero.neurocode.model.ModelKind.LOCAL &&
                (ref.localPath == localPath || (localName != null && ref.localName == localName))
        }
        if (sessions.none { uses(it.model) }) return@withLock
        persistLocked(
            sessions.map { if (uses(it.model)) it.copy(model = null) else it },
        )
    }

    suspend fun delete(sessionId: String) = mutex.withLock {
        persistLocked(_sessions.value.filterNot { it.id == sessionId })
        synchronized(journalLock) { journalsBySession.remove(sessionId) }
        // Журнал и spill удалённого диалога копились бы вечно.
        withContext(Dispatchers.IO) {
            runCatching { File(journals, "$sessionId.jsonl").delete() }
            runCatching { spill.removeSession(sessionId) }
        }
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

    private suspend fun update(sessionId: String, block: (ChatSession) -> ChatSession) =
        mutex.withLock {
            val updated = _sessions.value.map { if (it.id == sessionId) block(it) else it }
                .sortedByDescending { it.updatedAt }
            persistLocked(updated)
        }

    /** Пишет только изменившиеся диалоги и удаляет исчезнувшие: запись одного диалога не трогает остальные. */
    private suspend fun persistLocked(sessions: List<ChatSession>) {
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
