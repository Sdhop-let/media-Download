package com.ep.donwnloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- 素材库：日期手风琴统一视图 ----------------
// 2026-09-10 二稿（用户定型）：顶层按下载日期分组（今天/昨天/M月d日），点击组头展开该日的
// 3 列正方形预览框网格（复用 MediaCell）；分类胶囊（全部/X/IG/Bluesky/Edqiu + 图片/视频）
// 点击时内容区以淡入+轻微上滑过渡动画切换。胶囊命名 2026-09-10 与用户确认（Instagram 显示为 IG）。

/** 日期手风琴组：按下载日期聚合的媒体（key = LocalDate.toString，组内保持下载时间倒序）。 */
private data class DayGroup(val key: String, val label: String, val items: List<MediaItem>)

/** UTC ISO → 本地 LocalDate（兼容带 Z / 无时区两种格式，解析失败返回 null）。 */
private fun parseIsoLocalDate(iso: String): LocalDate? {
    if (iso.isBlank()) return null
    return runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    }.recoverCatching {
        LocalDateTime.parse(iso.trim().take(19)).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull()
}

/** 平铺媒体（已按 sortBase 倒序、同帖连续）→ 帖子归并 → 日期分组。
 *  sortBase="downloaded"（2026-09-10 用户定型）：帖子归属日期 = 组内 MAX(downloaded_at)，
 *  缺失用发帖时间兜底——整帖同组不拆散，老帖重下载时整帖浮到最新日期组；
 *  sortBase="posted"（2026-09-11 新增，修正"老帖挤新日期组"）：按发帖时间归组，
 *  时间线即推文时间线，2024 年的老帖沉回 2024，不再霸占最近日期。
 *  组间按日期倒序硬排序，杜绝"9月6日插到昨天前面"的乱序（旧版按出现顺序建组的 bug）。 */
private fun groupByDownloadDay(rows: List<MediaItem>, sortBase: String = "downloaded"): List<DayGroup> {
    val today = LocalDate.now()
    val postMap = LinkedHashMap<Long, MutableList<MediaItem>>()
    rows.forEach { m -> postMap.getOrPut(m.postRowId) { mutableListOf() }.add(m) }
    val dayMap = HashMap<String, MutableList<MediaItem>>()
    postMap.values.forEach { items ->
        val d: LocalDate = if (sortBase == "posted") {
            parseIsoLocalDate(items.first().postTime) ?: today
        } else {
            items.maxOfOrNull { m ->
                parseIsoLocalDate(m.downloadedAt) ?: parseIsoLocalDate(m.postTime) ?: today
            } ?: today
        }
        dayMap.getOrPut(d.toString()) { mutableListOf() }.addAll(items)
    }
    val f = DateTimeFormatter.ofPattern("M月d日")
    val fY = DateTimeFormatter.ofPattern("yyyy年M月d日")
    return dayMap.entries
        .sortedByDescending { it.key }   // ISO yyyy-MM-dd 字符序 = 日期倒序
        .map { (key, items) ->
            val d = LocalDate.parse(key)
            val label = when {
                d == today -> "今天"
                d == today.minusDays(1) -> "昨天"
                d.year == today.year -> d.format(f)
                else -> d.format(fY)
            }
            DayGroup(key, label, items)
        }
}

