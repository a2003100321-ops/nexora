package com.nexora.source.plugin.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginProcessStateMachineTest {
    @Test
    fun `old death and new completion remain isolated under concurrent delivery`() = runTest {
        repeat(100) { index ->
            val state = PluginProcessStateMachine(maxPendingRequests = 2)
            val old = state.accept("old-$index")
            val current = state.accept("current-$index")
            assertTrue(state.attachToGeneration(old, generation = 20))
            assertTrue(state.attachToGeneration(current, generation = 21))

            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { state.binderDied(generation = 20) },
                    async(Dispatchers.Default) {
                        state.complete(
                            current,
                            generation = 21,
                            SpiderExecutionResult.Success("ok"),
                        )
                    },
                ).awaitAll()
            }

            assertCrashed(old.result.await())
            assertEquals(
                "ok",
                assertIs<SpiderExecutionResult.Success>(current.result.await()).payload,
            )
        }
    }

    @Test
    fun `old binder death fails only its generation while a new process stays healthy`() = runTest {
        val state = PluginProcessStateMachine(maxPendingRequests = 3)
        val old = state.accept("old-request")
        assertTrue(state.attachToGeneration(old, generation = 1))

        // A replacement Binder can admit work before the old death callback finishes outside the
        // connection lock. The generation carried by the callback is the isolation boundary.
        val replacement = state.accept("replacement-request")
        assertTrue(state.attachToGeneration(replacement, generation = 2))

        assertEquals(1, state.binderDied(generation = 1))
        assertCrashed(old.result.await())
        assertFalse(replacement.result.isCompleted)
        assertTrue(
            state.complete(
                replacement,
                generation = 2,
                SpiderExecutionResult.Success("healthy"),
            ),
        )
        assertEquals(
            "healthy",
            assertIs<SpiderExecutionResult.Success>(replacement.result.await()).payload,
        )
    }

    @Test
    fun `old exact cancel callback and death cannot affect reused logical request id`() = runTest {
        val state = PluginProcessStateMachine(maxPendingRequests = 1)
        val first = state.accept("reused-request")
        assertTrue(state.attachToGeneration(first, generation = 10))
        assertTrue(
            state.complete(
                first,
                generation = 10,
                SpiderExecutionResult.Success("first"),
            ),
        )
        assertEquals("first", assertIs<SpiderExecutionResult.Success>(first.result.await()).payload)

        val current = state.accept("reused-request")
        assertFalse(first.wireRequestId == current.wireRequestId)
        assertTrue(state.attachToGeneration(current, generation = 11))
        assertFalse(state.cancel(first).accepted)
        assertFalse(
            state.complete(
                first,
                generation = 10,
                SpiderExecutionResult.Success("stale"),
            ),
        )
        assertEquals(0, state.binderDied(generation = 10))
        assertFalse(current.result.isCompleted)
        assertTrue(
            state.complete(
                current,
                generation = 11,
                SpiderExecutionResult.Success("current"),
            ),
        )
        assertEquals("current", assertIs<SpiderExecutionResult.Success>(current.result.await()).payload)
    }

    @Test
    fun `remote exception is attributed to one request in the matching generation`() = runTest {
        val state = PluginProcessStateMachine(maxPendingRequests = 2)
        val failed = state.accept("failed-request")
        val unaffected = state.accept("unaffected-request")
        assertTrue(state.attachToGeneration(failed, generation = 3))
        assertTrue(state.attachToGeneration(unaffected, generation = 3))

        assertFalse(state.remoteException(failed, generation = 2))
        assertTrue(state.remoteException(failed, generation = 3))
        assertCrashed(failed.result.await())
        assertFalse(unaffected.result.isCompleted)
        assertTrue(
            state.complete(
                unaffected,
                generation = 3,
                SpiderExecutionResult.Success("ok"),
            ),
        )
        assertEquals("ok", assertIs<SpiderExecutionResult.Success>(unaffected.result.await()).payload)
    }

    @Test
    fun `host admission is bounded and exact handle cancellation releases its slot`() = runTest {
        val state = PluginProcessStateMachine(maxPendingRequests = 1)
        val pending = state.accept("request-1")
        val busy = assertIs<PluginRequestRegistration.Rejected>(state.register("request-2"))
        assertEquals(SpiderFailureCode.RESOURCE_LIMIT, busy.failure.code)

        assertTrue(state.cancel(pending).accepted)
        assertEquals(
            SpiderFailureCode.CANCELLED,
            assertIs<SpiderExecutionResult.Failure>(pending.result.await()).failure.code,
        )

        val later = state.accept("request-2")
        assertTrue(state.attachToGeneration(later, generation = 7))
        assertTrue(
            state.complete(
                later,
                generation = 7,
                SpiderExecutionResult.Success("not-poisoned"),
            ),
        )
        assertEquals(
            "not-poisoned",
            assertIs<SpiderExecutionResult.Success>(later.result.await()).payload,
        )
    }

    @Test
    fun `duplicates attachment cancellation and close have deterministic outcomes`() = runTest {
        val state = PluginProcessStateMachine(maxPendingRequests = 2)
        val pending = state.accept("request-1")
        val duplicate = assertIs<PluginRequestRegistration.Rejected>(state.register("request-1"))
        assertEquals(SpiderFailureCode.INVALID_INPUT, duplicate.failure.code)
        assertTrue(state.attachToGeneration(pending, generation = 1))
        assertFalse(state.attachToGeneration(pending, generation = 2))
        assertTrue(state.cancel(pending).accepted)
        assertEquals(
            SpiderFailureCode.CANCELLED,
            assertIs<SpiderExecutionResult.Failure>(pending.result.await()).failure.code,
        )

        val closing = state.accept("request-2")
        state.close()
        assertCrashed(closing.result.await())
        val closed = assertIs<PluginRequestRegistration.Rejected>(state.register("request-3"))
        assertEquals(SpiderFailureCode.PLUGIN_CRASHED, closed.failure.code)
        assertFalse(state.cancel(closing).accepted)
    }

    private fun PluginProcessStateMachine.accept(requestId: String): PluginPendingRequestHandle =
        assertIs<PluginRequestRegistration.Accepted>(register(requestId)).handle

    private fun assertCrashed(result: SpiderExecutionResult) {
        assertEquals(
            SpiderFailureCode.PLUGIN_CRASHED,
            assertIs<SpiderExecutionResult.Failure>(result).failure.code,
        )
    }
}
