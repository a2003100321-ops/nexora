package com.nexora.source.api

@JvmInline
public value class LegacyConfigId(public val value: String)

@JvmInline
public value class LegacySourceKey(public val value: String)

@JvmInline
public value class RawJson(public val canonicalText: String)

public enum class ConfigImportKind {
    REMOTE_URL,
    PASTED_TEXT,
    LOCAL_FILE,
}

public enum class SourceSearchCapability {
    SUPPORTED,
    UNSUPPORTED,
}

public enum class SourceUserActivation {
    ENABLED,
    DISABLED,
}

public enum class SourceAvailability {
    AVAILABLE,
    LOAD_FAILED,
    TEMPORARILY_UNAVAILABLE,
}

public data class SourceOperationalState(
    val searchCapability: SourceSearchCapability,
    val userActivation: SourceUserActivation = SourceUserActivation.ENABLED,
    val availability: SourceAvailability = SourceAvailability.AVAILABLE,
)

public enum class CompatibilitySeverity {
    INFO,
    WARNING,
    ERROR,
}

public enum class CompatibilityIssueCode {
    INPUT_TOO_LARGE,
    INVALID_UTF8,
    INVALID_JSON,
    INVALID_BASE64,
    INVALID_2423_AES,
    INVALID_URL,
    REMOTE_LOAD_FAILED,
    REMOTE_REFERENCE_FAILED,
    SITE_INVALID,
    PARSE_INVALID,
    FIELD_TYPE_MISMATCH,
    UNSUPPORTED_SOURCE_TYPE,
    POLICY_PRESERVED_NOT_EXECUTED,
    RESOURCE_LIMIT,
    STORAGE_FAILURE,
}

public data class CompatibilityDiagnostic(
    val code: CompatibilityIssueCode,
    val severity: CompatibilitySeverity,
    val jsonPath: String,
    val userMessage: String,
    val recoverable: Boolean,
)

public data class LegacySiteDescriptor(
    val sourceKey: LegacySourceKey,
    val legacyKey: String,
    val name: String,
    val type: Int,
    val api: String,
    val ext: String,
    val jar: String,
    val playUrl: String,
    val timeoutMillis: Long,
    val quickSearch: Boolean,
    val categories: List<String>,
    val requestHeaders: Map<String, String>,
    val state: SourceOperationalState,
    val raw: RawJson,
)

public data class LegacyParseDescriptor(
    val name: String,
    val type: Int,
    val url: String,
    val ext: RawJson?,
    val raw: RawJson,
)

public data class LegacyConfigFields(
    val spider: String?,
    val sites: List<LegacySiteDescriptor>,
    val parses: List<LegacyParseDescriptor>,
    val rules: List<RawJson>,
    val headers: List<RawJson>,
    val hosts: List<String>,
    val flags: List<String>,
    val danmaku: String?,
    val doh: List<RawJson>,
    val proxy: List<RawJson>,
    val ads: List<String>,
    val wallpaper: String?,
    val logo: String?,
    val notice: RawJson?,
    val lives: List<RawJson>,
)

public data class LegacyConfigSnapshot(
    val id: LegacyConfigId,
    val displayName: String,
    val importKind: ConfigImportKind,
    val origin: String?,
    val originDisplay: String?,
    val original: RawJson,
    val expanded: RawJson,
    val fields: LegacyConfigFields,
    val unknownTopLevelFields: Map<String, RawJson>,
    val diagnostics: List<CompatibilityDiagnostic>,
)
