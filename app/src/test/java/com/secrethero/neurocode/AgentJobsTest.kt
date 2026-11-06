package com.secrethero.neurocode

import com.secrethero.neurocode.ai.AgentJobStatus
import com.secrethero.neurocode.ai.AgentJobs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJobsTest {
    @Test
    fun successfulJobStoresBoundedOutputAndCanBeRemoved() = runBlocking {
        val jobs = AgentJobs(
            runner = { _, command, emit ->
                emit("start:$command;")
                emit("end")
            },
            maxOutputChars = 12,
        ).use { manager ->
            val started = manager.start("project", "tests", "Run tests")
            val finished = manager.wait(started.id)

            assertEquals(AgentJobStatus.SUCCEEDED, finished.status)
            assertEquals("project", finished.projectId)
            assertEquals("Run tests", finished.description)
            assertEquals("start:tests;end".takeLast(12), finished.output)
            assertTrue(manager.remove(started.id))
            assertEquals(null, manager.check(started.id))
        }
    }

    @Test
    fun failedJobKeepsError() = runBlocking {
        AgentJobs(runner = { _, _, _ -> error("command failed") }).use { jobs ->
            val id = jobs.start("project", "bad command").id
            val result = jobs.wait(id)

            assertEquals(AgentJobStatus.FAILED, result.status)
            assertEquals("command failed", result.error)
        }
    }

    @Test
    fun stopCancelsRunningCoroutine() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        AgentJobs(
            runner = { _, _, _ ->
                started.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        ).use { jobs ->
            val id = jobs.start("project", "long command").id
            // Ждём, пока команда реально стартует: иначе stop() может обогнать тело корутины
            // и блок finally с complete() не выполнится.
            started.await()
            assertTrue(jobs.stop(id))
            withTimeout(5_000) { cancelled.await() }
            assertEquals(AgentJobStatus.CANCELLED, jobs.wait(id).status)
            assertFalse(jobs.stop(id))
        }
    }

    @Test
    fun runningJobsCountTowardLimit() {
        AgentJobs(runner = { _, _, _ -> kotlinx.coroutines.awaitCancellation() }, maxJobs = 1).use { jobs ->
            jobs.start("project", "first")
            val failure = runCatching { jobs.start("project", "second") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure is IllegalStateException)
        }
    }

    @Test
    fun completedJobsAreReclaimedBeforeStartingAnother() = runBlocking {
        AgentJobs(runner = { _, _, _ -> }, maxJobs = 1).use { jobs ->
            val first = jobs.start("project", "first")
            jobs.wait(first.id)
            val second = jobs.start("project", "second")
            assertEquals(AgentJobStatus.SUCCEEDED, jobs.wait(second.id).status)
        }
    }

    @Test
    fun closeCancelsRunningJobs() = runBlocking(Dispatchers.Default) {
        val jobs = AgentJobs(runner = { _, _, _ -> kotlinx.coroutines.awaitCancellation() })
        val id = jobs.start("project", "long").id
        jobs.close()
        assertEquals(AgentJobStatus.CANCELLED, jobs.wait(id).status)
    }
}
