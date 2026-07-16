package com.nexora.mobile

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.nexora.source.runtime.AndroidSourceRuntime

public class NexoraApplication : Application(), SingletonImageLoader.Factory {
    public val sourceRuntime: AndroidSourceRuntime by lazy {
        AndroidSourceRuntime.create(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = createSecurePosterImageLoader(context)
}
