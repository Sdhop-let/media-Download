package com.ep.donwnloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

/** 下载结果通知中枢：
 *  1) 有悬浮窗权限且开关开 → 屏幕底部悬浮胶囊（FLAG_NOT_TOUCHABLE 完全不挡操作，3.5s 自动淡出）
 *  2) App 不在前台 → 系统通知兜底（Android 10+ 后台 Toast 被系统抑制，必须另有通道）
 *  前台时由现有 toast 流提示，不重复发通知。 */
object NotifyHub {
    private const val CHANNEL_ID = "download_result"
    private const val NOTIF_ID = 1002
    private val main = Handler(Looper.getMainLooper())
    private var capsule: View? = null

    /** TaskManager 任务收尾统一入口。 */
    fun onTaskFinished(ctx: Context, t: TaskItem) {
        if (t.status == "canceled") return
        val ok = t.status == "done" || t.status == "partial"
        val title: String
        val text: String
        if (ok) {
            title = "✓ 已保存 ${t.doneFiles} 个素材"
            val bits = mutableListOf<String>()
            if (t.skipped > 0) bits.add("跳过重复 ${t.skipped}")
            if (t.message.isNotBlank()) bits.add(t.message)
            text = bits.joinToString(" · ").ifBlank { "可在素材库查看" }
        } else {
            title = "✕ 下载失败"
            text = t.error.ifBlank { "打开 App 查看详情" }
        }
        val app = ctx.applicationContext
        // 1) 底部悬浮胶囊
        if (Store.prefs.capsuleNotify && Settings.canDrawOverlays(app)) {
            showCapsule(app, title)
        }
        // 2) 后台系统通知兜底（前台时 toast 已足够，避免打扰）
        if (!MainActivity.isForeground) {
            postNotification(app, ok, title, text)
        }
    }

    private fun postNotification(ctx: Context, ok: Boolean, title: String, text: String) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "下载结果", NotificationManager.IMPORTANCE_HIGH))
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
        }
    }

    // ---------- 底部悬浮胶囊 ----------

    private fun showCapsule(ctx: Context, text: String) {
        main.post {
            try {
                removeCapsule()
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val d = ctx.resources.displayMetrics.density
                fun dp(v: Int) = (v * d).toInt()
                val tv = TextView(ctx).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    maxLines = 2
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E6172440"))   // 深蓝玻璃，近不透明
                        cornerRadius = dp(26).toFloat()
                    }
                    setPadding(dp(20), dp(11), dp(20), dp(11))
                    alpha = 0f
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE   // 纯展示，不挡任何操作
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.y = dp(88)   // 悬浮在导航栏上方，App 内也与底栏胶囊视觉错开
                wm.addView(tv, lp)
                capsule = tv
                tv.animate().alpha(1f).setDuration(180).start()
                main.postDelayed({ tv.animate().alpha(0f).setDuration(320).start() }, 3200)
                // 移除不依赖动画 endAction（部分 ROM 不回调 → 窗口永久泄漏），Handler 定时兜底
                main.postDelayed({ if (capsule === tv) removeCapsule() }, 3550)
            } catch (_: Exception) { /* 悬浮窗失败静默（系统通知兜底已另行判断） */ }
        }
    }

    private fun removeCapsule() {
        val v = capsule ?: return
        capsule = null
        try {
            // 注意：v.parent 是 ViewRootImpl 而非 WindowManager，直接转型恒为 null（会永久泄漏窗口）
            val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        } catch (_: Exception) { }
    }
}
