package com.secrethero.neurocode

import com.secrethero.neurocode.ai.SkillLibrary
import com.secrethero.neurocode.ai.SkillMarkdown
import com.secrethero.neurocode.ai.SkillPrompt
import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.model.SkillScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPromptTest {

    private val review = AgentSkill(
        id = "r",
        name = "Ревью",
        prompt = "Ищи баги",
        enabled = true,
    )
    private val tests = AgentSkill(
        id = "t",
        name = "Тесты",
        prompt = "Пиши тесты",
        enabled = true,
    )
    private val disabled = AgentSkill(
        id = "d",
        name = "Выключен",
        prompt = "Не должен попасть",
        enabled = false,
    )
    private val projectOnly = AgentSkill(
        id = "p",
        name = "Проект",
        prompt = "Только проект",
        enabled = true,
        scope = SkillScope.PROJECT,
        projectId = "proj-1",
    )

    @Test
    fun globalToggleDisablesAllSkills() {
        assertTrue(SkillPrompt.active(listOf(review, tests), skillsEnabled = false, projectId = null).isEmpty())
        assertEquals(
            listOf(review, tests),
            SkillPrompt.active(listOf(review, tests), skillsEnabled = true, projectId = null),
        )
    }

    @Test
    fun disabledSkillsAreExcluded() {
        assertEquals(
            listOf(review),
            SkillPrompt.active(listOf(review, disabled), skillsEnabled = true, projectId = null),
        )
    }

    @Test
    fun projectScopeOnlyAppliesToItsProject() {
        assertTrue(SkillPrompt.active(listOf(projectOnly), true, projectId = "proj-2").isEmpty())
        assertEquals(
            listOf(projectOnly),
            SkillPrompt.active(listOf(projectOnly), true, projectId = "proj-1"),
        )
        // Проектный скилл не действует, если проекта нет вовсе.
        assertTrue(SkillPrompt.active(listOf(projectOnly), true, projectId = null).isEmpty())
    }

    @Test
    fun linesFormatIsNameColonPrompt() {
        assertEquals(
            listOf("Ревью: Ищи баги"),
            SkillPrompt.lines(listOf(review), true, null),
        )
    }

    @Test
    fun blockIsEmptyWhenNoSkills() {
        assertEquals("", SkillPrompt.block(emptyList(), true, null))
        assertEquals("", SkillPrompt.block(listOf(review), false, null))
    }

    @Test
    fun blockContainsHeaderAndSkills() {
        val block = SkillPrompt.block(listOf(review, tests), true, null)
        assertTrue(block.contains("## Активные навыки"))
        assertTrue(block.contains("- Ревью: Ищи баги"))
        assertTrue(block.contains("- Тесты: Пиши тесты"))
    }

    @Test
    fun libraryHasExpectedSkills() {
        val ids = SkillLibrary.entries.map { it.id }.toSet()
        assertTrue(ids.contains("builtin-code-review"))
        assertTrue(ids.contains("builtin-write-tests"))
        assertTrue(ids.contains("builtin-commits"))
        assertTrue(ids.contains("builtin-refactor"))
        assertTrue(SkillLibrary.entries.all { it.name.isNotBlank() && it.prompt.isNotBlank() })
    }

    @Test
    fun markdownParsesHeaderAndBody() {
        val skill = SkillMarkdown.parse(
            "# Мой скилл\n> description: делает X\n> tags: код, тесты\nДелай X и Y.\nНе делай Z.\n",
        )
        assertNotNull(skill)
        assertEquals("Мой скилл", skill!!.name)
        assertEquals("делает X", skill.description)
        assertEquals(listOf("код", "тесты"), skill.tags)
        assertEquals("Делай X и Y.\nНе делай Z.", skill.prompt)
    }

    @Test
    fun markdownRejectsEmptyOrHeaderless() {
        assertNull(SkillMarkdown.parse(""))
        assertNull(SkillMarkdown.parse("Текст без заголовка"))
        assertNull(SkillMarkdown.parse("# Заголовок без тела\n\n"))
    }

    @Test
    fun skillSerializationRoundTripsNewFields() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val original = AgentSkill(
            id = "id1",
            name = "N",
            prompt = "P",
            enabled = true,
            description = "desc",
            tags = listOf("a", "b"),
            scope = SkillScope.PROJECT,
            projectId = "prj",
        )
        val decoded = json.decodeFromString(AgentSkill.serializer(), json.encodeToString(original))
        assertEquals(original, decoded)
    }
}
