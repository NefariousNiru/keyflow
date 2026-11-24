package io.keyflow.remotekeyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val light = lightColorScheme(
    primary = Color(0xFF424E99),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF202B64),
    surface = Color(0xFFF5F7FF),
    onSurface = Color(0xFF181F35),
    surfaceVariant = Color(0xFFDCE5F6),
    onSurfaceVariant = Color(0xFF384463),
    outline = Color(0xFF697795),
    outlineVariant = Color(0xFFB3C0DC)
)
private val dark = darkColorScheme(
    primary = Color(0xFFC1C8FF),
    onPrimary = Color(0xFF222D65),
    primaryContainer = Color(0xFF424F89),
    onPrimaryContainer = Color(0xFFE9ECFF),
    surface = Color(0xFF171D33),
    onSurface = Color(0xFFE8EDFF),
    surfaceVariant = Color(0xFF303A56),
    onSurfaceVariant = Color(0xFFC8D3F1),
    outline = Color(0xFF98A5C7),
    outlineVariant = Color(0xFF596789)
)

@Composable
internal fun KeyFlowTheme(content: @Composable () -> Unit) {
    val night = isSystemInDarkTheme()
    val backdrop = if (night) listOf(Color(0xFF212E4A), Color(0xFF171D33), Color(0xFF28233E))
        else listOf(Color(0xFFE3F2FA), Color(0xFFF3F6FF), Color(0xFFEAE5F8))
    MaterialTheme(
        colorScheme = if (night) dark else light,
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp))
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(backdrop))) { content() }
    }
}
