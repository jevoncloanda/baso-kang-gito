package id.basokanggito.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

object Space {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val section = 32.dp
}

internal val LightColors = lightColorScheme(
    primary = Color(0xFF303B53), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E7EE), onPrimaryContainer = Color(0xFF253047),
    // Terracotta is deepened from #BD6254 for small text on light surfaces.
    secondary = Color(0xFF9F4D40), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E3DE), onSecondaryContainer = Color(0xFF71372D),
    tertiary = Color(0xFF75582F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEED7B6), onTertiaryContainer = Color(0xFF4E391D),
    background = Color(0xFFF8F5F1), onBackground = Color(0xFF253047),
    surface = Color.White, onSurface = Color(0xFF253047),
    surfaceVariant = Color(0xFFEAE6E0), onSurfaceVariant = Color(0xFF5D5F65),
    surfaceTint = Color(0xFF303B53),
    surfaceDim = Color(0xFFDFDBD5), surfaceBright = Color(0xFFFFFCF8),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F5F1),
    surfaceContainer = Color(0xFFF2EFEB), surfaceContainerHigh = Color(0xFFECE9E4),
    surfaceContainerHighest = Color(0xFFE6E3DE),
    outline = Color(0xFF7C7975), outlineVariant = Color(0xFFDDD9D3),
    inverseSurface = Color(0xFF2D3139), inverseOnSurface = Color(0xFFF5F1EB), inversePrimary = Color(0xFFC2CBDD),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFC2CBDD), onPrimary = Color(0xFF273248),
    primaryContainer = Color(0xFF39465E), onPrimaryContainer = Color(0xFFE5EAF5),
    secondary = Color(0xFFEDB3A6), onSecondary = Color(0xFF562B24),
    secondaryContainer = Color(0xFF57362F), onSecondaryContainer = Color(0xFFFFDBD2),
    tertiary = Color(0xFFD8B77F), onTertiary = Color(0xFF402E14),
    tertiaryContainer = Color(0xFF584528), onTertiaryContainer = Color(0xFFF6DFB9),
    background = Color(0xFF171B23), onBackground = Color(0xFFECE9E4),
    surface = Color(0xFF222731), onSurface = Color(0xFFECE9E4),
    surfaceVariant = Color(0xFF343943), onSurfaceVariant = Color(0xFFC8C5C0),
    surfaceTint = Color(0xFFC2CBDD),
    surfaceDim = Color(0xFF171B23), surfaceBright = Color(0xFF3D424D),
    surfaceContainerLowest = Color(0xFF12151B), surfaceContainerLow = Color(0xFF1D212A),
    surfaceContainer = Color(0xFF222731), surfaceContainerHigh = Color(0xFF2C313C),
    surfaceContainerHighest = Color(0xFF373C47),
    outline = Color(0xFF96999F), outlineVariant = Color(0xFF484D57),
    inverseSurface = Color(0xFFE6E2DC), inverseOnSurface = Color(0xFF2D3139), inversePrimary = Color(0xFF47556E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val BasoTypography = Typography(
    headlineLarge = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun BasoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BasoTypography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
        content = content,
    )
}
