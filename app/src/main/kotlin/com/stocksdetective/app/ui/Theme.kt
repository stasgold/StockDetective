package com.stocksdetective.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpCenter
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.stocksdetective.kit.Verdict
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object Palette {
    val accent = Color(0xFFF2B340) // detective amber
    val up = Color(0xFF33BF73)
    val down = Color(0xFFEB4D4D)
    val gray = Color(0xFF8E8E93)

    fun change(v: Double): Color = if (v >= 0) up else down

    fun verdict(v: Verdict): Color = when (v) {
        Verdict.JUSTIFIED -> Color(0xFF0A84FF)
        Verdict.OVERREACTION -> Color(0xFFFF9F0A)
        Verdict.UNDERREACTION -> Color(0xFFBF5AF2)
        Verdict.UNEXPLAINED -> Color(0xFFFF453A)
        Verdict.NOISE -> gray
    }

    fun verdictIcon(v: Verdict): ImageVector = when (v) {
        Verdict.JUSTIFIED -> Icons.Filled.Verified
        Verdict.OVERREACTION -> Icons.Filled.LocalFireDepartment
        Verdict.UNDERREACTION -> Icons.Filled.AcUnit
        Verdict.UNEXPLAINED -> Icons.AutoMirrored.Filled.HelpCenter
        Verdict.NOISE -> Icons.Filled.GraphicEq
    }
}

private val light: ColorScheme = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color.White,
    primaryContainer = Palette.accent,
    onPrimaryContainer = Color(0xFF2B1A00),
    secondary = Palette.accent,
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFEDEDF2),
    background = Color.White,
    surface = Color.White,
)

private val dark: ColorScheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF2B1A00),
    primaryContainer = Color(0xFF6B4700),
    onPrimaryContainer = Color(0xFFFFDDAA),
    secondary = Palette.accent,
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    background = Color.Black,
    surface = Color.Black,
)

@Composable
fun StocksDetectiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}

private val usd: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US).apply { currency = Currency.getInstance("USD") }

val Double.signedPercent: String get() = String.format(Locale.US, "%+.2f%%", this)
val Double.currency: String get() = synchronized(usd) { usd.format(this) }
val Double.signedCurrency: String get() = (if (this >= 0) "+" else "") + currency
