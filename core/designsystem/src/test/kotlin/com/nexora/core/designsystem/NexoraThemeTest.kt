package com.nexora.core.designsystem

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class NexoraThemeTest {
    @Test
    public fun darkModeAlwaysResolvesToDark(): Unit {
        assertTrue(resolveDarkTheme(NexoraThemeMode.DARK, systemInDarkTheme = false))
    }

    @Test
    public fun lightModeAlwaysResolvesToLight(): Unit {
        assertFalse(resolveDarkTheme(NexoraThemeMode.LIGHT, systemInDarkTheme = true))
    }

    @Test
    public fun systemModeFollowsSystemSetting(): Unit {
        assertTrue(resolveDarkTheme(NexoraThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(resolveDarkTheme(NexoraThemeMode.SYSTEM, systemInDarkTheme = false))
    }
}
