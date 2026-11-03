package kr.hanidayo.eodi.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kr.hanidayo.eodi.R
import kr.hanidayo.eodi.data.ParkingRecord
import kr.hanidayo.eodi.data.Store
import kr.hanidayo.eodi.ui.MainActivity

object Notifications {
    private const val CH_PARKING = "parking"   // 서버 FCM의 channel_id와 같아야 함
    private const val CH_DRIVE = "drive"
    const val EXTRA_PARKING_ID = "parking_id"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PARKING, "주차 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "가족이 주차를 기록하면 알려줘요"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DRIVE, "운전 기록 중", NotificationManager.IMPORTANCE_LOW).apply {
                description = "운전하는 동안 표시돼요"
                setShowBadge(false)
            }
        )
    }

    private fun openIntent(context: Context, parkingId: String?): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_PARKING_ID, parkingId)
        return PendingIntent.getActivity(
            context, parkingId?.hashCode() ?: 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun driving(context: Context, carName: String): Notification =
        NotificationCompat.Builder(context, CH_DRIVE)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle("운전 기록 중")
            .setContentText("$carName · 주차하면 층을 계산해요")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent(context, null))
            .build()

    /** 앱이 켜져 있을 때 받은 FCM */
    fun push(context: Context, title: String, body: String, data: Map<String, String>) {
        val tag = data["car_id"] ?: "eodi"
        val n = NotificationCompat.Builder(context, CH_PARKING)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, data["parking_id"]))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(tag, 1, n)
    }

    /** 가족 공유를 안 쓸 때 이 기기에만 */
    fun parkedLocal(context: Context, r: ParkingRecord, auto: Boolean = false) {
        val title = "${r.levelName} · ${Store.carName(r.carId)}"
        val text = if (auto) "운전 기록이 오래 멈춰 있어서 GPS를 끄고 주차로 기록했어요. 층이 틀리면 앱에서 보정해 주세요."
        else "주차 위치를 기록했어요. 기둥 사진을 찍어두면 더 찾기 쉬워요."
        val n = NotificationCompat.Builder(context, CH_PARKING)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(if (auto) "자동으로 주차 기록 · $title" else title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, r.id))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(r.carId, 1, n)
    }
}
