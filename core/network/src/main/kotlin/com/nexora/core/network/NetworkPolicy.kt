package com.nexora.core.network

public data class NetworkPolicy(
    val requireTlsValidation: Boolean = true,
    val allowCleartext: Boolean = false,
    val maxRedirects: Int = 5,
    val maxRequestTimeoutMillis: Long = 60_000L,
    val maxResponseBytes: Long = 8L * 1024L * 1024L,
) {
    init {
        require(requireTlsValidation) { "Nexora never disables TLS certificate validation." }
        require(!allowCleartext) { "Cleartext transport is disabled by the M0-M2 security baseline." }
        require(maxRedirects in 0..10) { "Redirect limit must be between 0 and 10." }
        require(maxRequestTimeoutMillis in 1L..300_000L) {
            "Request timeout limit must be between 1 ms and 5 minutes."
        }
        require(maxResponseBytes in 1L..64L * 1024L * 1024L) {
            "Response limit must be between 1 byte and 64 MiB."
        }
    }
}
