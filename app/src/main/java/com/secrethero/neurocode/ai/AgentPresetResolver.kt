package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.AgentPermissionMode
import com.secrethero.neurocode.model.AgentPreset
import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.ExternalAgentTool
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.Project

/** Полная конфигурация агента после применения пресета либо старых настроек. */
data class ResolvedAgentConfig(
    val preset: AgentPreset? = null,
    val model: ModelRef?,
    val skills: List<AgentSkill>,
    val externalTools: List<ExternalAgentTool>,
    val agentMode: Boolean,
    val allowAgentShell: Boolean,
    val maxAgentSteps: Int,
    val permissionMode: AgentPermissionMode,
    val planMode: Boolean,
)

object AgentPresetResolver {
    /** Цепочка: диалог → проект → глобально; неизвестный id пропускается как отсутствующий. */
    fun preset(session: ChatSession?, project: Project?, settings: AppSettings): AgentPreset? {
        val ids = listOfNotNull(session?.presetId, project?.presetId, settings.selectedPresetId)
        return ids.firstNotNullOfOrNull { id -> settings.presets.firstOrNull { it.id == id } }
    }

    /**
     * Возвращает активные сущности, а не только id: их можно передать напрямую в orchestrator.
     * Назначенный пресет полностью задаёт политику и наборы; без него сохраняется старое поведение.
     */
    fun resolve(session: ChatSession?, project: Project?, settings: AppSettings): ResolvedAgentConfig {
        val preset = preset(session, project, settings)
        val skills = SkillPrompt.active(settings.skills, settings.skillsEnabled, project?.id)
        val tools = settings.externalTools.filter { it.enabled }
        val model = ModelResolver.resolve(session, project, settings, hasKey = { true }).ref
        if (preset == null) {
            return ResolvedAgentConfig(
                model = model,
                skills = skills,
                externalTools = tools,
                agentMode = settings.agentMode,
                allowAgentShell = settings.allowAgentShell,
                maxAgentSteps = settings.maxAgentSteps,
                permissionMode = settings.agentPermissionMode,
                planMode = settings.planMode,
            )
        }
        return ResolvedAgentConfig(
            preset = preset,
            model = model,
            skills = skills.filter { it.id in preset.skillIds },
            externalTools = tools.filter { it.id in preset.externalToolIds },
            agentMode = preset.agentMode,
            allowAgentShell = preset.allowAgentShell,
            maxAgentSteps = preset.maxAgentSteps,
            permissionMode = preset.permissionMode,
            planMode = preset.planMode,
        )
    }
}