@Composable
fun LibraryScreen() {
    // 两级筛选：一级分类入口（来源/分组/类型，点击过渡展开二级选项）；二级为具体选项。
    var openSection by remember { mutableStateOf<String?>(null) }   // null|"origin"|"group"|"type"
    var group by remember { mutableStateOf("time") }                // 分组方式：time=按下载日期 / author=按作者
    var sortBase by remember { mutableStateOf("downloaded") }       // 时间视图排序基准：downloaded=下载时间 / posted=发帖时间
    var origin by remember { mutableStateOf("") }                   // ''=全部来源；'edqiu'=Edqiu 同步导入
    var platform by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var mediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var dayGroups by remember { mutableStateOf<List<DayGroup>>(emptyList()) }
    val expandedDays = remember { mutableStateListOf<String>() }
    // 作者分组状态
    var authors by remember { mutableStateOf<List<AuthorItem>>(emptyList()) }
    var expandedAuthorId by remember { mutableStateOf<Long?>(null) }
    var authorMedia by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var stats by remember { mutableStateOf(Stats(0, 0, 0, emptyMap())) }
    // 作者统计（2026-09-11 新增）：当前筛选下的作者排行数据 + 弹层开关 + 跨分组跳转待展开作者
    var authorStatRows by remember { mutableStateOf<List<AuthorStatRow>>(emptyList()) }
    var showAuthorStats by remember { mutableStateOf(false) }
    var pendingAuthorJump by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var feed by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }  // 竖滑视频流：列表 + 起始下标
    var previewing by remember { mutableStateOf<MediaItem?>(null) }
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var lastFilterKey by remember { mutableStateOf("") }
    // 长按批量删除：选中模式与已选集合
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    BackHandler(enabled = selectMode) {
        selectMode = false
        selectedIds.clear()
    }

    // mediaVersion：媒体入库/恢复/登记时自增（Store），素材库观察到变化立即重查，下载完成无需切页。
    // 筛选条件变化时重置手风琴（默认展开最新一组/收起作者）并置 loading 触发内容区过渡动画；
    // mediaVersion/reload 触发的重查静默刷新（不置 loading，避免下载完成时全屏闪动画）。
    LaunchedEffect(group, platform, type, origin, sortBase, reload, Store.mediaVersion) {
        val fk = "$group|$platform|$type|$origin|$sortBase"
        val filterChanged = fk != lastFilterKey
        lastFilterKey = fk
        if (filterChanged) loading = true
        withContext(Dispatchers.IO) {
            if (group == "time") {
                // 2000 条上限（root 直读后 Edqiu 225+ 全量在库；作者列表同理放大到全量）
                mediaItems = Store.db.listMediaTime(platform, type, "", null, 0, 2000,
                    origin = origin, sortBase = sortBase)
                dayGroups = groupByDownloadDay(mediaItems, sortBase)
            } else {
                // 旧版 limit=30 只显示前 30 位作者（用户 90+ 位作者只见 20 多的根因），放大为全量
                authors = Store.db.listAuthors(platform, "", 0, 1000, perAuthor = 0, origin = origin)
            }
            stats = Store.db.stats()
            // 作者统计排行（跟随平台/类型/来源筛选；与 stats 同步刷新，弹层打开即有数据）
            authorStatRows = Store.db.authorStats(platform, type, origin)
        }
        if (filterChanged) {
            expandedDays.clear()
            expandedAuthorId = null
            if (group == "time") dayGroups.firstOrNull()?.let { expandedDays.add(it.key) }
            // 作者统计弹层「查看素材」跨分组跳转：分组切换重置后恢复待展开的作者
            pendingAuthorJump?.let { expandedAuthorId = it }
            pendingAuthorJump = null
        }
        loading = false
    }
    // 作者分组 · 展开作者的全部作品：作者时间线——按发帖时间从新到旧分组（同发帖时间按 postRowId
    // 稳定序），同帖内保持图片序号顺序；展示为 3 列正方形网格
    LaunchedEffect(group, expandedAuthorId, platform, type, origin, reload, Store.mediaVersion) {
        val id = expandedAuthorId ?: return@LaunchedEffect
        if (group != "author") return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val rows = Store.db.listMediaTime(platform, type, "", id, 0, 2000, origin = origin)
            val groups = LinkedHashMap<Long, MutableList<MediaItem>>()
            rows.forEach { m ->
                groups.getOrPut(m.postRowId) { mutableListOf() }.add(m)
            }
            authorMedia = groups.values
                .sortedWith(compareByDescending<List<MediaItem>> { it.first().postTime }
                    .thenByDescending { it.first().postRowId })
                .flatMap { g -> g.sortedBy { it.mediaIndex } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("素材库", textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold, fontSize = 20.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp))
        // 统计玻璃块：平台项数 / 类型项数
        GlassCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatCell("X", stats.byPlatform["twitter"] ?: 0, Modifier.weight(1f))
                    StatCell("Instagram", stats.byPlatform["instagram"] ?: 0, Modifier.weight(1f))
                    StatCell("Bluesky", stats.byPlatform["bluesky"] ?: 0, Modifier.weight(1f))
                }
                Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp)
                    .background(ios().line.copy(alpha = 0.55f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatCell("图片", stats.byType["photo"] ?: 0, Modifier.weight(1f))
                    StatCell("视频", stats.byType["video"] ?: 0, Modifier.weight(1f))
                    // 作者格可点（2026-09-11）：弹出「作者统计」排行弹层（跟随当前筛选）
                    StatCell("作者", authorStatRows.size, Modifier.weight(1f),
                        onClick = { showAuthorStats = true })
                }
            }
        }
        // 两级筛选：排 1 = 一级分类入口（来源/分组/类型，命名 2026-09-10 由 AI 定），
        // 点击过渡动画展开排 2 的二级选项（同时只展开一个；选中后自动收起）。
        val originLabel = when {
            origin == "edqiu" -> "Edqiu"
            platform == "twitter" -> "X"
            platform == "instagram" -> "IG"
            platform == "bluesky" -> "Bluesky"
            else -> "全部"
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionChip("来源", originLabel, open = openSection == "origin") {
                openSection = if (openSection == "origin") null else "origin"
            }
            SectionChip("分组", if (group == "time") "时间" else "作者",
                open = openSection == "group") {
                openSection = if (openSection == "group") null else "group"
            }
            SectionChip("类型", when (type) {
                "photo" -> "图片"; "video" -> "视频"; else -> "全部"
            }, open = openSection == "type") {
                openSection = if (openSection == "type") null else "type"
            }
        }
        // 排 2 = 当前展开分类的二级选项（淡入+下滑展开；选中即生效并收起）
        AnimatedContent(
            targetState = openSection,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 3 }) togetherWith
                    fadeOut(tween(150))
            },
            label = "section", modifier = Modifier.animateContentSize(),
        ) { section ->
            when (section) {
                "origin" -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip("全部", platform == "" && origin == "") {
                        platform = ""; origin = ""; loading = true; openSection = null
                    }
                    FilterChip("X", platform == "twitter") {
                        platform = "twitter"; loading = true; openSection = null
                    }
                    FilterChip("IG", platform == "instagram") {
                        platform = "instagram"; loading = true; openSection = null
                    }
                    FilterChip("Bluesky", platform == "bluesky") {
                        platform = "bluesky"; loading = true; openSection = null
                    }
                    FilterChip("Edqiu", origin == "edqiu") {
                        origin = if (origin == "edqiu") "" else "edqiu"
                        loading = true; openSection = null
                    }
                }
                "group" -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip("时间", group == "time") {
                            group = "time"; loading = true; openSection = null
                        }
                        FilterChip("作者", group == "author") {
                            group = "author"; loading = true; openSection = null
                        }
                    }
                    // 时间视图排序基准（2026-09-11 用户拍板增加）：按下载=最近拿到的一批聚最新组；
                    // 按发帖=推文时间线，老帖沉回原发帖日期，不再挤占最近日期组
                    if (group == "time") {
                        Row(Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip("按下载时间", sortBase == "downloaded") {
                                sortBase = "downloaded"; loading = true
                            }
                            FilterChip("按发帖时间", sortBase == "posted") {
                                sortBase = "posted"; loading = true
                            }
                        }
                    }
                }
                "type" -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip("全部", type == "") { type = ""; loading = true; openSection = null }
                    FilterChip("图片", type == "photo") {
                        type = "photo"; loading = true; openSection = null
                    }
                    FilterChip("视频", type == "video") {
                        type = "video"; loading = true; openSection = null
                    }
                }
                else -> Box(Modifier.fillMaxWidth())
            }
        }
        // 批量删除操作栏（长按进入）
        if (selectMode) {
            GlassCard(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selectedIds.size} 项", fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        selectedIds.clear()
                        (if (group == "time") mediaItems else authorMedia).forEach { selectedIds.add(it.id) }
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("全选") }
                    TextButton(onClick = {
                        selectMode = false
                        selectedIds.clear()
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("取消") }
                    TextButton(onClick = {
                        val ids = selectedIds.toList()
                        Store.scope.launch(Dispatchers.IO) {
                            ids.forEach { Store.db.deleteMedia(it) }
                            reload++
                            Store.tasks.toast.value = "已删除 ${ids.size} 项（回收站可恢复）"
                        }
                        selectMode = false
                        selectedIds.clear()
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("删除", color = ErrColor)
                    }
                }
            }
        }

        // 内容：分类/筛选切换过渡动画（淡入 + 轻微上滑）包住 骨架/空态/两种分组手风琴
        AnimatedContent(
            targetState = "$group|$platform|$type|$origin",
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 22 }) togetherWith
                    fadeOut(tween(160))
            },
            label = "libFilter",
        ) { _ ->
            when {
                loading -> SkeletonList()
                group == "time" && dayGroups.isEmpty() ->
                    EmptyHint(platform.isNotBlank() || type.isNotBlank() || origin.isNotBlank())
                group == "author" && authors.isEmpty() ->
                    EmptyHint(platform.isNotBlank() || type.isNotBlank() || origin.isNotBlank())
                group == "time" -> AccordionList(
                    groups = dayGroups,
                    expandedDays = expandedDays,
                    selectMode = selectMode,
                    selectedIds = selectedIds,
                    onToggle = { key ->
                        if (key in expandedDays) expandedDays.remove(key) else expandedDays.add(key)
                    },
                    onClick = { m ->
                        if (selectMode) {
                            if (m.id in selectedIds) selectedIds.remove(m.id) else selectedIds.add(m.id)
                        } else selected = m
                    },
                    onLongClick = { m ->
                        if (!selectMode) { selectMode = true; selectedIds.clear() }
                        if (m.id in selectedIds) selectedIds.remove(m.id) else selectedIds.add(m.id)
                    },
                )
                else -> AuthorAccordion(
                    authors = authors,
                    expandedId = expandedAuthorId,
                    authorMedia = authorMedia,
                    selectMode = selectMode,
                    selectedIds = selectedIds,
                    onToggle = { id -> expandedAuthorId = if (expandedAuthorId == id) null else id },
                    onClick = { m ->
                        if (selectMode) {
                            if (m.id in selectedIds) selectedIds.remove(m.id) else selectedIds.add(m.id)
                        } else selected = m
                    },
                    onLongClick = { m ->
                        if (!selectMode) { selectMode = true; selectedIds.clear() }
                        if (m.id in selectedIds) selectedIds.remove(m.id) else selectedIds.add(m.id)
                    },
                )
            }
        }
    }

    // 作者统计弹层：副标题标注当前生效的筛选，行点击跳转到作者分组视图并展开该作者
    if (showAuthorStats) {
        val filterLabel = listOfNotNull(
            if (origin == "edqiu") "Edqiu" else when (platform) {
                "twitter" -> "X"; "instagram" -> "IG"; "bluesky" -> "Bluesky"; else -> null
            },
            when (type) { "photo" -> "图片"; "video" -> "视频"; else -> null },
        ).joinToString(" · ")
        AuthorStatsSheet(
            rows = authorStatRows,
            filterLabel = filterLabel,
            onDismiss = { showAuthorStats = false },
            onOpenAuthor = { id ->
                showAuthorStats = false
                if (group == "author") expandedAuthorId = id
                else { group = "author"; pendingAuthorJump = id }
            },
        )
    }

    selected?.let { m ->
        MediaSheet(m,
            onDismiss = { selected = null },
            onPlay = {
                // 竖滑视频流（2026-09-10 用户定型）：范围为当前上下文的全部视频——
                // 时间分组 = 当前筛选下的全部视频；作者分组 = 该作者全部视频。以点击视频为起点。
                val src = if (group == "time") mediaItems else authorMedia
                val videos = src.filter { it.mediaType == "video" }
                val idx = videos.indexOfFirst { it.id == m.id }
                selected = null
                if (idx >= 0) feed = videos to idx
            },
            onShare = { shareMedia(m) },
            onCopyLink = { copyLink(m) },
            onPreview = { previewing = m },
            onDeleted = {
                Store.scope.launch(Dispatchers.IO) {
                    Store.db.deleteMedia(m.id)
                    reload++
                }
                selected = null
            })
    }
    feed?.let { (videos, idx) ->
        VideoFeedDialog(items = videos, initialIndex = idx, onDismiss = { feed = null })
    }
    previewing?.let { m ->
        ImageViewerDialog(path = m.filePath, onDismiss = { previewing = null })
    }
}

