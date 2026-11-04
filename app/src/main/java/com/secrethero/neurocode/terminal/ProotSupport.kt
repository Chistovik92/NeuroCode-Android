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

/** Сборка командной строки запуска программы внутри rootfs под proot. */
internal object ProotCommand {
    private const val HOST_SHELL = "/system/bin/sh"
    private const val GUEST_ENV = "/usr/bin/env"

    /**
     * Переменные proot ставит оболочка хоста, а не `env`: путь встроенного `libproot.so` содержит `==`
     * (`~~…==/com.…/lib/…`), и `env` принимает такой путь за присваивание переменной. Значения передаются
     * позиционными аргументами (`$1` — каталог для загрузчика), а не вставляются в текст скрипта.
     */
    private const val LAUNCHER_SCRIPT =
        "export PROOT_TMP_DIR=\"$1\" PROOT_NO_SECCOMP=1; shift; exec \"$@\""

    /**
     * У proot нет параметра `--env`: переменные гостя задаются запуском `env -i` внутри rootfs, а
     * собственные переменные proot (`PROOT_TMP_DIR` — без неё он не может создать загрузчик,
     * `PROOT_NO_SECCOMP`) — запуском через оболочку хоста, чтобы их получили все, кто строит команду
     * (терминал, команды агента, LSP).
     */
    @Suppress("LongParameterList")
    fun build(
        binary: String,
        rootfs: String,
        workspace: String,
        tmpDir: String,
        guestPath: String,
        wrapped: List<String>,
    ): List<String> = listOf(
        HOST_SHELL, "-c", LAUNCHER_SCRIPT, "proot-launcher", tmpDir,
        binary,
        "--kill-on-exit",
        "-r", rootfs,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-b", "$workspace:/workspace",
        "-w", "/workspace",
        "-0",
        GUEST_ENV, "-i",
        "HOME=/workspace", "PATH=$guestPath", "TMPDIR=/tmp", "TERM=xterm-256color",
    ) + wrapped
}

/**
 * Распаковка архива не переносила права: ни один файл rootfs не был исполняемым, и proot отвечал
 * «'/bin/sh' is not executable». Исполняемыми делаются ELF-файлы и скрипты с `#!`.
 */
internal object ExecutableBits {
    private const val HEAD = 4
    private val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    fun needsExecutable(head: ByteArray): Boolean =
        (head.size >= HEAD && head.copyOf(HEAD).contentEquals(elf)) ||
            (head.size >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte())

    /** Обходит [root] (символические ссылки не разыменовываются) и возвращает число исправленных файлов. */
    fun repair(root: java.io.File): Int {
        var fixed = 0
        root.walkTopDown()
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .forEach { file ->
                val head = runCatching { file.inputStream().use { it.readNBytes(HEAD) } }.getOrNull()
                if (head != null && needsExecutable(head)) {
                    file.setExecutable(true, false)
                    fixed++
                }
            }
        return fixed
    }
}

/** Понятное описание кода выхода процесса: сигналы (код > 128) называются, а не показываются числом. */
internal fun describeExit(code: Int): String {
    val signal = code - SIGNAL_OFFSET
    val name = when (signal) {
        SIGILL -> "SIGILL, illegal instruction"
        SIGABRT -> "SIGABRT, aborted"
        SIGKILL -> "SIGKILL, killed"
        SIGSEGV -> "SIGSEGV, crash"
        SIGSYS -> "SIGSYS, system call blocked by Android"
        else -> null
    }
    return if (code > SIGNAL_OFFSET && name != null) "exit $code (signal $signal: $name)" else "exit $code"
}

private const val SIGNAL_OFFSET = 128
private const val SIGILL = 4
private const val SIGABRT = 6
private const val SIGKILL = 9
private const val SIGSEGV = 11
private const val SIGSYS = 31

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
