package com.secrethero.neurocode.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val settings = container.settings.settings

    private val _modelImportProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val modelImportProgress: StateFlow<Pair<Long, Long>?> = _modelImportProgress.asStateFlow()

    private val _providerModels =
        MutableStateFlow<ProviderModelsState>(ProviderModelsState(emptyList(), null, false))
    val providerModels: StateFlow<ProviderModelsState> = _providerModels.asStateFlow()

    fun loadProviderModels(provider: ProviderConfig) = viewModelScope.launch {
        _providerModels.value = _providerModels.value.copy(loading = true)
        runCatching {
            val key = container.settings.apiKey(provider).orEmpty()
            container.apiClient.models(provider, key)
        }.onSuccess { models ->
            _providerModels.value = ProviderModelsState(models, null, false)
        }.onFailure { error ->
            _providerModels.value = ProviderModelsState(
                emptyList(),
                error.message ?: error::class.java.simpleName,
                false,
            )
        }
    }

    fun selectProvider(providerId: String) = updateSettings {
        it.copy(selectedProviderId = providerId, useLocalModel = false)
    }

    fun setUseLocalModel(enabled: Boolean) = updateSettings { it.copy(useLocalModel = enabled) }
    fun setAgentMode(enabled: Boolean) = updateSettings { it.copy(agentMode = enabled) }
    fun setAllowAgentShell(enabled: Boolean) = updateSettings { it.copy(allowAgentShell = enabled) }
    fun setMaxAgentSteps(value: Int) = updateSettings { it.copy(maxAgentSteps = value.coerceIn(1, 20)) }
    fun setThemeMode(mode: ThemeMode) = updateSettings { it.copy(themeMode = mode) }

    fun setSkillsEnabled(enabled: Boolean) = updateSettings { it.copy(skillsEnabled = enabled) }

    fun saveSkill(skill: AgentSkill) = updateSettings { current ->
        current.copy(skills = current.skills.filterNot { it.id == skill.id } + skill)
    }

    fun deleteSkill(skillId: String) = updateSettings { current ->
        current.copy(skills = current.skills.filterNot { it.id == skillId })
    }

    fun toggleSkill(skillId: String, enabled: Boolean) = updateSettings { current ->
        current.copy(
            skills = current.skills.map {
                if (it.id == skillId) it.copy(enabled = enabled) else it
            },
        )
    }

    fun saveProvider(provider: ProviderConfig, apiKey: String?) = viewModelScope.launch {
        runCatching { container.settings.upsertProvider(provider, apiKey) }
            .onFailure(container.bus::showError)
    }

    fun removeProvider(providerId: String) = viewModelScope.launch {
        runCatching { container.settings.removeProvider(providerId) }
            .onFailure(container.bus::showError)
    }

    fun importLocalModel(uri: Uri) = viewModelScope.launch {
        runCatching {
            _modelImportProgress.value = 0L to -1L
            val imported = container.localLlama.importModel(uri) { copied, total ->
                _modelImportProgress.value = copied to total
            }
            container.settings.update {
                it.copy(
                    localModelPath = imported.path,
                    localModelName = imported.name,
                    useLocalModel = true,
                )
            }
        }.onFailure(container.bus::showError)
        _modelImportProgress.value = null
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) =
        viewModelScope.launch {
            runCatching { container.settings.update(transform) }
                .onFailure(container.bus::showError)
        }
}

data class ProviderModelsState(
    val models: List<String>,
    val error: String?,
    val loading: Boolean,
)
