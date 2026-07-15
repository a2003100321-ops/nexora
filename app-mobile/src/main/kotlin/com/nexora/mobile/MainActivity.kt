package com.nexora.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nexora.core.designsystem.NexoraTheme
import com.nexora.feature.home.MobileHomeShell

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexoraTheme {
                MobileHomeShell()
            }
        }
    }
}
