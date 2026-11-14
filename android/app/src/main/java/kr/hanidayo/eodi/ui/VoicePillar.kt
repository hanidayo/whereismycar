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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardCapitalization
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

/** "기둥 위치를 말해 보세요" 카드 */
@Composable
fun VoicePillarCard(record: ParkingRecord) {
    val listen = rememberVoicePillar(record)
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
}

/** 구글 음성 인식으로 듣기 (홈 마이크 버튼용) */
@Composable
fun rememberVoicePillar(record: ParkingRecord?): () -> Unit = rememberPillarInput(record).listen

class PillarInput(val listen: () -> Unit, val type: () -> Unit)

private data class PillarDraft(val heard: String?, val pillar: String, val level: Int?)

/** 말하기(구글 음성 인식)·직접 쓰기 → 결과를 고칠 수 있는 확인 창 → 저장 */
@Composable
fun rememberPillarInput(record: ParkingRecord?): PillarInput {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf<PillarDraft?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK || record == null) return@rememberLauncherForActivityResult
        val candidates = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        // 후보 중 기둥 번호를 알아들은 첫 문장
        val best = candidates.map { it to PillarSpeech.parse(it) }.firstOrNull { it.second.pillar != null }
            ?: candidates.firstOrNull()?.let { it to PillarSpeech.parse(it) }
        draft = PillarDraft(best?.first, best?.second?.pillar ?: record.pillarText.orEmpty(), best?.second?.level)
    }
    fun listen() {
        if (record == null) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "기둥 위치를 말해 주세요 (예: 지하 3층 에이 십이)")
        try {
            launcher.launch(i)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "이 폰에서는 음성 인식을 쓸 수 없어요. 직접 입력해 주세요.", Toast.LENGTH_SHORT).show()
            draft = PillarDraft(null, record.pillarText.orEmpty(), null)
        }
    }
    fun type() {
        if (record != null) draft = PillarDraft(null, record.pillarText.orEmpty(), null)
    }

    draft?.let { d ->
        PillarDialog(
            d,
            onDismiss = { draft = null },
            onRetry = { draft = null; listen() },
            onSave = { pillar, level ->
                val p = pillar.trim().uppercase().ifEmpty { null }
                record?.let { Store.applyVoice(it.id, PillarSpeech.Result(level, p)) }
                draft = null
            },
        )
    }
    return PillarInput(::listen, ::type)
}

@Composable
private fun PillarDialog(d: PillarDraft, onDismiss: () -> Unit, onRetry: () -> Unit, onSave: (String, Int?) -> Unit) {
    var pillar by remember(d) { mutableStateOf(d.pillar) }
    var level by remember(d) { mutableStateOf(d.level) }
    var levelMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Eodi.colors.card,
        title = { Text("기둥 위치") },
        text = {
            Column {
                if (d.heard != null) {
                    Text("“${d.heard}”", color = Eodi.colors.secondaryText, fontSize = 13.sp)
                    Spacer(Modifier.size(10.dp))
                }
                OutlinedTextField(
                    pillar, { pillar = it.uppercase() },
                    label = { Text("기둥 번호 · 틀렸으면 고쳐 주세요") },
                    placeholder = { Text("예: A12") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("층", color = Eodi.colors.ink, modifier = Modifier.weight(1f))
                    Box {
                        Text(
                            (level?.let { longLevelName(it) } ?: "그대로") + " ▾",
                            color = Eodi.colors.primary, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Eodi.colors.soft)
                                .clickable { levelMenu = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                        DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                            DropdownMenuItem(text = { Text("그대로 두기") }, onClick = { level = null; levelMenu = false })
                            (4 downTo -6).forEach { l ->
                                DropdownMenuItem(text = { Text(longLevelName(l)) }, onClick = { level = l; levelMenu = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pillar.isNotBlank() || level != null, onClick = { onSave(pillar, level) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onRetry) { Text("말하기") } },
    )
}
