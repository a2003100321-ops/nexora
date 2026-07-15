package com.nexora.source.plugin.api

/**
 * Validates the deliberately narrow M3 isolated-host prototype boundary.
 *
 * The Android host currently shares one binding waiter across admitted work. Supporting more than
 * one in-flight host call would require participant-aware timeout/cancellation ownership, which is
 * intentionally deferred instead of exposing unsafe partial concurrency.
 */
public object IsolatedPluginHostPolicy {
    public const val SUPPORTED_MAX_CONCURRENT_TASKS: Int = 1

    public fun requireSupported(policy: PluginResourcePolicy) {
        require(policy.maxConcurrentTasks == SUPPORTED_MAX_CONCURRENT_TASKS) {
            "隔离插件宿主当前仅支持 maxConcurrentTasks=1；多调用绑定取消隔离尚未实现"
        }
    }
}
