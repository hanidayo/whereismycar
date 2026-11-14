package kr.hanidayo.eodi.ui

import android.Manifest
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.Mic
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.google.android.gms.location.LocationServices
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupOff
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kr.hanidayo.eodi.data.Car
import kr.hanidayo.eodi.data.ParkingRecord
import kr.hanidayo.eodi.data.Store
import kr.hanidayo.eodi.data.SyncStatus
import kr.hanidayo.eodi.drive.DriveService
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    resumeTick: Int,
    onOpen: (String) -> Unit,
    onHistory: () -> Unit,
    onAskPermissions: (Array<String>) -> Unit,
) {
    val data by Store.data.collectAsStateWithLifecycle()
    val sync by Store.syncStatus.collectAsStateWithLifecycle()
    val live by DriveService.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val here = rememberLastLocation(resumeTick)
    // 홈에 먼저 보여 줄 차: 직접 고른 차 → 운전 중인 차 → 첫 차
    val car = data.cars.firstOrNull { it.id == data.homeCarId }
        ?: data.cars.firstOrNull { it.id == live?.state?.carId }
        ?: data.cars.firstOrNull()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; Store.refresh(); refreshing = false } },
        modifier = Modifier.fillMaxSize().background(Eodi.colors.background),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.statusBarsPadding(),
        ) {
            item {
                Column(Modifier.padding(top = 14.dp, start = 4.dp)) {
                    Text(
                        "어디댔지",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            brush = Brush.horizontalGradient(listOf(Eodi.colors.primary, Eodi.colors.primaryDeep)),
                        ),
                    )
                    SyncLine(sync)
                }
            }
            item(key = "perm-$resumeTick") { PermissionCard(onAskPermissions) }
            if (car != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocationOn, null, tint = Eodi.colors.ink, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("오늘 주차 위치", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Eodi.colors.ink,
                            modifier = Modifier.weight(1f))
                        CarPill(data.cars, car) { Store.setHomeCar(it) }
                    }
                }
                item {
                    val r = Store.current(data, car.id)
                    val driving = live?.state?.carId == car.id
                    val place = r?.let { Store.place(data, it) }
                    HeroCard(r, driving, live?.delta, place?.name, PlaceIcon.resolve(place?.icon, place?.name)) { r?.let { onOpen(it.id) } }
                }

                item { SectionHeader("감지 상태") }
                item {
                    val r = Store.current(data, car.id)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val driving = live?.state?.carId == car.id
                        StatusTile("차량 상태", if (driving) "운전 중" else "시동 꺼짐", if (driving) "기록 중" else null,
                            Modifier.weight(1f)) {
                            Box(Modifier.size(46.dp).clip(CircleShape)
                                .background(if (driving) Color(0x24FF5A6E) else Eodi.colors.soft),
                                contentAlignment = Alignment.Center) {
                                if (driving) PulsingDot() else Icon(Icons.Filled.PowerSettingsNew, null, tint = Eodi.colors.secondaryText)
                            }
                        }
                        val lat = r?.latitude
                        val lon = r?.longitude
                        val distText = when {
                            r == null -> "기록 없음"
                            lat == null || lon == null -> "위치 없음"
                            here == null -> "위치 받는 중…"
                            else -> {
                                val out = FloatArray(1)
                                android.location.Location.distanceBetween(lat, lon, here.latitude, here.longitude, out)
                                // 오차보다 가까우면 거리 대신 "근처"
                                if (out[0] <= maxOf(here.accuracy, 30f)) "바로 근처"
                                else "${formatDistance(out[0])} · ±${here.accuracy.toInt()}m"
                            }
                        }
                        StatusTile("주차장 입구까지", "차와의 거리", distText,
                            Modifier.weight(1f)) {
                            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0x265AA9F5)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.LocationOn, null, tint = Color(0xFF3F8EE8))
                            }
                        }
                    }
                }
            }
            item { ManualControls(car, car?.let { Store.current(data, it.id) }) }
            item { SectionHeader("최근 기록", "전체 보기", onHistory) }
            item {
                val recent = data.records.sortedByDescending { it.instant }.take(4)
                EodiCard {
                    if (recent.isEmpty()) {
                        Text("아직 주차 기록이 없어요", color = Eodi.colors.secondaryText, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                    }
                    recent.forEachIndexed { i, r ->
                        RecordRow(r) { onOpen(r.id) }
                        if (i < recent.lastIndex) HorizontalDivider(Modifier.padding(start = 66.dp), color = Eodi.colors.soft)
                    }
                }
            }
        }
    }
}

