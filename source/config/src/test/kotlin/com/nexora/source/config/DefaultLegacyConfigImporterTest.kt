package com.nexora.source.config

import com.nexora.source.api.CompatibilityIssueCode
import com.nexora.source.api.CompatibilitySeverity
import com.nexora.source.api.ConfigImportKind
import com.nexora.source.api.SourceAvailability
import com.nexora.source.api.SourceSearchCapability
import com.nexora.source.api.SourceUserActivation
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DefaultLegacyConfigImporterTest {
    private val corpusRoot: Path
        get() = Path.of(requireNotNull(System.getProperty(CORPUS_PATH_PROPERTY)))

    @Test
    fun fullGoldenConfigParsesAllSupportedFieldsAndNormalizesRelativeUrls() = runTest {
        val importer = DefaultLegacyConfigImporter()
        val input = fixtureText("fixtures/valid/full-golden.json")

        val result = importer.importConfig(
            text = input,
            displayName = "完整金样",
            kind = ConfigImportKind.REMOTE_URL,
            origin = "https://example.invalid/config/main.json",
        )

        assertTrue(result.succeeded)
        assertEquals(1, result.imported.size)
        val snapshot = result.imported.single()
        val fields = snapshot.fields
        assertEquals("完整金样", snapshot.displayName)
        assertEquals("https://example.invalid/…", snapshot.originDisplay)
        assertEquals("https://example.invalid/config/plugins/global.jar", fields.spider)
        assertEquals(listOf(0, 1, 4), fields.sites.map { site -> site.type })
        assertEquals("https://example.invalid/config/api/xml", fields.sites[0].api)
        assertEquals("https://example.invalid/api/json", fields.sites[1].api)
        assertEquals("https://example.invalid/config/play", fields.sites[2].playUrl)
        assertEquals("https://example.invalid/config/plugins/global.jar", fields.sites[0].jar)
        assertEquals("{\"mode\":\"safe\"}", fields.sites[0].ext)
        assertEquals(3_000L, fields.sites[0].timeoutMillis)
        assertFalse(fields.sites[0].quickSearch)
        assertEquals(listOf("Movie"), fields.sites[0].categories)
        assertEquals(mapOf("X-Test" to "one"), fields.sites[0].requestHeaders)
        assertEquals(SourceAvailability.AVAILABLE, fields.sites[0].state.availability)
        assertEquals("https://example.invalid/config/parse", fields.parses.single().url)
        assertEquals(1, fields.rules.size)
        assertEquals(1, fields.headers.size)
        assertEquals(listOf("example.invalid"), fields.hosts)
        assertEquals(listOf("qq"), fields.flags)
        assertEquals("https://example.invalid/config/danmaku", fields.danmaku)
        assertEquals(1, fields.doh.size)
        assertEquals(1, fields.proxy.size)
        assertEquals(listOf("ads.example.invalid"), fields.ads)
        assertEquals("https://example.invalid/config/wall.jpg", fields.wallpaper)
        assertEquals("https://example.invalid/logo.png", fields.logo)
        assertEquals("\"Synthetic notice\"", fields.notice?.canonicalText)
        assertEquals(1, fields.lives.size)
        assertEquals(
            "{\"a\":[3,2,1],\"z\":1}",
            snapshot.unknownTopLevelFields.getValue("futureFeature").canonicalText,
        )
        assertEquals(
            listOf(
                CompatibilityIssueCode.POLICY_PRESERVED_NOT_EXECUTED,
                CompatibilityIssueCode.POLICY_PRESERVED_NOT_EXECUTED,
            ),
            result.diagnostics.map { diagnostic -> diagnostic.code },
        )
        assertTrue(result.diagnostics.all { diagnostic -> diagnostic.severity == CompatibilitySeverity.INFO })

        val roundTrip = importer.importConfig(
            text = snapshot.original.canonicalText,
            displayName = "规范化回读",
            kind = ConfigImportKind.REMOTE_URL,
            origin = snapshot.origin,
        ).imported.single()
        assertEquals(snapshot.id, roundTrip.id)
        assertEquals(snapshot.original, roundTrip.original)
        assertEquals(snapshot.unknownTopLevelFields, roundTrip.unknownTopLevelFields)
    }

    @Test
    fun markerBase64PlainBase64And2423AesGoldenVectorsDecode() = runTest {
        val importer = DefaultLegacyConfigImporter()

        val marker = importer.importConfig(
            fixtureText("fixtures/decoder/base64-marker.txt"),
            "marker",
            ConfigImportKind.PASTED_TEXT,
        )
        val plain = importer.importConfig(
            fixtureText("fixtures/decoder/base64-plain.txt"),
            "plain",
            ConfigImportKind.PASTED_TEXT,
        )
        val aes = importer.importConfig(
            fixtureText("fixtures/decoder/aes-2423.txt"),
            "aes",
            ConfigImportKind.PASTED_TEXT,
        )

        assertTrue(marker.succeeded)
        assertTrue(plain.succeeded)
        assertEquals(marker.imported.single().id, plain.imported.single().id)
        assertEquals("encoded", marker.imported.single().fields.sites.single().legacyKey)
        assertTrue(aes.succeeded)
        assertEquals("{\"sites\":[]}", aes.imported.single().original.canonicalText)
    }

    @Test
    fun malformedEncodingUtf8SizeAndDepthReturnStableFatalCodes() = runTest {
        val importer = DefaultLegacyConfigImporter()

        assertFatal(
            importer.importConfig(
                fixtureText("fixtures/malformed/truncated.json"),
                "truncated",
                ConfigImportKind.LOCAL_FILE,
            ),
            CompatibilityIssueCode.INVALID_JSON,
        )
        assertFatal(
            importer.importConfig(
                fixtureText("fixtures/malformed/invalid-2423.txt"),
                "bad-aes",
                ConfigImportKind.LOCAL_FILE,
            ),
            CompatibilityIssueCode.INVALID_2423_AES,
        )
        assertFatal(
            importer.importConfig(
                byteArrayOf(0xC3.toByte(), 0x28),
                "bad-utf8",
                ConfigImportKind.LOCAL_FILE,
            ),
            CompatibilityIssueCode.INVALID_UTF8,
        )
        assertFatal(
            importer.importConfig(
                ByteArray(DefaultLegacyConfigImporter.MAX_INPUT_BYTES + 1) { ' '.code.toByte() },
                "too-large",
                ConfigImportKind.LOCAL_FILE,
            ),
            CompatibilityIssueCode.INPUT_TOO_LARGE,
        )
        assertFatal(
            importer.importConfig(
                "[".repeat(DefaultLegacyConfigImporter.MAX_JSON_DEPTH + 1) +
                    "0" +
                    "]".repeat(DefaultLegacyConfigImporter.MAX_JSON_DEPTH + 1),
                "too-deep",
                ConfigImportKind.PASTED_TEXT,
            ),
            CompatibilityIssueCode.INVALID_JSON,
        )
        assertFatal(
            importer.importConfig(
                "这不是 Base64!",
                "bad-base64",
                ConfigImportKind.PASTED_TEXT,
            ),
            CompatibilityIssueCode.INVALID_BASE64,
        )
    }

    @Test
    fun injectedRemoteObjectArraysExpandWithPerReferenceFailureIsolation() = runTest {
        val origin = "https://example.invalid/config/main.json"
        val payloads = mapOf(
            "https://example.invalid/config/remote/remote-sites.json" to
                fixtureBytes("fixtures/remote/remote-sites.json"),
            "https://example.invalid/config/remote/remote-parses.json" to
                fixtureBytes("fixtures/remote/remote-parses.json"),
            "https://example.invalid/config/remote/remote-rules.json" to
                fixtureBytes("fixtures/remote/remote-rules.json"),
        )
        val requested = mutableListOf<String>()
        val importer = DefaultLegacyConfigImporter(
            RemoteArrayLoader { resolvedUrl ->
                requested += resolvedUrl
                payloads[resolvedUrl] ?: error("synthetic missing payload")
            },
        )

        val result = importer.importConfig(
            fixtureText("fixtures/valid/remote-arrays.json"),
            "remote-arrays",
            ConfigImportKind.REMOTE_URL,
            origin,
        )

        assertTrue(result.succeeded)
        val snapshot = result.imported.single()
        assertEquals(listOf("local", "remote-one", "remote-two"), snapshot.fields.sites.map { it.legacyKey })
        assertEquals("https://example.invalid/config/api/remote-one", snapshot.fields.sites[1].api)
        assertEquals("https://example.invalid/config/parse/remote", snapshot.fields.parses.single().url)
        assertEquals(3, snapshot.fields.rules.size)
        assertEquals(4, requested.size)
        assertTrue(requested.contains("https://example.invalid/config/remote/missing.json"))
        assertEquals(
            listOf(CompatibilityIssueCode.REMOTE_REFERENCE_FAILED),
            result.diagnostics.map { diagnostic -> diagnostic.code },
        )
        assertTrue(result.diagnostics.single().recoverable)
        assertTrue(result.diagnostics.single().userMessage.contains("其他有效条目"))
        assertTrue(snapshot.expanded.canonicalText.contains("remote-one"))

        val failClosed = DefaultLegacyConfigImporter().importConfig(
            fixtureText("fixtures/valid/remote-arrays.json"),
            "remote-arrays-disabled",
            ConfigImportKind.REMOTE_URL,
            origin,
        )
        assertEquals(listOf("local"), failClosed.imported.single().fields.sites.map { it.legacyKey })
        assertEquals(4, failClosed.diagnostics.count { it.code == CompatibilityIssueCode.REMOTE_REFERENCE_FAILED })
    }

    @Test
    fun malformedSitesAndParsesAreIsolatedWithoutDroppingValidNeighbors() = runTest {
        val result = DefaultLegacyConfigImporter().importConfig(
            fixtureText("fixtures/malformed/bad-site-and-parse.json"),
            "bad-items",
            ConfigImportKind.LOCAL_FILE,
        )

        assertTrue(result.succeeded)
        val fields = result.imported.single().fields
        assertEquals(listOf("good-site", "good-after-errors"), fields.sites.map { it.legacyKey })
        assertEquals(listOf("good-parse", "good-after-errors"), fields.parses.map { it.name })
        assertEquals(2, result.diagnostics.count { it.code == CompatibilityIssueCode.SITE_INVALID })
        assertEquals(2, result.diagnostics.count { it.code == CompatibilityIssueCode.PARSE_INVALID })
        assertTrue(result.diagnostics.all { diagnostic -> diagnostic.recoverable })
    }

    @Test
    fun legacySearchabilityDoesNotSilentlyDisableAConfiguredSource() = runTest {
        val snapshot = DefaultLegacyConfigImporter().importConfig(
            text = """{"sites":[{"key":"no-search","type":1,"searchable":0,"timeout":999}]}""",
            displayName = "search-state",
            kind = ConfigImportKind.PASTED_TEXT,
        ).imported.single()

        val site = snapshot.fields.sites.single()
        assertEquals(SourceSearchCapability.UNSUPPORTED, site.state.searchCapability)
        assertEquals(SourceUserActivation.ENABLED, site.state.userActivation)
        assertEquals(60_000L, site.timeoutMillis)
    }

    @Test
    fun remoteOriginDisplayNeverContainsCredentialsPathQueryOrFragment() = runTest {
        val snapshot = DefaultLegacyConfigImporter().importConfig(
            text = """{"sites":[]}""",
            displayName = "redaction",
            kind = ConfigImportKind.REMOTE_URL,
            origin = "https://alice:secret@example.invalid:8443/private/config.json?token=value#fragment",
        ).imported.single()

        assertEquals("https://example.invalid:8443/…", snapshot.originDisplay)
        assertFalse(snapshot.originDisplay.orEmpty().contains("secret"))
        assertFalse(snapshot.originDisplay.orEmpty().contains("token"))
    }

    @Test
    fun validRemoteOriginKeepsConfigurationAndSourceIdentityAcrossContentUpdates() = runTest {
        val importer = DefaultLegacyConfigImporter()
        val origin = "HTTPS://EXAMPLE.INVALID:443/config/main.json?channel=stable"
        val first = importer.importConfig(
            text = """{"sites":[{"key":"one","name":"First","type":1,"api":"https://source.invalid/api"}]}""",
            displayName = "first",
            kind = ConfigImportKind.REMOTE_URL,
            origin = origin,
        ).imported.single()
        val updated = importer.importConfig(
            text = """{"future":true,"sites":[{"key":"one","name":"Updated","type":1,"api":"https://source.invalid/api"}]}""",
            displayName = "updated",
            kind = ConfigImportKind.REMOTE_URL,
            origin = "https://example.invalid/config/main.json?channel=stable",
        ).imported.single()
        val differentOrigin = importer.importConfig(
            text = updated.original.canonicalText,
            displayName = "different",
            kind = ConfigImportKind.REMOTE_URL,
            origin = "https://example.invalid/config/other.json?channel=stable",
        ).imported.single()

        assertEquals(first.id, updated.id)
        assertEquals(first.fields.sites.single().sourceKey, updated.fields.sites.single().sourceKey)
        assertFalse(updated.id.value.contains("example.invalid"))
        assertEquals("https://example.invalid/…", updated.originDisplay)
        assertFalse(updated.id == differentOrigin.id)
    }

    @Test
    fun pastedAndLocalConfigurationIdentityStillTracksCanonicalContent() = runTest {
        val importer = DefaultLegacyConfigImporter()
        val first = importer.importConfig(
            text = """{"sites":[]}""",
            displayName = "first",
            kind = ConfigImportKind.PASTED_TEXT,
        ).imported.single()
        val updated = importer.importConfig(
            text = """{"future":true,"sites":[]}""",
            displayName = "updated",
            kind = ConfigImportKind.PASTED_TEXT,
        ).imported.single()

        assertFalse(first.id == updated.id)
    }

    @Test
    fun excessiveSiteCountIsBoundedWithARecoverableDiagnostic() = runTest {
        val sites = (0..DefaultLegacyConfigImporter.MAX_SITES_PER_CONFIG).joinToString(",") { index ->
            """{"key":"site-$index","name":"站点 $index","type":1,"api":"https://site-$index.invalid/api"}"""
        }

        val result = DefaultLegacyConfigImporter().importConfig(
            text = """{"sites":[$sites]}""",
            displayName = "站点上限",
            kind = ConfigImportKind.PASTED_TEXT,
        )

        assertEquals(
            DefaultLegacyConfigImporter.MAX_SITES_PER_CONFIG,
            result.imported.single().fields.sites.size,
        )
        assertTrue(
            result.diagnostics.any { diagnostic ->
                diagnostic.code == CompatibilityIssueCode.RESOURCE_LIMIT && diagnostic.recoverable
            },
        )
    }

    @Test
    fun unknownTopLevelFieldsSurviveCanonicalRoundTrip() = runTest {
        val importer = DefaultLegacyConfigImporter()
        val first = importer.importConfig(
            fixtureText("fixtures/valid/unknown-fields.json"),
            "unknown",
            ConfigImportKind.LOCAL_FILE,
        ).imported.single()

        assertEquals(setOf("futureObject", "futureScalar"), first.unknownTopLevelFields.keys)
        assertEquals(
            "{\"a\":{\"first\":1,\"second\":2},\"z\":true}",
            first.unknownTopLevelFields.getValue("futureObject").canonicalText,
        )
        assertEquals("\"preserve-me\"", first.unknownTopLevelFields.getValue("futureScalar").canonicalText)

        val second = importer.importConfig(
            first.original.canonicalText,
            "unknown-round-trip",
            ConfigImportKind.PASTED_TEXT,
        ).imported.single()
        assertEquals(first.original, second.original)
        assertEquals(first.unknownTopLevelFields, second.unknownTopLevelFields)
    }

    private fun fixtureText(relativePath: String): String = corpusRoot.resolve(relativePath).readText()

    private fun fixtureBytes(relativePath: String): ByteArray = corpusRoot.resolve(relativePath).readBytes()

    private fun assertFatal(
        result: com.nexora.source.api.LegacyConfigImportResult,
        expectedCode: CompatibilityIssueCode,
    ) {
        assertFalse(result.succeeded)
        assertTrue(result.imported.isEmpty())
        val diagnostic = assertNotNull(result.diagnostics.singleOrNull())
        assertEquals(expectedCode, diagnostic.code)
        assertEquals(CompatibilitySeverity.ERROR, diagnostic.severity)
        assertFalse(diagnostic.recoverable)
        assertTrue(diagnostic.userMessage.any { character -> character.code > 127 })
    }

    private companion object {
        const val CORPUS_PATH_PROPERTY: String = "nexora.legacyConfigCorpus"
    }
}
