package com.secrethero.neurocode.terminal

import java.io.IOException

/** Этап подготовки Linux-окружения, на котором произошёл сбой или идёт работа. */
enum class ProotStage {
    PRECHECK,
    PROOT,
    ROOTFS,
    EXTRACT,
    VERIFY,
}

/** Причина, по которой окружение недоступно: этап и текст исключения / HTTP-код. */
data class ProotFailure(val stage: ProotStage, val detail: String)

/** Прогресс загрузки; [totalBytes] = 0, если сервер не сообщил размер. */
data class ProotProgress(val stage: ProotStage, val downloadedBytes: Long, val totalBytes: Long) {
    val fraction: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

internal class ProotException(val stage: ProotStage, message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal fun Throwable.toProotFailure(): ProotFailure {
    if (this is ProotException) return ProotFailure(stage, message.orEmpty())
    val text = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
    return ProotFailure(ProotStage.VERIFY, text)
}

/** Повторяет [block] при [IOException] до [attempts] раз с растущей паузой; последняя ошибка пробрасывается. */
internal fun <T> retrying(
    attempts: Int,
    delayMs: Long,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    block: (attempt: Int) -> T,
): T {
    require(attempts >= 1) { "attempts must be >= 1" }
    var lastError: IOException? = null
    for (attempt in 1..attempts) {
        try {
            return block(attempt)
        } catch (error: ProotException) {
            if (error.stage == ProotStage.PRECHECK) throw error
            lastError = error
        } catch (error: IOException) {
            lastError = error
        }
        if (attempt < attempts) sleep(delayMs * attempt)
    }
    throw checkNotNull(lastError)
}
