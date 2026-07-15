package com.nexora.core.network

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class NetworkPolicyTest {
    @Test
    public fun defaultPolicyRequiresValidatedTlsAndRejectsCleartext(): Unit {
        val policy = NetworkPolicy()

        assertTrue(policy.requireTlsValidation)
        assertFalse(policy.allowCleartext)
    }

    @Test
    public fun disabledTlsValidationIsRejected(): Unit {
        assertFailsWith<IllegalArgumentException> {
            NetworkPolicy(requireTlsValidation = false)
        }
    }

    @Test
    public fun cleartextTransportIsRejected(): Unit {
        assertFailsWith<IllegalArgumentException> {
            NetworkPolicy(allowCleartext = true)
        }
    }
}
