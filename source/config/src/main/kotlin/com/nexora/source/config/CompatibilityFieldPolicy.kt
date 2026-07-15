package com.nexora.source.config

public enum class LegacyFieldPolicy {
    PARSE_AND_PRESERVE,
    PRESERVE_WITHOUT_EXECUTION,
}

public object CompatibilityFieldPolicies {
    public val live: LegacyFieldPolicy = LegacyFieldPolicy.PRESERVE_WITHOUT_EXECUTION
    public val notice: LegacyFieldPolicy = LegacyFieldPolicy.PRESERVE_WITHOUT_EXECUTION
    public val drm: LegacyFieldPolicy = LegacyFieldPolicy.PRESERVE_WITHOUT_EXECUTION
}
