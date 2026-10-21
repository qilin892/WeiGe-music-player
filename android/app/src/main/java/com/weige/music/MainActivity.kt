package com.weige.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weige.music.play.PlayerController
import com.weige.music.script.MusicSource
import com.weige.music.ui.WeigeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

                AppRoot(vm)
            }
        }
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
    }
}
