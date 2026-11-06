package com.secrethero.neurocode

import com.secrethero.neurocode.ai.SubagentLimits
import com.secrethero.neurocode.ai.SubagentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentPolicyTest {
    @Test
    fun validLimitsAllowOnlyDepthAndParallelismWithinBounds() {
        val policy = SubagentPolicy(SubagentLimits(maxDepth = 2, maxParallel = 3))

        assertTrue(policy.canSpawn(depth = 1, activeParallel = 2))
        assertFalse(policy.canSpawn(depth = 2, activeParallel = 0))
        assertFalse(policy.canSpawn(depth = 0, activeParallel = 3))
        assertFalse(policy.canSpawn(depth = -1, activeParallel = 0))
    }

    @Test
    fun invalidLimitsAreRejected() {
        listOf(
            SubagentLimits(maxDepth = -1),
            SubagentLimits(maxParallel = 0),
            SubagentLimits(timeoutMillis = 0),
            SubagentLimits(maxTaskChars = 0),
            SubagentLimits(maxDescriptionChars = -1),
            SubagentLimits(maxResultChars = -1),
        ).forEach { limits ->
            assertNotNull(runCatching { SubagentPolicy(limits) }.exceptionOrNull())
        }
    }

    @Test
    fun taskAndDescriptionAreSanitizedAndBounded() {
        val policy = SubagentPolicy(
            SubagentLimits(maxTaskChars = 8, maxDescriptionChars = 5),
        )
        val task = policy.sanitize("  do\u0000 something\r\nnow  ", "  useful\u0007 description ")

        assertEquals("do somet", task.task)
        assertEquals("usefu", task.description)
        assertFalse(task.task.any { it != '\n' && it != '\t' && it.isISOControl() })
    }

    @Test
    fun blankTaskIsRejectedAndResultIsBounded() {
        val policy = SubagentPolicy(SubagentLimits(maxResultChars = 4))

        assertNotNull(runCatching { policy.sanitize(" \n\u0000 ") }.exceptionOrNull())
        assertEquals("line", policy.sanitizeResult(" line\u0000 of text "))
    }
}
