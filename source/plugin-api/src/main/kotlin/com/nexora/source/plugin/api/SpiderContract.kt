package com.nexora.source.plugin.api

public data class SpiderContractVersion(
    val major: Int,
    val minor: Int,
) {
    init {
        require(major >= 0 && minor >= 0) { "Contract versions cannot be negative." }
    }
}

public object SpiderV1 {
    public val VERSION: SpiderContractVersion = SpiderContractVersion(major = 1, minor = 0)
}

public enum class PluginEngineKind {
    BUILT_IN_JAR_ADAPTER,
    JAVASCRIPT_FIXTURE_SUBSET,
    PYTHON_FIXTURE_SUBSET,
}

public enum class SpiderOperation {
    INITIALIZE,
    HOME,
    HOME_VIDEO,
    CATEGORY,
    DETAIL,
    SEARCH,
    PLAYBACK_RESOLVE,
    DESTROY,
}

public enum class SpiderFailureCode {
    CONTRACT_MISMATCH,
    INVALID_INPUT,
    UNSUPPORTED_OPERATION,
    POLICY_BLOCKED,
    TIMEOUT,
    CANCELLED,
    PLUGIN_CRASHED,
    RESOURCE_LIMIT,
    BAD_RESPONSE,
}

public data class SpiderFailure(
    val code: SpiderFailureCode,
    val userMessage: String,
)

public data class PluginResourcePolicy(
    val maxEnvelopeBytes: Int = 64 * 1024,
    val maxFieldChars: Int = 4 * 1024,
    val maxResultBytes: Int = 64 * 1024,
    val timeoutMillis: Long = 10_000,
    val maxConcurrentTasks: Int = 1,
) {
    init {
        require(maxEnvelopeBytes in 256..(512 * 1024))
        require(maxFieldChars in 1..maxEnvelopeBytes)
        require(maxResultBytes in 1..maxEnvelopeBytes)
        require(timeoutMillis in 1..60_000)
        require(maxConcurrentTasks in 1..4)
    }
}

public data class SpiderPluginDescriptor(
    val pluginId: String,
    val engineKind: PluginEngineKind,
    val contractVersion: SpiderContractVersion = SpiderV1.VERSION,
    val syntheticOnly: Boolean = true,
)

public data class SpiderRequestEnvelope(
    val requestId: String,
    val sourceKey: String,
    val pluginId: String,
    val contractVersion: SpiderContractVersion,
    val operation: SpiderOperation,
    val payload: String,
)

public sealed interface SpiderExecutionResult {
    public data class Success(val payload: String) : SpiderExecutionResult
    public data class Failure(val failure: SpiderFailure) : SpiderExecutionResult
}

public object SpiderContractValidator {
    private val identifier = Regex("[A-Za-z0-9._:-]{1,128}")

    public fun isValidIdentifier(value: String): Boolean = identifier.matches(value)

    public fun validate(
        request: SpiderRequestEnvelope,
        policy: PluginResourcePolicy,
    ): SpiderFailure? {
        if (request.contractVersion.major != SpiderV1.VERSION.major) {
            return SpiderFailure(
                SpiderFailureCode.CONTRACT_MISMATCH,
                "插件协议版本不受支持",
            )
        }
        if (!isValidIdentifier(request.requestId) ||
            !isValidIdentifier(request.sourceKey) ||
            !isValidIdentifier(request.pluginId)
        ) {
            return SpiderFailure(SpiderFailureCode.INVALID_INPUT, "插件请求标识无效")
        }
        if (request.payload.length > policy.maxFieldChars ||
            request.payload.toByteArray(Charsets.UTF_8).size > policy.maxEnvelopeBytes
        ) {
            return SpiderFailure(SpiderFailureCode.RESOURCE_LIMIT, "插件请求超过安全上限")
        }
        return null
    }

    public fun normalizeResult(
        result: SpiderExecutionResult,
        policy: PluginResourcePolicy,
    ): SpiderExecutionResult = when (result) {
        is SpiderExecutionResult.Success -> {
            if (result.payload.utf8LengthExceeds(policy.maxResultBytes)) {
                SpiderExecutionResult.Failure(
                    SpiderFailure(
                        SpiderFailureCode.RESOURCE_LIMIT,
                        "插件结果超过安全上限",
                    ),
                )
            } else {
                result
            }
        }
        is SpiderExecutionResult.Failure -> {
            if (result.failure.userMessage.length > policy.maxFieldChars ||
                result.failure.userMessage.utf8LengthExceeds(policy.maxEnvelopeBytes)
            ) {
                SpiderExecutionResult.Failure(
                    SpiderFailure(
                        SpiderFailureCode.BAD_RESPONSE,
                        "插件返回了无效错误信息",
                    ),
                )
            } else {
                result
            }
        }
    }
}

internal fun String.utf8LengthExceeds(limit: Int): Boolean {
    var bytes = 0
    var index = 0
    while (index < length) {
        val character = this[index]
        bytes += when {
            character.code <= 0x7f -> 1
            character.code <= 0x7ff -> 2
            character.isHighSurrogate() &&
                index + 1 < length &&
                this[index + 1].isLowSurrogate() -> {
                index += 1
                4
            }
            else -> 3
        }
        if (bytes > limit) return true
        index += 1
    }
    return false
}

public fun interface PluginExecutionBoundary {
    public suspend fun execute(request: SpiderRequestEnvelope): SpiderExecutionResult
}
