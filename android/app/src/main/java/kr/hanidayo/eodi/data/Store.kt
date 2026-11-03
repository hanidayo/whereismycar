package kr.hanidayo.eodi.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kr.hanidayo.eodi.util.Ocr
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** 앱 전체 상태. 파일 하나(JSON)에 저장하고, 가족 서버와 동기화합니다. */
object Store {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 입구(=GPS가 끊기기 직전 위치) 기준. 경사지처럼 입구마다 높이가 다른 곳은 입구별로 따로 학습되게 좁게 잡음 */
    private const val GARAGE_RADIUS_M = 60f

    private lateinit var appContext: Context
    private lateinit var file: File
    lateinit var photoDir: File
        private set

    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data
    private val _sync = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val syncStatus: StateFlow<SyncStatus> = _sync

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        appContext = context.applicationContext
        file = File(appContext.filesDir, "eodi-store.json")
        photoDir = File(appContext.filesDir, "photos").apply { mkdirs() }
        if (file.exists()) {
            runCatching { _data.value = json.decodeFromString<AppData>(file.readText()) }
        }
    }

    private fun update(f: (AppData) -> AppData): AppData {
        val new = _data.updateAndGet(f)
        synchronized(this) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(AppData.serializer(), new))
            tmp.renameTo(file)
        }
        return new
    }

    private val client: SyncClient? get() = SyncClient.of(_data.value.sync)

    val memberName: String get() = _data.value.sync.member.ifBlank { "나" }
    val defaultCar: Car? get() = _data.value.cars.firstOrNull()

    fun car(id: String): Car? = _data.value.cars.firstOrNull { it.id == id }
    fun carName(id: String): String = car(id)?.name ?: "삭제된 차"

    fun current(data: AppData, carId: String): ParkingRecord? =
        data.records.filter { it.carId == carId }.maxByOrNull { it.instant }

    fun record(id: String): ParkingRecord? = _data.value.records.firstOrNull { it.id == id }

    fun summary(r: ParkingRecord): String = buildString {
        append("${carName(r.carId)}: ${r.levelName}")
        r.pillarText?.let { append(" · $it") }
    }

    /** 진입 방향이 이만큼 넘게 다르면 가까워도 다른 입구로 봄 */
    private const val ENTRANCE_COURSE_TOLERANCE = 70.0

    /** 같은 입구(가까운 위치 + 비슷한 진입 방향)로 학습된 주차장 */
    private fun garageNear(lat: Double, lon: Double, course: Double?): GarageProfile? {
        val out = FloatArray(1)
        return _data.value.garages
            .map { g -> Location.distanceBetween(lat, lon, g.latitude, g.longitude, out); g to out[0] }
            .filter { it.second <= GARAGE_RADIUS_M }
            .filter { (g, _) ->
                val gc = g.course
                course == null || gc == null || angleDifference(course, gc) <= ENTRANCE_COURSE_TOLERANCE
            }
            .minByOrNull { it.second }?.first
    }

    private fun push(op: suspend (SyncClient) -> Unit) {
        val c = client ?: return
        scope.launch {
            runCatching { op(c) }
                .onSuccess { _sync.value = SyncStatus.Ok(Instant.now()) }
                .onFailure { _sync.value = SyncStatus.Failed(it.message ?: "연결 실패") }
        }
    }

    // ---------------------------------------------------------------- 설정

    fun setSync(settings: SyncSettings) {
        update { it.copy(sync = settings) }
    }

    fun setDefaultFloorHeight(h: Double) {
        update { it.copy(defaultFloorHeight = h) }
    }

    fun setFcmToken(token: String) {
        update { it.copy(fcmToken = token) }
        registerDevice()
    }

    fun registerDevice() {
        val token = _data.value.fcmToken ?: return
        push { it.putDevice(token, memberName) }
    }

    // ---------------------------------------------------------------- 차

    fun addCar(): Car {
        val used = _data.value.cars.map { it.color }.toSet()
        val color = (0 until CarColors.count).firstOrNull { it !in used } ?: 0
        val car = Car(name = "새 차", color = color)
        update { it.copy(cars = it.cars + car) }
        push { it.putCar(car) }
        return car
    }

    fun updateCar(car: Car, bluetoothAddress: String?) {
        val name = car.name.trim().ifEmpty { this.car(car.id)?.name ?: "차" }
        val fixed = car.copy(name = name)
        update { d ->
            val devices = d.carDevices.toMutableMap()
            if (bluetoothAddress == null) devices.remove(car.id) else {
                devices.entries.removeAll { it.value == bluetoothAddress } // 한 기기는 한 차에만
                devices[car.id] = bluetoothAddress
            }
            d.copy(cars = d.cars.map { if (it.id == car.id) fixed else it }, carDevices = devices)
        }
        push { it.putCar(fixed) }
    }

    fun deleteCar(id: String) {
        update { d -> d.copy(
            cars = d.cars.filterNot { it.id == id }, carDevices = d.carDevices - id,
            activityCarId = d.activityCarId.takeIf { it != id },
        ) }
        push { it.deleteCar(id) }
    }

    fun setActivityCar(carId: String?) {
        update { it.copy(activityCarId = carId) }
        kr.hanidayo.eodi.drive.ActivityWatch.sync(appContext)
    }

    /** 다른 차로 잘못 기록됐을 때 */
    fun changeCar(recordId: String, carId: String) {
        val r = record(recordId) ?: return
        if (r.carId == carId) return
        val updated = update { d -> d.copy(records = d.records.map { if (it.id == recordId) it.copy(carId = carId) else it }) }
            .records.firstOrNull { it.id == recordId } ?: return
        if (updated.synced) push { it.patchParking(updated) }
    }

    fun carIdForDevice(address: String): String? =
        _data.value.carDevices.entries.firstOrNull { it.value == address }?.key

    // ---------------------------------------------------------------- 주차 기록

    fun recordParking(
        carId: String, lat: Double?, lon: Double?, accuracy: Double?, delta: Double?, course: Double? = null,
    ): ParkingRecord {
        val profile = if (lat != null && lon != null) garageNear(lat, lon, course) else null
        val record = ParkingRecord(
            carId = carId, member = memberName,
            latitude = lat, longitude = lon, accuracy = accuracy, deltaMeters = delta, course = course,
            level = delta?.let { FloorModel.predict(it, profile, _data.value.defaultFloorHeight) },
            garageId = profile?.id,
        )
        update { it.copy(records = (it.records + record).takeLast(300)) }
        scope.launch { uploadPending() }
        return record
    }

    /** 실제 층으로 고치고, 그 주차장 학습값에 반영 */
    fun correct(id: String, level: Int) {
        val r0 = record(id) ?: return
        var r = r0.copy(level = level, calibrated = true)
        var changed: GarageProfile? = null
        val delta = r.deltaMeters
        if (delta != null && r.latitude != null && r.longitude != null) {
            val existing = _data.value.garages.firstOrNull { it.id == r.garageId }
                ?: garageNear(r.latitude!!, r.longitude!!, r.course)
            val found = existing ?: GarageProfile(latitude = r.latitude!!, longitude = r.longitude!!, course = r.course)
            val base = if (found.course == null) found.copy(course = r.course) else found
            val samples = (base.samples.filterNot { it.recordId == id } + GarageSample(id, delta, level)).takeLast(10)
            changed = base.copy(samples = samples)
            r = r.copy(garageId = changed.id)
        }
        val g = changed
        update { d ->
            d.copy(
                records = d.records.map { if (it.id == id) r else it },
                garages = if (g == null) d.garages
                else if (d.garages.any { it.id == g.id }) d.garages.map { if (it.id == g.id) g else it }
                else d.garages + g,
            )
        }
        if (r.synced) push { it.patchParking(r) }
        if (g != null) push { it.putGarage(g) }
    }

    fun deleteGarage(id: String) {
        update { d -> d.copy(garages = d.garages.filterNot { it.id == id }) }
        push { it.deleteGarage(id) }
    }

    // ---------------------------------------------------------------- 사진 + OCR

    fun newPhotoFile(recordId: String): File = File(photoDir, "$recordId-${System.currentTimeMillis()}.jpg")

    /** 카메라로 찍은 파일을 줄여 저장하고, 글자를 읽고, 서버에 올림 */
    suspend fun attachPhoto(recordId: String, photo: File) {
        shrink(photo)
        val old = record(recordId)?.photoFile
        if (old != null && old != photo.name) File(photoDir, old).delete()
        update { d -> d.copy(records = d.records.map {
            if (it.id == recordId) it.copy(photoFile = photo.name, photoPending = true) else it
        }) }
        val ocr = Ocr.recognize(appContext, photo)
        val updated = update { d -> d.copy(records = d.records.map {
            if (it.id == recordId) it.copy(pillarText = ocr.pillar ?: it.pillarText, ocrLevel = ocr.level) else it
        }) }.records.firstOrNull { it.id == recordId } ?: return
        if (updated.synced) push { it.patchParking(updated) }
        uploadPending()
    }

    fun photoFile(r: ParkingRecord): File? = r.photoFile?.let { File(photoDir, it) }?.takeIf { it.exists() }

    /** 가족이 올린 사진 내려받기 */
    suspend fun fetchRemotePhoto(recordId: String) {
        val r = record(recordId) ?: return
        if (!r.hasPhoto || photoFile(r) != null) return
        val c = client ?: return
        val bytes = runCatching { c.photo(recordId) }.getOrNull() ?: return
        val f = File(photoDir, "$recordId-remote.jpg").apply { writeBytes(bytes) }
        update { d -> d.copy(records = d.records.map { if (it.id == recordId) it.copy(photoFile = f.name) else it }) }
    }

    private fun shrink(file: File, maxSide: Int = 1600) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val rotated = Ocr.applyExifRotation(file, bmp)
        FileOutputStream(file).use { rotated.compress(Bitmap.CompressFormat.JPEG, 75, it) }
    }

    // ---------------------------------------------------------------- 동기화

    suspend fun refresh() {
        val c = client ?: run { _sync.value = SyncStatus.Off; return }
        _sync.value = SyncStatus.Syncing
        try {
            val remote = c.state()
            val local = _data.value

            if (remote.cars.isEmpty()) local.cars.forEach { c.putCar(it) } // 첫 기기: 내 차 목록을 올림

            val localOnlyGarages = local.garages.filter { g -> remote.garages.none { it.id == g.id } }
            update { d ->
                val records = d.records.toMutableList()
                for (rp in remote.parkings) {
                    val i = records.indexOfFirst { it.id == rp.id }
                    if (i >= 0) {
                        records[i] = records[i].copy(
                            carId = rp.carId, level = rp.level, calibrated = rp.calibrated, pillarText = rp.pillarText,
                            ocrLevel = rp.ocrLevel, hasPhoto = rp.hasPhoto, synced = true,
                        )
                    } else {
                        records += rp.copy(synced = true, photoFile = null, photoPending = false)
                    }
                }
                d.copy(
                    cars = if (remote.cars.isEmpty()) d.cars else remote.cars,
                    records = records,
                    garages = remote.garages + localOnlyGarages,
                )
            }
            localOnlyGarages.forEach { c.putGarage(it) }
            _data.value.fcmToken?.let { c.putDevice(it, memberName) }
            uploadPending()
        } catch (e: Exception) {
            _sync.value = SyncStatus.Failed(e.message ?: "연결 실패")
        }
    }

    suspend fun uploadPending() {
        val c = client ?: return
        try {
            for (r in _data.value.records.filter { !it.synced }) {
                c.postParking(r)
                update { d -> d.copy(records = d.records.map { if (it.id == r.id) it.copy(synced = true) else it }) }
            }
            for (r in _data.value.records.filter { it.synced && it.photoPending }) {
                val f = photoFile(r) ?: continue
                c.uploadPhoto(r.id, f.readBytes())
                update { d -> d.copy(records = d.records.map {
                    if (it.id == r.id) it.copy(photoPending = false, hasPhoto = true) else it
                }) }
            }
            _sync.value = SyncStatus.Ok(Instant.now())
        } catch (e: Exception) {
            _sync.value = SyncStatus.Failed(e.message ?: "연결 실패")
        }
    }

    suspend fun testConnection(): String {
        val c = client ?: return "서버 주소와 가족 코드를 입력하세요."
        return try {
            val s = c.state()
            refresh()
            "연결됨 · 차 ${s.cars.size}대"
        } catch (e: Exception) {
            e.message ?: "연결 실패"
        }
    }

    suspend fun testPush(): String {
        val c = client ?: return "먼저 가족 공유를 설정하세요."
        if (_data.value.fcmToken == null) return "이 기기의 푸시 토큰을 아직 못 받았어요. 잠시 후 다시 시도하세요."
        return try {
            _data.value.fcmToken?.let { c.putDevice(it, memberName) }
            c.testPush()
            "테스트 알림을 보냈어요."
        } catch (e: Exception) {
            e.message ?: "실패"
        }
    }
}
