package com.secrethero.neurocode.ai

import android.content.Context
import androidx.annotation.StringRes
import com.secrethero.neurocode.R
import com.secrethero.neurocode.data.FilesystemAccess
import com.secrethero.neurocode.data.ProjectRepository
import com.secrethero.neurocode.model.ApprovalRisk
import com.secrethero.neurocode.model.ToolApprovalRequest
import com.secrethero.neurocode.terminal.ApprovalGate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Инструменты агента для файловой системы Android вне проекта (0.8.2).
 * Чтение (list/search/read) выполняется без подтверждения — корни уже разрешены пользователем;
 * импорт в проект проходит ApprovalGate. Вынесено из [AgentTools], чтобы не раздувать его.
 */
class FsAgentTools(
    private val context: Context,
    private val fs: FilesystemAccess,
    private val projects: ProjectRepository,
    private val approvals: ApprovalGate,
    private val rootsProvider: () -> List<String>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun str(@StringRes resId: Int, vararg args: Any?): String =
        context.getString(resId, *args)

    fun definitions(): List<JsonObject> = listOf(
        function(
            name = "fs_list",
            description = "Показать содержимое папки в разрешённой пользователем области памяти " +
                "устройства (вне проекта). Доступны только папки из «Корни доступа».",
            properties = mapOf(
                "root" to "Имя разрешённой папки, как в списке корней доступа",
                "path" to "Подпуть внутри корня; пусто — сам корень",
            ),
            required = listOf("root"),
        ),
        function(
            name = "fs_search",
            description = "Найти файлы в разрешённых папках: по имени, расширению, размеру " +
                "или содержимому небольших текстовых файлов.",
            properties = mapOf(
                "query" to "Текст в имени или содержимом файла; пусто — все файлы",
                "extension" to "Расширение без точки, например pdf",
                "limit" to "Максимум результатов, от 1 до 100",
            ),
        ),
        function(
            name = "fs_read",
            description = "Прочитать текстовый файл (до 1 МБ) из разрешённой пользователем папки.",
            properties = mapOf(
                "root" to "Имя разрешённой папки",
                "path" to "Путь файла внутри корня",
            ),
            required = listOf("root", "path"),
        ),
        function(
            name = "fs_import",
            description = "Скопировать файл из разрешённой папки в проект (в imported/, без перезаписи). " +
                "Требует подтверждения пользователя.",
            properties = mapOf(
                "root" to "Имя разрешённой папки",
                "path" to "Путь файла внутри корня",
            ),
            required = listOf("root", "path"),
        ),
    )

    suspend fun execute(projectId: String, name: String, arguments: JsonObject): String =
        when (name) {
            "fs_list" -> list(arguments)
            "fs_search" -> search(arguments)
            "fs_read" -> read(arguments)
            "fs_import" -> import(projectId, arguments)
            else -> error("Неизвестный инструмент $name")
        }

    private suspend fun list(arguments: JsonObject): String {
        val roots = rootsProvider.invoke()
        val rootUri = resolveRoot(arguments, roots)
        val path = arguments.optional("path").trim('/')
        val entries = fs.list(roots, rootUri, path)
        if (entries.isEmpty()) return "Папка пуста: ${fs.displayPath(roots, rootUri, path)}"
        return json.encodeToString(entries).take(MAX_TOOL_OUTPUT)
    }

    private suspend fun search(arguments: JsonObject): String {
        val roots = rootsProvider.invoke()
        require(roots.isNotEmpty()) { str(R.string.fs_no_roots) }
        val query = arguments.optional("query")
        val extension = arguments.optional("extension")
            .trim().removePrefix(".").takeIf { it.isNotBlank() }
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(MIN_SEARCH_LIMIT, MAX_SEARCH_LIMIT) ?: DEFAULT_SEARCH_LIMIT
        val hits = fs.search(roots, query, extension, limit)
        if (hits.isEmpty()) return "Совпадений нет"
        return json.encodeToString(hits).take(MAX_TOOL_OUTPUT)
    }

    private suspend fun read(arguments: JsonObject): String {
        val roots = rootsProvider.invoke()
        val rootUri = resolveRoot(arguments, roots)
        val path = arguments.string("path").trim('/')
        val text = fs.readText(roots, rootUri, path)
            ?: return "Файл двоичный или не найден: ${fs.displayPath(roots, rootUri, path)}"
        return text.take(MAX_TOOL_OUTPUT)
    }

    private suspend fun import(projectId: String, arguments: JsonObject): String {
        val roots = rootsProvider.invoke()
        val rootUri = resolveRoot(arguments, roots)
        val path = arguments.string("path").trim('/')
        val fullPath = fs.displayPath(roots, rootUri, path)
        val approved = approvals.ask(
            ToolApprovalRequest(
                title = str(R.string.approve_fs_import),
                details = str(R.string.approve_fs_import_details, fullPath, "imported/"),
                risk = ApprovalRisk.FILE_WRITE,
            ),
        )
        if (!approved) return str(R.string.deny_fs_import, fullPath)
        val target = fs.importToProject(roots, rootUri, path, projectId, projects)
        return str(R.string.fs_imported_format, fullPath, target)
    }

    /** Разрешённый корень по имени из списка корней доступа; имя регистронезависимо. */
    private fun resolveRoot(arguments: JsonObject, granted: List<String>): String {
        require(granted.isNotEmpty()) { str(R.string.fs_no_roots) }
        val requested = arguments.optional("root").trim()
        val available = fs.roots(granted)
        val match = available.firstOrNull {
            it.displayName.equals(requested, ignoreCase = true) || it.uri == requested
        } ?: error(
            str(
                R.string.fs_unknown_root_format,
                requested,
                available.joinToString { root -> root.displayName },
            ),
        )
        return match.uri
    }

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?: error("Отсутствует параметр $name")

    private fun JsonObject.optional(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun function(
        name: String,
        description: String,
        properties: Map<String, String>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    properties.forEach { (key, hint) -> put(key, buildJsonObject {
                        put("type", guessType(key))
                        put("description", hint)
                    }) }
                })
                put("additionalProperties", false)
                if (required.isNotEmpty()) {
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                }
            })
        })
    }

    private fun guessType(key: String): String = when (key) {
        "limit" -> "integer"
        else -> "string"
    }

    companion object {
        private const val MAX_TOOL_OUTPUT = 24_000
        private const val MIN_SEARCH_LIMIT = 1
        private const val MAX_SEARCH_LIMIT = 100
        private const val DEFAULT_SEARCH_LIMIT = 30
    }
}
