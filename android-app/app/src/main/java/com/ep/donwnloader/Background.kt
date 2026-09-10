package com.ep.donwnloader

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** App 完全退出后，仍按周期（≥15 分钟）复测代理并记录最优——「持续优化」的后台部分。 */
class OptimizeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        if (!Store.prefs.autoOptimize) return Result.success()
        if (Store.proxy.testing.value) return Result.success()
        withContext(Dispatchers.IO) { Store.proxy.detectAndTest("后台优选") }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    "proxy_optimize", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<OptimizeWorker>(15, TimeUnit.MINUTES).build())
            } catch (_: Exception) { /* WorkManager 不可用时静默降级为应用内优选 */ }
        }
    }
}

/** TaskManager 在有任务入队/重试时调起前台服务。 */
fun ensureDownloadService(ctx: Context) {
    try {
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
    } catch (_: Exception) { /* 通知权限受限时不影响应用内下载 */ }
}