/** 分享媒体文件（FileProvider 授权给任意 App）。外部共享路径先异步物化到本 App cache
 *  （root 通道 su cat 单文件），落盘后才拉起系统分享；自有路径直接可用零等待。 */
fun shareMedia(m: MediaItem) {
    val ctx = Store.appContext
    val local = File(m.filePath)
    if (local.isFile) {
        doShare(ctx, local, m)
        return
    }
    Store.tasks.toast.value = "正在准备文件…"
    Store.scope.launch(Dispatchers.IO) {
        val f = ContentAccess.local(m.filePath)
        if (f == null) {
            Store.tasks.toast.value = "文件不可用（Root 通道读取失败）：${local.name}"
        } else {
            kotlinx.coroutines.withContext(Dispatchers.Main) { doShare(ctx, f, m) }
        }
    }
}

private fun doShare(ctx: android.content.Context, f: File, m: MediaItem) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", f)
        val type = if (m.mediaType == "video") "video/*" else "image/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "@${m.handle} 的素材")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "分享素材").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Store.tasks.toast.value = "分享失败：${it.message}"
    }
}

/** 复制帖子链接到剪贴板。 */
fun copyLink(m: MediaItem) {
    val ctx = Store.appContext
    runCatching {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("post link", m.postUrl))
        Store.tasks.toast.value = "已复制帖子链接"
    }
}

