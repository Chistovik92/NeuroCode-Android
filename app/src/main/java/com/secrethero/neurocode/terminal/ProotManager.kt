package com.secrethero.neurocode.terminal

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

enum class ProotState {
    IDLE,
    PREPARING,
    READY,
    UNAVAILABLE,
}

private const val COPY_BUFFER_SIZE = 64 * 1024

class ProotManager(private val context: Context) {
    private val mutex = Mutex()
    private val downloads = com.secrethero.neurocode.downloads.DownloadQueue(context)
    private val _state = MutableStateFlow(ProotState.IDLE)
    val state: StateFlow<ProotState> = _state.asStateFlow()
    private val _failure = MutableStateFlow<ProotFailure?>(null)

    /** Причина последнего сбоя; null, пока не было ошибки или после сброса. */
    val failure: StateFlow<ProotFailure?> = _failure.asStateFlow()
    private val fallbackMarker get() = File(linuxDir, ".android-shell-fallback")
    private val _progress = MutableStateFlow<ProotProgress?>(null)

    /** Прогресс текущей загрузки; null, когда ничего не скачивается. */
    val progress: StateFlow<ProotProgress?> = _progress.asStateFlow()

    /** Бинарник, прошедший проверку при установке (встроенный или скачанный). */
    @Volatile
    private var verifiedBinary: File? = null

    /**
     * Путь к внешнему загрузчику Termux-сборки proot; `null` для статической musl-сборки, которая
     * несёт загрузчик внутри себя.
     */
    @Volatile
    private var loaderPath: String? = null

    private val linuxDir get() = File(context.filesDir, "linux")
    private val rootfsDir get() = File(linuxDir, "rootfs")

    fun isReady(): Boolean = _state.value == ProotState.READY

    suspend fun initialize(): Boolean = mutex.withLock {
        if (fallbackMarker.exists()) {
            _state.value = ProotState.UNAVAILABLE
            _failure.value = ProotFailure(
                ProotStage.VERIFY,
                "Linux disabled after an Android compatibility failure; Android shell is used",
            )
            return false
        }
        when (_state.value) {
            ProotState.READY -> return true
            ProotState.PREPARING -> return false
            else -> Unit
        }
        _state.value = ProotState.PREPARING
        _failure.value = null
        val error = withContext(Dispatchers.IO) { runCatching { prepare() }.exceptionOrNull() }
        _progress.value = null
        if (error == null) {
            _state.value = ProotState.READY
        } else {
            val failure = error.toProotFailure()
            _failure.value = failure
            _state.value = ProotState.UNAVAILABLE
            if (failure.detail.contains("SIGSYS", ignoreCase = true) ||
                failure.detail.contains("Permission denied", ignoreCase = true) ||
                failure.detail.contains("noexec", ignoreCase = true)
            ) {
                linuxDir.mkdirs()
                fallbackMarker.writeText(failure.detail)
            }
            // Загруженный rootfs не виноват, если не запускается сам proot: оставляем его, чтобы не качать заново
            // при каждом запуске; «Сбросить» в настройках очищает всё.
            if (failure.stage != ProotStage.VERIFY) cleanup()
        }
        error == null
    }

    suspend fun reset() = mutex.withLock {
        if (_state.value == ProotState.PREPARING) return@withLock
        _state.value = ProotState.IDLE
        _failure.value = null
        fallbackMarker.delete()
        cleanup()
    }

    fun command(wrapped: List<String>, workspace: File): List<String> {
        if (!isReady()) return wrapped
        return guestCommand(wrapped, workspace)
    }

    /** Команда запуска внутри rootfs; не зависит от состояния, поэтому годится и для проверки при установке. */
    private fun guestCommand(wrapped: List<String>, workspace: File): List<String> =
        ProotCommand.build(
            binary = activeBinary().absolutePath,
            loader = loaderPath,
            rootfs = rootfsDir.absolutePath,
            workspace = workspace.absolutePath,
            tmpDir = File(linuxDir, "tmp").apply { mkdirs() }.absolutePath,
            guestPath = GUEST_PATH,
            wrapped = wrapped,
        )

    /** Тот proot, который прошёл проверку запуска при подготовке: всегда встроенный `libproot.so`. */
    private fun activeBinary(): File = verifiedBinary ?: resolveBundledBinary()
        ?: throw ProotException(ProotStage.PROOT, "bundled proot (libproot.so) is missing")

