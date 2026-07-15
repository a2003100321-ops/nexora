package com.nexora.source.sandbox

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearizedConnectionWaiterTest {
    @Test
    fun `success marked under lock resumes unconfined waiter only after action runs`() = runTest {
        val lock = Any()
        val waiter = LinearizedConnectionWaiter<String>()
        var resumed = false
        val consumer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            assertEquals("remote", waiter.await())
            assertFalse(Thread.holdsLock(lock))
            resumed = true
        }

        val completion = synchronized(lock) {
            assertNotNull(waiter.markSuccess("remote")).also {
                assertFalse(resumed)
            }
        }
        assertFalse(resumed)
        completion.invoke()
        consumer.join()
        assertTrue(resumed)
    }

    @Test
    fun `failure marked first prevents a later success`() = runTest {
        val waiter = LinearizedConnectionWaiter<String>()
        val failure = assertNotNull(waiter.markFailure(Disconnected()))
        assertNull(waiter.markSuccess("stale"))
        failure.invoke()

        assertFailsWith<Disconnected> { waiter.await() }
    }

    @Test
    fun `concurrent success and close choose exactly one completion`() = runTest {
        repeat(100) {
            val waiter = LinearizedConnectionWaiter<String>()
            val start = CountDownLatch(1)
            val success = async(Dispatchers.IO) {
                start.await()
                waiter.markSuccess("remote")
            }
            val failure = async(Dispatchers.IO) {
                start.await()
                waiter.markFailure(Disconnected())
            }
            start.countDown()
            val completions = listOfNotNull(success.await(), failure.await())
            assertEquals(1, completions.size)
            completions.single().invoke()
            val outcome = runCatching { waiter.await() }
            assertTrue(outcome.getOrNull() == "remote" || outcome.exceptionOrNull() is Disconnected)
        }
    }

    private class Disconnected : Exception()
}
