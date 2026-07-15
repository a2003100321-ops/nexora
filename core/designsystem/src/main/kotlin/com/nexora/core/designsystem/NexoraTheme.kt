package com.nexora.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

public enum class NexoraThemeMode {
    DARK,
    LIGHT,
    SYSTEM,
}

public fun resolveDarkTheme(
    mode: NexoraThemeMode,
    systemInDarkTheme: Boolean,
): Boolean = when (mode) {
    NexoraThemeMode.DARK -> true
    NexoraThemeMode.LIGHT -> false
    NexoraThemeMode.SYSTEM -> systemInDarkTheme
}

private val NexoraDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB3B7),
    onPrimary = Color(0xFF680013),
    primaryContainer = Color(0xFF920020),
    onPrimaryContainer = Color(0xFFFFDADB),
    secondary = Color(0xFFE7BDBF),
    onSecondary = Color(0xFF44292B),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF47464B),
    onSurfaceVariant = Color(0xFFC8C5CB),
)

private val NexoraLightColorScheme = lightColorScheme(
    primary = Color(0xFFB71935),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDADB),
    onPrimaryContainer = Color(0xFF40000A),
    secondary = Color(0xFF765657),
    onSecondary = Color.White,
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE7E1E5),
    onSurfaceVariant = Color(0xFF494649),
)

@Composable
public fun NexoraTheme(
    mode: NexoraThemeMode = NexoraThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = resolveDarkTheme(
        mode = mode,
        systemInDarkTheme = isSystemInDarkTheme(),
    )

    MaterialTheme(
        colorScheme = if (useDarkTheme) NexoraDarkColorScheme else NexoraLightColorScheme,
        content = content,
    )
}
