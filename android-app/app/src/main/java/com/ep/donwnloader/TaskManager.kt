package com.ep.donwnloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** 收件箱捕获结果：新增数 / 重复数 / 新增条目 id（数据类以便按需解构）。 */
data class CaptureResult(val added: Int, val dup: Int, val newIds: List<Long>)

/** 任务完成脉冲：有新素材入库时置值，驱动下载页「查看素材」跳转胶囊。 */
data class DonePulse(val taskId: Long, val doneFiles: Int)

/** 同一目标文件正被其他任务下载时抛出；上层捕获后只跳过、不删对方正在写的 dest。 */
class DestBusyException(msg: String) : Exception(msg)

/** 下载任务引擎：收件箱捕获 + 队列 + 路由回退 + 去重入库。 */
class TaskManager {
    val tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val inbox = MutableStateFlow<List<InboxItem>>(emptyList())
    val toast = MutableStateFlow<String?>(null)
    val donePulse = MutableStateFlow<DonePulse?>(null)

    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val cancelFlags = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    private val fails = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    fun start() {
        repeat(2) { Store.scope.launch(Dispatchers.IO) { worker() } }
        // 上次中断的任务标记失败
        Store.db.runningTaskIds().forEach {
            Store.db.updateTask(it, "status" to "error", "error" to "应用重启，任务中断",
                "finished_at" to nowIso())
        }
        refresh()
        refreshInbox()
        // 视频封面补齐：历史视频缺 thumb_path 的批量补生成（fd 抽帧，成功回写 DB）
        Store.scope.launch(Dispatchers.IO) { VideoThumbs.backfillMissing() }
        // 存量巡检：头像变体升级（零网络）→ 导入时间戳修正（零网络）→ hash 本地补算（零网络）→ 作者资料联网回填（限速幂等，失败下轮重试）
        Store.scope.launch(Dispatchers.IO) {
            runCatching { Store.db.upgradeAvatarVariants() }
            runCatching { if (fixImportedTimestamps()) Store.mediaVersion++ }
            runCatching { backfillHashes() }
            runCatching { backfillAuthorUids() }
        }
    }

    fun refresh() {
        tasks.value = Store.db.listTasks()
    }

    fun refreshInbox() {
        inbox.value = Store.db.listInbox()
    }

    // ---------- 收件箱：捕获 → 元数据 → 批量下载 ----------

    /** 捕获链接进收件箱（不直接下载）。返回 (新增数, 重复数, 新增条目 id)。 */
    fun capture(urls: List<String>): CaptureResult {
        var added = 0
        var dup = 0
        val newIds = mutableListOf<Long>()
        for (u in urls) {
            val url = u.trim()
            if (url.isEmpty()) continue
            val det = Extractors.detect(url) ?: continue
            val existing = Store.db.inboxUrlExists(url)
            if (existing) { dup++; continue }
            val id = Store.db.captureInbox(url, det.platform, det.kind)
            newIds.add(id)
            added++
            Store.scope.launch(Dispatchers.IO) { fetchMeta(id, url) }
        }
        refreshInbox()
        return CaptureResult(added, dup, newIds)
    }

    /** 系统分享入口：X / Instagram / Bluesky 分享过来的文本 → 提取链接 → 捕获（可自动下载）。 */
    fun handleSharePayload(texts: List<String>) {
        val links = texts.flatMap { ShareIn.extractLinks(it) }.distinct()
        if (links.isEmpty()) {
            // 没有可识别链接：退回旧行为，把文本填进下载页输入框由用户处理
            texts.firstOrNull { it.isNotBlank() }?.let { Store.pendingShare = it }
            return
        }
        val r = capture(links)
        val auto = Store.prefs.shareAutoDownload
        if (auto && r.newIds.isNotEmpty()) downloadInbox(r.newIds)
        val dupNote = if (r.dup > 0) "，${r.dup} 条重复跳过" else ""
        toast.value = when {
            r.added == 0 && r.dup > 0 -> "分享的链接已在收件箱" + if (auto) "" else "，可点「下载」"
            r.added > 0 && auto -> "已接收分享：${r.added} 条开始下载$dupNote"
            r.added > 0 -> "已捕获 ${r.added} 条到收件箱$dupNote"
            else -> null
        }
    }

    /** 尽力拉取作者/文案等元数据（失败保持 captured，不阻塞）。 */
    private fun fetchMeta(id: Long, url: String) {
        try {
            val route = Store.proxy.currentRoute() ?: Store.proxy.alternateRoute()
            val plan = Extractors.plan(url, Http(route), Store.prefs.maxBskyPosts, Store.prefs.igCookie)
            val first = plan.posts.firstOrNull()
            // 收件箱预览图：优先照片，视频取封面缩图（无缩图则留空显示占位）
            val m0 = first?.media?.firstOrNull { it.type == "photo" } ?: first?.media?.firstOrNull()
            val preview = m0?.let {
                if (it.type == "photo") it.thumbUrl.ifBlank { it.url } else it.thumbUrl
            } ?: ""
            Store.db.updateInboxMeta(
                id, plan.platform, plan.kind,
                first?.author?.handle ?: "", first?.author?.name ?: "",
                first?.author?.avatarUrl ?: "", first?.text ?: "",
                plan.posts.sumOf { it.media.size }, preview)
            refreshInbox()
        } catch (_: Exception) { /* 元数据失败不影响下载 */ }
    }

    /** 批量下载收件箱项。 */
    fun downloadInbox(ids: List<Long>) {
        var started = 0
        for (id in ids.distinct()) {
            val item = Store.db.inboxItem(id) ?: continue
            if (item.status == "downloading") continue
            val tid = Store.db.insertTask(item.url, item.platform, item.kind)
            Store.db.updateInboxStatus(id, "downloading", "", tid)
            cancelFlags[tid] = false
            queue.trySend(tid)
            started++
        }
        if (started > 0) ensureDownloadService(Store.appContext)
        refreshInbox()
        refresh()
    }

