package com.airtouch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0B7C70)
private val TealLight = Color(0xFF4FCDBB)

@Composable
fun AirTouchTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = TealLight) else lightColorScheme(primary = Teal)
    MaterialTheme(colorScheme = colors, content = content)
}
