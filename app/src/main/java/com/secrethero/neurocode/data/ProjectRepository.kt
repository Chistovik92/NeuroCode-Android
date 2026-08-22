package com.secrethero.neurocode.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.secrethero.neurocode.model.FileNode
import com.secrethero.neurocode.model.Project
import com.secrethero.neurocode.model.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectRepository(private val context: Context) {
    private val workspaceRoot = File(context.filesDir, "workspaces").apply { mkdirs() }
    private val store = JsonFileStore(
        file = context.filesDir.resolve("state/projects.json"),
        serializer = ListSerializer(Project.serializer()),
        defaultValue = { emptyList() },
    )
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    suspend fun initialize() {
        _projects.value = store.read().filter { File(it.rootPath).isDirectory }
    }

    suspend fun create(name: String): Project = withContext(Dispatchers.IO) {
        val project = register(name)
        File(project.rootPath, "README.md").writeText(
            "# ${project.name}\n\nПроект создан в NeuroCode Android.\n",
        )
        project
    }

    suspend fun register(name: String): Project = withContext(Dispatchers.IO) {
        val cleanName = sanitizeName(name.ifBlank { "Проект" })
        val id = UUID.randomUUID().toString()
        val root = File(workspaceRoot, id).apply { mkdirs() }
        File(root, ".neurocode").mkdirs()
        val project = Project(id = id, name = cleanName, rootPath = root.absolutePath)
        persist(listOf(project) + _projects.value)
        project
    }

    suspend fun importTree(uri: Uri, requestedName: String?): Project =
        withContext(Dispatchers.IO) {
            val source = DocumentFile.fromTreeUri(context, uri)
                ?: throw IOException("Не удалось открыть выбранную папку")
            val project = create(requestedName ?: source.name ?: "Импортированный проект")
            copyDocumentTree(source, File(project.rootPath), 0)
            touch(project.id)
            project
        }

    suspend fun exportTree(
        projectId: String,
        target: Uri,
        onProgress: (copied: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val root = resolve(projectId, "")
        val destination = DocumentFile.fromTreeUri(context, target)
            ?: throw IOException("Не удалось открыть папку назначения")
        val total = root.walkTopDown()
            .onEnter { it.name !in exportIgnoredDirectories }
            .filter { it.isFile && !it.name.endsWith(PENDING_SUFFIX) }
            .count()
        onProgress(0, total)
        copyToDocumentTree(root, destination, 0, onProgress, total)
    }

    private fun copyToDocumentTree(
        source: File,
        target: DocumentFile,
        depth: Int,
        onProgress: (copied: Int, total: Int) -> Unit,
        total: Int,
    ): Int {
        require(depth <= MAX_EXPORT_DEPTH) { "Слишком глубокая структура папок" }
        var copied = 0
        val existing = target.listFiles().associateBy { it.name }
        source.listFiles().orEmpty()
            .filterNot { it.name in exportIgnoredDirectories }
            .filterNot { it.name.endsWith(PENDING_SUFFIX) }
            .sortedBy { it.name.lowercase() }
            .forEach { entry ->
                val current = existing[entry.name]
                if (entry.isDirectory) {
                    val directory = current?.takeIf { it.isDirectory }
                        ?: target.createDirectory(entry.name)
                        ?: throw IOException("Не удалось создать папку ${entry.name}")
                    copied += copyToDocumentTree(entry, directory, depth + 1, onProgress, total)
                } else {
                    require(entry.length() <= MAX_IMPORTED_FILE_BYTES) {
                        "Файл ${entry.name} больше 200 МБ"
                    }
                    val document = current?.takeIf { it.isFile }
                        ?: target.createFile(mimeTypeFor(entry.name), entry.name)
                        ?: throw IOException("Не удалось создать файл ${entry.name}")
                    context.contentResolver.openOutputStream(document.uri, "wt")?.use { output ->
                        entry.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IOException("Не удалось записать ${entry.name}")
                    copied++
                    onProgress(copied, total)
                }
            }
        return copied
    }

    suspend fun renameEntry(projectId: String, oldPath: String, newPath: String) =
        withContext(Dispatchers.IO) {
            val source = resolve(projectId, oldPath)
            require(source != resolve(projectId, "")) { "Нельзя переименовать корень проекта" }
            val target = resolve(projectId, newPath)
            require(!target.exists()) { "Путь уже существует: $newPath" }
            target.parentFile?.mkdirs()
            check(source.renameTo(target)) {
                "Не удалось переместить $oldPath → $newPath"
            }
            touch(projectId)
        }

    suspend fun deleteEntry(projectId: String, relativePath: String) =
        withContext(Dispatchers.IO) {
            val root = resolve(projectId, "")
            val target = resolve(projectId, relativePath)
            require(target != root) { "Нельзя удалить корень проекта" }
            backup(projectId, relativePath, target)
            check(target.deleteRecursively()) { "Не удалось удалить: $relativePath" }
            touch(projectId)
        }

    data class SyncResult(
        val added: Int,
        val updated: Int,
        val skipped: Int,
    )

    suspend fun syncFromFolder(
        projectId: String,
        source: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncResult = withContext(Dispatchers.IO) {
        val root = resolve(projectId, "")
        val tree = DocumentFile.fromTreeUri(context, source)
            ?: throw IOException("Не удалось открыть привязанную папку")
        val files = collectDocumentFiles(tree, depth = 0)
        var result = SyncResult(0, 0, 0)
        for ((index, doc) in files.withIndex()) {
            onProgress(index, files.size)
            if (doc.length > MAX_IMPORTED_FILE_BYTES) {
                result = result.copy(skipped = result.skipped + 1)
                continue
            }
            val target = File(root, doc.relativePath)
            target.parentFile?.mkdirs()
            val existed = target.exists()
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                result = result.copy(skipped = result.skipped + 1)
                return@run
            }
            result = if (existed) {
                result.copy(updated = result.updated + 1)
            } else {
                result.copy(added = result.added + 1)
            }
            onProgress(index + 1, files.size)
        }
        touch(projectId)
        result
    }

    private class RemoteDoc(
        val uri: android.net.Uri,
        val relativePath: String,
        val length: Long,
    )

    private fun collectDocumentFiles(dir: DocumentFile, depth: Int): List<RemoteDoc> {
        require(depth <= MAX_EXPORT_DEPTH) { "Слишком глубокая структура папок" }
        val out = mutableListOf<RemoteDoc>()
        for (child in dir.listFiles().take(MAX_IMPORTED_FILES)) {
            val name = child.name ?: continue
            when {
                child.isDirectory && name !in exportIgnoredDirectories ->
                    out += collectDocumentFiles(child, depth + 1).map {
                        RemoteDoc(it.uri, "$name/${it.relativePath}", it.length)
                    }
                child.isFile && !name.endsWith(PENDING_SUFFIX) ->
                    out += RemoteDoc(child.uri, name, child.length())
            }
        }
        return out
    }

    data class ZipExportResult(
        val files: Int,
        val bytes: Long,
    )

    suspend fun exportZip(
        projectId: String,
        target: Uri,
        onProgress: (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ZipExportResult = withContext(Dispatchers.IO) {
        val root = resolve(projectId, "")
        val entries = root.walkTopDown()
            .onEnter { it.name !in exportIgnoredDirectories }
            .filter { it.isFile && !it.name.endsWith(PENDING_SUFFIX) }
            .toList()
        val total = entries.sumOf { it.length() }
        onProgress(0, total)
        val output = context.contentResolver.openOutputStream(target, "wt")
            ?: throw IOException("Не удалось создать архив в выбранной папке")
        var written = 0L
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            for (file in entries) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val entry = ZipEntry(relative).apply {
                    time = file.lastModified().coerceAtLeast(0)
                }
                zip.putNextEntry(entry)
                file.inputStream().use { input ->
                    val buffer = ByteArray(ZIP_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
                zip.closeEntry()
            }
            zip.finish()
        }
        ZipExportResult(files = entries.size, bytes = written)
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    suspend fun delete(projectId: String) = withContext(Dispatchers.IO) {
        val project = get(projectId) ?: return@withContext
        File(project.rootPath).deleteRecursively()
        persist(_projects.value.filterNot { it.id == projectId })
    }

    fun get(projectId: String?): Project? =
        _projects.value.firstOrNull { it.id == projectId }

    fun resolve(projectId: String, relativePath: String): File {
        val project = get(projectId) ?: error("Проект не найден")
        return PathGuard.resolveWithin(File(project.rootPath), relativePath)
    }

    suspend fun tree(projectId: String): List<FileNode> = withContext(Dispatchers.IO) {
        val root = resolve(projectId, "")
        root.listFiles().orEmpty()
            .filterNot { it.name == ".neurocode" }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { toNode(root, it, 0) }
    }

    suspend fun readText(projectId: String, relativePath: String): String =
        withContext(Dispatchers.IO) {
            val file = resolve(projectId, relativePath)
            require(file.isFile) { "Файл не найден: $relativePath" }
            require(file.length() <= MAX_TEXT_FILE_BYTES) { "Файл слишком большой для редактора" }
            require(!looksBinary(file)) { "Двоичный файл нельзя открыть как текст" }
            file.readText()
        }

    suspend fun writeText(projectId: String, relativePath: String, content: String) =
        withContext(Dispatchers.IO) {
            require(content.toByteArray().size <= MAX_TEXT_FILE_BYTES) {
                "Файл превышает лимит 2 МБ"
            }
            val file = resolve(projectId, relativePath)
            file.parentFile?.mkdirs()
            if (file.exists()) backup(projectId, relativePath, file)
            val pending = File(file.parentFile, file.name + ".neurocode.tmp")
            pending.writeText(content)
            if (!pending.renameTo(file)) {
                pending.copyTo(file, overwrite = true)
                pending.delete()
            }
            touch(projectId)
        }

    suspend fun createFile(projectId: String, relativePath: String) =
        writeText(projectId, relativePath, "")

    suspend fun createDirectory(projectId: String, relativePath: String) =
        withContext(Dispatchers.IO) {
            check(resolve(projectId, relativePath).mkdirs()) {
                "Не удалось создать папку или она уже существует"
            }
            touch(projectId)
        }

    suspend fun deleteFile(projectId: String, relativePath: String) =
        withContext(Dispatchers.IO) {
            val root = resolve(projectId, "")
            val file = resolve(projectId, relativePath)
            require(file != root) { "Нельзя удалить корень проекта" }
            require(file.isFile) { "Удаление доступно только для файлов: $relativePath" }
            backup(projectId, relativePath, file)
            check(file.delete()) { "Не удалось удалить файл $relativePath" }
            touch(projectId)
        }

    suspend fun search(projectId: String, query: String, limit: Int = 100): List<SearchHit> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val root = resolve(projectId, "")
            val result = mutableListOf<SearchHit>()
            root.walkTopDown()
                .onEnter { it.name !in ignoredDirectories }
                .filter { it.isFile && it.length() <= SEARCH_FILE_BYTES && !looksBinary(it) }
                .forEach { file ->
                    if (result.size >= limit) return@forEach
                    runCatching {
                        file.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                if (result.size < limit && line.contains(query, ignoreCase = true)) {
                                    result += SearchHit(
                                        path = file.relativeTo(root).invariantSeparatorsPath,
                                        line = index + 1,
                                        preview = line.trim().take(240),
                                    )
                                }
                            }
                        }
                    }
                }
            result
        }

    private suspend fun persist(projects: List<Project>) {
        store.write(projects)
        _projects.value = projects
    }

    private suspend fun touch(projectId: String) {
        persist(_projects.value.map {
            if (it.id == projectId) it.copy(updatedAt = System.currentTimeMillis()) else it
        })
    }

    private fun toNode(root: File, file: File, depth: Int): FileNode {
        val children = if (file.isDirectory && depth < 8 && file.name !in ignoredDirectories) {
            file.listFiles().orEmpty()
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                .take(500)
                .map { toNode(root, it, depth + 1) }
        } else {
            emptyList()
        }
        return FileNode(
            name = file.name,
            relativePath = file.relativeTo(root).invariantSeparatorsPath,
            directory = file.isDirectory,
            size = if (file.isFile) file.length() else 0,
            children = children,
        )
    }

    private fun copyDocumentTree(source: DocumentFile, target: File, depth: Int) {
        require(depth <= 32) { "Слишком глубокая структура папок" }
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles().take(MAX_IMPORTED_FILES).forEach { child ->
                val name = sanitizeName(child.name ?: return@forEach)
                copyDocumentTree(child, File(target, name), depth + 1)
            }
        } else if (source.isFile) {
            require(source.length() <= MAX_IMPORTED_FILE_BYTES) {
                "Файл ${source.name} больше 200 МБ"
            }
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Не удалось прочитать ${source.name}")
        }
    }

    private fun backup(projectId: String, relativePath: String, source: File) {
        val root = resolve(projectId, ".neurocode/history/${System.currentTimeMillis()}")
        val target = File(root, relativePath)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun looksBinary(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val sample = ByteArray(4096)
            val count = input.read(sample)
            count > 0 && sample.take(count).count { it == 0.toByte() } > 0
        }
    }.getOrDefault(true)

    private fun sanitizeName(value: String): String {
        val clean = value.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(80)
            .ifBlank { "item" }
        return if (clean == "." || clean == "..") "item" else clean
    }

    companion object {
        private const val MAX_TEXT_FILE_BYTES = 2L * 1024 * 1024
        private const val SEARCH_FILE_BYTES = 1024L * 1024
        private const val MAX_IMPORTED_FILE_BYTES = 200L * 1024 * 1024
        private const val MAX_IMPORTED_FILES = 10_000
        private const val MAX_EXPORT_DEPTH = 32
        private const val PENDING_SUFFIX = ".neurocode.tmp"
        private const val ZIP_BUFFER_SIZE = 64 * 1024
        private val ignoredDirectories = setOf(".git", "build", ".gradle", ".idea", "node_modules")
        private val exportIgnoredDirectories = setOf(".neurocode")
    }
}