    private fun prepare() {
        checkFreeSpace()
        // Встроенный proot — всегда Termux-сборка (bionic); её загрузчик лежит рядом отдельным файлом.
        val proot = resolveBundledBinary()
            ?: throw ProotException(ProotStage.PROOT, "bundled proot (libproot.so) is missing for this ABI")
        verifiedBinary = proot
        loaderPath = resolveBundledLoader()?.absolutePath
            ?: throw ProotException(ProotStage.PROOT, "bundled proot loader (libproot_loader.so) is missing")
        if (!markerFile().exists()) {
            linuxDir.mkdirs()
            rootfsDir.mkdirs()
            downloadAndExtract(rootfsUrl(), rootfsDir)
        }
        repairRootfs()
        ensureResolvConf()
        verifyExecutable(proot)
        verifyGuestShell()
    }

    /**
     * Архив распаковывался без прав на исполнение, поэтому в уже установленных rootfs нет ни одного
     * исполняемого файла. Раз в ревизию права восстанавливаются без повторной загрузки.
     */
    private fun repairRootfs() {
        if (runCatching { markerFile().readText() }.getOrNull() == ROOTFS_REVISION) return
        ExecutableBits.repair(rootfsDir)
        markerFile().writeText(ROOTFS_REVISION)
    }

