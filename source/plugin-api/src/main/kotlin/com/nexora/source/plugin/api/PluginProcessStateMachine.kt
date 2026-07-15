package com.nexora.source.plugin.api

import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Result of admitting a host request before it crosses the process boundary. */
public sealed interface PluginRequestRegistration {
    public data class Accepted(
        val handle: PluginPendingRequestHandle,
    ) : PluginRequestRegistration {
        public val result: Deferred<SpiderExecutionResult>
            get() = handle.result
    }

    public data class Rejected(
        val failure: SpiderFailure,
    ) : PluginRequestRegistration
}

/** Opaque identity for one admitted request, even when callers later reuse its textual ID. */
public class PluginPendingRequestHandle internal constructor(
    public val requestId: String,
    public val wireRequestId: String,
    public val result: Deferred<SpiderExecutionResult>,
)

/** Exact process generation, if any, from which a cancelled request must be withdrawn. */
public data class PluginRequestCancellation(
    val accepted: Boolean,
    val generation: Long?,
    val wireRequestId: String? = null,
)

/**
 * Thread-safe host-side registry for requests crossing an isolated-process boundary.
 *
 * Requests are admitted before binding so both bind waiters and Binder calls count towards the
 * same hard limit. Once a Binder is selected, [attachToGeneration] associates the request with
 * that exact process generation. Completion and death events must carry the same generation;
 * therefore a late callback or death notification from an old Binder cannot affect a reused
 * request ID that belongs to a newer process.
 */
public class PluginProcessStateMachine(
    private val maxPendingRequests: Int = 4,
) : Closeable {
    init {
        require(maxPendingRequests > 0)
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, PendingRequest>()
    private var closed = false

    public fun register(requestId: String): PluginRequestRegistration {
        if (!SpiderContractValidator.isValidIdentifier(requestId)) {
            return rejected(SpiderFailureCode.INVALID_INPUT, "插件请求标识无效")
        }
        return synchronized(lock) {
            when {
                closed -> rejected(SpiderFailureCode.PLUGIN_CRASHED, "插件隔离边界已关闭")
                requestId in pending ->
                    rejected(SpiderFailureCode.INVALID_INPUT, "插件请求标识重复")
                pending.size >= maxPendingRequests ->
                    rejected(SpiderFailureCode.RESOURCE_LIMIT, "插件并发请求已达到安全上限")
                else -> {
                    val result = CompletableDeferred<SpiderExecutionResult>()
                    val handle = PluginPendingRequestHandle(
                        requestId = requestId,
                        wireRequestId = "call:${UUID.randomUUID()}",
                        result = result,
                    )
                    pending[requestId] = PendingRequest(
                        handle = handle,
                        generation = null,
                        result = result,
                    )
                    PluginRequestRegistration.Accepted(handle)
                }
            }
        }
    }

    /** Associates an admitted request with one concrete Binder process generation. */
    public fun attachToGeneration(handle: PluginPendingRequestHandle, generation: Long): Boolean {
        if (generation <= 0) return false
        return synchronized(lock) {
            val request = pending[handle.requestId] ?: return@synchronized false
            if (request.handle !== handle) return@synchronized false
            if (request.generation != null) return@synchronized false
            request.generation = generation
            true
        }
    }

    public fun complete(
        handle: PluginPendingRequestHandle,
        generation: Long,
        result: SpiderExecutionResult,
    ): Boolean = remove(handle, generation)?.complete(result) ?: false

    /** Removes a request that failed locally before it was attached to any process. */
    public fun completeBeforeAttachment(
        handle: PluginPendingRequestHandle,
        result: SpiderExecutionResult,
    ): Boolean {
        val removed = synchronized(lock) {
            val request = pending[handle.requestId] ?: return@synchronized null
            if (request.handle !== handle || request.generation != null) return@synchronized null
            pending.remove(handle.requestId)?.result
        } ?: return false
        return removed.complete(result)
    }

    /** Cancels only this exact admission; it never affects a later request reusing the same ID. */
    public fun cancel(handle: PluginPendingRequestHandle): PluginRequestCancellation {
        val removed = synchronized(lock) {
            val request = pending[handle.requestId]
            if (request?.handle !== handle) return@synchronized null
            pending.remove(handle.requestId)
        } ?: return PluginRequestCancellation(accepted = false, generation = null)
        removed.result.complete(failure(SpiderFailureCode.CANCELLED, "插件请求已取消"))
        return PluginRequestCancellation(
            accepted = true,
            generation = removed.generation,
            wireRequestId = removed.handle.wireRequestId,
        )
    }

    public fun remoteException(handle: PluginPendingRequestHandle, generation: Long): Boolean = failOne(
        handle,
        generation,
        SpiderFailureCode.PLUGIN_CRASHED,
        "插件隔离进程通信失败",
    )

    /** Fails only requests attached to the Binder generation that actually died. */
    public fun binderDied(generation: Long): Int {
        if (generation <= 0) return 0
        val requests = synchronized(lock) {
            val matching = pending
                .filterValues { it.generation == generation }
                .mapValues { it.value.result }
            matching.keys.forEach(pending::remove)
            matching.values.toList()
        }
        val result = failure(SpiderFailureCode.PLUGIN_CRASHED, "插件隔离进程已终止")
        requests.forEach { it.complete(result) }
        return requests.size
    }

    override fun close() {
        val requests = synchronized(lock) {
            if (closed) return
            closed = true
            pending.values.map(PendingRequest::result).also { pending.clear() }
        }
        val result = failure(SpiderFailureCode.PLUGIN_CRASHED, "插件隔离边界已关闭")
        requests.forEach { it.complete(result) }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun failOne(
        handle: PluginPendingRequestHandle,
        generation: Long,
        code: SpiderFailureCode,
        message: String,
    ): Boolean = remove(handle, generation)?.complete(failure(code, message)) ?: false

    private fun remove(
        handle: PluginPendingRequestHandle,
        generation: Long,
    ): CompletableDeferred<SpiderExecutionResult>? = synchronized(lock) {
        val request = pending[handle.requestId] ?: return@synchronized null
        if (request.handle !== handle) return@synchronized null
        if (request.generation != generation) return@synchronized null
        pending.remove(handle.requestId)?.result
    }

    private fun rejected(
        code: SpiderFailureCode,
        message: String,
    ): PluginRequestRegistration.Rejected = PluginRequestRegistration.Rejected(
        SpiderFailure(code, message),
    )

    private fun failure(code: SpiderFailureCode, message: String): SpiderExecutionResult =
        SpiderExecutionResult.Failure(SpiderFailure(code, message))

    private data class PendingRequest(
        val handle: PluginPendingRequestHandle,
        var generation: Long?,
        val result: CompletableDeferred<SpiderExecutionResult>,
    )
}
