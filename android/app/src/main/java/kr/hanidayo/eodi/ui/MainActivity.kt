package kr.hanidayo.eodi.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.hanidayo.eodi.data.Store
import kr.hanidayo.eodi.util.Notifications

sealed interface Screen {
    data object Home : Screen
    data class Detail(val recordId: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)
    /** 설정 화면에서 돌아왔을 때 권한 상태를 다시 읽기 위한 카운터 */
    private var resumeTick by mutableIntStateOf(0)

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        askPermissions.launch(firstPermissions())

        setContent {
            EodiTheme {
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        resumeTick = resumeTick,
                        onOpen = { screen = Screen.Detail(it) },
                        onSettings = { screen = Screen.Settings },
                        onAskPermissions = { askPermissions.launch(it) },
                    )
                    is Screen.Detail -> DetailScreen(s.recordId, onBack = { screen = Screen.Home })
                    Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        lifecycleScope.launch { Store.refresh() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 알림을 눌러 들어오면 그 주차 기록을 바로 엶 */
    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(Notifications.EXTRA_PARKING_ID) ?: return
        lifecycleScope.launch {
            Store.refresh()
            if (Store.record(id) != null) screen = Screen.Detail(id)
        }
    }

    private fun firstPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
