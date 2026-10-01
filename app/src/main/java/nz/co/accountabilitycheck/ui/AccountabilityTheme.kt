package nz.co.accountabilitycheck.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightInk = Color(0xFF213C36)
private val LightMutedInk = Color(0xFF68756D)
private val LightCream = Color(0xFFF7F5ED)
private val LightTeal = Color(0xFF2C695A)
private val LightPaleTeal = Color(0xFFE2ECE4)
private val LightSand = Color(0xFFECE8DA)
private val LocalDarkTheme = staticCompositionLocalOf { false }

private val LightPalette = lightColorScheme(
    primary = LightTeal,
    onPrimary = Color.White,
    primaryContainer = LightPaleTeal,
    onPrimaryContainer = LightInk,
    secondary = Color(0xFF746242),
    secondaryContainer = Color(0xFFF0E5CC),
    onSecondaryContainer = LightInk,
    background = LightCream,
    onBackground = LightInk,
    surface = LightCream,
    onSurface = LightInk,
    surfaceVariant = LightSand,
    onSurfaceVariant = LightMutedInk,
    surfaceContainer = Color(0xFFF0EEE5),
    surfaceContainerLow = Color(0xFFFCFBF7),
    outline = Color(0xFF8F9B91),
    outlineVariant = Color(0xFFD9DED4),
    error = Color(0xFF9A3B30),
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF91D6B5),
    onPrimary = Color(0xFF103427),
    primaryContainer = Color(0xFF244437),
    onPrimaryContainer = Color(0xFFE2F5E8),
    secondary = Color(0xFFD4BD8E),
    onSecondary = Color(0xFF382D18),
    secondaryContainer = Color(0xFF4B3D26),
    onSecondaryContainer = Color(0xFFF5E8CA),
    background = Color(0xFF111A16),
    onBackground = Color(0xFFEAF3EB),
    surface = Color(0xFF111A16),
    onSurface = Color(0xFFEAF3EB),
    surfaceVariant = Color(0xFF2C3B32),
    onSurfaceVariant = Color(0xFFADBEAF),
    surfaceContainer = Color(0xFF202B24),
    surfaceContainerLow = Color(0xFF19231D),
    outline = Color(0xFF859A88),
    outlineVariant = Color(0xFF3D5142),
    error = Color(0xFFFFB4A8),
    errorContainer = Color(0xFF782B25),
    onErrorContainer = Color(0xFFFFDAD4),
)

internal val Ink: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
internal val MutedInk: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Cream: Color
    @Composable get() = MaterialTheme.colorScheme.background
internal val Teal: Color
    @Composable get() = MaterialTheme.colorScheme.primary
internal val PaleTeal: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer
internal val Sand: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val ReadyCard: Color
    @Composable get() = if (LocalDarkTheme.current) Color(0xFF205340) else LightInk

@Composable
fun AccountabilityTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = if (darkTheme) DarkPalette else LightPalette, typography = Typography(), content = content)
    }
}
