package com.nexora.source.sandbox

import java.io.Closeable
import kotlinx.coroutines.Job

/**
 * Synchronous admission performed on the Binder thread before a service coroutine is launched.
 *
 * A lease exists before its [Job] does, so cancellation that races between Binder entry and
 * coroutine attachment is retained by that exact lease. Unknown IDs are never retained and
 * therefore cannot poison a later request-ID reuse.
 */
internal class PluginLaunchAdmission(
    private val maxConcurrentTasks: Int,
) : Closeable {
    init {
        require(maxConcurrentTasks > 0)
    }

    private val lock = Any()
    private val active = LinkedHashMap<String, Lease>()
    private var closed = false

    fun tryAdmit(requestId: String): Result {
        return synchronized(lock) {
            when {
                closed -> Result.Rejected(AdmissionRejection.CLOSED)
                requestId in active -> Result.Rejected(AdmissionRejection.DUPLICATE_ID)
                active.size >= maxConcurrentTasks ->
                    Result.Rejected(AdmissionRejection.RESOURCE_LIMIT)
                else -> {
                    val lease = Lease(requestId, this)
                    active[requestId] = lease
                    Result.Accepted(lease)
                }
            }
        }
    }

    fun cancel(requestId: String): Boolean {
        var job: Job? = null
        val found = synchronized(lock) {
            val lease = active[requestId] ?: return@synchronized false
            lease.cancelled = true
            job = lease.job
            true
        }
        if (!found) return false
        job?.cancel()
        return true
    }

    override fun close() {
        val jobs = synchronized(lock) {
            if (closed) return
            closed = true
            active.values.mapNotNull(Lease::job).also { active.clear() }
        }
        jobs.forEach(Job::cancel)
    }

    internal fun activeCount(): Int = synchronized(lock) { active.size }

    private fun attach(lease: Lease, job: Job): Boolean {
        val cancelImmediately = synchronized(lock) {
            if (closed || active[lease.requestId] !== lease || lease.job != null) {
                return@synchronized null
            }
            lease.job = job
            lease.cancelled
        } ?: run {
            job.cancel()
            return false
        }
        job.invokeOnCompletion { lease.close() }
        if (cancelImmediately) job.cancel()
        return !cancelImmediately
    }

    private fun release(lease: Lease) {
        synchronized(lock) {
            if (active[lease.requestId] === lease) active.remove(lease.requestId)
        }
    }

    sealed interface Result {
        data class Accepted(val lease: Lease) : Result
        data class Rejected(val reason: AdmissionRejection) : Result
    }

    enum class AdmissionRejection {
        CLOSED,
        DUPLICATE_ID,
        RESOURCE_LIMIT,
    }

    class Lease internal constructor(
        internal val requestId: String,
        private val owner: PluginLaunchAdmission,
    ) : Closeable {
        internal var cancelled: Boolean = false
        internal var job: Job? = null

        fun attach(job: Job): Boolean = owner.attach(this, job)

        override fun close() {
            owner.release(this)
        }
    }
}
