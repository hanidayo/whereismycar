package kr.hanidayo.eodi.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 네이버 지도 앱으로 차까지 도보 길찾기 (앱이 없으면 설치 화면) */
object NaverMap {
    private const val PACKAGE = "com.nhn.android.nmap"

    fun walkTo(context: Context, lat: Double, lon: Double, name: String) {
        val uri = Uri.parse(
            "nmap://route/walk?dlat=$lat&dlng=$lon&dname=${Uri.encode(name)}&appname=${context.packageName}",
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(store)
            } catch (_: ActivityNotFoundException) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
