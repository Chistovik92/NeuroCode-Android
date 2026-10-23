package com.secrethero.neurocode.data

import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Паспорт проекта в экспорте: имя и выбранная модель. Без секретов и локальных путей —
 * у локальной модели остаётся только имя файла GGUF, путь на другом устройстве другой.
 */
@Serializable
data class ProjectManifest(
    val version: Int = 1,
    val name: String,
    val model: ModelRef? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FILE_NAME = "neurocode-project.json"

        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun forExport(project: Project) = ProjectManifest(
            name = project.name,
            model = project.model?.copy(localPath = null),
        )

        /** Читает паспорт; битый или чужой файл даёт null, а не ошибку импорта. */
        fun decode(text: String): ProjectManifest? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
