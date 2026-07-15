package com.nexora.source.sandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import com.nexora.source.plugin.api.PluginExecutionBoundary
import com.nexora.source.plugin.api.PluginResourcePolicy
import com.nexora.source.plugin.api.PluginSupervisor
import com.nexora.source.plugin.api.SpiderContractValidator
import com.nexora.source.plugin.api.SpiderExecutionResult
import com.nexora.source.plugin.api.SpiderFailure
import com.nexora.source.plugin.api.SpiderFailureCode
import com.nexora.source.plugin.api.SpiderIpcCodec
import com.nexora.source.sandbox.ipc.ISpiderCallback
import com.nexora.source.sandbox.ipc.ISpiderSandbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

public class IsolatedSpiderService : Service() {
    private val policy = PluginResourcePolicy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val supervisor = PluginSupervisor(policy)
    private val launchAdmission = PluginLaunchAdmission(policy.maxConcurrentTasks)

    private val binder = object : ISpiderSandbox.Stub() {
        override fun execute(requestEnvelope: String?, callback: ISpiderCallback?) {
            if (requestEnvelope == null || callback == null) return
            val decoded = SpiderIpcCodec.decodeRequest(requestEnvelope).getOrElse {
                deliver(callback, SpiderIpcCodec.invalidRequest(policy = policy))
                return
            }
            val admission = when (val result = launchAdmission.tryAdmit(decoded.request.requestId)) {
                is PluginLaunchAdmission.Result.Accepted -> result.lease
                is PluginLaunchAdmission.Result.Rejected -> {
                    val code = when (result.reason) {
                        PluginLaunchAdmission.AdmissionRejection.DUPLICATE_ID ->
                            SpiderFailureCode.INVALID_INPUT
                        PluginLaunchAdmission.AdmissionRejection.CLOSED ->
                            SpiderFailureCode.PLUGIN_CRASHED
                        PluginLaunchAdmission.AdmissionRejection.RESOURCE_LIMIT ->
                            SpiderFailureCode.RESOURCE_LIMIT
                    }
                    deliver(
                        callback,
                        SpiderIpcCodec.encodeResult(
                            decoded.request.requestId,
                            SpiderExecutionResult.Failure(
                                SpiderFailure(code, "插件并发任务已达到安全上限"),
                            ),
                            policy,
                        ),
                    )
                    return
                }
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val validationFailure = SpiderContractValidator.validate(
                    decoded.request,
                    policy,
                )
                val engine = if (validationFailure == null) {
                    FixtureEngineRegistry.resolve(decoded.request.pluginId, decoded.engineKind)
                } else {
                    null
                }
                val result = if (validationFailure != null) {
                    SpiderExecutionResult.Failure(validationFailure)
                } else if (engine == null) {
                    SpiderExecutionResult.Failure(
                        SpiderFailure(SpiderFailureCode.POLICY_BLOCKED, "插件未列入仓库 fixture 白名单"),
                    )
                } else {
                    supervisor.execute(decoded.request, PluginExecutionBoundary(engine::execute))
                }
                deliver(
                    callback,
                    SpiderIpcCodec.encodeResult(decoded.request.requestId, result, policy),
                )
            }
            if (admission.attach(job)) {
                job.start()
            }
        }

        override fun cancel(requestId: String?) {
            requestId?.let { id ->
                if (launchAdmission.cancel(id)) supervisor.cancel(id)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        launchAdmission.close()
        supervisor.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun deliver(callback: ISpiderCallback, value: String) {
        try {
            callback.onResult(value)
        } catch (_: RemoteException) {
            // The host disappeared; the isolated task has no durable side effect to recover.
        }
    }
}
