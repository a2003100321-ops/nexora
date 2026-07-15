package com.nexora.source.config

import kotlin.test.Test
import kotlin.test.assertEquals

public class CompatibilityFieldPolicyTest {
    @Test
    public fun liveNoticeAndDrmArePreservedWithoutExecution(): Unit {
        val disabledFields = listOf(
            CompatibilityFieldPolicies.live,
            CompatibilityFieldPolicies.notice,
            CompatibilityFieldPolicies.drm,
        )

        disabledFields.forEach { policy ->
            assertEquals(LegacyFieldPolicy.PRESERVE_WITHOUT_EXECUTION, policy)
        }
    }
}
