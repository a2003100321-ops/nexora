package com.nexora.feature.sources

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal const val MAX_LOCAL_CONFIG_BYTES: Int = 2 * 1024 * 1024

internal sealed interface StrictTextReadResult {
    data class Success(val text: String) : StrictTextReadResult

    data object TooLarge : StrictTextReadResult

    data object InvalidUtf8 : StrictTextReadResult

    data object ReadFailed : StrictTextReadResult
}

/** Reads at most one byte beyond [maxBytes] and never closes [input]. */
internal fun readStrictUtf8Text(
    input: InputStream,
    maxBytes: Int = MAX_LOCAL_CONFIG_BYTES,
): StrictTextReadResult {
    require(maxBytes >= 0) { "maxBytes must not be negative" }

    val bytes = ByteArray(maxBytes + 1)
    var total = 0

    try {
        while (total < bytes.size) {
            val count = input.read(bytes, total, bytes.size - total)
            if (count < 0) break
            if (count == 0) continue
            total += count
        }
    } catch (_: IOException) {
        return StrictTextReadResult.ReadFailed
    }

    if (total > maxBytes) return StrictTextReadResult.TooLarge

    val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    return try {
        val decoded = decoder.decode(ByteBuffer.wrap(bytes, 0, total)).toString()
        StrictTextReadResult.Success(decoded.removePrefix("\uFEFF"))
    } catch (_: CharacterCodingException) {
        StrictTextReadResult.InvalidUtf8
    }
}