    fun removeInbox(id: Long) {
        Store.db.removeInbox(id)
        refreshInbox()
    }

    /** 旧接口保留：直接批量创建任务（重试等内部使用 inbox 无关路径）。 */
    fun enqueue(url: String, platform: String, kind: String): Long {
        val id = Store.db.insertTask(url, platform, kind)
        cancelFlags[id] = false
        queue.trySend(id)
        ensureDownloadService(Store.appContext)
        refresh()
        return id
    }

    // ---------- 任务 ----------

    fun cancel(id: Long) {
        val t = Store.db.getTask(id) ?: return
        when (t.status) {
            "queued" -> {
                Store.db.updateTask(id, "status" to "canceled", "finished_at" to nowIso())
                cancelFlags[id] = true
            }
            "running" -> cancelFlags[id] = true
        }
        refresh()
    }

    fun retry(id: Long) {
        val t = Store.db.getTask(id) ?: return
        if (t.status !in listOf("error", "done", "canceled")) return
        Store.db.updateTask(id, "status" to "queued", "error" to "", "message" to "",
            "total_files" to 0, "done_files" to 0, "skipped" to 0, "bytes" to 0,
            "finished_at" to "")
        cancelFlags[id] = false
        queue.trySend(id)
        ensureDownloadService(Store.appContext)
        refresh()
    }

    fun delete(id: Long) {
        cancel(id)
        Store.db.deleteTask(id)
        refresh()
    }

    private suspend fun worker() {
        for (id in queue) {
            val t = Store.db.getTask(id) ?: continue
            if (t.status != "queued") continue
            run(id, t.url)
        }
    }

    private fun canceled(id: Long) = cancelFlags[id] == true

    private suspend fun run(id: Long, url: String) {
        Store.db.updateTask(id, "status" to "running")
        fails[id] = 0
        refresh()
        try {
            val route = Store.proxy.currentRoute()
            val alt = Store.proxy.alternateRoute()
            // 提取（主路由失败自动换备用）
            val http = Http(route)
            var plan = withContext(Dispatchers.IO) {
                try {
                    Extractors.plan(url, http, Store.prefs.maxBskyPosts, Store.prefs.igCookie)
                } catch (e: Exception) {
                    if (alt == null) throw e
                    Extractors.plan(url, Http(alt), Store.prefs.maxBskyPosts, Store.prefs.igCookie)
                }
            }
            // T8.8 降级：原生提取失败 → 内嵌 yt-dlp（X 受限内容/主页、Instagram）
            if (plan.error != null) {
                Store.db.updateTask(id, "message" to "原生提取失败，改用内置 yt-dlp…")
                refresh()
                val fb = withContext(Dispatchers.IO) { YtDlp.fallbackPlan(url) }
                if (fb != null && fb.error == null && fb.posts.isNotEmpty()) {
                    plan = fb.copy(note = "内置 yt-dlp 提取")
                } else {
                    val detail = fb?.error?.let { "（yt-dlp: $it）" } ?: ""
                    throw Exception(plan.error + detail)
                }
            }
            if (plan.error != null) throw Exception(plan.error)
            val total = plan.posts.sumOf { it.media.size }
            Store.db.updateTask(id, "total_files" to total, "platform" to plan.platform,
                "kind" to plan.kind, "message" to plan.note)
            for (post in plan.posts) {
                if (canceled(id)) throw InterruptedException()
                downloadPost(id, post, route, alt)
            }
            val t = Store.db.getTask(id)!!
            val f = fails[id] ?: 0
            if (t.doneFiles > 0 || t.skipped > 0) {
                var msg = "完成：下载 ${t.doneFiles} 个文件"
                if (t.skipped > 0) msg += "，跳过重复 ${t.skipped}"
                if (f > 0) msg += "，失败 $f"
                if (t.message.isNotBlank()) msg += "（${t.message}）"
                Store.db.updateTask(id, "status" to if (f > 0) "partial" else "done",
                    "message" to msg, "finished_at" to nowIso())
                if (t.doneFiles > 0) toast.value = "任务完成：${t.doneFiles} 个文件"
            } else {
                Store.db.updateTask(id, "status" to "error",
                    "error" to (if (f > 0) "$f 个文件下载失败" else t.message.ifBlank { "没有下载到任何媒体" }),
                    "finished_at" to nowIso())
            }
        } catch (e: InterruptedException) {
            Store.db.updateTask(id, "status" to "canceled", "finished_at" to nowIso())
        } catch (e: Exception) {
            Store.db.updateTask(id, "status" to "error",
                "error" to (e.message ?: e.toString()), "finished_at" to nowIso())
        } finally {
            fails.remove(id)
            cancelFlags.remove(id)
            // 同步收件箱状态
            Store.db.inboxByTask(id)?.let { item ->
                val t2 = Store.db.getTask(id)
                if (t2 != null) {
                    val st = when (t2.status) {
                        "done", "partial" -> "downloaded"
                        "canceled" -> "captured"
                        else -> "failed"
                    }
                    Store.db.updateInboxStatus(item.id, st, t2.error.ifBlank { t2.message })
                }
            }
            refresh()
            refreshInbox()
            // 结果反馈：底部悬浮胶囊（授权时）/ 后台系统通知兜底；前台 toast 由状态流驱动
            Store.db.getTask(id)?.let {
                NotifyHub.onTaskFinished(Store.appContext, it)
                if (it.doneFiles > 0) pulseDone(it)
            }
        }
    }

