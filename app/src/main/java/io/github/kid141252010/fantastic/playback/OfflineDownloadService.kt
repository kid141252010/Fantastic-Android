package io.github.kid141252010.fantastic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** A user-started data-sync foreground task; going back to the player does not cancel a download. */
class OfflineDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloads get() = (application as FantasticApp).downloads
    private var worker: Job? = null
    private var startId = 0
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.startId = startId
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "音乐下载", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            downloads.failQueued("系统未允许在后台启动下载")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (worker?.isActive != true) {
            worker = scope.launch {
                val updates = launch {
                    downloads.entries.collect { list ->
                        notifications.notify(NOTIFICATION, notification(list.firstOrNull { it.status == DownloadStatus.Downloading }))
                    }
                }
                try { downloads.runPending() }
                finally {
                    updates.cancel()
                    stopSelf(this@OfflineDownloadService.startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(active: OfflineDownload?): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(active?.track?.title ?: "音乐下载")
        .setContentText(active?.let { "正在下载到此设备" } ?: "正在准备下载")
        .setProgress(100, active?.percent ?: 0, active?.percent == null)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloads.failQueued("系统暂停了后台下载，请打开应用后重试")
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "music-downloads"
        const val NOTIFICATION = 2407
    }
}
