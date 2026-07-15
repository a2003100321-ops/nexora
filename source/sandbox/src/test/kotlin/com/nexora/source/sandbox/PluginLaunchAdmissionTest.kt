package com.nexora.source.sandbox

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginLaunchAdmissionTest {
    @Test
    fun `service admission is bounded before jobs launch`() {
        PluginLaunchAdmission(maxConcurrentTasks = 1).use { admission ->
            val first = assertIs<PluginLaunchAdmission.Result.Accepted>(
                admission.tryAdmit("first"),
            )
            val rejected = assertIs<PluginLaunchAdmission.Result.Rejected>(
                admission.tryAdmit("second"),
            )
            val duplicate = assertIs<PluginLaunchAdmission.Result.Rejected>(
                admission.tryAdmit("first"),
            )
            assertEquals(
                PluginLaunchAdmission.AdmissionRejection.RESOURCE_LIMIT,
                rejected.reason,
            )
            assertEquals(
                PluginLaunchAdmission.AdmissionRejection.DUPLICATE_ID,
                duplicate.reason,
            )
            assertEquals(1, admission.activeCount())
            first.lease.close()
            assertEquals(0, admission.activeCount())
        }
    }

    @Test
    fun `cancel before lazy job attachment is retained by exact lease`() = runTest {
        PluginLaunchAdmission(maxConcurrentTasks = 1).use { admission ->
            val lease = assertIs<PluginLaunchAdmission.Result.Accepted>(
                admission.tryAdmit("raced"),
            ).lease
            assertTrue(admission.cancel("raced"))
            var executed = false
            val job = launch(start = CoroutineStart.LAZY) { executed = true }

            assertFalse(lease.attach(job))
            job.start()
            job.join()
            assertFalse(executed)
            assertEquals(0, admission.activeCount())
        }
    }

    @Test
    fun `unknown cancel is not retained for later id reuse`() = runTest {
        PluginLaunchAdmission(maxConcurrentTasks = 1).use { admission ->
            assertFalse(admission.cancel("reused"))
            val lease = assertIs<PluginLaunchAdmission.Result.Accepted>(
                admission.tryAdmit("reused"),
            ).lease
            var executed = false
            val job = launch(start = CoroutineStart.LAZY) { executed = true }

            assertTrue(lease.attach(job))
            job.start()
            job.join()
            assertTrue(executed)
            assertEquals(0, admission.activeCount())

            val reused = assertIs<PluginLaunchAdmission.Result.Accepted>(
                admission.tryAdmit("reused"),
            )
            reused.lease.close()
        }
    }

    @Test
    fun `close before job attachment cancels the unstarted job and releases lease`() = runTest {
        val admission = PluginLaunchAdmission(maxConcurrentTasks = 1)
        val lease = assertIs<PluginLaunchAdmission.Result.Accepted>(
            admission.tryAdmit("closing"),
        ).lease
        admission.close()
        var executed = false
        val job = launch(start = CoroutineStart.LAZY) { executed = true }

        assertFalse(lease.attach(job))
        job.start()
        job.join()
        assertFalse(executed)
        assertEquals(0, admission.activeCount())
        assertEquals(
            PluginLaunchAdmission.AdmissionRejection.CLOSED,
            assertIs<PluginLaunchAdmission.Result.Rejected>(
                admission.tryAdmit("after-close"),
            ).reason,
        )
    }

    @Test
    fun `cancel after attachment but before start prevents execution and auto releases`() = runTest {
        PluginLaunchAdmission(maxConcurrentTasks = 1).use { admission ->
            val lease = assertIs<PluginLaunchAdmission.Result.Accepted>(
                admission.tryAdmit("attached"),
            ).lease
            var executed = false
            val job = launch(start = CoroutineStart.LAZY) { executed = true }

            assertTrue(lease.attach(job))
            assertTrue(admission.cancel("attached"))
            job.start()
            job.join()
            assertFalse(executed)
            assertEquals(0, admission.activeCount())
        }
    }
}
