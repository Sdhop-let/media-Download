package com.ep.donwnloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 视频封面缩略图。
 *
 * 素材库视频封面缺失的根因链（2026-09-10 真机 OnePLK110/ColorOS 定位）：
 *  1) 下载时单时间点(400ms)抽帧在部分编码/设备取不到帧 → thumb_path 为空；
 *  2) 惰性补生成走 MediaMetadataRetriever.setDataSource(绝对路径)，媒体栈对
 *     /storage/emulated/0/Android/data/<pkg> 下的原始路径可能被拒（fd 视图问题），
 *     且旧实现把所有异常静默吞掉 → .thumbs 目录存在但永远空着，界面只有 🎬 占位符。
 * 修复：
 *  - setDataSource 改为「文件描述符优先」（应用对自身外部私有目录有 FUSE 权限，fd 直传
 *    绕开媒体栈的路径访问限制），原始路径仅作兜底重试；
 *  - 全链路 Log（tag=VideoThumbs），失败原因可在 logcat 追溯；
 *  - 复用已有缩略图前先做 JPEG 头校验，截断/损坏文件自动删除重建；
 *  - backfillMissing()：启动时批量补齐缺封面的视频并回写 DB（覆盖回收站详情等直读
 *    thumb_path 的场景，不只依赖格子滑入时的惰性生成）。
 */
object VideoThumbs {

    private const val TAG = "VideoThumbs"

    /** 内存缓存：videoFilePath -> 封面 File（仅当文件存在且有效时使用）。 */
    private val cache = ConcurrentHashMap<String, File?>()

    /** 解析一个视频封面（不触发生成）：已有有效缩略图 → 内存缓存 → 否则 null。 */
    fun resolve(media: MediaItem): File? {
        if (media.thumbPath.isNotBlank()) {
            val f = File(media.thumbPath)
            if (f.isFile && f.length() > 0L && validJpeg(f)) return f
        }
        val c = cache[media.filePath]
        if (c != null && c.isFile && c.length() > 0L && validJpeg(c)) return c
        return null
    }

    /** 惰性补生成封面：resolve 为空时在 IO 线程调用。成功会缓存并发起 DB 回写。 */
    fun ensure(media: MediaItem): File? {
        resolve(media)?.let { return it }
        val video = File(media.filePath)
        if (!video.isFile) {
            Log.w(TAG, "ensure: 源视频不存在 ${media.filePath}")
            return null
        }
        val out = generate(video)
        if (out == null) {
            Log.w(TAG, "ensure: 生成失败 ${video.name}")
            return null
        }
        cache[media.filePath] = out
        try {
            Store.scope.launch(Dispatchers.IO) {
                runCatching { Store.db.updateMediaThumb(media.id, out.absolutePath) }
            }
        } catch (_: Exception) { /* 回写失败不影响展示 */ }
        return out
    }

    /** 启动补齐：给缺/坏封面的视频批量补生成（上限 limit 条），成功回写 DB。 */
    fun backfillMissing(limit: Int = 200) {
        try {
            val missing = Store.db.listMediaTime("", "video", "", null, 0, limit).filter { m ->
                if (m.thumbPath.isBlank()) true
                else File(m.thumbPath).let { !it.isFile || it.length() == 0L }
            }
            if (missing.isEmpty()) return
            Log.i(TAG, "backfill: ${missing.size} 个视频缺封面，开始补生成")
            var ok = 0
            missing.forEach { m -> if (ensure(m) != null) ok++ }
            Log.i(TAG, "backfill: 完成 $ok/${missing.size}")
        } catch (t: Throwable) {
            Log.w(TAG, "backfill: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 对视频文件抽帧生成 ≤520px 宽 JPEG 缩略图；任一环节失败返回 null（原因见日志）。 */
    fun generate(video: File): File? {
        return try {
            val dir = File(video.parentFile, ".thumbs").apply { mkdirs() }
            if (!dir.isDirectory) {
                Log.w(TAG, "generate: .thumbs 目录创建失败 ${dir.absolutePath}")
                return null
            }
            val out = File(dir, video.nameWithoutExtension + ".jpg")
            if (out.isFile && out.length() > 0L && validJpeg(out)) return out
            // 存在但损坏（截断/0 宽高）的旧缩略图：删掉重建
            if (out.exists()) {
                Log.w(TAG, "generate: 旧缩略图损坏，重建 ${out.name}")
                out.delete()
            }
            val bitmap = extractFrame(video)
            if (bitmap == null) {
                Log.w(TAG, "generate: 抽帧全失败 ${video.name} (${video.length()} bytes)")
                return null
            }
            val w = 520
            val h = (bitmap.height * (w.toDouble() / bitmap.width)).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (!validJpeg(out)) {
                Log.w(TAG, "generate: 写出的 JPEG 校验失败 ${out.absolutePath} size=${out.length()}")
                return null
            }
            Log.i(TAG, "generate: OK ${out.name} size=${out.length()}")
            out
        } catch (t: Throwable) {
            Log.w(TAG, "generate: 异常 ${video.name}: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 抽帧：fd 直传优先（绕开媒体栈对 Android/data 原始路径的访问限制），路径方式兜底。 */
    private fun extractFrame(video: File): Bitmap? {
        // 1) FileDescriptor 方式：fd 由应用自身打开，不受媒体进程路径白名单影响
        try {
            FileInputStream(video).use { fis ->
                val retr = MediaMetadataRetriever()
                try {
                    retr.setDataSource(fis.fd)
                    val bm = frames(retr)
                    if (bm != null) return bm
                } finally {
                    runCatching { retr.release() }
                }
            }
            Log.w(TAG, "extractFrame: fd 方式未取到帧，改试路径方式 ${video.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "extractFrame: fd 方式异常: ${t.javaClass.simpleName}: ${t.message}")
        }
        // 2) 原始路径方式兜底（fd 已失败时死马当活马医）
        val retr = MediaMetadataRetriever()
        try {
            retr.setDataSource(video.absolutePath)
            return frames(retr)
        } catch (t: Throwable) {
            Log.w(TAG, "extractFrame: 路径方式异常: ${t.javaClass.simpleName}: ${t.message}")
            return null
        } finally {
            runCatching { retr.release() }
        }
    }

    /** 多个固定时间点 + 按时长比例扫描 + 兜底，任一命中的帧即返回。 */
    private fun frames(retr: MediaMetadataRetriever): Bitmap? {
        val durMs = runCatching {
            retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
        val unknown = durMs <= 0
        for (us in listOf(400_000L, 1_000_000L, 250_000L)) {
            if (unknown || us / 1000 < durMs - 50) {
                frame(retr, us)?.let { return it }
            }
        }
        if (durMs > 0) {
            for (f in listOf(0.15, 0.3, 0.5, 0.7)) {
                frame(retr, (durMs * f).toLong() * 1000)?.let { return it }
            }
        }
        return frame(retr, 0)
    }

    /** 抽一帧：CLOSEST_SYNC 为空再退 CLOSEST，最后退默认首帧。 */
    private fun frame(retr: MediaMetadataRetriever, us: Long): Bitmap? {
        runCatching {
            retr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { return it }
        }
        return runCatching {
            retr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)?.let { return it }
        }.getOrNull() ?: runCatching {
            @Suppress("DEPRECATION") retr.getFrameAtTime(us)
        }.getOrNull()
    }

    /** JPEG 有效性头校验（只解码尺寸头，开销极低；能识别截断/损坏文件）。 */
    private fun validJpeg(f: File): Boolean = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        opts.outWidth > 0 && opts.outHeight > 0
    } catch (_: Exception) {
        false
    }
}
