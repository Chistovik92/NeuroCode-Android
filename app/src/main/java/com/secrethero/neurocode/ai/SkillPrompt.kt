package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.model.SkillScope

/**
 * Чистая логика сборки блока «Активные навыки» для системного промпта (0.9.2).
 * Вынесена из `AgentOrchestrator.systemPrompt` и `ChatViewModel.CloudRequest`, чтобы
 * покрыть unit-тестами без Android и сети.
 *
 * Правила:
 * - скиллы включаются только при включённом глобальном переключателе ([skillsEnabled]);
 * - учитываются только включённые скиллы;
 * - скилл с областью `PROJECT` действует только в своём проекте (иначе — в любом);
 * - итоговый текст — `имя: prompt`, по одному на строку.
 */
object SkillPrompt {

    /**
     * Скиллы, действующие в [projectId], в порядке исходного списка.
     */
    fun active(skills: List<AgentSkill>, skillsEnabled: Boolean, projectId: String?): List<AgentSkill> {
        if (!skillsEnabled) return emptyList()
        return skills.filter { skill ->
            skill.enabled && when (skill.scope) {
                SkillScope.GLOBAL -> true
                SkillScope.PROJECT -> skill.projectId == projectId && projectId != null
            }
        }
    }

    /** Строки блока скиллов для `AgentOrchestrator.systemPrompt` (по одной на скилл). */
    fun lines(skills: List<AgentSkill>, skillsEnabled: Boolean, projectId: String?): List<String> =
        active(skills, skillsEnabled, projectId).map { skill ->
            "${skill.name}: ${skill.prompt.trim()}"
        }

    /** Готовый блок `## Активные навыки` (пустая строка, если навыков нет). */
    fun block(skills: List<AgentSkill>, skillsEnabled: Boolean, projectId: String?): String {
        val lines = lines(skills, skillsEnabled, projectId)
        if (lines.isEmpty()) return ""
        return "\n\n## Активные навыки\n" +
            lines.joinToString("\n") { line -> "- $line" } +
            "\nПрименяй эти навыки при выполнении задачи."
    }
}
