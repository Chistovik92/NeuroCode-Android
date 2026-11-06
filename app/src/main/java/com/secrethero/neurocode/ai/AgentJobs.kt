package com.secrethero.neurocode.ai

import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class AgentJobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class AgentJobSnapshot(
    val id: String,
    val projectId: String,
    val command: String,
    val description: String,
    val status: AgentJobStatus,
    val output: String,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isFinished: Boolean
        get() = status != AgentJobStatus.RUNNING
}

fun interface AgentCommandRunner {
    suspend fun run(projectId: String, command: String, emit: suspend (String) -> Unit)
}

class AgentJobs(
    private val runner: AgentCommandRunner,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxJobs: Int = 32,
    private val maxOutputChars: Int = 64 * 1024,
) : Closeable {
    private data class MutableJob(
        val id: String,
        val projectId: String,
        val command: String,
        val description: String,
        val createdAt: Long,
        var updatedAt: Long,
        var status: AgentJobStatus = AgentJobStatus.RUNNING,
        var error: String? = null,
        val output: StringBuilder = StringBuilder(),
        var coroutine: Job? = null,
    )

    private val lock = Any()
    private val jobs = LinkedHashMap<String, MutableJob>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var closed = false

    init {
        require(maxJobs > 0) { "maxJobs must be positive" }
        require(maxOutputChars >= 0) { "maxOutputChars must not be negative" }
    }

    fun start(projectId: String, command: String, description: String = command): AgentJobSnapshot {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val job = MutableJob(id, projectId, command, description, now, now)
        return synchronized(lock) {
            check(!closed) { "AgentJobs is closed" }
            jobs.values.removeAll { it.isFinished() }
            check(jobs.size < maxJobs) { "Maximum number of agent jobs reached: $maxJobs" }
            jobs[id] = job
            job.coroutine = scope.launch {
                execute(job)
            }.also { coroutine ->
                coroutine.invokeOnCompletion { cause ->
                    if (cause is CancellationException) finishIfRunning(job, AgentJobStatus.CANCELLED)
                }
            }
            job.snapshot()
        }
    }

    fun check(id: String): AgentJobSnapshot? = synchronized(lock) { jobs[id]?.snapshot() }

    fun read(id: String): String? = synchronized(lock) { jobs[id]?.output?.toString() }

    suspend fun wait(id: String): AgentJobSnapshot {
        val coroutine = synchronized(lock) {
            requireNotNull(jobs[id]) { "Unknown agent job: $id" }.coroutine
        }
        checkNotNull(coroutine).join()
        return checkNotNull(check(id))
    }

    fun stop(id: String): Boolean {
        val coroutine = synchronized(lock) { jobs[id]?.takeUnless { it.isFinished() }?.coroutine }
            ?: return false
        coroutine.cancel(CancellationException("Agent job stopped: $id"))
        return true
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val job = jobs[id] ?: return@synchronized false
        if (!job.isFinished()) return@synchronized false
        jobs.remove(id)
        true
    }

    fun snapshots(): List<AgentJobSnapshot> = synchronized(lock) { jobs.values.map { it.snapshot() } }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        scope.cancel(CancellationException("AgentJobs closed"))
    }

    private suspend fun execute(job: MutableJob) {
        try {
            runner.run(job.projectId, job.command) { text ->
                synchronized(lock) {
                    if (maxOutputChars > 0) {
                        job.output.append(text)
                        if (job.output.length > maxOutputChars) {
                            job.output.delete(0, job.output.length - maxOutputChars)
                        }
                    }
                }
            }
            finish(job, AgentJobStatus.SUCCEEDED)
        } catch (cancelled: CancellationException) {
            finish(job, AgentJobStatus.CANCELLED)
            throw cancelled
        } catch (failure: Throwable) {
            synchronized(lock) {
                job.error = failure.message ?: failure.javaClass.simpleName
            }
            finish(job, AgentJobStatus.FAILED)
        }
    }

    private fun finish(job: MutableJob, status: AgentJobStatus) {
        synchronized(lock) {
            job.status = status
            job.updatedAt = System.currentTimeMillis()
        }
    }

    private fun finishIfRunning(job: MutableJob, status: AgentJobStatus) {
        synchronized(lock) {
            if (job.status == AgentJobStatus.RUNNING) {
                job.status = status
                job.updatedAt = System.currentTimeMillis()
            }
        }
    }

    private fun MutableJob.isFinished(): Boolean = status != AgentJobStatus.RUNNING

    private fun MutableJob.snapshot(): AgentJobSnapshot = AgentJobSnapshot(
        id = id,
        projectId = projectId,
        command = command,
        description = description,
        status = status,
        output = output.toString(),
        error = error,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
