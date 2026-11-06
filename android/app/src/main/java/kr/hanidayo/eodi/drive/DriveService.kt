package kr.hanidayo.eodi.drive

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kr.hanidayo.eodi.data.Altitude
import kr.hanidayo.eodi.data.Store
import kr.hanidayo.eodi.util.Notifications
import kotlin.coroutines.resume
import kotlin.math.abs

/** 운전 중 상태. 서비스가 중간에 죽어도 이어갈 수 있게 저장 */
@Serializable
data class DriveState(
    val carId: String,
    val startedAt: Long = System.currentTimeMillis(),
    /** 마지막으로 GPS가 좋았던 시점(=주차장 입구 직전)의 기압(hPa) */
    val refPressure: Double? = null,
    val refLat: Double? = null,
    val refLon: Double? = null,
    val refAccuracy: Double? = null,
    val refTime: Long? = null,
    /** 기준점에서 차가 향하던 방향(도) — 입구 구분용 */
    val refCourse: Double? = null,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val lastAccuracy: Double? = null,
    /** 마지막으로 의미 있게(MOVE_THRESHOLD_M 이상) 움직인 위치·시각 — 자동 종료 판단용 */
    val moveLat: Double? = null,
    val moveLon: Double? = null,
    val lastMoveAt: Long? = null,
    /** 기준점 이후 가장 높은 기압(hPa, =가장 낮은 위치). 자동 종료 때 층 계산에 씀 */
    val peakPressure: Double? = null,
    /** "bt" = 블루투스·수동, "activity" = 차량 탑승 감지로 시작 */
    val source: String = "bt",
)

data class LiveDrive(val state: DriveState, val pressure: Double?, val hasBarometer: Boolean) {
    val delta: Double?
        get() {
            val ref = state.refPressure ?: return null
            val now = pressure ?: return null
            return Altitude.meters(now) - Altitude.meters(ref)
        }
}

/**
 * 운전 중 GPS와 기압을 기록하는 포그라운드 서비스.
 * 블루투스 연결 → START, 해제 → PARK
 */
class DriveService : Service(), SensorEventListener {

