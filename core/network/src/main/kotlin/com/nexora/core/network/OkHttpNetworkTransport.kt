package com.nexora.core.network

import com.nexora.core.logging.NexoraLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

public object NetworkTransports {
    public fun create(
        policy: NetworkPolicy = NetworkPolicy(),
        logger: NexoraLogger? = null,
    ): SafeHttpTransport {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        return OkHttpNetworkTransport(client, policy, logger)
    }
}

internal class OkHttpNetworkTransport(
    private val callFactory: Call.Factory,
    private val policy: NetworkPolicy = NetworkPolicy(),
    private val logger: NexoraLogger? = null,
) : SafeHttpTransport {
    override suspend fun execute(request: NetworkRequest): NetworkResult {
        val initialRedactedUrl = NetworkRedaction.redactUrl(request.url)
        val preparation = prepare(request, initialRedactedUrl)
        if (preparation is Preparation.Rejected) {
            logFailure(preparation.result.failure)
            return preparation.result
        }
        preparation as Preparation.Accepted

        logger?.info("网络请求开始 method=${request.method} url=$initialRedactedUrl")
        val result = try {
            withTimeout(request.timeoutMillis) {
                executeRedirectChain(request, preparation.url)
            }
        } catch (_: TimeoutCancellationException) {
            failure(
                NetworkFailureCode.TIMEOUT,
                "请求超时，请稍后重试。",
                initialRedactedUrl,
            )
        } catch (cancelled: CancellationException) {
            logger?.info("网络请求已取消 url=$initialRedactedUrl")
            throw cancelled
        } catch (_: ResponseLimitExceededException) {
            failure(
                NetworkFailureCode.RESPONSE_TOO_LARGE,
                "服务器返回的数据过大，已停止接收。",
                initialRedactedUrl,
            )
        } catch (error: IOException) {
            when {
                error.hasCause<SSLException>() -> failure(
                    NetworkFailureCode.TLS_VALIDATION_FAILED,
                    "安全连接验证失败，请检查服务器证书。",
                    initialRedactedUrl,
                )

                error.hasCause<SocketTimeoutException>() || error is InterruptedIOException -> failure(
                    NetworkFailureCode.TIMEOUT,
                    "请求超时，请稍后重试。",
                    initialRedactedUrl,
                )

                else -> failure(
                    NetworkFailureCode.CONNECTION_FAILED,
                    "网络连接失败，请稍后重试。",
                    initialRedactedUrl,
                )
            }
        } catch (_: IllegalArgumentException) {
            failure(
                NetworkFailureCode.INVALID_REQUEST,
                "网络请求参数无效。",
                initialRedactedUrl,
            )
        }

        when (result) {
            is NetworkResult.Success -> logger?.info(
                "网络请求完成 status=${result.response.statusCode} " +
                    "bytes=${result.response.bodySize} url=${result.response.redactedUrl}",
            )

            is NetworkResult.Failure -> logFailure(result.failure)
        }
        return result
    }

    private suspend fun executeRedirectChain(
        original: NetworkRequest,
        initialUrl: HttpUrl,
    ): NetworkResult {
        var currentUrl = initialUrl
        var currentMethod = original.method
        var currentBody = original.body
        var currentHeaders = original.headers
        var redirectCount = 0

        while (true) {
            val request = buildRequest(currentUrl, currentMethod, currentHeaders, currentBody)
            when (val hop = callFactory.newCall(request).awaitHop(original.maxResponseBytes)) {
                is HttpHop.Complete -> return NetworkResult.Success(
                    NetworkResponse(
                        statusCode = hop.statusCode,
                        headers = hop.headers,
                        body = hop.body,
                        redactedUrl = NetworkRedaction.redactUrl(currentUrl.toString()),
                    ),
                )

                is HttpHop.Redirect -> {
                    if (redirectCount >= original.maxRedirects) {
                        return failure(
                            NetworkFailureCode.TOO_MANY_REDIRECTS,
                            "服务器重定向次数过多，已停止请求。",
                            NetworkRedaction.redactUrl(currentUrl.toString()),
                        )
                    }
                    val nextUrl = currentUrl.resolve(hop.location)
                        ?: return failure(
                            NetworkFailureCode.REDIRECT_REJECTED,
                            "服务器返回了无效的重定向地址。",
                            NetworkRedaction.redactUrl(currentUrl.toString()),
                        )
                    val redirectRejection = validateHttpsUrl(nextUrl)
                    if (redirectRejection != null) {
                        return failure(
                            NetworkFailureCode.REDIRECT_REJECTED,
                            "服务器重定向不安全，已阻止请求。",
                            NetworkRedaction.redactUrl(nextUrl.toString()),
                        )
                    }

                    val sameOrigin = currentUrl.scheme == nextUrl.scheme &&
                        currentUrl.host == nextUrl.host &&
                        currentUrl.port == nextUrl.port
                    if (!sameOrigin) currentHeaders = retainCrossOriginHeaders(currentHeaders)

                    if (hop.statusCode == 303 ||
                        ((hop.statusCode == 301 || hop.statusCode == 302) && currentMethod == NetworkMethod.POST)
                    ) {
                        currentMethod = NetworkMethod.GET
                        currentBody = null
                    }
                    currentUrl = nextUrl
                    redirectCount += 1
                }
            }
        }
    }

    private fun prepare(request: NetworkRequest, redactedUrl: String): Preparation {
        val url = request.url.toHttpUrlOrNullCompat()
            ?: return rejected(
                NetworkFailureCode.INVALID_URL,
                "网络地址无效，仅支持 HTTPS。",
                redactedUrl,
            )
        if (validateHttpsUrl(url) != null) {
            return rejected(
                NetworkFailureCode.INVALID_URL,
                "网络地址无效，仅支持 HTTPS。",
                NetworkRedaction.redactUrl(url.toString()),
            )
        }
        if (request.timeoutMillis !in 1L..policy.maxRequestTimeoutMillis ||
            request.maxResponseBytes !in 1L..policy.maxResponseBytes ||
            request.maxRedirects !in 0..policy.maxRedirects
        ) {
            return rejected(
                NetworkFailureCode.INVALID_REQUEST,
                "网络请求超出安全限制。",
                redactedUrl,
            )
        }
        if (request.method == NetworkMethod.GET && request.body != null) {
            return rejected(
                NetworkFailureCode.INVALID_REQUEST,
                "GET 请求不能携带请求体。",
                redactedUrl,
            )
        }
        if (request.body != null && !validContentType(request.body.contentType)) {
            return rejected(
                NetworkFailureCode.INVALID_REQUEST,
                "请求体类型无效。",
                redactedUrl,
            )
        }
        if (!validHeaders(request.headers)) {
            return rejected(
                NetworkFailureCode.INVALID_HEADER,
                "请求头包含非法字符或受保护字段。",
                redactedUrl,
            )
        }
        return Preparation.Accepted(url)
    }

    private fun buildRequest(
        url: HttpUrl,
        method: NetworkMethod,
        headers: Map<String, String>,
        body: NetworkBody?,
    ): Request {
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        return when (method) {
            NetworkMethod.GET -> builder.get().build()
            NetworkMethod.POST -> {
                val requestBody = (body?.bytes ?: ByteArray(0)).toRequestBody(
                    body?.contentType?.toMediaTypeOrNull(),
                )
                builder.post(requestBody).build()
            }
        }
    }

    private fun validHeaders(headers: Map<String, String>): Boolean {
        val normalizedNames = mutableSetOf<String>()
        return headers.all { (name, value) ->
            val normalized = name.lowercase()
            normalizedNames.add(normalized) &&
                name.isNotEmpty() &&
                name.all(::isHeaderNameCharacter) &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' } &&
                value.all { it == '\t' || it.code in 0x20..0x7e || it.code >= 0x80 } &&
                normalized !in protectedRequestHeaders
        }
    }

    private fun validContentType(contentType: String): Boolean =
        contentType.isNotBlank() &&
            contentType.none { it == '\r' || it == '\n' || it == '\u0000' } &&
            contentType.toMediaTypeOrNull() != null

    private fun validateHttpsUrl(url: HttpUrl): String? = when {
        url.scheme != "https" -> "scheme"
        url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() -> "userinfo"
        else -> null
    }

    private fun retainCrossOriginHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { name -> name.lowercase() in safeCrossOriginHeaders }

    private fun rejected(
        code: NetworkFailureCode,
        message: String,
        redactedUrl: String,
    ): Preparation.Rejected = Preparation.Rejected(failure(code, message, redactedUrl))

    private fun logFailure(failure: NetworkFailure) {
        logger?.error(
            "网络请求失败 code=${failure.code} url=${failure.redactedUrl}",
        )
    }

    private sealed interface Preparation {
        data class Accepted(val url: HttpUrl) : Preparation

        data class Rejected(val result: NetworkResult.Failure) : Preparation
    }

    private companion object {
        private val protectedRequestHeaders: Set<String> = setOf(
            "connection",
            "content-length",
            "host",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )

        private val safeCrossOriginHeaders: Set<String> = setOf(
            "accept",
            "accept-language",
            "range",
            "user-agent",
        )

        private fun isHeaderNameCharacter(character: Char): Boolean =
            character.code in 0x21..0x7e && character !in "()<>@,;:\\\"/[]?={} \t"

        private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
            var current: Throwable? = this
            while (current != null) {
                if (current is T) return true
                current = current.cause
            }
            return false
        }

        private fun String.toHttpUrlOrNullCompat(): HttpUrl? =
            okhttp3.HttpUrl.Companion.run { toHttpUrlOrNull() }

        private fun failure(
            code: NetworkFailureCode,
            message: String,
            redactedUrl: String,
        ): NetworkResult.Failure = NetworkResult.Failure(
            NetworkFailure(code, message, redactedUrl),
        )
    }
}