fun formatDistance(m: Float): String = if (m < 1000) "${m.toInt()}m" else String.format(Locale.US, "%.1fkm", m / 1000)

@SuppressLint("MissingPermission")
@Composable
fun rememberLastLocation(tick: Int): android.location.Location? {
    val ctx = LocalContext.current
    var loc by remember { mutableStateOf<android.location.Location?>(null) }
    LaunchedEffect(tick) {
        if (granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            // 저장된 위치는 오래됐을 수 있어서, 2분 안의 것만 먼저 쓰고 지금 위치를 새로 받음
            client.lastLocation.addOnSuccessListener {
                if (it != null && System.currentTimeMillis() - it.time < 120_000 && loc == null) loc = it
            }
            client.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { if (it != null) loc = it }
        }
    }
    return loc
}

@Composable
private fun CarPill(cars: List<Car>, selected: Car, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Eodi.colors.soft)
                .clickable(enabled = cars.size > 1) { open = true }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected.name, color = Eodi.colors.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (cars.size > 1) Icon(Icons.Filled.KeyboardArrowDown, null, tint = Eodi.colors.primary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            cars.forEach { c ->
                DropdownMenuItem(text = { Text(c.name) }, onClick = { onPick(c.id); open = false },
                    trailingIcon = { if (c.id == selected.id) Icon(Icons.Filled.Check, null) })
            }
        }
    }
}

@Composable
private fun HeroCard(
    r: ParkingRecord?, driving: Boolean, delta: Double?, placeName: String?, placeIcon: PlaceIcon,
    onClick: () -> Unit,
) {
    val primary = Eodi.colors.primary
    HeroBox(primary, Modifier.clip(RoundedCornerShape(30.dp)).clickable(enabled = r != null && !driving, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 210.dp).padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (driving) Icons.Filled.Sensors else if (placeName != null) placeIcon.icon else Icons.Filled.MyLocation, null,
                        tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (driving) "운전 기록 중" else placeName ?: "현재 위치 감지", color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    when {
                        driving -> "주차하면\n자동으로 기록해요"
                        r != null -> "${longLevelName(r.level)}${r.pillarText?.let { " $it" } ?: ""}에\n주차했어요"
                        else -> "아직 주차\n기록이 없어요"
                    },
                    color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp,
                )
                val yellow = Color(0xFFFFE08A)
                val pill = when {
                    driving && delta != null -> buildAnnotatedString {
                        append("입구 대비 "); withStyle(SpanStyle(color = yellow)) { append(String.format(Locale.US, "%+.1fm", delta)) }
                    }
                    !driving && r != null -> buildAnnotatedString {
                        append("기록 완료 · "); withStyle(SpanStyle(color = yellow)) { append(longLevelName(r.level)) }
                    }
                    else -> null
                }
                if (pill != null) {
                    Text(pill, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            CarIllustration(if (driving) null else r?.level, primary, 120.dp)
        }
    }
}

