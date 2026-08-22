package com.secrethero.neurocode.ai

import com.secrethero.neurocode.data.ProjectRepository
import com.secrethero.neurocode.git.GitRepository
import com.secrethero.neurocode.model.ApprovalRisk
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
    private val projects: ProjectRepository,
    private val git: GitRepository,
    private val shell: ShellSession,
    private val approvals: ApprovalGate,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun definitions(): JsonArray = buildJsonArray {
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
            description = "Выполнить команду Android shell в корне проекта. Это ограниченная /system/bin оболочка, не полноценный Linux.",
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
    }

    suspend fun execute(
        projectId: String,
        call: ToolCall,
        allowAgentShell: Boolean,
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
                        title = "Разрешить изменение файла?",
                        details = "$path\n\n${content.take(1_200)}",
                        risk = ApprovalRisk.FILE_WRITE,
                    ),
                )
                if (!approved) return "Пользователь запретил изменение $path"
                projects.writeText(projectId, path, content)
                "Файл $path сохранён (${content.length} символов)"
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
                            title = if (risky != null) "Опасная команда" else "Разрешить команду?",
                            details = buildString {
                                append("$ ")
                                append(command)
                                risky?.let { append("\n\nПричина: ").append(it) }
                            },
                            risk = if (risky != null) ApprovalRisk.DESTRUCTIVE else ApprovalRisk.SHELL,
                        ),
                    )
                    if (!approved) return "Пользователь запретил выполнение команды"
                }
                shell.runOnce(projectId, command, timeout).take(MAX_TOOL_OUTPUT)
            }
            "git_status" -> git.status(projectId).toString()
            "git_diff" -> {
                val staged = arguments["staged"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                git.diff(projectId, staged).ifBlank { "Изменений нет" }.take(MAX_TOOL_OUTPUT)
            }
            else -> "Неизвестный инструмент: ${call.name}"
        }
    }.getOrElse { error ->
        "Ошибка инструмента ${call.name}: ${error.message ?: error::class.java.simpleName}"
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
