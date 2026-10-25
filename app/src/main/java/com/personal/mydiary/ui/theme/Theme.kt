package com.personal.mydiary.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.personal.mydiary.data.Mood

private val LightColors = lightColorScheme(
    primary = Color(0xFF7B4FA0),
    secondary = Color(0xFFB0457B),
    tertiary = Color(0xFF3F7D6E),
    background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD7B8F5),
    secondary = Color(0xFFF4A9CB),
    tertiary = Color(0xFF9ED3C2),
)

@Composable
fun MyDiaryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

val Mood.emoji: String
    get() = when (this) {
        Mood.GOOD -> "😊"
        Mood.FAIR -> "😐"
        Mood.BAD -> "😞"
    }

val Mood.label: String
    get() = when (this) {
        Mood.GOOD -> "Good"
        Mood.FAIR -> "Fair"
        Mood.BAD -> "Bad"
    }

val Mood.color: Color
    get() = when (this) {
        Mood.GOOD -> Color(0xFF43A047)
        Mood.FAIR -> Color(0xFFF9A825)
        Mood.BAD -> Color(0xFFE53935)
    }
