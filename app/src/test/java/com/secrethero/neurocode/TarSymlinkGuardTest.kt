package com.secrethero.neurocode

import com.secrethero.neurocode.terminal.isSafeSymlinkTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tar-slip (Б-6 из ревью 0.9.0): цель симлинка из архива rootfs должна оставаться
 * внутри каталога распаковки. Alpine такие цели не использует, но архив приходит
 * по сети — проверяем каждую.
 */
class TarSymlinkGuardTest {

    @Test
    fun allowsRelativeTargetsInsideRootfs() {
        assertTrue(isSafeSymlinkTarget("bin/busybox"))
        assertTrue(isSafeSymlinkTarget("./sh"))
        assertTrue(isSafeSymlinkTarget("usr/lib/libc.so"))
    }

    @Test
    fun rejectsAbsoluteTargets() {
        assertFalse(isSafeSymlinkTarget("/data/local/tmp/x"))
        assertFalse(isSafeSymlinkTarget("/system/bin/sh"))
    }

    @Test
    fun rejectsParentTraversal() {
        assertFalse(isSafeSymlinkTarget("../outside"))
        assertFalse(isSafeSymlinkTarget("a/../../escape"))
        assertFalse(isSafeSymlinkTarget(".."))
    }

    @Test
    fun rejectsBlankTarget() {
        assertFalse(isSafeSymlinkTarget(""))
        assertFalse(isSafeSymlinkTarget("   "))
    }
}
