package com.secrethero.neurocode

import com.secrethero.neurocode.terminal.ProotException
import com.secrethero.neurocode.terminal.ProotFailure
import com.secrethero.neurocode.terminal.ProotProgress
import com.secrethero.neurocode.terminal.ProotStage
import com.secrethero.neurocode.terminal.retrying
import com.secrethero.neurocode.terminal.toProotFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ProotSupportTest {
    @Test
    fun retryingReturnsValueAfterTransientFailures() {
        val pauses = mutableListOf<Long>()
        var calls = 0

        val result = retrying(attempts = 3, delayMs = 100, sleep = { pauses += it }) {
            calls++
            if (calls < 3) throw IOException("reset") else "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(100L, 200L), pauses)
    }

    @Test
    fun retryingRethrowsLastErrorWhenAllAttemptsFail() {
        var calls = 0

        val error = assertThrows(IOException::class.java) {
            retrying(attempts = 2, delayMs = 0, sleep = {}) { calls++; throw IOException("fail $calls") }
        }

        assertEquals("fail 2", error.message)
    }

    @Test
    fun retryingDoesNotRepeatPrecheckFailure() {
        var calls = 0

        assertThrows(ProotException::class.java) {
            retrying(attempts = 3, delayMs = 0, sleep = {}) {
                calls++
                throw ProotException(ProotStage.PRECHECK, "no space")
            }
        }

        assertEquals(1, calls)
    }

    @Test
    fun prootExceptionKeepsStageAndText() {
        val failure = ProotException(ProotStage.ROOTFS, "HTTP 404 for url").toProotFailure()

        assertEquals(ProotFailure(ProotStage.ROOTFS, "HTTP 404 for url"), failure)
    }

    @Test
    fun foreignExceptionFallsBackToVerifyStage() {
        val failure = IllegalStateException("boom").toProotFailure()

        assertEquals(ProotFailure(ProotStage.VERIFY, "boom"), failure)
    }

    @Test
    fun progressFractionIsNullWithoutTotal() {
        assertNull(ProotProgress(ProotStage.ROOTFS, 10, 0).fraction)
        assertEquals(0.5f, ProotProgress(ProotStage.ROOTFS, 5, 10).fraction)
    }
}
