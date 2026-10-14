package com.pulsedrop.app.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pulsedrop.app.R

/**
 * Bundled rather than fetched from Google Fonts: the app has to look the same on a
 * network-isolated LAN with no upstream, and the browser client is served by this device.
 */
val PulseFontFamily = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_semibold, FontWeight.SemiBold),
    Font(R.font.google_sans_bold, FontWeight.Bold)
)

private fun style(
    size: Int,
    line: Int,
    tracking: Double,
    weight: FontWeight
): TextStyle = TextStyle(
    fontFamily = PulseFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

val PulseTypography = Typography(
    displayLarge = style(40, 48, -1.0, FontWeight.Bold),
    displayMedium = style(34, 42, -0.8, FontWeight.Bold),
    displaySmall = style(30, 38, -0.5, FontWeight.Bold),
    headlineLarge = style(28, 36, -0.5, FontWeight.Bold),
    headlineMedium = style(26, 34, -0.5, FontWeight.Bold),
    headlineSmall = style(22, 30, -0.2, FontWeight.SemiBold),
    titleLarge = style(20, 28, -0.2, FontWeight.Bold),
    titleMedium = style(16, 24, 0.1, FontWeight.SemiBold),
    titleSmall = style(14, 20, 0.1, FontWeight.SemiBold),
    bodyLarge = style(16, 24, 0.3, FontWeight.Normal),
    bodyMedium = style(14, 20, 0.2, FontWeight.Normal),
    bodySmall = style(12, 16, 0.3, FontWeight.Normal),
    labelLarge = style(14, 20, 0.1, FontWeight.SemiBold),
    labelMedium = style(12, 16, 0.4, FontWeight.Medium),
    labelSmall = style(11, 14, 0.5, FontWeight.Medium)
)

@Composable
fun PulseDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PulseLightScheme,
        typography = PulseTypography,
        content = {
            // A bare Text() with no style reads LocalTextStyle, which defaults to the
            // platform font; swapping only the family puts Google Sans on every last label.
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = PulseFontFamily),
                content = content
            )
        }
    )
}
