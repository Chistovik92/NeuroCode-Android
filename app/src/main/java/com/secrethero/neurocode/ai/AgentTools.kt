package com.secrethero.neurocode.ai

import android.content.Context
import androidx.annotation.StringRes
import com.secrethero.neurocode.R
import com.secrethero.neurocode.data.FilesystemAccess
import com.secrethero.neurocode.data.ProjectRepository
import com.secrethero.neurocode.git.GitRepository
import com.secrethero.neurocode.model.AgentPermissionMode
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

/** Зависимости инструментов доступа к файловой системе (0.8.2); null — инструменты fs_* недоступны. */
class FsTools(
    val tools: FsAgentTools,
)

class AgentTools(
    private val context: Context,
    private val projects: ProjectRepository,
    private val git: GitRepository,
    private val shell: ShellSession,
    private val approvals: ApprovalGate,
    private val fsTools: FsTools? = null,
    private val interaction: AgentInteraction = AgentInteraction(),
    private val jobs: AgentJobs? = null,
    private val runSubagent: (suspend (String, String, String, Int) -> String)? = null,
    private val subagentPolicy: SubagentPolicy = SubagentPolicy(),
    private val subagentDepth: Int = 0,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun str(@StringRes resId: Int, vararg args: Any?): String =
        context.getString(resId, *args)

    suspend fun fsRootName(call: ToolCall): String? {
        val tools = fsTools?.tools
        if (tools == null || !call.name.startsWith("fs_")) return null
        val arguments = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrNull()
        return arguments?.let { tools.rootLabel(call.name, it) }
    }

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
        add(tool(
            name = "todo",
            description = "Заменить видимый чек-лист задач; не больше одной in_progress.",
            properties = mapOf("items" to buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("items", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("text", property("string", "Задача"))
                        put("status", property("string", "pending, in_progress или done"))
                    })
                })
            }),
            required = listOf("items"),
        ))
        add(tool("goal", "Закрепить цель сессии.", mapOf("text" to property("string", "Цель")), listOf("text")))
        add(tool("ask_user_question", "Задать уточнение с необязательными вариантами; можно пропустить.", mapOf(
            "question" to property("string", "Вопрос"),
            "options" to buildJsonObject { put("type", JsonPrimitive("array")); put("items", property("string", "Вариант")) },
        ), listOf("question")))
        add(tool("exit_plan_mode", "Показать план пользователю для одобрения или замечаний.", mapOf(
            "plan" to property("string", "Полный план"),
        ), listOf("plan")))
        add(tool("run_in_background", "Запустить одобренную долгую shell-команду в фоне; вернуть job id.", mapOf(
            "command" to property("string", "Команда shell"),
        ), listOf("command")))
        add(tool("job_check", "Проверить статус фоновой команды.", mapOf("id" to property("string", "Job id")), listOf("id")))
        add(tool("job_read", "Прочитать ограниченный вывод фоновой команды.", mapOf("id" to property("string", "Job id")), listOf("id")))
        add(tool("job_stop", "Остановить фоновую команду.", mapOf("id" to property("string", "Job id")), listOf("id")))
        add(tool("subagent", "Выполнить независимую подзадачу отдельным контекстом с ограниченной глубиной.", mapOf(
            "task" to property("string", "Конкретная самодостаточная подзадача"),
            "description" to property("string", "Короткое название"),
        ), listOf("task")))
        add(tool("present", "Показать существующий итоговый файл проекта карточкой.", mapOf(
            "path" to property("string", "Относительный путь существующего файла"),
        ), listOf("path")))
        addFsDefinitions().forEach { add(it) }
        externalTools.filter { it.enabled }.forEach { external ->
            add(tool(
                name = externalName(external),
                description = "${external.name}: ${external.description}. Команда фиксирована настройками и потребует подтверждения.",
                properties = emptyMap(),
            ))
        }
    }

    /** Инструменты доступа к файловой системе Android за пределами проекта (0.8.2). */
    private fun addFsDefinitions(): List<JsonObject> =
        fsTools?.tools?.definitions().orEmpty()

    suspend fun execute(
        projectId: String,
        call: ToolCall,
        allowAgentShell: Boolean,
        externalTools: List<ExternalAgentTool> = emptyList(),
        permissionMode: AgentPermissionMode = AgentPermissionMode.ASK_ALWAYS,
        planMode: Boolean = false,
        onEvent: suspend (AgentEvent) -> Unit = {},
    ): String = runCatching {
        // Единый барьер: в режиме плана/только чтения запрещены все изменения и shell,
        // включая внешние и ещё не зарегистрированные инструменты (fail closed).
        if (planMode || permissionMode == AgentPermissionMode.READ_ONLY) {
            require(call.name !in MUTATING_TOOLS && !call.name.startsWith("custom_")) {
                "В текущем режиме доступны только чтение, планирование и вопросы"
            }
        }
        // Некоторые модели присылают пустую строку вместо «{}» для инструментов без параметров.
        val arguments = json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject
        when (call.name) {
            "list_files" -> json.encodeToString(projects.tree(projectId)).take(MAX_TOOL_OUTPUT)
            "read_file" -> {
                val path = arguments.string("path")
                projects.readText(projectId, path).take(MAX_TOOL_OUTPUT)
            }
            "write_file" -> {
                val path = arguments.string("path")
                val content = arguments.string("content")
                val approved = permissionMode == AgentPermissionMode.AUTO_EDIT || approvals.ask(
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
                val approved = permissionMode == AgentPermissionMode.AUTO_EDIT || approvals.ask(
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
                val approved = permissionMode == AgentPermissionMode.AUTO_EDIT || approvals.ask(
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
                    val approved = permissionMode == AgentPermissionMode.AUTO_EDIT || approvals.ask(
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
            "todo" -> AgentPlanTools.todos(arguments).also {
                onEvent(AgentEvent.TasksChanged(it))
            }.let(AgentPlanTools::taskSummary)
            "goal" -> AgentPlanTools.goal(arguments).also {
                onEvent(AgentEvent.GoalChanged(it))
            }
            "ask_user_question" -> {
                val answer = interaction.ask(AgentQuestion(
                    text = arguments.string("question").take(AgentInteraction.MAX_ANSWER_CHARS),
                    options = (arguments["options"] as? JsonArray).orEmpty().mapNotNull {
                        it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                    }.distinct().take(AgentInteraction.MAX_OPTIONS).map { AgentQuestionOption(it) },
                )) ?: "Пользователь пропустил вопрос или время ответа истекло"
                answer
            }
            "exit_plan_mode" -> AgentPlanTools.plan(arguments).also {
                onEvent(AgentEvent.PlanReady(it))
            }.text
            "run_in_background" -> {
                require(!planMode && permissionMode != AgentPermissionMode.READ_ONLY) { "Фоновые команды запрещены в режиме чтения" }
                val command = arguments.string("command").take(2_000)
                val risk = CommandPolicy.risk(command)
                val approved = permissionMode == AgentPermissionMode.AUTO_EDIT || approvals.ask(ToolApprovalRequest(
                    title = str(R.string.approve_command),
                    details = "$ $command",
                    risk = if (risk == null) ApprovalRisk.SHELL else ApprovalRisk.DESTRUCTIVE,
                ))
                if (!approved) return str(R.string.deny_command)
                jobs?.start(projectId, command)?.id ?: "Фоновые задачи недоступны"
            }
            "job_check" -> jobs?.check(arguments.string("id"))?.toString() ?: "Фоновая задача не найдена"
            "job_read" -> jobs?.read(arguments.string("id")) ?: "Фоновая задача не найдена"
            "job_stop" -> if (jobs?.stop(arguments.string("id")) == true) "Остановлено" else "Задача уже завершена или не найдена"
            "subagent" -> {
                require(!planMode && permissionMode != AgentPermissionMode.READ_ONLY) { "Субагенты недоступны в режиме плана" }
                val sanitized = subagentPolicy.sanitize(
                    arguments.string("task"),
                    arguments["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                runSubagent?.invoke(projectId, sanitized.task, sanitized.description, subagentDepth + 1) ?: "Субагенты недоступны"
            }
            "present" -> {
                val path = AgentPlanTools.presentPath(arguments)
                require(projects.resolve(projectId, path).isFile) { "Файл не найден: $path" }
                onEvent(AgentEvent.Deliverable(path))
                "Результат показан: $path"
            }
            "fs_list", "fs_search", "fs_read" ->
                fsDelegate { tools -> tools.execute(projectId, call.name, arguments) }
            "fs_import" -> {
                require(!planMode && permissionMode != AgentPermissionMode.READ_ONLY) { "Импорт запрещён в режиме чтения" }
                fsDelegate { tools -> tools.execute(projectId, call.name, arguments) }
            }
            else -> executeExternal(projectId, call, externalTools)
        }
    }.getOrElse { error ->
        // Отмена пользователя («Стоп») — не ошибка инструмента: пробрасываем, чтобы агентный
        // цикл оборвался немедленно, а не продолжал шаги со «сбойным» результатом.
        if (error is kotlinx.coroutines.CancellationException) throw error
        str(R.string.tool_error_format, call.name, error.message ?: error::class.java.simpleName)
    }

    private suspend fun fsDelegate(block: suspend (FsAgentTools) -> String): String {
        val tools = fsTools?.tools ?: error("Инструменты файловой системы недоступны")
        return block(tools)
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
        private val MUTATING_TOOLS = setOf(
            "write_file", "replace_in_file", "delete_file", "rename_file", "run_command",
            "run_in_background", "job_stop", "fs_import",
        )
        private const val MAX_TOOL_OUTPUT = 24_000
    }
}
