package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ProviderConfig

sealed interface AgentEvent {
    data class Status(val text: String) : AgentEvent
    data class ToolStarted(val name: String, val arguments: String) : AgentEvent
    data class ToolFinished(val name: String, val result: String) : AgentEvent
}

class AgentOrchestrator(
    private val client: OpenAiCompatibleClient,
    private val tools: AgentTools,
) {
    suspend fun run(
        projectId: String,
        projectName: String,
        provider: ProviderConfig,
        apiKey: String,
        history: List<ChatMessage>,
        maxSteps: Int,
        allowAgentShell: Boolean,
        onEvent: (AgentEvent) -> Unit = {},
    ): String {
        val messages = mutableListOf(
            ApiMessage(
                role = "system",
                content = systemPrompt(projectName),
            ),
        )
        history.takeLast(40)
            .filter { it.role != MessageRole.TOOL }
            .forEach { message ->
                messages += ApiMessage(
                    role = when (message.role) {
                        MessageRole.SYSTEM -> "system"
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                        MessageRole.TOOL -> "tool"
                    },
                    content = message.content,
                )
            }

        repeat(maxSteps.coerceIn(1, 20)) { index ->
            onEvent(AgentEvent.Status("Шаг агента ${index + 1}"))
            val turn = client.complete(provider, apiKey, messages, tools.definitions())
            messages += ApiMessage(
                role = "assistant",
                content = turn.content,
                toolCalls = turn.toolCalls.map {
                    ApiToolCall(it.id, it.name, it.arguments)
                },
            )
            if (turn.toolCalls.isEmpty()) {
                return turn.content.ifBlank { "Модель завершила работу без текстового ответа." }
            }

            turn.toolCalls.forEach { call ->
                onEvent(AgentEvent.ToolStarted(call.name, call.arguments))
                val result = tools.execute(projectId, call, allowAgentShell)
                onEvent(AgentEvent.ToolFinished(call.name, result))
                messages += ApiMessage(
                    role = "tool",
                    content = result,
                    toolCallId = call.id,
                )
            }
        }
        return "Достигнут лимит шагов агента. Уточните задачу или увеличьте лимит в настройках."
    }

    suspend fun chat(
        provider: ProviderConfig,
        apiKey: String,
        history: List<ChatMessage>,
    ): String {
        val messages = history.takeLast(50).map { message ->
            ApiMessage(
                role = when (message.role) {
                    MessageRole.SYSTEM -> "system"
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.TOOL -> "tool"
                },
                content = message.content,
            )
        }
        return client.complete(provider, apiKey, messages).content
    }

    private fun systemPrompt(projectName: String) = """
        Ты — агент разработки внутри Android-приложения NeuroCode.
        Активный проект: $projectName.
        Работай только через предоставленные инструменты и только внутри корня проекта.
        Сначала изучи нужные файлы, затем делай минимальные обоснованные изменения.
        Не выдумывай результаты команд. После изменений проверь Git diff, если Git доступен.
        Android shell ограничен: в нём могут отсутствовать git, python, node и компиляторы.
        Никогда не запрашивай и не выводи API-ключи, токены или другие секреты.
        Отвечай пользователю по-русски, а имена API, классов и команд сохраняй как в коде.
    """.trimIndent()
}
