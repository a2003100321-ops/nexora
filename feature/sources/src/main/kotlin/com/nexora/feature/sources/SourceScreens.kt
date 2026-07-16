package com.nexora.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nexora.source.api.CompatibilityDiagnostic
import com.nexora.source.api.CompatibilityIssueCode
import com.nexora.source.api.CompatibilitySeverity
import com.nexora.source.api.ConfigImportKind
import com.nexora.source.api.LegacyConfigId
import com.nexora.source.api.LegacyConfigSnapshot
import com.nexora.source.api.LegacySiteDescriptor
import com.nexora.source.api.LegacySourceKey
import com.nexora.source.api.LegacySourceState
import com.nexora.source.api.SourceAvailability
import com.nexora.source.api.SourceSearchCapability
import com.nexora.source.api.SourceUserActivation

@Composable
internal fun SourceOnboardingScreen(
    busy: Boolean,
    error: SourcesUiErrorCode?,
    onDismissError: () -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.sources_brand),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.sources_onboarding_eyebrow),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.sources_onboarding_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.sources_onboarding_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.sources_onboarding_privacy),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (error != null) {
                    SourcesMessageCard(
                        error = error,
                        feedback = null,
                        onDismiss = onDismissError,
                    )
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.sources_onboarding_import))
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.sources_onboarding_skip))
                }
            }
        }
    }
}

@Composable
internal fun SourceManagementScreen(
    state: LegacySourceState,
    importEditor: ImportEditor,
    remoteUrl: String,
    pastedText: String,
    busy: Boolean,
    error: SourcesUiErrorCode?,
    feedback: SourcesUiFeedback?,
    diagnostics: List<CompatibilityDiagnostic>,
    finishLabelIsBack: Boolean,
    pendingDelete: LegacyConfigSnapshot?,
    onImportEditorChange: (ImportEditor) -> Unit,
    onRemoteUrlChange: (String) -> Unit,
    onPastedTextChange: (String) -> Unit,
    onImportRemoteUrl: () -> Unit,
    onImportPastedText: () -> Unit,
    onChooseLocalFile: () -> Unit,
    onSourceEnabledChange: (LegacyConfigId, LegacySourceKey, Boolean) -> Unit,
    onRequestDelete: (LegacyConfigSnapshot) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (LegacyConfigId) -> Unit,
    onClearDiagnostics: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenSearch: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp,
                vertical = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                ManagementHeader(
                    busy = busy,
                    finishLabelIsBack = finishLabelIsBack,
                    onFinished = onFinished,
                )
            }
            if (busy) {
                item(key = "busy") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.sources_busy),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item(key = "search-test") {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.sources_search_test_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.sources_search_test_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = onOpenSearch,
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.sources_search_test_action))
                        }
                    }
                }
            }
            item(key = "import") {
                ImportConfigurationCard(
                    importEditor = importEditor,
                    remoteUrl = remoteUrl,
                    pastedText = pastedText,
                    busy = busy,
                    onImportEditorChange = onImportEditorChange,
                    onRemoteUrlChange = onRemoteUrlChange,
                    onPastedTextChange = onPastedTextChange,
                    onImportRemoteUrl = onImportRemoteUrl,
                    onImportPastedText = onImportPastedText,
                    onChooseLocalFile = onChooseLocalFile,
                )
            }
            if (error != null || feedback != null) {
                item(key = "message") {
                    SourcesMessageCard(
                        error = error,
                        feedback = feedback,
                        onDismiss = onDismissMessage,
                    )
                }
            }
            if (diagnostics.isNotEmpty()) {
                item(key = "diagnostics") {
                    DiagnosticsSection(
                        diagnostics = diagnostics,
                        busy = busy,
                        onClear = onClearDiagnostics,
                    )
                }
            }
            item(key = "configurations-heading") {
                SectionHeading(
                    title = stringResource(R.string.sources_configurations_title),
                    detail = stringResource(
                        R.string.sources_configuration_count,
                        state.configurations.size,
                    ),
                )
            }
            if (state.configurations.isEmpty()) {
                item(key = "empty") { EmptyConfigurationsCard() }
            } else {
                items(
                    items = state.configurations,
                    key = { configuration -> configuration.id.value },
                ) { configuration ->
                    ConfigurationCard(
                        configuration = configuration,
                        busy = busy,
                        onSourceEnabledChange = onSourceEnabledChange,
                        onDelete = { onRequestDelete(configuration) },
                    )
                }
            }
            item(key = "bottom-space") { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }

    if (pendingDelete != null) {
        DeleteConfigurationDialog(
            configuration = pendingDelete,
            busy = busy,
            onDismiss = onDismissDelete,
            onConfirm = { onConfirmDelete(pendingDelete.id) },
        )
    }
}

