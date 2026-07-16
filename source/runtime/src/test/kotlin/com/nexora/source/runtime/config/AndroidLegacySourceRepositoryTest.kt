package com.nexora.source.runtime.config

import com.nexora.core.network.SafeHttpTransport
import com.nexora.core.network.NetworkResponse
import com.nexora.core.network.NetworkResult
import com.nexora.source.api.SourceSearchCapability
import com.nexora.source.api.SourceUserActivation
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AndroidLegacySourceRepositoryTest {
    @Test
    fun importedStateActivationAndUnknownFieldsSurviveRestart() = runBlocking {
        val directory = Files.createTempDirectory("nexora-source-state").toFile()
        val unusedTransport = SafeHttpTransport { error("network must not be used") }
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = AndroidLegacySourceRepository.createForTest(directory, unusedTransport, repositoryScope)
            withTimeout(5_000L) { repository.state.first { state -> state.isInitialized } }

            val imported = repository.importPastedText(
                """{"future":{"kept":true},"sites":[{"key":"one","name":"One","type":1,"api":"https://source.invalid/api","searchable":0}]}""",
            )
            assertTrue(imported.succeeded)
            val configuration = repository.state.value.configurations.single()
            val source = configuration.fields.sites.single()
            assertEquals(SourceSearchCapability.UNSUPPORTED, source.state.searchCapability)
            assertEquals(SourceUserActivation.ENABLED, source.state.userActivation)

            repository.setSourceEnabled(configuration.id, source.sourceKey, enabled = false)
            repository.completeOnboardingWithoutImport()

            val restored = AndroidLegacySourceRepository.createForTest(directory, unusedTransport, repositoryScope)
            withTimeout(5_000L) { restored.state.first { state -> state.isInitialized } }
            val restoredState = restored.state.value
            assertTrue(restoredState.isInitialized)
            assertTrue(restoredState.onboardingCompleted)
            val restoredConfiguration = restoredState.configurations.single()
            assertEquals(configuration.id, restoredConfiguration.id)
            assertEquals("{\"kept\":true}", restoredConfiguration.unknownTopLevelFields.getValue("future").canonicalText)
            assertEquals(
                SourceUserActivation.DISABLED,
                restoredConfiguration.fields.sites.single().state.userActivation,
            )
        } finally {
            repositoryScope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun refreshingTheSameRemoteOriginKeepsIdentityAndUserActivation() = runBlocking {
        val directory = Files.createTempDirectory("nexora-remote-refresh").toFile()
        val calls = AtomicInteger()
        val payloads = listOf(
            """{"sites":[{"key":"one","name":"First","type":1,"api":"https://source.invalid/api"}]}""",
            """{"future":true,"sites":[{"key":"one","name":"Updated","type":1,"api":"https://source.invalid/api"}]}""",
        )
        val transport = SafeHttpTransport {
            val payload = payloads[calls.getAndIncrement().coerceAtMost(payloads.lastIndex)]
            NetworkResult.Success(
                NetworkResponse(
                    statusCode = 200,
                    headers = emptyMap(),
                    body = payload.toByteArray(),
                    redactedUrl = "https://config.invalid/…",
                ),
            )
        }
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = AndroidLegacySourceRepository.createForTest(directory, transport, repositoryScope)
            withTimeout(5_000L) { repository.state.first { state -> state.isInitialized } }

            val first = repository.importRemoteUrl("https://config.invalid/config.json?channel=stable")
                .imported.single()
            val firstSite = first.fields.sites.single()
            repository.setSourceEnabled(first.id, firstSite.sourceKey, enabled = false)

            val refreshed = repository.importRemoteUrl("https://config.invalid/config.json?channel=stable")
                .imported.single()
            val refreshedSite = refreshed.fields.sites.single()

            assertEquals(first.id, refreshed.id)
            assertEquals(firstSite.sourceKey, refreshedSite.sourceKey)
            assertEquals("Updated", refreshedSite.name)
            assertEquals(SourceUserActivation.DISABLED, refreshedSite.state.userActivation)
            assertEquals(1, repository.state.value.configurations.size)
        } finally {
            repositoryScope.cancel()
            directory.deleteRecursively()
        }
    }
}
