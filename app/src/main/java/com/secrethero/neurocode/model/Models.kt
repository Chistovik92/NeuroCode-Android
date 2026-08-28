package com.secrethero.neurocode.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Project(
    val id: String,
    val name: String,
    val rootPath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class FileNode(
    val name: String,
    val relativePath: String,
    val directory: Boolean,
    val size: Long = 0,
    val children: List<FileNode> = emptyList(),
)

@Serializable
data class SearchHit(
    val path: String,
    val line: Int,
    val preview: String,
)

@Serializable
data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKeyName: String = id,
    val enabled: Boolean = true,
    val extraHeaders: Map<String, String> = emptyMap(),
)

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class AppDesign {
    /** Classic GitHub-dark inspired look. */
    CLASSIC,

    /** Material 3 Expressive look with Gemini-style palette and gradient accents. */
    MODERN,
}

@Serializable
data class AgentSkill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val enabled: Boolean = true,
)

@Serializable
data class ExternalAgentTool(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val command: String,
    val enabled: Boolean = true,
)

@Serializable
data class AppSettings(
    val skills: List<AgentSkill> = emptyList(),
    val skillsEnabled: Boolean = true,
    val externalTools: List<ExternalAgentTool> = emptyList(),
    val providers: List<ProviderConfig> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedProjectId: String? = null,
    val localModelPath: String? = null,
    val localModelName: String? = null,
    val useLocalModel: Boolean = false,
    val agentMode: Boolean = true,
    val allowAgentShell: Boolean = false,
    val maxAgentSteps: Int = 8,
    val postChecksEnabled: Boolean = false,
    val postCheckCommands: List<String> = emptyList(),
    val fallbackProviderId: String? = null,
    val gitUsernames: Map<String, String> = emptyMap(),
    val removedDefaultProviderIds: List<String> = emptyList(),
    val recentFilesByProject: Map<String, List<String>> = emptyMap(),
    val linkedFolderByProject: Map<String, String> = emptyMap(),
    val lspEnabled: Boolean = false,
    val lspCommand: String = "",
    val themeMode: ThemeMode = ThemeMode.DARK,
    val appDesign: AppDesign = AppDesign.CLASSIC,
)

@Serializable
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/** Как вложение попадает в запрос к модели. */
@Serializable
enum class AttachmentKind {
    /** Картинка: уходит как image_url с data-URL (нужна vision-модель). */
    IMAGE,

    /** Текст или код: содержимое вставляется в сообщение. */
    TEXT,

    /** Прочие файлы: модель получает только имя, тип и путь внутри проекта. */
    BINARY,
}

/** Файл, приложенный к сообщению; лежит в `.neurocode/attachments` активного проекта. */
@Serializable
data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val relativePath: String,
    val kind: AttachmentKind,
)

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String? = null,
    val toolCallId: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    /** Размышления модели (reasoning), если провайдер их отдаёт. */
    val reasoning: String? = null,
)

@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новый диалог",
    val projectId: String? = null,
    val providerId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class AssistantTurn(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val reasoning: String = "",
)

/**
 * Лимиты и расход модели по последнему ответу провайдера: окно контекста из каталога
 * моделей, токены из `usage` и остатки из заголовков `x-ratelimit-*`, если они есть.
 */
data class ModelLimits(
    val model: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val requestsRemaining: String? = null,
    val requestsLimit: String? = null,
    val tokensRemaining: String? = null,
    val tokensLimit: String? = null,
    val resetHint: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val hasRateLimits: Boolean
        get() = requestsRemaining != null || tokensRemaining != null

    val hasUsage: Boolean
        get() = totalTokens != null || promptTokens != null
}

enum class ApprovalRisk {
    FILE_WRITE,
    SHELL,
    DESTRUCTIVE,
}

data class ToolApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val details: String,
    val risk: ApprovalRisk,
)

sealed interface ChatRunState {
    data object Idle : ChatRunState
    data class Working(val status: String) : ChatRunState
    data class Failed(val message: String) : ChatRunState
}
