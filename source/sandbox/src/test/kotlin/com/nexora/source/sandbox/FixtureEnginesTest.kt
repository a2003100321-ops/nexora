package com.nexora.source.sandbox

import com.nexora.source.plugin.api.PluginEngineKind
import com.nexora.source.plugin.api.SpiderContractVersion
import com.nexora.source.plugin.api.SpiderExecutionResult
import com.nexora.source.plugin.api.SpiderOperation
import com.nexora.source.plugin.api.SpiderRequestEnvelope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FixtureEnginesTest {
    @Test
    fun `built in jar adapter is explicit and deterministic`() = runTest {
        val engine = assertNotNull(
            FixtureEngineRegistry.resolve("fixture.jar", PluginEngineKind.BUILT_IN_JAR_ADAPTER),
        )
        val result = assertIs<SpiderExecutionResult.Success>(engine.execute(request("{}")))
        assertContains(result.payload, "built-in-jar")
    }

    @Test
    fun `javascript subset accepts only fixed result grammar`() = runTest {
        val accepted = JavaScriptFixtureSubsetEngine.execute(
            request("nexora.result({\"title\":\"safe\"});"),
        )
        assertIs<SpiderExecutionResult.Success>(accepted)
        assertIs<SpiderExecutionResult.Failure>(
            JavaScriptFixtureSubsetEngine.execute(request("eval('danger')")),
        )
        assertIs<SpiderExecutionResult.Failure>(
            JavaScriptFixtureSubsetEngine.execute(request("nexora.result(fetch('x'));")),
        )
    }

    @Test
    fun `python subset accepts result comment and rejects executable lines`() = runTest {
        assertIs<SpiderExecutionResult.Success>(
            PythonFixtureSubsetEngine.execute(request("# nexora-result: {\"title\":\"safe\"}")),
        )
        assertIs<SpiderExecutionResult.Failure>(
            PythonFixtureSubsetEngine.execute(
                request("import os\n# nexora-result: {\"title\":\"unsafe\"}"),
            ),
        )
    }

    @Test
    fun `network and file capabilities are deny all`() {
        assertFalse(SandboxCapabilityPolicy.NETWORK_ALLOWED)
        assertFalse(SandboxCapabilityPolicy.FILE_ACCESS_ALLOWED)
    }

    private fun request(payload: String) = SpiderRequestEnvelope(
        requestId = "request-1",
        sourceKey = "source-1",
        pluginId = "fixture",
        contractVersion = SpiderContractVersion(1, 0),
        operation = SpiderOperation.SEARCH,
        payload = payload,
    )
}
