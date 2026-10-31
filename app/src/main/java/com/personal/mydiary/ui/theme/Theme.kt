package com.personal.mydiary.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.mydiary.R
import com.personal.mydiary.data.Mood

private val LightColors = lightColorScheme(
    primary = Color(0xFF6A45A8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDFFF),
    onPrimaryContainer = Color(0xFF24104F),
    secondary = Color(0xFFB83F73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDE8),
    onSecondaryContainer = Color(0xFF3F0021),
    tertiary = Color(0xFF00796B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC5F0E8),
    onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFF8F5FC),
    onBackground = Color(0xFF1C1A22),
    surface = Color(0xFFF8F5FC),
    onSurface = Color(0xFF1C1A22),
    surfaceVariant = Color(0xFFE8E2F0),
    onSurfaceVariant = Color(0xFF5C5668),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF1ECF7),
    surfaceContainerHigh = Color(0xFFEBE5F2),
    surfaceContainerHighest = Color(0xFFE5DFEC),
    outline = Color(0xFF8A8396),
    outlineVariant = Color(0xFFD9D2E2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF38206F),
    primaryContainer = Color(0xFF4F3587),
    onPrimaryContainer = Color(0xFFEBDFFF),
    secondary = Color(0xFFFFB0CD),
    onSecondary = Color(0xFF5E1138),
    secondaryContainer = Color(0xFF7B2850),
    onSecondaryContainer = Color(0xFFFFDDE8),
    tertiary = Color(0xFF7ED8C8),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048),
    onTertiaryContainer = Color(0xFFC5F0E8),
    background = Color(0xFF14121A),
    onBackground = Color(0xFFE7E1EC),
    surface = Color(0xFF14121A),
    onSurface = Color(0xFFE7E1EC),
    surfaceVariant = Color(0xFF332E3D),
    onSurfaceVariant = Color(0xFFC9C2D3),
    surfaceContainerLowest = Color(0xFF0F0D14),
    surfaceContainerLow = Color(0xFF211E29),
    surfaceContainer = Color(0xFF1E1B25),
    surfaceContainerHigh = Color(0xFF2A2633),
    surfaceContainerHighest = Color(0xFF35313E),
    outline = Color(0xFF938C9E),
    outlineVariant = Color(0xFF47414F),
)

/** The app's banner colours; white text stays readable on both ends. */
val HeroStart = Color(0xFF5A3A9E)
val HeroEnd = Color(0xFFA83C78)

@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) = Font(
    R.font.nunito_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800))

private fun TextStyle.nunito(weight: FontWeight? = fontWeight) = copy(fontFamily = Nunito, fontWeight = weight)

private val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.nunito(), displayMedium = displayMedium.nunito(), displaySmall = displaySmall.nunito(),
        headlineLarge = headlineLarge.nunito(FontWeight.ExtraBold),
        headlineMedium = headlineMedium.nunito(FontWeight.ExtraBold),
        headlineSmall = headlineSmall.nunito(FontWeight.ExtraBold),
        titleLarge = titleLarge.nunito(FontWeight.ExtraBold),
        titleMedium = titleMedium.nunito(FontWeight.Bold),
        titleSmall = titleSmall.nunito(FontWeight.Bold),
        bodyLarge = bodyLarge.nunito(), bodyMedium = bodyMedium.nunito(), bodySmall = bodySmall.nunito(),
        labelLarge = labelLarge.nunito(FontWeight.Bold),
        labelMedium = labelMedium.nunito(FontWeight.SemiBold),
        labelSmall = labelSmall.nunito(FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MyDiaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

val Mood.emoji: String
    get() = when (this) {
        Mood.GOOD -> "😊"
        Mood.FAIR -> "😐"
        Mood.BAD -> "😞"
        Mood.ANGRY -> "😠"
        Mood.TIRED -> "😴"
        Mood.BUSY -> "🤯"
    }

val Mood.label: String
    get() = when (this) {
        Mood.GOOD -> "Good"
        Mood.FAIR -> "Fair"
        Mood.BAD -> "Bad"
        Mood.ANGRY -> "Angry"
        Mood.TIRED -> "Tired"
        Mood.BUSY -> "Busy"
    }

val Mood.color: Color
    get() = when (this) {
        Mood.GOOD -> Color(0xFF43A047)
        Mood.FAIR -> Color(0xFFF9A825)
        Mood.BAD -> Color(0xFFE53935)
        Mood.ANGRY -> Color(0xFFD84315)
        Mood.TIRED -> Color(0xFF78909C)
        Mood.BUSY -> Color(0xFF1E88E5)
    }
