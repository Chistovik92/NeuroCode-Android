package com.secrethero.neurocode.data

import com.secrethero.neurocode.model.ChatAttachment
import com.secrethero.neurocode.model.ChatSession
import java.util.UUID

/** Копирование диалога: новый UUID у диалога и у каждого сообщения, вложения — отдельными файлами. */
object ChatCopies {
    /**
     * Копия [source] без общих идентификаторов и файлов с оригиналом. [copyAttachment] делает копию
     * файла вложения и возвращает новое вложение; null — файла нет, вложение в копию не попадает.
     * Своя «песочница» у копии не наследуется, она создаётся при первом запросе.
     */
    suspend fun duplicate(
        source: ChatSession,
        titleSuffix: String,
        now: Long = System.currentTimeMillis(),
        newId: () -> String = { UUID.randomUUID().toString() },
        copyAttachment: suspend (ChatAttachment) -> ChatAttachment?,
    ): ChatSession {
        val messages = source.messages.map { message ->
            message.copy(
                id = newId(),
                attachments = message.attachments.mapNotNull { copyAttachment(it) },
            )
        }
        return source.copy(
            id = newId(),
            title = source.title + titleSuffix,
            createdAt = now,
            updatedAt = now,
            sandboxProjectId = null,
            messages = messages,
        )
    }
}
