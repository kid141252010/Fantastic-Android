package io.github.kid141252010.fantastic

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.kid141252010.fantastic.ui.AppShell
import io.github.kid141252010.fantastic.ui.theme.FantasticTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FantasticApp
        // 播放通知需要这个权限（Android 13+），拒绝了也能播放，只是没有通知栏控件。
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            FantasticTheme {
                AppShell(app)
            }
        }
    }
}
