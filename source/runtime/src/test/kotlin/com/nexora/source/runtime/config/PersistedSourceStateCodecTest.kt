package com.nexora.source.runtime.config

import com.nexora.source.api.ConfigImportKind
import com.nexora.source.api.CompatibilityDiagnostic
import com.nexora.source.api.CompatibilityIssueCode
import com.nexora.source.api.CompatibilitySeverity
import com.nexora.source.api.LegacyConfigId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistedSourceStateCodecTest {
    @Test
    fun stateRoundTripsWithoutLosingRawConfiguration() {
        val expected = PersistedSourceState(
            onboardingCompleted = true,
            configurations = listOf(
                PersistedConfiguration(
                    id = LegacyConfigId("config-1"),
                    displayName = "本地配置",
                    importKind = ConfigImportKind.LOCAL_FILE,
                    origin = null,
                    originDisplay = null,
                    originalJson = "{\"unknown\":{\"kept\":true}}",
                    expandedJson = "{\"unknown\":{\"kept\":true},\"sites\":[]}",
                    disabledLegacyKeys = setOf("legacy-source-2"),
                    diagnostics = listOf(
                        CompatibilityDiagnostic(
                            code = CompatibilityIssueCode.SITE_INVALID,
                            severity = CompatibilitySeverity.WARNING,
                            jsonPath = "$.sites[1]",
                            userMessage = "该站点格式错误，已跳过。",
                            recoverable = true,
                        ),
                    ),
                ),
            ),
        )

        val actual = PersistedSourceStateCodec.decode(PersistedSourceStateCodec.encode(expected))

        assertEquals(expected, actual)
    }

    @Test
    fun unsupportedSchemaIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            PersistedSourceStateCodec.decode(
                """{"schemaVersion":2,"onboardingCompleted":false,"configurations":[]}""",
            )
        }
    }

    @Test
    fun fileStoreRoundTripsInPrivateDirectory() {
        val root = Files.createTempDirectory("nexora-source-store").toFile()
        val store = FileLegacySourceStore(root)
        val expected = PersistedSourceState(onboardingCompleted = true)

        store.write(expected)

        assertEquals(expected, store.read())
    }
}
