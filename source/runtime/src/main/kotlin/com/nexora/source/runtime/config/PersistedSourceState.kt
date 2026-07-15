package com.nexora.source.runtime.config

import com.nexora.source.api.ConfigImportKind
import com.nexora.source.api.CompatibilityDiagnostic
import com.nexora.source.api.LegacyConfigId

internal data class PersistedSourceState(
    val onboardingCompleted: Boolean = false,
    val configurations: List<PersistedConfiguration> = emptyList(),
)

internal data class PersistedConfiguration(
    val id: LegacyConfigId,
    val displayName: String,
    val importKind: ConfigImportKind,
    val origin: String?,
    val originDisplay: String?,
    val originalJson: String,
    val expandedJson: String,
    val disabledLegacyKeys: Set<String>,
    val diagnostics: List<CompatibilityDiagnostic>,
)
