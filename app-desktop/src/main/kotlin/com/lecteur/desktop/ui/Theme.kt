package com.lecteur.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lecteur.core.model.ThemeMode

// Same palette as core:designsystem (Android): amber accent on near-black.
private val Dark = darkColorScheme(
    primary = Color(0xFFFFA000),
    onPrimary = Color(0xFF1E1000),
    primaryContainer = Color(0xFF422600),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = Color(0xFF82B1FF),
    onSecondary = Color(0xFF002B73),
    secondaryContainer = Color(0xFF003F9E),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFFFF5252),
    onTertiary = Color(0xFF450004),
    background = Color(0xFF0F0F12),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF18181D),
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF23232A),
    onSurfaceVariant = Color(0xFFA5A5B2),
    surfaceContainer = Color(0xFF1C1C22),
    surfaceContainerHigh = Color(0xFF26262E),
    surfaceContainerHighest = Color(0xFF2E2E37),
    surfaceContainerLow = Color(0xFF151519),
    outline = Color(0xFF383842),
    outlineVariant = Color(0xFF26262F)
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A5100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCB8),
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = Color(0xFF2F5FB3),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E3FF),
    onSecondaryContainer = Color(0xFF001B3E),
    tertiary = Color(0xFFB3261E),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFBFD),
    onBackground = Color(0xFF1B1B20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFFEDEDF3),
    onSurfaceVariant = Color(0xFF5A5A68),
    outline = Color(0xFFC6C6D2),
    outlineVariant = Color(0xFFE2E2EA)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun LecteurTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
