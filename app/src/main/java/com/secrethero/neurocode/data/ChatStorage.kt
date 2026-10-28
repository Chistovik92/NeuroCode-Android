package com.secrethero.neurocode.data

import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.ModelRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.io.File

/** Строка индекса диалогов: всё нужное для списка без чтения сообщений. */
@Serializable
data class ChatIndexEntry(
    val id: String,
    val title: String,
    val projectId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val model: ModelRef? = null,
) {
    companion object {
        fun of(session: ChatSession) = ChatIndexEntry(
            id = session.id,
            title = session.title,
            projectId = session.projectId,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            messageCount = session.messages.size,
            model = session.model,
        )
    }
}

/** Где и как лежат диалоги; [ChatStorage] выбирает реализацию. */
interface ChatPersistence {
    suspend fun loadAll(): List<ChatSession>

    /** [all] — полный список после изменения, [changed] — записать, [removed] — удалить. */
    suspend fun apply(all: List<ChatSession>, changed: List<ChatSession>, removed: Set<String>)
}

/**
 * Один файл на диалог: `chats/<id>.json` плюс `chats/index.json`. Запись одного диалога не трогает остальные,
 * повреждённый файл уходит в карантин ([JsonFileStore]) и не мешает открыть соседние.
 */
class SplitChatStore(
    private val dir: File,
    private val onCorrupt: (File) -> Unit = {},
) : ChatPersistence {
    private val indexStore = JsonFileStore(
        file = File(dir, INDEX_FILE),
        serializer = ListSerializer(ChatIndexEntry.serializer()),
        defaultValue = { emptyList() },
        onCorrupt = onCorrupt,
    )

    fun hasIndex(): Boolean = File(dir, INDEX_FILE).exists()

    private fun sessionFile(id: String): File {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        require(safe.isNotEmpty()) { "Пустой идентификатор диалога" }
        return File(dir, "$safe.json")
    }

    /** Читает файлы диалогов; индекс не трогает. */
    suspend fun readSessions(): List<ChatSession> {
        val files = withContext(Dispatchers.IO) {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".json") && file.name != INDEX_FILE }.orEmpty()
        }
        return files.mapNotNull { file ->
            JsonFileStore(file, ChatSession.serializer().nullable, { null }, onCorrupt).read()
        }.distinctBy { it.id }
    }

    override suspend fun loadAll(): List<ChatSession> {
        withContext(Dispatchers.IO) { dir.mkdirs() }
        val sessions = readSessions()
        val indexed = indexStore.read().map { it.id to it.updatedAt }.toSet()
        if (indexed != sessions.map { it.id to it.updatedAt }.toSet()) writeIndex(sessions)
        return sessions
    }

    override suspend fun apply(all: List<ChatSession>, changed: List<ChatSession>, removed: Set<String>) {
        changed.forEach { writeSession(it) }
        withContext(Dispatchers.IO) {
            removed.forEach { id ->
                val file = sessionFile(id)
                file.delete()
                File(dir, file.name + ".tmp").delete()
            }
        }
        writeIndex(all)
    }

    suspend fun writeSession(session: ChatSession) {
        JsonFileStore(sessionFile(session.id), ChatSession.serializer(), { session }, onCorrupt).write(session)
    }

    suspend fun writeIndex(sessions: List<ChatSession>) {
        indexStore.write(sessions.sortedByDescending { it.updatedAt }.map(ChatIndexEntry::of))
    }

    /** Убирает следы неудавшейся миграции: файлы указанных диалогов и индекс. */
    suspend fun rollback(ids: Collection<String>) = withContext(Dispatchers.IO) {
        ids.forEach { runCatching { sessionFile(it).delete() } }
        File(dir, INDEX_FILE).delete()
    }

    companion object {
        const val INDEX_FILE = "index.json"
    }
}

/** Прежнее хранение: все диалоги одним файлом `chats.json`. Остаётся запасным путём, если миграция не удалась. */
class LegacyChatFile(
    legacyFile: File,
    onCorrupt: (File) -> Unit = {},
) : ChatPersistence {
    private val store = JsonFileStore(
        file = legacyFile,
        serializer = ListSerializer(ChatSession.serializer()),
        defaultValue = { emptyList() },
        onCorrupt = onCorrupt,
    )

    override suspend fun loadAll(): List<ChatSession> = store.read()

    override suspend fun apply(all: List<ChatSession>, changed: List<ChatSession>, removed: Set<String>) {
        store.write(all)
    }
}