private sealed interface HttpHop {
    data class Complete(
        val statusCode: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) : HttpHop

    data class Redirect(
        val statusCode: Int,
        val location: String,
    ) : HttpHop
}

private val redirectCodes: Set<Int> = setOf(301, 302, 303, 307, 308)

private suspend fun Call.awaitHop(maxResponseBytes: Long): HttpHop =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val location = response.header("Location")
                        val hop = if (response.code in redirectCodes && location != null) {
                            HttpHop.Redirect(response.code, location)
                        } else {
                            HttpHop.Complete(
                                statusCode = response.code,
                                headers = response.headers.toMultimap()
                                    .mapValues { (_, values) -> values.toList() },
                                body = response.body.readWithLimit(maxResponseBytes),
                            )
                        }
                        continuation.resumeWith(Result.success(hop))
                    }
                } catch (exception: ResponseLimitExceededException) {
                    continuation.resumeWith(Result.failure(exception))
                } catch (exception: IOException) {
                    continuation.resumeWith(Result.failure(exception))
                }
            }
        })
    }

private fun okhttp3.ResponseBody.readWithLimit(maxBytes: Long): ByteArray {
    val declaredLength = contentLength()
    if (declaredLength > maxBytes) throw ResponseLimitExceededException()

    val initialSize = minOf(maxBytes, 8_192L).toInt()
    val output = ByteArrayOutputStream(initialSize)
    byteStream().use { input ->
        val buffer = ByteArray(8_192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) throw ResponseLimitExceededException()
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}

private class ResponseLimitExceededException : IOException()
