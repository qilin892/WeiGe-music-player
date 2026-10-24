package com.weige.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weige.music.play.PlayerController
import com.weige.music.script.MusicSource
import com.weige.music.ui.WeigeTheme

class MainActivity : ComponentActivity() {

    /** 通知栏点进来要打开的标记，热启动走 onNewIntent，冷启动走 onCreate */
    private var openPlayer by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        openPlayer = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true

        MusicSource.init(this)

        // 启动窗口背景是静态资源，来不及跟随 Compose 主题；
        // 这里按上次保存的模式先刷一次窗口底色，避免白天模式启动闪黑。
        val darkNow = getSharedPreferences("weige", 0).getBoolean("dark", false)
        window.decorView.setBackgroundColor(
            if (darkNow) 0xFF0A0908.toInt() else 0xFFF5F0E6.toInt()
        )

        askNotificationPermission()

        setContent {
            val vm: AppViewModel = viewModel()

            WeigeTheme(dark = vm.dark) {
                // 状态栏/导航栏用浅色还是深色图标，跟着主题走
                LaunchedEffect(vm.dark) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !vm.dark
                        isAppearanceLightNavigationBars = !vm.dark
                    }
                }

                LaunchedEffect(Unit) {
                    PlayerController.connect(this@MainActivity)
                }

                // 从通知栏点进来的：直接落到播放页。
                // 冷启动时 MediaController 还没连上、state.song 为空，
                // 这里只置位；AppRoot 的判断是 showPlayer && song != null，
                // 等连接完成 song 就位，播放页自然盖上来。
                LaunchedEffect(openPlayer) {
                    if (openPlayer) {
                        vm.showPlayer = true
                        openPlayer = false
                    }
                }

                AppRoot(vm)
            }
        }
    }

    /** 已经在运行时从通知栏点进来（singleTop，不会重走 onCreate） */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer = true
    }

    /** Android 13+ 不申请这个权限，媒体通知就不显示（播放本身不受影响） */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    private companion object {
        const val REQ_NOTIFICATION = 1001
        const val EXTRA_OPEN_PLAYER = "openPlayer"
    }
}
