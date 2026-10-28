package com.secrethero.neurocode

import com.secrethero.neurocode.terminal.ExecutableBits
import com.secrethero.neurocode.terminal.ProotCommand
import com.secrethero.neurocode.terminal.describeExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotCommandTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val binary = "/data/app/lib/x86_64/libproot.so"
    private val elfHeader = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    private fun build(wrapped: List<String> = listOf("/bin/sh", "-l")) = ProotCommand.build(
        binary = binary,
        rootfs = "/data/files/linux/rootfs",
        workspace = "/data/files/workspaces/p1",
        tmpDir = "/data/files/linux/tmp",
        guestPath = "/usr/bin:/bin",
        wrapped = wrapped,
    )

    @Test
    fun commandNeverUsesTheUnsupportedEnvOption() {
        assertTrue(build().none { it.startsWith("--env") })
    }

    @Test
    fun prootOwnVariablesAreSetByTheHostShellBeforeTheBinary() {
        val command = build()

        assertEquals(listOf("/system/bin/sh", "-c"), command.take(2))
        assertTrue("PROOT_TMP_DIR" in command[2] && "PROOT_NO_SECCOMP=1" in command[2])
        assertEquals("/data/files/linux/tmp", command[command.indexOf("proot-launcher") + 1])
        assertTrue(command.indexOf("/data/files/linux/tmp") < command.indexOf(binary))
    }

    @Test
    fun binaryPathWithEqualsSignIsNotTakenForAnEnvAssignment() {
        val androidPath = "/data/app/~~j4Dxoif1ONx3aege2gqRBw==/" +
            "com.secrethero.neurocode-RTpvofJz3msukIsKq3fMog==/lib/x86_64/libproot.so"

        val command = ProotCommand.build(androidPath, "/r", "/w", "/t", "/bin", listOf("/bin/sh"))

        assertEquals("/system/bin/sh", command.first())
        assertTrue(androidPath in command)
        assertTrue(command.none { it.startsWith("PROOT_") })
        assertEquals("exec \"\$@\"", command[2].substringAfter("shift; "))
    }

    @Test
    fun guestEnvironmentIsSetThroughEnvInsideRootfs() {
        val command = build()
        val guestEnv = command.indexOf("/usr/bin/env")

        assertEquals("-i", command[guestEnv + 1])
        val guestPart = command.subList(guestEnv, command.size)
        assertTrue(guestPart.containsAll(listOf("HOME=/workspace", "PATH=/usr/bin:/bin")))
        assertEquals(listOf("/bin/sh", "-l"), command.takeLast(2))
    }

    @Test
    fun workspaceIsBoundAsWorkspaceAndRootfsIsUsedAsRoot() {
        val command = build()

        assertTrue("/data/files/workspaces/p1:/workspace" in command)
        assertEquals("/data/files/linux/rootfs", command[command.indexOf("-r") + 1])
        assertEquals("/workspace", command[command.indexOf("-w") + 1])
    }

    @Test
    fun wrappedCommandStaysLastAndIntact() {
        val wrapped = listOf("/bin/sh", "-c", "echo \"a b\" && ls")

        assertEquals(wrapped, build(wrapped).takeLast(3))
    }

    @Test
    fun exitCodesAboveOneHundredTwentyEightAreNamedAsSignals() {
        assertEquals("exit 159 (signal 31: SIGSYS, system call blocked by Android)", describeExit(159))
        assertEquals("exit 139 (signal 11: SIGSEGV, crash)", describeExit(139))
        assertEquals("exit 1", describeExit(1))
        assertEquals("exit 0", describeExit(0))
        assertEquals("exit 200", describeExit(200))
    }

    @Test
    fun elfAndShebangHeadersNeedTheExecutableBit() {
        assertTrue(ExecutableBits.needsExecutable(elfHeader))
        assertTrue(ExecutableBits.needsExecutable("#!/bin/sh\n".toByteArray()))
        assertFalse(ExecutableBits.needsExecutable("hello".toByteArray()))
        assertFalse(ExecutableBits.needsExecutable(ByteArray(0)))
    }

    @Test
    fun repairCountsOnlyBinariesAndScriptsAndSkipsPlainFiles() {
        val root = tmp.newFolder("rootfs")
        File(root, "bin").mkdirs()
        File(root, "bin/busybox").writeBytes(elfHeader + byteArrayOf(1, 2))
        File(root, "bin/script").writeText("#!/bin/sh\necho hi\n")
        File(root, "etc").mkdirs()
        File(root, "etc/alpine-release").writeText("3.19.0")
        File(root, "etc/empty").writeText("")

        assertEquals(2, ExecutableBits.repair(root))
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            assertTrue(File(root, "bin/busybox").canExecute())
            assertFalse(File(root, "etc/alpine-release").canExecute())
        }
    }
}
