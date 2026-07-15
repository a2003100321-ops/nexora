package com.nexora.source.testkit

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyConfigCorpusTest {
    @Test
    fun checkedInCorpusIsSyntheticTextOnlyAndValid() {
        val root = Path.of(requireNotNull(System.getProperty(LegacyConfigCorpus.CORPUS_PATH_PROPERTY)))

        val manifest = LegacyConfigCorpus.loadAndValidate(root)

        assertEquals(1, manifest.schemaVersion)
        assertTrue(manifest.syntheticOnly)
        assertEquals("FORBIDDEN", manifest.pluginExecution)
        assertTrue(manifest.cases.size >= 6)
        assertTrue(manifest.cases.all(CorpusCase::synthetic))
        assertEquals(
            CorpusExpectation.DESCRIBE_ONLY,
            manifest.cases.single { case -> case.id == "plugin-descriptors-only" }.expectation,
        )
    }

    @Test
    fun forbiddenPluginAndBinaryExtensionsAreRejectedWithoutBeingFixtures() {
        val root = Files.createTempDirectory("legacy-corpus-forbidden")
        val fixture = root.resolve("fixtures/minimal.json")
        fixture.parent.createDirectories()
        fixture.writeText("{}")
        root.resolve("fixtures/never-open.jar").writeText("plain text marker; never a plugin")

        val issues = LegacyConfigCorpus.validate(root, manifestFor(fixture, root))

        assertTrue(issues.any { issue ->
            issue.code == "FORBIDDEN_EXTENSION" && issue.detail.contains("never-open.jar")
        })
    }

    @Test
    fun traversalPathIsRejected() {
        val root = Files.createTempDirectory("legacy-corpus-traversal")
        val outside = root.parent.resolve("outside.json")
        val manifest = CorpusManifest(
            schemaVersion = 1,
            suite = "legacy-config",
            syntheticOnly = true,
            pluginExecution = "FORBIDDEN",
            cases = listOf(
                CorpusCase(
                    id = "traversal",
                    relativePath = "../outside.json",
                    synthetic = true,
                    kind = CorpusKind.VALID_CONFIG,
                    expectation = CorpusExpectation.ACCEPT,
                    sha256 = "0".repeat(64),
                ),
            ),
        )

        val issues = LegacyConfigCorpus.validate(root, manifest)

        assertTrue(issues.any { issue -> issue.code == "UNSAFE_PATH" })
        Files.deleteIfExists(outside)
    }

    @Test
    fun contentTamperingIsDetectedByHash() {
        val root = Files.createTempDirectory("legacy-corpus-tamper")
        val fixture = root.resolve("fixtures/minimal.json")
        fixture.parent.createDirectories()
        fixture.writeText("{\"changed\":true}")
        val manifest = manifestFor(fixture, root).copy(
            cases = manifestFor(fixture, root).cases.map { case -> case.copy(sha256 = "0".repeat(64)) },
        )

        val issues = LegacyConfigCorpus.validate(root, manifest)

        assertTrue(issues.any { issue -> issue.code == "HASH_MISMATCH" })
    }

    private fun manifestFor(fixture: Path, root: Path): CorpusManifest {
        val bytes = Files.readAllBytes(fixture)
        return CorpusManifest(
            schemaVersion = 1,
            suite = "legacy-config",
            syntheticOnly = true,
            pluginExecution = "FORBIDDEN",
            cases = listOf(
                CorpusCase(
                    id = "minimal",
                    relativePath = root.relativize(fixture).toString().replace('\\', '/'),
                    synthetic = true,
                    kind = CorpusKind.VALID_CONFIG,
                    expectation = CorpusExpectation.ACCEPT,
                    sha256 = MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { byte -> "%02x".format(byte) },
                ),
            ),
        )
    }
}
