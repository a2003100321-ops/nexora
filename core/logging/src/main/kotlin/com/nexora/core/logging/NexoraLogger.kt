package com.nexora.core.logging

public interface NexoraLogger {
    public fun info(message: String)

    public fun error(message: String, cause: Throwable? = null)
}
