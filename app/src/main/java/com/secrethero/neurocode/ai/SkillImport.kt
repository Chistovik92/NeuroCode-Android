package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.model.SkillScope

/**
 * Импорт скиллов в формате `SKILL.md` (0.9.2) — помимо JSON-бэкапа.
 *
 * Формат: первый заголовок `# Имя` (или `## Имя`) — название, остальной текст — инструкция.
 * Поддерживаются необязательные поля из front-matter-подобных строк `> description: …` и
 * `> tags: a, b`, остальные строки считаются телом промпта.
 */
object SkillMarkdown {

    fun parse(text: String): AgentSkill? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lines = trimmed.lineSequence().map { it.trimEnd() }.toList()

        var name: String? = null
        val descriptionParts = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val body = mutableListOf<String>()

        for (line in lines) {
            when {
                name == null && (line.startsWith("# ") || line.startsWith("## ")) ->
                    name = line.substringAfter(' ').trim()
                line.startsWith("> description:") ->
                    descriptionParts += line.substringAfter("> description:").trim()
                line.startsWith("> tags:") ->
                    tags += line.substringAfter("> tags:").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> body += line
            }
        }
        val skillName = name?.takeIf { it.isNotBlank() } ?: return null
        val prompt = body.joinToString("\n").trim()
        if (prompt.isBlank()) return null
        return AgentSkill(
            name = skillName,
            prompt = prompt,
            description = descriptionParts.joinToString(" ").trim(),
            tags = tags.distinct(),
            scope = SkillScope.GLOBAL,
        )
    }
}
