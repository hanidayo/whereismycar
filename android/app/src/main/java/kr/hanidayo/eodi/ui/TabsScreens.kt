package kr.hanidayo.eodi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.hanidayo.eodi.data.Store
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
private fun TabTitle(text: String) {
    Text(text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Eodi.colors.ink,
        modifier = Modifier.padding(top = 14.dp, start = 4.dp, bottom = 4.dp))
}

@Composable
private fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (on) Color.White else Eodi.colors.primary,
        fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (on) Eodi.colors.primary else Eodi.colors.soft)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

private val dayFmt = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

/** 기록 탭: 모든 주차 기록을 날짜별로 */
@Composable
fun HistoryScreen(onOpen: (String) -> Unit) {
    val data by Store.data.collectAsStateWithLifecycle()
    var carFilter by remember { mutableStateOf<String?>(null) }
    val records = data.records.filter { carFilter == null || it.carId == carFilter }.sortedByDescending { it.instant }
    val days = records.groupBy { it.instant.atZone(ZoneId.systemDefault()).toLocalDate() }.toList()
    val today = LocalDate.now()

    LazyColumn(
        Modifier.fillMaxSize().background(Eodi.colors.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TabTitle("주차 기록") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip("전체", carFilter == null) { carFilter = null } }
                items(data.cars, key = { it.id }) { c -> FilterChip(c.name, carFilter == c.id) { carFilter = c.id } }
            }
        }
        if (records.isEmpty()) {
            item { Text("기록이 없어요", color = Eodi.colors.secondaryText, modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) }
        }
        days.forEach { (day, items) ->
            item(key = day.toString()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (day) { today -> "오늘"; today.minusDays(1) -> "어제"; else -> dayFmt.format(day) },
                        color = Eodi.colors.secondaryText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    EodiCard {
                        items.forEachIndexed { i, r ->
                            RecordRow(r) { onOpen(r.id) }
                            if (i < items.lastIndex) HorizontalDivider(Modifier.padding(start = 66.dp), color = Eodi.colors.soft)
                        }
                    }
                }
            }
        }
    }
}

/** 위치 탭: 차마다 지금 주차 위치와 거리, 지도 앱으로 열기 */
@Composable
fun LocationScreen(resumeTick: Int, onOpen: (String) -> Unit) {
    val data by Store.data.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val here = rememberLastLocation(resumeTick)

    LazyColumn(
        Modifier.fillMaxSize().background(Eodi.colors.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TabTitle("위치") }
        items(data.cars, key = { it.id }) { car ->
            val r = Store.current(data, car.id)
            val color = CarPalette.color(car.color)
            EodiCard(Modifier.clip(RoundedCornerShape(24.dp)).clickable(enabled = r != null) { r?.let { onOpen(it.id) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloorTile(r?.level, color, 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(car.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Eodi.colors.ink)
                        Text(
                            if (r == null) "기록 없음" else "${longLevelName(r.level)} · ${friendlyDate(r.instant)}",
                            color = Eodi.colors.secondaryText, fontSize = 13.sp,
                        )
                        val lat = r?.latitude
                        val lon = r?.longitude
                        if (lat != null && lon != null && here != null) {
                            val out = FloatArray(1)
                            android.location.Location.distanceBetween(lat, lon, here.latitude, here.longitude, out)
                            Text("여기서 ${formatDistance(out[0])}", color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    val lat = r?.latitude
                    val lon = r?.longitude
                    if (lat != null && lon != null) {
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Eodi.colors.soft)
                                .clickable {
                                    val label = Uri.encode("${car.name} ${longLevelName(r.level)}")
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")))
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Map, null, tint = Eodi.colors.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("지도", color = Eodi.colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