    /** Запускает настоящую команду в гостевой системе: «proot --version» не ловит ни права, ни параметры. */
    private fun verifyGuestShell() {
        val workspace = File(linuxDir, "verify").apply { mkdirs() }
        val process = try {
            ProcessBuilder(guestCommand(listOf("/bin/sh", "-c", "echo $GUEST_PROBE"), workspace))
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            throw ProotException(ProotStage.VERIFY, "guest shell cannot start: ${error.message}", error)
        }
        // Читаем параллельно ожиданию: если гость напишет больше буфера пайпа, он завис бы до тайм-аута.
        val outputFuture = java.util.concurrent.FutureTask { process.inputStream.bufferedReader().readText() }
        Thread(outputFuture, "proot-verify-reader").apply { isDaemon = true }.start()
        val finished = process.waitFor(EXEC_CHECK_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        val output = if (finished) runCatching { outputFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("") else ""
        process.destroyForcibly()
        if (!finished || process.exitValue() != 0 || GUEST_PROBE !in output) {
            val reason = if (finished) {
                output.trim().take(PROBE_ERROR_CHARS).ifBlank { describeExit(process.exitValue()) }
            } else {
                "timed out"
            }
            throw ProotException(ProotStage.VERIFY, "guest shell failed: $reason")
        }
    }

    private fun checkFreeSpace() {
        linuxDir.mkdirs()
        val free = linuxDir.usableSpace
        if (free < MIN_FREE_BYTES) {
            throw ProotException(
                ProotStage.PRECHECK,
                "free ${free / BYTES_IN_MB} MB, need ${MIN_FREE_BYTES / BYTES_IN_MB} MB",
            )
        }
    }

    /**
     * alpine-minirootfs не содержит /etc/resolv.conf, поэтому без него гостевые
     * процессы не резолвят домены (apk add, wget, git clone по https и т.д.).
     * Пишется при каждой подготовке, чтобы починиться и для уже установленных rootfs.
     */
    private fun ensureResolvConf() {
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        if (resolvConf.exists()) return
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    private fun markerFile() = File(linuxDir, ".rootfs-ok")

    private fun cleanup() {
        runCatching {
            markerFile().delete()
            rootfsDir.deleteRecursively()
        }
    }

    private fun resolveBundledBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val bundled = File(nativeDir, "libproot.so")
        // nativeLibraryDir уже отдаёт .so с битом исполнения (0755): лишний chmod
        // только порождает avc setattr denial и ничего не меняет.
        return bundled.takeIf { it.exists() }
    }

    /** Внешний загрузчик Termux-сборки proot из того же `nativeLibraryDir`, что и `libproot.so`. */
    private fun resolveBundledLoader(): File? = context.applicationInfo.nativeLibraryDir
        ?.let { dir -> File(dir, "libproot_loader.so").takeIf { it.exists() } }

    private fun verifyExecutable(proot: File) {
        val process = try {
            ProcessBuilder(proot.absolutePath, "--version").redirectErrorStream(true).start()
        } catch (error: IOException) {
            throw ProotException(ProotStage.VERIFY, "bundled proot cannot start: ${error.message}", error)
        }
        // Вывод вычитывается параллельно ожиданию: зависание пайпа не должно ломать проверку.
        val outputFuture = java.util.concurrent.FutureTask {
            process.inputStream.bufferedReader().readText()
        }
        Thread(outputFuture, "proot-version-reader").apply { isDaemon = true }.start()
        val finished = process.waitFor(EXEC_CHECK_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        val exit = if (finished) process.exitValue() else null
        runCatching { outputFuture.get(2, TimeUnit.SECONDS) }
        process.destroyForcibly()
        if (exit != 0) {
            val reason = if (exit == null) "proot --version timed out" else "proot ${describeExit(exit)}"
            throw ProotException(ProotStage.VERIFY, reason)
        }
    }

    private fun alpineArch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armeabi" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> throw ProotException(ProotStage.PRECHECK, "Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    private fun rootfsUrl(): String {
        val arch = alpineArch()
        return "$ALPINE_CD/v$ALPINE_VERSION/releases/$arch/" +
            "alpine-minirootfs-$ALPINE_MINIROOTFS-$arch.tar.gz"
    }

    private fun downloadAndExtract(url: String, destination: File) {
        destination.mkdirs()
        val archive = File(context.cacheDir, "rootfs-download.tar.gz")
        downloadTo(url, archive, ProotStage.ROOTFS)
        try {
            extractTarGz(archive.inputStream(), destination) { true }
        } catch (error: IOException) {
            throw ProotException(ProotStage.EXTRACT, "rootfs: ${error.message}", error)
        } finally {
            archive.delete()
        }
    }

    /** Скачивает [url] в [target] с повтором при обрыве; 404 и прочие HTTP-ошибки называют адрес. */
    private fun downloadTo(url: String, target: File, stage: ProotStage) {
        try {
            downloads.download(
                url,
                target,
                attempts = DOWNLOAD_ATTEMPTS,
                retryDelayMs = DOWNLOAD_RETRY_DELAY_MS,
            ) { copied, total ->
                _progress.value = ProotProgress(stage, copied, total)
            }
        } catch (error: ProotException) {
            throw error
        } catch (error: IOException) {
            throw ProotException(stage, "${error.message ?: error::class.java.simpleName} ($url)", error)
        }
    }

    companion object {
        /** Версия Alpine для rootfs и обязательный подкаталог на зеркале; при смене проверить доступность 404-ответом. */
        private const val ALPINE_CD = "https://dl-cdn.alpinelinux.org/alpine"
        private const val ALPINE_VERSION = "3.19"
        private const val ALPINE_MINIROOTFS = "3.19.0"
        /** Ревизия подготовленного rootfs: при смене права на файлы восстанавливаются один раз. */
        private const val ROOTFS_REVISION = "alpine-exec-1"
        private const val GUEST_PROBE = "neurocode-guest-ok"
        private const val PROBE_ERROR_CHARS = 300
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024
        private const val BYTES_IN_MB = 1024L * 1024
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 2_000L
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val EXEC_CHECK_TIMEOUT_MS = 10_000
    }
}

private class TarEntry(
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val linkTarget: String,
    val size: Long,
)

private class TarReader(private val input: InputStream) {
    private val header = ByteArray(BLOCK_SIZE)

    fun nextEntry(): TarEntry? {
        while (true) {
            val read = input.readFully(header)
            if (read < BLOCK_SIZE) return null
            if (header.all { it.toInt() == 0 }) continue
            val name = parseString(header, 0, NAME_LENGTH)
            if (name.isBlank()) continue
            val prefix = parseString(header, PREFIX_OFFSET, PREFIX_LENGTH)
            val fullName = if (prefix.isNotBlank()) "$prefix/$name" else name
            val size = parseOctal(header, SIZE_OFFSET, SIZE_LENGTH) ?: 0L
            lastSize = size
            val typeFlag = header[TYPE_FLAG_OFFSET]
            val linkName = parseString(header, LINKNAME_OFFSET, NAME_LENGTH)
            val normalized = fullName.removePrefix("./").trimEnd('/')
            if (normalized.isBlank() || normalized == "..") continue
            return TarEntry(
                name = normalized,
                isDirectory = typeFlag == TYPE_DIR || fullName.endsWith("/"),
                isSymlink = typeFlag == TYPE_SYMLINK,
                linkTarget = linkName,
                size = size,
            )
        }
    }

    fun readCurrentTo(output: OutputStream) {
        val size = currentSize()
        var remaining = size
        val chunk = ByteArray(COPY_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Unexpected end of tar stream")
            output.write(chunk, 0, read)
            remaining -= read
        }
        skipPadding(size)
    }

    fun skipCurrent() {
        val size = currentSize()
        var remaining = paddedSize(size)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun currentSize() = lastSize

    private var lastSize = 0L

    private fun paddedSize(size: Long): Long = (size + BLOCK_SIZE - 1) / BLOCK_SIZE * BLOCK_SIZE

    private fun skipPadding(size: Long) {
        val padding = paddedSize(size) - size
        var remaining = padding
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    companion object {
        private const val BLOCK_SIZE = 512
        private const val NAME_LENGTH = 100
        private const val SIZE_LENGTH = 12
        private const val PREFIX_LENGTH = 155
        private const val PREFIX_OFFSET = 345
        private const val LINKNAME_OFFSET = 157
        private const val SIZE_OFFSET = 124
        private const val TYPE_FLAG_OFFSET = 156
        private const val TYPE_DIR = '5'.code.toByte()
        private const val TYPE_SYMLINK = '2'.code.toByte()

        private fun parseString(buffer: ByteArray, offset: Int, length: Int): String {
            var end = offset
            val limit = offset + length
            while (end < limit && buffer[end] != 0.toByte()) end++
            return String(buffer, offset, end - offset, Charsets.UTF_8).trim()
        }

        private fun parseOctal(buffer: ByteArray, offset: Int, length: Int): Long? {
            var value = 0L
            var started = false
            for (i in offset until offset + length) {
                val b = buffer[i].toInt() and 0xFF
                if (b == 0 || b == ' '.code) {
                    if (started) break else continue
                }
                if (b < '0'.code || b > '7'.code) return null
                started = true
                value = value shl 3 or (b - '0'.code).toLong()
            }
            return value
        }

        private fun InputStream.readFully(buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val read = read(buffer, total, buffer.size - total)
                if (read < 0) return if (total == 0) -1 else total
                total += read
            }
            return total
        }
    }
}

private fun extractTarGz(stream: InputStream, destination: File, include: (String) -> Boolean) {
    val destinationPath = destination.toPath()
    GZIPInputStream(stream, COPY_BUFFER_SIZE).use { gz ->
        val reader = TarReader(gz)
        while (true) {
            val entry = reader.nextEntry() ?: break
            val safePath = Paths.get(destination.absolutePath, *entry.name.split('/').toTypedArray())
                .normalize()
            // Посегментная проверка: строковый префикс пропускал «rootfs2» рядом с «rootfs».
            if (!safePath.startsWith(destinationPath) || !include(entry.name)) {
                reader.skipCurrent()
                continue
            }
            when {
                entry.isDirectory -> Files.createDirectories(safePath)
                entry.isSymlink -> extractSymlink(reader, entry, safePath)
                else -> extractRegularFile(reader, safePath)
            }
        }
    }
}

private fun extractSymlink(reader: TarReader, entry: TarEntry, safePath: java.nio.file.Path) {
    // Цель симлинка не проверялась: «../x» или абсолютный путь выводили бы последующие
    // обычные записи за пределы rootfs (tar-slip). Alpine такие цели не использует —
    // небезопасные просто пропускаем.
    if (!isSafeSymlinkTarget(entry.linkTarget)) {
        reader.readCurrentTo(OutputStream.nullOutputStream())
        return
    }
    safePath.parent?.let { Files.createDirectories(it) }
    runCatching { Files.deleteIfExists(safePath) }
    runCatching { Files.createSymbolicLink(safePath, Paths.get(entry.linkTarget)) }
        .onFailure { reader.readCurrentTo(OutputStream.nullOutputStream()) }
}

private fun extractRegularFile(reader: TarReader, safePath: java.nio.file.Path) {
    // NOFOLLOW: запись не пойдёт по ранее созданному симлинку наружу.
    safePath.parent?.let { Files.createDirectories(it) }
    runCatching { Files.deleteIfExists(safePath) }
    Files.newOutputStream(safePath).use { reader.readCurrentTo(it) }
}

/**
 * Цель симлинка внутри rootfs: относительный путь без `..`-сегментов. Абсолютные цели
 * (например, `/data/...`) и выходы за корень отклоняются — это tar-slip.
 */
internal fun isSafeSymlinkTarget(target: String): Boolean =
    target.isNotBlank() && !target.startsWith("/") && target.split('/').none { it == ".." }
