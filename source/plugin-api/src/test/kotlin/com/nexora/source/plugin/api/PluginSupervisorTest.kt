package com.nexora.source.plugin.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginSupervisorTest {
    @Test
    fun `rejects incompatible major version`() = runTest {
        PluginSupervisor(dispatcher = StandardTestDispatcher(testScheduler)).use { supervisor ->
            val result = supervisor.execute(request(version = SpiderContractVersion(2, 0))) {
                SpiderExecutionResult.Success("never")
            }
            assertEquals(
                SpiderFailureCode.CONTRACT_MISMATCH,
                assertIs<SpiderExecutionResult.Failure>(result).failure.code,
            )
        }
    }

    @Test
    fun `rejects payload over configured limit`() = runTest {
        val policy = PluginResourcePolicy(
            maxEnvelopeBytes = 256,
            maxFieldChars = 8,
            maxResultBytes = 256,
        )
        PluginSupervisor(policy, StandardTestDispatcher(testScheduler)).use { supervisor ->
            val result = supervisor.execute(request(payload = "123456789")) {
                SpiderExecutionResult.Success("never")
            }
            assertEquals(
                SpiderFailureCode.RESOURCE_LIMIT,
                assertIs<SpiderExecutionResult.Failure>(result).failure.code,
            )
        }
    }

    @Test
    fun `timeout and crash are contained and later request succeeds`() = runTest {
        val policy = PluginResourcePolicy(timeoutMillis = 100)
        PluginSupervisor(policy, StandardTestDispatcher(testScheduler)).use { supervisor ->
            val timeout = supervisor.execute(request("timeout")) {
                delay(1_000)
                SpiderExecutionResult.Success("late")
            }
            assertEquals(
                SpiderFailureCode.TIMEOUT,
                assertIs<SpiderExecutionResult.Failure>(timeout).failure.code,
            )
            val crash = supervisor.execute(request("crash")) { error("fixture crash") }
            assertEquals(
                SpiderFailureCode.PLUGIN_CRASHED,
                assertIs<SpiderExecutionResult.Failure>(crash).failure.code,
            )
            assertEquals(
                "ok",
                assertIs<SpiderExecutionResult.Success>(
                    supervisor.execute(request("healthy")) { SpiderExecutionResult.Success("ok") },
                ).payload,
            )
        }
    }

    @Test
    fun `cancel and concurrency limit are enforced`() = runTest {
        PluginSupervisor(dispatcher = StandardTestDispatcher(testScheduler)).use { supervisor ->
            val entered = CompletableDeferred<Unit>()
            val first = async {
                supervisor.execute(request("first")) {
                    entered.complete(Unit)
                    delay(60_000)
                    SpiderExecutionResult.Success("late")
                }
            }
            entered.await()
            val busy = supervisor.execute(request("second")) { SpiderExecutionResult.Success("no") }
            assertEquals(
                SpiderFailureCode.RESOURCE_LIMIT,
                assertIs<SpiderExecutionResult.Failure>(busy).failure.code,
            )
            assertTrue(supervisor.cancel("first"))
            assertEquals(
                SpiderFailureCode.CANCELLED,
                assertIs<SpiderExecutionResult.Failure>(first.await()).failure.code,
            )
        }
    }

    @Test
    fun `unknown late cancel cannot poison a later reused request id`() = runTest {
        PluginSupervisor(dispatcher = StandardTestDispatcher(testScheduler)).use { supervisor ->
            assertFalse(supervisor.cancel("not-registered-yet"))
            val result = supervisor.execute(request("not-registered-yet")) {
                SpiderExecutionResult.Success("healthy")
            }
            assertEquals("healthy", assertIs<SpiderExecutionResult.Success>(result).payload)
        }
    }

    @Test
    fun `caller cancellation joins worker before returning`() = runTest {
        PluginSupervisor(dispatcher = StandardTestDispatcher(testScheduler)).use { supervisor ->
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val caller = launch {
                supervisor.execute(request("caller-cancelled")) {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
            entered.await()
            caller.cancelAndJoin()
            assertTrue(stopped.isCompleted)

            val healthy = supervisor.execute(request("after-cancellation")) {
                SpiderExecutionResult.Success("ok")
            }
            assertEquals("ok", assertIs<SpiderExecutionResult.Success>(healthy).payload)
        }
    }

    @Test
    fun `oversized UTF-8 output is converted to a resource failure`() = runTest {
        val policy = PluginResourcePolicy(maxResultBytes = 8)
        PluginSupervisor(policy, StandardTestDispatcher(testScheduler)).use { supervisor ->
            val result = supervisor.execute(request("utf8-output")) {
                SpiderExecutionResult.Success("中文中文")
            }
            assertEquals(
                SpiderFailureCode.RESOURCE_LIMIT,
                assertIs<SpiderExecutionResult.Failure>(result).failure.code,
            )
        }
    }

    @Test
    fun `fatal JVM error is not translated or swallowed`() = runTest {
        PluginSupervisor(dispatcher = StandardTestDispatcher(testScheduler)).use { supervisor ->
            assertFailsWith<LinkageError> {
                supervisor.execute(request("fatal")) { throw LinkageError("fatal fixture") }
            }
            val healthy = supervisor.execute(request("after-fatal")) {
                SpiderExecutionResult.Success("ok")
            }
            assertEquals("ok", assertIs<SpiderExecutionResult.Success>(healthy).payload)
        }
    }

    private fun request(
        id: String = "request-1",
        version: SpiderContractVersion = SpiderV1.VERSION,
        payload: String = "{}",
    ) = SpiderRequestEnvelope(
        requestId = id,
        sourceKey = "source-1",
        pluginId = "fixture.jar",
        contractVersion = version,
        operation = SpiderOperation.SEARCH,
        payload = payload,
    )
}