@Composable
private fun ManagementHeader(
    busy: Boolean,
    finishLabelIsBack: Boolean,
    onFinished: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sources_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.sources_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onFinished, enabled = !busy) {
            Text(
                stringResource(
                    if (finishLabelIsBack) R.string.sources_back else R.string.sources_finish,
                ),
            )
        }
    }
}

@Composable
private fun ImportConfigurationCard(
    importEditor: ImportEditor,
    remoteUrl: String,
    pastedText: String,
    busy: Boolean,
    onImportEditorChange: (ImportEditor) -> Unit,
    onRemoteUrlChange: (String) -> Unit,
    onPastedTextChange: (String) -> Unit,
    onImportRemoteUrl: () -> Unit,
    onImportPastedText: () -> Unit,
    onChooseLocalFile: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.sources_import_section),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = importEditor == ImportEditor.REMOTE_URL,
                    onClick = { onImportEditorChange(ImportEditor.REMOTE_URL) },
                    label = { Text(stringResource(R.string.sources_import_url_mode)) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = importEditor == ImportEditor.PASTED_TEXT,
                    onClick = { onImportEditorChange(ImportEditor.PASTED_TEXT) },
                    label = { Text(stringResource(R.string.sources_import_text_mode)) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
            when (importEditor) {
                ImportEditor.REMOTE_URL -> {
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = onRemoteUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.sources_url_label)) },
                        placeholder = { Text(stringResource(R.string.sources_url_placeholder)) },
                        supportingText = { Text(stringResource(R.string.sources_url_supporting)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Button(
                        onClick = onImportRemoteUrl,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.sources_import_url_action))
                    }
                }

                ImportEditor.PASTED_TEXT -> {
                    OutlinedTextField(
                        value = pastedText,
                        onValueChange = onPastedTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        minLines = 5,
                        maxLines = 12,
                        label = { Text(stringResource(R.string.sources_pasted_text_label)) },
                        placeholder = {
                            Text(stringResource(R.string.sources_pasted_text_placeholder))
                        },
                    )
                    Button(
                        onClick = onImportPastedText,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.sources_import_text_action))
                    }
                }
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = onChooseLocalFile,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(stringResource(R.string.sources_choose_file))
            }
            Text(
                text = stringResource(R.string.sources_file_supporting),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SourcesMessageCard(
    error: SourcesUiErrorCode?,
    feedback: SourcesUiFeedback?,
    onDismiss: () -> Unit,
) {
    val isError = error != null
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isError) {
                Text(
                    text = stringResource(R.string.sources_error_title),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = if (error != null) errorMessage(error) else feedbackMessage(feedback),
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.sources_dismiss_message))
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(
    diagnostics: List<CompatibilityDiagnostic>,
    busy: Boolean,
    onClear: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sources_diagnostics_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.sources_diagnostics_count, diagnostics.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onClear, enabled = !busy) {
                    Text(stringResource(R.string.sources_diagnostics_clear))
                }
            }
            diagnostics.forEachIndexed { index, diagnostic ->
                if (index > 0) HorizontalDivider()
                DiagnosticRow(diagnostic)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(diagnostic: CompatibilityDiagnostic) {
    val severityColor = severityColor(diagnostic.severity)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = severityLabel(diagnostic.severity),
                color = severityColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = issueLabel(diagnostic.code),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(text = diagnostic.userMessage, style = MaterialTheme.typography.bodyMedium)
        if (diagnostic.jsonPath.isNotBlank()) {
            Text(
                text = stringResource(R.string.sources_diagnostic_path, diagnostic.jsonPath),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(
                if (diagnostic.recoverable) {
                    R.string.sources_diagnostic_recoverable
                } else {
                    R.string.sources_diagnostic_not_recoverable
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SectionHeading(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EmptyConfigurationsCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.sources_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.sources_empty_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ConfigurationCard(
    configuration: LegacyConfigSnapshot,
    busy: Boolean,
    onSourceEnabledChange: (LegacyConfigId, LegacySourceKey, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val enabledCount = configuration.fields.sites.count { site ->
        site.state.userActivation == SourceUserActivation.ENABLED
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = configuration.displayName.ifBlank {
                            stringResource(R.string.sources_unnamed_configuration)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = importKindLabel(configuration.importKind),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = stringResource(
                            R.string.sources_config_site_summary,
                            configuration.fields.sites.size,
                            enabledCount,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (configuration.diagnostics.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.sources_config_issue_summary,
                                configuration.diagnostics.size,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text(stringResource(R.string.sources_delete_configuration))
                }
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.sources_sites_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (configuration.fields.sites.isEmpty()) {
                Text(
                    text = stringResource(R.string.sources_sites_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                configuration.fields.sites.forEachIndexed { index, site ->
                    if (index > 0) HorizontalDivider()
                    SourceSiteRow(
                        site = site,
                        busy = busy,
                        onEnabledChange = { enabled ->
                            onSourceEnabledChange(configuration.id, site.sourceKey, enabled)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSiteRow(
    site: LegacySiteDescriptor,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val displayName = site.name.ifBlank { stringResource(R.string.sources_unnamed_site) }
    val enabled = site.state.userActivation == SourceUserActivation.ENABLED
    val switchDescription = stringResource(
        R.string.sources_site_toggle_description,
        displayName,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.sources_site_type, site.type),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = listOf(
                    searchCapabilityLabel(site.state.searchCapability),
                    availabilityLabel(site.state.availability),
                    stringResource(
                        if (enabled) {
                            R.string.sources_site_enabled
                        } else {
                            R.string.sources_site_disabled
                        },
                    ),
                ).joinToString(separator = " · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            enabled = !busy,
            modifier = Modifier.semantics { contentDescription = switchDescription },
        )
    }
}

@Composable
private fun DeleteConfigurationDialog(
    configuration: LegacyConfigSnapshot,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val displayName = configuration.displayName.ifBlank {
        stringResource(R.string.sources_unnamed_configuration)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sources_delete_confirm_title, displayName)) },
        text = { Text(stringResource(R.string.sources_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(
                    text = stringResource(R.string.sources_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.sources_cancel))
            }
        },
    )
}

@Composable
private fun errorMessage(error: SourcesUiErrorCode): String = stringResource(
    when (error) {
        SourcesUiErrorCode.URL_REQUIRED -> R.string.sources_error_url_required
        SourcesUiErrorCode.TEXT_REQUIRED -> R.string.sources_error_text_required
        SourcesUiErrorCode.IMPORT_REJECTED -> R.string.sources_error_import_rejected
        SourcesUiErrorCode.OPERATION_FAILED -> R.string.sources_error_operation_failed
        SourcesUiErrorCode.UNSUPPORTED_FILE -> R.string.sources_error_unsupported_file
        SourcesUiErrorCode.FILE_NOT_FOUND -> R.string.sources_error_file_not_found
        SourcesUiErrorCode.PERMISSION_DENIED -> R.string.sources_error_permission_denied
        SourcesUiErrorCode.FILE_TOO_LARGE -> R.string.sources_error_file_too_large
        SourcesUiErrorCode.INVALID_UTF8 -> R.string.sources_error_invalid_utf8
        SourcesUiErrorCode.FILE_READ_FAILED -> R.string.sources_error_file_read_failed
    },
)

@Composable
private fun feedbackMessage(feedback: SourcesUiFeedback?): String = when (feedback) {
    is SourcesUiFeedback.Imported -> if (feedback.count == 1) {
        stringResource(R.string.sources_import_success_one)
    } else {
        stringResource(R.string.sources_import_success_many, feedback.count)
    }

    SourcesUiFeedback.SourceUpdated -> stringResource(R.string.sources_toggle_success)
    SourcesUiFeedback.ConfigurationDeleted -> stringResource(R.string.sources_delete_success)
    null -> ""
}

@Composable
private fun importKindLabel(kind: ConfigImportKind): String = stringResource(
    when (kind) {
        ConfigImportKind.REMOTE_URL -> R.string.sources_import_kind_remote
        ConfigImportKind.PASTED_TEXT -> R.string.sources_import_kind_pasted
        ConfigImportKind.LOCAL_FILE -> R.string.sources_import_kind_local
    },
)

@Composable
private fun severityLabel(severity: CompatibilitySeverity): String = stringResource(
    when (severity) {
        CompatibilitySeverity.INFO -> R.string.sources_severity_info
        CompatibilitySeverity.WARNING -> R.string.sources_severity_warning
        CompatibilitySeverity.ERROR -> R.string.sources_severity_error
    },
)

@Composable
private fun severityColor(severity: CompatibilitySeverity): Color = when (severity) {
    CompatibilitySeverity.INFO -> MaterialTheme.colorScheme.primary
    CompatibilitySeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    CompatibilitySeverity.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun issueLabel(code: CompatibilityIssueCode): String = stringResource(
    when (code) {
        CompatibilityIssueCode.INPUT_TOO_LARGE -> R.string.sources_issue_input_too_large
        CompatibilityIssueCode.INVALID_UTF8 -> R.string.sources_issue_invalid_utf8
        CompatibilityIssueCode.INVALID_JSON -> R.string.sources_issue_invalid_json
        CompatibilityIssueCode.INVALID_BASE64 -> R.string.sources_issue_invalid_base64
        CompatibilityIssueCode.INVALID_2423_AES -> R.string.sources_issue_invalid_2423_aes
        CompatibilityIssueCode.INVALID_URL -> R.string.sources_issue_invalid_url
        CompatibilityIssueCode.REMOTE_LOAD_FAILED -> R.string.sources_issue_remote_load_failed
        CompatibilityIssueCode.REMOTE_REFERENCE_FAILED -> {
            R.string.sources_issue_remote_reference_failed
        }

        CompatibilityIssueCode.SITE_INVALID -> R.string.sources_issue_site_invalid
        CompatibilityIssueCode.PARSE_INVALID -> R.string.sources_issue_parse_invalid
        CompatibilityIssueCode.FIELD_TYPE_MISMATCH -> R.string.sources_issue_field_type_mismatch
        CompatibilityIssueCode.UNSUPPORTED_SOURCE_TYPE -> {
            R.string.sources_issue_unsupported_source_type
        }

        CompatibilityIssueCode.POLICY_PRESERVED_NOT_EXECUTED -> {
            R.string.sources_issue_preserved_not_executed
        }

        CompatibilityIssueCode.RESOURCE_LIMIT -> R.string.sources_issue_resource_limit
        CompatibilityIssueCode.STORAGE_FAILURE -> R.string.sources_issue_storage_failure
    },
)

@Composable
private fun searchCapabilityLabel(capability: SourceSearchCapability): String = stringResource(
    when (capability) {
        SourceSearchCapability.SUPPORTED -> R.string.sources_site_supported
        SourceSearchCapability.UNSUPPORTED -> R.string.sources_site_unsupported
    },
)

@Composable
private fun availabilityLabel(availability: SourceAvailability): String = stringResource(
    when (availability) {
        SourceAvailability.AVAILABLE -> R.string.sources_site_available
        SourceAvailability.LOAD_FAILED -> R.string.sources_site_load_failed
        SourceAvailability.TEMPORARILY_UNAVAILABLE -> {
            R.string.sources_site_temporarily_unavailable
        }
    },
)
