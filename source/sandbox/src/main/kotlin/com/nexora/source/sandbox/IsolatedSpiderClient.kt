package com.nexora.source.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.nexora.source.plugin.api.IsolatedPluginHostPolicy
import com.nexora.source.plugin.api.PluginEngineKind
import com.nexora.source.plugin.api.PluginPendingRequestHandle
import com.nexora.source.plugin.api.PluginProcessStateMachine
import com.nexora.source.plugin.api.PluginRequestRegistration
import com.nexora.source.plugin.api.PluginResourcePolicy
import com.nexora.source.plugin.api.SpiderContractValidator
import com.nexora.source.plugin.api.SpiderExecutionResult
import com.nexora.source.plugin.api.SpiderFailure
import com.nexora.source.plugin.api.SpiderFailureCode
import com.nexora.source.plugin.api.SpiderIpcCodec
import com.nexora.source.plugin.api.SpiderRequestEnvelope
import com.nexora.source.sandbox.ipc.ISpiderCallback
import com.nexora.source.sandbox.ipc.ISpiderSandbox
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Production host adapter for the non-exported isolated Spider service.
 *
 * Every admitted logical request receives a unique wire ID and is attached to one Binder
 * generation. Callback, cancellation, remote-exception and death paths all carry that identity,
 * preventing delayed work from an old process or an old ID reuse from touching a current call.
 */
