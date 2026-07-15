package com.nexora.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nexora.core.designsystem.NexoraTheme
import com.nexora.core.designsystem.NexoraThemeMode

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as NexoraApplication).sourceRepository
        setContent {
            NexoraTheme(mode = NexoraThemeMode.SYSTEM) {
                NexoraMobileApp(repository = repository)
            }
        }
    }
}
