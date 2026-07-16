package com.nexora.source.runtime.config

import android.content.Context
import com.nexora.core.network.NetworkRequest
import com.nexora.core.network.NetworkResult
import com.nexora.core.network.NetworkTransports
import com.nexora.core.network.SafeHttpTransport
import com.nexora.source.api.CompatibilityDiagnostic
import com.nexora.source.api.CompatibilityIssueCode
import com.nexora.source.api.CompatibilitySeverity
import com.nexora.source.api.ConfigImportKind
import com.nexora.source.api.LegacyConfigId
import com.nexora.source.api.LegacyConfigImportResult
import com.nexora.source.api.LegacyConfigSnapshot
import com.nexora.source.api.LegacySourceKey
import com.nexora.source.api.LegacySourceRepository
import com.nexora.source.api.LegacySourceState
import com.nexora.source.api.RawJson
import com.nexora.source.api.SourceUserActivation
import com.nexora.source.config.DefaultLegacyConfigImporter
import com.nexora.source.config.RemoteArrayLoader
import java.net.URI
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public class AndroidLegacySourceRepository private constructor(
    private val store: FileLegacySourceStore,
    private val transport: SafeHttpTransport,
    private val scope: CoroutineScope,
) : LegacySourceRepository {
    private val operationMutex = Mutex()
    private val importer = DefaultLegacyConfigImporter(
        remoteArrayLoader = RemoteArrayLoader(::loadRemoteReference),
    )
    private val restoreImporter = DefaultLegacyConfigImporter()
    private val mutableState = MutableStateFlow(LegacySourceState(isBusy = true))

    override val state: StateFlow<LegacySourceState> = mutableState.asStateFlow()

    init {
        scope.launch { restore() }
    }

    override suspend fun importRemoteUrl(url: String): LegacyConfigImportResult = operationMutex.withLock {
        setBusy(true)
        try {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return@withLock failImport(
                issue(
                    code = CompatibilityIssueCode.INVALID_URL,
                    path = "$",
                    message = "请输入有效的 HTTPS 配置地址。",
                ),
            )
            when (val result = transport.execute(NetworkRequest(url = trimmed))) {
                is NetworkResult.Failure -> failImport(
                    issue(
                        code = CompatibilityIssueCode.REMOTE_LOAD_FAILED,
                        path = "$",
                        message = result.failure.userMessage,
                    ),
                )

                is NetworkResult.Success -> {
                    if (result.response.statusCode !in 200..299) {
                        failImport(
                            issue(
                                code = CompatibilityIssueCode.REMOTE_LOAD_FAILED,
                                path = "$",
                                message = "配置服务器返回 HTTP ${result.response.statusCode}，请稍后重试。",
                            ),
                        )
                    } else {
                        val imported = importer.importConfig(
                            bytes = result.response.body,
                            displayName = displayNameForRemote(trimmed),
                            kind = ConfigImportKind.REMOTE_URL,
                            origin = trimmed,
                        )
                        acceptImport(imported)
                    }
                }
            }
        } finally {
            setBusy(false)
        }
    }

    override suspend fun importPastedText(text: String): LegacyConfigImportResult = operationMutex.withLock {
        setBusy(true)
        try {
            acceptImport(
                importer.importConfig(
                    text = text,
                    displayName = "粘贴的配置",
                    kind = ConfigImportKind.PASTED_TEXT,
                ),
            )
        } finally {
            setBusy(false)
        }
    }

    override suspend fun importLocalFile(
        fileName: String,
        text: String,
    ): LegacyConfigImportResult = operationMutex.withLock {
        setBusy(true)
        try {
            acceptImport(
                importer.importConfig(
                    text = text,
                    displayName = safeFileDisplayName(fileName),
                    kind = ConfigImportKind.LOCAL_FILE,
                ),
            )
        } finally {
            setBusy(false)
        }
    }

    override suspend fun setSourceEnabled(
        configId: LegacyConfigId,
        sourceKey: LegacySourceKey,
        enabled: Boolean,
    ): Unit = operationMutex.withLock {
        val current = mutableState.value
        val updated = current.copy(
            configurations = current.configurations.map { configuration ->
                if (configuration.id != configId) configuration else configuration.copy(
                    fields = configuration.fields.copy(
                        sites = configuration.fields.sites.map { site ->
                            if (site.sourceKey != sourceKey) site else site.copy(
                                state = site.state.copy(
                                    userActivation = if (enabled) {
                                        SourceUserActivation.ENABLED
                                    } else {
                                        SourceUserActivation.DISABLED
                                    },
                                ),
                            )
                        },
                    ),
                )
            },
        )
        persistOrReport(updated)
    }

    override suspend fun deleteConfiguration(configId: LegacyConfigId): Unit = operationMutex.withLock {
        val current = mutableState.value
        persistOrReport(
            current.copy(
                configurations = current.configurations.filterNot { it.id == configId },
            ),
        )
    }

    override suspend fun completeOnboardingWithoutImport(): Unit = operationMutex.withLock {
        persistOrReport(mutableState.value.copy(onboardingCompleted = true))
    }

    override suspend fun clearLastImportDiagnostics() {
        mutableState.value = mutableState.value.copy(lastImportDiagnostics = emptyList())
    }

    private suspend fun acceptImport(result: LegacyConfigImportResult): LegacyConfigImportResult {
        if (!result.succeeded) {
            mutableState.value = mutableState.value.copy(lastImportDiagnostics = result.diagnostics)
            return result
        }
        val current = mutableState.value
        val existingById = current.configurations.associateBy(LegacyConfigSnapshot::id)
        val importedWithPreservedActivation = result.imported.map { imported ->
            val existingSites = existingById[imported.id]
                ?.fields
                ?.sites
                ?.associateBy { site -> site.sourceKey }
                .orEmpty()
            imported.copy(
                fields = imported.fields.copy(
                    sites = imported.fields.sites.map { site ->
                        val previous = existingSites[site.sourceKey] ?: return@map site
                        site.copy(
                            state = site.state.copy(
                                userActivation = previous.state.userActivation,
                            ),
                        )
                    },
                ),
            )
        }
        val acceptedResult = result.copy(imported = importedWithPreservedActivation)
        val importedIds = importedWithPreservedActivation.map(LegacyConfigSnapshot::id).toSet()
        val updated = current.copy(
            onboardingCompleted = true,
            configurations = (
                current.configurations.filterNot { it.id in importedIds } + importedWithPreservedActivation
            )
                .sortedBy(LegacyConfigSnapshot::displayName),
            lastImportDiagnostics = result.diagnostics,
        )
        return if (persist(updated)) {
            mutableState.value = updated
            acceptedResult
        } else {
            val storageDiagnostic = storageFailure()
            mutableState.value = current.copy(lastImportDiagnostics = result.diagnostics + storageDiagnostic)
            LegacyConfigImportResult(
                imported = emptyList(),
                diagnostics = result.diagnostics + storageDiagnostic,
            )
        }
    }

    private suspend fun persistOrReport(updated: LegacySourceState) {
        val current = mutableState.value
        if (persist(updated)) {
            mutableState.value = updated
        } else {
            mutableState.value = current.copy(
                lastImportDiagnostics = current.lastImportDiagnostics + storageFailure(),
            )
        }
    }

    private suspend fun persist(state: LegacySourceState): Boolean = withContext(Dispatchers.IO) {
        runCatching { store.write(state.toPersistedState()) }.isSuccess
    }

    private suspend fun restore() {
        val restored = withContext(Dispatchers.IO) { runCatching(store::read) }
        if (restored.isFailure) {
            mutableState.value = LegacySourceState(
                isInitialized = true,
                lastImportDiagnostics = listOf(storageFailure()),
                isBusy = false,
            )
            return
        }
        val persisted = restored.getOrThrow()
        val configurations = mutableListOf<LegacyConfigSnapshot>()
        for (record in persisted.configurations) {
            restoreConfiguration(record)?.let(configurations::add)
        }
        mutableState.value = LegacySourceState(
            isInitialized = true,
            onboardingCompleted = persisted.onboardingCompleted,
            configurations = configurations,
            lastImportDiagnostics = configurations.flatMap(LegacyConfigSnapshot::diagnostics),
            isBusy = false,
        )
    }

    private suspend fun restoreConfiguration(record: PersistedConfiguration): LegacyConfigSnapshot? {
        val parsed = restoreImporter.importConfig(
            text = record.expandedJson,
            displayName = record.displayName,
            kind = record.importKind,
            origin = record.origin,
        ).imported.singleOrNull() ?: return null
        val restoredSites = parsed.fields.sites.map { site ->
            site.copy(
                sourceKey = LegacySourceKey("${record.id.value}:${site.legacyKey}"),
                state = site.state.copy(
                    userActivation = if (site.legacyKey in record.disabledLegacyKeys) {
                        SourceUserActivation.DISABLED
                    } else {
                        SourceUserActivation.ENABLED
                    },
                ),
            )
        }
        return parsed.copy(
            id = record.id,
            displayName = record.displayName,
            origin = record.origin,
            originDisplay = record.originDisplay,
            original = RawJson(record.originalJson),
            fields = parsed.fields.copy(sites = restoredSites),
            diagnostics = record.diagnostics,
        )
    }

    private suspend fun loadRemoteReference(resolvedUrl: String): ByteArray {
        return when (val result = transport.execute(NetworkRequest(url = resolvedUrl))) {
            is NetworkResult.Failure -> throw RemoteReferenceException(result.failure.userMessage)
            is NetworkResult.Success -> {
                if (result.response.statusCode !in 200..299) {
                    throw RemoteReferenceException(
                        "远程数组返回 HTTP ${result.response.statusCode}。",
                    )
                }
                result.response.body
            }
        }
    }

    private fun failImport(diagnostic: CompatibilityDiagnostic): LegacyConfigImportResult {
        mutableState.value = mutableState.value.copy(lastImportDiagnostics = listOf(diagnostic))
        return LegacyConfigImportResult(emptyList(), listOf(diagnostic))
    }

    private fun setBusy(busy: Boolean) {
        mutableState.value = mutableState.value.copy(isBusy = busy)
    }

    private fun LegacySourceState.toPersistedState(): PersistedSourceState = PersistedSourceState(
        onboardingCompleted = onboardingCompleted,
        configurations = configurations.map { configuration ->
            PersistedConfiguration(
                id = configuration.id,
                displayName = configuration.displayName,
                importKind = configuration.importKind,
                origin = configuration.origin,
                originDisplay = configuration.originDisplay,
                originalJson = configuration.original.canonicalText,
                expandedJson = configuration.expanded.canonicalText,
                disabledLegacyKeys = configuration.fields.sites
                    .filter { it.state.userActivation == SourceUserActivation.DISABLED }
                    .map { it.legacyKey }
                    .toSet(),
                diagnostics = configuration.diagnostics,
            )
        },
    )

    public companion object {
        public fun create(context: Context): AndroidLegacySourceRepository =
            create(context, NetworkTransports.create())

        internal fun create(
            context: Context,
            transport: SafeHttpTransport,
        ): AndroidLegacySourceRepository = AndroidLegacySourceRepository(
                store = FileLegacySourceStore(context.applicationContext.noBackupFilesDir),
                transport = transport,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )

        internal fun createForTest(
            storeDirectory: File,
            transport: SafeHttpTransport,
            scope: CoroutineScope,
        ): AndroidLegacySourceRepository = AndroidLegacySourceRepository(
            store = FileLegacySourceStore(storeDirectory),
            transport = transport,
            scope = scope,
        )

        private fun issue(
            code: CompatibilityIssueCode,
            path: String,
            message: String,
        ): CompatibilityDiagnostic = CompatibilityDiagnostic(
            code = code,
            severity = CompatibilitySeverity.ERROR,
            jsonPath = path,
            userMessage = message,
            recoverable = false,
        )

        private fun storageFailure(): CompatibilityDiagnostic = issue(
            code = CompatibilityIssueCode.STORAGE_FAILURE,
            path = "$",
            message = "无法安全保存数据源配置，请检查设备存储空间后重试。",
        )

        private fun displayNameForRemote(url: String): String = runCatching {
            URI(url).host?.takeIf(String::isNotBlank)?.let { "远程配置 · $it" }
        }.getOrNull() ?: "远程配置"

        private fun safeFileDisplayName(fileName: String): String {
            val sanitized = fileName.substringAfterLast('/').substringAfterLast('\\')
                .filterNot(Char::isISOControl)
                .trim()
                .take(80)
            return sanitized.ifEmpty { "本地配置" }
        }
    }
}

private class RemoteReferenceException(message: String) : IllegalStateException(message)
