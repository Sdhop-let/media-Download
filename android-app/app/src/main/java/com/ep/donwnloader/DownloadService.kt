package com.ep.donwnloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 下载前台服务：App 退到后台/被划走时保活下载，并在通知栏显示聚合进度。 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL_ID, "下载进度", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif("下载服务运行中", "准备中…"))
        scope.launch {
            Store.tasks.tasks.collectLatest { tasks ->
                val active = tasks.filter { it.status == "queued" || it.status == "running" }
                if (active.isEmpty()) {
                    stopSelf()
                    return@collectLatest
                }
                val now = System.currentTimeMillis()
                if (now - lastUpdate < 600) return@collectLatest   // 节流
                lastUpdate = now
                val done = active.sumOf { it.doneFiles }
                val total = active.sumOf { it.totalFiles }.coerceAtLeast(1)
                val bytes = fmtBytes(active.sumOf { it.bytes })
                NotificationManagerCompat.from(this@DownloadService)
                    .notify(NOTIF_ID, buildNotif("正在下载 ${active.size} 个任务", "$done/$total 个文件 · $bytes"))
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotif(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "download_progress"
        const val NOTIF_ID = 1001
    }
}
