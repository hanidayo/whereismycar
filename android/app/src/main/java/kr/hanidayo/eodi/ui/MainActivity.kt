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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
}

enum class Tab(val label: String) { Home("홈"), History("기록"), Location("위치"), Settings("설정") }

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var tab by mutableStateOf(Tab.Home)
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
                BackHandler(enabled = screen != Screen.Home || tab != Tab.Home) {
                    if (screen != Screen.Home) screen = Screen.Home else tab = Tab.Home
                }
                when (val s = screen) {
                    is Screen.Detail -> DetailScreen(s.recordId, onBack = { screen = Screen.Home })
                    Screen.Home -> Scaffold(
                        containerColor = Eodi.colors.background,
                        contentWindowInsets = WindowInsets(0),
                        bottomBar = {
                            NavigationBar(containerColor = Eodi.colors.card, tonalElevation = 0.dp) {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = tab == t,
                                        onClick = { tab = t },
                                        icon = {
                                            Icon(when (t) {
                                                Tab.Home -> Icons.Filled.Home
                                                Tab.History -> Icons.Filled.Schedule
                                                Tab.Location -> Icons.Filled.Map
                                                Tab.Settings -> Icons.Filled.Settings
                                            }, t.label)
                                        },
                                        label = { Text(t.label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Eodi.colors.primary,
                                            selectedTextColor = Eodi.colors.primary,
                                            indicatorColor = Eodi.colors.soft,
                                            unselectedIconColor = Eodi.colors.secondaryText,
                                            unselectedTextColor = Eodi.colors.secondaryText,
                                        ),
                                    )
                                }
                            }
                        },
                    ) { pad ->
                        Box(Modifier.padding(pad)) {
                            when (tab) {
                                Tab.Home -> HomeScreen(
                                    resumeTick = resumeTick,
                                    onOpen = { screen = Screen.Detail(it) },
                                    onHistory = { tab = Tab.History },
                                    onAskPermissions = { askPermissions.launch(it) },
                                )
                                Tab.History -> HistoryScreen(onOpen = { screen = Screen.Detail(it) })
                                Tab.Location -> LocationScreen(resumeTick, onOpen = { screen = Screen.Detail(it) })
                                Tab.Settings -> SettingsScreen()
                            }
                        }
                    }
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
