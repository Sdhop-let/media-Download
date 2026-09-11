package com.ep.donwnloader

import android.util.Log
import java.io.File

/**
 * 媒体文件的磁盘侧删除与残留清理。
 *
 * 2026-09-10 修复「删除素材后下载目录残留垃圾」：
 *  - 旧实现先删 DB 行再删文件，且 File.delete() 返回值被忽略 → 删除一旦失败文件就永久失联成孤儿；
 *  - 清空回收站只删文件本身，留下一串空目录（.thumbs / 作者目录 / 平台目录）。
 * 统一后的顺序：文件删干净 → 才硬删 DB 行（失败的条目保留在回收站可重试）→ 自底向上清理空目录。
 */
object MediaFiles {

    private const val TAG = "MediaFiles"

    /** 媒体扩展名（判定"看起来是素材"的文件）。 */
    val MEDIA_EXTS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic",
        "mp4", "webm", "mov", "m4v", "mkv")

    /**
     * 删除单个磁盘文件。路径为空或文件已不存在视为成功；删除失败返回 false（原因写日志）。
     * 应用私有目录（Android/data/<pkg>）可直接删；外部目录依赖 MANAGE_EXTERNAL_STORAGE 授权。
     */
    fun deleteDisk(path: String): Boolean {
        if (path.isBlank()) return true
        val f = File(path)
        if (!f.exists()) return true
        val ok = f.delete()
        if (!ok) Log.w(TAG, "deleteDisk 失败: $path")
        return ok
    }

    /**
     * 彻底删除回收站条目：先删原件与缩略图，二者都成功（或本就缺失）才硬删 DB 行；
     * 有文件删不掉的条目保留在回收站（可重试/可恢复）。最后清理下载目录里的空目录。
     * 共享引用条目（文件在本 App 私有目录之外，如 Edqiu 共享目录）只解除登记，
     * 不物理删原文件——文件归属主 App 管理，删了会破坏对方生态。
     * 返回 (成功条数, 失败保留条数)。
     */
    fun purgeEntries(rows: List<Triple<Long, String, String>>): Pair<Int, Int> {
        val base = Store.appContext.getExternalFilesDir(null)?.absolutePath?.trimEnd('/') ?: ""
        var ok = 0
        val failedIds = mutableListOf<Long>()
        rows.forEach { (id, f, t) ->
            // 必须带分隔符，防前缀重叠目录（files vs files_evil）误判导致物理删外部文件
            val shared = f.isNotBlank() && base.isNotBlank() &&
                !File(f).absolutePath.startsWith(base + "/")
            val okF = if (shared) true else deleteDisk(f)
            val okT = deleteDisk(t)
            if (shared && okT) Log.i(TAG, "purgeEntries: 共享引用只解除登记（保留外部文件）$f")
            if (okF && okT) ok++ else failedIds.add(id)
        }
        Store.db.hardDeleteMedia(rows.map { it.first }.filterNot { it in failedIds })
        if (failedIds.isNotEmpty()) {
            Log.w(TAG, "purgeEntries: ${failedIds.size} 条文件删除失败，保留在回收站")
        }
        val baseDir = Store.appContext.getExternalFilesDir(null)
        if (baseDir != null) pruneEmptyDirs(File(baseDir, "downloads"))
        Log.i(TAG, "purgeEntries: 成功 $ok 条，失败 ${failedIds.size} 条")
        return ok to failedIds.size
    }

    /**
     * 孤儿与残留清理（仅限应用自有下载目录 downloads/，不碰外部同步目录原文件）：
     *  1) 磁盘上有、库里未登记（file_path / thumb_path 均不匹配）的媒体文件与 .part 下载残片；
     *  2) 最后 modified 在 10 分钟内的文件跳过（避免误删正在下载的内容）；
     *  3) 随后自底向上清理全部空目录。
     * 返回 (删除文件数, 清理目录数)。
     */
    fun residueSweep(): Pair<Int, Int> {
        val base = Store.appContext.getExternalFilesDir(null) ?: return 0 to 0
        val root = File(base, "downloads")
        if (!root.isDirectory) return 0 to 0
        val now = System.currentTimeMillis()
        var files = 0
        // 活跃任务的 dest 与 .part 集合（Store 未初始化时为空集，保守不删）
        val active = runCatching { Store.tasks.activeDownloadPaths() }.getOrDefault(emptySet())
        val all = root.walkBottomUp().filter { it.isFile }.toList()
        all.forEach { f ->
            // 排除活跃任务正在下载的 dest 与 .part（配合 .part 带 taskId 命名），哪怕 mtime 超 10 分钟也不误删
            if (f.absolutePath in active) return@forEach
            if (f.lastModified() > now - 10 * 60_000L) return@forEach  // 活跃文件保护
            val isPart = f.name.endsWith(".part")
            if (!isPart && f.extension.lowercase() !in MEDIA_EXTS) return@forEach
            if (!isPart && Store.db.diskPathRegistered(f.absolutePath)) return@forEach
            if (deleteDisk(f.absolutePath)) files++
        }
        val dirs = pruneEmptyDirs(root)
        Log.i(TAG, "residueSweep: 删除 $files 个未登记文件，清理 $dirs 个空目录")
        return files to dirs
    }

    /**
     * 自底向上清理 root 下的空目录（含 .thumbs 与空作者/平台目录），root 本身保留。
     * 返回删除的目录数。walkBottomUp 先收集再删，避免迭代中修改树。
     */
    fun pruneEmptyDirs(root: File): Int {
        if (!root.isDirectory) return 0
        var pruned = 0
        val dirs = root.walkBottomUp().filter { it.isDirectory }.toList()
        dirs.forEach { d ->
            if (d.absolutePath == root.absolutePath) return@forEach
            // 自底向上：子目录此刻已被处理，目录里只剩空目录或已为空
            val empty = d.list()?.isEmpty() ?: true
            if (empty && d.delete()) {
                pruned++
                Log.i(TAG, "pruneEmptyDirs: 删空目录 ${d.absolutePath}")
            }
        }
        return pruned
    }
}
