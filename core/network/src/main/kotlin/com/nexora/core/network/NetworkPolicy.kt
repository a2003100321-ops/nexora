package com.nexora.core.network

public data class NetworkPolicy(
    val requireTlsValidation: Boolean = true,
    val allowCleartext: Boolean = false,
) {
    init {
        require(requireTlsValidation) { "Nexora never disables TLS certificate validation." }
        require(!allowCleartext) { "Cleartext transport is disabled by the M0-M2 security baseline." }
    }
}
