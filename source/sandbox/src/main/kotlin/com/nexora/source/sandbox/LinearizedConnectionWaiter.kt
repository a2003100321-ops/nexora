package com.nexora.source.sandbox

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/**
 * Separates a lock-protected outcome decision from coroutine resumption.
 *
 * [markSuccess] or [markFailure] is called while the connection lock is held. The returned action
 * is invoked only after releasing that lock, so an unconfined continuation cannot execute Binder
 * work from inside the critical section. The atomic winner is the linearization point.
 */
internal class LinearizedConnectionWaiter<T> {
    private val marked = AtomicBoolean(false)
    private val deferred = CompletableDeferred<T>()

    fun markSuccess(value: T): (() -> Unit)? = mark { deferred.complete(value) }

    fun markFailure(error: Exception): (() -> Unit)? = mark {
        deferred.completeExceptionally(error)
    }

    suspend fun await(): T = deferred.await()

    private fun mark(completion: () -> Unit): (() -> Unit)? =
        completion.takeIf { marked.compareAndSet(false, true) }
}