/** 全屏图片预览（黑底 + 点击任意处关闭）。外部共享路径先按需物化（root 通道，秒级）。 */
@Composable
fun ImageViewerDialog(path: String, onDismiss: () -> Unit) {
    // 自有路径 local() 直接返回原 File；外部路径物化到 ro_share 后展示
    var resolved by remember(path) { mutableStateOf(File(path).takeIf { it.isFile }) }
    LaunchedEffect(path) {
        if (resolved == null) {
            val f = withContext(Dispatchers.IO) { ContentAccess.local(path) }
            if (f != null) resolved = f
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            val f = resolved
            if (f == null) {
                Text("读取中…", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(f).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    imageLoader = Store.imageLoader,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 骨架屏：日期手风琴加载占位（组头条 + 两张帖子卡），呼吸明暗脉冲与流光进度同节奏。 */
@Composable
private fun SkeletonList() {
    val c = LocalMonet.current
    val trans = rememberInfiniteTransition(label = "sk")
    val a by trans.animateFloat(
        initialValue = 0.30f, targetValue = 0.70f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "skA")
    LazyColumn(
        contentPadding = PaddingValues(12.dp, 2.dp, 12.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                .width(120.dp).height(18.dp).clip(RoundedCornerShape(8.dp))
                .background(c.fill.copy(alpha = a)))
        }
        items(2) {
            Box(Modifier.fillMaxWidth().height(136.dp).clip(RoundedCornerShape(24.dp))
                .background(c.fill.copy(alpha = a)))
        }
    }
}

/** 统计块单元格：数字 + 标签居中；onClick 非空时可点（标签带 ▸ 提示，如「作者统计」入口格）。 */
@Composable
private fun StatCell(label: String, count: Int, modifier: Modifier = Modifier,
                     onClick: (() -> Unit)? = null) {
    val c = ios()
    Column(modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text)
        Text(if (onClick != null) "$label ▸" else label,
            fontSize = 10.5.sp, color = c.faint)
    }
}

/** 等宽筛选胶囊（与下载页同款）。 */
@Composable
private fun RowScope.FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = ios()
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(100.dp))
            .then(
                if (selected) Modifier.background(c.accent)
                else Modifier.background(
                    if (c.isDark) Color(0x14FFFFFF) else Color(0xB8FFFFFF))
                    .border(1.dp, c.rim, RoundedCornerShape(100.dp)))
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp,
            color = if (selected) Color.White else c.text2,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** 日期手风琴组头：日期标签 + 项数 + 旋转箭头（点击展开/收起该日下载的帖子）。 */
@Composable
private fun DayHeader(label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val c = ios()
    val angle by animateFloatAsState(if (expanded) 90f else 0f, label = "arrow")
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
        Spacer(Modifier.width(6.dp))
        Text("· $count 项", fontSize = 11.sp, color = c.faint)
        Spacer(Modifier.weight(1f))
        Text("▸", fontSize = 13.sp, color = c.faint,
            modifier = Modifier.padding(horizontal = 6.dp).rotate(angle))
    }
}

/** 一级分类入口胶囊：「来源 · X ▸」形式——维度名 + 当前生效值 + 旋转箭头；展开时主色高亮。 */
@Composable
private fun RowScope.SectionChip(label: String, value: String, open: Boolean, onClick: () -> Unit) {
    val c = ios()
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "secArrow")
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(100.dp))
            .then(
                if (open) Modifier.background(c.accent)
                else Modifier.background(
                    if (c.isDark) Color(0x14FFFFFF) else Color(0xB8FFFFFF))
                    .border(1.dp, c.rim, RoundedCornerShape(100.dp)))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label · $value", fontSize = 11.sp,
                color = if (open) Color.White else c.text2,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("▸", fontSize = 9.sp,
                color = if (open) Color.White else c.faint,
                modifier = Modifier.padding(start = 3.dp).rotate(angle))
        }
    }
}