    /** 任务有新素材入库 → 置「查看素材」脉冲；点击消费或 10s 后自动消失。 */
    private fun pulseDone(t: TaskItem) {
        donePulse.value = DonePulse(t.id, t.doneFiles)
        Store.scope.launch {
            kotlinx.coroutines.delay(10_000)
            if (donePulse.value?.taskId == t.id) donePulse.value = null
        }
    }

    /**
     * 下载入库主链路（2026-09-10 双重验证改造）：
     * ① DB 行在 + 文件在 + 未删      → 真已下载，skip；
     * ② DB 行在 + 文件在 + 回收站    → 自动恢复（重下意图优先），按既有排序规则归位；
     * ③ DB 行在 + 文件丢失          → 伪下载：重下并复用该行（更新 file_path/size/hash/下载时间）；
     * ④ 全新条目：下载后算内容 hash 再过一道闸——
     *    命中 active 他帖（IG p/reel 裂帖等）→ 丢弃新文件不重复入库；
     *    命中回收站条目 → 恢复该条目并用新文件顶替；
     *    未命中 → 正常入库。
     */
    private suspend fun downloadPost(id: Long, post: PostMeta, route: String?, alt: String?) {
        val authorId = Store.db.upsertAuthor(
            post.platform, post.author.handle, post.author.name,
            post.author.avatarUrl, post.author.profileUrl, post.author.uid)
        val prow = Store.db.upsertPost(
            post.platform, post.postId, post.postUrl, authorId, post.createdAt, post.text)
        var bytes = 0L
        for (m in post.media) {
            if (canceled(id)) throw InterruptedException()

            // ---- 既有行判定（DB + 文件双验证；外部共享路径走 root stat，不误判丢失） ----
            val hit = Store.db.mediaCheck(prow, m.index)
            if (hit != null) {
                val fileOk = hit.filePath.isNotBlank() &&
                    ContentAccess.existsRemote(hit.filePath)
                if (hit.deleted && fileOk) {
                    // 回收站已有 → 识别到即恢复，文件本体还在不必重下
                    Store.db.restoreMedia(hit.rowId)
                    Store.mediaVersion++  // 恢复入库 → 素材库即时刷新
                    bump(id, done = 1)
                    refresh()
                    continue
                }
                if (!hit.deleted && fileOk) {
                    bump(id, skipped = 1); continue
                }
                // DB 在文件丢（无论是否在回收站）→ 重下复用该行；
                // 旧路径是外部共享目录（属主已删）时必须落回本 App 自有目录，绝不写属主目录
                val destPath = if (hit.filePath.isBlank() || ContentAccess.isForeign(hit.filePath))
                    destFile(post, m).absolutePath else hit.filePath
                val dest = File(destPath)
                try {
                    bytes = downloadFile(id, m.url, dest, route, alt)
                } catch (e: Exception) {
                    if (e is DestBusyException) { bump(id, skipped = 1); continue }
                    fails[id] = (fails[id] ?: 0) + 1
                    dest.delete()
                    bump(id)
                    continue
                }
                val thumb = if (m.type == "video") makeVideoThumb(dest) else ""
                Store.db.updateMediaRow(hit.rowId, dest.absolutePath, bytes, thumb, fileHash(dest))
                if (hit.deleted) Store.db.restoreMedia(hit.rowId)
                bump(id, done = 1, bytes = bytes)
                Store.mediaVersion++
                refresh()
                continue
            }

            // ---- 全新条目 ----
            val dest = destFile(post, m)
            try {
                bytes = downloadFile(id, m.url, dest, route, alt)
            } catch (e: Exception) {
                if (e is DestBusyException) { bump(id, skipped = 1); continue }
                fails[id] = (fails[id] ?: 0) + 1
                dest.delete()
                bump(id)
                continue
            }
            val thumb = if (m.type == "video") makeVideoThumb(dest) else ""
            val hash = fileHash(dest)
            // ---- hash 闸门：同内容归并（IG p/reel 裂帖 / 同文件不同链接形态） ----
            val dup = Store.db.findByHash(hash)
            if (dup != null) {
                if (dup.third) {
                    // 命中回收站条目 → 恢复 + 新文件顶替（旧文件可能已损坏/丢失）
                    Store.db.restoreMedia(dup.first)
                    Store.db.updateMediaRow(dup.first, dest.absolutePath, bytes, thumb, hash)
                    Store.mediaVersion++
                    bump(id, done = 1, bytes = bytes)
                } else {
                    // active 重复（同帖防御 / 他帖裂帖）→ 不重复入库
                    dest.delete()
                    bump(id, skipped = 1)
                }
                refresh()
                continue
            }
            val ok = Store.db.addMedia(prow, m.index, m.type, dest.absolutePath, m.ext,
                bytes, m.width, m.height, m.url, thumb, hash)
            if (!ok) {
                dest.delete()
                bump(id, skipped = 1)
            } else {
                bump(id, done = 1, bytes = bytes)
                Store.mediaVersion++  // 新媒体入库 → 素材库即时刷新（无需切页重进）
            }
            refresh()
        }
    }

    /** 文件内容 MD5（流式，下载完成后算一次；失败返回空串=放弃查重不阻塞入库）。 */
    private fun fileHash(f: File): String = try {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }

    private fun bump(id: Long, done: Int = 0, skipped: Int = 0, bytes: Long = 0) {
        val t = Store.db.getTask(id) ?: return
        Store.db.updateTask(id,
            "done_files" to (t.doneFiles + done),
            "skipped" to (t.skipped + skipped),
            "bytes" to (t.bytes + bytes))
    }

