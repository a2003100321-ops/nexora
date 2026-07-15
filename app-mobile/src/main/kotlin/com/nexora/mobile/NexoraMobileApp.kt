package com.nexora.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexora.feature.home.MobileHomeShell
import com.nexora.feature.sources.MobileSourcesFlow
import com.nexora.source.api.LegacySourceRepository

private enum class MobileDestination {
    LANDING,
    SOURCES,
}

@Composable
public fun NexoraMobileApp(
    repository: LegacySourceRepository,
    modifier: Modifier = Modifier,
) {
    val sourceState by repository.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf<MobileDestination?>(null) }

    LaunchedEffect(sourceState.isInitialized) {
        if (sourceState.isInitialized && destination == null) {
            destination = if (sourceState.onboardingCompleted) {
                MobileDestination.LANDING
            } else {
                MobileDestination.SOURCES
            }
        }
    }

    when (destination) {
        null -> InitializingScreen(modifier)
        MobileDestination.LANDING -> MobileHomeShell(
            onManageSources = { destination = MobileDestination.SOURCES },
            modifier = modifier,
        )
        MobileDestination.SOURCES -> MobileSourcesFlow(
            repository = repository,
            onFinished = { destination = MobileDestination.LANDING },
            modifier = modifier,
        )
    }
}

@Composable
private fun InitializingScreen(modifier: Modifier) {
    val description = stringResource(R.string.initializing_sources)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = description },
                )
                Text(
                    text = description,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
