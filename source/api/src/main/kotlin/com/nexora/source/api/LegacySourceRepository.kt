package com.nexora.source.api

import kotlinx.coroutines.flow.StateFlow

public data class LegacySourceState(
    val isInitialized: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val configurations: List<LegacyConfigSnapshot> = emptyList(),
    val lastImportDiagnostics: List<CompatibilityDiagnostic> = emptyList(),
    val isBusy: Boolean = false,
)

public data class LegacyConfigImportResult(
    val imported: List<LegacyConfigSnapshot>,
    val diagnostics: List<CompatibilityDiagnostic>,
) {
    public val succeeded: Boolean
        get() = imported.isNotEmpty()
}

public interface LegacySourceRepository {
    public val state: StateFlow<LegacySourceState>

    public suspend fun importRemoteUrl(url: String): LegacyConfigImportResult

    public suspend fun importPastedText(text: String): LegacyConfigImportResult

    public suspend fun importLocalFile(fileName: String, text: String): LegacyConfigImportResult

    public suspend fun setSourceEnabled(
        configId: LegacyConfigId,
        sourceKey: LegacySourceKey,
        enabled: Boolean,
    )

    public suspend fun deleteConfiguration(configId: LegacyConfigId)

    public suspend fun completeOnboardingWithoutImport()

    public suspend fun clearLastImportDiagnostics()
}
