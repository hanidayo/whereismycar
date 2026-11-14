package kr.hanidayo.eodi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SyncException(val code: Int) : Exception(
    when (code) {
        401 -> "가족 코드가 맞지 않아요."
        else -> "서버 응답 오류 ($code)"
    }
)

@Serializable
private data class PatchBody(
    @SerialName("car_id") val carId: String,
    val level: Int? = null,
    val calibrated: Boolean,
    @SerialName("pillar_text") val pillarText: String? = null,
    @SerialName("ocr_level") val ocrLevel: Int? = null,
)

@Serializable
private data class CarBody(val name: String, val color: Int)

@Serializable
private data class DeviceBody(val token: String, val member: String, val platform: String = "android")

class SyncClient private constructor(settings: SyncSettings) {
    private val base = settings.serverUrl.trim()
        .let { if (it.startsWith("http")) it else "https://$it" }
        .trimEnd('/')
    private val token = settings.token

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        private val JSON = "application/json".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()

        fun of(settings: SyncSettings): SyncClient? =
            if (settings.isConfigured) SyncClient(settings) else null
    }

    private suspend fun send(path: String, method: String = "GET", body: RequestBody? = null): ByteArray =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$base/$path")
                .header("Authorization", "Bearer $token")
                .method(method, body)
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw SyncException(r.code)
                r.body?.bytes() ?: ByteArray(0)
            }
        }

    private inline fun <reified T> json(value: T): RequestBody =
        Store.json.encodeToString(value).toRequestBody(JSON)

    suspend fun state(): RemoteState =
        Store.json.decodeFromString(send("api/state").decodeToString())

    suspend fun putCar(car: Car) {
        send("api/cars/${car.id}", "PUT", json(CarBody(car.name, car.color)))
    }

    suspend fun deleteCar(id: String) {
        send("api/cars/$id", "DELETE")
    }

    suspend fun postParking(r: ParkingRecord): PostResult {
        val bytes = send("api/parkings", "POST", json(r))
        return runCatching { Store.json.decodeFromString<PostResult>(bytes.decodeToString()) }.getOrDefault(PostResult())
    }

    suspend fun deleteParking(id: String) {
        send("api/parkings/$id", "DELETE")
    }

    suspend fun patchParking(r: ParkingRecord) {
        send("api/parkings/${r.id}", "PATCH", json(PatchBody(r.carId, r.level, r.calibrated, r.pillarText, r.ocrLevel)))
    }

    suspend fun uploadPhoto(id: String, jpeg: ByteArray) {
        send("api/parkings/$id/photo", "PUT", jpeg.toRequestBody(JPEG))
    }

    suspend fun photo(id: String): ByteArray = send("api/parkings/$id/photo")

    suspend fun putGarage(g: GarageProfile) {
        send("api/garages/${g.id}", "PUT", json(g))
    }

    suspend fun deleteGarage(id: String) {
        send("api/garages/$id", "DELETE")
    }

    suspend fun putDevice(fcmToken: String, member: String) {
        send("api/devices", "PUT", json(DeviceBody(fcmToken, member)))
    }

    suspend fun testPush() {
        send("api/test-push", "POST", ByteArray(0).toRequestBody(JSON))
    }
}