public class IsolatedSpiderClient(
    context: Context,
    private val policy: PluginResourcePolicy = PluginResourcePolicy(),
) : Closeable {
    init {
        IsolatedPluginHostPolicy.requireSupported(policy)
    }

    private val applicationContext = context.applicationContext
    private val state = PluginProcessStateMachine(policy.maxConcurrentTasks)
    private val connectionLock = Any()
    private var closed = false
    private var bindingActive = false
    private var activeConnection: SpiderServiceConnection? = null
    private var currentSession: RemoteSession? = null
    private var connectingSession: RemoteSession? = null
    private var connectionWaiter: LinearizedConnectionWaiter<RemoteSession>? = null
    private var nextGeneration = 0L

    public suspend fun execute(
        request: SpiderRequestEnvelope,
        engineKind: PluginEngineKind,
    ): SpiderExecutionResult {
        SpiderContractValidator.validate(request, policy)?.let {
            return SpiderExecutionResult.Failure(it)
        }
        val registration = state.register(request.requestId)
        if (registration is PluginRequestRegistration.Rejected) {
            return SpiderExecutionResult.Failure(registration.failure)
        }
        val handle = (registration as PluginRequestRegistration.Accepted).handle
        val wireRequest = request.copy(requestId = handle.wireRequestId)
        val encoded = SpiderIpcCodec.encodeRequest(wireRequest, engineKind, policy).getOrElse {
            val result = failure(SpiderFailureCode.RESOURCE_LIMIT, "插件请求超过 IPC 安全上限")
            state.completeBeforeAttachment(handle, result)
            return result
        }
        var acquisition: RemoteAcquisition? = null
        var requestSession: RemoteSession? = null

        return try {
            withTimeout(policy.timeoutMillis) {
                acquisition = acquireRemote()
                val session = awaitRemote(acquisition)
                requestSession = session
                if (!state.attachToGeneration(handle, session.generation)) {
                    return@withTimeout handle.result.await()
                }
                val callback = callbackFor(handle, session.generation)
                if (!isCurrent(session)) {
                    state.remoteException(handle, session.generation)
                    return@withTimeout handle.result.await()
                }
                try {
                    session.sandbox.execute(encoded, callback)
                } catch (_: RemoteException) {
                    state.remoteException(handle, session.generation)
                    if (!session.binder.isBinderAlive) markRemoteDead(session)
                }
                handle.result.await()
            }
        } catch (_: TimeoutCancellationException) {
            expireBindingAttempt(acquisition)
            val cancellation = state.cancel(handle)
            if (cancellation.accepted) {
                cancelRemote(
                    requestSession,
                    cancellation.wireRequestId,
                    cancellation.generation,
                )
            }
            failure(SpiderFailureCode.TIMEOUT, "插件执行超时")
        } catch (cancellationError: CancellationException) {
            expireBindingAttempt(acquisition)
            val cancellation = state.cancel(handle)
            if (cancellation.accepted) {
                cancelRemote(
                    requestSession,
                    cancellation.wireRequestId,
                    cancellation.generation,
                )
            }
            throw cancellationError
        } catch (_: Exception) {
            // LinkageError/VirtualMachineError intentionally cross this host boundary.
            expireBindingAttempt(acquisition)
            val session = requestSession
            val result = failure(SpiderFailureCode.PLUGIN_CRASHED, "插件隔离进程连接失败")
            if (session == null) {
                state.completeBeforeAttachment(handle, result)
            } else {
                state.remoteException(handle, session.generation)
            }
            result
        }
    }

    override fun close() {
        val snapshot = synchronized(connectionLock) {
            if (closed) return
            closed = true
            ConnectionSnapshot(
                connection = activeConnection,
                sessions = listOfNotNull(currentSession, connectingSession),
                waiterCompletion = connectionWaiter?.markFailure(
                    SpiderSandboxDisconnectedException(),
                ),
            ).also {
                bindingActive = false
                activeConnection = null
                currentSession = null
                connectingSession = null
                connectionWaiter = null
            }
        }
        state.close()
        snapshot.waiterCompletion?.invoke()
        snapshot.sessions.forEach(::unlinkDeath)
        safeUnbind(snapshot.connection)
    }

    private fun callbackFor(
        handle: PluginPendingRequestHandle,
        generation: Long,
    ): ISpiderCallback = object : ISpiderCallback.Stub() {
        override fun onResult(resultEnvelope: String?) {
            val result = resultEnvelope
                ?.let {
                    SpiderIpcCodec.decodeResult(
                        it,
                        handle.wireRequestId,
                        policy,
                    ).getOrNull()
                }
                ?: failure(SpiderFailureCode.BAD_RESPONSE, "插件返回了无效结果")
            state.complete(handle, generation, result)
        }
    }

    private fun acquireRemote(): RemoteAcquisition = synchronized(connectionLock) {
        if (closed) throw SpiderSandboxDisconnectedException()
        currentSession?.let(RemoteAcquisition::Immediate) ?: run {
            val waiter = connectionWaiter ?: LinearizedConnectionWaiter<RemoteSession>().also {
                connectionWaiter = it
            }
            val connection = if (!bindingActive) {
                SpiderServiceConnection().also {
                    bindingActive = true
                    activeConnection = it
                }
            } else {
                null
            }
            RemoteAcquisition.Waiting(waiter, connection)
        }
    }

    private suspend fun awaitRemote(acquisition: RemoteAcquisition): RemoteSession {
        if (acquisition is RemoteAcquisition.Immediate) {
            return synchronized(connectionLock) {
                if (closed || currentSession !== acquisition.session) {
                    throw SpiderSandboxDisconnectedException()
                }
                acquisition.session
            }
        }
        acquisition as RemoteAcquisition.Waiting
        acquisition.connection?.let { connection ->
            val started: Boolean? = try {
                applicationContext.bindService(
                    Intent(applicationContext, IsolatedSpiderService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (error: Exception) {
                failBindingAttempt(acquisition.waiter, connection, error)
                null
            }
            if (started == false) {
                failBindingAttempt(
                    acquisition.waiter,
                    connection,
                    SpiderSandboxDisconnectedException(),
                )
            } else if (started == true) {
                val stillActive = synchronized(connectionLock) {
                    !closed && activeConnection === connection
                }
                if (!stillActive) safeUnbind(connection)
            }
        }
        val session = acquisition.waiter.await()
        return synchronized(connectionLock) {
            if (closed || currentSession !== session) throw SpiderSandboxDisconnectedException()
            session
        }
    }

    private fun failBindingAttempt(
        waiter: LinearizedConnectionWaiter<RemoteSession>,
        connection: SpiderServiceConnection,
        error: Exception,
    ) {
        var connecting: RemoteSession? = null
        var waiterCompletion: (() -> Unit)? = null
        val shouldUnbind = synchronized(connectionLock) {
            if (activeConnection !== connection || connectionWaiter !== waiter) {
                false
            } else {
                connectionWaiter = null
                activeConnection = null
                bindingActive = false
                connecting = connectingSession
                connectingSession = null
                waiterCompletion = waiter.markFailure(error)
                true
            }
        }
        waiterCompletion?.invoke()
        connecting?.let(::unlinkDeath)
        if (shouldUnbind) safeUnbind(connection)
    }

    /** Resets a bindService(true)-without-callback attempt so the next call performs a fresh bind. */
    private fun expireBindingAttempt(acquisition: RemoteAcquisition?) {
        val waiting = acquisition as? RemoteAcquisition.Waiting ?: return
        var connecting: RemoteSession? = null
        var waiterCompletion: (() -> Unit)? = null
        val connection = synchronized(connectionLock) {
            if (connectionWaiter !== waiting.waiter || currentSession != null) {
                return@synchronized null
            }
            connecting = connectingSession
            connectingSession = null
            connectionWaiter = null
            waiterCompletion = waiting.waiter.markFailure(
                SpiderSandboxDisconnectedException(),
            )
            activeConnection.also {
                activeConnection = null
                bindingActive = false
            }
        }
        waiterCompletion?.invoke()
        connecting?.let(::unlinkDeath)
        safeUnbind(connection)
    }

    private fun connectionLost(
        source: SpiderServiceConnection,
        expected: ExpectedRemote?,
        bindingDied: Boolean,
    ) {
        val loss = synchronized(connectionLock) {
            if (activeConnection !== source) return@synchronized null
            val sessions = if (expected == null) {
                listOfNotNull(currentSession, connectingSession)
            } else {
                val exact = listOfNotNull(currentSession, connectingSession).firstOrNull {
                    it.generation == expected.generation && it.binder === expected.binder
                } ?: return@synchronized null
                listOf(exact)
            }
            sessions.forEach { session ->
                if (currentSession === session) currentSession = null
                if (connectingSession === session) connectingSession = null
            }
            val waiterCompletion = if (currentSession == null && connectingSession == null) {
                connectionWaiter?.markFailure(SpiderSandboxDisconnectedException()).also {
                    connectionWaiter = null
                }
            } else {
                null
            }
            val unbind = if (bindingDied) {
                activeConnection.also {
                    activeConnection = null
                    bindingActive = false
                }
            } else {
                null
            }
            ConnectionLoss(sessions, unbind, waiterCompletion)
        } ?: return

        loss.waiterCompletion?.invoke()
        loss.sessions.forEach { session ->
            unlinkDeath(session)
            state.binderDied(session.generation)
        }
        safeUnbind(loss.connectionToUnbind)
    }

    private fun cancelRemote(
        session: RemoteSession?,
        wireRequestId: String?,
        generation: Long?,
    ) {
        if (session == null || wireRequestId == null || generation == null) return
        val exact = synchronized(connectionLock) {
            currentSession?.takeIf {
                it === session && it.generation == generation && !closed
            }
        } ?: return
        try {
            exact.sandbox.cancel(wireRequestId)
        } catch (_: RemoteException) {
            if (!exact.binder.isBinderAlive) markRemoteDead(exact)
        }
    }

    private fun markRemoteDead(session: RemoteSession) {
        val connection = synchronized(connectionLock) {
            currentSession?.takeIf {
                it === session && it.generation == session.generation && it.binder === session.binder
            }?.let { activeConnection }
        }
        connection?.let {
            connectionLost(
                it,
                ExpectedRemote(session.generation, session.binder),
                bindingDied = true,
            )
        }
    }

    private fun isCurrent(session: RemoteSession): Boolean = synchronized(connectionLock) {
        !closed && currentSession === session
    }

    private fun safeUnbind(connection: ServiceConnection?) {
        if (connection == null) return
        try {
            applicationContext.unbindService(connection)
        } catch (_: Exception) {
            // A concurrent framework disconnect may already have removed this binding.
        }
    }

    private fun unlinkDeath(session: RemoteSession) {
        val recipient = session.recipient ?: return
        unlinkDeath(session.binder, recipient)
    }

    private fun unlinkDeath(binder: IBinder, recipient: IBinder.DeathRecipient) {
        try {
            binder.unlinkToDeath(recipient, 0)
        } catch (_: Exception) {
            // The binder was already dead or unlinked.
        }
    }

    private fun failure(code: SpiderFailureCode, message: String): SpiderExecutionResult =
        SpiderExecutionResult.Failure(SpiderFailure(code, message))

    private inner class SpiderServiceConnection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                connectionLost(this, expected = null, bindingDied = true)
                return
            }
            val sandbox = ISpiderSandbox.Stub.asInterface(binder)
            var replaced: List<RemoteSession> = emptyList()
            val prepared = synchronized(connectionLock) {
                if (closed || activeConnection !== this) {
                    null
                } else {
                    replaced = listOfNotNull(currentSession, connectingSession)
                    currentSession = null
                    connectingSession = null
                    val generation = nextConnectionGeneration()
                    RemoteSession(sandbox, binder, generation, recipient = null).also {
                        connectingSession = it
                    }
                }
            }
            replaced.forEach { old ->
                unlinkDeath(old)
                state.binderDied(old.generation)
            }
            if (prepared == null) return

            val recipient = IBinder.DeathRecipient {
                connectionLost(
                    this,
                    ExpectedRemote(prepared.generation, binder),
                    bindingDied = true,
                )
            }
            try {
                binder.linkToDeath(recipient, 0)
            } catch (_: RemoteException) {
                connectionLost(
                    this,
                    ExpectedRemote(prepared.generation, binder),
                    bindingDied = true,
                )
                return
            }
            if (!binder.isBinderAlive) {
                connectionLost(
                    this,
                    ExpectedRemote(prepared.generation, binder),
                    bindingDied = true,
                )
                // The prepared session intentionally did not publish the recipient yet, so the
                // generic connection-loss cleanup cannot see this exact registration.
                unlinkDeath(binder, recipient)
                return
            }

            val connected = prepared.copy(recipient = recipient)
            var waiterCompletion: (() -> Unit)? = null
            val accepted = synchronized(connectionLock) {
                val connecting = connectingSession
                if (closed ||
                    activeConnection !== this ||
                    connecting?.generation != prepared.generation ||
                    connecting.binder !== binder
                ) {
                    false
                } else {
                    connectingSession = null
                    currentSession = connected
                    val waiter = connectionWaiter
                    connectionWaiter = null
                    // Only the outcome marker is set under the lock. Invoking it below prevents
                    // an unconfined waiter from executing Binder work inside this critical section.
                    waiterCompletion = waiter?.markSuccess(connected)
                    true
                }
            }
            if (accepted) {
                waiterCompletion?.invoke()
            } else {
                unlinkDeath(connected)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionLost(this, expected = null, bindingDied = false)
        }

        override fun onBindingDied(name: ComponentName?) {
            connectionLost(this, expected = null, bindingDied = true)
        }
    }

    private fun nextConnectionGeneration(): Long {
        nextGeneration = if (nextGeneration == Long.MAX_VALUE) 1 else nextGeneration + 1
        return nextGeneration
    }

    private sealed interface RemoteAcquisition {
        data class Immediate(val session: RemoteSession) : RemoteAcquisition
        data class Waiting(
            val waiter: LinearizedConnectionWaiter<RemoteSession>,
            val connection: SpiderServiceConnection?,
        ) : RemoteAcquisition
    }

    private data class RemoteSession(
        val sandbox: ISpiderSandbox,
        val binder: IBinder,
        val generation: Long,
        val recipient: IBinder.DeathRecipient?,
    )

    private data class ExpectedRemote(
        val generation: Long,
        val binder: IBinder,
    )

    private data class ConnectionLoss(
        val sessions: List<RemoteSession>,
        val connectionToUnbind: SpiderServiceConnection?,
        val waiterCompletion: (() -> Unit)?,
    )

    private data class ConnectionSnapshot(
        val connection: SpiderServiceConnection?,
        val sessions: List<RemoteSession>,
        val waiterCompletion: (() -> Unit)?,
    )
}

private class SpiderSandboxDisconnectedException : Exception("Spider sandbox disconnected")
