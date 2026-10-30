package com.secrethero.neurocode.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Доступ модели к файловой системе Android за пределами проекта (0.8.2).
 *
 * Пользователь явно разрешает папки («корни доступа») через Storage Access Framework;
 * каждое разрешение хранится как persistable URI permission. Все операции только для чтения,
 * запись и удаление вне проекта запрещены. Системные каталоги пропускаются, результаты
 * и глубина обхода ограничены, каждый вызов проверяется по списку корней.
 */
class FilesystemAccess(private val context: Context) {

    @Serializable
    data class FsEntry(
        val name: String,
        val path: String,
        val directory: Boolean,
        val size: Long = 0,
    )

    @Serializable
    data class FsHit(
        val path: String,
        val name: String,
        val size: Long,
    )

    /** Один корень доступа: дерево URI и отображаемое имя. */
    data class FsRoot(
        val uri: String,
        val displayName: String,
    )

    /** Корни, разрешение на которые ещё действительно (SAF-доступ может быть отозван системой). */
    fun roots(rootUris: List<String>): List<FsRoot> = rootUris.mapNotNull { raw ->
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(raw))
            if (tree?.canRead() == true) {
                FsRoot(uri = raw, displayName = tree.name ?: raw)
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Список содержимого папки. [path] — путь внутри корня («» — корень).
     * Возвращает записи или исключение, если корень не разрешён либо папка не найдена.
     */
    suspend fun list(rootUris: List<String>, rootUri: String, path: String): List<FsEntry> =
        withContext(Dispatchers.IO) {
            require(rootUri in rootUris) { "Папка не разрешена пользователем" }
            val dir = resolveDir(rootUris, rootUri, path)
            dir.listFiles()
                .filterNot { it.name == null || it.name in SKIPPED_DIRECTORIES }
                .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
                .take(MAX_LIST_ENTRIES)
                .map { child ->
                    FsEntry(
                        name = child.name ?: "?",
                        path = FsPath.join(path, child.name ?: "?"),
                        directory = child.isDirectory,
                        size = if (child.isFile) child.length() else 0,
                    )
                }
        }

    /**
     * Поиск по разрешённым корням: по имени, расширению и содержимому текстовых файлов.
     * [extension] задаётся без точки, сравнение без учёта регистра.
     */
    suspend fun search(
        rootUris: List<String>,
        query: String,
        extension: String? = null,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<FsHit> = withContext(Dispatchers.IO) {
        val capped = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val normalizedQuery = query.trim()
        val result = mutableListOf<FsHit>()
        for (root in roots(rootUris)) {
            if (result.size >= capped) break
            collectMatches(root, normalizedQuery, extension, capped, result)
        }
        result
    }

    private fun collectMatches(
        root: FsRoot,
        query: String,
        extension: String?,
        limit: Int,
        result: MutableList<FsHit>,
    ) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(root.uri)) ?: return
        walk(tree, root.displayName, 0) { file, relativePath ->
            if (result.size >= limit) return@walk
            val hit = matchFile(file, relativePath, query, extension) ?: return@walk
            result += hit
        }
    }

    /** Файл соответствует запросу: имя, расширение, размер и содержимое проверяются порознь. */
    private fun matchFile(
        file: DocumentFile,
        relativePath: String,
        query: String,
        extension: String?,
    ): FsHit? {
        val name = file.name
        val eligible = name != null &&
            name !in SKIPPED_DIRECTORIES &&
            (extension == null || name.endsWith(".$extension", ignoreCase = true)) &&
            file.isFile &&
            file.length() <= MAX_SEARCH_FILE_BYTES
        if (!eligible) return null
        val size = file.length()
        val matchesName = query.isEmpty() || name.orEmpty().contains(query, true)
        val matchesContent = query.isEmpty() || file.textContentContains(query)
        return if (matchesName || matchesContent) {
            FsHit(path = relativePath, name = name.orEmpty(), size = size)
        } else {
            null
        }
    }

