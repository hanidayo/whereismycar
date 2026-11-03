package kr.hanidayo.eodi.ui

import android.Manifest
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
    onSettings: () -> Unit,
    onAskPermissions: (Array<String>) -> Unit,
) {
    val data by Store.data.collectAsStateWithLifecycle()
    val sync by Store.syncStatus.collectAsStateWithLifecycle()
    val live by DriveService.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; Store.refresh(); refreshing = false } },
        modifier = Modifier.fillMaxSize().background(Eodi.colors.background),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.statusBarsPadding(),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("어디댔지", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "설정") }
                }
                SyncLine(sync)
            }
            item(key = "perm-$resumeTick") { PermissionCard(onAskPermissions) }
            live?.let { l -> item { RecordingBanner(l.delta, Store.carName(l.state.carId), l.state.refTime, l.hasBarometer) } }
            items(data.cars, key = { it.id }) { car ->
                val r = Store.current(data, car.id)
                if (r != null) ParkingCard(r, car) { onOpen(r.id) } else EmptyCard(car)
            }
            item { ManualControls(data.cars, live?.state?.carId) }
        }
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

@Composable
private fun ParkingCard(r: ParkingRecord, car: Car, onClick: () -> Unit) {
    val color = CarPalette.color(car.color)
    EodiCard(Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FloorTile(r.level, color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(car.name, style = MaterialTheme.typography.titleMedium)
                Text("${r.member} · ${relativeTime(r)}", color = Eodi.colors.secondaryText, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    r.pillarText?.let { Chip(it, color, Icons.Outlined.GridView) }
                    r.deltaMeters?.let {
                        Text(String.format(Locale.US, "%+.1fm", it), color = Eodi.colors.tertiaryText, fontSize = 12.sp)
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Eodi.colors.tertiaryText)
        }
    }
}

@Composable
private fun EmptyCard(car: Car) {
    val color = CarPalette.color(car.color)
    EodiCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(76.dp).border(BorderStroke(2.dp, color.copy(alpha = 0.5f)), RoundedCornerShape(21.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.DirectionsCar, null, tint = color) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(car.name, style = MaterialTheme.typography.titleMedium)
                Text("아직 주차 기록이 없어요", color = Eodi.colors.secondaryText, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun RecordingBanner(delta: Double?, carName: String, refTime: Long?, hasBarometer: Boolean) {
    EodiCard(tint = Color(0xFFFF3B30)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PulsingDot()
                    Text("운전 기록 중", style = MaterialTheme.typography.titleMedium)
                }
                Text(carName, fontSize = 14.sp)
                Text(
                    refTime?.let { "GPS 기준점 ${timeFmt.format(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" }
                        ?: "GPS 기준점 찾는 중…",
                    color = Eodi.colors.secondaryText, fontSize = 12.sp,
                )
                if (!hasBarometer) Text("이 기기는 기압계가 없어 층을 계산할 수 없어요", color = Color(0xFFFF9500), fontSize = 12.sp)
            }
            delta?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(String.format(Locale.US, "%+.1f", it), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("m", color = Eodi.colors.secondaryText, fontSize = 12.sp)
                }
            }
        }
    }
}

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
private fun ManualControls(cars: List<Car>, drivingCar: String?) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf(drivingCar ?: cars.firstOrNull()?.id) }
    EodiCard {
        Text("수동 기록", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cars, key = { it.id }) { car ->
                val on = selected == car.id
                val color = CarPalette.color(car.color)
                Text(
                    car.name,
                    color = if (on) Color.White else color,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) color else color.copy(alpha = 0.12f))
                        .clickable { selected = car.id }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { selected?.let { DriveService.start(ctx, it) } },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Filled.DirectionsCar, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("운전 시작")
            }
            Button(
                onClick = { DriveService.park(ctx, selected) },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(Icons.Filled.LocalParking, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("지금 주차")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("평소에는 차 블루투스가 연결·해제될 때 자동으로 기록돼요.", color = Eodi.colors.secondaryText, fontSize = 12.sp)
    }
}
