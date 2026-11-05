package kr.hanidayo.eodi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 아이폰 앱과 같은 순서의 차 색 (흰 글씨가 잘 보이는 파스텔) */
object CarPalette {
    val colors = listOf(
        Color(0xFF7C6CF2), // 보라
        Color(0xFF36B9A0), // 민트
        Color(0xFFFF8A65), // 코랄
        Color(0xFFF06CA8), // 핑크
        Color(0xFF5AA9F5), // 하늘
        Color(0xFFF5B53F), // 노랑
        Color(0xFF8BC34A), // 연두
        Color(0xFF8E94AD), // 회색
    )

    fun color(i: Int): Color = colors[((i % colors.size) + colors.size) % colors.size]
}

/** 파스텔 보라 톤 */
data class EodiColors(
    val primary: Color,
    val primaryDeep: Color,
    val background: Color,
    val card: Color,
    val soft: Color,
    val ink: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
)

private val Light = EodiColors(
    primary = Color(0xFF6C5CE7), primaryDeep = Color(0xFF5243D6),
    background = Color(0xFFF6F4FF), card = Color.White, soft = Color(0xFFEFEBFF),
    ink = Color(0xFF1F1B3D), secondaryText = Color(0xFF8A86A6), tertiaryText = Color(0xFFB6B3CC),
)
private val Dark = EodiColors(
    primary = Color(0xFF8F82FF), primaryDeep = Color(0xFF6E60F0),
    background = Color(0xFF121022), card = Color(0xFF1E1B33), soft = Color(0xFF2A2650),
    ink = Color(0xFFF2F0FF), secondaryText = Color(0xFF9C98BA), tertiaryText = Color(0xFF65618A),
)

object Eodi {
    val colors: EodiColors
        @Composable get() = if (isSystemInDarkTheme()) Dark else Light
}

@Composable
fun EodiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = Color.White, secondaryContainer = c.soft,
            onSecondaryContainer = c.primary, background = c.background, surface = c.card,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, onSurface = c.ink,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = Color.White, secondaryContainer = c.soft,
            onSecondaryContainer = c.primary, background = c.background, surface = c.card,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, onSurface = c.ink,
        )
    }
    val base = Typography()
    val typography = base.copy(
        headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
