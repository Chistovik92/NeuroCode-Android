package com.secrethero.neurocode.sync

import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.Project

/**
 * Мерж двух [SyncPayload] по правилам ROADMAP 0.9.5: last-writer-wins по `updatedAt`,
 * удаление — tombstone, чтобы удалённое не воскресало после обратной синхронизации.
 *
 * Локальная и серверная записи равнозначны: у обеих есть `updatedAt` и `deviceId`.
 * При равном времени побеждает больший `deviceId` (детерминированный выбор вместо
 * «чей синк позже»).
 */
object SyncMerge {
    /**
     * Результат слияния серверного payload с локальным состоянием:
     * что применить на устройстве (мерж выполняет [SyncRepository]).
     */
    data class Result(
        /** Диалоги, которые нужно заменить/создать локально. */
        val chatsToApply: List<ChatSession>,
        /** Проекты, которые нужно заменить/создать локально. */
        val projectsToApply: List<Project>,
        /** Диалоги, удалённые на другом устройстве: удалить локально. */
        val chatsToDelete: List<String>,
        /** Проекты, удалённые на другом устройстве: удалить локально. */
        val projectsToDelete: List<String>,
        /** Новый payload для отправки на сервер (локальные + принятые изменения). */
        val merged: SyncPayload,
    )

    fun merge(local: SyncPayload, remote: SyncPayload): Result {
        require(local.deviceId != remote.deviceId) {
            "Синхронизация с собственной записью не выполняется"
        }

        val deletedChats = (local.deletedChatIds + remote.deletedChatIds).distinct()
        val deletedProjects = (local.deletedProjectIds + remote.deletedProjectIds).distinct()

        val remoteChats = remote.chats.filterNot { it.id in deletedChats }
        val remoteProjects = remote.projects.filterNot { it.id in deletedProjects }

        val localChatsById = local.chats.associateBy { it.id }
        // Tombstone приоритетнее данных: удалённое на другом устройстве удаляется
        // безусловно — иначе удаление пришлось бы «спорить» с правками по времени.
        val chatsToDelete = deletedChats.filter { localChatsById.containsKey(it) }

        val chatsToApply = remoteChats.filter { remoteChat ->
            val localChat = localChatsById[remoteChat.id]
            when (localChat) {
                null -> true
                else -> isNewer(remote.updatedAt, remote.deviceId, localChat.updatedAt, local.deviceId)
            }
        }

        val localProjectsById = local.projects.associateBy { it.id }
        val projectsToDelete = deletedProjects.filter { localProjectsById.containsKey(it) }
        val projectsToApply = remoteProjects.filter { remoteProject ->
            val localProject = localProjectsById[remoteProject.id]
            when (localProject) {
                null -> true
                else -> isNewer(remote.updatedAt, remote.deviceId, localProject.updatedAt, local.deviceId)
            }
        }

        // Объединённый payload: локальное состояние + изменения с другого устройства,
        // без удалённого (tombstones обоих устройств). Третье устройство увидит итог.
        val appliedChatsById = chatsToApply.associateBy { it.id }
        val appliedProjectsById = projectsToApply.associateBy { it.id }
        val mergedChats = local.chats
            .filterNot { it.id in chatsToDelete }
            .map { appliedChatsById[it.id] ?: it } +
            chatsToApply.filterNot { applied -> localChatsById.containsKey(applied.id) }
        val mergedProjects = local.projects
            .filterNot { it.id in projectsToDelete }
            .map { appliedProjectsById[it.id] ?: it } +
            projectsToApply.filterNot { applied -> localProjectsById.containsKey(applied.id) }

        val merged = SyncPayload(
            deviceId = local.deviceId,
            deviceName = local.deviceName,
            // Мерж новее обоих исходов: иначе третья запись с «равным» временем
            // снова выиграет tie-break у этого устройства.
            updatedAt = maxOf(local.updatedAt, remote.updatedAt) + 1,
            chats = mergedChats,
            projects = mergedProjects,
            settings = if (isNewer(remote.updatedAt, remote.deviceId, local.updatedAt, local.deviceId)) {
                remote.settings
            } else {
                local.settings
            },
            deletedChatIds = deletedChats,
            deletedProjectIds = deletedProjects,
        )

        return Result(
            chatsToApply = chatsToApply,
            projectsToApply = projectsToApply,
            chatsToDelete = chatsToDelete,
            projectsToDelete = projectsToDelete,
            merged = merged,
        )
    }

    /** LWW: строго новее по времени, при равном времени — больший deviceId. */
    private fun isNewer(remoteAt: Long, remoteDevice: String, localAt: Long, localDevice: String): Boolean =
        remoteAt > localAt || (remoteAt == localAt && remoteDevice > localDevice)
}
