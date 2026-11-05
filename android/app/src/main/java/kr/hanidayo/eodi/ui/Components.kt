package kr.hanidayo.eodi.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.foundation.shape.GenericShape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.hanidayo.eodi.data.Level

/** 층 표시 타일: 차 색 둥근 사각형 안에 B3 */
@Composable
fun FloorTile(level: Int?, color: Color, size: Dp = 76.dp) {
    val text = level?.let(Level::name) ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.78f), color))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = (size.value * if (text.length > 2) 0.32f else 0.4f).sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** 흰 카드 (tint를 주면 옅은 색 카드) */
@Composable
fun EodiCard(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (tint == null) Modifier.shadow(10.dp, shape, ambientColor = Eodi.colors.primary.copy(alpha = 0.25f), spotColor = Eodi.colors.primary.copy(alpha = 0.18f)) else Modifier)
            .clip(shape)
            .background(tint?.copy(alpha = 0.12f) ?: Eodi.colors.card)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Chip(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PulsingDot(color: Color = Color(0xFFFF5A6E)) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(10.dp).alpha(a).clip(CircleShape).background(color))
}

/** "지하 3층" / "1층" / "층 모름" */
fun longLevelName(level: Int?): String = when {
    level == null -> "층 모름"
    level >= 0 -> "${level + 1}층"
    else -> "지하 ${-level}층"
}

private val clock = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
private val monthDay = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)

/** "오늘 오후 4:05" / "어제 오후 10:52" / "11월 3일 오후 4:05" */
fun friendlyDate(instant: Instant): String {
    val z = instant.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (z.toLocalDate()) {
        today -> "오늘"
        today.minusDays(1) -> "어제"
        else -> monthDay.format(z)
    }
    return "$day ${clock.format(z)}"
}

/** 섹션 제목 + 오른쪽 링크 */
@Composable
fun SectionHeader(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Eodi.colors.ink, modifier = Modifier.weight(1f))
        if (trailing != null && onTrailing != null) {
            Text("$trailing ›", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Eodi.colors.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickableNoRipple(onTrailing).padding(4.dp))
        }
    }
}

private val PinTail = GenericShape { size, _ ->
    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close()
}

/** 핀 모양 뱃지 (B3) */
@Composable
fun PinBadge(text: String, color: Color, size: Dp = 46.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = color, fontWeight = FontWeight.Black, maxLines = 1,
                fontSize = (size.value * if (text.length > 2) 0.3f else 0.38f).sp)
        }
        Box(Modifier.offset(y = (-size.value * 0.1f).dp).width(size * 0.34f).height(size * 0.26f).clip(PinTail).background(Color.White))
    }
}

/** 둥근 배경 위 차 + 층 핀 */
@Composable
fun CarIllustration(level: Int?, color: Color, size: Dp = 124.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)))
        Box(Modifier.size(size * 0.72f).offset(y = size * 0.1f).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)))
        Icon(Icons.Filled.DirectionsCar, null, tint = Color.White, modifier = Modifier.size(size * 0.5f).offset(y = size * 0.16f))
        Box(Modifier.offset(y = -size * 0.2f)) { PinBadge(level?.let(Level::name) ?: "?", color, size * 0.34f) }
    }
}

/** 큰 그라데이션 카드 */
@Composable
fun HeroBox(color: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = color, spotColor = color)
            .clip(shape)
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.82f), color))),
    ) {
        Box(Modifier.size(260.dp).align(Alignment.BottomEnd).offset(x = 90.dp, y = 110.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)))
        Box(Modifier.size(180.dp).align(Alignment.BottomStart).offset(x = (-80).dp, y = 100.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
        content()
    }
}

/** 물결 없는 클릭 */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(
        interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null, onClick = onClick,
    ))

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        fontSize = 13.sp,
        color = Eodi.colors.secondaryText,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(key, color = Eodi.colors.secondaryText, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp)
    }
}
