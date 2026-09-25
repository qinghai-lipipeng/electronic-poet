package com.otaku.poet.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalView

// 浅色：暖纸 + 墨蓝
private val LightColors = lightColorScheme(
    primary = Color(0xFF394575),
    onPrimary = Color(0xFFF5F2EA),
    primaryContainer = Color(0xFFE3E6F2),
    onPrimaryContainer = Color(0xFF232B4A),
    secondary = Color(0xFF7A6A4F),
    background = Color(0xFFFAF7F1),
    onBackground = Color(0xFF23211D),
    surface = Color(0xFFFDFBF6),
    onSurface = Color(0xFF23211D),
    surfaceVariant = Color(0xFFEFEAE0),
    onSurfaceVariant = Color(0xFF4A463E),
    outline = Color(0xFFBDB6A8),
)

// 深色：夜墨
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB3BDE0),
    onPrimary = Color(0xFF1E2540),
    primaryContainer = Color(0xFF333D63),
    onPrimaryContainer = Color(0xFFE3E6F2),
    secondary = Color(0xFFD2BE9C),
    background = Color(0xFF15171D),
    onBackground = Color(0xFFE6E2D8),
    surface = Color(0xFF1B1E25),
    onSurface = Color(0xFFE6E2D8),
    surfaceVariant = Color(0xFF2A2E38),
    onSurfaceVariant = Color(0xFFC7C2B6),
    outline = Color(0xFF555A66),
)

@Composable
fun ElectronicPoetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity
            activity?.window?.statusBarColor = Color.Transparent.toArgb()
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = PoetTypography,
        content = content,
    )
}
