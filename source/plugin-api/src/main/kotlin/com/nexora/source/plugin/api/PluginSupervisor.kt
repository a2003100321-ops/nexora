package com.nexora.source.plugin.api

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

public class PluginSupervisor(
    private val policy: PluginResourcePolicy = PluginResourcePolicy(),
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
    private val permits = Semaphore(policy.maxConcurrentTasks)
    private val active = ConcurrentHashMap<String, Job>()
    private val closed = AtomicBoolean(false)

    public suspend fun execute(
        request: SpiderRequestEnvelope,
        boundary: PluginExecutionBoundary,
    ): SpiderExecutionResult {
        SpiderContractValidator.validate(request, policy)?.let {
            return SpiderExecutionResult.Failure(it)
        }
        if (closed.get()) {
            return failure(SpiderFailureCode.PLUGIN_CRASHED, "插件运行器已关闭")
        }
        if (!permits.tryAcquire()) {
            return failure(SpiderFailureCode.RESOURCE_LIMIT, "插件运行器正忙")
        }
        try {
            return supervisorScope {
                val worker = async(dispatcher, start = CoroutineStart.LAZY) {
                    boundary.execute(request)
                }
                var registered = false
                try {
                    if (active.putIfAbsent(request.requestId, worker) != null) {
                        worker.cancel()
                        return@supervisorScope failure(
                            SpiderFailureCode.INVALID_INPUT,
                            "插件请求标识重复",
                        )
                    }
                    registered = true
                    if (closed.get()) {
                        worker.cancel()
                    } else {
                        worker.start()
                    }
                    try {
                        val result = withTimeout(policy.timeoutMillis) { worker.await() }
                        SpiderContractValidator.normalizeResult(result, policy)
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        stopWorker(worker)
                        failure(SpiderFailureCode.TIMEOUT, "插件执行超时")
                    } catch (cancellation: CancellationException) {
                        stopWorker(worker)
                        if (!currentCoroutineContext().isActive) throw cancellation
                        val code = if (closed.get()) {
                            SpiderFailureCode.PLUGIN_CRASHED
                        } else {
                            SpiderFailureCode.CANCELLED
                        }
                        failure(code, if (closed.get()) "插件运行器已关闭" else "插件请求已取消")
                    } catch (_: Exception) {
                        // Fatal JVM errors must cross this boundary and terminate the isolated worker.
                        failure(SpiderFailureCode.PLUGIN_CRASHED, "插件运行失败")
                    }
                } finally {
                    if (registered) active.remove(request.requestId, worker)
                    worker.cancel()
                }
            }
        } finally {
            permits.release()
        }
    }

    public fun cancel(requestId: String): Boolean {
        if (!SpiderContractValidator.isValidIdentifier(requestId) || closed.get()) return false
        return active[requestId]?.let { worker ->
            worker.cancel()
            true
        } ?: false
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.values.forEach(Job::cancel)
        active.clear()
    }

    private suspend fun stopWorker(worker: Job) {
        withContext(NonCancellable) {
            worker.cancelAndJoin()
        }
    }

    private fun failure(code: SpiderFailureCode, message: String): SpiderExecutionResult =
        SpiderExecutionResult.Failure(SpiderFailure(code, message))
}
