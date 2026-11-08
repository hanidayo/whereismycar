package kr.hanidayo.eodi.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kr.hanidayo.eodi.data.Store
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

private enum class CheckStatus { Unknown, Ok, Warn, Fail }

private data class CheckItem(val status: CheckStatus = CheckStatus.Unknown, val detail: String = "점검 전", val time: String? = null)

private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm")
private fun now() = LocalDateTime.now().format(timeFmt)

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private suspend fun readPressure(ctx: Context): Float? {
    val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
    return withTimeoutOrNull(4_000) {
        suspendCancellableCoroutine { c ->
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    sm.unregisterListener(this)
                    if (c.isActive) c.resume(e.values[0])
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            c.invokeOnCancellation { sm.unregisterListener(l) }
        }
    }
}

@SuppressLint("MissingPermission")
private suspend fun readGps(ctx: Context): android.location.Location? {
    if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return null
    val cts = CancellationTokenSource()
    return withTimeoutOrNull(15_000) {
        runCatching {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
        }.getOrNull()
    }.also { if (it == null) cts.cancel() }
}

/** 설정 맨 위: 자동 기록 준비 상태 + GPS·기압 셀프점검 */
@Composable
fun SensorCheckSection(resumeTick: Int = 0) {
    val ctx = LocalContext.current
    val data by Store.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pressure by remember { mutableStateOf(CheckItem()) }
    var gps by remember { mutableStateOf(CheckItem()) }
    var running by remember { mutableStateOf(false) }

    fun run() {
        if (running) return
        running = true
        scope.launch {
            val hasBaro = (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
                .getDefaultSensor(Sensor.TYPE_PRESSURE) != null
            pressure = if (!hasBaro) CheckItem(CheckStatus.Fail, "이 폰에는 기압계가 없어요", now())
            else readPressure(ctx)?.let { CheckItem(CheckStatus.Ok, String.format("정상 · %.1f hPa", it), now()) }
                ?: CheckItem(CheckStatus.Fail, "값을 못 읽었어요", now())

            gps = CheckItem(CheckStatus.Unknown, "위치 받는 중…")
            val loc = readGps(ctx)
            gps = when {
                loc == null -> CheckItem(CheckStatus.Fail, "위치를 못 받았어요 · 위치 권한 확인", now())
                loc.hasVerticalAccuracy() && loc.hasAltitude() && loc.accuracy <= 25f ->
                    CheckItem(CheckStatus.Ok, "위성 신호 · 정확도 ${loc.accuracy.toInt()}m", now())
                else -> CheckItem(CheckStatus.Warn, "위성 신호 약함 · 정확도 ${loc.accuracy.toInt()}m\n실내면 밖에서 다시 점검해 보세요", now())
            }
            running = false
        }
    }

    SectionTitleText("자동 기록 준비 상태")
    EodiCard {
        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notif = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        val btScan = granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        val linked = data.cars.count { data.carDevices[it.id] != null }
        ReadyRow("정확한 위치 권한", if (fine) CheckStatus.Ok else CheckStatus.Fail, null)
        ReadyRow("항상 위치 허용", if (bg) CheckStatus.Ok else CheckStatus.Warn, if (bg) null else "화면이 꺼진 채 운전하면 기록이 안 될 수 있어요")
        ReadyRow("근처 기기(블루투스) 권한", if (btScan) CheckStatus.Ok else CheckStatus.Fail, null)
        ReadyRow("차량 블루투스 지정", if (linked == data.cars.size && linked > 0) CheckStatus.Ok else CheckStatus.Warn,
            "${linked}/${data.cars.size}대 · 차 편집에서 지정")
        if (data.activityCarId != null) {
            val act = granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)
            ReadyRow("신체 활동 권한 (탑승 감지)", if (act) CheckStatus.Ok else CheckStatus.Fail, null)
        }
        ReadyRow("알림 권한", if (notif) CheckStatus.Ok else CheckStatus.Warn, null)
        ReadyRow("가족 공유", if (data.sync.isConfigured) CheckStatus.Ok else CheckStatus.Warn,
            if (data.sync.isConfigured) null else "꺼져 있어요")
    }

    SectionTitleText("GPS · 기압 셀프점검")
    EodiCard {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            CheckTile("기압 센서", pressure, Modifier.weight(1f))
            Spacer(Modifier.width(1.dp).fillMaxHeight().background(Eodi.colors.soft))
            Spacer(Modifier.width(12.dp))
            CheckTile("GPS", gps, Modifier.weight(1f))
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Eodi.colors.soft)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !running) { run() }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (running) "점검 중…" else "셀프점검 하기", color = Eodi.colors.primary, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("›", color = Eodi.colors.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
    Text("GPS는 하늘이 보이는 곳에서 점검해야 정확해요. 층 기준점은 위성 신호가 잡힐 때만 정해져요.",
        color = Eodi.colors.secondaryText, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
}

@Composable
private fun SectionTitleText(text: String) {
    Text(text, color = Eodi.colors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
}

private fun statusIcon(s: CheckStatus) = when (s) {
    CheckStatus.Ok -> Icons.Filled.CheckCircle to Color(0xFF34C38F)
    CheckStatus.Warn -> Icons.Filled.Error to Color(0xFFE69A28)
    CheckStatus.Fail -> Icons.Filled.Cancel to Color(0xFFEF5350)
    CheckStatus.Unknown -> Icons.Filled.RadioButtonUnchecked to Color(0xFF9C98BA)
}

@Composable
private fun ReadyRow(title: String, s: CheckStatus, detail: String?) {
    val (icon, color) = statusIcon(s)
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Eodi.colors.ink, fontSize = 15.sp)
            if (detail != null) Text(detail, color = Eodi.colors.secondaryText, fontSize = 12.sp)
        }
        Text(if (s == CheckStatus.Ok) "완료" else "확인 필요", color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CheckTile(title: String, item: CheckItem, modifier: Modifier) {
    val (icon, color) = statusIcon(item.status)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, color = Eodi.colors.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Text(item.detail, color = if (item.status == CheckStatus.Ok) color else Eodi.colors.secondaryText, fontSize = 13.sp)
        item.time?.let { Text(it, color = Eodi.colors.tertiaryText, fontSize = 12.sp) }
    }
}
