package com.nexora.source.plugin.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpiderIpcCodecTest {
    @Test
    fun `request envelope round trips through strict wire format`() {
        val request = request(payload = "{\"title\":\"安全\"}")
        val encoded = SpiderIpcCodec.encodeRequest(
            request,
            PluginEngineKind.JAVASCRIPT_FIXTURE_SUBSET,
        ).getOrThrow()
        val decoded = SpiderIpcCodec.decodeRequest(encoded).getOrThrow()

        assertEquals(request, decoded.request)
        assertEquals(PluginEngineKind.JAVASCRIPT_FIXTURE_SUBSET, decoded.engineKind)
    }

    @Test
    fun `UTF-8 result limit is measured in bytes`() {
        val policy = PluginResourcePolicy(
            maxEnvelopeBytes = 256,
            maxFieldChars = 128,
            maxResultBytes = 8,
        )
        val encoded = SpiderIpcCodec.encodeResult(
            requestId = "request-1",
            result = SpiderExecutionResult.Success("中文中文"),
            policy = policy,
        )
        val decoded = SpiderIpcCodec.decodeResult(encoded, "request-1", policy).getOrThrow()

        assertEquals(
            SpiderFailureCode.RESOURCE_LIMIT,
            assertIs<SpiderExecutionResult.Failure>(decoded).failure.code,
        )
    }

    @Test
    fun `IPC envelope overflow is replaced by bounded failure`() {
        val policy = PluginResourcePolicy(
            maxEnvelopeBytes = 256,
            maxFieldChars = 128,
            maxResultBytes = 256,
        )
        val encoded = SpiderIpcCodec.encodeResult(
            requestId = "request-1",
            result = SpiderExecutionResult.Success("x".repeat(240)),
            policy = policy,
        )

        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= policy.maxEnvelopeBytes)
        assertEquals(
            SpiderFailureCode.RESOURCE_LIMIT,
            assertIs<SpiderExecutionResult.Failure>(
                SpiderIpcCodec.decodeResult(encoded, "request-1", policy).getOrThrow(),
            ).failure.code,
        )
    }

    @Test
    fun `unexpected fields and mismatched request identifiers are rejected`() {
        val extraField = """{"requestId":"request-1","status":"success","payload":"ok","extra":1}"""
        assertTrue(SpiderIpcCodec.decodeResult(extraField, "request-1").isFailure)

        val valid = SpiderIpcCodec.encodeResult(
            "request-1",
            SpiderExecutionResult.Success("ok"),
        )
        assertTrue(SpiderIpcCodec.decodeResult(valid, "other-request").isFailure)
    }

    @Test
    fun `numeric values cannot impersonate required string fields`() {
        val numericIdentifier = """{"requestId":1,"status":"success","payload":"ok"}"""
        assertTrue(SpiderIpcCodec.decodeResult(numericIdentifier, "1").isFailure)
    }

    @Test
    fun `fallback remains bounded for the longest legal request identifier`() {
        val policy = PluginResourcePolicy(
            maxEnvelopeBytes = 256,
            maxFieldChars = 128,
            maxResultBytes = 256,
        )
        val encoded = SpiderIpcCodec.encodeResult(
            requestId = "r".repeat(128),
            result = SpiderExecutionResult.Success("x".repeat(240)),
            policy = policy,
        )
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= policy.maxEnvelopeBytes)
    }

    private fun request(payload: String) = SpiderRequestEnvelope(
        requestId = "request-1",
        sourceKey = "source-1",
        pluginId = "fixture.js",
        contractVersion = SpiderV1.VERSION,
        operation = SpiderOperation.SEARCH,
        payload = payload,
    )
}
