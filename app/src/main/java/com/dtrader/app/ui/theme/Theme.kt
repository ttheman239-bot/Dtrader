package com.dtrader.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = InfoBlue,
    onPrimary = Bg,
    secondary = BullGreen,
    onSecondary = Bg,
    tertiary = Accent,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = BearRed,
)

private val LightScheme = lightColorScheme(
    primary = InfoBlue,
    secondary = BullGreen,
    tertiary = Accent,
    error = BearRed,
)

@Composable
fun DtraderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = DtraderTypography,
        content = content,
    )
}