/** 作者手风琴（分组=作者）：作者卡（头像+显示名+@handle·项数+旋转箭头）点击展开/收起；
 *  展开区为该作者作品的 3 列正方形网格（发帖时间倒序、同帖连续、组内序号正序）。 */
@Composable
private fun AuthorAccordion(
    authors: List<AuthorItem>,
    expandedId: Long?,
    authorMedia: List<MediaItem>,
    selectMode: Boolean,
    selectedIds: List<Long>,
    onToggle: (Long) -> Unit,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 2.dp, 12.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(authors, key = { it.id }) { a ->
            val expanded = expandedId == a.id
            GlassCard(Modifier.animateItem()) {
                Column {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onToggle(a.id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(38.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)) {
                            if (a.avatarUrl.isNotBlank()) AsyncImage(
                                model = a.avatarUrl, contentDescription = null,
                                contentScale = ContentScale.Crop,
                                imageLoader = Store.imageLoader,
                                modifier = Modifier.fillMaxSize())
                            else Text(a.handle.take(1).uppercase().ifBlank { "?" },
                                modifier = Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name.ifBlank { a.handle },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("@${a.handle} · ${a.mediaCount} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = ios().faint,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("▸", fontSize = 13.sp, color = ios().faint,
                            modifier = Modifier.padding(horizontal = 6.dp)
                                .rotate(if (expanded) 90f else 0f))
                    }
                    if (expanded) {
                        if (authorMedia.isEmpty()) {
                            Text("该作者暂无此类素材",
                                style = MaterialTheme.typography.bodySmall,
                                color = ios().faint,
                                modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
                        } else {
                            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                                authorMedia.chunked(3).forEach { row ->
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        row.forEach { m ->
                                            Box(Modifier.weight(1f)) {
                                                MediaCell(m,
                                                    selected = selectMode && m.id in selectedIds,
                                                    onClick = { onClick(m) },
                                                    onLongClick = { onLongClick(m) })
                                            }
                                        }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 日期手风琴列表：组头（日期+项数+旋转箭头）点击展开/收起；组内为 3 列正方形预览框网格
 *  （复用 MediaCell：视频 ▶ / Edqiu E 角标 / 批量选中天然兼容），行级出入场动画。 */
@Composable
private fun AccordionList(
    groups: List<DayGroup>,
    expandedDays: List<String>,
    selectMode: Boolean,
    selectedIds: List<Long>,
    onToggle: (String) -> Unit,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 2.dp, 12.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { g ->
            val expanded = g.key in expandedDays
            item(key = "h_" + g.key) {
                DayHeader(g.label, g.items.size, expanded) { onToggle(g.key) }
            }
            if (expanded) {
                val rows = g.items.chunked(3)
                items(rows.size, key = { ri -> "r_${g.key}_${rows[ri].first().id}" }) { ri ->
                    val row = rows[ri]
                    Row(Modifier.fillMaxWidth().animateItem(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { m ->
                            Box(Modifier.weight(1f)) {
                                MediaCell(m,
                                    selected = selectMode && m.id in selectedIds,
                                    onClick = { onClick(m) },
                                    onLongClick = { onLongClick(m) })
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(filtered: Boolean) {
    if (filtered) {
        EmptyState(icon = "🔍", title = "没有符合条件的素材",
            hint = "试试切换平台或类型筛选")
    } else {
        EmptyState(icon = "🖼️", title = "素材库还空着",
            hint = "下载的图片和视频会自动归档到这里",
            actionText = "去下载页", onAction = { Store.selectedTab = 0 })
    }
}

/** 解析媒体封面：图片直接取原图；视频取缩略图，缺失/失效时在后台补抽帧并回写数据库。
 *  外部共享路径（root 直读登记的原路径）：图片走 su 流降采样缩略图（.thumbs_ext 持久位），
 *  网格不物化原图；视频由 VideoThumbs.ensure 按需单文件物化后抽帧。 */
@Composable
private fun rememberVideoCover(m: MediaItem): File? {
    if (m.mediaType != "video") {
        if (File(m.filePath).isFile) {
            return remember(m.filePath) { File(m.filePath) }
        }
        if (!ContentAccess.isForeign(m.filePath)) return null
        // 外部图片：先查持久缩略图，没有则 su 流生成（IO 线程）
        val cached = remember(m.filePath) { ContentAccess.thumbOutFile(m.filePath)?.takeIf { it.isFile && it.length() > 0L } }
        val state = remember(m.filePath) { mutableStateOf(cached) }
        LaunchedEffect(m.filePath) {
            if (state.value == null) {
                val t = withContext(Dispatchers.IO) { ContentAccess.imageThumb(m.filePath) }
                if (t != null) state.value = t
            }
        }
        return state.value
    }
    val state = remember(m.id, m.filePath, m.thumbPath) {
        mutableStateOf(VideoThumbs.resolve(m))
    }
    LaunchedEffect(m.id, m.filePath, m.thumbPath) {
        if (state.value == null) {
            val t = withContext(Dispatchers.IO) { VideoThumbs.ensure(m) }
            if (t != null) state.value = t
        }
    }
    return state.value
}

/** 媒体格子：封面（图片用原图，视频用抽帧缩略图）+ 视频角标 + 玻璃描边；支持长按批量选择。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCell(m: MediaItem, selected: Boolean = false, onClick: () -> Unit,
              onLongClick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val c = ios()
    val cover = rememberVideoCover(m)
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, c.rim, RoundedCornerShape(14.dp))
            .then(
                if (onLongClick != null)
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier.clickable(onClick = onClick)),
    ) {
        if (cover != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(cover).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                imageLoader = Store.imageLoader,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(if (m.mediaType == "video") "🎬" else "🖼",
                modifier = Modifier.align(Alignment.Center), fontSize = 30.sp)
        }
        if (m.mediaType == "video") {
            Text("▶",
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp))
        }
        // 来源角标：Edqiu 同步导入的素材（时间/作者视图共用此 Cell）
        if (m.origin == "edqiu") {
            Text("E",
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)
                    .background(Color(0x88000000), RoundedCornerShape(5.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp))
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))
            Box(Modifier.size(26.dp).clip(CircleShape).background(c.accent)
                .align(Alignment.Center), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 素材详情：玻璃底部弹层（封面 + 信息 + 操作）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaSheet(
    m: MediaItem,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onPreview: () -> Unit,
    onDeleted: () -> Unit,
) {
    val c = LocalMonet.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (c.isDark) Color(0xF20E1830) else Color(0xF7F4F8FE),
        contentColor = c.text,
        scrimColor = Color(0x66000000),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp)
                .background(if (c.isDark) Color(0xFF3A4A6E) else Color(0xFFC3CDE2), RoundedCornerShape(2.dp)))
        },
    ) {
        // 竖版媒体封面曾把弹层撑到超屏（fillMaxWidth+Fit 无高度上限），底部按钮排被推出屏幕
        // 无法点击——封面限高 + 整体可滚动双保险（2026-09-10 修）
        Column(Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 34.dp)) {
            val cover = rememberVideoCover(m)
            if (cover != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(cover).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    imageLoader = Store.imageLoader,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .then(
                            if (m.mediaType == "photo")
                                Modifier.clickable { onPreview() }
                            else Modifier),
                )
                if (m.mediaType == "photo") {
                    Text("点按图片可全屏预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(14.dp))
            }
            Text("@${m.handle}", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text("${platName(m.platform)} · ${if (m.mediaType == "video") "视频" else "图片"} · ${fmtIso(m.postTime)}",
                style = MaterialTheme.typography.labelSmall, color = c.faint)
            if (m.postText.isNotBlank()) {
                Text(m.postText, style = MaterialTheme.typography.bodySmall, color = c.text2,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Text("文件：${m.ext} · ${fmtBytes(m.fileSize)}",
                style = MaterialTheme.typography.labelSmall, color = c.faint,
                modifier = Modifier.padding(top = 4.dp))
            if (m.origin == "edqiu") {
                Text("来源：Edqiu 导入",
                    style = MaterialTheme.typography.labelSmall, color = c.faint,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.mediaType == "video") {
                    GlassButton("▶ 播放", onClick = onPlay, modifier = Modifier.weight(1f))
                }
                GlassButton("⤴ 分享", onClick = onShare, modifier = Modifier.weight(1f))
                GlassButton("↗ 原帖", onClick = { Store.openUrl(m.postUrl) },
                    kind = ButtonKind.Ghost, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("⧉ 复制链接", onClick = onCopyLink,
                    kind = ButtonKind.Ghost, modifier = Modifier.weight(1f))
                GlassButton("@ 作者", onClick = { Store.openUrl(m.profileUrl) },
                    kind = ButtonKind.Ghost, modifier = Modifier.weight(1f))
                GlassButton("删除", onClick = onDeleted,
                    kind = ButtonKind.Ghost, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 作者统计弹层（2026-09-11 新增）：当前筛选下按素材数排行的作者榜。
 *  汇总行 = 作者数/素材总数/总容量（+当前筛选）；每行 = 头像 + 名字 + 图/视拆分 +
 *  素材数/容量 + 主色占比条（以榜首为基准），点行跳到作者分组视图并展开该作者。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorStatsSheet(
    rows: List<AuthorStatRow>,
    filterLabel: String,
    onDismiss: () -> Unit,
    onOpenAuthor: (Long) -> Unit,
) {
    val c = LocalMonet.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val totalItems = rows.sumOf { it.mediaCount }
    val totalBytes = rows.sumOf { it.bytes }
    val maxCnt = rows.maxOfOrNull { it.mediaCount } ?: 0
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (c.isDark) Color(0xF20E1830) else Color(0xF7F4F8FE),
        contentColor = c.text,
        scrimColor = Color(0x66000000),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp)
                .background(if (c.isDark) Color(0xFF3A4A6E) else Color(0xFFC3CDE2), RoundedCornerShape(2.dp)))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("作者统计", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = c.text)
            Text(
                "${rows.size} 位作者 · $totalItems 项素材 · ${fmtBytes(totalBytes)}" +
                    if (filterLabel.isNotBlank()) " · $filterLabel" else "",
                style = MaterialTheme.typography.labelSmall, color = c.faint,
                modifier = Modifier.padding(top = 2.dp))
            if (rows.isEmpty()) {
                Text("当前筛选下还没有作者素材",
                    style = MaterialTheme.typography.bodySmall, color = c.faint,
                    modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(rows, key = { it.id }) { r ->
                        AuthorStatRowItem(r, maxCnt, onClick = { onOpenAuthor(r.id) })
                    }
                }
            }
        }
    }
}

/** 作者统计排行行：头像 + 名字/@handle·图视拆分 + 素材数/容量，下方主色占比条（榜首=满格）。 */
@Composable
private fun AuthorStatRowItem(r: AuthorStatRow, maxCount: Int, onClick: () -> Unit) {
    val c = LocalMonet.current
    Column(Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 2.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (r.avatarUrl.isNotBlank()) AsyncImage(
                    model = r.avatarUrl, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    imageLoader = Store.imageLoader,
                    modifier = Modifier.fillMaxSize())
                else Text(r.handle.take(1).uppercase().ifBlank { "?" },
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name.ifBlank { r.handle },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                val split = buildString {
                    append("@").append(r.handle)
                    if (r.photoCount > 0) append(" · 图 ").append(r.photoCount)
                    if (r.videoCount > 0) append(" · 视 ").append(r.videoCount)
                    if (r.postCount > 0) append(" · ").append(r.postCount).append(" 帖")
                }
                Text(split, style = MaterialTheme.typography.labelSmall,
                    color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${r.mediaCount} 项", fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold, color = c.text)
                Text(fmtBytes(r.bytes), fontSize = 10.sp, color = c.faint)
            }
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(c.line.copy(alpha = 0.45f))) {
            val frac = if (maxCount <= 0) 0f else
                (r.mediaCount.toFloat() / maxCount).coerceIn(0.04f, 1f)
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight()
                .background(c.accent.copy(alpha = 0.75f)))
        }
    }
}
