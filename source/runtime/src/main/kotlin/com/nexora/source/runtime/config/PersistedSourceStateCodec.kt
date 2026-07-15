package com.nexora.source.runtime.config

import com.nexora.source.api.ConfigImportKind
import com.nexora.source.api.CompatibilityDiagnostic
import com.nexora.source.api.CompatibilityIssueCode
import com.nexora.source.api.CompatibilitySeverity
import com.nexora.source.api.LegacyConfigId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object PersistedSourceStateCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_CONFIGURATIONS = 100
    private const val MAX_DISABLED_SOURCES = 10_000

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowTrailingComma = false
    }

    fun encode(state: PersistedSourceState): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(SCHEMA_VERSION))
            put("onboardingCompleted", JsonPrimitive(state.onboardingCompleted))
            put("configurations", buildJsonArray {
                state.configurations.forEach { configuration -> add(configuration.toJson()) }
            })
        },
    )

    fun decode(text: String): PersistedSourceState {
        val root = json.parseToJsonElement(text).jsonObject
        val version = root.requiredPrimitive("schemaVersion").content.toIntOrNull()
        require(version == SCHEMA_VERSION) { "Unsupported source store schema version" }
        val onboardingCompleted = root.requiredPrimitive("onboardingCompleted").booleanOrNull
            ?: error("onboardingCompleted must be a boolean")
        val configurations = root.required("configurations").jsonArray
        require(configurations.size <= MAX_CONFIGURATIONS) { "Too many stored configurations" }
        return PersistedSourceState(
            onboardingCompleted = onboardingCompleted,
            configurations = configurations.mapIndexed { index, element ->
                element.jsonObject.toConfiguration(index)
            },
        )
    }

    private fun PersistedConfiguration.toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id.value))
        put("displayName", JsonPrimitive(displayName))
        put("importKind", JsonPrimitive(importKind.name))
        put("origin", origin?.let(::JsonPrimitive) ?: JsonNull)
        put("originDisplay", originDisplay?.let(::JsonPrimitive) ?: JsonNull)
        put("originalJson", JsonPrimitive(originalJson))
        put("expandedJson", JsonPrimitive(expandedJson))
        put("disabledLegacyKeys", buildJsonArray {
            disabledLegacyKeys.sorted().forEach { key ->
                add(JsonPrimitive(key))
            }
        })
        put("diagnostics", buildJsonArray {
            diagnostics.forEach { diagnostic ->
                add(buildJsonObject {
                    put("code", JsonPrimitive(diagnostic.code.name))
                    put("severity", JsonPrimitive(diagnostic.severity.name))
                    put("jsonPath", JsonPrimitive(diagnostic.jsonPath))
                    put("userMessage", JsonPrimitive(diagnostic.userMessage))
                    put("recoverable", JsonPrimitive(diagnostic.recoverable))
                })
            }
        })
    }

    private fun JsonObject.toConfiguration(index: Int): PersistedConfiguration {
        val prefix = "configurations[$index]"
        val disabled = required("disabledLegacyKeys").jsonArray
        val diagnostics = required("diagnostics").jsonArray
        require(disabled.size <= MAX_DISABLED_SOURCES) { "$prefix has too many disabled sources" }
        val importKindName = requiredPrimitive("importKind").content
        val importKind = ConfigImportKind.entries.firstOrNull { it.name == importKindName }
            ?: error("$prefix has unsupported importKind")
        return PersistedConfiguration(
            id = LegacyConfigId(requiredPrimitive("id").content),
            displayName = requiredPrimitive("displayName").content,
            importKind = importKind,
            origin = nullableString("origin"),
            originDisplay = nullableString("originDisplay"),
            originalJson = requiredPrimitive("originalJson").content,
            expandedJson = requiredPrimitive("expandedJson").content,
            disabledLegacyKeys = disabled.map { value -> value.jsonPrimitive.content }.toSet(),
            diagnostics = diagnostics.mapIndexed { diagnosticIndex, value ->
                val item = value.jsonObject
                val codeName = item.requiredPrimitive("code").content
                val severityName = item.requiredPrimitive("severity").content
                CompatibilityDiagnostic(
                    code = CompatibilityIssueCode.entries.firstOrNull { it.name == codeName }
                        ?: error("$prefix diagnostics[$diagnosticIndex] has unsupported code"),
                    severity = CompatibilitySeverity.entries.firstOrNull { it.name == severityName }
                        ?: error("$prefix diagnostics[$diagnosticIndex] has unsupported severity"),
                    jsonPath = item.requiredPrimitive("jsonPath").content,
                    userMessage = item.requiredPrimitive("userMessage").content,
                    recoverable = item.requiredPrimitive("recoverable").booleanOrNull
                        ?: error("$prefix diagnostics[$diagnosticIndex] has invalid recoverable"),
                )
            },
        )
    }

    private fun JsonObject.required(key: String): JsonElement =
        get(key) ?: error("Stored source state is missing $key")

    private fun JsonObject.requiredPrimitive(key: String): JsonPrimitive = required(key).jsonPrimitive

    private fun JsonObject.nullableString(key: String): String? = when (val value = required(key)) {
        JsonNull -> null
        else -> value.jsonPrimitive.content
    }
}
