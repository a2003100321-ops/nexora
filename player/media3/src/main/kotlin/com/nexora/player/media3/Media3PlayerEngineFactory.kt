package com.nexora.player.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.nexora.player.api.PlayerEngine

@OptIn(UnstableApi::class)
public fun createMedia3PlayerEngine(context: Context): PlayerEngine = Media3Engine(context)
