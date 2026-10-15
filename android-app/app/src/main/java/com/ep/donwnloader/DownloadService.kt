package com.ep.donwnloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 下载前台服务：App 退到后台/被划走时保活下载，并在通知栏显示聚合进度。
 *  在 Android 16+ 上，此进度通知升级为「以进度为中心的通知（Live Update）」，可被系统提拔到
 *  状态栏胶囊 / ColorOS 16/17 灵动岛（流体云）实时展示；Android < 16 回退为经典进度通知。 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastUpdate = 0L
    private var currentNotif: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL_ID, "下载进度", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onStartCommand 可能在下载途中再次触发（分享新链接/重试），须拿「当前」通知续前台，
        // 否则每次都用「准备中」覆盖灵动岛，把实时进度和完成态顶掉。
        startForeground(NOTIF_ID, currentNotif
            ?: buildNotif("下载服务运行中", "准备中…", null, false).also { currentNotif = it })
        if (started.compareAndSet(false, true)) {
            scope.launch {
                Store.tasks.tasks.collectLatest { tasks ->
                    val active = tasks.filter { it.status == "queued" || it.status == "running" }
                    if (active.isEmpty()) {
                        // 全部结束：仅长下载在灵动岛短暂显示「下载完成」；小文件走底部胶囊/通知，不占灵动岛。
                        val finished = tasks.filter { it.status == "done" || it.status == "partial" }
                        val saved = finished.sumOf { it.doneFiles }
                        val liveDone = finished.any { it.id in Store.tasks.heavyTasks.value }
                        if (saved > 0 && liveDone) {
                            val skipped = finished.sumOf { it.skipped }
                            val text = buildString {
                                append("已保存 $saved 个素材")
                                if (skipped > 0) append(" · 跳过重复 $skipped")
                            }
                            val n = buildNotif("下载完成", text, 100, true).also { currentNotif = it }
                            NotificationManagerCompat.from(this@DownloadService)
                                .notify(NOTIF_ID, n)
                            delay(3000)
                        }
                        stopSelf()
                        return@collectLatest
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate < 250) return@collectLatest   // 节流（配合流式字节上报）
                    lastUpdate = now
                    val live = active.any { it.id in Store.tasks.heavyTasks.value }
                    val done = active.sumOf { it.doneFiles }
                    val total = active.sumOf { it.totalFiles }.coerceAtLeast(1)
                    val bytes = fmtBytes(active.sumOf { it.bytes })
                    val n = buildNotif("正在下载 ${active.size} 个任务", "$done/$total 个文件 · $bytes", done * 100 / total, live)
                    currentNotif = n
                    NotificationManagerCompat.from(this@DownloadService)
                        .notify(NOTIF_ID, n)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotif(title: String, text: String, percent: Int?, live: Boolean): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= 36 && live) {
            // 长下载：Live Update（灵动岛）进度胶囊；ProgressStyle 覆盖 setProgress，进度由 Style 表达。
            val style = NotificationCompat.ProgressStyle().setStyledByProgress(true)
            if (percent == null) style.setProgressIndeterminate(true)
            else style.setProgress(percent.coerceIn(0, 100))
            b.setStyle(style).setRequestPromotedOngoing(true)
        } else {
            // 小文件/旧系统：经典进度，不请求灵动岛提升。
            b.setProgress(100, (percent ?: 0).coerceIn(0, 100), percent == null)
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "download_progress"
        const val NOTIF_ID = 1001
    }
}