    companion object {
        private const val ACTION_START = "kr.hanidayo.eodi.START"
        private const val ACTION_PARK = "kr.hanidayo.eodi.PARK"
        private const val ACTION_START_ACTIVITY = "kr.hanidayo.eodi.START_ACTIVITY"
        private const val ACTION_PARK_ACTIVITY = "kr.hanidayo.eodi.PARK_ACTIVITY"
        private const val EXTRA_CAR = "car"
        private const val NOTIFICATION_ID = 41
        private const val PREFS = "drive"
        private const val KEY_STATE = "state"

        /** 이 정확도(m) 이하면 지상에서 GPS가 잘 잡힌 것으로 봄 */
        private const val GOOD_ACCURACY = 25f
        /** 기준 기압은 마지막 GPS 시점보다 몇 초 앞의 값(경사로 진입 전) */
        private const val LOOKBACK_MS = 5_000L
        private const val EXTRA_AUTO = "auto"
        /** 주차 기록 뒤 이 시간 안에 오는 자동 '운전 시작'은 재연결로 보고 무시 */
        private const val RECONNECT_COOLDOWN_MS = 5 * 60_000L
        /** 이만큼 움직이지 않으면(지하에서 GPS가 안 잡히는 시간 포함) 주차한 것으로 보고 자동 종료 */
        private const val IDLE_LIMIT_MS = 30 * 60_000L
        /** 운전 시작 후 이 시간이 지나면 무조건 자동 종료(배터리 보호) */
        private const val MAX_DRIVE_MS = 8 * 60 * 60_000L
        /** 이 거리(m) 이상 움직여야 "움직였다"로 봄 */
        private const val MOVE_THRESHOLD_M = 100f

        private val _live = MutableStateFlow<LiveDrive?>(null)
        val live: StateFlow<LiveDrive?> = _live

        /** fromAutomation: 블루투스·탑승 감지가 부른 경우 (주차 직후 재연결이면 무시) */
        fun start(context: Context, carId: String, fromAutomation: Boolean = false) =
            send(context, ACTION_START, carId) { putExtra(EXTRA_AUTO, fromAutomation) }
        fun park(context: Context, carId: String?) = send(context, ACTION_PARK, carId)
        fun startFromActivity(context: Context, carId: String) =
            send(context, ACTION_START_ACTIVITY, carId) { putExtra(EXTRA_AUTO, true) }
        fun parkFromActivity(context: Context) = send(context, ACTION_PARK_ACTIVITY, null)

        private fun send(context: Context, action: String, carId: String?, extra: Intent.() -> Unit = {}) {
            val i = Intent(context, DriveService::class.java).setAction(action).putExtra(EXTRA_CAR, carId).apply(extra)
            // Android 12+: 백그라운드(브로드캐스트)에서 포그라운드 서비스 시작이 막히면 예외 → 앱이 죽지 않게
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        fun hasBarometer(context: Context): Boolean =
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PRESSURE) != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var sensors: SensorManager
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var state: DriveState? = null
    private var latestPressure: Pair<Long, Double>? = null
    private val buffer = ArrayDeque<Pair<Long, Double>>()
    private var running = false
    private var watchdog: Job? = null
    private var autoParking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        state = loadState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val carId = intent?.getStringExtra(EXTRA_CAR)
        val name = Store.carName(carId ?: state?.carId ?: Store.defaultCar?.id ?: "")
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, Notifications.driving(this, name),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) { // 위치 권한이 없으면 Android 14+에서 SecurityException
            return stopAndReturn()
        }
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        if (auto && state == null && intent?.action in setOf(ACTION_START, ACTION_START_ACTIVITY)) {
            val last = Store.data.value.records.maxOfOrNull { it.instant.toEpochMilli() }
            if (last != null && System.currentTimeMillis() - last < RECONNECT_COOLDOWN_MS) return stopAndReturn()
        }
        when (intent?.action) {
            ACTION_START -> begin(carId ?: Store.defaultCar?.id ?: return stopAndReturn())
            ACTION_PARK -> scope.launch { finishParking(carId, auto = false) }
            // 블루투스로 이미 기록 중이면 끼어들지 않음
            ACTION_START_ACTIVITY -> if (state == null) begin(carId ?: return stopAndReturn(), "activity") else startSensors()
            ACTION_PARK_ACTIVITY -> when (state?.source) {
                // 차에서 내린 뒤 감지되므로 사람이 이미 움직였을 수 있음 → 자동 기록처럼 최저점 사용
                "activity" -> scope.launch { finishParking(null, auto = true) }
                null -> return stopAndReturn()
                else -> startSensors() // 블루투스 운전은 블루투스 해제로 끝냄
            }
            else -> if (state != null) startSensors() else return stopAndReturn() // 시스템이 재시작한 경우
        }
        return START_STICKY
    }

    private fun stopAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    private fun begin(carId: String, source: String = "bt") {
        val s = state
        // 이미 기록 중이면(탑승 감지 후 블루투스 연결 등) 기준점은 유지하고 차·방식만 맞춤
        if (s != null && System.currentTimeMillis() - s.startedAt < 15 * 60_000L) {
            state = s.copy(carId = carId, source = if (source == "bt") "bt" else s.source)
            saveState()
            startSensors()
            return
        }
        state = DriveState(carId = carId, source = source)
        buffer.clear()
        saveState()
        startSensors()
    }

    @SuppressLint("MissingPermission")
    private fun startSensors() {
        if (running) return
        running = true
        sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()
        runCatching { fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper()) }
        watchdog = scope.launch {
            while (isActive) {
                delay(60_000)
                checkAutoStop()
            }
        }
        publish()
    }

    private fun stopSensors() {
        if (!running) return
        running = false
        sensors.unregisterListener(this)
        fused.removeLocationUpdates(locationCallback)
        watchdog?.cancel()
        watchdog = null
    }

    /** 블루투스 해제를 놓쳐도 GPS가 계속 켜져 있지 않게 하는 안전장치 */
    private fun checkAutoStop() {
        val s = state ?: return
        if (autoParking) return
        val now = System.currentTimeMillis()
        val idle = now - (s.lastMoveAt ?: s.startedAt)
        val total = now - s.startedAt
        if (idle < IDLE_LIMIT_MS && total < MAX_DRIVE_MS) return
        autoParking = true
        scope.launch { finishParking(s.carId, auto = true) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val hPa = event.values[0].toDouble()
        latestPressure = now to hPa
        buffer.addLast(now to hPa)
        while (buffer.isNotEmpty() && buffer.first().first < now - 180_000) buffer.removeFirst()
        val s = state
        if (s?.refPressure != null && hPa > (s.peakPressure ?: Double.NEGATIVE_INFINITY)) {
            state = s.copy(peakPressure = hPa)
            saveState()
        }
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onLocation(loc: Location) {
        var s = state ?: return
        s = s.copy(lastLat = loc.latitude, lastLon = loc.longitude, lastAccuracy = loc.accuracy.toDouble())
        if (loc.hasAccuracy() && loc.accuracy <= 50f) {
            val mLat = s.moveLat
            val mLon = s.moveLon
            val moved = if (mLat != null && mLon != null) {
                val out = FloatArray(1)
                Location.distanceBetween(mLat, mLon, loc.latitude, loc.longitude, out)
                out[0]
            } else Float.MAX_VALUE
            if (moved >= MOVE_THRESHOLD_M) {
                s = s.copy(moveLat = loc.latitude, moveLon = loc.longitude, lastMoveAt = System.currentTimeMillis())
            }
        }
        val age = System.currentTimeMillis() - loc.time
        // 지하에서도 Wi-Fi로 정확한 척하는 위치가 잡힐 때가 있어서, 속도·고도가 있는 위성(GPS) 위치만 기준점으로
        val satellite = loc.hasSpeed() && loc.hasAltitude() && loc.hasVerticalAccuracy()
        if (satellite && loc.hasAccuracy() && loc.accuracy <= GOOD_ACCURACY && age < 10_000) {
            s = s.copy(
                refLat = loc.latitude, refLon = loc.longitude,
                refAccuracy = loc.accuracy.toDouble(), refTime = loc.time,
                refCourse = if (loc.hasBearing() && loc.speed > 1.5f) loc.bearing.toDouble() else s.refCourse,
                refPressure = pressureNear(loc.time - LOOKBACK_MS) ?: s.refPressure,
                peakPressure = null, // 아직 지상 → 최저점 다시 추적
            )
        }
        state = s
        saveState()
        publish()
        checkAutoStop()
    }

    private fun pressureNear(time: Long): Double? =
        buffer.minByOrNull { abs(it.first - time) }?.second ?: latestPressure?.second

    /** 지금 기압 한 번 읽기 (서비스가 막 시작돼 센서가 꺼져 있어도) */
    private suspend fun currentPressure(): Double? {
        latestPressure?.let { (t, p) -> if (System.currentTimeMillis() - t < 3_000) return p }
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
        return withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine<Double> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sensors.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0].toDouble())
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                cont.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    /** auto: 안전장치가 부른 경우(주차 후 한참 지났을 수 있음) */
    private suspend fun finishParking(requestedCar: String?, auto: Boolean) {
        val s = state
        val carId = requestedCar ?: s?.carId ?: Store.defaultCar?.id
        if (carId == null) { shutdown(); return }
        // 이번 운전에서 GPS를 한 번도 못 잡았으면 주차장을 벗어나지 않은 것(재연결 등) → 이전 기록 유지
        val prev = Store.current(Store.data.value, carId)
        if (s != null && s.refPressure == null && s.refLat == null && prev != null &&
            System.currentTimeMillis() - prev.instant.toEpochMilli() < 6 * 60 * 60_000L
        ) { shutdown(); return }

        val now = currentPressure()
        val ref = s?.refPressure
        var delta = if (ref != null && now != null) Altitude.meters(now) - Altitude.meters(ref) else null
        // 자동 종료는 사람이 위층으로 갔을 수 있음 → 입구 이후 가장 낮았던 지점을 씀
        val peak = s?.peakPressure
        if (auto && ref != null && peak != null) {
            val lowest = Altitude.meters(peak) - Altitude.meters(ref)
            if (delta == null || lowest < delta) delta = lowest
        }

        var lat = s?.refLat ?: s?.lastLat
        var lon = s?.refLon ?: s?.lastLon
        var acc = if (s?.refLat != null) s.refAccuracy else s?.lastAccuracy
        val course = if (s?.refLat != null) s.refCourse else null
        if (lat == null) {
            val last = runCatching { suspendCancellableCoroutine<Location?> { c ->
                fused.lastLocation.addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
            } }.getOrNull()
            lat = last?.latitude; lon = last?.longitude; acc = last?.accuracy?.toDouble()
        }

        val record = Store.recordParking(carId, lat, lon, acc, delta, course)
        // 가족 공유 중이면 서버가 FCM으로 모두에게(나 포함) 알림 → 로컬 알림 생략
        if (auto || !Store.data.value.sync.isConfigured) Notifications.parkedLocal(this, record, auto)
        shutdown()
    }

    private fun shutdown() {
        stopSensors()
        state = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_STATE).apply()
        _live.value = null
        autoParking = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish() {
        _live.value = state?.let { LiveDrive(it, latestPressure?.second, hasBarometer(this)) }
    }

    private fun saveState() {
        val s = state ?: return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, Store.json.encodeToString(DriveState.serializer(), s)).apply()
    }

    private fun loadState(): DriveState? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, null)?.let {
            runCatching { Store.json.decodeFromString(DriveState.serializer(), it) }.getOrNull()
        }

    override fun onDestroy() {
        stopSensors()
        scope.cancel()
        super.onDestroy()
    }
}
