package com.nexora.source.plugin.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

public data class SpiderIpcRequest(
    val request: SpiderRequestEnvelope,
    val engineKind: PluginEngineKind,
)

/** Strict, size-bounded wire format shared by the host and isolated worker. */
public object SpiderIpcCodec {
    private val json = Json
    private val requestFields = setOf(
        "requestId",
        "sourceKey",
        "pluginId",
        "contractMajor",
        "contractMinor",
        "operation",
        "payload",
        "engineKind",
    )
    private val successFields = setOf("requestId", "status", "payload")
    private val failureFields = setOf("requestId", "status", "errorCode", "message")

    public fun encodeRequest(
        request: SpiderRequestEnvelope,
        engineKind: PluginEngineKind,
        policy: PluginResourcePolicy = PluginResourcePolicy(),
    ): Result<String> = captureCodecFailure {
        SpiderContractValidator.validate(request, policy)?.let { failure ->
            throw IllegalArgumentException(failure.code.name)
        }
        buildJsonObject {
            put("requestId", request.requestId)
            put("sourceKey", request.sourceKey)
            put("pluginId", request.pluginId)
            put("contractMajor", request.contractVersion.major)
            put("contractMinor", request.contractVersion.minor)
            put("operation", request.operation.name)
            put("payload", request.payload)
            put("engineKind", engineKind.name)
        }.toString().requireEnvelopeLimit(policy)
    }

    public fun decodeRequest(
        value: String,
        policy: PluginResourcePolicy = PluginResourcePolicy(),
    ): Result<SpiderIpcRequest> = captureCodecFailure {
        value.requireEnvelopeLimit(policy)
        val root = json.parseToJsonElement(value).jsonObject
        require(root.keys == requestFields) { "Unexpected request fields" }
        val request = SpiderRequestEnvelope(
            requestId = root.required("requestId"),
            sourceKey = root.required("sourceKey"),
            pluginId = root.required("pluginId"),
            contractVersion = SpiderContractVersion(
                root.getValue("contractMajor").jsonPrimitive.int,
                root.getValue("contractMinor").jsonPrimitive.int,
            ),
            operation = SpiderOperation.valueOf(root.required("operation")),
            payload = root.required("payload"),
        )
        SpiderContractValidator.validate(request, policy)?.let { failure ->
            throw IllegalArgumentException(failure.code.name)
        }
        SpiderIpcRequest(
            request = request,
            engineKind = PluginEngineKind.valueOf(root.required("engineKind")),
        )
    }

    public fun encodeResult(
        requestId: String,
        result: SpiderExecutionResult,
        policy: PluginResourcePolicy = PluginResourcePolicy(),
    ): String {
        val safeRequestId = requestId.takeIf(SpiderContractValidator::isValidIdentifier) ?: "invalid"
        val normalized = SpiderContractValidator.normalizeResult(result, policy)
        val encoded = buildJsonObject {
            put("requestId", safeRequestId)
            when (normalized) {
                is SpiderExecutionResult.Success -> {
                    put("status", "success")
                    put("payload", normalized.payload)
                }
                is SpiderExecutionResult.Failure -> {
                    put("status", "failure")
                    put("errorCode", normalized.failure.code.name)
                    put("message", normalized.failure.userMessage)
                }
            }
        }.toString()
        if (!encoded.utf8LengthExceeds(policy.maxEnvelopeBytes)) return encoded

        val boundedFailure = minimalFailure(
            safeRequestId,
            SpiderFailureCode.RESOURCE_LIMIT,
            "插件结果超过 IPC 安全上限",
        )
        if (!boundedFailure.utf8LengthExceeds(policy.maxEnvelopeBytes)) return boundedFailure

        return minimalFailure(
            requestId = "invalid",
            code = SpiderFailureCode.RESOURCE_LIMIT,
            message = "IPC result exceeds limit",
        )
    }

    public fun decodeResult(
        value: String,
        expectedRequestId: String,
        policy: PluginResourcePolicy = PluginResourcePolicy(),
    ): Result<SpiderExecutionResult> = captureCodecFailure {
        value.requireEnvelopeLimit(policy)
        val root = json.parseToJsonElement(value).jsonObject
        require(root.required("requestId") == expectedRequestId) { "Mismatched request identifier" }
        val result = when (root.required("status")) {
            "success" -> {
                require(root.keys == successFields) { "Unexpected success fields" }
                SpiderExecutionResult.Success(root.required("payload"))
            }
            "failure" -> {
                require(root.keys == failureFields) { "Unexpected failure fields" }
                SpiderExecutionResult.Failure(
                    SpiderFailure(
                        code = SpiderFailureCode.valueOf(root.required("errorCode")),
                        userMessage = root.required("message"),
                    ),
                )
            }
            else -> error("Unknown response status")
        }
        SpiderContractValidator.normalizeResult(result, policy)
    }

    public fun invalidRequest(
        message: String = "插件请求格式无效",
        policy: PluginResourcePolicy = PluginResourcePolicy(),
    ): String = encodeResult(
        requestId = "invalid",
        result = SpiderExecutionResult.Failure(
            SpiderFailure(SpiderFailureCode.INVALID_INPUT, message),
        ),
        policy = policy,
    )

    private fun minimalFailure(
        requestId: String,
        code: SpiderFailureCode,
        message: String,
    ): String = buildJsonObject {
        put("requestId", requestId)
        put("status", "failure")
        put("errorCode", code.name)
        put("message", message)
    }.toString()

    private fun String.requireEnvelopeLimit(policy: PluginResourcePolicy): String {
        require(!utf8LengthExceeds(policy.maxEnvelopeBytes)) { "IPC envelope exceeds limit" }
        return this
    }

    private fun Map<String, JsonElement>.required(name: String): String {
        val primitive = getValue(name).jsonPrimitive
        require(primitive.isString) { "$name must be a string" }
        return primitive.content
    }

    private inline fun <T> captureCodecFailure(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: Exception) {
        Result.failure(error)
    }
}
