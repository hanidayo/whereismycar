package kr.hanidayo.eodi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

fun newId(): String = UUID.randomUUID().toString().lowercase()
fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

/** 차는 고유 ID로 구분. 이름을 바꿔도 기록·블루투스 연결·알림이 그대로 이어짐 */
@Serializable
data class Car(
    val id: String = newId(),
    val name: String,
    val color: Int = 0,
)

@Serializable
data class ParkingRecord(
    val id: String = newId(),
    @SerialName("car_id") val carId: String,
    val member: String,
    val date: String = nowIso(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    @SerialName("delta_meters") val deltaMeters: Double? = null,
    /** 0 = 1F, -1 = B1, 1 = 2F */
    val level: Int? = null,
    val calibrated: Boolean = false,
    @SerialName("pillar_text") val pillarText: String? = null,
    @SerialName("ocr_level") val ocrLevel: Int? = null,
    @SerialName("has_photo") val hasPhoto: Boolean = false,
    // ↓ 이 기기 전용
    @SerialName("garage_id") val garageId: String? = null,
    @SerialName("photo_file") val photoFile: String? = null,
    @SerialName("photo_pending") val photoPending: Boolean = false,
    val synced: Boolean = false,
) {
    val instant: Instant get() = runCatching { Instant.parse(date) }.getOrDefault(Instant.EPOCH)
    val levelName: String get() = level?.let(Level::name) ?: "층 미확인"
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

@Serializable
data class GarageSample(
    @SerialName("record_id") val recordId: String,
    val delta: Double,
    val level: Int,
)

@Serializable
data class GarageProfile(
    val id: String = newId(),
    val latitude: Double,
    val longitude: Double,
    val name: String? = null,
    val samples: List<GarageSample> = emptyList(),
)

@Serializable
data class SyncSettings(
    val serverUrl: String = "",
    val token: String = "",
    val member: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

@Serializable
data class RemoteState(
    val cars: List<Car> = emptyList(),
    val parkings: List<ParkingRecord> = emptyList(),
    val garages: List<GarageProfile> = emptyList(),
)

@Serializable
data class AppData(
    val cars: List<Car> = listOf(Car(name = "내 차", color = 0)),
    val records: List<ParkingRecord> = emptyList(),
    val garages: List<GarageProfile> = emptyList(),
    val defaultFloorHeight: Double = 3.3,
    val sync: SyncSettings = SyncSettings(),
    /** 차 ID → 차 블루투스 기기 주소 */
    val carDevices: Map<String, String> = emptyMap(),
    val fcmToken: String? = null,
)

sealed interface SyncStatus {
    data object Off : SyncStatus
    data object Syncing : SyncStatus
    data class Ok(val at: Instant) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/** 색은 번호로 저장(아이폰과 같은 순서). 실제 색은 ui/Theme.kt */
object CarColors {
    const val count = 8
}

object Level {
    fun name(i: Int): String = if (i >= 0) "${i + 1}F" else "B${-i}"
}

object Altitude {
    /** 기압(hPa) → 표준대기 기준 고도(m). 차이만 쓰므로 절대값 오차는 상관없음 */
    fun meters(hPa: Double): Double = 44330.0 * (1.0 - (hPa / 1013.25).pow(0.190263))
}

object FloorModel {
    /** level = a * delta + b */
    fun coefficients(profile: GarageProfile?, defaultHeight: Double): Pair<Double, Double> {
        val fallback = 1.0 / defaultHeight to 0.0
        val samples = profile?.samples ?: return fallback
        val last = samples.lastOrNull() ?: return fallback

        if (samples.size >= 2) {
            val n = samples.size.toDouble()
            val mx = samples.sumOf { it.delta } / n
            val my = samples.sumOf { it.level.toDouble() } / n
            val sxx = samples.sumOf { (it.delta - mx) * (it.delta - mx) }
            val sxy = samples.sumOf { (it.delta - mx) * (it.level - my) }
            if (sxx > 4) {
                val a = sxy / sxx
                if (a > 0 && 1 / a in 2.0..6.0) return a to (my - a * mx)
            }
        }
        if (last.level != 0) {
            val h = last.delta / last.level
            if (h in 2.0..6.0) return 1 / h to 0.0
        }
        return 1.0 / defaultHeight to (last.level - last.delta / defaultHeight)
    }

    fun predict(delta: Double, profile: GarageProfile?, defaultHeight: Double): Int {
        val (a, b) = coefficients(profile, defaultHeight)
        return (a * delta + b).roundToInt()
    }
}
