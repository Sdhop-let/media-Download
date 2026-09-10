package com.ep.donwnloader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun statusText(s: String) = when (s) {
    "queued" -> "排队中"; "running" -> "下载中"; "done" -> "已完成"
    "canceled" -> "已取消"; "partial" -> "部分完成"; else -> "失败"
}

private fun statusColor(s: String) = when (s) {
    "done" -> OkColor; "running" -> BsColor; "queued" -> WarnColor
    "canceled" -> WarnColor; "partial" -> WarnColor; else -> ErrColor
}

private fun inboxStatusText(s: String) = when (s) {
    "captured" -> "待下载"; "downloading" -> "下载中"; "downloaded" -> "已完成"; else -> "失败"
}

private fun inboxStatusColor(s: String) = when (s) {
    "downloaded" -> OkColor; "downloading" -> BsColor
    "captured" -> WarnColor; else -> ErrColor
}

@Composable
private fun platColor(p: String) = when (p) {
    "twitter" -> ios().text; "instagram" -> IgColor; "bluesky" -> BsColor; else -> ios().faint
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen() {
    val ctx = LocalContext.current
    val tasks by Store.tasks.tasks.collectAsState()
    val inbox by Store.tasks.inbox.collectAsState()
    val scope = rememberCoroutineScope()
    var input by Store.draftInput
    var pasteOpen by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    BackHandler(enabled = showTasks) { showTasks = false }
    LaunchedEffect(Store.pendingShare) {
        Store.pendingShare?.let { input = it; Store.pendingShare = null; pasteOpen = true }
    }
    LaunchedEffect(Unit) {
        Store.scope.launch(Dispatchers.IO) { Store.tasks.refresh(); Store.tasks.refreshInbox() }
    }

    val active = tasks.filter { it.status == "queued" || it.status == "running" }
    val history = tasks.filter { it.status != "queued" && it.status != "running" }
    var inboxFilter by remember { mutableStateOf("") }
    val filteredInbox = if (inboxFilter.isBlank()) inbox else inbox.filter { it.status == inboxFilter }
    val pendingCount = inbox.count { it.status == "captured" || it.status == "failed" }
    val cntCaptured = inbox.count { it.status == "captured" }
    val cntDownloading = inbox.count { it.status == "downloading" }
    val cntDone = inbox.count { it.status == "downloaded" }
    val cntFailed = inbox.count { it.status == "failed" }

    fun captureText(text: String, collapse: Boolean) {
        val urls = ShareIn.extractLinks(text)
        if (urls.isEmpty()) {
            Store.tasks.toast.value = "未识别到支持的链接（X / Instagram / Bluesky）"
            return
        }
        input = ""
        if (collapse) pasteOpen = false
        scope.launch(Dispatchers.IO) {
            val r = Store.tasks.capture(urls)
            Store.tasks.toast.value =
                "已捕获 ${r.added} 条到收件箱" + if (r.dup > 0) "，${r.dup} 条重复跳过" else ""
        }
    }

    val donePulse by Store.tasks.donePulse.collectAsState()

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 124.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 顶部：左「捕获」/ 中标题 / 右「下载」对称布局
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassButton("＋ 捕获", onClick = {
                    when {
                        !pasteOpen -> {
                            pasteOpen = true
                            // 展开粘贴区的同时，自动捕获剪贴板里的链接
                            scope.launch(Dispatchers.IO) {
                                val clip = runCatching {
                                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                                        .primaryClip?.getItemAt(0)?.text?.toString()
                                }.getOrNull().orEmpty()
                                val urls = ShareIn.extractLinks(clip)
                                if (urls.isNotEmpty()) {
                                    val r = Store.tasks.capture(urls)
                                    Store.tasks.toast.value = "已从剪贴板捕获 ${r.added} 条" +
                                        if (r.dup > 0) "，${r.dup} 条重复跳过" else ""
                                }
                            }
                        }
                        input.isNotBlank() -> captureText(input, collapse = true)
                        else -> pasteOpen = false
                    }
                }, modifier = Modifier.width(88.dp))
                Text("收件箱", textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.weight(1f))
                GlassButton(
                    when {
                        selectMode -> "下载 ${selectedIds.size}"
                        pendingCount > 0 -> "下载 $pendingCount"
                        else -> "下载"
                    },
                    enabled = if (selectMode) selectedIds.isNotEmpty() else pendingCount > 0,
                    onClick = {
                        if (selectMode) {
                            if (selectedIds.isNotEmpty()) {
                                val ids = selectedIds.toList()
                                scope.launch(Dispatchers.IO) { Store.tasks.downloadInbox(ids) }
                                selectMode = false
                                selectedIds.clear()
                            }
                        } else if (pendingCount > 0) showMenu = true
                    },
                    modifier = Modifier.width(88.dp))
            }
        }
        // 可折叠粘贴区（默认隐藏）
        item {
            AnimatedVisibility(
                visible = pasteOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                GlassCard {
                    Column(Modifier.padding(12.dp)) {
                        GlassTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                            placeholder = "粘贴 X / Bluesky / Instagram 链接，支持多行批量…",
                            minLines = 3,
                        )
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { input = "" },
                                contentPadding = PaddingValues(horizontal = 6.dp)) { Text("清空") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { pasteOpen = false },
                                contentPadding = PaddingValues(horizontal = 6.dp)) { Text("收起") }
                        }
                    }
                }
            }
        }
        // 任务与历史：满长胶囊 tab，点击进入/收起任务页（primary 低透明底，随莫奈/种子色取色）
        item {
            val c = ios()
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(100.dp))
                    .background(c.primary.copy(alpha = if (c.isDark) 0.26f else 0.12f))
                    .border(1.dp, c.primary.copy(alpha = if (c.isDark) 0.35f else 0.18f),
                        RoundedCornerShape(100.dp))
                    .clickable { showTasks = !showTasks }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("任务与历史", fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold, color = c.text)
                    Spacer(Modifier.weight(1f))
                    Text("进行中 ${active.size} · 历史 ${history.size}",
                        fontSize = 11.sp, color = c.faint,
                        modifier = Modifier.padding(end = 8.dp))
                    Text(if (showTasks) "▾" else "▸", fontSize = 13.sp, color = c.text2)
                }
            }
        }

        if (showTasks) {
            // 任务子页：进行中 + 历史任务
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp)) {
                    Text("进行中", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${active.size} 个进行中", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (active.isEmpty()) {
                item { EmptyState(icon = "⏳", title = "队列空闲中",
                    hint = "添加任务后会在这里实时显示进度", compact = true) }
            }
            items(active, key = { "t" + it.id }) { t -> TaskCard(t) }

            item { Text("历史任务", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
            if (history.isEmpty()) {
                item { EmptyState(icon = "🗂️", title = "还没有历史记录",
                    hint = "完成的任务会在这里留下痕迹", compact = true) }
            }
            items(history, key = { "h" + it.id }) { t -> TaskRow(t) }
        } else {
            // 收件箱：状态筛选五个胶囊一行等宽
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip("全部 ${inbox.size}", inboxFilter.isBlank()) { inboxFilter = "" }
                    FilterChip("待下载 $cntCaptured", inboxFilter == "captured") { inboxFilter = "captured" }
                    FilterChip("下载中 $cntDownloading", inboxFilter == "downloading") { inboxFilter = "downloading" }
                    FilterChip("已完成 $cntDone", inboxFilter == "downloaded") { inboxFilter = "downloaded" }
                    FilterChip("失败 $cntFailed", inboxFilter == "failed") { inboxFilter = "failed" }
                }
            }
            // 批量选择模式操作栏
            if (selectMode) {
                item {
                    GlassCard {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("已选 ${selectedIds.size} 条", fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                selectedIds.clear()
                                filteredInbox.filter {
                                    it.status == "captured" || it.status == "failed"
                                }.forEach { selectedIds.add(it.id) }
                            }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("全选") }
                            TextButton(onClick = {
                                selectMode = false
                                selectedIds.clear()
                            }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("取消") }
                        }
                    }
                }
            }
            if (filteredInbox.isEmpty()) {
                item {
                    if (inbox.isEmpty()) {
                        EmptyState(icon = "📥", title = "收件箱空空如也",
                            hint = "粘贴一条 X / Instagram / Bluesky 链接，\n或从其他 App 分享过来，会自动收进这里。",
                            actionText = "粘贴链接", onAction = { pasteOpen = true })
                    } else {
                        EmptyState(icon = "🔍", title = "该状态下暂无条目",
                            hint = "换个状态筛选看看")
                    }
                }
            }
            items(filteredInbox, key = { it.id }) { item ->
                InboxCard(item,
                    selectMode = selectMode,
                    selected = item.id in selectedIds,
                    onToggle = {
                        if (item.id in selectedIds) selectedIds.remove(item.id)
                        else selectedIds.add(item.id)
                    })
            }
        }
    }
    // 任务完成跳转胶囊：有新素材入库时浮在 tab 栏上方，点击直达素材库顶部
    AnimatedVisibility(
            visible = donePulse != null,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp),
        ) {
            val pulse = donePulse
            if (pulse != null) {
                val c = ios()
                Row(
                    Modifier.clip(RoundedCornerShape(100.dp))
                        .background(c.primary.copy(alpha = if (c.isDark) 0.30f else 0.16f))
                        .border(1.dp, c.primary.copy(alpha = if (c.isDark) 0.45f else 0.28f),
                            RoundedCornerShape(100.dp))
                        .clickable {
                            Store.tasks.donePulse.value = null
                            Store.selectedTab = 1  // 素材库页随 when(tab) 重建，天然回到顶部新内容
                        }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✓ 已下载 ${pulse.doneFiles} 个新素材", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = c.text)
                    Spacer(Modifier.width(10.dp))
                    Text("查看素材 →", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.primary)
                }
            }
        }
    }

    // 下载菜单：全部下载 / 批量下载
    if (showMenu) {
        val c = ios()
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            containerColor = if (c.isDark) Color(0xF20E1830) else Color(0xF7F4F8FE),
            scrimColor = Color(0x66000000),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
                Text("下载收件箱", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("待处理 $pendingCount 条（待下载与失败项）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(14.dp))
                GlassButton("全部下载", onClick = {
                    val ids = inbox.filter { it.status == "captured" || it.status == "failed" }
                        .map { it.id }
                    scope.launch(Dispatchers.IO) { Store.tasks.downloadInbox(ids) }
                    showMenu = false
                }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                GlassButton("批量下载（自选条目）", onClick = {
                    selectMode = true
                    selectedIds.clear()
                    showMenu = false
                }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 状态筛选胶囊：五个一行等宽压缩版。 */
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

@Composable
private fun InboxCard(
    item: InboxItem,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    GlassCard(topStripe = inboxStatusColor(item.status)) {
        Row(Modifier.padding(12.dp)) {
            // 批量选择模式：勾选圈
            if (selectMode) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .then(
                            if (selected) Modifier.background(ios().accent)
                            else Modifier.border(1.5.dp, ios().faint, CircleShape))
                        .clickable { onToggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Text("✓", color = Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
            }
            // 左列：媒体预览图（小正方形主视觉，×N 角标）
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, ios().rim, RoundedCornerShape(12.dp))) {
                if (item.previewUrl.isNotBlank()) AsyncImage(
                    model = item.previewUrl, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    imageLoader = Store.imageLoader, modifier = Modifier.fillMaxSize())
                else Text("▦", modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                if (item.mediaCount > 1)
                    Text("×${item.mediaCount}",
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color(0x8C000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        color = Color.White, fontSize = 9.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)
                .then(if (selectMode) Modifier.clickable { onToggle() } else Modifier)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 小头像（20dp）融入文字行：左列让位给预览图
                    Box(Modifier.size(20.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (item.avatarUrl.isNotBlank()) AsyncImage(
                            model = item.avatarUrl, contentDescription = null,
                            contentScale = ContentScale.Crop,
                            imageLoader = Store.imageLoader, modifier = Modifier.fillMaxSize())
                        else Text(item.handle.take(1).uppercase().ifBlank { "?" },
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(5.dp))
                    PlatBadge(item.platform)
                    Spacer(Modifier.width(5.dp))
                    Text(if (item.handle.isNotBlank()) "@${item.handle}" else shortUrl(item.url),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(5.dp))
                    Text(inboxStatusText(item.status),
                        color = inboxStatusColor(item.status), fontSize = 10.sp)
                }
                if (item.text.isNotBlank()) {
                    Text(item.text, fontSize = 10.sp, lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp))
                }
                if (item.error.isNotBlank()) {
                    Text(item.error,
                        color = if (item.status == "failed") ErrColor
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp, lineHeight = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!selectMode) Row {
                    if (item.status == "captured" || item.status == "failed") {
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) { Store.tasks.downloadInbox(listOf(item.id)) }
                        }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(if (item.status == "failed") "重试" else "下载")
                        }
                    }
                    TextButton(onClick = { Store.openUrl(item.url) },
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↗ 原帖") }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) { Store.tasks.removeInbox(item.id) }
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("移除") }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(t: TaskItem) {
    GlassCard(topStripe = statusColor(t.status)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatBadge(t.platform)
                Spacer(Modifier.width(8.dp))
                Text(shortUrl(t.url), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatusPill(statusText(t.status), statusColor(t.status))
            }
            Spacer(Modifier.height(8.dp))
            val pct = if (t.totalFiles > 0)
                ((t.doneFiles + t.skipped).toFloat() / t.totalFiles).coerceIn(0f, 1f)
            else if (t.status == "running") 0.04f else 0f
            IosProgress(pct)
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("${t.doneFiles}/${if (t.totalFiles > 0) t.totalFiles else "?"} 个文件")
                    if (t.skipped > 0) append(" · 跳过重复 ${t.skipped}")
                    if (t.bytes > 0) append(" · ${fmtBytes(t.bytes)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (t.error.isNotBlank()) {
                Text(t.error, color = ErrColor, style = MaterialTheme.typography.labelSmall)
            } else if (t.message.isNotBlank()) {
                Text(t.message, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                if (t.status == "queued" || t.status == "running")
                    TextButton(onClick = { Store.tasks.cancel(t.id) }) { Text("取消") }
                if (t.status == "error" || t.status == "partial" || t.status == "canceled" || t.status == "done")
                    TextButton(onClick = { Store.tasks.retry(t.id) }) { Text("重试") }
                TextButton(onClick = { Store.openUrl(t.url) }) { Text("↗ 原帖") }
                TextButton(onClick = { Store.tasks.delete(t.id) }) { Text("删除", color = ErrColor) }
            }
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem) {
    GlassCard {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(statusText(t.status), statusColor(t.status))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(shortUrl(t.url), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${statusText(t.status)} · ${t.doneFiles} 文件" +
                        (if (t.skipped > 0) " · 跳过${t.skipped}" else "") +
                        (if (t.error.isNotBlank()) " · ${t.error}" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { Store.openUrl(t.url) },
                contentPadding = PaddingValues(4.dp)) { Text("↗") }
            TextButton(onClick = { Store.tasks.retry(t.id) },
                contentPadding = PaddingValues(4.dp)) { Text("↻") }
            TextButton(onClick = { Store.tasks.delete(t.id) },
                contentPadding = PaddingValues(4.dp)) { Text("🗑") }
        }
    }
}

private fun shortUrl(u: String): String =
    u.removePrefix("https://").removePrefix("http://").removePrefix("www.")
