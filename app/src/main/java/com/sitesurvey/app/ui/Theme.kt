package com.sitesurvey.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF3DDBB8)
val Muted = Color(0xFF8B98A5)
val Warn = Color(0xFFF2B84B)
val Bad = Color(0xFFFF6B6B)
val Surface1 = Color(0xFF161C22)
val Line = Color(0xFF26303A)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00201A),
    secondary = Color(0xFF6CB6FF),
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0E1114),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Surface1,
    onSurfaceVariant = Muted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Color(0xFF1B232B),
    surfaceContainerLow = Color(0xFF12171C),
    outline = Line,
    outlineVariant = Line,
    error = Bad,
)

@Composable
fun SurveyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
