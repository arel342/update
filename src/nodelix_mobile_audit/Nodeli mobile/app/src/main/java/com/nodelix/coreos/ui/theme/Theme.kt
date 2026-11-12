package com.nodelix.coreos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NodelixDarkColorScheme = darkColorScheme(
    primary = NodelixPrimary,
    onPrimary = Color(0xFF002116),
    primaryContainer = Color(0xFF003828),
    onPrimaryContainer = NodelixPrimary,
    secondary = NodelixSecondary,
    onSecondary = Color(0xFF001F29),
    secondaryContainer = Color(0xFF083142),
    onSecondaryContainer = NodelixSecondary,
    tertiary = NodelixWarning,
    background = NodelixBackground,
    onBackground = NodelixTextPrimary,
    surface = NodelixBackgroundSecondary,
    onSurface = NodelixTextPrimary,
    surfaceVariant = NodelixCard,
    onSurfaceVariant = NodelixTextSecondary,
    outline = NodelixBorder,
    error = NodelixError,
    onError = Color.White
)

@Composable
fun NodelixTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NodelixDarkColorScheme,
        typography = NodelixTypography,
        content = content
    )
}
