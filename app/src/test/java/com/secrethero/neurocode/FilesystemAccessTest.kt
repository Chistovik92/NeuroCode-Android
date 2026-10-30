package com.secrethero.neurocode

import com.secrethero.neurocode.data.FilesystemAccess
import com.secrethero.neurocode.data.FsPath
import com.secrethero.neurocode.model.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты логики доступа к файловой системе Android (0.8.2): настройки корней доступа,
 * нормализация путей внутри разрешённого корня и разбор аргументов инструментов fs_*.
 */
class FilesystemAccessTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `settings default has no filesystem roots`() {
        assertTrue(AppSettings().fsRoots.isEmpty())
    }

    @Test
    fun `settings with roots survive json round trip`() {
        val original = AppSettings(
            fsRoots = listOf("content://com.android.externalstorage/tree/primary%3ADownload"),
        )
        val encoded = Json.encodeToString(AppSettings.serializer(), original)
        val decoded = Json.decodeFromString(AppSettings.serializer(), encoded)
        assertEquals(listOf("content://com.android.externalstorage/tree/primary%3ADownload"), decoded.fsRoots)
    }

    @Test
    fun `old settings json without fsRoots decodes with default`() {
        val decoded = Json.decodeFromString(AppSettings.serializer(), """{"providers":[]}""")
        assertTrue(decoded.fsRoots.isEmpty())
    }

    @Test
    fun `path segments drop empty dot and dotdot`() {
        assertEquals(listOf("docs", "report.pdf"), FsPath.segments("/docs/.././report.pdf/"))
        assertEquals(emptyList<String>(), FsPath.segments(".."))
        assertEquals(emptyList<String>(), FsPath.segments(""))
    }

    @Test
    fun `traversal attempt cannot escape root`() {
        val segments = FsPath.segments("../../system/etc")
        assertTrue(segments.none { it == ".." })
        assertEquals(listOf("system", "etc"), segments)
    }

    @Test
    fun `join builds relative path inside root`() {
        assertEquals("docs", FsPath.join("", "docs"))
        assertEquals("docs/file.txt", FsPath.join("docs", "file.txt"))
    }

    @Test
    fun `sanitize name replaces forbidden characters`() {
        assertEquals("report_2026.pdf", FsPath.sanitizeName("report:2026.pdf"))
        assertEquals("file", FsPath.sanitizeName("  "))
        assertEquals("a_b", FsPath.sanitizeName("a/b"))
    }

    @Test
    fun `skipped directories include system folders`() {
        assertTrue("Android" in FilesystemAccess.SKIPPED_DIRECTORIES)
        assertTrue("Android/data" in FilesystemAccess.SKIPPED_DIRECTORIES)
        assertTrue("Android/obb" in FilesystemAccess.SKIPPED_DIRECTORIES)
        assertTrue(".git" in FilesystemAccess.SKIPPED_DIRECTORIES)
    }

    @Test
    fun `fs tool arguments root and path parse correctly`() {
        val arguments = json.parseToJsonElement(
            """{"root":"Download","path":"docs/report.pdf"}""",
        ).jsonObject
        assertEquals("Download", arguments["root"]?.jsonPrimitive?.content)
        assertEquals("docs/report.pdf", arguments["path"]?.jsonPrimitive?.content)
    }
}
