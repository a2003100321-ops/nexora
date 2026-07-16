package com.nexora.feature.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nexora.source.api.PerSourceSearchProgress
import com.nexora.source.api.PerSourceSearchState
import com.nexora.source.api.SourceMediaDetail
import com.nexora.source.api.SourceMediaSummary
import java.net.InetAddress
import java.net.URI

@Composable
internal fun SourceSearchScreen(
    state: SourceSearchUiState,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSelectResult: (SourceMediaSummary) -> Unit,
    onCloseDetail: () -> Unit,
    onBack: () -> Unit,
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
            item(key = "search-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.sources_search_back))
                    }
                    Text(
                        text = stringResource(R.string.sources_search_test_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.sources_search_test_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item(key = "search-input") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.sources_search_input)) },
                        placeholder = { Text(stringResource(R.string.sources_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onRetry,
                            enabled = state.query.isNotBlank() && state.phase != SearchRunPhase.RUNNING,
                        ) {
                            Text(stringResource(R.string.sources_search_retry))
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            enabled = state.phase == SearchRunPhase.DEBOUNCING ||
                                state.phase == SearchRunPhase.RUNNING,
                        ) {
                            Text(stringResource(R.string.sources_search_cancel))
                        }
                    }
                    SearchPhaseMessage(state)
                }
            }
            if (state.sources.isNotEmpty()) {
                item(key = "source-status-title") {
                    Text(
                        text = stringResource(R.string.sources_search_source_states),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                itemsIndexed(
                    items = state.sources,
                    key = { index, progress -> "${progress.sourceKey.value.length}:${progress.sourceKey.value}:$index" },
                ) { _, progress ->
                    SourceProgressCard(progress)
                }
                if (state.sourcesTruncated) {
                    item(key = "source-limit") {
                        Text(
                            text = stringResource(R.string.sources_search_source_limit),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (state.results.isNotEmpty()) {
                item(key = "result-title") {
                    Text(
                        text = stringResource(R.string.sources_search_results, state.results.size),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                itemsIndexed(
                    items = state.results,
                    key = { index, result -> searchRowKey(result, index) },
                ) { _, result ->
                    SearchResultCard(result = result, onClick = { onSelectResult(result) })
                }
                if (state.resultsTruncated) {
                    item(key = "result-limit") {
                        Text(
                            text = stringResource(R.string.sources_search_result_limit),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    when (val detail = state.detail) {
        SearchDetailUiState.None -> Unit
        is SearchDetailUiState.Loading -> SearchDetailDialog(
            title = detail.title,
            detail = null,
            error = null,
            loading = true,
            onClose = onCloseDetail,
        )

        is SearchDetailUiState.Loaded -> SearchDetailDialog(
            title = detail.detail.media.title,
            detail = detail.detail,
            error = null,
            loading = false,
            onClose = onCloseDetail,
        )

        is SearchDetailUiState.Failed -> SearchDetailDialog(
            title = detail.title,
            detail = null,
            error = detail.userMessage,
            loading = false,
            onClose = onCloseDetail,
        )
    }
}

@Composable
private fun SearchPhaseMessage(state: SourceSearchUiState) {
    if (state.phase == SearchRunPhase.RUNNING || state.phase == SearchRunPhase.DEBOUNCING) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    val message = when (state.phase) {
        SearchRunPhase.IDLE -> R.string.sources_search_idle
        SearchRunPhase.DEBOUNCING -> R.string.sources_search_debouncing
        SearchRunPhase.RUNNING -> R.string.sources_search_running
        SearchRunPhase.CANCELLED -> R.string.sources_search_cancelled
        SearchRunPhase.COMPLETED -> when {
            state.sources.isEmpty() -> R.string.sources_search_no_sources
            state.results.isEmpty() -> R.string.sources_search_no_results
            else -> null
        }
    }
    if (message != null) {
        Text(
            text = stringResource(message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SourceProgressCard(progress: PerSourceSearchProgress) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = progress.sourceName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when (val sourceState = progress.state) {
                    PerSourceSearchState.Loading -> stringResource(R.string.sources_search_loading)
                    is PerSourceSearchState.Success -> stringResource(
                        if (sourceState.truncated) {
                            R.string.sources_search_success_truncated
                        } else {
                            R.string.sources_search_success
                        },
                        sourceState.itemCount,
                    )

                    is PerSourceSearchState.Failure -> stringResource(
                        R.string.sources_search_failure,
                        sourceState.error.userMessage,
                    )
                },
                color = when (progress.state) {
                    is PerSourceSearchState.Failure -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SourceMediaSummary,
    onClick: () -> Unit,
) {
    val displayTitle = result.title.ifBlank {
        stringResource(R.string.sources_search_unknown_title)
    }
    val posterUrl = remember(result.poster) { strictHttpsPosterUrl(result.poster) }
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(88.dp)
                    .size(width = 88.dp, height = 124.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (posterUrl == null) {
                    Text(
                        text = stringResource(R.string.sources_search_poster_unavailable),
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = stringResource(R.string.sources_search_poster, displayTitle),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val metadata = listOf(result.year, result.type, result.region)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .joinToString(" · ")
                if (metadata.isNotEmpty()) {
                    Text(
                        text = metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.sources_search_source, result.sourceName),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SearchDetailDialog(
    title: String,
    detail: SourceMediaDetail?,
    error: String?,
    loading: Boolean,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sources_search_detail_title))
                Text(
                    text = title.ifBlank { stringResource(R.string.sources_search_unknown_title) },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.sources_search_detail_loading))
                    }

                    error != null -> Text(
                        text = stringResource(R.string.sources_search_detail_failed, error),
                        color = MaterialTheme.colorScheme.error,
                    )

                    detail != null -> DetailContent(detail)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.sources_search_detail_close))
            }
        },
    )
}

@Composable
private fun DetailContent(detail: SourceMediaDetail) {
    if (detail.director.isNotBlank()) {
        Text(stringResource(R.string.sources_search_detail_director, detail.director))
    }
    if (detail.actors.isNotBlank()) {
        Text(stringResource(R.string.sources_search_detail_actors, detail.actors))
    }
    if (detail.description.isNotBlank()) {
        Text(stringResource(R.string.sources_search_detail_description, detail.description))
    }
    Text(
        text = stringResource(R.string.sources_search_detail_lines),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (detail.lines.isEmpty()) {
        Text(stringResource(R.string.sources_search_detail_no_lines))
    } else {
        detail.lines.forEach { line ->
            Text(
                text = stringResource(
                    R.string.sources_search_detail_line,
                    line.name,
                    line.episodes.size,
                ),
                fontWeight = FontWeight.Medium,
            )
            val episodeNames = line.episodes.map { episode -> episode.name.trim() }
                .filter(String::isNotEmpty)
                .joinToString(" · ")
            if (episodeNames.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.sources_search_detail_episodes, episodeNames),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun strictHttpsPosterUrl(value: String): String? {
    if (value.isBlank() || value.length > MAX_POSTER_URL_LENGTH || value.any(Char::isISOControl)) return null
    return runCatching {
        val parsed = URI(value.trim())
        if (!parsed.scheme.equals("https", ignoreCase = true) ||
            parsed.host.isNullOrBlank() ||
            parsed.rawUserInfo != null ||
            parsed.rawFragment != null ||
            isForbiddenLocalPosterHost(parsed.host)
        ) {
            return@runCatching null
        }
        parsed.toASCIIString()
    }.getOrNull()
}

private fun isForbiddenLocalPosterHost(rawHost: String): Boolean = runCatching {
    val host = rawHost.trim().trim('[', ']').trimEnd('.').lowercase()
    if (host == "localhost" || host.endsWith(".localhost")) return@runCatching true
    if (host.all { character -> character.isDigit() || character == '.' } && host.count { it == '.' } != 3) {
        return@runCatching true
    }
    val parts = host.split('.')
    if (parts.size == 4) {
        val bytes = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return@runCatching false }
            .map { it.toByte() }
            .toByteArray()
        return@runCatching isForbiddenPosterAddress(InetAddress.getByAddress(bytes))
    }
    if (':' in host) {
        val address = InetAddress.getByName(host)
        return@runCatching isForbiddenPosterAddress(address) || host.startsWith("fc") || host.startsWith("fd")
    }
    false
}.getOrDefault(true)

private fun isForbiddenPosterAddress(address: InetAddress): Boolean {
    val bytes = address.address
    return address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress ||
        (bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64)
}

private fun searchRowKey(result: SourceMediaSummary, index: Int): String = buildString {
    append(result.sourceKey.value.length)
    append(':')
    append(result.sourceKey.value)
    append(':')
    append(result.vodId.length)
    append(':')
    append(result.vodId)
    append(':')
    append(index)
}

private const val MAX_POSTER_URL_LENGTH: Int = 8_192
