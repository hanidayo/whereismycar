package kr.hanidayo.eodi.drive

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kr.hanidayo.eodi.data.Store

/**
 * 블루투스를 꺼 둔 날을 위한 보조 감지: 구글 활동 인식으로 "차에 탐/내림"을 받아 기록.
 * 설정에서 차 하나를 지정했을 때만 켜짐. 블루투스로 시작한 운전에는 끼어들지 않음.
 */
object ActivityWatch {
    private const val ACTION = "kr.hanidayo.eodi.ACTIVITY_TRANSITION"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 7,
            Intent(context, ActivityReceiver::class.java).setAction(ACTION),
            // 활동 인식 결과를 인텐트에 채워 넣어야 해서 MUTABLE
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /** 설정에 맞게 감지를 켜거나 끔 (앱 시작·재부팅·설정 변경 때 호출) */
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        Store.init(context)
        val client = ActivityRecognition.getClient(context)
        val enabled = Store.data.value.activityCarId != null && hasPermission(context)
        runCatching {
            if (enabled) {
                val transitions = listOf(
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    ActivityTransition.ACTIVITY_TRANSITION_EXIT,
                ).map {
                    ActivityTransition.Builder()
                        .setActivityType(DetectedActivity.IN_VEHICLE)
                        .setActivityTransition(it)
                        .build()
                }
                client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
            } else {
                client.removeActivityTransitionUpdates(pendingIntent(context))
            }
        }
    }

    fun isTransition(intent: Intent) = intent.action == ACTION
}

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ActivityWatch.sync(context) // 재부팅·업데이트 후 감지 등록이 풀리므로 다시
                return
            }
        }
        if (!ActivityWatch.isTransition(intent) || !ActivityTransitionResult.hasResult(intent)) return
        val carId = Store.data.value.activityCarId ?: return
        val event = ActivityTransitionResult.extractResult(intent)?.transitionEvents
            ?.lastOrNull { it.activityType == DetectedActivity.IN_VEHICLE } ?: return
        when (event.transitionType) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> DriveService.startFromActivity(context, carId)
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> DriveService.parkFromActivity(context)
        }
    }
}
