package kr.hanidayo.eodi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 장소 아이콘 (아이폰과 같은 키) */
data class PlaceIcon(val key: String, val label: String, val icon: ImageVector, val color: Color) {
    companion object {
        val all = listOf(
            PlaceIcon("home", "집", Icons.Filled.Home, Color(0xFF7C6CF2)),
            PlaceIcon("office", "회사", Icons.Filled.Business, Color(0xFF4A7BE0)),
            PlaceIcon("school", "학교", Icons.Filled.School, Color(0xFFF5A623)),
            PlaceIcon("department", "백화점", Icons.Filled.ShoppingBag, Color(0xFFF06CA8)),
            PlaceIcon("mart", "마트", Icons.Filled.ShoppingCart, Color(0xFFFF7A45)),
            PlaceIcon("airport", "공항", Icons.Filled.Flight, Color(0xFF3BA3F0)),
            PlaceIcon("train", "기차역", Icons.Filled.Train, Color(0xFF2E9E6E)),
            PlaceIcon("hospital", "병원", Icons.Filled.LocalHospital, Color(0xFFEF5350)),
            PlaceIcon("restaurant", "식당", Icons.Filled.Restaurant, Color(0xFFE58A2E)),
            PlaceIcon("cafe", "카페", Icons.Filled.LocalCafe, Color(0xFF9C6B4E)),
            PlaceIcon("hotel", "호텔", Icons.Filled.Hotel, Color(0xFF8E6CD8)),
            PlaceIcon("park", "공원", Icons.Filled.Park, Color(0xFF5BB04B)),
            PlaceIcon("gym", "운동", Icons.Filled.FitnessCenter, Color(0xFF14A3A3)),
            PlaceIcon("cinema", "영화관", Icons.Filled.Theaters, Color(0xFF5C6BC0)),
            PlaceIcon("gas", "주유소", Icons.Filled.LocalGasStation, Color(0xFF607D8B)),
            PlaceIcon("church", "교회", Icons.Filled.Church, Color(0xFFA1887F)),
            PlaceIcon("family", "가족·친척", Icons.Filled.Favorite, Color(0xFFFF6B8B)),
            PlaceIcon("parking", "주차장", Icons.Filled.LocalParking, Color(0xFF8E94AD)),
        )

        fun find(key: String?) = all.firstOrNull { it.key == key }

        /** 아이콘을 안 고른 곳: 이름에 "집"이 있으면 집, 아니면 주차장 */
        fun resolve(icon: String?, name: String?): PlaceIcon =
            find(icon) ?: if (name?.contains("집") == true) all[0] else find("parking")!!
    }
}

@Composable
fun PlaceBadge(place: PlaceIcon, size: Dp = 52.dp, selected: Boolean = false) {
    val shape = RoundedCornerShape(size * 0.31f)
    Box(
        Modifier.size(size).clip(shape).background(place.color.copy(alpha = 0.14f))
            .then(if (selected) Modifier.border(2.5.dp, place.color, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(place.icon, place.label, tint = place.color, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun PlaceIconGrid(selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlaceIcon.all.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { p ->
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable { onSelect(p.key) }.padding(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlaceBadge(p, 46.dp, selected == p.key)
                        Text(p.label, fontSize = 11.sp, color = Eodi.colors.ink, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.size(50.dp)) }
            }
        }
    }
}

/** 장소 이름·아이콘 정하기 */
@Composable
fun PlaceDialog(
    initialName: String?, initialIcon: String?,
    onDismiss: () -> Unit, onSave: (String, String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName ?: "") }
    var icon by remember { mutableStateOf(initialIcon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Eodi.colors.card,
        title = { Text("장소") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("장소 이름") },
                    placeholder = { Text("예: 집 정문, 회사, 롯데백화점") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("이 위치(같은 입구)에 다음에 주차하면 자동으로 이 이름과 아이콘이 붙어요.",
                    fontSize = 12.sp, color = Eodi.colors.secondaryText)
                Spacer(Modifier.height(14.dp))
                PlaceIconGrid(icon) { icon = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, icon) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
