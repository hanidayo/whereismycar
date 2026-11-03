package kr.hanidayo.eodi

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kr.hanidayo.eodi.data.Store
import kr.hanidayo.eodi.util.Notifications

class EodiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifications.createChannels(this)
        kr.hanidayo.eodi.drive.ActivityWatch.sync(this)
        // google-services.json 없이 빌드하면 Firebase가 초기화되지 않음 → 푸시만 끄고 앱은 정상 동작
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            runCatching {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { Store.setFcmToken(it) }
            }
        }
    }
}
