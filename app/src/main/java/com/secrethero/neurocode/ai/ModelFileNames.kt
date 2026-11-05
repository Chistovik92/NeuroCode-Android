package com.secrethero.neurocode.ai

/**
 * Подбор имени файла при импорте GGUF (Б-7/2.4 плана 0.9.0): повторный импорт той же
 * модели не должен молча перезаписывать существующую. Кандидаты — `имя-2.gguf`,
 * `имя-3.gguf`…, пока не найдётся свободное.
 */
internal fun uniqueModelFileName(requested: String, exists: (String) -> Boolean): String {
    if (!exists(requested)) return requested
    val stem = requested.substringBeforeLast('.')
    val extension = requested.substringAfterLast('.', "")
    var index = 2
    while (true) {
        val candidate = if (extension.isBlank()) "$stem-$index" else "$stem-$index.$extension"
        if (!exists(candidate)) return candidate
        index++
    }
}
