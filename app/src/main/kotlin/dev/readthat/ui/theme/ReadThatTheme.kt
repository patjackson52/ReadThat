package dev.readthat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ReadThatOrange = Color(0xFFF04424)
val ReadThatBlue = Color(0xFF0A449B)
val ReadThatNavy = Color(0xFF0B1416)
val ReadThatSurface = Color(0xFFF6F8F9)
val ReadThatOutline = Color(0xFFD6D9DB)

private val LightColors = lightColorScheme(
    primary = ReadThatOrange,
    onPrimary = Color.White,
    secondary = ReadThatBlue,
    surface = Color.White,
    surfaceVariant = ReadThatSurface,
    background = Color.White,
    onSurface = ReadThatNavy,
    onSurfaceVariant = Color(0xFF576F76),
    outline = Color(0xFF878A8C),
    outlineVariant = ReadThatOutline,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6A33),
    secondary = Color(0xFF64A4FF),
    background = ReadThatNavy,
    surface = ReadThatNavy,
    surfaceVariant = Color(0xFF1A282D),
    onSurface = Color(0xFFF2F4F5),
    onSurfaceVariant = Color(0xFFB8C5C9),
    outline = Color(0xFF8C9A9F),
    outlineVariant = Color(0xFF33464D),
)

@Composable
fun ReadThatTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
