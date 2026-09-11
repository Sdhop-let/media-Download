package com.ep.donwnloader

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Root 文件通道：经 su 访问本 App 进程无权直读的路径——典型是其他应用的
 * Android/data 私有目录（Android 11+ 的 FUSE 拦截无视 MANAGE_EXTERNAL_STORAGE）。
 *
 * 设计原则（2026-09-11 用户拍板，替代旧版整树镜像 cp -rpu）：【零镜像、零复制】
 *  - 目录枚举 / 元数据（size+mtime）/ 哈希 / sidecar 文本：全部在设备侧命令直出，
 *    只向 App 传文本，不落盘；
 *  - 仅当消费方必须随机访问 fd（视频播放 seek、MediaMetadataRetriever 抽帧、
 *    系统分享、FileProvider）时，才由 ContentAccess 按需把【单个文件】流式物化到
 *    本 App cache（LRU 清理）；
 *  - 属主目录（Edqiu 生态）永不写入、永不改动。
 *
 * 与 RootProxy 的差异：RootProxy 面向代理探测（授权失败静默 15 分钟、短超时）；
 * 本类面向文件操作——大目录枚举/批量哈希可达数十秒，长超时 + 输出格式约定。
 */
object RootIO {

    private const val SU_TIMEOUT_MS = 180_000L   // 3 分钟：大目录枚举/批量 md5 单条命令正常远小于 3 分钟；md5Batch 已按 40 个/批分批执行，每批独立计时
    private const val SENTINEL = "__ROOTIO_OK__"

    /** su 是否可用（复用 RootProxy 的探测与缓存；未授权/非 root 返回 false）。 */
    fun available(): Boolean =
        runCatching { RootProxy.probe().suAvailable }.getOrDefault(false)

    /** su -c 执行命令，返回 stdout（含哨兵校验由调用方做）；失败/超时返回 null。 */
    fun exec(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).start()
        var text: String? = null
        val reader = Thread { text = runCatching { p.inputStream.bufferedReader().readText() }.getOrNull() }
        reader.isDaemon = true
        reader.start()                       // 并行读流，防 stdout 缓冲满死锁
        val ok = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        runCatching { p.destroy() }
        reader.join(1000)
        if (ok) text else null
    } catch (_: Exception) {
        null
    }

    /** shell 单引号转义（防路径中的 ' 破坏命令）。 */
    private fun sh(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /** 路径 → md5 前 12 位稳定 key（本地副本命名/旧镜像目录识别用）。 */
    fun key12(s: String): String = runCatching {
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
    }.getOrDefault("")

    // ---------- 只读元数据（零复制） ----------

    /** 设备侧文件条目：绝对路径 + size + mtime(ms)。 */
    data class RootEntry(val path: String, val size: Long, val mtimeMs: Long)

    /**
     * 枚举目录树全部文件（含 size/mtime），单次 su 会话直出文本。
     * find -print0 | xargs -0 stat 兼容空格/中文路径；分隔符 '|'（EP/Edqiu 命名不含竖线）。
     * 空树返回空 list（合法状态）；仅 su 不可用/超时返回 null。
     */
    fun listTree(srcPath: String): List<RootEntry>? {
        if (srcPath.isBlank()) return null
        val out = exec(
            "find ${sh(srcPath)} -type f -print0 | xargs -0 stat -c '%s|%Y|%n' 2>/dev/null; echo $SENTINEL"
        ) ?: return null
        if (!out.contains(SENTINEL)) return null
        return out.lineSequence().mapNotNull { line ->
            if (line.contains(SENTINEL)) return@mapNotNull null
            val seg = line.split('|', limit = 3)
            if (seg.size < 3) return@mapNotNull null
            val size = seg[0].toLongOrNull() ?: return@mapNotNull null
            val mtime = seg[1].toLongOrNull() ?: return@mapNotNull null
            RootEntry(seg[2], size, mtime * 1000L)
        }.toList()
    }

    /** 单文件 stat（低频场景：重复下载命中检查、单文件存在性）。失败返回 null。 */
    fun statOne(path: String): RootEntry? {
        val out = exec("stat -c '%s|%Y|%n' ${sh(path)} 2>/dev/null; echo $SENTINEL") ?: return null
        if (!out.contains(SENTINEL)) return null
        val seg = out.lineSequence().firstOrNull { it.contains('|') && !it.contains(SENTINEL) } ?: return null
        val parts = seg.split('|', limit = 3)
        if (parts.size < 3) return null
        val size = parts[0].toLongOrNull() ?: return null
        val mtime = parts[1].toLongOrNull() ?: return null
        return RootEntry(parts[2], size, mtime * 1000L)
    }

    /** 读文本文件（sidecar JSON 等小文件）。哨兵隔离内容边界，失败返回 null。 */
    fun catText(path: String): String? {
        val out = exec("cat ${sh(path)}; echo; echo $SENTINEL") ?: return null
        if (!out.contains(SENTINEL)) return null
        return out.substringBefore(SENTINEL)
            .trimEnd('\n')
            .takeIf { it.isNotEmpty() }
    }

    /**
     * md5sum 批量：远端直出哈希（视频几十 MB 也不传输内容）。
     * 每条命令限 40 个文件防 ARG_MAX；返回 path → hash（32 hex）。
     */
    fun md5Batch(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        paths.chunked(40).forEach { chunk ->
            val res = exec(chunk.joinToString(" ") { sh(it) }.let { "md5sum $it 2>/dev/null; echo $SENTINEL" })
                ?: return@forEach
            if (!res.contains(SENTINEL)) return@forEach
            res.lineSequence().filter { !it.contains(SENTINEL) }.forEach { line ->
                val hash = line.takeWhile { it.isLetterOrDigit() }
                if (hash.length != 32) return@forEach
                val path = line.dropWhile { it != ' ' }.trimStart(' ', '*')
                if (path.isNotBlank()) out[path] = hash
            }
        }
        return out
    }

    // ---------- 按需流读取 / 物化（消费方需要 fd 或随机访问时） ----------

    /** su cat 流：读 sidecar / 流式复制单文件。用完必须 close()（联动销毁进程）。 */
    fun catStream(path: String): InputStream? = try {
        val p = ProcessBuilder("su", "-c", "cat ${sh(path)}").start()
        SuStream(p.inputStream, p)
    } catch (_: Exception) {
        null
    }

    /** 包装 su cat 的 stdout；close 时销毁进程防僵尸。 */
    private class SuStream(private val src: InputStream, private val proc: Process) : InputStream() {
        override fun read(): Int = src.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len)
        override fun available(): Int = src.available()
        override fun close() {
            runCatching { src.close() }
            runCatching { proc.destroy() }
        }
    }

    /** 把远端文件流式物化到本地 dst（由 ContentAccess 决定 dst 与清理策略）。 */
    fun materialize(path: String, dst: File): Boolean {
        val s = catStream(path) ?: return false
        return try {
            dst.parentFile?.mkdirs()
            dst.outputStream().use { out -> s.use { it.copyTo(out, 1 shl 16) } }
            dst.isFile && dst.length() > 0L
        } catch (_: Throwable) {
            runCatching { dst.delete() }
            false
        }
    }

    /** 删除目录树（仅限本 App 自有目录，如废弃 mirror 的退役清理）。 */
    fun rmTree(path: String): Boolean {
        val out = exec("rm -rf ${sh(path)} && echo $SENTINEL") ?: return false
        return out.contains(SENTINEL)
    }
}