enum class MigrationResult { NOT_NEEDED, DONE, FAILED }

/**
 * Перенос `chats.json` в файлы по диалогам. Идемпотентен и обратим: сначала резервная копия
 * `chats.json.bak-0.8.0`, затем запись и проверка (число диалогов и сообщений), и только после этого
 * `chats.json` переименовывается в `chats.json.migrated`. Любой сбой убирает новые файлы, старый остаётся.
 */
object ChatMigration {
    const val BACKUP_SUFFIX = ".bak-0.8.0"
    const val MIGRATED_SUFFIX = ".migrated"

    suspend fun run(legacy: File, target: SplitChatStore, onCorrupt: (File) -> Unit = {}): MigrationResult {
        val sessions = pendingSessions(legacy, target, onCorrupt) ?: return MigrationResult.NOT_NEEDED
        return runCatching { migrate(legacy, target, sessions) }
            .getOrElse {
                target.rollback(sessions.map { session -> session.id })
                MigrationResult.FAILED
            }
    }

    /**
     * Диалоги, которые ещё надо перенести; null — переносить нечего: файла нет, перенос уже сделан,
     * либо файл пуст или повреждён (повреждённый уже в карантине и не потерян).
     */
    private suspend fun pendingSessions(legacy: File, target: SplitChatStore, onCorrupt: (File) -> Unit) = when {
        !legacy.exists() -> null
        target.hasIndex() -> {
            markMigrated(legacy)
            null
        }
        else -> JsonFileStore(legacy, ListSerializer(ChatSession.serializer()).nullable, { null }, onCorrupt).read()
    }

    private suspend fun migrate(legacy: File, target: SplitChatStore, sessions: List<ChatSession>): MigrationResult {
        withContext(Dispatchers.IO) {
            val backup = File(legacy.parentFile, legacy.name + BACKUP_SUFFIX)
            if (!backup.exists()) legacy.copyTo(backup)
        }
        sessions.forEach { target.writeSession(it) }
        val written = target.readSessions()
        check(written.map { it.id }.toSet() == sessions.map { it.id }.toSet()) { "Не все диалоги записаны" }
        val expectedMessages = sessions.sumOf { it.messages.size }
        check(written.sumOf { it.messages.size } == expectedMessages) { "Число сообщений не совпало" }
        target.writeIndex(sessions)
        markMigrated(legacy)
        return MigrationResult.DONE
    }

    private suspend fun markMigrated(legacy: File) = withContext(Dispatchers.IO) {
        var target = File(legacy.parentFile, legacy.name + MIGRATED_SUFFIX)
        if (target.exists()) {
            target = File(legacy.parentFile, legacy.name + MIGRATED_SUFFIX + "-" + System.currentTimeMillis())
        }
        legacy.renameTo(target)
    }
}

/**
 * Хранилище диалогов приложения: при первом запуске 0.8.0 переносит `chats.json` в файлы по диалогам;
 * если перенос не удался — работает по-старому, ничего не теряя.
 */
class ChatStorage(
    private val stateDir: File,
    private val onCorrupt: (File) -> Unit = {},
) : ChatPersistence {
    private val mutex = Mutex()
    private var backend: ChatPersistence? = null

    override suspend fun loadAll(): List<ChatSession> = mutex.withLock {
        val legacy = File(stateDir, "chats.json")
        val split = SplitChatStore(File(stateDir, "chats"), onCorrupt)
        val chosen = when (ChatMigration.run(legacy, split, onCorrupt)) {
            MigrationResult.FAILED -> LegacyChatFile(legacy, onCorrupt)
            else -> split
        }
        backend = chosen
        chosen.loadAll()
    }

    override suspend fun apply(all: List<ChatSession>, changed: List<ChatSession>, removed: Set<String>) =
        mutex.withLock {
            checkNotNull(backend) { "Хранилище диалогов не загружено" }.apply(all, changed, removed)
        }
}
