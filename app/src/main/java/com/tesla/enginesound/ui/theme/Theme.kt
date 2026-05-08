package com.tesla.enginesound.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val TeslaDarkColorScheme = darkColorScheme(
    primary = TeslaRed,
    onPrimary = TeslaWhite,
    primaryContainer = TeslaRedDim,
    onPrimaryContainer = TeslaWhite,
    secondary = TeslaBlue,
    onSecondary = TeslaWhite,
    secondaryContainer = TeslaGray,
    onSecondaryContainer = TeslaWhite,
    tertiary = RpmGreen,
    onTertiary = TeslaDark,
    background = TeslaDark,
    onBackground = TeslaWhite,
    surface = TeslaGray,
    onSurface = TeslaWhite,
    surfaceVariant = TeslaGray,
    onSurfaceVariant = TeslaGrayLight,
    outline = TeslaGrayLight,
    error = RpmRed,
    onError = TeslaWhite
)

@Composable
fun TeslaEngineSoundTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = TeslaDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = TeslaDark.toArgb()
            window.navigationBarColor = TeslaDark.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
