package com.secrethero.neurocode

import com.secrethero.neurocode.ai.AgentWorkflow
import com.secrethero.neurocode.ai.AgentWorkflowStatus
import com.secrethero.neurocode.ai.AgentWorkflows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkflowsTest {
    @Test
    fun stepsRunSequentiallyAndResultsAreBounded() = runBlocking {
        val calls = mutableListOf<String>()
        AgentWorkflows(maxStepResultChars = 5).use { workflows ->
            val run = workflows.start(AgentWorkflow("id", "name", listOf("one", "two"))) { step ->
                calls += step
                "result:$step"
            }
            val result = run.await()

            assertEquals(AgentWorkflowStatus.SUCCEEDED, result.status)
            assertEquals(listOf("one", "two"), calls)
            assertEquals(listOf("resul", "resul"), result.results)
            assertEquals(2, result.currentStep)
            assertEquals(result, run.snapshot.value)
        }
    }

    @Test
    fun failureStopsFollowingSteps() = runBlocking {
        AgentWorkflows().use { workflows ->
            val calls = mutableListOf<String>()
            val run = workflows.start(AgentWorkflow("id", "name", listOf("one", "two"))) { step ->
                calls += step
                error("step failed")
            }
            val result = run.await()

            assertEquals(AgentWorkflowStatus.FAILED, result.status)
            assertEquals("step failed", result.error)
            assertEquals(listOf("one"), calls)
            assertTrue(result.results.isEmpty())
        }
    }

    @Test
    fun cancelCancelsRunningStep() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        AgentWorkflows().use { workflows ->
            val run = workflows.start(AgentWorkflow("id", "name", listOf("one"))) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            // Ждём, пока шаг реально стартует: иначе cancel() может обогнать тело корутины
            // (CoroutineStart.LAZY) и блок finally с complete() просто не выполнится.
            started.await()
            run.cancel()
            withTimeout(5_000) { cancelled.await() }
            assertEquals(AgentWorkflowStatus.CANCELLED, run.await().status)
        }
    }

    @Test
    fun limitsRejectTooManyStepsAndConcurrentRuns() {
        AgentWorkflows(maxConcurrentRuns = 1, maxSteps = 1).use { workflows ->
            workflows.start(AgentWorkflow("id", "name", listOf("one"))) { awaitCancellation() }
            assertNotNull(runCatching {
                workflows.start(AgentWorkflow("id", "name", listOf("one"))) { "" }
            }.exceptionOrNull())
            assertNotNull(runCatching {
                workflows.start(AgentWorkflow("id", "name", listOf("one", "two"))) { "" }
            }.exceptionOrNull())
        }
    }

    @Test
    fun completedRunsDoNotConsumeConcurrencyLimit() = runBlocking {
        AgentWorkflows(maxConcurrentRuns = 1).use { workflows ->
            val workflow = AgentWorkflow("id", "name", listOf("one"))
            workflows.start(workflow) { "first" }.await()
            val result = workflows.start(workflow) { "second" }.await()
            assertEquals(AgentWorkflowStatus.SUCCEEDED, result.status)
            assertEquals(listOf("second"), result.results)
            assertTrue(result.isFinished)
        }
    }
}