    /** Текст файла (до [maxChars]); null — файл двоичный, слишком большой или не найден. */
    suspend fun readText(
        rootUris: List<String>,
        rootUri: String,
        path: String,
        maxChars: Int = MAX_TEXT_CHARS,
    ): String? =
        withContext(Dispatchers.IO) {
            require(rootUri in rootUris) { "Папка не разрешена пользователем" }
            val file = resolveFile(rootUris, rootUri, path)
            require(file.isFile) { "Файл не найден: $path" }
            require(file.length() <= MAX_READ_FILE_BYTES) { "Файл больше 1 МБ" }
            val text = runCatching {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull() ?: return@withContext null
            if (text.contains('\u0000')) return@withContext null
            if (text.length <= maxChars) text else text.take(maxChars) + "\n… файл обрезан"
        }

    /**
     * Копирует файл из разрешённого корня в проект (без перезаписи существующих).
     * Возвращает относительный путь в проекте.
     */
    suspend fun importToProject(
        rootUris: List<String>,
        rootUri: String,
        path: String,
        projectId: String,
        projects: ProjectRepository,
    ): String = withContext(Dispatchers.IO) {
        require(rootUri in rootUris) { "Папка не разрешена пользователем" }
        val file = resolveFile(rootUris, rootUri, path)
        require(file.isFile) { "Файл не найден: $path" }
        require(file.length() <= MAX_IMPORT_FILE_BYTES) { "Файл больше 32 МБ" }
        val name = sanitizeName(file.name ?: "file")
        val target = projects.resolve(projectId, "imported/$name")
        require(!target.exists()) { "Файл imported/$name уже есть в проекте" }
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Не удалось прочитать $path")
        "imported/$name"
    }

    /** Человекочитаемый путь корня с подпутём для журнала и подтверждений. */
    fun displayPath(rootUris: List<String>, rootUri: String, path: String): String {
        val name = roots(rootUris).firstOrNull { it.uri == rootUri }?.displayName ?: rootUri
        return if (path.isBlank()) name else "$name/$path"
    }

    private fun resolveDir(rootUris: List<String>, rootUri: String, path: String): DocumentFile {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            ?: throw IOException("Папка недоступна")
        require(rootUri in rootUris) { "Папка не разрешена пользователем" }
        val segments = pathSegments(path)
        var current = tree
        segments.forEach { segment ->
            current = current.listFiles().firstOrNull {
                it.isDirectory && it.name == segment
            } ?: throw IOException("Папка не найдена: $path")
        }
        return current
    }

    private fun resolveFile(rootUris: List<String>, rootUri: String, path: String): DocumentFile {
        val segments = pathSegments(path)
        require(segments.isNotEmpty()) { "Укажите файл внутри разрешённой папки" }
        val parentPath = segments.dropLast(1).joinToString("/")
        val dir = resolveDir(rootUris, rootUri, parentPath)
        val fileName = segments.last()
        return dir.listFiles().firstOrNull { it.isFile && it.name == fileName }
            ?: throw IOException("Файл не найден: $path")
    }

    private fun walk(
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        onFile: (DocumentFile, String) -> Unit,
    ) {
        if (depth > MAX_WALK_DEPTH) return
        dir.listFiles().take(MAX_WALK_ENTRIES).forEach { child ->
            val name = child.name ?: return@forEach
            if (name in SKIPPED_DIRECTORIES) return@forEach
            val relativePath = FsPath.join(prefix, name)
            if (child.isDirectory) {
                walk(child, relativePath, depth + 1, onFile)
            } else if (child.isFile) {
                onFile(child, relativePath)
            }
        }
    }

    /**
     * Читает небольшое начало файла и проверяет, что запрос встречается в нём.
     * Крупные и двоичные файлы по содержимому не ищутся.
     */
    private fun DocumentFile.textContentContains(query: String): Boolean = runCatching {
        if (length() > MAX_SEARCH_FILE_BYTES) return false
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(CONTENT_SAMPLE_BYTES)
            val read = input.read(buffer)
            if (read <= 0) return false
            String(buffer, 0, read, Charsets.UTF_8)
        } ?: return false
        if (text.contains('\u0000')) return false
        text.contains(query, ignoreCase = true)
    }.getOrDefault(false)

    private fun pathSegments(path: String): List<String> = FsPath.segments(path)

    private fun sanitizeName(value: String): String = FsPath.sanitizeName(value)

    companion object {
        /** Системные каталоги и мусор, которые модель не должна видеть даже в разрешённых папках. */
        val SKIPPED_DIRECTORIES = setOf(
            "Android",
            "Android/data",
            "Android/obb",
            ".git",
            ".gradle",
            ".idea",
            "node_modules",
            "build",
        )

        private const val MAX_LIST_ENTRIES = 200
        private const val MAX_WALK_DEPTH = 8
        private const val MAX_WALK_ENTRIES = 500
        private const val MAX_SEARCH_FILE_BYTES = 1024L * 1024
        private const val MAX_READ_FILE_BYTES = 1024L * 1024
        private const val MAX_IMPORT_FILE_BYTES = 32L * 1024 * 1024
        private const val CONTENT_SAMPLE_BYTES = 256 * 1024
        private const val DEFAULT_SEARCH_LIMIT = 30
        private const val MAX_SEARCH_LIMIT = 100
        private const val MAX_TEXT_CHARS = 24_000
    }
}

/**
 * Чистая логика путей внутри разрешённого корня: нормализация, склейка и безопасное имя файла.
 * Вынесена из [FilesystemAccess], чтобы покрыть unit-тестами без Android.
 */
object FsPath {

    /**
     * Сегменты относительного пути: пустые, «.» и «..» отбрасываются —
     * выход за пределы разрешённого корня невозможен по построению.
     */
    fun segments(path: String): List<String> =
        path.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }

    fun join(prefix: String, name: String): String =
        if (prefix.isBlank()) name else "$prefix/$name"

    /** Имя файла без символов, опасных для файловой системы; пустое имя заменяется. */
    fun sanitizeName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(MAX_NAME_LENGTH).ifBlank { "file" }

    private const val MAX_NAME_LENGTH = 80
}
