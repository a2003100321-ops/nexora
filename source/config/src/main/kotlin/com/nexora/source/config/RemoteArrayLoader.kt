package com.nexora.source.config

/**
 * Loads a text document referenced by an object-array field.
 *
 * The config module never supplies a network implementation. Callers may inject a policy-enforcing
 * implementation; the default always fails closed.
 */
public fun interface RemoteArrayLoader {
    public suspend fun load(resolvedUrl: String): ByteArray

    public companion object {
        public fun disabled(): RemoteArrayLoader = RemoteArrayLoader { resolvedUrl ->
            throw RemoteArrayLoadingDisabledException(resolvedUrl)
        }
    }
}

public class RemoteArrayLoadingDisabledException(
    public val resolvedUrl: String,
) : IllegalStateException("远程数组加载器未启用")
