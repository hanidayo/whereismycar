package kr.hanidayo.eodi.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.hanidayo.eodi.data.Level
import kr.hanidayo.eodi.data.ParkingRecord
import kr.hanidayo.eodi.data.Store
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN)

@Composable
fun DetailScreen(recordId: String, onBack: () -> Unit) {
    val data by Store.data.collectAsStateWithLifecycle()
    val r = data.records.firstOrNull { it.id == recordId }
    if (r == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val car = data.cars.firstOrNull { it.id == r.carId }
    val color = CarPalette.color(car?.color ?: 0)
    val carName = car?.name ?: "삭제된 차"

    LaunchedEffect(recordId) { Store.fetchRemotePhoto(recordId) }

    Column(
        Modifier.fillMaxSize().background(Eodi.colors.background).statusBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text(carName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            var carMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { carMenu = true }) { Icon(Icons.Filled.DirectionsCar, "다른 차로 바꾸기") }
                DropdownMenu(expanded = carMenu, onDismissRequest = { carMenu = false }) {
                    Text("다른 차로 바꾸기", color = Eodi.colors.secondaryText, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    data.cars.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.name) },
                            onClick = { Store.changeCar(r.id, c.id); carMenu = false },
                            trailingIcon = { if (c.id == r.carId) Icon(Icons.Filled.Check, null) },
                        )
                    }
                }
            }
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Hero(r, color)
            if (r.hasLocation) PlaceCard(r)
            VoicePillarCard(r)
            if (r.hasLocation) LocationCard(r, carName)
            PhotoCard(r, color)
            EodiCard {
                r.deltaMeters?.let { InfoRow("입구 대비 높이", String.format(Locale.US, "%+.1fm", it)) }
                r.accuracy?.let { InfoRow("GPS 오차", String.format(Locale.US, "약 %.0fm", it)) }
                InfoRow("기록한 사람", r.member)
                InfoRow("공유", when {
                    r.synced -> "가족에게 공유됨"
                    data.sync.isConfigured -> "올리는 중"
                    else -> "공유 꺼짐"
                })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Hero(r: ParkingRecord, color: Color) {
    var menu by remember { mutableStateOf(false) }
    EodiCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FloorTile(r.level, color, 128.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.calibrated) Chip("보정됨", Color(0xFF34A853), Icons.Filled.Check)
                r.pillarText?.let { Chip(it, color, Icons.Outlined.GridView) }
            }
            Text("${r.member} · ${dateFmt.format(r.instant.atZone(ZoneId.systemDefault()))}",
                color = Eodi.colors.secondaryText, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("층 보정")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (i in 4 downTo -6) {
                            DropdownMenuItem(
                                text = { Text(Level.name(i)) },
                                onClick = { Store.correct(r.id, i); menu = false },
                                trailingIcon = { if (i == r.level) Icon(Icons.Filled.Check, null) },
                            )
                        }
                    }
                }
                val ocr = r.ocrLevel
                if (ocr != null && ocr != r.level) {
                    Button(
                        onClick = { Store.correct(r.id, ocr) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = color),
                    ) {
                        Icon(Icons.Filled.DocumentScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("사진대로 ${Level.name(ocr)}")
                    }
                }
            }
            Text("보정하면 이 주차장의 층 높이를 학습해서 다음부터 더 정확해져요.",
                color = Eodi.colors.tertiaryText, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PlaceCard(r: ParkingRecord) {
    val data by Store.data.collectAsStateWithLifecycle()
    val g = Store.place(data, r)
    var editing by remember { mutableStateOf(false) }
    EodiCard(Modifier.clip(RoundedCornerShape(24.dp)).clickable { editing = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaceBadge(PlaceIcon.resolve(g?.icon, g?.name), 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("장소", color = Eodi.colors.secondaryText, fontSize = 12.sp)
                Text(g?.name ?: "장소 이름 붙이기", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = if (g?.name == null) Eodi.colors.primary else Eodi.colors.ink)
            }
            Icon(Icons.Filled.Edit, "편집", tint = Eodi.colors.secondaryText)
        }
    }
    if (editing) {
        PlaceDialog(g?.name, g?.icon, onDismiss = { editing = false }) { name, icon ->
            Store.setPlace(r.id, name, icon); editing = false
        }
    }
}

@Composable
private fun LocationCard(r: ParkingRecord, carName: String) {
    val ctx = LocalContext.current
    val lat = r.latitude ?: return
    val lon = r.longitude ?: return
    EodiCard(Modifier.clip(RoundedCornerShape(22.dp)).clickable {
        kr.hanidayo.eodi.util.NaverMap.walkTo(ctx, lat, lon, "$carName ${longLevelName(r.level)}")
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Map, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("네이버 지도로 길찾기", style = MaterialTheme.typography.titleMedium)
                Text(String.format(Locale.US, "%.5f, %.5f", lat, lon), color = Eodi.colors.secondaryText, fontSize = 13.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Eodi.colors.tertiaryText)
        }
    }
}

@Composable
private fun PhotoCard(r: ParkingRecord, color: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var analyzing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<File?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending ?: return@rememberLauncherForActivityResult
        if (ok) scope.launch {
            analyzing = true
            withContext(Dispatchers.IO) { Store.attachPhoto(r.id, f) }
            analyzing = false
        } else f.delete()
    }

    val file = Store.photoFile(r)
    val bitmap = remember(file?.path) {
        file?.let { f ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
        }
    }

    EodiCard {
        Text("기둥 사진", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        when {
            bitmap != null -> Image(
                bitmap, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)),
            )
            r.hasPhoto -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("사진 받는 중…", color = Eodi.colors.secondaryText)
            }
        }
        if (analyzing) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("글자 읽는 중…", color = Eodi.colors.secondaryText)
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = {
                val f = Store.newPhotoFile(r.id)
                pending = f
                camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = color.copy(alpha = 0.14f), contentColor = color),
        ) {
            Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (r.photoFile == null) "기둥 사진 찍기" else "다시 찍기")
        }
    }
}
