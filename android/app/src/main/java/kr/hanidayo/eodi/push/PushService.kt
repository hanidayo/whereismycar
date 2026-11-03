package kr.hanidayo.eodi.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kr.hanidayo.eodi.data.Store
import kr.hanidayo.eodi.util.Notifications

/**
 * FCM 수신. 앱이 백그라운드면 시스템이 알림을 직접 띄우고(탭하면 앱이 열림),
 * 앱이 켜져 있을 때만 여기서 알림을 띄웁니다. 받을 때마다 최신 기록도 받아옵니다.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Store.init(applicationContext)
        Store.setFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Store.init(applicationContext)
        Store.scope.launch { Store.refresh() }
        val n = message.notification ?: return
        Notifications.push(this, n.title ?: "어디댔지", n.body ?: "", message.data)
    }
}
