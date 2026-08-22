package com.secrethero.neurocode

import com.secrethero.neurocode.data.PathGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class PathGuardTest {

    private val root = File("workspaces/test-root")

    @Test
    fun resolvesNestedPathInsideRoot() {
        val resolved = PathGuard.resolveWithin(root, "src/main/App.kt")
        assertEquals(File(root.canonicalFile, "src/main/App.kt").canonicalFile, resolved)
    }

    @Test
    fun resolvesProjectRootItself() {
        assertEquals(root.canonicalFile, PathGuard.resolveWithin(root, ""))
        assertEquals(root.canonicalFile, PathGuard.resolveWithin(root, "/"))
    }

    @Test
    fun rejectsParentTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            PathGuard.resolveWithin(root, "../outside.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PathGuard.resolveWithin(root, "a/../../b.txt")
        }
    }

    @Test
    fun absoluteLeadingSlashIsContainedInsideProject() {
        val resolved = PathGuard.resolveWithin(root, "/tmp/file.txt")
        assertEquals(File(root.canonicalFile, "tmp/file.txt").canonicalFile, resolved)
    }
}
