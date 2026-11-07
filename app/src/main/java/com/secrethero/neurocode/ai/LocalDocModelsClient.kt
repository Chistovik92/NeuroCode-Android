package com.secrethero.neurocode.ai

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import com.secrethero.neurocode.model.ModelRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Клиент общего хранилища моделей LocalDoc AI (0.9.0).
 *
 * LocalDoc AI (KMP) публикует скачанные GGUF-модели через ContentProvider
 * `content://<applicationId>.models` (release и debug): список — `query()` с полями `_id`/`name`/`size`,
 * байты — `openInputStream(content://com.localdoc.ai.models/<id>)`. Начиная с 0.9.4.6 NeuroCode
 * использует модель напрямую через `openFileDescriptor` (реальный fd → `/proc/self/fd/<fd>`),
 * без копирования в `files/models`. Отсутствие или удаление LocalDoc AI делает
 * прямую ссылку недоступной, но не ломает экран.
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
        internal val authority: String = RELEASE_AUTHORITY,
    )

    private data class Installation(val packageName: String, val authority: String)

    private fun installations(): List<Installation> = PACKAGES.mapNotNull { packageName ->
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }
            .getOrNull()
            ?.let { Installation(packageName, "$packageName.models") }
    }

    /** Установлено ли приложение LocalDoc AI (release или debug-суффикс). */
    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        installations().isNotEmpty()
    }

    /**
     * Скачанные модели LocalDoc AI; пустой список, если приложение не установлено
     * или провайдер недоступен (старая версия).
     */
    suspend fun list(): List<LocalDocModel> = withContext(Dispatchers.IO) {
        installations().flatMap { installation ->
            runCatching {
                context.contentResolver.query(
                    Uri.parse("content://${installation.authority}"),
                    arrayOf(PROJECTION_ID, PROJECTION_NAME, PROJECTION_SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: id
                            add(
                                LocalDocModel(
                                    id = id,
                                    name = name,
                                    sizeBytes = cursor.getLong(2),
                                    authority = installation.authority,
                                ),
                            )
                        }
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Поток байтов модели для передачи в [LocalLlamaClient.importModel].
     * null, если модель недоступна (удалена в LocalDoc AI между list и open).
     */
    suspend fun open(model: LocalDocModel) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri(model))
        }.getOrNull()
    }

    /** content-URI модели: `content://<authority>/<id>`. */
    fun uri(model: LocalDocModel): Uri = uri(model.id, model.authority)

    /** content-URI модели по id и authority (release/debug). */
    fun uri(id: String, authority: String): Uri =
        Uri.withAppendedPath(Uri.parse("content://$authority"), id)

    /**
     * Файловый дескриптор модели от провайдера LocalDoc AI. В отличие от потока байтов,
     * реальный fd позволяет NeuroCode загрузить GGUF напрямую через `/proc/self/fd/<fd>`,
     * не копируя модель в `files/models`. null, если модель или провайдер недоступны.
     */
    suspend fun openDescriptor(model: LocalDocModel): ParcelFileDescriptor? =
        openDescriptor(model.id, model.authority)

    /** То же по [id] и [authority] — для прямой ссылки [ModelRef]. */
    suspend fun openDescriptor(id: String, authority: String): ParcelFileDescriptor? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri(id, authority), "r")
            }.getOrNull()
        }

    /** [ModelRef] прямой ссылки на модель LocalDoc AI. */
    fun ref(model: LocalDocModel): ModelRef =
        ModelRef.localDoc(id = model.id, name = model.name, authority = model.authority)

    /**
     * Установлен ли пакет с данным authority провайдера (release или debug).
     * Синхронная проверка package manager — для проверки доступности перед загрузкой.
     */
    fun isAuthorityInstalled(authority: String): Boolean =
        PACKAGES.any { packageName ->
            "$packageName.models" == authority &&
                runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
        }

    private companion object {
        const val RELEASE_AUTHORITY = "com.localdoc.ai.models"
        const val PROJECTION_ID = BaseColumns._ID
        const val PROJECTION_NAME = "name"
        const val PROJECTION_SIZE = "size"

        /** Release-пакет и debug-суффикс LocalDoc AI: debug-сборки для совместной отладки. */
        val PACKAGES = listOf("com.localdoc.ai", "com.localdoc.ai.debug")
    }
}