@Composable
private fun StatusTile(
    title: String, value: String, detail: String?, modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    EodiCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = Eodi.colors.secondaryText, fontSize = 12.sp)
                Text(value, color = Eodi.colors.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                if (detail != null) Text(detail, color = Eodi.colors.secondaryText, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** 기록 한 줄 (홈·기록 탭 공용) */
@Composable
fun RecordRow(r: ParkingRecord, onClick: () -> Unit) {
    val data by Store.data.collectAsStateWithLifecycle()
    val garage = Store.place(data, r)
    val place = garage?.name ?: if (r.latitude == null) "위치 없음" else "주차장"
    val icon = PlaceIcon.resolve(garage?.icon, garage?.name)
    val color = icon.color
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaceBadge(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("$place · ${Store.carName(r.carId)}", color = Eodi.colors.secondaryText, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(longLevelName(r.level), color = color, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(friendlyDate(r.instant), color = Eodi.colors.secondaryText, fontSize = 13.sp)
            Text(r.member, color = Eodi.colors.tertiaryText, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Eodi.colors.tertiaryText)
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

@Composable
private fun SyncLine(sync: SyncStatus) {
    val c = Eodi.colors.secondaryText
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)) {
        when (sync) {
            SyncStatus.Off -> { Icon(Icons.Filled.GroupOff, null, tint = c, modifier = Modifier.size(15.dp)); Text("가족 공유 꺼짐", color = c, fontSize = 13.sp) }
            SyncStatus.Syncing -> { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp); Text("동기화 중", color = c, fontSize = 13.sp) }
            is SyncStatus.Ok -> {
                Icon(Icons.Filled.Group, null, tint = c, modifier = Modifier.size(15.dp))
                Text("가족과 공유 중 · ${timeFmt.format(sync.at.atZone(ZoneId.systemDefault()))}", color = c, fontSize = 13.sp)
            }
            is SyncStatus.Failed -> {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFFF9500), modifier = Modifier.size(15.dp))
                Text(sync.message, color = c, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun relativeTime(r: ParkingRecord): String =
    DateUtils.getRelativeTimeSpanString(r.instant.toEpochMilli(), System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS).toString()

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionCard(onAsk: (Array<String>) -> Unit) {
    val ctx = LocalContext.current
    val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    val background = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val bt = Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)
    if (fine && background && bt) return

    val orange = Color(0xFFFF9500)
    EodiCard(tint = orange) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.LocationOff, null, tint = orange)
            Text("권한이 더 필요해요", color = orange, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !fine || !bt -> "위치와 근처 기기(블루투스) 권한을 허용해 주세요."
                else -> "앱을 안 켜도 주차를 기록하려면 위치 권한을 '항상 허용'으로 바꿔 주세요. 배터리 설정에서 '제한 없음'도 권장해요."
            },
            color = Eodi.colors.secondaryText, fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                when {
                    !fine || !bt -> onAsk(buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                    }.toTypedArray())
                    else -> onAsk(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            },
            border = BorderStroke(1.dp, orange),
        ) { Text("권한 허용", color = orange) }
        if (fine && bt) {
            Text(
                "앱 설정 열기",
                color = MaterialTheme.colorScheme.primary, fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp).clickable {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                },
            )
        }
    }
}

@Composable
private fun ManualControls(car: Car?, current: ParkingRecord?) {
    val ctx = LocalContext.current
    val listen = rememberVoicePillar(current)
    EodiCard {
        Text("수동 기록" + (car?.let { " · ${it.name}" } ?: ""), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Eodi.colors.ink)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(50)).background(Eodi.colors.soft)
                    .clickable { car?.let { DriveService.start(ctx, it.id) } },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DirectionsCar, null, tint = Eodi.colors.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("운전 시작", color = Eodi.colors.primary, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Eodi.colors.primary, Eodi.colors.primaryDeep)))
                    .clickable { DriveService.park(ctx, car?.id) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.LocalParking, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("지금 주차", color = Color.White, fontWeight = FontWeight.Bold)
            }
            // 기둥 위치 말하기 (이 차의 마지막 기록에)
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(Eodi.colors.soft)
                    .clickable(enabled = current != null) { listen() }
                    .alpha(if (current != null) 1f else 0.4f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Mic, "기둥 위치 말하기", tint = Eodi.colors.primary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("평소에는 차 블루투스가 연결·해제될 때 자동으로 기록돼요. 🎤 버튼으로 기둥 위치를 말하면 마지막 기록에 저장돼요.",
            color = Eodi.colors.secondaryText, fontSize = 12.sp)
    }
}
