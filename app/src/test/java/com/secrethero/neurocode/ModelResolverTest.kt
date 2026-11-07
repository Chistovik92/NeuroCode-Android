package com.secrethero.neurocode

import com.secrethero.neurocode.ai.ModelProblem
import com.secrethero.neurocode.ai.ModelResolver
import com.secrethero.neurocode.ai.ModelSource
import com.secrethero.neurocode.model.AgentPreset
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.ModelKind
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.Project
import com.secrethero.neurocode.model.ProviderConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelResolverTest {
    private val openai = ProviderConfig(id = "p1", name = "OpenAI", baseUrl = "https://x/v1", model = "gpt-a")
    private val router = ProviderConfig(id = "p2", name = "Router", baseUrl = "https://y/v1", model = "r-1")
    private val settings = AppSettings(providers = listOf(openai, router), selectedProviderId = "p1")
    private val project = Project(id = "pr", name = "Demo", rootPath = "/tmp/demo")
    private val hasKey: (ProviderConfig) -> Boolean = { true }

    @Test
    fun usesGlobalModelWhenNothingIsOverridden() {
        val result = ModelResolver.resolve(null, project, settings, hasKey)

        assertEquals(ModelSource.GLOBAL, result.source)
        assertEquals(ModelRef.cloud("p1", "gpt-a"), result.ref)
        assertTrue(result.ready)
    }

    @Test
    fun projectModelBeatsGlobal() {
        val withModel = project.copy(model = ModelRef.cloud("p2", "r-9"))

        val result = ModelResolver.resolve(null, withModel, settings, hasKey)

        assertEquals(ModelSource.PROJECT, result.source)
        assertEquals("r-9", result.provider?.model)
        assertEquals("gpt-a", openai.model)
    }

    @Test
    fun dialogModelBeatsProjectAndGlobal() {
        val withModel = project.copy(model = ModelRef.cloud("p2", "r-9"))
        val session = ChatSession(model = ModelRef.cloud("p1", "gpt-b"))

        val result = ModelResolver.resolve(session, withModel, settings, hasKey)

        assertEquals(ModelSource.DIALOG, result.source)
        assertEquals("gpt-b", result.provider?.model)
    }

    @Test
    fun dialogPresetBeatsExplicitDialogProjectAndGlobalModels() {
        val preset = AgentPreset(id = "dialog", name = "Dialog", model = ModelRef.cloud("p2", "preset-dialog"))
        val configured = settings.copy(presets = listOf(preset))
        val session = ChatSession(
            presetId = preset.id,
            model = ModelRef.cloud("p1", "dialog-model"),
        )
        val configuredProject = project.copy(
            presetId = "missing-project",
            model = ModelRef.cloud("p1", "project-model"),
        )

        val result = ModelResolver.resolve(session, configuredProject, configured, hasKey)

        assertEquals(ModelSource.DIALOG, result.source)
        assertEquals("preset-dialog", result.provider?.model)
    }

    @Test
    fun projectPresetBeatsExplicitProjectModelAndGlobalPreset() {
        val projectPreset = AgentPreset(
            id = "project",
            name = "Project",
            model = ModelRef.cloud("p2", "preset-project"),
        )
        val globalPreset = AgentPreset(id = "global", name = "Global", model = ModelRef.cloud("p1", "preset-global"))
        val configured = settings.copy(presets = listOf(projectPreset, globalPreset), selectedPresetId = "global")
        val configuredProject = project.copy(presetId = "project", model = ModelRef.cloud("p1", "project-model"))

        val result = ModelResolver.resolve(null, configuredProject, configured, hasKey)

        assertEquals(ModelSource.PROJECT, result.source)
        assertEquals("preset-project", result.provider?.model)
    }

    @Test
    fun globalPresetBeatsLegacyModelFallback() {
        val preset = AgentPreset(id = "global", name = "Global", model = ModelRef.cloud("p2", "preset-global"))
        val configured = settings.copy(presets = listOf(preset), selectedPresetId = preset.id)
        val configuredProject = project.copy(model = ModelRef.cloud("p1", "ignored"))

        val result = ModelResolver.resolve(null, configuredProject, configured, hasKey)

        assertEquals(ModelSource.GLOBAL, result.source)
        assertEquals("preset-global", result.provider?.model)
    }

    @Test
    fun missingPresetReferenceDoesNotBlockLegacyModelFallback() {
        val configured = settings.copy(selectedPresetId = "missing")

        val session = ChatSession(model = ModelRef.cloud("p2", "legacy"))
        val result = ModelResolver.resolve(session, project, configured, hasKey)

        assertEquals(ModelSource.DIALOG, result.source)
        assertEquals("legacy", result.provider?.model)
    }

    @Test
    fun assignedPresetWithoutModelFallsBackToLegacyModelAtSameLevel() {
        val preset = AgentPreset(id = "global", name = "No model")
        val configured = settings.copy(presets = listOf(preset), selectedPresetId = preset.id)

        val configuredProject = project.copy(model = ModelRef.cloud("p2", "ignored"))
        val result = ModelResolver.resolve(null, configuredProject, configured, hasKey)

        assertEquals(ModelSource.GLOBAL, result.source)
        assertEquals("gpt-a", result.provider?.model)
    }

    @Test
    fun globalLocalModelIsUsedWhenEnabled() {
        val local = settings.copy(useLocalModel = true, localModelPath = "/m/a.gguf", localModelName = "a.gguf")

        val result = ModelResolver.resolve(null, null, local, hasKey, fileExists = { true })

        assertTrue(result.isLocal)
        assertEquals(ModelRef.local("/m/a.gguf", "a.gguf"), result.ref)
    }

    @Test
    fun projectCanUseLocalModelWhileGlobalIsCloud() {
        val withModel = project.copy(model = ModelRef.local("/m/b.gguf", "b.gguf"))

        val result = ModelResolver.resolve(null, withModel, settings, hasKey, fileExists = { true })

        assertTrue(result.isLocal)
        assertEquals(ModelSource.PROJECT, result.source)
    }

    @Test
    fun missingProviderIsReportedWithoutFallingBackToAnotherModel() {
        val withModel = project.copy(model = ModelRef.cloud("gone", "m"))

        val result = ModelResolver.resolve(null, withModel, settings, hasKey)

        assertFalse(result.ready)
        assertEquals(ModelProblem.NO_PROVIDER, result.problem)
        assertEquals(ModelSource.PROJECT, result.source)
    }

    @Test
    fun missingKeyDisabledProviderAndMissingFileAreReported() {
        val noKey = ModelResolver.resolve(null, null, settings, hasKey = { false })
        val disabled = ModelResolver.resolve(
            null,
            null,
            settings.copy(providers = listOf(openai.copy(enabled = false), router)),
            hasKey,
        )
        val missingFile = ModelResolver.resolve(
            ChatSession(model = ModelRef.local("/gone.gguf", "gone.gguf")),
            null,
            settings,
            hasKey,
            fileExists = { false },
        )

        assertEquals(ModelProblem.NO_KEY, noKey.problem)
        assertEquals(ModelProblem.PROVIDER_DISABLED, disabled.problem)
        assertEquals(ModelProblem.LOCAL_FILE_MISSING, missingFile.problem)
    }

    @Test
    fun nothingSelectedWhenThereAreNoProviders() {
        val result = ModelResolver.resolve(null, null, AppSettings(), hasKey)

        assertNull(result.ref)
        assertEquals(ModelProblem.NOTHING_SELECTED, result.problem)
    }

    @Test
    fun recentModelsKeepNewestFirstWithoutDuplicatesAndLimit() {
        val refs = (1..7).map { ModelRef.cloud("p1", "m$it") }
        var recent = emptyList<ModelRef>()
        refs.forEach { recent = ModelResolver.pushRecent(recent, it) }
        recent = ModelResolver.pushRecent(recent, refs[4])

        assertEquals(AppSettings.RECENT_MODELS_LIMIT, recent.size)
        assertEquals(refs[4], recent.first())
        assertEquals(recent.size, recent.map { it.key }.toSet().size)
    }

    @Test
    fun favoriteTogglesOnAndOff() {
        val ref = ModelRef.cloud("p1", "m")

        val added = ModelResolver.toggleFavorite(emptyList(), ref)
        val removed = ModelResolver.toggleFavorite(added, ref)

        assertEquals(listOf(ref), added)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun oldSavedDataWithoutModelFieldsStillLoads() {
        val json = Json { ignoreUnknownKeys = true }

        val project = json.decodeFromString(Project.serializer(), """{"id":"a","name":"n","rootPath":"/r"}""")
        val session = json.decodeFromString(ChatSession.serializer(), """{"id":"s","messages":[]}""")
        val app = json.decodeFromString(AppSettings.serializer(), "{}")

        assertNull(project.model)
        assertNull(session.model)
        assertNull(project.presetId)
        assertNull(session.presetId)
        assertNull(app.selectedPresetId)
        assertTrue(app.presets.isEmpty())
        assertTrue(app.recentModels.isEmpty())
    }

    @Test
    fun labelsDescribeCloudAndLocalModels() {
        val cloud = ModelRef.cloud("p1", "gpt-a")
        val local = ModelRef.local("/m/x.gguf", "x.gguf")

        assertEquals("gpt-a", ModelResolver.shortLabel(cloud))
        assertEquals("OpenAI · gpt-a", ModelResolver.fullLabel(cloud, settings.providers))
        assertEquals("GGUF · x.gguf", ModelResolver.fullLabel(local, settings.providers))
        assertEquals(ModelKind.LOCAL, local.kind)
    }
}
