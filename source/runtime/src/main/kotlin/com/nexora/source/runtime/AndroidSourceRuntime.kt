package com.nexora.source.runtime

import android.content.Context
import com.nexora.core.network.NetworkTransports
import com.nexora.source.api.AllSourcesSearcher
import com.nexora.source.api.LegacyHttpSourceGateway
import com.nexora.source.api.LegacySourceRepository
import com.nexora.source.runtime.config.AndroidLegacySourceRepository
import com.nexora.source.runtime.http.DefaultLegacyHttpSourceGateway
import com.nexora.source.runtime.search.DefaultAllSourcesSearcher

public class AndroidSourceRuntime private constructor(
    public val repository: LegacySourceRepository,
    public val httpGateway: LegacyHttpSourceGateway,
    public val allSourcesSearcher: AllSourcesSearcher,
) {
    public companion object {
        public fun create(context: Context): AndroidSourceRuntime {
            val transport = NetworkTransports.create()
            val gateway = DefaultLegacyHttpSourceGateway(transport)
            return AndroidSourceRuntime(
                repository = AndroidLegacySourceRepository.create(context, transport),
                httpGateway = gateway,
                allSourcesSearcher = DefaultAllSourcesSearcher(gateway),
            )
        }
    }
}
