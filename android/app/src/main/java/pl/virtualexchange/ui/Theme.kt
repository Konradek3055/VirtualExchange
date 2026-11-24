package pl.virtualexchange.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF0ECB81)
val Red = Color(0xFFF6465D)
val Gold = Color(0xFFF0B90B)

private val Scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color.Black,
    background = Color(0xFF0B0E11),
    surface = Color(0xFF181A20),
    surfaceVariant = Color(0xFF1E2329),
    onBackground = Color(0xFFEAECEF),
    onSurface = Color(0xFFEAECEF),
)

@Composable
fun VeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
