package com.nexora.feature.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexora.source.api.CompatibilityDiagnostic
import com.nexora.source.api.AllSourcesSearcher
import com.nexora.source.api.LegacyConfigId
import com.nexora.source.api.LegacyConfigImportResult
import com.nexora.source.api.LegacyConfigSnapshot
import com.nexora.source.api.LegacyHttpSourceGateway
import com.nexora.source.api.LegacySourceKey
import com.nexora.source.api.LegacySourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public fun MobileSourcesFlow(
    repository: LegacySourceRepository,
    searcher: AllSourcesSearcher,
    gateway: LegacyHttpSourceGateway,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceState by repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val searchController = remember(repository, searcher, gateway, scope) {
        SourceSearchController(
            repository = repository,
            searcher = searcher,
            gateway = gateway,
            scope = scope,
        )
    }
    val searchState by searchController.state.collectAsStateWithLifecycle()
    val documentReader = remember(context) {
        LocalConfigDocumentReader(context.contentResolver)
    }
    val startedInOnboarding = rememberSaveable {
        !repository.state.value.onboardingCompleted
    }

    var destination by rememberSaveable {
        mutableStateOf(
            if (repository.state.value.onboardingCompleted) {
                SourcesDestination.MANAGEMENT
            } else {
                SourcesDestination.ONBOARDING
            },
        )
    }
    var importEditor by rememberSaveable { mutableStateOf(ImportEditor.REMOTE_URL) }
    var remoteUrl by rememberSaveable { mutableStateOf("") }
    var pastedText by remember { mutableStateOf("") }
    var operationInFlight by remember { mutableStateOf(false) }
    var uiError by rememberSaveable { mutableStateOf<SourcesUiErrorCode?>(null) }
    var feedback by remember { mutableStateOf<SourcesUiFeedback?>(null) }
    var resultDiagnostics by remember {
        mutableStateOf<List<CompatibilityDiagnostic>?>(null)
    }
    var pendingDelete by remember { mutableStateOf<LegacyConfigSnapshot?>(null) }

    LaunchedEffect(sourceState.onboardingCompleted) {
        if (sourceState.onboardingCompleted) destination = SourcesDestination.MANAGEMENT
    }

    val busy = sourceState.isBusy || operationInFlight

    suspend fun acceptImportResult(result: LegacyConfigImportResult) {
        resultDiagnostics = result.diagnostics
        if (!result.succeeded) {
            feedback = null
            uiError = SourcesUiErrorCode.IMPORT_REJECTED
            return
        }

        if (!repository.state.value.onboardingCompleted) {
            repository.completeOnboardingWithoutImport()
        }
        feedback = SourcesUiFeedback.Imported(result.imported.size)
        uiError = null
    }

    fun launchOperation(operation: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            operationInFlight = true
            try {
                operation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                feedback = null
                uiError = SourcesUiErrorCode.OPERATION_FAILED
            } finally {
                operationInFlight = false
            }
        }
    }

    fun finish() {
        if (busy) return
        if (sourceState.onboardingCompleted) {
            onFinished()
        } else {
            launchOperation {
                repository.completeOnboardingWithoutImport()
                onFinished()
            }
        }
    }

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            launchOperation {
                uiError = null
                feedback = null
                when (val readResult = documentReader.read(uri)) {
                    is LocalDocumentReadResult.Success -> {
                        val result = repository.importLocalFile(
                            fileName = readResult.document.fileName,
                            text = readResult.document.text,
                        )
                        acceptImportResult(result)
                    }

                    is LocalDocumentReadResult.Failure -> {
                        resultDiagnostics = null
                        uiError = readResult.error.toUiErrorCode()
                    }
                }
            }
        }
    }

    when (destination) {
        SourcesDestination.ONBOARDING -> SourceOnboardingScreen(
            busy = busy,
            error = uiError,
            onDismissError = { uiError = null },
            onImport = {
                uiError = null
                destination = SourcesDestination.MANAGEMENT
            },
            onSkip = ::finish,
            modifier = modifier,
        )

        SourcesDestination.MANAGEMENT -> SourceManagementScreen(
            state = sourceState,
            importEditor = importEditor,
            remoteUrl = remoteUrl,
            pastedText = pastedText,
            busy = busy,
            error = uiError,
            feedback = feedback,
            diagnostics = resultDiagnostics ?: sourceState.lastImportDiagnostics,
            finishLabelIsBack = !startedInOnboarding,
            pendingDelete = pendingDelete,
            onImportEditorChange = { importEditor = it },
            onRemoteUrlChange = { remoteUrl = it },
            onPastedTextChange = { pastedText = it },
            onImportRemoteUrl = {
                val normalizedUrl = remoteUrl.trim()
                if (normalizedUrl.isEmpty()) {
                    feedback = null
                    uiError = SourcesUiErrorCode.URL_REQUIRED
                } else {
                    launchOperation {
                        uiError = null
                        feedback = null
                        val result = repository.importRemoteUrl(normalizedUrl)
                        acceptImportResult(result)
                        if (result.succeeded) remoteUrl = ""
                    }
                }
            },
            onImportPastedText = {
                if (pastedText.isBlank()) {
                    feedback = null
                    uiError = SourcesUiErrorCode.TEXT_REQUIRED
                } else {
                    launchOperation {
                        uiError = null
                        feedback = null
                        val result = repository.importPastedText(pastedText)
                        acceptImportResult(result)
                        if (result.succeeded) pastedText = ""
                    }
                }
            },
            onChooseLocalFile = {
                if (!busy) {
                    documentLauncher.launch(SUPPORTED_DOCUMENT_MIME_TYPES)
                }
            },
            onSourceEnabledChange = { configId, sourceKey, enabled ->
                launchOperation {
                    uiError = null
                    feedback = null
                    repository.setSourceEnabled(configId, sourceKey, enabled)
                    feedback = SourcesUiFeedback.SourceUpdated
                }
            },
            onRequestDelete = { pendingDelete = it },
            onDismissDelete = { pendingDelete = null },
            onConfirmDelete = { configId ->
                pendingDelete = null
                launchOperation {
                    uiError = null
                    feedback = null
                    repository.deleteConfiguration(configId)
                    feedback = SourcesUiFeedback.ConfigurationDeleted
                }
            },
            onClearDiagnostics = {
                resultDiagnostics = emptyList()
                launchOperation { repository.clearLastImportDiagnostics() }
            },
            onDismissMessage = {
                uiError = null
                feedback = null
            },
            onOpenSearch = { destination = SourcesDestination.SEARCH_TEST },
            onFinished = ::finish,
            modifier = modifier,
        )

        SourcesDestination.SEARCH_TEST -> SourceSearchScreen(
            state = searchState,
            onQueryChange = searchController::updateQuery,
            onCancel = searchController::cancelSearch,
            onRetry = searchController::retry,
            onSelectResult = searchController::selectResult,
            onCloseDetail = searchController::closeDetail,
            onBack = {
                searchController.cancelSearch()
                destination = SourcesDestination.MANAGEMENT
            },
            modifier = modifier,
        )
    }
}

private val SUPPORTED_DOCUMENT_MIME_TYPES = arrayOf(
    "application/json",
    "application/octet-stream",
    "text/json",
    "text/plain",
)
