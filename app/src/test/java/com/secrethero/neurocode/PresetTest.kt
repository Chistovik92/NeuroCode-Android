package com.secrethero.neurocode

import com.secrethero.neurocode.ai.AgentPresetResolver
import com.secrethero.neurocode.model.AgentPermissionMode
import com.secrethero.neurocode.model.AgentPreset
import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.ExternalAgentTool
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.Project
import com.secrethero.neurocode.model.SkillScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTest {
    private val model = ModelRef.cloud("p1", "preset-model")
    private val skills = listOf(
        AgentSkill(id = "selected", name = "Selected", prompt = "Use it"),
        AgentSkill(id = "other", name = "Other", prompt = "Do not use"),
        AgentSkill(id = "disabled", name = "Disabled", prompt = "No", enabled = false),
        AgentSkill(
            id = "wrong-project",
            name = "Wrong project",
            prompt = "No",
            scope = SkillScope.PROJECT,
            projectId = "another",
        ),
    )
    private val tools = listOf(
        ExternalAgentTool(id = "selected-tool", name = "Selected", description = "", command = "selected"),
        ExternalAgentTool(id = "other-tool", name = "Other", description = "", command = "other"),
        ExternalAgentTool(id = "disabled-tool", name = "Disabled", description = "", command = "no", enabled = false),
    )
    private val preset = AgentPreset(
        id = "preset",
        name = "Review",
        model = model,
        skillIds = setOf("selected", "disabled", "wrong-project", "missing"),
        externalToolIds = setOf("selected-tool", "disabled-tool", "missing"),
        agentMode = false,
        allowAgentShell = true,
        maxAgentSteps = 3,
        permissionMode = AgentPermissionMode.READ_ONLY,
        planMode = true,
    )
    private val settings = AppSettings(
        skills = skills,
        externalTools = tools,
        presets = listOf(preset),
        selectedPresetId = preset.id,
    )

    @Test
    fun resolvesCompletePresetConfigurationAndFiltersActiveEntities() {
        val result = AgentPresetResolver.resolve(null, null, settings)

        assertEquals(preset, result.preset)
        assertEquals(model, result.model)
        assertEquals(listOf("selected"), result.skills.map { it.id })
        assertEquals(listOf("selected-tool"), result.externalTools.map { it.id })
        assertFalse(result.agentMode)
        assertTrue(result.allowAgentShell)
        assertEquals(3, result.maxAgentSteps)
        assertEquals(AgentPermissionMode.READ_ONLY, result.permissionMode)
        assertTrue(result.planMode)
    }

    @Test
    fun presetPriorityIsDialogProjectGlobalAndUnknownIdsAreSkipped() {
        val projectPreset = preset.copy(id = "project", name = "Project")
        val dialogPreset = preset.copy(id = "dialog", name = "Dialog")
        val configured = settings.copy(presets = listOf(preset, projectPreset, dialogPreset))
        val project = Project(id = "p", name = "P", rootPath = "/p", presetId = "project")
        val dialog = ChatSession(presetId = "dialog")

        assertEquals(dialogPreset, AgentPresetResolver.preset(dialog, project, configured))
        assertEquals(projectPreset, AgentPresetResolver.preset(null, project, configured))
        assertEquals(preset, AgentPresetResolver.preset(null, null, configured))
        assertEquals(
            projectPreset,
            AgentPresetResolver.preset(ChatSession(presetId = "missing"), project, configured),
        )
        val missingDialog = ChatSession(presetId = "missing")
        assertNull(
            AgentPresetResolver.preset(missingDialog, null, settings.copy(selectedPresetId = null)),
        )
    }

    @Test
    fun withoutPresetUsesLegacySkillsToolsAndPolicy() {
        val legacy = settings.copy(
            presets = emptyList(),
            selectedPresetId = null,
            agentMode = true,
            allowAgentShell = false,
            maxAgentSteps = 11,
            agentPermissionMode = AgentPermissionMode.AUTO_EDIT,
            planMode = false,
        )

        val result = AgentPresetResolver.resolve(null, null, legacy)

        assertNull(result.preset)
        assertEquals(listOf("selected", "other"), result.skills.map { it.id })
        assertEquals(listOf("selected-tool", "other-tool"), result.externalTools.map { it.id })
        assertTrue(result.agentMode)
        assertFalse(result.allowAgentShell)
        assertEquals(11, result.maxAgentSteps)
        assertEquals(AgentPermissionMode.AUTO_EDIT, result.permissionMode)
    }

    @Test
    fun globallyDisabledSkillsRemainDisabledForPreset() {
        val result = AgentPresetResolver.resolve(null, null, settings.copy(skillsEnabled = false))

        assertTrue(result.skills.isEmpty())
        assertEquals(listOf("selected-tool"), result.externalTools.map { it.id })
    }

    @Test
    fun presetRoundTripsWithModelSkillsToolsAndPolicy() {
        val json = Json { ignoreUnknownKeys = true }
        val app = settings.copy(selectedPresetId = preset.id)

        val decoded = json.decodeFromString(
            AppSettings.serializer(),
            json.encodeToString(AppSettings.serializer(), app),
        )

        assertEquals(preset, decoded.presets.single())
        assertEquals(preset.id, decoded.selectedPresetId)
        assertEquals(setOf("selected", "disabled", "wrong-project", "missing"), decoded.presets.single().skillIds)
    }
}
