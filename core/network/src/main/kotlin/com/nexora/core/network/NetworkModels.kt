package com.nexora.core.network

public enum class NetworkMethod {
    GET,
    POST,
}

public class NetworkBody(
    bytes: ByteArray,
    public val contentType: String = "application/octet-stream",
) {
    private val content: ByteArray = bytes.copyOf()

    public val size: Int
        get() = content.size

    public val bytes: ByteArray
        get() = content.copyOf()

    override fun toString(): String = "NetworkBody(size=$size)"
}

public class NetworkRequest(
    public val url: String,
    public val method: NetworkMethod = NetworkMethod.GET,
    headers: Map<String, String> = emptyMap(),
    public val body: NetworkBody? = null,
    public val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    public val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    public val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) {
    public val headers: Map<String, String> = headers.toMap()

    override fun toString(): String = buildString {
        append("NetworkRequest(method=")
        append(method)
        append(", url=")
        append(NetworkRedaction.redactUrl(url))
        append(", headerNames=")
        append(NetworkRedaction.headerNames(headers))
        append(", body=")
        append(body)
        append(", timeoutMillis=")
        append(timeoutMillis)
        append(", maxResponseBytes=")
        append(maxResponseBytes)
        append(", maxRedirects=")
        append(maxRedirects)
        append(')')
    }

    public companion object {
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
        public const val DEFAULT_MAX_RESPONSE_BYTES: Long = 2L * 1024L * 1024L
        public const val DEFAULT_MAX_REDIRECTS: Int = 3
    }
}

public class NetworkResponse(
    public val statusCode: Int,
    headers: Map<String, List<String>>,
    body: ByteArray,
    public val redactedUrl: String,
) {
    public val headers: Map<String, List<String>> = headers
        .mapValues { (_, values) -> values.toList() }
        .toMap()
    private val content: ByteArray = body.copyOf()

    public val body: ByteArray
        get() = content.copyOf()

    public val bodySize: Int
        get() = content.size

    override fun toString(): String = buildString {
        append("NetworkResponse(statusCode=")
        append(statusCode)
        append(", headerNames=")
        append(headers.keys.sortedWith(String.CASE_INSENSITIVE_ORDER))
        append(", bodySize=")
        append(bodySize)
        append(", redactedUrl=")
        append(redactedUrl)
        append(')')
    }
}

public enum class NetworkFailureCode {
    INVALID_URL,
    INVALID_REQUEST,
    INVALID_HEADER,
    TIMEOUT,
    RESPONSE_TOO_LARGE,
    REDIRECT_REJECTED,
    TOO_MANY_REDIRECTS,
    TLS_VALIDATION_FAILED,
    CONNECTION_FAILED,
}

public data class NetworkFailure(
    val code: NetworkFailureCode,
    val userMessage: String,
    val redactedUrl: String,
)

public sealed interface NetworkResult {
    public data class Success(val response: NetworkResponse) : NetworkResult

    public data class Failure(val failure: NetworkFailure) : NetworkResult
}

public fun interface SafeHttpTransport {
    public suspend fun execute(request: NetworkRequest): NetworkResult
}
