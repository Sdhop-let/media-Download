package com.ep.donwnloader

import android.app.WallpaperManager
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 壁纸舞台图像源（Edqiu WallpaperProvider 改造，M11）。
 *
 * 优先级：
 * 1. 用户在设置页自选的舞台壁纸（filesDir/stage_wallpaper.jpg，Edqiu「壁纸锐源」同义）；
 * 2. 系统壁纸（WallpaperManager 公开 API 无需授权，半尺寸降采样后虚化细节无损）；
 * 3. 均不可得 → GlassBackground 落回「中性基底 + 局部光斑团」程序化色场。
 *
 * 设计要点（Edqiu 2026-10-22「舞台方案」）：玻璃折射真实照片才有素材可弯——
 * 程序化渐变采到的永远是均匀色，磨砂/折射「无米下锅」。
 * - 降采样 1/2：全尺寸 ARGB ≈ 14MB，半尺寸 ≈ 3.5MB，后续过 28dp 高斯虚化，细节损失不可感知
 * - 热更新：自选壁纸保存后 [invalidate] 递增版本，GlassBackground 采集版本号强制重载
 *   （系统壁纸变更需重启 App 生效——本 App 无常驻监听，简化处理）
 * - 失败降级：任何一步异常返回 null，观感无缝回退色场
 */
object StageWallpaper {

    private const val TAG = "StageWallpaper"
    private const val CUSTOM_FILE = "stage_wallpaper.jpg"

    @Volatile
    private var cached: ImageBitmap? = null

    /** 当前缓存的壁纸（可空 = 尚未加载或无壁纸）。 */
    val current: ImageBitmap? get() = cached

    /** 壁纸数据版本：变更失效递增，GlassBackground 采集它触发重载。 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** 异步加载舞台壁纸（IO 线程 decode 不阻塞重组）；force=true 跳过缓存重新解码。 */
    suspend fun load(context: Context, force: Boolean = false): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (!force) cached?.let { return@withContext it }
            runCatching {
                // ① 自选舞台壁纸优先（用户在设置 → 外观 → 壁纸舞台选的图）
                val custom = File(context.filesDir, CUSTOM_FILE)
                if (custom.isFile) {
                    return@runCatching decodeDownsampled(custom.absolutePath)?.also { cached = it }
                }
                // ② 系统壁纸兜底（peek 级 API，异常即降级色场）
                val wm = WallpaperManager.getInstance(context)
                val drawable = wm.fastDrawable ?: wm.drawable ?: return@runCatching null
                val dm = context.resources.displayMetrics
                val w = (dm.widthPixels / 2).coerceAtLeast(1)
                val h = (dm.heightPixels / 2).coerceAtLeast(1)
                drawable.toBitmap(w, h).asImageBitmap().also { cached = it }
            }.onFailure {
                android.util.Log.w(TAG, "stage wallpaper load failed: ${it.message}")
            }.getOrNull()
        }

    /** 半尺寸降采样解码（按目标短边缩放，省内存；失败返回 null）。 */
    private fun decodeDownsampled(path: String): ImageBitmap? = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        val dm = appDisplaySize()
        val targetW = (dm.first / 2).coerceAtLeast(1)
        val targetH = (dm.second / 2).coerceAtLeast(1)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= targetW && opts.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            .let { android.graphics.BitmapFactory.decodeFile(path, it) }
            ?.asImageBitmap()
    }.getOrNull()

    private fun appDisplaySize(): Pair<Int, Int> = runCatching {
        val ctx = Store.appContext
        val metrics = ctx.resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }.getOrDefault(1440 to 3120)

    /**
     * 失效缓存并递增版本（自选壁纸保存 / 恢复默认后调用）。500ms 去重。
     */
    fun invalidate() {
        synchronized(this) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastInvalidateAtMs < 500L) return
            lastInvalidateAtMs = now
        }
        cached = null
        _version.value += 1
        android.util.Log.i(TAG, "舞台壁纸变更：缓存已失效，version=${_version.value}")
    }

    private var lastInvalidateAtMs = 0L
}
