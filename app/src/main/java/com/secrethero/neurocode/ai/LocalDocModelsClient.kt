package com.secrethero.neurocode.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Клиент общего хранилища моделей LocalDoc AI (0.9.0).
 *
 * LocalDoc AI (KMP) публикует скачанные GGUF-модели через ContentProvider
 * `content://com.localdoc.ai.models`: список — `query()` с полями `_ID`/`name`/`size`,
 * байты — `openInputStream(content://com.localdoc.ai.models/<id>)`. NeuroCode копирует
 * модель в свой `files/models` обычным `LocalLlamaClient.importModel` — без повторной
 * загрузки из сети. Отсутствие или удаление LocalDoc AI не влияет на импортированные
 * модели: они уже лежат у NeuroCode.
 *
 * Все операции — на `Dispatchers.IO`: это binder-вызовы в другой процесс.
 * Любой сбой (провайдер недоступен, старая версия без провайдера) означает
 * «моделей нет» и не должен ломать экран.
 */
class LocalDocModelsClient(private val context: Context) {

    data class LocalDocModel(
        val id: String,
        val name: String,
        val sizeBytes: Long,
    )

    val authorityUri: Uri = Uri.parse("content://$AUTHORITY")

    /** Установлено ли приложение LocalDoc AI (release или debug-суффикс). */
    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        PACKAGES.any { packageName ->
            runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
        }
    }

    /**
     * Скачанные модели LocalDoc AI; пустой список, если приложение не установлено
     * или провайдер недоступен (старая версия).
     */
    suspend fun list(): List<LocalDocModel> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                authorityUri,
                arrayOf(PROJECTION_ID, PROJECTION_NAME, PROJECTION_SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: id
                        add(LocalDocModel(id = id, name = name, sizeBytes = cursor.getLong(2)))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Поток байтов модели для передачи в [LocalLlamaClient.importModel].
     * null, если модель недоступна (удалена в LocalDoc AI между list и open).
     */
    suspend fun open(id: String) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(Uri.withAppendedPath(authorityUri, id))
        }.getOrNull()
    }

    private companion object {
        const val AUTHORITY = "com.localdoc.ai.models"
        const val PROJECTION_ID = "_ID"
        const val PROJECTION_NAME = "name"
        const val PROJECTION_SIZE = "size"

        /** Release-пакет и debug-суффикс LocalDoc AI: debug-сборки для совместной отладки. */
        val PACKAGES = listOf("com.localdoc.ai", "com.localdoc.ai.debug")
    }
}
