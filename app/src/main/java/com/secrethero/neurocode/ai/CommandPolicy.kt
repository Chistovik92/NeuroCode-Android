package com.secrethero.neurocode.ai

object CommandPolicy {

    fun risk(command: String): String? {
        val normalized = command.lowercase()
        return when {
            Regex("(^|[;&|]\\s*)rm\\s+(.*\\s)?(-[^ ]*r|--recursive)").containsMatchIn(normalized) ->
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
        val tokens = command.trim().split(Regex("\\s+"))
        val executable = tokens.first()
        if (executable !in SAFE_READ_ONLY_EXECUTABLES) return false
        // find умеет менять файлы через -delete/-exec/-fprint. sed исключён целиком:
        // даже без -i его команды w/e создают файлы и запускают программы.
        return tokens.drop(1).none { it in FORBIDDEN_ARGUMENTS }
    }

    private val FORBIDDEN_ARGUMENTS = setOf(
        "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls",
    )

    private val SAFE_READ_ONLY_EXECUTABLES =
        setOf("pwd", "ls", "find", "grep", "head", "tail", "wc", "cat")
}
