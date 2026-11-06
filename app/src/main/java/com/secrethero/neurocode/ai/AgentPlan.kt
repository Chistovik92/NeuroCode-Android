package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.AgentPlan
import com.secrethero.neurocode.model.AgentPlanStatus
import com.secrethero.neurocode.model.AgentTask
import com.secrethero.neurocode.model.AgentTaskStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Разбор недоверенных аргументов модели и переходы состояния без Android-зависимостей. */
object AgentPlanTools {
    fun todos(arguments: JsonObject): List<AgentTask> {
        val items = arguments["items"] as? JsonArray ?: error("Нужен список items")
        require(items.size <= MAX_TASKS) { "Не больше $MAX_TASKS задач" }
        val tasks = items.mapIndexed { index, element ->
            val item = element as? JsonObject ?: error("Задача ${index + 1} не объект")
            val text = item["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(text.isNotEmpty()) { "Пустой текст задачи ${index + 1}" }
            val status = when (item["status"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                null, "pending" -> AgentTaskStatus.PENDING
                "in_progress" -> AgentTaskStatus.IN_PROGRESS
                "done" -> AgentTaskStatus.DONE
                else -> error("Неизвестный статус задачи ${index + 1}")
            }
            AgentTask(text = text.take(MAX_TASK_CHARS), status = status)
        }
        require(tasks.count { it.status == AgentTaskStatus.IN_PROGRESS } <= 1) {
            "Только одна задача может выполняться"
        }
        return tasks
    }

    fun goal(arguments: JsonObject): String =
        arguments["text"]?.jsonPrimitive?.contentOrNull?.trim()?.take(MAX_GOAL_CHARS)
            ?.takeIf { it.isNotEmpty() } ?: error("Нужен текст цели")

    fun presentPath(arguments: JsonObject): String =
        arguments["path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: error("Нужен path")

    fun plan(arguments: JsonObject): AgentPlan {
        val text = arguments["plan"]?.jsonPrimitive?.contentOrNull?.trim()?.take(MAX_PLAN_CHARS)
            ?.takeIf { it.isNotEmpty() } ?: error("Пустой план")
        return AgentPlan(text = text)
    }

    fun reviewPlan(plan: AgentPlan, approved: Boolean, comment: String): AgentPlan = plan.copy(
        status = if (approved) AgentPlanStatus.APPROVED else AgentPlanStatus.REJECTED,
        comment = comment.trim().take(MAX_PLAN_CHARS),
    )

    fun todoStatus(tasks: List<AgentTask>): String = if (tasks.isEmpty()) {
        "Список задач пуст"
    } else {
        buildString {
            tasks.forEach { task ->
                append(when (task.status) {
                    AgentTaskStatus.PENDING -> "[ ] "
                    AgentTaskStatus.IN_PROGRESS -> "[→] "
                    AgentTaskStatus.DONE -> "[x] "
                }).append(task.text).append('\n')
            }
        }.trim()
    }

    fun taskSummary(tasks: List<AgentTask>): String {
        val done = tasks.count { it.status == AgentTaskStatus.DONE }
        return "Задачи: $done/${tasks.size}"
    }

    const val MAX_TASKS = 30
    const val MAX_TASK_CHARS = 300
    const val MAX_GOAL_CHARS = 1_000
    const val MAX_PLAN_CHARS = 12_000
}
