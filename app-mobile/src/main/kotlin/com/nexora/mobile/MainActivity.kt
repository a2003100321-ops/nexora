package com.nexora.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nexora.core.designsystem.NexoraTheme
import com.nexora.core.designsystem.NexoraThemeMode

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceRuntime = (application as NexoraApplication).sourceRuntime
        setContent {
            NexoraTheme(mode = NexoraThemeMode.SYSTEM) {
                NexoraMobileApp(
                    repository = sourceRuntime.repository,
                    searcher = sourceRuntime.allSourcesSearcher,
                    gateway = sourceRuntime.httpGateway,
                )
            }
        }
    }
}
