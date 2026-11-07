package com.secrethero.neurocode.data

import com.secrethero.neurocode.model.AppSettings

/** Один типизированный пункт каталога настроек, доступный поиску по названию и ключу. */
data class SettingEntry(
    val key: String,
    val title: String,
    val section: String,
    val value: String,
    val keywords: List<String> = emptyList(),
)

object SettingsCatalog {
    const val CURRENT_SCHEMA_VERSION = 1

    fun entries(settings: AppSettings): List<SettingEntry> = listOf(
        entry("appearance.theme", "Тема", "Внешний вид", settings.themeMode.name, "theme цвет светлая тёмная"),
        entry("appearance.design", "Дизайн интерфейса", "Внешний вид", settings.appDesign.name),
        entry("appearance.dynamic_color", "Material You", "Внешний вид", settings.dynamicColor),
        entry("model.local", "Локальная GGUF", "Модели", settings.useLocalModel),
        entry("model.wifi_only", "Загрузки только по Wi-Fi", "Модели", settings.wifiOnlyDownloads),
        entry("agent.enabled", "Режим агента", "Агент", settings.agentMode),
        entry("agent.plan_mode", "Режим плана", "Агент", settings.planMode),
        entry(
            "agent.permissions",
            "Разрешения инструментов",
            "Агент",
            settings.agentPermissionMode.name,
            "guard policy политика",
        ),
        entry("agent.shell", "Команды без повторного запроса", "Агент", settings.allowAgentShell),
        entry("agent.max_steps", "Максимум шагов агента", "Агент", settings.maxAgentSteps),
        entry("agent.post_checks", "Проверки после правок", "Агент", settings.postChecksEnabled),
        entry("agent.skills", "Скиллы", "Агент", settings.skillsEnabled),
        entry("runtime.lsp", "LSP", "Среда выполнения", settings.lspEnabled),
    )

    fun search(settings: AppSettings, query: String): List<SettingEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return entries(settings)
        return entries(settings).filter { candidate ->
            (listOf(candidate.key, candidate.title, candidate.section, candidate.value) + candidate.keywords)
                .any { it.lowercase().contains(needle) }
        }
    }

    private fun entry(
        key: String,
        title: String,
        section: String,
        value: Any,
        keywords: String = "",
    ) = SettingEntry(key, title, section, value.toString(), keywords.split(' ').filter { it.isNotBlank() })
}
