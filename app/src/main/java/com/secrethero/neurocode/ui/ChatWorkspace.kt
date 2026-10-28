package com.secrethero.neurocode.ui

import com.secrethero.neurocode.AppContainer
import com.secrethero.neurocode.data.ChatCopies
import com.secrethero.neurocode.model.ChatSession

/**
 * Рабочие папки и файлы диалога: каждый диалог работает в своём проекте или в собственной скрытой
 * «песочнице» и никогда не берёт чужой выбранный проект.
 */
internal class ChatWorkspace(private val container: AppContainer) {
    /** Проект диалога либо его песочница (создаётся при первом запросе); null, если диалога нет. */
    suspend fun forSession(session: ChatSession?): String? = session?.let {
        it.projectId
            ?: it.sandboxProjectId?.takeIf { id -> container.projects.get(id) != null }
            ?: createSandbox(it)
    }

    private suspend fun createSandbox(session: ChatSession): String {
        val sandbox = container.projects.createSandbox(session.title)
        container.chats.setSandbox(session.id, sandbox.id)
        return sandbox.id
    }

    /**
     * Удаляет то, что принадлежит только этому диалогу: его вложения и скрытую «песочницу». Файлы проекта
     * и другие диалоги не затрагиваются — у дубликатов вложения отдельные.
     */
    suspend fun removeOwnFiles(session: ChatSession) {
        val workspace = session.projectId ?: session.sandboxProjectId
        if (workspace != null) {
            session.messages.flatMap { it.attachments }.forEach { container.projects.deleteAttachment(workspace, it) }
        }
        session.sandboxProjectId?.let { container.projects.delete(it) }
    }

    /** Копия диалога с новыми идентификаторами и собственными файлами вложений; null, если диалога нет. */
    suspend fun duplicate(sessionId: String, titleSuffix: String): ChatSession? {
        val source = container.chats.get(sessionId) ?: return null
        val projectId = source.projectId
        val copy = ChatCopies.duplicate(source, titleSuffix) { attachment ->
            projectId?.let { container.projects.duplicateAttachment(it, attachment) }
        }
        container.chats.add(copy)
        return copy
    }
}
