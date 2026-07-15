package com.nexora.source.runtime

import kotlin.test.Test
import kotlin.test.assertFalse

public class DynamicExecutionPolicyTest {
    @Test
    public fun unknownPluginsAndMainProcessDynamicCodeRemainDisabled(): Unit {
        val executionFlags = listOf(
            DynamicExecutionPolicy.EXECUTE_UNKNOWN_JAR,
            DynamicExecutionPolicy.EXECUTE_UNKNOWN_JAVASCRIPT,
            DynamicExecutionPolicy.EXECUTE_UNKNOWN_PYTHON,
            DynamicExecutionPolicy.ALLOW_MAIN_PROCESS_DYNAMIC_CODE,
        )

        executionFlags.forEach { enabled -> assertFalse(enabled) }
    }
}
