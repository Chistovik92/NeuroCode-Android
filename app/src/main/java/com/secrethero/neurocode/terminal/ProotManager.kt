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
import java.net.HttpURLConnection
import java.net.URL
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
    private val _state = MutableStateFlow(ProotState.IDLE)
    val state: StateFlow<ProotState> = _state.asStateFlow()

    private val linuxDir get() = File(context.filesDir, "linux")
    private val prootBinary get() = File(linuxDir, "proot")
    private val rootfsDir get() = File(linuxDir, "rootfs")

    fun isReady(): Boolean = _state.value == ProotState.READY

    suspend fun initialize(): Boolean = mutex.withLock {
        when (_state.value) {
            ProotState.READY -> return true
            ProotState.PREPARING -> return false
            else -> Unit
        }
        _state.value = ProotState.PREPARING
        val ok = withContext(Dispatchers.IO) { runCatching { prepare() }.getOrDefault(false) }
        _state.value = if (ok) ProotState.READY else ProotState.UNAVAILABLE
        if (!ok) cleanup()
        ok
    }

    fun reset() {
        if (_state.value == ProotState.PREPARING) return
        _state.value = ProotState.IDLE
        cleanup()
    }

    fun command(wrapped: List<String>, workspace: File): List<String> {
        if (!isReady()) return wrapped
        return listOf(
            prootBinary.absolutePath,
            "--kill-on-exit",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${workspace.absolutePath}:/workspace",
            "-w", "/workspace",
            "-0",
            "--env=HOME=/workspace",
            "--env=PATH=$GUEST_PATH",
            "--env=TMPDIR=/tmp",
            "--env=TERM=xterm-256color",
        ) + wrapped
    }

    private fun prepare(): Boolean {
        val proot = resolveBundledBinary() ?: installProotStatic()
        if (!markerFile().exists()) {
            linuxDir.mkdirs()
            rootfsDir.mkdirs()
            downloadAndExtract(rootfsUrl(), rootfsDir)
            markerFile().writeText("alpine")
        }
        return canExecute(proot)
    }

    private fun markerFile() = File(linuxDir, ".rootfs-ok")

    private fun cleanup() {
        runCatching {
            markerFile().delete()
            prootBinary.delete()
            rootfsDir.deleteRecursively()
        }
    }

    private fun resolveBundledBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val bundled = File(nativeDir, "libproot.so")
        if (!bundled.exists()) return null
        bundled.setExecutable(true, false)
        return bundled
    }

    private fun canExecute(proot: File): Boolean = runCatching {
        val process = ProcessBuilder(proot.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(EXEC_CHECK_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        process.destroyForcibly()
        finished && process.exitValue() == 0
    }.getOrDefault(false)

    private fun installProotStatic(): File {
        val arch = alpineArch()
        val version = resolveProotVersion(arch)
            ?: throw IOException("proot-static not found in repository index")
        val apk = File(context.cacheDir, "proot-static-$version-$arch.apk")
        downloadTo(apkDownloadUrl(arch, version), apk)
        linuxDir.mkdirs()
        extractTarGz(apk.inputStream(), linuxDir) { path ->
            path == PROOT_APK_PATH_BIN || path == PROOT_APK_PATH_SBIN
        }
        apk.delete()
        val extracted = sequenceOf(
            File(linuxDir, PROOT_APK_PATH_BIN),
            File(linuxDir, PROOT_APK_PATH_SBIN),
        ).firstOrNull { it.exists() } ?: throw IOException("proot.static missing inside package")
        extracted.setExecutable(true, false)
        extracted.copyTo(prootBinary, overwrite = true)
        prootBinary.setExecutable(true, false)
        extracted.delete()
        extracted.parentFile?.let { parent ->
            if (parent != linuxDir) parent.deleteRecursively()
        }
        return prootBinary
    }

    private fun resolveProotVersion(arch: String): String? {
        val index = File(context.cacheDir, "APKINDEX-proot.tar.gz")
        downloadTo(APKINDEX_URL.format(arch), index)
        var name: String? = null
        var version: String? = null
        var result: String? = null
        GZIPInputStream(index.inputStream()).use { gz ->
            val reader = TarReader(gz)
            while (true) {
                val entry = reader.nextEntry() ?: break
                if (entry.isDirectory || entry.name.contains("/")) {
                    reader.skipCurrent()
                    continue
                }
                val text = reader.readCurrentAsString()
                text.lineSequence().forEach { line ->
                    when {
                        line.startsWith("P:") -> name = line.removePrefix("P:")
                        line.startsWith("V:") -> version = line.removePrefix("V:")
                        line.isEmpty() && name == "proot-static" -> result = version
                    }
                }
                reader.skipCurrent()
            }
        }
        index.delete()
        return result
    }

    private fun alpineArch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armeabi" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> throw IOException("Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    private fun apkDownloadUrl(arch: String, version: String): String =
        "$ALPINE_REPO/$arch/proot-static-$version.apk"

    private fun rootfsUrl(): String {
        val arch = alpineArch()
        return "$ALPINE_CD/v$ALPINE_VERSION/releases/$arch/" +
            "alpine-minirootfs-$ALPINE_MINIROOTFS-$arch.tar.gz"
    }

    private fun downloadAndExtract(url: String, destination: File) {
        destination.mkdirs()
        val archive = File(context.cacheDir, "rootfs-download.tar.gz")
        downloadTo(url, archive)
        extractTarGz(archive.inputStream(), destination) { true }
        archive.delete()
    }

    private fun downloadTo(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ALPINE_CD = "https://dl-cdn.alpinelinux.org/alpine"
        private const val ALPINE_REPO = "$ALPINE_CD/edge/community"
        private const val ALPINE_VERSION = "3.19"
        private const val ALPINE_MINIROOTFS = "3.19.0"
        private const val APKINDEX_URL = "$ALPINE_REPO/%s/APKINDEX.tar.gz"
        private const val PROOT_APK_PATH_BIN = "usr/bin/proot.static"
        private const val PROOT_APK_PATH_SBIN = "usr/sbin/proot.static"
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val EXEC_CHECK_TIMEOUT_MS = 10_000
        private const val BUFFER_SIZE = 64 * 1024
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

    fun readCurrentAsString(): String {
        val buffer = ByteArray(currentSize().toInt())
        input.readFully(buffer)
        skipPadding(currentSize())
        return String(buffer, Charsets.UTF_8)
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
    GZIPInputStream(stream, COPY_BUFFER_SIZE).use { gz ->
        val reader = TarReader(gz)
        while (true) {
            val entry = reader.nextEntry() ?: break
            val safePath = Paths.get(destination.absolutePath, *entry.name.split('/').toTypedArray())
                .normalize()
            if (!safePath.startsWith(destination.absolutePath)) {
                reader.skipCurrent()
                continue
            }
            if (!include(entry.name)) {
                reader.skipCurrent()
                continue
            }
            if (entry.isDirectory) {
                Files.createDirectories(safePath)
            } else if (entry.isSymlink) {
                safePath.parent?.let { Files.createDirectories(it) }
                runCatching { Files.deleteIfExists(safePath) }
                runCatching { Files.createSymbolicLink(safePath, Paths.get(entry.linkTarget)) }
                    .onFailure { reader.readCurrentTo(OutputStream.nullOutputStream()) }
            } else {
                safePath.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(safePath).use { reader.readCurrentTo(it) }
            }
        }
    }
}
