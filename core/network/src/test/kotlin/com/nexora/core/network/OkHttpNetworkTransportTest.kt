package com.nexora.core.network

import com.nexora.core.logging.NexoraLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

public class OkHttpNetworkTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var serverCertificates: HandshakeCertificates
    private lateinit var trustedClient: OkHttpClient

    @Before
    public fun setUp(): Unit {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        trustedClient = secureTestClient(clientCertificates)
        server = newHttpsServer(serverCertificates)
    }

    @After
    public fun tearDown(): Unit {
        server.shutdown()
    }

    @Test
    public fun getReturnsStatusHeadersBodyAndRedactedUrl(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Test", "yes")
                .setBody("你好，Nexora"),
        )
        val transport = OkHttpNetworkTransport(trustedClient)

        val result = transport.execute(
            NetworkRequest(server.url("/catalog?token=top-secret").toString()),
        )

        val success = assertIs<NetworkResult.Success>(result)
        assertEquals(200, success.response.statusCode)
        assertEquals(listOf("yes"), success.response.headers["x-test"])
        assertEquals("你好，Nexora", success.response.body.toString(Charsets.UTF_8))
        assertFalse(success.response.redactedUrl.contains("top-secret"))
        assertFalse(success.response.toString().contains("top-secret"))
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    public fun postSendsCopiedBodyAndContentType(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("created"))
        val original = "{\"name\":\"Nexora\"}".toByteArray()
        val body = NetworkBody(original, "application/json; charset=utf-8")
        original.fill(0)
        val transport = OkHttpNetworkTransport(trustedClient)

        val result = transport.execute(
            NetworkRequest(
                url = server.url("/sources").toString(),
                method = NetworkMethod.POST,
                body = body,
            ),
        )

        assertIs<NetworkResult.Success>(result)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals("{\"name\":\"Nexora\"}", recorded.body.readUtf8())
    }

    @Test
    public fun coroutineCancellationCancelsActiveCallWhileReadingBody(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("delayed")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )
        val trackingFactory = TrackingCallFactory(trustedClient)
        val transport = OkHttpNetworkTransport(trackingFactory)

        val job = async(Dispatchers.Default) {
            transport.execute(NetworkRequest(server.url("/slow").toString()))
        }
        val request = withContext(Dispatchers.IO) {
            server.takeRequest(2, TimeUnit.SECONDS)
        }
        assertNotNull(request)

        job.cancelAndJoin()

        assertTrue(trackingFactory.lastCall?.isCanceled() == true)
    }

    @Test
    public fun perRequestTimeoutCancelsCallAndReturnsChineseFailure(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("too late")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )
        val trackingFactory = TrackingCallFactory(trustedClient)
        val transport = OkHttpNetworkTransport(trackingFactory)

        val result = transport.execute(
            NetworkRequest(
                url = server.url("/timeout").toString(),
                timeoutMillis = 100,
            ),
        )

        val failure = assertIs<NetworkResult.Failure>(result).failure
        assertEquals(NetworkFailureCode.TIMEOUT, failure.code)
        assertContains(failure.userMessage, "超时")
        assertTrue(trackingFactory.lastCall?.isCanceled() == true)
    }

    @Test
    public fun responseLargerThanRequestLimitIsRejectedBeforeBuffering(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(1_024)))
        val transport = OkHttpNetworkTransport(trustedClient)

        val result = transport.execute(
            NetworkRequest(
                url = server.url("/large").toString(),
                maxResponseBytes = 16,
            ),
        )

        val failure = assertIs<NetworkResult.Failure>(result).failure
        assertEquals(NetworkFailureCode.RESPONSE_TOO_LARGE, failure.code)
        assertContains(failure.userMessage, "过大")
    }

    @Test
    public fun invalidAndCleartextUrlsAreRejectedWithoutOpeningCall(): Unit = runBlocking {
        val trackingFactory = TrackingCallFactory(trustedClient)
        val transport = OkHttpNetworkTransport(trackingFactory)

        val malformed = transport.execute(NetworkRequest("not a url?token=secret"))
        val cleartext = transport.execute(NetworkRequest("http://example.com/path?token=secret"))

        assertEquals(
            NetworkFailureCode.INVALID_URL,
            assertIs<NetworkResult.Failure>(malformed).failure.code,
        )
        assertEquals(
            NetworkFailureCode.INVALID_URL,
            assertIs<NetworkResult.Failure>(cleartext).failure.code,
        )
        assertEquals(0, trackingFactory.callCount)
        assertFalse(malformed.toString().contains("secret"))
        assertFalse(cleartext.toString().contains("secret"))
    }

    @Test
    public fun headerCrlfAndProtectedTransportHeadersAreRejected(): Unit = runBlocking {
        val trackingFactory = TrackingCallFactory(trustedClient)
        val transport = OkHttpNetworkTransport(trackingFactory)

        val crlf = transport.execute(
            NetworkRequest(
                url = server.url("/").toString(),
                headers = mapOf("X-Test" to "safe\r\nInjected: true"),
            ),
        )
        val protected = transport.execute(
            NetworkRequest(
                url = server.url("/").toString(),
                headers = mapOf("Content-Length" to "1"),
            ),
        )

        assertEquals(
            NetworkFailureCode.INVALID_HEADER,
            assertIs<NetworkResult.Failure>(crlf).failure.code,
        )
        assertEquals(
            NetworkFailureCode.INVALID_HEADER,
            assertIs<NetworkResult.Failure>(protected).failure.code,
        )
        assertEquals(0, trackingFactory.callCount)
    }

    @Test
    public fun httpsToHttpRedirectIsRejectedBeforeFollowing(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://example.com/plain?token=redirect-secret"),
        )
        val transport = OkHttpNetworkTransport(trustedClient)

        val result = transport.execute(NetworkRequest(server.url("/redirect").toString()))

        val failure = assertIs<NetworkResult.Failure>(result).failure
        assertEquals(NetworkFailureCode.REDIRECT_REJECTED, failure.code)
        assertContains(failure.userMessage, "不安全")
        assertFalse(failure.redactedUrl.contains("redirect-secret"))
        assertEquals(1, server.requestCount)
    }

    @Test
    public fun redirectLimitIsEnforcedOneHopAtATime(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(302).addHeader("Location", "/second?token=hidden"),
        )
        server.enqueue(
            MockResponse().setResponseCode(302).addHeader("Location", "/third"),
        )
        val transport = OkHttpNetworkTransport(trustedClient)

        val result = transport.execute(
            NetworkRequest(
                url = server.url("/first").toString(),
                maxRedirects = 1,
            ),
        )

        val failure = assertIs<NetworkResult.Failure>(result).failure
        assertEquals(NetworkFailureCode.TOO_MANY_REDIRECTS, failure.code)
        assertEquals(2, server.requestCount)
        assertFalse(failure.redactedUrl.contains("hidden"))
    }

    @Test
    public fun crossOriginRedirectDropsCredentials(): Unit = runBlocking {
        val target = newHttpsServer(serverCertificates)
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", target.url("/target")),
            )
            target.enqueue(MockResponse().setBody("ok"))
            val transport = OkHttpNetworkTransport(trustedClient)

            val result = transport.execute(
                NetworkRequest(
                    url = server.url("/origin").toString(),
                    headers = mapOf(
                        "Authorization" to "Bearer auth-secret",
                        "Cookie" to "session=cookie-secret",
                        "User-Agent" to "Nexora-Test",
                    ),
                ),
            )

            assertIs<NetworkResult.Success>(result)
            val redirected = target.takeRequest()
            assertEquals(null, redirected.getHeader("Authorization"))
            assertEquals(null, redirected.getHeader("Cookie"))
            assertEquals("Nexora-Test", redirected.getHeader("User-Agent"))
        } finally {
            target.shutdown()
        }
    }

    @Test
    public fun defaultProductionClientRejectsSelfSignedCertificate(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("must not be trusted"))
        val transport = NetworkTransports.create()

        val result = transport.execute(
            NetworkRequest(
                url = server.url("/self-signed").toString(),
                timeoutMillis = 5_000,
            ),
        )

        val failure = assertIs<NetworkResult.Failure>(result).failure
        assertTrue(
            failure.code == NetworkFailureCode.TLS_VALIDATION_FAILED ||
                failure.code == NetworkFailureCode.CONNECTION_FAILED,
        )
        assertEquals(0, server.requestCount, "TLS validation must fail before an HTTP request is accepted")
        assertTrue(failure.userMessage.any { character -> character.code in 0x4e00..0x9fff })
    }

    @Test
    public fun logsAndObjectStringsNeverContainQueryCookieOrAuthorizationSecrets(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "server=server-secret")
                .setBody("ok"),
        )
        val logger = CapturingLogger()
        val transport = OkHttpNetworkTransport(trustedClient, logger = logger)
        val request = NetworkRequest(
            url = server.url("/safe?access_token=query-secret").toString(),
            headers = mapOf(
                "Authorization" to "Bearer auth-secret",
                "Cookie" to "session=cookie-secret",
            ),
        )

        val result = transport.execute(request)

        val allVisibleText = buildString {
            append(logger.messages.joinToString())
            append(request)
            append(result)
        }
        listOf("query-secret", "auth-secret", "cookie-secret", "server-secret").forEach { secret ->
            assertFalse(allVisibleText.contains(secret), "Secret leaked: $secret")
        }
        assertEquals("<已隐藏>", NetworkRedaction.redactHeaders(request.headers)["Authorization"])
        assertEquals("<已隐藏>", NetworkRedaction.redactHeaders(request.headers)["Cookie"])
    }

    private fun secureTestClient(certificates: HandshakeCertificates): OkHttpClient =
        OkHttpClient.Builder()
            .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    private fun newHttpsServer(certificates: HandshakeCertificates): MockWebServer =
        MockWebServer().apply {
            useHttps(certificates.sslSocketFactory(), false)
            start()
        }

    private class TrackingCallFactory(
        private val delegate: Call.Factory,
    ) : Call.Factory {
        var lastCall: Call? = null
            private set
        var callCount: Int = 0
            private set

        override fun newCall(request: Request): Call = delegate.newCall(request).also { call ->
            lastCall = call
            callCount += 1
        }
    }

    private class CapturingLogger : NexoraLogger {
        val messages: MutableList<String> = mutableListOf()

        override fun info(message: String) {
            messages += message
        }

        override fun error(message: String, cause: Throwable?) {
            messages += message
            cause?.message?.let(messages::add)
        }
    }
}
