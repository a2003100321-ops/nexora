package com.nexora.mobile

import android.app.Application
import com.nexora.source.runtime.config.AndroidLegacySourceRepository

public class NexoraApplication : Application() {
    public val sourceRepository: AndroidLegacySourceRepository by lazy {
        AndroidLegacySourceRepository.create(this)
    }
}
