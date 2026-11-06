package com.secrethero.neurocode.ai

import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class AgentWorkflow(
    val id: String,
    val name: String,
    val steps: List<String>,
)

@Serializable
enum class AgentWorkflowStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

@Serializable
data class AgentWorkflowSnapshot(
    val runId: String,
    val workflowId: String,
    val status: AgentWorkflowStatus,
    val currentStep: Int,
    val results: List<String>,
    val error: String? = null,
) {
    val isFinished: Boolean
        get() = status != AgentWorkflowStatus.RUNNING
}

class AgentWorkflowRun internal constructor(
    initialSnapshot: AgentWorkflowSnapshot,
    private val job: Job,
) {
    private val mutableSnapshot = MutableStateFlow(initialSnapshot)
    val snapshot: StateFlow<AgentWorkflowSnapshot> = mutableSnapshot.asStateFlow()

    internal fun update(snapshot: AgentWorkflowSnapshot) {
        mutableSnapshot.value = snapshot
    }

    fun cancel() {
        job.cancel(CancellationException("Agent workflow cancelled: ${snapshot.value.runId}"))
    }

    suspend fun await(): AgentWorkflowSnapshot {
        job.join()
        return snapshot.value
    }
}

class AgentWorkflows(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxConcurrentRuns: Int = 4,
    private val maxSteps: Int = 64,
    private val maxStepResultChars: Int = 64 * 1024,
) : Closeable {
    private val lock = Any()
    private val runs = LinkedHashMap<String, AgentWorkflowRun>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var closed = false

    init {
        require(maxConcurrentRuns > 0) { "maxConcurrentRuns must be positive" }
        require(maxSteps > 0) { "maxSteps must be positive" }
        require(maxStepResultChars >= 0) { "maxStepResultChars must not be negative" }
    }

    fun start(workflow: AgentWorkflow, step: suspend (String) -> String): AgentWorkflowRun {
        require(workflow.id.isNotBlank()) { "Workflow id must not be blank" }
        require(workflow.steps.size <= maxSteps) { "Workflow has too many steps: ${workflow.steps.size}" }
        synchronized(lock) {
            check(!closed) { "AgentWorkflows is closed" }
            runs.values.removeAll { it.snapshot.value.isFinished }
            check(runs.size < maxConcurrentRuns) {
                "Maximum number of workflow runs reached: $maxConcurrentRuns"
            }
            val runId = UUID.randomUUID().toString()
            var snapshot = AgentWorkflowSnapshot(
                runId = runId,
                workflowId = workflow.id,
                status = AgentWorkflowStatus.RUNNING,
                currentStep = 0,
                results = emptyList(),
            )
            lateinit var run: AgentWorkflowRun
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    workflow.steps.forEachIndexed { index, command ->
                        snapshot = snapshot.copy(currentStep = index)
                        run.update(snapshot)
                        val result = step(command).take(maxStepResultChars)
                        snapshot = snapshot.copy(results = snapshot.results + result, currentStep = index + 1)
                        run.update(snapshot)
                    }
                    snapshot = snapshot.copy(status = AgentWorkflowStatus.SUCCEEDED)
                    run.update(snapshot)
                } catch (cancelled: CancellationException) {
                    run.update(snapshot.copy(status = AgentWorkflowStatus.CANCELLED))
                    throw cancelled
                } catch (failure: Throwable) {
                    run.update(
                        snapshot.copy(
                            status = AgentWorkflowStatus.FAILED,
                            error = failure.message ?: failure.javaClass.simpleName,
                        ),
                    )
                }
            }
            run = AgentWorkflowRun(snapshot, job)
            runs[runId] = run
            job.start()
            return run
        }
    }

    fun snapshots(): List<AgentWorkflowSnapshot> = synchronized(lock) {
        runs.values.map { it.snapshot.value }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        scope.cancel(CancellationException("AgentWorkflows closed"))
    }
}
