package com.secrethero.neurocode.ai

import android.content.Context
import androidx.annotation.StringRes
import com.secrethero.neurocode.R
import com.secrethero.neurocode.data.ProjectRepository
import com.secrethero.neurocode.git.GitRepository
import com.secrethero.neurocode.model.ApprovalRisk
import com.secrethero.neurocode.model.ExternalAgentTool
import com.secrethero.neurocode.model.ToolApprovalRequest
import com.secrethero.neurocode.model.ToolCall
import com.secrethero.neurocode.terminal.ApprovalGate
import com.secrethero.neurocode.terminal.ShellSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AgentTools(
    private val context: Context,
    private val projects: ProjectRepository,
    private val git: GitRepository,
    private val shell: ShellSession,
    private val approvals: ApprovalGate,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun str(@StringRes resId: Int, vararg args: Any?): String =
        context.getString(resId, *args)

    fun definitions(externalTools: List<ExternalAgentTool> = emptyList()): JsonArray = buildJsonArray {
        add(tool(
            name = "list_files",
            description = "Показать дерево файлов активного проекта.",
            properties = emptyMap(),
        ))
        add(tool(
            name = "read_file",
            description = "Прочитать текстовый файл проекта.",
            properties = mapOf(
                "path" to property("string", "Относительный путь от корня проекта"),
            ),
            required = listOf("path"),
        ))
        add(tool(
            name = "write_file",
            description = "Создать или полностью перезаписать текстовый файл проекта. Требует подтверждения пользователя.",
            properties = mapOf(
                "path" to property("string", "Относительный путь от корня проекта"),
                "content" to property("string", "Полное новое содержимое файла"),
            ),
            required = listOf("path", "content"),
        ))
        add(tool(
            name = "replace_in_file",
            description = "Заменить уникальный фрагмент текстового файла. Экономнее полной перезаписи. Требует подтверждения пользователя.",
            properties = mapOf(
                "path" to property("string", "Относительный путь от корня проекта"),
                "search" to property("string", "Точный существующий фрагмент, ровно одно вхождение"),
                "replacement" to property("string", "Текст для замены"),
            ),
            required = listOf("path", "search", "replacement"),
        ))
        add(tool(
            name = "delete_file",
            description = "Удалить файл проекта (папки удалять нельзя). Копия сохраняется в .neurocode/history. Требует подтверждения пользователя.",
            properties = mapOf(
                "path" to property("string", "Относительный путь от корня проекта"),
            ),
            required = listOf("path"),
        ))
        add(tool(
            name = "search_text",
            description = "Найти текст во всех небольших текстовых файлах проекта.",
            properties = mapOf(
                "query" to property("string", "Искомый текст"),
                "limit" to property("integer", "Максимум результатов, от 1 до 100"),
            ),
            required = listOf("query"),
        ))
        add(tool(
            name = "run_command",
            description = "Выполнить команду shell в корне проекта. Если установлено Linux-окружение " +
                "(proot), команда выполняется внутри Alpine Linux; иначе доступен ограниченный " +
                "/system/bin без пакетного менеджера.",
            properties = mapOf(
                "command" to property("string", "Команда shell"),
                "timeout_seconds" to property("integer", "Тайм-аут от 1 до 120 секунд"),
            ),
            required = listOf("command"),
        ))
        add(tool(
            name = "git_status",
            description = "Получить состояние Git активного проекта.",
            properties = emptyMap(),
        ))
        add(tool(
            name = "git_diff",
            description = "Получить Git diff активного проекта.",
            properties = mapOf(
                "staged" to property("boolean", "true для проиндексированных изменений"),
            ),
        ))
        externalTools.filter { it.enabled }.forEach { external ->
            add(tool(
                name = externalName(external),
                description = "${external.name}: ${external.description}. Команда фиксирована настройками и потребует подтверждения.",
                properties = emptyMap(),
            ))
        }
    }

    suspend fun execute(
        projectId: String,
        call: ToolCall,
        allowAgentShell: Boolean,
        externalTools: List<ExternalAgentTool> = emptyList(),
    ): String = runCatching {
        val arguments = json.parseToJsonElement(call.arguments).jsonObject
        when (call.name) {
            "list_files" -> json.encodeToString(projects.tree(projectId)).take(MAX_TOOL_OUTPUT)
            "read_file" -> {
                val path = arguments.string("path")
                projects.readText(projectId, path).take(MAX_TOOL_OUTPUT)
            }
            "write_file" -> {
                val path = arguments.string("path")
                val content = arguments.string("content")
                val approved = approvals.ask(
                    ToolApprovalRequest(
                        title = str(R.string.approve_write_file),
                        details = "$path\n\n${content.take(1_200)}",
                        risk = ApprovalRisk.FILE_WRITE,
                    ),
                )
                if (!approved) return str(R.string.deny_write_format, path)
                projects.writeText(projectId, path, content)
                str(R.string.saved_format, path, content.length)
            }
            "replace_in_file" -> {
                val path = arguments.string("path")
                val search = arguments.string("search")
                val replacement = arguments.string("replacement")
                require(search.isNotEmpty()) { "Параметр search пуст" }
                val original = projects.readText(projectId, path)
                val occurrences = countOccurrences(original, search)
                require(occurrences == 1) {
                    "Фрагмент должен иметь ровно одно вхождение, найдено: $occurrences"
                }
                val approved = approvals.ask(
                    ToolApprovalRequest(
                        title = str(R.string.approve_replace_file),
                        details = "$path\n\n− ${search.take(600)}\n+ ${replacement.take(600)}",
                        risk = ApprovalRisk.FILE_WRITE,
                    ),
                )
                if (!approved) return str(R.string.deny_edit_format, path)
                projects.writeText(projectId, path, original.replaceFirst(search, replacement))
                str(R.string.edited_format, path, search.length, replacement.length)
            }
            "delete_file" -> {
                val path = arguments.string("path")
                val approved = approvals.ask(
                    ToolApprovalRequest(
                        title = str(R.string.approve_delete_file),
                        details = path + str(R.string.approve_keep_history),
                        risk = ApprovalRisk.DESTRUCTIVE,
                    ),
                )
                if (!approved) return str(R.string.deny_delete_format, path)
                projects.deleteFile(projectId, path)
                str(R.string.deleted_format, path)
            }
            "search_text" -> {
                val query = arguments.string("query")
                val limit = arguments["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 50
                json.encodeToString(projects.search(projectId, query, limit)).take(MAX_TOOL_OUTPUT)
            }
            "run_command" -> {
                val command = arguments.string("command").take(2_000)
                val timeout = arguments["timeout_seconds"]?.jsonPrimitive?.intOrNull
                    ?.coerceIn(1, 120)?.times(1_000L) ?: 60_000L
                val risky = CommandPolicy.risk(command)
                val unattended = allowAgentShell && risky == null && CommandPolicy.isSafeReadOnly(command)
                if (!unattended) {
                    val approved = approvals.ask(
                        ToolApprovalRequest(
                            title = if (risky != null) {
                                str(R.string.dangerous_command)
                            } else {
                                str(R.string.approve_command)
                            },
                            details = buildString {
                                append("$ ")
                                append(command)
                                risky?.let { append(str(R.string.reason_format, it)) }
                            },
                            risk = if (risky != null) ApprovalRisk.DESTRUCTIVE else ApprovalRisk.SHELL,
                        ),
                    )
                    if (!approved) return str(R.string.deny_command)
                }
                shell.runOnce(projectId, command, timeout).take(MAX_TOOL_OUTPUT)
            }
            "git_status" -> git.status(projectId).toString()
            "git_diff" -> {
                val staged = arguments["staged"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                git.diff(projectId, staged).ifBlank { "Изменений нет" }.take(MAX_TOOL_OUTPUT)
            }
            else -> executeExternal(projectId, call, externalTools)
        }
    }.getOrElse { error ->
        str(R.string.tool_error_format, call.name, error.message ?: error::class.java.simpleName)
    }

    private suspend fun executeExternal(
        projectId: String,
        call: ToolCall,
        externalTools: List<ExternalAgentTool>,
    ): String {
        val tool = externalTools.firstOrNull {
            it.enabled && externalName(it) == call.name
        } ?: return str(R.string.unknown_tool_format, call.name)
        val command = tool.command.trim().take(2_000)
        require(command.isNotBlank()) { "Внешний инструмент ${tool.name} не содержит команду" }
        val risk = CommandPolicy.risk(command)
        val approved = approvals.ask(
            ToolApprovalRequest(
                title = if (risk == null) {
                    str(R.string.external_tool_title)
                } else {
                    str(R.string.dangerous_external_tool)
                },
                details = buildString {
                    append(tool.name).append("\n$ ").append(command)
                    risk?.let { append(str(R.string.reason_format, it)) }
                },
                risk = if (risk == null) ApprovalRisk.SHELL else ApprovalRisk.DESTRUCTIVE,
            ),
        )
        if (!approved) return str(R.string.deny_external_format, tool.name)
        return shell.runOnce(projectId, command, 120_000).take(MAX_TOOL_OUTPUT)
    }

    private fun externalName(tool: ExternalAgentTool): String =
        "custom_" + tool.id.filter { it.isLetterOrDigit() }.take(12).lowercase()

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = haystack.indexOf(needle, index)
            if (index < 0) return count
            count++
            index += needle.length
        }
    }

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("function", buildJsonObject {
            put("name", JsonPrimitive(name))
            put("description", JsonPrimitive(description))
            put("parameters", buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    properties.forEach { (key, value) -> put(key, value) }
                })
                put("additionalProperties", JsonPrimitive(false))
                if (required.isNotEmpty()) {
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                }
            })
        })
    }

    private fun property(type: String, description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("description", JsonPrimitive(description))
    }

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?: error("Отсутствует параметр $name")

    companion object {
        private const val MAX_TOOL_OUTPUT = 24_000
    }
}
