@file:Suppress("FunctionName")

package dev.mo.darkylock.ui.theme

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// الخلفية سوداء ودرجات الوردي هي لون الهوية والإجراءات؛ تبقى ألوان الإشارة مستقلة لتوضيح جودتها.
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFF29AB9),
        onPrimary = Color(0xFF2B2326),
        primaryContainer = Color(0xFFF7B4CA),
        onPrimaryContainer = Color(0xFF2B2326),
        secondary = Color(0xFFF6A2BE),
        onSecondary = Color(0xFF2B2326),
        secondaryContainer = Color(0xFFF9C2D3),
        onSecondaryContainer = Color(0xFF2B2326),
        tertiary = Color(0xFFE0B66D),
        onTertiary = Color(0xFF302100),
        tertiaryContainer = Color(0xFF4A3510),
        onTertiaryContainer = Color(0xFFFFE2A8),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF2E9EC),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFF2E9EC),
        surfaceVariant = Color(0xFF252225),
        onSurfaceVariant = Color(0xFFC9BBC0),
        outline = Color(0xFF8B8287),
        outlineVariant = Color(0xFF474247),
        inverseSurface = Color(0xFFF2E9EC),
        inverseOnSurface = Color(0xFF1A1A1A),
        inversePrimary = Color(0xFFD44E7D),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = Color(0xFF1C1C1C),
        surfaceContainerHighest = Color(0xFF262626),
    )

private val AccentPink = Color(0xFFF29AB9)
private val SignalScale = listOf(Color(0xFF7BE5A0), Color(0xFFE0D879), Color(0xFFF0B86E), Color(0xFFFF8A7E))

val LocalAccentPink = staticCompositionLocalOf { AccentPink }
val LocalSignalScale = staticCompositionLocalOf { SignalScale }

@Composable
fun DarkyTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAccentPink provides AccentPink,
        LocalSignalScale provides SignalScale,
    ) {
        MaterialExpressiveTheme(
            colorScheme = DarkColors,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
