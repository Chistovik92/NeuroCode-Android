package com.secrethero.neurocode.data

import android.content.Context
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

class ChatRepository(private val context: Context) {
    private val store = JsonFileStore(
        file = context.filesDir.resolve("state/chats.json"),
        serializer = ListSerializer(ChatSession.serializer()),
        defaultValue = { emptyList() },
    )
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    suspend fun initialize() {
        _sessions.value = store.read().sortedByDescending { it.updatedAt }
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
        update(sessionId) { session ->
            val firstUser = (session.messages + message).firstOrNull {
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
                messages = session.messages + message,
            )
        }
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

    private suspend fun update(sessionId: String, block: (ChatSession) -> ChatSession) {
        val updated = _sessions.value.map { if (it.id == sessionId) block(it) else it }
            .sortedByDescending { it.updatedAt }
        persist(updated)
    }

    private suspend fun persist(sessions: List<ChatSession>) {
        store.write(sessions)
        _sessions.value = sessions
    }

    private companion object {
        const val LEGACY_DEFAULT_TITLE = "Новый диалог"
    }
}
