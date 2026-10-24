package app.para.schedule.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.para.schedule.data.GroupProfile

private val lightColors = lightColorScheme(
    primary = Color(0xFF7660B7), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAE1FA), onPrimaryContainer = Color(0xFF392360),
    secondary = Color(0xFF698B78), secondaryContainer = Color(0xFFE4EFE7),
    tertiary = Color(0xFFB77E58), tertiaryContainer = Color(0xFFF9E9DB),
    background = Color(0xFFF8F7FB), onBackground = Color(0xFF252130),
    surface = Color(0xFFFFFEFF), onSurface = Color(0xFF252130),
    surfaceVariant = Color(0xFFF0EDF5), onSurfaceVariant = Color(0xFF80788E),
    outline = Color(0xFFC9C2D4), outlineVariant = Color(0xFFE9E4F0),
)
private val darkColors = darkColorScheme(
    primary = Color(0xFFCCB9F7), onPrimary = Color(0xFF38265C),
    primaryContainer = Color(0xFF3C2E54), onPrimaryContainer = Color(0xFFEEE3FF),
    secondary = Color(0xFFAACDB7), secondaryContainer = Color(0xFF293E33),
    tertiary = Color(0xFFE7B38F), tertiaryContainer = Color(0xFF4C3528),
    background = Color(0xFF17151D), onBackground = Color(0xFFF2ECF8),
    surface = Color(0xFF211E29), onSurface = Color(0xFFF2ECF8),
    surfaceVariant = Color(0xFF302B3A), onSurfaceVariant = Color(0xFFB2A8C2),
    outline = Color(0xFF6E647D), outlineVariant = Color(0xFF393141),
)

@Composable
fun ParaTheme(profile: GroupProfile, content: @Composable () -> Unit) {
    // Observe configuration updates as well as dark mode (including a new Monet palette).
    val configuration = LocalConfiguration.current
    val dark = when (profile.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
    val context = LocalContext.current
    val colors = remember(configuration, dark, profile.dynamicColors, context) {
        if (profile.dynamicColors && Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) darkColors else lightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 43.sp, letterSpacing = (-1).sp),
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 38.sp),
            headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        ), content = content,
    )
}


@Composable
fun lessonTint(index: Int): Color = when (index % 4) {
    1 -> MaterialTheme.colorScheme.secondaryContainer
    2 -> MaterialTheme.colorScheme.tertiaryContainer
    3 -> MaterialTheme.colorScheme.surfaceVariant
    else -> MaterialTheme.colorScheme.primaryContainer
}
