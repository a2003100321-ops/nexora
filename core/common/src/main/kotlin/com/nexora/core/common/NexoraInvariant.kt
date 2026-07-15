package com.nexora.core.common

public fun requireNexoraInvariant(condition: Boolean, lazyMessage: () -> String) {
    require(condition, lazyMessage)
}
