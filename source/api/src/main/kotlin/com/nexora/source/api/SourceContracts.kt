package com.nexora.source.api

public enum class LegacyPayloadKind {
    CONFIG,
    JAR,
    JAVASCRIPT,
    PYTHON,
}

public enum class ActivationState {
    ACTIVE,
    DISABLED_BY_POLICY,
}

public data class LegacyPayloadDescriptor(
    val kind: LegacyPayloadKind,
    val activation: ActivationState,
)
