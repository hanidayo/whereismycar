package kr.hanidayo.eodi.drive

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kr.hanidayo.eodi.data.Store

/**
 * 차 블루투스에 연결되면 운전 기록 시작, 끊기면 주차 기록.
 * 설정에서 차마다 블루투스 기기를 골라 두면 동작합니다.
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        val carId = Store.carIdForDevice(address) ?: return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> DriveService.start(context, carId, fromAutomation = true)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> DriveService.park(context, carId)
        }
    }
}
