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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val lastAccuracy: Double? = null,
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
        private const val EXTRA_CAR = "car"
        private const val NOTIFICATION_ID = 41
        private const val PREFS = "drive"
        private const val KEY_STATE = "state"

        /** 이 정확도(m) 이하면 지상에서 GPS가 잘 잡힌 것으로 봄 */
        private const val GOOD_ACCURACY = 25f
        /** 기준 기압은 마지막 GPS 시점보다 몇 초 앞의 값(경사로 진입 전) */
        private const val LOOKBACK_MS = 5_000L

        private val _live = MutableStateFlow<LiveDrive?>(null)
        val live: StateFlow<LiveDrive?> = _live

        fun start(context: Context, carId: String) = send(context, ACTION_START, carId)
        fun park(context: Context, carId: String?) = send(context, ACTION_PARK, carId)

        private fun send(context: Context, action: String, carId: String?) {
            val i = Intent(context, DriveService::class.java).setAction(action).putExtra(EXTRA_CAR, carId)
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
        when (intent?.action) {
            ACTION_START -> begin(carId ?: Store.defaultCar?.id ?: return stopAndReturn())
            ACTION_PARK -> scope.launch { finishParking(carId) }
            else -> if (state != null) startSensors() else return stopAndReturn() // 시스템이 재시작한 경우
        }
        return START_STICKY
    }

    private fun stopAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    private fun begin(carId: String) {
        state = DriveState(carId = carId)
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
        publish()
    }

    private fun stopSensors() {
        if (!running) return
        running = false
        sensors.unregisterListener(this)
        fused.removeLocationUpdates(locationCallback)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val hPa = event.values[0].toDouble()
        latestPressure = now to hPa
        buffer.addLast(now to hPa)
        while (buffer.isNotEmpty() && buffer.first().first < now - 180_000) buffer.removeFirst()
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onLocation(loc: Location) {
        var s = state ?: return
        s = s.copy(lastLat = loc.latitude, lastLon = loc.longitude, lastAccuracy = loc.accuracy.toDouble())
        val age = System.currentTimeMillis() - loc.time
        if (loc.hasAccuracy() && loc.accuracy <= GOOD_ACCURACY && age < 10_000) {
            s = s.copy(
                refLat = loc.latitude, refLon = loc.longitude,
                refAccuracy = loc.accuracy.toDouble(), refTime = loc.time,
                refPressure = pressureNear(loc.time - LOOKBACK_MS) ?: s.refPressure,
            )
        }
        state = s
        saveState()
        publish()
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
    private suspend fun finishParking(requestedCar: String?) {
        val s = state
        val carId = requestedCar ?: s?.carId ?: Store.defaultCar?.id
        if (carId == null) { shutdown(); return }

        val now = currentPressure()
        val ref = s?.refPressure
        val delta = if (ref != null && now != null) Altitude.meters(now) - Altitude.meters(ref) else null

        var lat = s?.refLat ?: s?.lastLat
        var lon = s?.refLon ?: s?.lastLon
        var acc = if (s?.refLat != null) s.refAccuracy else s?.lastAccuracy
        if (lat == null) {
            val last = runCatching { suspendCancellableCoroutine<Location?> { c ->
                fused.lastLocation.addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
            } }.getOrNull()
            lat = last?.latitude; lon = last?.longitude; acc = last?.accuracy?.toDouble()
        }

        val record = Store.recordParking(carId, lat, lon, acc, delta)
        // 가족 공유 중이면 서버가 FCM으로 모두에게(나 포함) 알림 → 로컬 알림 생략
        if (!Store.data.value.sync.isConfigured) Notifications.parkedLocal(this, record)
        shutdown()
    }

    private fun shutdown() {
        stopSensors()
        state = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_STATE).apply()
        _live.value = null
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
