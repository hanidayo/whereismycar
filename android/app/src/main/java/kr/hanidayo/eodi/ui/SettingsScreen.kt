package kr.hanidayo.eodi.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kr.hanidayo.eodi.data.Car
import kr.hanidayo.eodi.data.FloorModel
import kr.hanidayo.eodi.data.Store
import kr.hanidayo.eodi.data.SyncSettings
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val data by Store.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(data.sync.serverUrl) }
    var token by remember { mutableStateOf(data.sync.token) }
    var member by remember { mutableStateOf(data.sync.member) }
    var result by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Car?>(null) }
    var height by remember { mutableStateOf(data.defaultFloorHeight.toFloat()) }

    fun saveSync() {
        val s = SyncSettings(server.trim(), token.trim(), member.trim())
        if (s != data.sync) { Store.setSync(s); Store.registerDevice() }
    }

    Column(Modifier.fillMaxSize().background(Eodi.colors.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { saveSync(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text("설정", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {

            SectionTitle("가족 공유")
            EodiCard {
                OutlinedTextField(server, { server = it }, label = { Text("서버 주소") },
                    placeholder = { Text("eodi.hanidayo.kr") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(token, { token = it }, label = { Text("가족 코드") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(member, { member = it }, label = { Text("내 이름 (알림에 표시)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { saveSync(); scope.launch { result = Store.testConnection() } },
                        modifier = Modifier.weight(1f)) { Text("연결 확인") }
                    OutlinedButton(onClick = { saveSync(); scope.launch { result = Store.testPush() } },
                        modifier = Modifier.weight(1f)) { Text("알림 테스트") }
                }
                result?.let { Text(it, color = Eodi.colors.secondaryText, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            Hint("가족 모두 같은 서버 주소와 가족 코드를 넣으면 주차 기록과 차 목록이 공유되고, 누가 주차하든 모두에게 알림이 가요.")

            SectionTitle("차")
            EodiCard(Modifier.padding(0.dp)) {
                data.cars.forEachIndexed { i, car ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    val addr = data.carDevices[car.id]
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = car }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(CarPalette.color(car.color)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(car.name, fontSize = 16.sp)
                            Text(if (addr != null) "블루투스 자동 기록 켜짐" else "블루투스 기기 미지정",
                                color = Eodi.colors.secondaryText, fontSize = 12.sp)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Eodi.colors.tertiaryText)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth().clickable { editing = Store.addCar() }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("차 추가", color = MaterialTheme.colorScheme.primary)
                }
            }
            Hint("차마다 블루투스 기기를 지정하면 연결될 때 기록을 시작하고, 끊길 때 주차를 기록해요. 이름을 바꿔도 기록은 그대로예요.")

            SectionTitle("층 계산")
            EodiCard {
                Row {
                    Text("기본 층 높이", modifier = Modifier.weight(1f))
                    Text(String.format(Locale.US, "%.1fm", height), color = Eodi.colors.secondaryText)
                }
                Slider(height, { height = (it * 10).toInt() / 10f }, valueRange = 2.5f..4.5f,
                    onValueChangeFinished = { Store.setDefaultFloorHeight(height.toDouble()) })
            }
            Hint("처음 가는 주차장에서 쓰는 값이에요. 한 번 보정한 주차장은 학습한 값을 써요.")

            SectionTitle("학습된 주차장")
            EodiCard {
                if (data.garages.isEmpty()) {
                    Text("주차 기록에서 층을 보정하면 여기에 생겨요.", color = Eodi.colors.secondaryText)
                }
                data.garages.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    val (a, _) = FloorModel.coefficients(g, data.defaultFloorHeight)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name ?: String.format(Locale.US, "%.4f, %.4f", g.latitude, g.longitude))
                            Text(String.format(Locale.KOREAN, "보정 %d회 · 층 높이 %.1fm", g.samples.size, 1 / a),
                                color = Eodi.colors.secondaryText, fontSize = 12.sp)
                        }
                        IconButton(onClick = { Store.deleteGarage(g.id) }) {
                            Icon(Icons.Filled.Delete, "삭제", tint = Eodi.colors.tertiaryText)
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    editing?.let { car ->
        CarDialog(car, data.carDevices[car.id], onDismiss = { editing = null }, onDelete = {
            Store.deleteCar(car.id); editing = null
        })
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Eodi.colors.secondaryText, fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarDialog(car: Car, currentAddress: String?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(car.name) }
    var color by remember { mutableStateOf(car.color) }
    var address by remember { mutableStateOf(currentAddress) }

    val canReadBt = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val paired = remember {
        if (!canReadBt) emptyList()
        else ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.map { (it.name ?: it.address) to it.address }?.sortedBy { it.first } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("차 편집") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text("색", color = Eodi.colors.secondaryText, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CarPalette.colors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(c).clickable { color = i },
                            contentAlignment = Alignment.Center,
                        ) { if (color == i) Icon(Icons.Filled.Check, null, tint = Color.White) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bluetooth, null, tint = Eodi.colors.secondaryText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("자동 기록할 차 블루투스", color = Eodi.colors.secondaryText, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                if (!canReadBt) {
                    Text("근처 기기 권한을 허용하면 고를 수 있어요.", fontSize = 13.sp)
                }
                (listOf("사용 안 함" to null) + paired).forEach { (label, addr) ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { address = addr }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape)
                                .border(2.dp, if (address == addr) MaterialTheme.colorScheme.primary else Eodi.colors.tertiaryText, CircleShape)
                                .padding(4.dp).clip(CircleShape)
                                .background(if (address == addr) MaterialTheme.colorScheme.primary else Color.Transparent),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(label, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    FloorTile(-3, CarPalette.color(color), 64.dp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { Store.updateCar(car.copy(name = name, color = color), address); onDismiss() }) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDelete) { Text("삭제", color = Color(0xFFFF3B30)) }
        },
    )
}
