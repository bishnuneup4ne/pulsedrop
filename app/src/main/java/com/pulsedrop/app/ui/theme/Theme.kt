package com.pulsedrop.app.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// PulseDrop — a single light Material 3 scheme. Flat tonal surfaces, no gradients.
private val Brand = BrandBlue

internal val PulseLightScheme = lightColorScheme(
    // Primary — professional blue
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E40AF),

    // Secondary — teal/cyan
    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),

    // Tertiary — violet
    tertiary = BrandViolet,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),

    // Surfaces
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1C21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C21),
    surfaceVariant = Color(0xFFEEF1F8),
    onSurfaceVariant = Color(0xFF454A57),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F6FB),
    surfaceContainer = Color(0xFFEFF1F7),
    surfaceContainerHigh = Color(0xFFE9ECF4),
    surfaceContainerHighest = Color(0xFFE3E7F1),

    outline = Color(0xFF767B8A),
    outlineVariant = Color(0xFFD5D9E4),

    // Error
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)
