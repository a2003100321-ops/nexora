package com.nexora.source.testkit

public enum class CorpusKind {
    VALID_CONFIG,
    POLICY_DISABLED,
    PLUGIN_DESCRIPTORS_ONLY,
    MALFORMED,
}

public enum class CorpusExpectation {
    ACCEPT,
    ACCEPT_WITH_DISABLED_FIELDS,
    DESCRIBE_ONLY,
    REJECT,
}

public data class CorpusCase(
    val id: String,
    val relativePath: String,
    val synthetic: Boolean,
    val kind: CorpusKind,
    val expectation: CorpusExpectation,
    val sha256: String,
)

public data class CorpusManifest(
    val schemaVersion: Int,
    val suite: String,
    val syntheticOnly: Boolean,
    val pluginExecution: String,
    val cases: List<CorpusCase>,
)

public data class CorpusValidationIssue(
    val code: String,
    val detail: String,
)
