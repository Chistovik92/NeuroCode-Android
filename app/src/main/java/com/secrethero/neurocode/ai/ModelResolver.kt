package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.ModelKind
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.Project
import com.secrethero.neurocode.model.ProviderConfig
import java.io.File

/** Откуда взята действующая модель: цепочка «диалог → проект → глобально». */
enum class ModelSource {
    DIALOG,
    PROJECT,
    GLOBAL,
}

/** Почему выбранную модель нельзя использовать; молчаливой подмены чужой моделью нет. */
enum class ModelProblem {
    NOTHING_SELECTED,
    NO_PROVIDER,
    PROVIDER_DISABLED,
    NO_KEY,
    NO_MODEL_ID,
    LOCAL_NOT_IMPORTED,
    LOCAL_FILE_MISSING,
}

/**
 * Итог выбора модели для запроса. [provider] уже содержит идентификатор модели из [ref],
 * поэтому глобальная настройка провайдера не меняется.
 */
data class ResolvedModel(
    val ref: ModelRef?,
    val source: ModelSource,
    val provider: ProviderConfig? = null,
    val problem: ModelProblem? = null,
) {
    val ready: Boolean get() = ref != null && problem == null
    val isLocal: Boolean get() = ref?.kind == ModelKind.LOCAL
}

object ModelResolver {
    /** Глобальная модель по умолчанию из настроек (то, что было единственным выбором до 0.7.7). */
    fun globalRef(settings: AppSettings): ModelRef? {
        if (settings.useLocalModel) {
            return settings.localModelPath?.let { ModelRef.local(it, settings.localModelName ?: File(it).name) }
        }
        return settings.providers.firstOrNull { it.id == settings.selectedProviderId }
            ?.let { ModelRef.cloud(it.id, it.model) }
    }

    @Suppress("ReturnCount")
    fun resolve(
        session: ChatSession?,
        project: Project?,
        settings: AppSettings,
        hasKey: (ProviderConfig) -> Boolean,
        fileExists: (String) -> Boolean = { File(it).isFile },
    ): ResolvedModel {
        val (source, ref) = when {
            session?.model != null -> ModelSource.DIALOG to session.model
            project?.model != null -> ModelSource.PROJECT to project.model
            else -> ModelSource.GLOBAL to globalRef(settings)
        }
        if (ref == null) {
            val problem = if (settings.useLocalModel) ModelProblem.LOCAL_NOT_IMPORTED else ModelProblem.NOTHING_SELECTED
            return ResolvedModel(null, source, problem = problem)
        }
        return when (ref.kind) {
            ModelKind.LOCAL -> resolveLocal(ref, source, fileExists)
            ModelKind.CLOUD -> resolveCloud(ref, source, settings, hasKey)
        }
    }

    private fun resolveLocal(ref: ModelRef, source: ModelSource, fileExists: (String) -> Boolean): ResolvedModel {
        val path = ref.localPath
        val problem = when {
            path.isNullOrBlank() -> ModelProblem.LOCAL_NOT_IMPORTED
            !fileExists(path) -> ModelProblem.LOCAL_FILE_MISSING
            else -> null
        }
        return ResolvedModel(ref, source, problem = problem)
    }

    private fun resolveCloud(
        ref: ModelRef,
        source: ModelSource,
        settings: AppSettings,
        hasKey: (ProviderConfig) -> Boolean,
    ): ResolvedModel {
        val base = settings.providers.firstOrNull { it.id == ref.providerId }
            ?: return ResolvedModel(ref, source, problem = ModelProblem.NO_PROVIDER)
        val provider = base.copy(model = ref.model?.takeIf { it.isNotBlank() } ?: base.model)
        val problem = when {
            !base.enabled -> ModelProblem.PROVIDER_DISABLED
            provider.model.isBlank() -> ModelProblem.NO_MODEL_ID
            !hasKey(base) -> ModelProblem.NO_KEY
            else -> null
        }
        return ResolvedModel(ref, source, provider, problem)
    }

    /** Подпись для шапки и сообщений: у облачной — идентификатор модели, у локальной — имя файла. */
    fun shortLabel(ref: ModelRef?): String? = when (ref?.kind) {
        ModelKind.CLOUD -> ref.model
        ModelKind.LOCAL -> ref.localName
        null -> null
    }

    /** Полная подпись «провайдер · модель» для сообщения ассистента. */
    fun fullLabel(ref: ModelRef, providers: List<ProviderConfig>): String = when (ref.kind) {
        ModelKind.CLOUD -> {
            val name = providers.firstOrNull { it.id == ref.providerId }?.name
            listOfNotNull(name, ref.model).joinToString(" · ")
        }
        ModelKind.LOCAL -> "GGUF · ${ref.localName ?: ref.localPath.orEmpty()}"
    }

    /** Новый список «недавних»: выбранная модель первой, без повторов, не длиннее [limit]. */
    fun pushRecent(
        recent: List<ModelRef>,
        ref: ModelRef,
        limit: Int = AppSettings.RECENT_MODELS_LIMIT,
    ): List<ModelRef> = (listOf(ref) + recent.filterNot { it.key == ref.key }).take(limit)

    fun toggleFavorite(favorites: List<ModelRef>, ref: ModelRef): List<ModelRef> =
        if (favorites.any { it.key == ref.key }) favorites.filterNot { it.key == ref.key } else favorites + ref
}
