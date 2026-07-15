package com.nexora.feature.sources

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StrictUtf8TextReaderTest {
    @Test
    fun `reads valid UTF-8 text`() {
        val input = ByteArrayInputStream("{\"名称\":\"示例\"}".toByteArray(Charsets.UTF_8))

        val result = readStrictUtf8Text(input)

        assertEquals(
            StrictTextReadResult.Success("{\"名称\":\"示例\"}"),
            result,
        )
    }

    @Test
    fun `strips one UTF-8 byte order mark`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "{}".toByteArray(Charsets.UTF_8)

        val result = readStrictUtf8Text(ByteArrayInputStream(bytes))

        assertEquals(StrictTextReadResult.Success("{}"), result)
    }

    @Test
    fun `rejects malformed UTF-8`() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)

        val result = readStrictUtf8Text(ByteArrayInputStream(malformed))

        assertIs<StrictTextReadResult.InvalidUtf8>(result)
    }

    @Test
    fun `accepts content exactly at the byte limit`() {
        val result = readStrictUtf8Text(
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            maxBytes = 4,
        )

        assertIs<StrictTextReadResult.Success>(result)
    }

    @Test
    fun `rejects content one byte over the limit`() {
        val result = readStrictUtf8Text(
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
            maxBytes = 4,
        )

        assertIs<StrictTextReadResult.TooLarge>(result)
    }

    @Test
    fun `returns structured failure when reading throws`() {
        val failingInput = object : InputStream() {
            override fun read(): Int = throw IOException("fixture failure")
        }

        val result = readStrictUtf8Text(failingInput)

        assertIs<StrictTextReadResult.ReadFailed>(result)
    }

    @Test
    fun `accepts JSON and TXT names case-insensitively`() {
        assertEquals(true, isSupportedConfigDocument("config.JSON", null))
        assertEquals(true, isSupportedConfigDocument("config.Txt", null))
    }

    @Test
    fun `accepts supported MIME when provider omits a name`() {
        assertEquals(true, isSupportedConfigDocument(null, "application/json"))
        assertEquals(true, isSupportedConfigDocument(null, "text/plain"))
    }

    @Test
    fun `rejects unrelated document types`() {
        assertEquals(false, isSupportedConfigDocument("config.zip", "application/zip"))
    }
}
