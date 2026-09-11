package com.ep.donwnloader

import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * 外部共享路径统一读层（2026-09-11 root 直读改造的配套层）。
 *
 * 背景：共享引用模式下 media.file_path/source_url 登记的是【原路径】（如
 * /sdcard/Android/data/com.ed.edqiu/files/Download/xxx.mp4），App 进程无权直读
 * （Android 11+ FUSE 拦截无视 MANAGE_EXTERNAL_STORAGE）。已 root 设备经 RootIO：
 *  - 只读元数据/文本/哈希：走 su 命令直出，零落盘（RootIO.listTree/statOne/catText/md5Batch）；
 *  - 需要随机访问 fd 的消费方（视频播放 seek、MediaMetadataRetriever 抽帧、
 *    系统分享/FileProvider、Coil 大图）：按需把单个文件流式物化到
 *    cache/ro_share/（LRU 上限 + TTL 清理），原文件零改动，Edqiu 生态不受影响。
 *
 * isForeign 判定：路径不在本 App 私有目录内视为外部共享路径（登记时已验证可达）。
 * 这样 EP 自有文件（downloads/mirror 等）一律原样 File 直用，零行为变化。
 */
object ContentAccess {

    private const val TAG = "ContentAccess"
    private const val RO_DIR = "ro_share"
    private const val MAX_CACHE_BYTES = 300L * 1024 * 1024   // 物化缓存上限
    private const val TTL_MS = 7L * 24 * 3600 * 1000         // 单文件物化 7 天有效期

    /** 是否外部共享路径（EP 私有目录之外）。 */
    fun isForeign(path: String): Boolean {
        if (path.isBlank()) return false
        val own = runCatching { Store.appContext.getExternalFilesDir(null)?.absolutePath }.getOrNull()
        val dataDir = runCatching { Store.appContext.applicationInfo.dataDir }.getOrNull()
        if (own != null && path.startsWith(own + "/")) return false
        if (dataDir != null && path.startsWith(dataDir + "/")) return false
        return true
    }

    /**
     * 取一个【本地可读】的 File：自有路径原样返回；外部路径按需物化到 ro_share
     * （重复调用命中已物化副本即秒回）。失败返回 null（root 不可用/读失败）。
     * 消费方：视频播放、图片全屏查看、系统分享——都只在用户主动动作时调用。
     */
    fun local(path: String): File? {
        val f = File(path)
        if (f.isFile) return f
        if (!isForeign(path)) return null
        // 缓存 key 带 size+mtime：属主同路径换内容后旧副本自然过期（TTL sweep 清理），播的不是旧内容
        val stat = RootIO.statOne(path) ?: return null
        val dst = roShareFile(path, stat.size, stat.mtimeMs) ?: return null
        if (dst.isFile && dst.length() > 0L) return dst
        Log.i(TAG, "materialize: $path")
        return if (RootIO.materialize(path, dst)) dst else null
    }

    /** 外部路径流式读（小文件/顺序读场景，如图片解码）。自有路径直接 FileInputStream。 */
    fun readStream(path: String): InputStream? {
        val f = File(path)
        if (f.isFile) return runCatching { f.inputStream() }.getOrNull()
        if (!isForeign(path)) return null
        return RootIO.catStream(path)
    }

    /** 外部路径存在性（低频调用）：自有走 File；外部走 su stat（root 不可用视为不存在）。 */
    fun existsRemote(path: String): Boolean {
        val f = File(path)
        if (f.isFile) return true
        if (!isForeign(path)) return false
        return RootIO.statOne(path) != null
    }

    /** 外部视频缩略图持久输出位：外部私有 files/.thumbs_ext/<key12>.jpg（不写属主目录）。 */
    fun thumbOutFile(srcPath: String): File? {
        val ext = runCatching { Store.appContext.getExternalFilesDir(null) }.getOrNull() ?: return null
        return File(File(ext, ".thumbs_ext"), "${RootIO.key12(srcPath)}.jpg")
    }

    /**
     * 外部图片的本地缩略图：su cat 流 → 一次性解码（RGB_565 控内存）→ 降采样 520px
     * → 写 .thumbs_ext 持久位。网格列表只需缩略图，避免为滚动逐张物化原图；
     * 全屏查看（用户主动点击）才走 local() 物化原图。
     */
    fun imageThumb(srcPath: String): File? {
        val out = thumbOutFile(srcPath) ?: return null
        if (out.isFile && out.length() > 0L) return out
        val src = readStream(srcPath) ?: return null
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
            val bmp = android.graphics.BitmapFactory.decodeStream(src, null, opts) ?: return null
            val w = 520
            val h = (bmp.height * (w.toDouble() / bmp.width)).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            out.parentFile?.mkdirs()
            out.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
            bmp.recycle()
            if (scaled !== bmp) scaled.recycle()
            out.takeIf { it.isFile && it.length() > 0L }
        } catch (t: Throwable) {
            Log.w(TAG, "imageThumb: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            runCatching { src.close() }
        }
    }

    /** 物化目标：cache/ro_share/<key12>.<ext>（key 含 size+mtime，内容更新即自然过期）。 */
    private fun roShareFile(path: String, size: Long, mtimeMs: Long): File? {
        val dir = runCatching { File(Store.appContext.cacheDir, RO_DIR) }.getOrNull() ?: return null
        val ext = path.substringAfterLast('.', "")
        val name = RootIO.key12("$path|$size|$mtimeMs") + if (ext.isNotBlank() && ext.length <= 5) ".$ext" else ""
        return File(dir, name)
    }

    /** 启动清理：TTL 过期先删；仍超容量再按 lastModified 从旧到新删，直到回到上限内。 */
    fun sweep() {
        try {
            val dir = File(Store.appContext.cacheDir, RO_DIR)
            if (!dir.isDirectory) return
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            val now = System.currentTimeMillis()
            var bytes = 0L
            val alive = mutableListOf<File>()
            files.forEach { f ->
                if (now - f.lastModified() > TTL_MS) f.delete() else { alive.add(f); bytes += f.length() }
            }
            if (bytes <= MAX_CACHE_BYTES) return
            alive.sortedBy { it.lastModified() }.forEach { f ->
                if (bytes <= MAX_CACHE_BYTES) return
                bytes -= f.length()
                f.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sweep: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
