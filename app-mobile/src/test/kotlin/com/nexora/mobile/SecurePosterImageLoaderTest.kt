package com.nexora.mobile

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import okhttp3.Dns
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody

class SecurePosterImageLoaderTest {
    @Test
    fun secureClientNeverFollowsRedirects() {
        val client = createSecurePosterHttpClient(
            dns = Dns { listOf(InetAddress.getByName("203.0.113.10")) },
        )

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(10_000, client.callTimeoutMillis)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(10_000, client.readTimeoutMillis)
    }

    @Test
    fun cleartextPosterIsRejectedBeforeAnyConnectionAttempt() {
        val client = createSecurePosterHttpClient(
            dns = Dns { listOf(InetAddress.getByName("203.0.113.10")) },
        )
        val request = Request.Builder().url("http://images.example/poster.jpg").build()

        assertFailsWith<IOException> {
            client.newCall(request).execute().close()
        }
    }

    @Test
    fun dnsRejectsAnyPrivateOrLoopbackResolution() {
        val dns = PublicOnlyPosterDns(
            delegate = Dns {
                listOf(
                    InetAddress.getByName("203.0.113.10"),
                    InetAddress.getByName("127.0.0.1"),
                )
            },
        )

        assertFailsWith<UnknownHostException> { dns.lookup("images.example") }
    }

    @Test
    fun responseBodyIsRejectedBeforeExceedingTheByteLimit() {
        val accepted = ByteArray(16) { 1 }.toResponseBody()
        assertEquals(16L, accepted.readWithinLimit(16).contentLength())

        val oversized = ByteArray(17) { 1 }.toResponseBody()
        assertFailsWith<IOException> { oversized.readWithinLimit(16) }
    }
}
