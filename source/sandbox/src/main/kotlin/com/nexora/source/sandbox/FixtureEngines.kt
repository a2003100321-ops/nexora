package com.nexora.source.sandbox

import com.nexora.source.plugin.api.PluginEngineKind
import com.nexora.source.plugin.api.SpiderExecutionResult
import com.nexora.source.plugin.api.SpiderFailure
import com.nexora.source.plugin.api.SpiderFailureCode
import com.nexora.source.plugin.api.SpiderRequestEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal fun interface SandboxPluginEngine {
    suspend fun execute(request: SpiderRequestEnvelope): SpiderExecutionResult
}

internal object BuiltInJarFixtureAdapter : SandboxPluginEngine {
    override suspend fun execute(request: SpiderRequestEnvelope): SpiderExecutionResult =
        SpiderExecutionResult.Success(
            """{"fixture":"built-in-jar","operation":"${request.operation.name}"}""",
        )
}

internal object JavaScriptFixtureSubsetEngine : SandboxPluginEngine {
    private const val PREFIX = "nexora.result("

    override suspend fun execute(request: SpiderRequestEnvelope): SpiderExecutionResult {
        val script = request.payload.trim()
        if (!script.startsWith(PREFIX) || !script.endsWith(");")) {
            return rejected("JavaScript fixture 只允许 nexora.result(JSON);")
        }
        val body = script.substring(PREFIX.length, script.length - 2)
        return parseObject(body)
    }
}

internal object PythonFixtureSubsetEngine : SandboxPluginEngine {
    private const val PREFIX = "# nexora-result:"

    override suspend fun execute(request: SpiderRequestEnvelope): SpiderExecutionResult {
        val lines = request.payload.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.any { !it.trimStart().startsWith("#") }) {
            return rejected("Python fixture 只允许注释形式的 nexora-result JSON")
        }
        val resultLines = lines.map { it.trim() }.filter { it.startsWith(PREFIX) }
        if (resultLines.size != 1) {
            return rejected("Python fixture 必须包含一个 nexora-result")
        }
        return parseObject(resultLines.single().removePrefix(PREFIX).trim())
    }
}

internal object FixtureEngineRegistry {
    fun resolve(pluginId: String, kind: PluginEngineKind): SandboxPluginEngine? =
        when (pluginId to kind) {
            "fixture.jar" to PluginEngineKind.BUILT_IN_JAR_ADAPTER -> BuiltInJarFixtureAdapter
            "fixture.js" to PluginEngineKind.JAVASCRIPT_FIXTURE_SUBSET ->
                JavaScriptFixtureSubsetEngine
            "fixture.python" to PluginEngineKind.PYTHON_FIXTURE_SUBSET ->
                PythonFixtureSubsetEngine
            else -> null
        }
}

internal object SandboxCapabilityPolicy {
    const val NETWORK_ALLOWED: Boolean = false
    const val FILE_ACCESS_ALLOWED: Boolean = false
}

private fun parseObject(value: String): SpiderExecutionResult = try {
    val parsed = Json.parseToJsonElement(value)
    if (parsed !is JsonObject) rejected("fixture 结果必须是 JSON 对象")
    else SpiderExecutionResult.Success(parsed.toString())
} catch (_: IllegalArgumentException) {
    rejected("fixture 包含无效 JSON")
}

private fun rejected(message: String): SpiderExecutionResult = SpiderExecutionResult.Failure(
    SpiderFailure(SpiderFailureCode.POLICY_BLOCKED, message),
)
