package pl.virtualexchange.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF00C087)
val Red = Color(0xFFFF5B6E)
val Gold = Color(0xFFF2B84B)
val AccentBlue = Color(0xFF388BFF)

private val Scheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF24C8B5),
    onSecondary = Color(0xFF061412),
    background = Color(0xFF080D13),
    surface = Color(0xFF111923),
    surfaceVariant = Color(0xFF17222D),
    onBackground = Color(0xFFEAF0F6),
    onSurface = Color(0xFFEAF0F6),
    onSurfaceVariant = Color(0xFF9AA9B9),
    outline = Color(0xFF293847),
)

@Composable
fun VeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
