package com.nexora.source.testkit

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Loads and validates the synthetic legacy-config corpus without parsing a legacy config or
 * loading any plugin payload. All fixture files are treated as untrusted text.
 */
public object LegacyConfigCorpus {
    public const val MANIFEST_FILE: String = "manifest.json"
    public const val CORPUS_PATH_PROPERTY: String = "nexora.legacyConfigCorpus"

    private const val SCHEMA_VERSION = 1
    private const val SUITE = "legacy-config"
    private const val PLUGIN_EXECUTION_FORBIDDEN = "FORBIDDEN"
    private const val MAX_MANIFEST_BYTES = 256 * 1024L
    private const val MAX_FIXTURE_BYTES = 256 * 1024L

    private val allowedFixtureExtensions = setOf("json", "txt")
    private val forbiddenExtensions = setOf(
        "aar",
        "apk",
        "class",
        "dex",
        "dll",
        "dylib",
        "exe",
        "jar",
        "js",
        "py",
        "so",
        "zip",
    )
    private val caseIdPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    public fun loadAndValidate(root: Path): CorpusManifest {
        val absoluteRoot = root.toAbsolutePath().normalize()
        require(Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Legacy config corpus directory does not exist: $absoluteRoot"
        }
        require(!Files.isSymbolicLink(absoluteRoot)) {
            "Legacy config corpus root must not be a symbolic link: $absoluteRoot"
        }

        val manifestPath = absoluteRoot.resolve(MANIFEST_FILE)
        require(Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            "Legacy config corpus manifest does not exist: $manifestPath"
        }
        require(!Files.isSymbolicLink(manifestPath)) {
            "Legacy config corpus manifest must not be a symbolic link: $manifestPath"
        }
        val manifestBytes = Files.readAllBytes(manifestPath)
        require(manifestBytes.size <= MAX_MANIFEST_BYTES) {
            "Legacy config corpus manifest exceeds $MAX_MANIFEST_BYTES bytes"
        }

        val manifest = ManifestJson.decode(decodeUtf8(manifestBytes, MANIFEST_FILE))
        val issues = validate(absoluteRoot, manifest)
        require(issues.isEmpty()) {
            issues.joinToString(
                prefix = "Legacy config corpus is invalid:\n",
                separator = "\n",
            ) { issue -> "- ${issue.code}: ${issue.detail}" }
        }
        return manifest
    }

