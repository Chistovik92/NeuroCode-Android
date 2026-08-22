package com.secrethero.neurocode.ai

object CommandPolicy {

    fun risk(command: String): String? {
        val normalized = command.lowercase()
        return when {
            Regex("(^|[;&|]\\s*)rm\\s+(-[^ ]*r[^ ]*f|-[^ ]*f[^ ]*r)").containsMatchIn(normalized) ->
                "рекурсивное удаление"
            Regex("(^|[;&|]\\s*)(dd|mkfs|reboot|shutdown|su)\\b").containsMatchIn(normalized) ->
                "системная или необратимая операция"
            normalized.contains("curl") && normalized.contains("|") && normalized.contains("sh") ->
                "запуск скачанного скрипта"
            normalized.contains("wget") && normalized.contains("|") && normalized.contains("sh") ->
                "запуск скачанного скрипта"
            Regex("(^|\\s|[\"'])/").containsMatchIn(normalized) ->
                "обращение за пределами рабочего проекта"
            Regex("(^|[\\s/\"'=])\\.\\.(/|\\\\|$)").containsMatchIn(normalized) ->
                "переход за пределы рабочего проекта"
            else -> null
        }
    }

    fun isSafeReadOnly(command: String): Boolean {
        if (command.any { it in ";|&><\n\r`$(){}" }) return false
        if (command.contains("..")) return false
        val executable = command.trim().substringBefore(' ')
        return executable in SAFE_READ_ONLY_EXECUTABLES
    }

    private val SAFE_READ_ONLY_EXECUTABLES =
        setOf("pwd", "ls", "find", "grep", "sed", "head", "tail", "wc", "cat")
}
