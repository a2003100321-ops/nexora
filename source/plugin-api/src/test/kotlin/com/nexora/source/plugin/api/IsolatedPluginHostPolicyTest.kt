package com.nexora.source.plugin.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IsolatedPluginHostPolicyTest {
    @Test
    fun `default resource policy is the supported single in-flight host policy`() {
        val policy = PluginResourcePolicy()

        assertEquals(1, policy.maxConcurrentTasks)
        IsolatedPluginHostPolicy.requireSupported(policy)
    }

    @Test
    fun `host rejects a policy that claims unsupported participant concurrency`() {
        val error = assertFailsWith<IllegalArgumentException> {
            IsolatedPluginHostPolicy.requireSupported(
                PluginResourcePolicy(maxConcurrentTasks = 2),
            )
        }

        assertContains(error.message.orEmpty(), "maxConcurrentTasks=1")
        assertContains(error.message.orEmpty(), "取消隔离尚未实现")
    }
}
