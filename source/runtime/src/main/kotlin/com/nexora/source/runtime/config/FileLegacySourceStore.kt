package com.nexora.source.runtime.config

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class FileLegacySourceStore(
    directory: File,
) {
    private val storeDirectory: File = directory.resolve("nexora-source-config")
    private val storeFile: File = storeDirectory.resolve("state-v1.json")
    private val temporaryFile: File = storeDirectory.resolve("state-v1.json.tmp")

    fun read(): PersistedSourceState {
        if (!storeFile.exists()) return PersistedSourceState()
        require(storeFile.isFile && storeFile.length() <= MAX_STORE_BYTES) {
            "Stored source state is too large or invalid"
        }
        val bytes = storeFile.readBytes()
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        return PersistedSourceStateCodec.decode(text)
    }

    fun write(state: PersistedSourceState) {
        val bytes = PersistedSourceStateCodec.encode(state).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STORE_BYTES) { "Stored source state is too large" }
        storeDirectory.mkdirs()
        require(storeDirectory.isDirectory) { "Unable to create source state directory" }
        FileOutputStream(temporaryFile).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporaryFile.toPath(),
                storeFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile.toPath(),
                storeFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val MAX_STORE_BYTES: Int = 8 * 1024 * 1024
    }
}
