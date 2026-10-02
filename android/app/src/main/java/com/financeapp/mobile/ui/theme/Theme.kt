package com.financeapp.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ModernShapes = Shapes(
    extraSmall = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

private val FinanceLight = lightColorScheme(
    primary = Color(0xFF1479F8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FF),
    onPrimaryContainer = Color(0xFF0B3266),
    secondary = Color(0xFF6D4DF4),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F4F9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFCFE),
    surfaceContainer = Color(0xFFF7F9FC),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF3F6FA),
    onSurface = Color(0xFF111827),
    onSurfaceVariant = Color(0xFF5B6678),
    outline = Color(0xFFB8C3D3),
    outlineVariant = Color(0xFFD7DEE8),
    error = Color(0xFFE34B58)
)

private val FinanceDark = darkColorScheme(
    primary = Color(0xFF65A7FF),
    onPrimary = Color(0xFF052A55),
    primaryContainer = Color(0xFF123B6D),
    secondary = Color(0xFFA997FF),
    background = Color(0xFF07111F),
    surface = Color(0xFF0C1B2D),
    surfaceVariant = Color(0xFF12243A),
    surfaceContainerLowest = Color(0xFF081421),
    surfaceContainerLow = Color(0xFF0B1929),
    surfaceContainer = Color(0xFF0E2034),
    surfaceContainerHigh = Color(0xFF12243A),
    surfaceContainerHighest = Color(0xFF172B43),
    onSurface = Color(0xFFF4F7FC),
    onSurfaceVariant = Color(0xFFC4CFDD),
    outline = Color(0xFF50647C),
    outlineVariant = Color(0xFF30445C),
    error = Color(0xFFFF6F7C)
)

@Composable
fun FinanceTheme(themeMode: String = "SYSTEM", content: @Composable () -> Unit) {
    val useDark = when (themeMode.uppercase()) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) FinanceDark else FinanceLight,
        shapes = ModernShapes,
        content = content
    )
}
