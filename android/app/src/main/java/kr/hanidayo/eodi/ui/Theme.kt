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

/** 아이폰 앱과 같은 순서의 차 색 */
object CarPalette {
    val colors = listOf(
        Color(0xFF1A9E8F), // 청록
        Color(0xFF5452CC), // 남보라
        Color(0xFFED7333), // 주황
        Color(0xFFD94D7A), // 분홍
        Color(0xFF4D9933), // 초록
        Color(0xFF297DDB), // 파랑
        Color(0xFF8C6B4D), // 갈색
        Color(0xFF666B78), // 회색
    )

    fun color(i: Int): Color = colors[((i % colors.size) + colors.size) % colors.size]
}

/** iOS 느낌의 그룹 배경 + 흰 카드 */
data class EodiColors(
    val background: Color,
    val card: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
)

private val Light = EodiColors(Color(0xFFF2F2F7), Color.White, Color(0xFF6B6B70), Color(0xFFA3A3A8))
private val Dark = EodiColors(Color(0xFF000000), Color(0xFF1C1C1E), Color(0xFF9A9AA0), Color(0xFF636366))

object Eodi {
    val colors: EodiColors
        @Composable get() = if (isSystemInDarkTheme()) Dark else Light
}

@Composable
fun EodiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF3B8FEA), onPrimary = Color.White,
            background = Dark.background, surface = Dark.card, surfaceContainerHigh = Color(0xFF2C2C2E),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF297DDB), onPrimary = Color.White,
            background = Light.background, surface = Light.card, surfaceContainerHigh = Color.White,
        )
    }
    val base = Typography()
    val typography = base.copy(
        headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
