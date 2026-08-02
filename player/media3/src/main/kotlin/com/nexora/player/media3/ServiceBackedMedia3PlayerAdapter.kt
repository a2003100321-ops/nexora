package com.nexora.player.media3

import android.content.Context
import androidx.media3.common.util.UnstableApi

@UnstableApi
internal class ServiceBackedMedia3PlayerAdapter(
    context: Context,
) : Media3PlayerAdapter {
    private val appContext = context.applicationContext
    private var listener: Media3PlayerAdapterListener? = null

    private val delegate: Media3PlayerAdapter
        get() = Media3SessionPlayerOwner.acquireAdapter(appContext).also { it.setListener(listener) }

    override val currentPositionMs: Long
        get() = delegate.currentPositionMs

    override val durationMs: Long?
        get() = delegate.durationMs

    override fun setListener(listener: Media3PlayerAdapterListener?) {
        this.listener = listener
        delegate.setListener(listener)
    }

    override fun prepare(mappedItem: Media3MappedItem) {
        delegate.prepare(mappedItem)
    }

    override fun play() {
        delegate.play()
    }

    override fun pause() {
        delegate.pause()
    }

    override fun seekTo(positionMs: Long) {
        delegate.seekTo(positionMs)
    }

    override fun stop() {
        delegate.stop()
    }

    override fun release() {
        listener = null
        delegate.setListener(null)
        Media3SessionPlayerOwner.stopService(appContext)
    }
}