    public fun validate(root: Path, manifest: CorpusManifest): List<CorpusValidationIssue> {
        val absoluteRoot = root.toAbsolutePath().normalize()
        val issues = mutableListOf<CorpusValidationIssue>()

        fun issue(code: String, detail: String) {
            issues += CorpusValidationIssue(code, detail)
        }

        if (manifest.schemaVersion != SCHEMA_VERSION) {
            issue("UNSUPPORTED_SCHEMA", "Expected schema $SCHEMA_VERSION, got ${manifest.schemaVersion}")
        }
        if (manifest.suite != SUITE) {
            issue("WRONG_SUITE", "Expected '$SUITE', got '${manifest.suite}'")
        }
        if (!manifest.syntheticOnly || manifest.cases.any { case -> !case.synthetic }) {
            issue("NON_SYNTHETIC_INPUT", "M0-M2 corpus accepts synthetic fixtures only")
        }
        if (manifest.pluginExecution != PLUGIN_EXECUTION_FORBIDDEN) {
            issue("PLUGIN_EXECUTION_NOT_FORBIDDEN", "pluginExecution must be FORBIDDEN")
        }
        if (manifest.cases.isEmpty()) {
            issue("EMPTY_CORPUS", "At least one fixture is required")
        }

        manifest.cases.groupBy(CorpusCase::id).filterValues { cases -> cases.size > 1 }.keys
            .forEach { id -> issue("DUPLICATE_ID", id) }
        manifest.cases.groupBy(CorpusCase::relativePath).filterValues { cases -> cases.size > 1 }.keys
            .forEach { path -> issue("DUPLICATE_PATH", path) }

        val listedPaths = mutableSetOf<Path>()
        manifest.cases.forEach { case ->
            if (!caseIdPattern.matches(case.id)) {
                issue("INVALID_ID", case.id)
            }
            if (!sha256Pattern.matches(case.sha256)) {
                issue("INVALID_SHA256", "${case.id}: expected 64 lowercase hexadecimal characters")
            }

            val relativePath = safeRelativePath(case.relativePath)
            if (relativePath == null) {
                issue("UNSAFE_PATH", "${case.id}: ${case.relativePath}")
                return@forEach
            }
            if (relativePath.firstOrNull()?.toString() != "fixtures") {
                issue("OUTSIDE_FIXTURES", "${case.id}: ${case.relativePath}")
            }

            val extension = extensionOf(relativePath)
            if (extension in forbiddenExtensions) {
                issue("FORBIDDEN_EXTENSION", "${case.id}: .$extension files are never opened")
                return@forEach
            }
            if (extension !in allowedFixtureExtensions) {
                issue("NON_TEXT_EXTENSION", "${case.id}: .$extension is not an allowed fixture extension")
                return@forEach
            }

            val fixturePath = absoluteRoot.resolve(relativePath).normalize()
            if (!fixturePath.startsWith(absoluteRoot)) {
                issue("UNSAFE_PATH", "${case.id}: ${case.relativePath}")
                return@forEach
            }
            listedPaths.add(fixturePath)
            if (Files.isSymbolicLink(fixturePath)) {
                issue("SYMLINK_FIXTURE", "${case.id}: ${case.relativePath}")
                return@forEach
            }
            if (!Files.isRegularFile(fixturePath, LinkOption.NOFOLLOW_LINKS)) {
                issue("MISSING_FIXTURE", "${case.id}: ${case.relativePath}")
                return@forEach
            }

            val size = Files.size(fixturePath)
            if (size == 0L || size > MAX_FIXTURE_BYTES) {
                issue("INVALID_SIZE", "${case.id}: $size bytes")
                return@forEach
            }

            val bytes = Files.readAllBytes(fixturePath)
            val text = runCatching { decodeUtf8(bytes, case.relativePath) }.getOrElse { error ->
                issue("INVALID_UTF8", "${case.id}: ${error.message}")
                return@forEach
            }
            if (text.any { char -> char == '\u0000' || (char < ' ' && char !in "\t\n\r") }) {
                issue("CONTROL_CHARACTER", "${case.id}: contains a disallowed control character")
            }
            val actualHash = sha256(bytes)
            if (case.sha256 != actualHash) {
                issue("HASH_MISMATCH", "${case.id}: expected ${case.sha256}, got $actualHash")
            }
        }

        if (Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(absoluteRoot).use { paths ->
                paths.filter { path -> path != absoluteRoot }.forEach { path ->
                    val normalized = path.toAbsolutePath().normalize()
                    val relative = absoluteRoot.relativize(normalized).toString().replace('\\', '/')
                    if (Files.isSymbolicLink(path)) {
                        issue("SYMLINK_IN_CORPUS", relative)
                        return@forEach
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@forEach

                    val extension = extensionOf(path)
                    if (extension in forbiddenExtensions) {
                        issue("FORBIDDEN_EXTENSION", "$relative is forbidden and was not opened")
                    } else if (
                        path.fileName.toString() != MANIFEST_FILE &&
                        relative != "README.md" &&
                        normalized !in listedPaths
                    ) {
                        issue("UNLISTED_FILE", relative)
                    }
                }
            }
        }

        return issues.distinct()
    }

    private fun safeRelativePath(value: String): Path? {
        if (value.isBlank() || '\\' in value) return null
        val path = runCatching { Path.of(value) }.getOrNull() ?: return null
        if (path.isAbsolute || path.any { segment -> segment.toString() == ".." }) return null
        return path.normalize().takeIf { normalized -> normalized.toString().replace('\\', '/') == value }
    }

    private fun extensionOf(path: Path): String =
        path.fileName.toString().substringAfterLast('.', missingDelimiterValue = "").lowercase()

    private fun decodeUtf8(bytes: ByteArray, label: String): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .also { text -> require(!text.startsWith('\uFEFF')) { "$label must not contain a UTF-8 BOM" } }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private object ManifestJson {
    fun decode(text: String): CorpusManifest {
        val root = JsonParser(text).parse().objectValue("manifest")
        root.requireKeys("schemaVersion", "suite", "safety", "fixtures")

        val safety = root.required("safety").objectValue("safety")
        safety.requireKeys("syntheticOnly", "pluginExecution")

        val fixtures = root.required("fixtures").arrayValue("fixtures").mapIndexed { index, value ->
            val item = value.objectValue("fixtures[$index]")
            item.requireKeys("id", "path", "synthetic", "kind", "expectation", "sha256")
            CorpusCase(
                id = item.required("id").stringValue("fixtures[$index].id"),
                relativePath = item.required("path").stringValue("fixtures[$index].path"),
                synthetic = item.required("synthetic").booleanValue("fixtures[$index].synthetic"),
                kind = item.required("kind").enumValue<CorpusKind>("fixtures[$index].kind"),
                expectation = item.required("expectation")
                    .enumValue<CorpusExpectation>("fixtures[$index].expectation"),
                sha256 = item.required("sha256").stringValue("fixtures[$index].sha256"),
            )
        }

        return CorpusManifest(
            schemaVersion = root.required("schemaVersion").intValue("schemaVersion"),
            suite = root.required("suite").stringValue("suite"),
            syntheticOnly = safety.required("syntheticOnly").booleanValue("safety.syntheticOnly"),
            pluginExecution = safety.required("pluginExecution").stringValue("safety.pluginExecution"),
            cases = fixtures,
        )
    }

    private fun Map<String, JsonValue>.required(key: String): JsonValue =
        get(key) ?: error("Manifest is missing '$key'")

    private fun Map<String, JsonValue>.requireKeys(vararg allowed: String) {
        val unknown = keys - allowed.toSet()
        require(unknown.isEmpty()) { "Manifest contains unknown fields: ${unknown.sorted().joinToString()}" }
    }

    private fun JsonValue.objectValue(label: String): Map<String, JsonValue> =
        (this as? JsonObject)?.values ?: error("$label must be an object")

    private fun JsonValue.arrayValue(label: String): List<JsonValue> =
        (this as? JsonArray)?.values ?: error("$label must be an array")

    private fun JsonValue.stringValue(label: String): String =
        (this as? JsonString)?.value ?: error("$label must be a string")

    private fun JsonValue.booleanValue(label: String): Boolean =
        (this as? JsonBoolean)?.value ?: error("$label must be a boolean")

    private fun JsonValue.intValue(label: String): Int =
        (this as? JsonNumber)?.token?.toIntOrNull() ?: error("$label must be an integer")

    private inline fun <reified T : Enum<T>> JsonValue.enumValue(label: String): T {
        val name = stringValue(label)
        return enumValues<T>().firstOrNull { value -> value.name == name }
            ?: error("$label has unsupported value '$name'")
    }
}

private sealed interface JsonValue
private data class JsonObject(val values: Map<String, JsonValue>) : JsonValue
private data class JsonArray(val values: List<JsonValue>) : JsonValue
private data class JsonString(val value: String) : JsonValue
private data class JsonNumber(val token: String) : JsonValue
private data class JsonBoolean(val value: Boolean) : JsonValue
private data object JsonNull : JsonValue

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue {
        val value = parseValue()
        skipWhitespace()
        require(index == text.length) { "Unexpected trailing content at offset $index" }
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        require(index < text.length) { "Unexpected end of JSON at offset $index" }
        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(parseString())
            't' -> parseLiteral("true", JsonBoolean(true))
            'f' -> parseLiteral("false", JsonBoolean(false))
            'n' -> parseLiteral("null", JsonNull)
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected JSON token '${text[index]}' at offset $index")
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        skipWhitespace()
        val values = linkedMapOf<String, JsonValue>()
        if (consume('}')) return JsonObject(values)
        while (true) {
            skipWhitespace()
            require(index < text.length && text[index] == '"') { "Expected object key at offset $index" }
            val key = parseString()
            require(key !in values) { "Duplicate object key '$key'" }
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            if (consume('}')) return JsonObject(values)
            expect(',')
        }
    }

    private fun parseArray(): JsonArray {
        expect('[')
        skipWhitespace()
        val values = mutableListOf<JsonValue>()
        if (consume(']')) return JsonArray(values)
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (consume(']')) return JsonArray(values)
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when (char) {
                '"' -> return result.toString()
                '\\' -> {
                    require(index < text.length) { "Unterminated escape at offset $index" }
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= text.length) { "Incomplete unicode escape at offset $index" }
                            val value = text.substring(index, index + 4).toIntOrNull(16)
                                ?: error("Invalid unicode escape at offset $index")
                            result.append(value.toChar())
                            index += 4
                        }
                        else -> error("Invalid escape '$escaped' at offset ${index - 1}")
                    }
                }
                else -> {
                    require(char >= ' ') { "Unescaped control character at offset ${index - 1}" }
                    result.append(char)
                }
            }
        }
        error("Unterminated string")
    }

    private fun parseNumber(): JsonNumber {
        val start = index
        if (consume('-')) Unit
        require(index < text.length) { "Incomplete number at offset $start" }
        if (consume('0')) {
            require(index >= text.length || text[index] !in '0'..'9') { "Leading zero at offset $start" }
        } else {
            require(index < text.length && text[index] in '1'..'9') { "Invalid number at offset $start" }
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (consume('.')) {
            require(index < text.length && text[index] in '0'..'9') { "Invalid fraction at offset $start" }
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] in "eE") {
            index++
            if (index < text.length && text[index] in "+-") index++
            require(index < text.length && text[index] in '0'..'9') { "Invalid exponent at offset $start" }
            while (index < text.length && text[index] in '0'..'9') index++
        }
        return JsonNumber(text.substring(start, index))
    }

    private fun <T : JsonValue> parseLiteral(token: String, value: T): T {
        require(text.startsWith(token, index)) { "Expected '$token' at offset $index" }
        index += token.length
        return value
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in " \t\r\n") index++
    }

    private fun consume(expected: Char): Boolean {
        if (index >= text.length || text[index] != expected) return false
        index++
        return true
    }

    private fun expect(expected: Char) {
        require(consume(expected)) { "Expected '$expected' at offset $index" }
    }
}
