package pl.virtualexchange.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF089981)
val Red = Color(0xFFF23645)
val Gold = Color(0xFFF2B84B)
val AccentBlue = Color(0xFF2962FF)

private val Scheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF24C8B5),
    onSecondary = Color(0xFF061412),
    background = Color(0xFF000000),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF2A2E39),
)

@Composable
fun VeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
