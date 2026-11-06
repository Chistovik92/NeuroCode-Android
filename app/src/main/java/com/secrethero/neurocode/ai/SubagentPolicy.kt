package com.secrethero.neurocode.ai

import kotlinx.serialization.Serializable

@Serializable
data class SubagentLimits(
    val maxDepth: Int = 3,
    val maxParallel: Int = 4,
    val timeoutMillis: Long = 120_000,
    val maxTaskChars: Int = 16 * 1024,
    val maxDescriptionChars: Int = 2 * 1024,
    val maxResultChars: Int = 64 * 1024,
)

@Serializable
data class SanitizedSubagentTask(
    val task: String,
    val description: String,
)

class SubagentPolicy(
    val limits: SubagentLimits = SubagentLimits(),
) {
    init {
        validate()
    }

    fun validate(): List<String> = buildList {
        if (limits.maxDepth < 0) add("maxDepth must not be negative")
        if (limits.maxParallel <= 0) add("maxParallel must be positive")
        if (limits.timeoutMillis <= 0) add("timeoutMillis must be positive")
        if (limits.maxTaskChars <= 0) add("maxTaskChars must be positive")
        if (limits.maxDescriptionChars < 0) add("maxDescriptionChars must not be negative")
        if (limits.maxResultChars < 0) add("maxResultChars must not be negative")
    }.also { errors ->
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    fun canSpawn(depth: Int, activeParallel: Int): Boolean =
        depth in 0 until limits.maxDepth && activeParallel in 0 until limits.maxParallel

    fun requireCanSpawn(depth: Int, activeParallel: Int) {
        require(depth >= 0) { "Subagent depth must not be negative" }
        require(activeParallel >= 0) { "Active subagent count must not be negative" }
        require(depth < limits.maxDepth) { "Maximum subagent depth reached: ${limits.maxDepth}" }
        require(activeParallel < limits.maxParallel) {
            "Maximum parallel subagents reached: ${limits.maxParallel}"
        }
    }

    fun sanitize(task: String, description: String = ""): SanitizedSubagentTask = SanitizedSubagentTask(
        task = clean(task, limits.maxTaskChars).also {
            require(it.isNotBlank()) { "Subagent task must not be blank" }
        },
        description = clean(description, limits.maxDescriptionChars),
    )

    fun sanitizeResult(result: String): String = clean(result, limits.maxResultChars)

    private fun clean(value: String, maxChars: Int): String = buildString(value.length) {
        value.forEach { character ->
            when {
                character == '\r' -> if (lastOrNull() != '\n') append('\n')
                character == '\n' || character == '\t' -> append(character)
                !character.isISOControl() -> append(character)
            }
        }
    }.trim().take(maxChars).trimEnd()
}
