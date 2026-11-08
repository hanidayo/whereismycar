package kr.hanidayo.eodi.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.hanidayo.eodi.data.ParkingRecord
import kr.hanidayo.eodi.data.Store
import kr.hanidayo.eodi.util.PillarSpeech

/** "기둥 위치를 말해 보세요" 카드: 구글 음성 인식 → 층·기둥 번호 확인 → 저장 */
@Composable
fun VoicePillarCard(record: ParkingRecord) {
    val ctx = LocalContext.current
    var heard by remember { mutableStateOf<Pair<String, PillarSpeech.Result>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val candidates = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        // 후보 중 기둥 번호를 알아들은 첫 문장
        val best = candidates.map { it to PillarSpeech.parse(it) }
            .firstOrNull { it.second.pillar != null || it.second.level != null }
        heard = best ?: candidates.firstOrNull()?.let { it to PillarSpeech.Result(null, null) }
    }
    fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "기둥 위치를 말해 주세요 (예: 지하 3층 에이 십이)")
        try {
            launcher.launch(i)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "이 폰에서는 음성 인식을 쓸 수 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    EodiCard(Modifier.clip(RoundedCornerShape(24.dp)).clickable { listen() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.pillarText?.let { "기둥 $it" } ?: "기둥 위치를 말해 보세요",
                    fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Eodi.colors.ink)
                Text(if (record.pillarText == null) "눌러서 말하기 · 층도 같이 말하면 층도 고쳐져요" else "눌러서 다시 말하기",
                    color = Eodi.colors.secondaryText, fontSize = 12.sp)
            }
            Box(Modifier.size(52.dp).clip(CircleShape).background(Eodi.colors.soft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, "말하기", tint = Eodi.colors.primary)
            }
        }
    }

    heard?.let { (text, parsed) ->
        AlertDialog(
            onDismissRequest = { heard = null },
            containerColor = Eodi.colors.card,
            title = { Text("이렇게 들었어요") },
            text = {
                Column {
                    Text("“$text”", color = Eodi.colors.ink)
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        parsed.level?.let { Chip(longLevelName(it), Eodi.colors.primary) }
                        parsed.pillar?.let { Chip("기둥 $it", Color(0xFF2E9E6E)) }
                    }
                    if (parsed.level == null && parsed.pillar == null) {
                        Text("기둥 번호를 못 알아들었어요. 다시 말해 주세요.", color = Color(0xFFE67E22), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = parsed.level != null || parsed.pillar != null,
                    onClick = { Store.applyVoice(record.id, parsed); heard = null },
                ) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { heard = null; listen() }) { Text("다시 말하기") } },
        )
    }
}