    private fun destFile(post: PostMeta, m: MediaRef): File {
        val stamp = post.createdAt.removeSuffix("Z")
            .replace("-", "").replace("T", "_").replace(":", "").ifBlank { nowIso() }
            .replace(Regex("[^0-9_]"), "")
        val safeHandle = post.author.handle.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        val safePid = post.postId.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        val dir = File(Store.appContext.getExternalFilesDir(null), "downloads/${post.platform}/$safeHandle")
        dir.mkdirs()
        return File(dir, "${stamp}_${safePid}_${m.index}.${m.ext}")
    }

    /** 逐文件下载：每条路由试 2 次，主路由全失败再试备用；成功路由会被记住。 */
    private suspend fun downloadFile(
        id: Long, url: String, dest: File, route: String?, alt: String?,
    ): Long = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".t${id}.part")  // 带 taskId，并发同 URL 互不踩踏
        // 同一 dest 与 .part 同时未被占用才下载；否则本任务尚未落盘，抛互斥异常由上层只跳过不删文件
        if (!activePaths.add(dest.absolutePath) || !activePaths.add(tmp.absolutePath))
            throw DestBusyException("同一文件正被其他任务下载，已跳过")
        try {
            // 成功过的路由优先尝试（本次会话内沿用）
            val mem = routeMemory[id]
            val attempts = buildList {
                if (mem != null) add(mem)
                add(route)
                if (alt != null && alt != route) add(alt)
            }.distinct()
            var last: Exception? = null
            for (r in attempts) {
                for (tryI in 0 until 2) {
                    try {
                        val client = Net.client(r)
                        val req = Request.Builder().url(url).header("User-Agent", Http.UA).get().build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                            val src = resp.body?.byteStream() ?: throw Exception("空响应")
                            tmp.outputStream().use { out ->
                                src.copyTo(out, bufferSize = 1 shl 16)
                            }
                        }
                        val size = tmp.length()
                        if (size == 0L) throw Exception("空文件")
                        if (!tmp.renameTo(dest)) {
                            tmp.copyTo(dest, overwrite = true)
                            tmp.delete()
                        }
                        if (r != route) routeMemory[id] = r  // 记住成功路由
                        return@withContext size
                    } catch (e: Exception) {
                        last = e
                        tmp.delete()
                        if (tryI == 0) kotlinx.coroutines.delay(800)
                    }
                }
            }
            throw Exception("下载失败: ${last?.message ?: last}")
        } finally {
            activePaths.remove(dest.absolutePath)
            activePaths.remove(tmp.absolutePath)
        }
    }

    private val routeMemory = java.util.concurrent.ConcurrentHashMap<Long, String?>()

    /** 活跃下载的 dest 与 .part 绝对路径集合（.part 带 taskId 区分并发）；供残留清理排除。 */
    private val activePaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 暴露活跃下载路径（dest 与 .part），MediaFiles.residueSweep 在清理前排除以防误删。 */
    fun activeDownloadPaths(): Set<String> = activePaths

    /** 视频抽帧做缩略图；失败返回空串。复用 VideoThumbs 健壮抽帧。 */
    private fun makeVideoThumb(video: File): String =
        VideoThumbs.generate(video)?.absolutePath ?: ""

    // ---------- 存量数据巡检（启动静默跑，轻量用户的旧素材也能被新机制覆盖） ----------

    /** 本地补算素材 content_hash（零网络）：存量行入库早于 hash 机制，文件在本地理 MD5 即可回填。
     *  返回补算条数。 */
    fun backfillHashes(): Int {
        val rows = Store.db.mediaWithoutHash()
        if (rows.isEmpty()) return 0
        var done = 0
        for ((id, path) in rows) {
            val f = File(path)
            if (!f.exists() || f.length() == 0L) continue
            val h = fileHash(f)
            if (h.isNotBlank()) {
                Store.db.updateMediaHash(id, h)
                done++
            }
        }
        return done
    }

    /** 联网回填作者资料：X 走 fxtwitter user 端点（UID + 最新显示名 + 头像，共享导入作者的头像补齐）、
     *  BSky 走 resolveHandle、IG 需 Cookies（无则跳过）；local「导入素材」/unknown 伪作者直接排除。
     *  幂等：只处理 platform_uid 为空的行；单个失败跳过，下轮启动重试。返回 (成功, 总数)。 */
    suspend fun backfillAuthorUids(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val rows = Store.db.authorsWithoutUid()
        if (rows.isEmpty()) return@withContext 0 to 0
        val route = Store.proxy.currentRoute() ?: Store.proxy.alternateRoute()
        val http = Http(route)
        val igCookie = Store.prefs.igCookie
        var ok = 0
        for ((idx, row) in rows.withIndex()) {
            val (aid, platform, handle) = row
            // 伪作者无网可查（fxtwitter/BSky/IG 均必败），排除防无限重试白耗流量
            if (platform == "local" || handle.equals("unknown", true)) continue
            if (idx > 0) kotlinx.coroutines.delay(1200)  // 限速防平台限流
            try {
                when (platform) {
                    "twitter" -> {
                        // X：user 端点一并回填 UID + 最新显示名 + 头像（共享导入作者的头像补齐主路径）
                        val p = Extractors.twitterProfile(handle, http)
                        Store.db.updateAuthorProfile(aid, p.uid, p.name, p.avatarUrl)
                        ok++
                    }
                    "bluesky" -> {
                        Store.db.updateAuthorUid(aid, Extractors.bskyDid(handle, http))
                        ok++
                    }
                    "instagram" -> {
                        if (igCookie.isBlank()) continue
                        val uj = http.getJson(
                            "https://i.instagram.com/api/v1/users/web_profile_info/?username=$handle",
                            mapOf(
                                "User-Agent" to Extractors.igUserAgent(),
                                "X-IG-App-ID" to Extractors.igAppId(),
                                "Cookie" to igCookie))
                        val uid = uj.optJSONObject("data")?.optJSONObject("user")
                            ?.optString("id", "")?.ifBlank { null } ?: continue
                        Store.db.updateAuthorUid(aid, uid)
                        ok++
                    }
                    else -> continue
                }
            } catch (_: Exception) {
                // 单个失败不阻塞，下次启动重试
            }
        }
        ok to rows.size
    }

    // ---------- 本地目录扫描（把磁盘上已有但库里没有的文件重新登记） ----------

    fun rescanLibrary(): Int {
        val base = Store.appContext.getExternalFilesDir(null) ?: return 0
        val root = File(base, "downloads")
        if (!root.exists()) return 0
        var added = 0
        val pat = Regex("^(\\d{8})_(\\d{4})_(.+)_(\\d+)\\.(\\w+)$")
        for (pd in root.listFiles() ?: return 0) {
            val platform = pd.name
            for (hd in pd.listFiles() ?: continue) {
                if (!hd.isDirectory) continue
                val handle = hd.name
                for (f in hd.listFiles() ?: continue) {
                    if (f.isDirectory || f.name.startsWith(".")) continue
                    val m = pat.find(f.name) ?: continue
                    val (d8, t4, pid, idxS, ext) = m.destructured
                    val created = "%s-%s-%sT%s:%s:00Z".format(
                        d8.substring(0, 4), d8.substring(4, 6), d8.substring(6, 8),
                        t4.substring(0, 2), t4.substring(2, 4))
                    val authorId = Store.db.upsertAuthor(platform, handle, handle, "",
                        profileUrlFor(platform, handle))
                    val prow = Store.db.upsertPost(platform, pid,
                        postUrlFor(platform, handle, pid), authorId, created, "")
                    val ok = Store.db.addMedia(
                        prow, idxS.toIntOrNull() ?: 0,
                        if (ext.lowercase() in VIDEO_EXTS) "video" else "photo",
                        f.absolutePath, ext.lowercase(), f.length(), 0, 0, "", "")
                    if (ok) added++
                }
            }
        }
        if (added > 0) Store.mediaVersion++
        return added
    }

    // ---------- 外部目录同步（Edqiu 智能导入：三级识别 + 共享引用 + Root 镜像通道） ----------

    /** 同步预检统计：媒体总数 / 可识别 Edqiu 来源数 / 其余普通文件数 / 是否走了 Root 镜像。 */
    data class ExternalProbe(val total: Int, val edqiu: Int, val plain: Int, val rootMirror: Boolean = false)

    /** 同步结果：入库 / 内容重复跳过 / 归真实作者 / 识别推文但作者未知 / 普通导入 / Root 镜像。 */
    data class ExternalSyncReport(
        val registered: Int, val skippedDup: Int,
        val authorized: Int, val tweetOnly: Int, val plain: Int,
        val rootMirror: Boolean = false,
        /** 用户取消：已入库部分保留，可再次同步续传（幂等登记）。 */
        val cancelled: Boolean = false,
        /** 本次待登记候选总数（指纹+登记两阶段的进度分母）。 */
        val total: Int = 0,
    )

    /** Edqiu sidecar（.meta.json）关键字段。 */
    private class EdqiuMeta(
        val tweetId: String, val uploader: String, val authorName: String,
        val title: String, val mediaType: String,
    )

    /** 读 Edqiu sidecar：tweetId 无效且文件名也不匹配 → 视为无效元数据。 */
    private fun readEdqiuMeta(f: File): EdqiuMeta? {
        val text = f.resolveSibling(f.name + EDQIU_META_SUFFIX).takeIf { it.isFile }?.readText()
            ?: return null
        return try { parseEdqiuMeta(text, f.name) } catch (_: Exception) { null }
    }

    /**
     * 解析 sidecar JSON 文本（root 直读改造后 sidecar 内容可能来自 su cat，
     * 文本来源与文件系统解耦）。fileName 用于文件名兜底 tweetId。
     */
    private fun parseEdqiuMeta(text: String?, fileName: String): EdqiuMeta? = try {
        if (text.isNullOrBlank()) null
        else {
            val j = org.json.JSONObject(text)
            val tid = j.optString("tweetId", "")
            val tidOk = tid.matches(Regex("\\d{11,25}"))
            val nameId = EDQIU_NAME.matchEntire(fileName)?.groupValues?.get(2)
            if (!tidOk && nameId == null) null
            else EdqiuMeta(
                tweetId = if (tidOk) tid else nameId!!,
                uploader = j.optString("uploader", "").trim().removePrefix("@")
                    .takeIf { it.isNotBlank() && !it.equals("unknown", true) } ?: "",
                authorName = j.optString("authorName", "").trim().takeIf { it.isNotBlank() } ?: "",
                title = j.optString("title", ""),
                mediaType = j.optString("mediaType", "").lowercase(),
            )
        }
    } catch (_: Exception) { null }

    /** 推文 ID 雪花解码发帖时间（零网络）：id >> 22 + Twitter 纪元(2009-02)。越界/非法返回空串。 */
    private fun snowflakeIso(tweetId: String): String = try {
        val ms = (tweetId.toLong() ushr 22) + 1288834974657L
        val now = System.currentTimeMillis()
        if (ms < 1230000000000L || ms > now + 86_400_000L) ""
        else java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
    } catch (_: Exception) { "" }

    private fun mtimeIso(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    /**
     * 导入时间戳修正（启动巡检，零网络，幂等）：旧版导入把 downloaded_at 写成【导入时刻】，
     * 导致历史素材按导入时间全体霸占时间线顶部、挤压正常下载排序。
     * 修正语义：①edqiu 条目的 downloaded_at = 文件真实落盘时刻（mtime，即 Edqiu 当时的下载时间），
     * 只把"晚于真实下载时间"的条目往前修（ISO 字典序=时间序）；
     * ②其 twitter post 的 created_at 按推文 ID 雪花解码刷新（发帖时间绝对真理，不受 mtime 污染）。
     * 文件已丢失的条目保持原值。返回是否发生修正（驱动 mediaVersion 刷新）。
     */
    fun fixImportedTimestamps(): Boolean {
        var changed = false
        val touchedPosts = mutableSetOf<Long>()
        for (r in Store.db.edqiuImportedRows()) {
            touchedPosts.add(r.postRowId)
            val f = File(r.filePath)
            if (!f.isFile) continue
            val mtime = f.lastModified().takeIf { it > 0 } ?: continue
            val iso = mtimeIso(mtime)
            if (iso.isNotBlank() && iso < r.downloadedAt) {  // 只前修：导入时刻必然晚于真实下载
                Store.db.updateMediaDownloadedAt(r.id, iso)
                changed = true
            }
        }
        for (prowId in touchedPosts) {
            val p = Store.db.postById(prowId) ?: continue
            if (p.third != "twitter") continue
            val created = snowflakeIso(p.second)
            if (created.isNotBlank() && created != p.first) {
                Store.db.updatePostCreatedAt(prowId, created)
                changed = true
            }
        }
        return changed
    }

    /**
     * 外部目录同步（2026-09-11 root 直读改造）：扫描目录（含子目录）中的图片/视频，
     * 三级识别后【登记进素材库，文件保留原位】——外部目录归其属主 App（如 Edqiu）持续管理，
     * 本 App 只做只读引用：属主新下载的文件，下次同步自动增量入账。
     * 读通道：File API 可读走本地；不可读（Android/data 私有目录）+ 已 root 走 su 直读——
     * 目录枚举/size/mtime 单次命令直出、sidecar 文本 su cat、哈希 md5sum 远端直出，
     * 【零镜像零复制】，登记 file_path = 原路径。
     * - L1 sidecar：读 Edqiu .meta.json → tweetId 归并推文（posts 唯一键）、真实 handle/显示名/文案入库；
     * - L2 文件名兜底：{handle}_{tweetId}_{idx}_{kind}_{quality}.ext → 恢复作者与推文归属；
     * - L3 普通文件：挂 local「导入素材」，行为与旧版一致。
     * 时间语义：downloaded_at = 文件 mtime（Edqiu 真实下载时刻，时间线按原日期自然沉位，不因导入动作浮顶）；
     * posts.created_at = 推文 ID 雪花解码的发帖时间。
     * 去重：路径幂等（source_url）+ 内容 MD5（与下载链路同算法）；MD5 命中且库内未删 → 跳过。
     * sidecar 一并不动（Edqiu 收件箱 DownloadMonitor 靠它配对，删了会破坏属主生态）。
     * origin 标记：L1/L2 命中或路径含 com.ed.edqiu → 'edqiu'，素材库角标可见来源；
     * 共享素材被本 App 删除时只解除登记、不物理删文件（MediaFiles.purgeEntries 同规则）。
     * 可取消/进度（2026-09-11）：isCancelled 在「指纹分批」与「逐条登记」处轮询，取消即返回
     * 部分报告（已入库条目保留，refresh 保证 UI 一致，再同步幂等续传）；onProgress 按
     * phase="hash"（校验指纹）/ "register"（登记）上报 done/total 与已入库数。
     */
    fun syncExternalDir(
        dirPath: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (phase: String, done: Int, total: Int, registered: Int) -> Unit = { _, _, _, _ -> },
    ): ExternalSyncReport {
        val root = dirPath.trim()
        val dir = File(root)
        val useApi = dir.isDirectory     // File API 可读走本地；root 直读仅在不可读时启用
        val entries: List<RootIO.RootEntry>? = if (useApi) null else {
            if (!RootIO.available()) throw Exception(UNREACHABLE_DIR_MSG)
            RootIO.listTree(root) ?: throw Exception(UNREACHABLE_DIR_MSG)
        }
        var registered = 0; var skippedDup = 0
        var authorized = 0; var tweetOnly = 0; var plain = 0

        // 统一候选抽象：File 通道与 root 通道都产出 (path, size, mtime, sidecarPath)
        data class Cand(val path: String, val size: Long, val mtimeMs: Long, val sidecarPath: String?)
        val cands = mutableListOf<Cand>()
        if (useApi) {
            dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                if (f.extension.lowercase() !in MEDIA_EXTS) return@forEach
                val side = f.absolutePath + EDQIU_META_SUFFIX
                cands.add(Cand(f.absolutePath, f.length(), f.lastModified(),
                    if (File(side).isFile) side else null))
            }
        } else {
            val sidecars = entries!!.filter { it.path.endsWith(EDQIU_META_SUFFIX) }
                .mapTo(mutableSetOf()) { it.path.removeSuffix(EDQIU_META_SUFFIX) }
            entries.forEach { e ->
                val name = e.path.substringAfterLast('/')
                if (name.startsWith(".") || e.path.substringAfterLast('.', "").lowercase() !in MEDIA_EXTS) return@forEach
                cands.add(Cand(e.path, e.size, e.mtimeMs,
                    if (sidecars.contains(e.path)) e.path + EDQIU_META_SUFFIX else null))
            }
        }

        // 幂等：原路径已登记 → 跳过（root 直读登记原路径，二次同步天然幂等）
        val fresh = cands.filter { !Store.db.mediaPathRegistered(it.path) }
        val total = fresh.size
        var cancelled = false

        /** 取消检查点：置位后走 partial() 返回部分报告。 */
        fun abortIfCancelled(): Boolean {
            if (!isCancelled()) return false
            cancelled = true
            return true
        }

        /** 部分报告：已入库条目保留（refresh 让 UI 立即一致），cancelled=true 供 UI 区分文案。 */
        fun partial(): ExternalSyncReport {
            refresh()
            if (registered > 0) Store.mediaVersion++
            return ExternalSyncReport(registered, skippedDup, authorized, tweetOnly, plain,
                !useApi, cancelled = true, total = total)
        }

        // 内容级去重：批量哈希（File 通道本地算；root 通道远端 md5sum 直出，零内容传输）
        val hashOf = HashMap<String, String>()
        if (fresh.isNotEmpty()) {
            if (useApi) {
                fresh.forEachIndexed { i, c ->
                    if (abortIfCancelled()) return partial()
                    hashOf[c.path] = fileHash(File(c.path))
                    onProgress("hash", i + 1, total, 0)
                }
            } else {
                // root 通道：40 个/条分批（与 md5Batch 内部一致），批次间可取消/报进度
                fresh.map { it.path }.chunked(40).forEachIndexed { bi, chunk ->
                    if (abortIfCancelled()) return partial()
                    hashOf.putAll(RootIO.md5Batch(chunk))
                    onProgress("hash", minOf((bi + 1) * 40, total), total, 0)
                }
            }
        }

        for ((idx, c) in fresh.withIndex()) {
            if (abortIfCancelled()) return partial()
            val hash = hashOf[c.path] ?: ""
            if (hash.isNotBlank()) {
                val dup = Store.db.findByHash(hash)
                if (dup != null && !dup.third) { skippedDup++; continue }
            }
            val dlIso = mtimeIso(c.mtimeMs.takeIf { it > 0 } ?: System.currentTimeMillis())
            val ext = c.path.substringAfterLast('.', "").lowercase()
            val meta = if (c.sidecarPath != null) {
                val text = if (useApi) runCatching { File(c.sidecarPath).readText() }.getOrNull()
                           else RootIO.catText(c.sidecarPath)
                parseEdqiuMeta(text, c.path.substringAfterLast('/'))
            } else null
            val nameHit = EDQIU_NAME.matchEntire(c.path.substringAfterLast('/'))
            if (meta != null || nameHit != null) {
                // L1/L2：归并到真实推文 + 作者（handle 缺失走 twitter/unknown 占位）
                val tweetId = meta?.tweetId ?: nameHit!!.groupValues[2]
                val handle = meta?.uploader ?: ""
                val h2 = handle.ifBlank { nameHit?.groupValues?.get(1) ?: "" }
                val isKnownHandle = h2.isNotBlank()
                val authorId = Store.db.upsertAuthor("twitter",
                    if (isKnownHandle) h2 else "unknown",
                    meta?.authorName?.takeIf { it.isNotBlank() }
                        ?: if (isKnownHandle) h2 else "未识别作者",
                    "", if (isKnownHandle) "https://x.com/$h2" else "")
                val prow = Store.db.upsertPost("twitter", tweetId,
                    "https://x.com/i/status/$tweetId", authorId,
                    snowflakeIso(tweetId).ifBlank { dlIso }, meta?.title ?: "")
                val type = when (meta?.mediaType) {
                    "image" -> "photo"; "video" -> "video"
                    else -> if (ext in VIDEO_EXTS) "video" else "photo"
                }
                if (Store.db.addMedia(prow, -1, type, c.path, ext,
                        c.size, 0, 0, c.path, "", hash, "edqiu", dlIso)) {
                    registered++
                    if (isKnownHandle) authorized++ else tweetOnly++
                }
            } else {
                // L3：普通文件 → 导入素材（来源路径含 Edqiu 包名则打标）
                val authorId = Store.db.upsertAuthor("local", "导入素材", "导入素材", "", "")
                val base = c.path.substringAfterLast('/').substringBeforeLast('.')
                val pid = base.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                val prow = Store.db.upsertPost("local", pid, "", authorId, dlIso, c.path.substringAfterLast('/'))
                val origin = if (c.path.contains("com.ed.edqiu")) "edqiu" else ""
                if (Store.db.addMedia(prow, -1,
                        if (ext in VIDEO_EXTS) "video" else "photo",
                        c.path, ext, c.size, 0, 0, c.path, "", hash, origin, dlIso)) {
                    registered++; plain++
                }
            }
            onProgress("register", idx + 1, total, registered)
        }
        refresh()
        if (registered > 0) Store.mediaVersion++
        return ExternalSyncReport(registered, skippedDup, authorized, tweetOnly, plain,
            !useApi, cancelled = cancelled, total = total)
    }

    /**
     * 目录退订计数（移除前预览用）：该目录子树下已登记（含回收站）的外部共享条目数。
     */
    fun countExternalDir(dirPath: String): Int {
        val prefix = dirPath.trim().trimEnd('/')
        if (prefix.isBlank()) return 0
        val base = Store.appContext.getExternalFilesDir(null)?.absolutePath?.trimEnd('/') ?: ""
        return Store.db.externalDirMedia(prefix, base).size
    }

    /**
     * 目录退订（2026-09-11）：把某目录同步登记的素材整体解除登记（含回收站条目）。
     * 文件一律不动——共享引用模式，原件归属主 App（如 Edqiu）管理；缩略图在本 App
     * 私有目录内，顺带清理。posts/authors 留作孤儿（不进任何视图；再同步时 upsertPost
     * 复用同一推文行，归并不丢，与 purgeEntries 的既有行为一致）。
     * 目录边界安全：只匹配 prefix/' 子树，不会误伤前缀重叠的兄弟目录。
     * 返回解除登记条数。
     */
    fun removeExternalDir(dirPath: String): Int {
        val prefix = dirPath.trim().trimEnd('/')
        if (prefix.isBlank()) throw Exception("先填写要退订的目录路径")
        val base = Store.appContext.getExternalFilesDir(null)?.absolutePath?.trimEnd('/') ?: ""
        val rows = Store.db.externalDirMedia(prefix, base)
        rows.forEach { (_, t) -> if (t.isNotBlank()) MediaFiles.deleteDisk(t) }
        val n = Store.db.removeExternalDirRows(prefix, base)
        refresh()
        if (n > 0) Store.mediaVersion++
        android.util.Log.i("TaskManager", "removeExternalDir: prefix=$prefix 解除登记 $n 条（文件保留原位）")
        return n
    }

    /**
     * mirror 退役迁移（2026-09-11 root 直读改造）：旧版共享引用把镜像路径登记进库
     * （…/files/mirror/<key12>/…），统一迁回原路径（镜像前缀 → 同步目录前缀），
     * file_path 与 source_url 同时改指。幂等：无命中即跳过。
     * 迁移后对所有【DB 零引用】的 key 子目录做退役清理（含历史换目录留下的旧 key），
     * 正被占用的文件删失败不影响其余，下次启动自动重试。
     */
    fun migrateMirrorPaths(): Boolean {
        val ext = Store.appContext.getExternalFilesDir(null) ?: return false
        val mirrorRoot = File(ext, "mirror")
        if (!mirrorRoot.isDirectory) return false
        val syncDir = runCatching { Store.prefs.syncDir }.getOrNull()?.trim().orEmpty()
        var changed = false
        if (syncDir.isNotBlank()) {
            val key = RootIO.key12(syncDir)
            val mirrorPrefix = File(mirrorRoot, key).absolutePath + "/"
            val n = Store.db.repointMirrorPaths(mirrorPrefix, syncDir.trimEnd('/') + "/")
            if (n > 0) {
                android.util.Log.i("TaskManager", "migrateMirrorPaths: $n rows repointed → $syncDir")
                changed = true
            }
        }
        // 退役清理：任何 DB 零引用的 key 目录都删（mirror 通道已废，文件留在原路径）
        mirrorRoot.listFiles()?.forEach { kd ->
            if (kd.isDirectory && Store.db.countPathsUnder(kd.absolutePath + "/") == 0L) {
                val ok = runCatching { kd.deleteRecursively() }.getOrDefault(false)
                android.util.Log.i("TaskManager", "retire mirror ${kd.name}: ${if (ok) "deleted" else "retry next boot"}")
            }
        }
        return changed
    }

    /** 预检：验证目录可达并统计媒体数（File API 可读走本地；不可读 + 已 root 走 su 直读枚举）。 */
    fun probeExternalDir(dirPath: String): ExternalProbe {
        val root = dirPath.trim()
        val dir = File(root)
        val useApi = dir.isDirectory
        if (!useApi && !RootIO.available()) throw Exception(UNREACHABLE_DIR_MSG)
        var total = 0; var edqiu = 0
        if (useApi) {
            dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                if (f.extension.lowercase() !in MEDIA_EXTS) return@forEach
                total++
                if (File(f.absolutePath + EDQIU_META_SUFFIX).isFile ||
                    EDQIU_NAME.matches(f.name) || f.absolutePath.contains("com.ed.edqiu")) edqiu++
            }
        } else {
            val entries = RootIO.listTree(root) ?: throw Exception(UNREACHABLE_DIR_MSG)
            val sidecars = entries.filter { it.path.endsWith(EDQIU_META_SUFFIX) }
                .mapTo(mutableSetOf()) { it.path.removeSuffix(EDQIU_META_SUFFIX) }
            entries.forEach { e ->
                val name = e.path.substringAfterLast('/')
                if (name.startsWith(".") || e.path.substringAfterLast('.', "").lowercase() !in MEDIA_EXTS) return@forEach
                total++
                if (sidecars.contains(e.path) || EDQIU_NAME.matches(name) || e.path.contains("com.ed.edqiu")) edqiu++
            }
        }
        return ExternalProbe(total, edqiu, total - edqiu, !useApi)
    }

    private fun profileUrlFor(platform: String, handle: String) = when (platform) {        "twitter" -> "https://x.com/$handle"
        "instagram" -> "https://www.instagram.com/$handle/"
        "bluesky" -> "https://bsky.app/profile/$handle"
        else -> ""
    }

    private fun postUrlFor(platform: String, handle: String, pid: String) = when (platform) {
        "twitter" -> "https://x.com/$handle/status/$pid"
        "instagram" -> "https://www.instagram.com/p/$pid/"
        "bluesky" -> "https://bsky.app/profile/$handle/post/$pid"
        else -> ""
    }

    companion object {
        private val VIDEO_EXTS = setOf("mp4", "webm", "mov", "m4v", "mkv")
        private val IMG_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
        private val MEDIA_EXTS = VIDEO_EXTS + IMG_EXTS
        /** 目录不可达的统一提示（File API 与 Root 直读通道都不可用才会出现：
         *  Android 11+ 无法读其他应用 Android/data 私有目录，且本机 Root 通道不可用）；
         *  可将文件移到公共目录（如 /sdcard/EdqiuShare）后再同步。 */
        private const val UNREACHABLE_DIR_MSG =
            "目录不存在或无权访问（系统限制读取其他应用的 Android/data 目录，且本机 Root 通道不可用）；可将文件移到公共目录（如 /sdcard/EdqiuShare）后再同步"
        /** Edqiu sidecar 后缀（媒体文件旁的元数据 JSON）。 */
        private const val EDQIU_META_SUFFIX = ".meta.json"
        /** Edqiu 内部引擎落盘命名：{uploader}_{tweetId}_{idx}_{kind}_{quality}.{ext}。 */
        private val EDQIU_NAME = Regex(
            "^([A-Za-z0-9_]{1,30})_(\\d{11,25})_(\\d{1,2})_(?:video|image)_[A-Za-z0-9]{1,12}\\.[A-Za-z0-9]{1,5}$")
    }
}
