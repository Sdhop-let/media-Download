package com.ep.donwnloader

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var maxBsky by remember { mutableStateOf(Store.prefs.maxBskyPosts.toString()) }
    var autoOpt by remember { mutableStateOf(Store.prefs.autoOptimize) }
    var interval by remember { mutableStateOf(Store.prefs.intervalMin.toString()) }
    var dynColor by remember { mutableStateOf(Store.dynamicColor) }
    var shareAuto by remember { mutableStateOf(Store.prefs.shareAutoDownload) }
    var capsule by remember { mutableStateOf(Store.prefs.capsuleNotify) }
    var xCookieOpen by remember { mutableStateOf(false) }
    var igCookieOpen by remember { mutableStateOf(false) }
    var proxyOptOpen by remember { mutableStateOf(false) }
    var syncPage by remember { mutableStateOf(false) }
    var recycleOpen by remember { mutableStateOf(false) }
    var deletedItems by remember { mutableStateOf(listOf<MediaItem>()) }
    var deletedCnt by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            deletedCnt = Store.db.deletedCount()
        }
    }
    BackHandler(enabled = syncPage) { syncPage = false }
    BackHandler(enabled = recycleOpen) { recycleOpen = false }

    if (syncPage) {
        SyncExternalScreen(onBack = { syncPage = false })
        return
    }
    if (recycleOpen) {
        RecycleBinScreen(onBack = { recycleOpen = false })
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 116.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("设置", textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp))
        }

        // ══════════ 板块一：下载与网络 ══════════
        item {
            GlassCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GroupHeader("📥", "下载与网络")
                    SwitchRow("分享自动下载",
                        "X / Instagram / Bluesky 分享给本应用即自动进收件箱下载；关闭则只入收件箱",
                        shareAuto) { shareAuto = it; Store.prefs.shareAutoDownload = it }
                    RowDivider()
                    SwitchRow("底部胶囊通知",
                        "后台下载完成后屏幕底部悬浮结果提示；首次开启需授予「显示在其他应用上层」权限",
                        capsule) {
                        capsule = it
                        Store.prefs.capsuleNotify = it
                        if (it && !android.provider.Settings.canDrawOverlays(ctx)) {
                            runCatching {
                                ctx.startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                    RowDivider()
                    // Bluesky 主页抓取上限
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Bluesky 主页抓取上限", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("单次抓取主页最新帖子的数量", fontSize = 10.5.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp))
                        }
                        GlassTextField(
                            value = maxBsky,
                            onValueChange = {
                                maxBsky = it.filter { ch -> ch.isDigit() }
                                maxBsky.toIntOrNull()?.let { n -> Store.prefs.maxBskyPosts = n }
                            },
                            modifier = Modifier.width(84.dp),
                            singleLine = true,
                        )
                    }
                    RowDivider()
                    // X Cookie（可折叠，chevron 动画；副标题随抓取状态刷新）
                    CollapseRow("X / Twitter Cookie",
                        if (Store.prefs.twCookieHeader().isNotBlank()) "已配置 · 受限内容与主页解析用"
                        else "未配置 · 浏览器登录抓取或手动粘贴", xCookieOpen) {
                        xCookieOpen = !xCookieOpen
                    }
                    AnimatedVisibility(xCookieOpen, enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()) {
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("推荐：在 App 内嵌浏览器完成登录后自动抓取（含 HttpOnly）；下方手动粘贴保留作兜底",
                                fontSize = 10.5.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            GlassButton(text = "浏览器登录 X 并自动抓取", kind = ButtonKind.Ghost,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    ctx.startActivity(Intent(ctx, CookieLoginActivity::class.java)
                                        .putExtra(CookieLoginActivity.EXTRA_PLATFORM, "twitter"))
                                })
                            // cookieEpoch 为 key：容器登录成功返回后重读 prefs 刷新输入框
                            // 明文默认隐藏（掩码 + 只读），点「显示」查看/编辑——防旁人窥屏
                            var xReveal by remember { mutableStateOf(false) }
                            var twToken by remember(Store.cookieEpoch.intValue) { mutableStateOf(Store.prefs.twAuthToken) }
                            var twCt0 by remember(Store.cookieEpoch.intValue) { mutableStateOf(Store.prefs.twCt0) }
                            CookieFieldRow(
                                value = twToken, reveal = xReveal, placeholder = "auth_token = 32 位十六进制",
                                onReveal = { xReveal = !xReveal },
                                onValueChange = { twToken = it.trim(); Store.prefs.twAuthToken = twToken },
                            )
                            CookieFieldRow(
                                value = twCt0, reveal = xReveal, placeholder = "ct0 = 160 位十六进制",
                                onReveal = { xReveal = !xReveal },
                                onValueChange = { twCt0 = it.trim(); Store.prefs.twCt0 = twCt0 },
                            )
                            Text("凭据已加密存储（设备 Keystore），仅本应用可读；默认隐藏，点「显示」查看或编辑明文",
                                fontSize = 10.sp, lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    RowDivider()
                    // Instagram Cookie（可折叠，chevron 动画；副标题随抓取状态刷新）
                    CollapseRow("Instagram Cookie",
                        if (Store.prefs.igCookie.isNotBlank()) "已配置 · 帖子与主页解析用"
                        else "未配置 · 浏览器登录抓取或手动粘贴", igCookieOpen) {
                        igCookieOpen = !igCookieOpen
                    }
                    AnimatedVisibility(igCookieOpen, enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()) {
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("推荐：在 App 内嵌浏览器完成登录后自动抓取 Cookie（含 sessionid）；下方手动粘贴保留作兜底",
                                fontSize = 10.5.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("实验性功能：非官方接口，使用 sessionid 抓取可能触发平台风控导致会话失效，请自行评估。",
                                fontSize = 10.5.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp))
                            GlassButton(text = "浏览器登录 Instagram 并自动抓取", kind = ButtonKind.Ghost,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    ctx.startActivity(Intent(ctx, CookieLoginActivity::class.java)
                                        .putExtra(CookieLoginActivity.EXTRA_PLATFORM, "instagram"))
                                })
                            var igReveal by remember { mutableStateOf(false) }
                            var igCookie by remember(Store.cookieEpoch.intValue) { mutableStateOf(Store.prefs.igCookie) }
                            CookieFieldRow(
                                value = igCookie, reveal = igReveal,
                                placeholder = "sessionid=xxx; ds_user_id=xxx; …",
                                onReveal = { igReveal = !igReveal },
                                onValueChange = { igCookie = it; Store.prefs.igCookie = it },
                                singleLine = false, minLines = 3,
                            )
                            Text("凭据已加密存储（设备 Keystore），仅本应用可读；默认隐藏，点「显示」查看或编辑明文",
                                fontSize = 10.sp, lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    RowDivider()
                    // 代理优选（开关 + 折叠，chevron 动画）
                    Row(Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { proxyOptOpen = !proxyOptOpen }
                        .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("代理优选", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("周期复测候选，显著更优时自动切换", fontSize = 10.5.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp))
                        }
                        IosSwitch(checked = autoOpt, onChange = {
                            autoOpt = it
                            Store.prefs.autoOptimize = it
                            Store.prefs.intervalMin = interval.toIntOrNull() ?: 10
                        }, modifier = Modifier.padding(start = 10.dp))
                        Chevron(open = proxyOptOpen, modifier = Modifier.padding(start = 8.dp))
                    }
                    AnimatedVisibility(proxyOptOpen, enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("复测间隔", fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            GlassTextField(
                                value = interval,
                                onValueChange = { interval = it.filter { ch -> ch.isDigit() } },
                                modifier = Modifier.width(64.dp),
                                singleLine = true,
                            )
                            Text("分钟；代理页可导入 Clash 配置参与优选", fontSize = 10.5.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ══════════ 板块二：存储管理 ══════════
        item {
            GlassCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GroupHeader("🗂", "存储管理", hint = "素材库 · 回收站")
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val scope2 = rememberCoroutineScope()
                        var rescanned by remember { mutableStateOf<Int?>(null) }
                        var sweepOpen by remember { mutableStateOf(false) }
                        GlassButton("扫描下载目录", onClick = {
                            scope2.launch(Dispatchers.IO) { rescanned = Store.tasks.rescanLibrary() }
                        }, modifier = Modifier.weight(1f))
                        GlassButton("同步其他目录", onClick = { syncPage = true },
                            kind = ButtonKind.Ghost, modifier = Modifier.weight(1f))
                        GlassButton("清理残留", onClick = { sweepOpen = true },
                            kind = ButtonKind.Ghost, modifier = Modifier.weight(1f))
                        if (sweepOpen) {
                            ConfirmDialog("清理残留文件？",
                                "扫描应用下载目录：删除未登记的孤儿文件与下载残片，并清空遗留的空目录。库内素材与回收站文件不受影响。",
                                confirmText = "清理",
                                onConfirm = {
                                    sweepOpen = false
                                    scope2.launch(Dispatchers.IO) {
                                        val (f, d) = MediaFiles.residueSweep()
                                        Store.tasks.toast.value = "已清理 $f 个残留文件、$d 个空目录"
                                    }
                                },
                                onDismiss = { sweepOpen = false })
                        }
                    }
                    Text("扫描仅登记应用下载目录；「同步其他目录」共享引用外部素材（文件留在原位）；「清理残留」清孤儿文件与空目录",
                        fontSize = 10.5.sp, lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp))
                    RowDivider()
                    // 回收站入口 → 独立子页
                    Row(Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { recycleOpen = true }
                        .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("回收站（${deletedCnt}）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("已删除素材暂存于此，可恢复或彻底清除", fontSize = 10.5.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp))
                        }
                        Chevron(open = false)
                    }
                }
            }
        }

        // ══════════ 板块三：外观与主题 ══════════
        item {
            GlassCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GroupHeader("🎨", "外观与主题")
                    IosSegmented(
                        options = listOf("跟随系统", "浅色", "深色"),
                        selected = when (Store.themeMode) { "light" -> 1; "dark" -> 2; else -> 0 },
                        onSelect = { i ->
                            val v = when (i) { 1 -> "light"; 2 -> "dark"; else -> "system" }
                            Store.themeMode = v
                            Store.prefs.themeMode = v
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                    RowDivider()
                    SwitchRow("莫奈动态取色", "Android 12+ 跟随系统壁纸配色", dynColor) {
                        // 运行时状态必须同步写：MainActivity 的派生分支读 Store.dynamicColor，
                        // 只写 prefs 会导致关莫奈后仍走莫奈配色、自定义种子色被挡住
                        dynColor = it
                        Store.dynamicColor = it
                        Store.prefs.dynamicColor = it
                    }
                    // 莫奈取色作用域：仅主色（现状）/ 全局跟随（背景与光斑同步派生）
                    if (dynColor) {
                        Column(Modifier.padding(start = 2.dp, top = 4.dp, bottom = 6.dp)) {
                            Text("取色作用域", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("仅主色：按钮与选中态用壁纸色；全局跟随：背景渐变与光斑同步跟随壁纸",
                                fontSize = 10.5.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp))
                            IosSegmented(
                                options = listOf("仅主色", "全局跟随"),
                                selected = if (Store.dynamicScope == "full") 1 else 0,
                                onSelect = { i ->
                                    val v = if (i == 1) "full" else "accent"
                                    Store.dynamicScope = v
                                    Store.prefs.dynamicScope = v
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                    RowDivider()
                    // 主题颜色自定义（莫奈开启时让位给壁纸色）
                    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                        Text("主题颜色", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (dynColor) "莫奈取色开启时，自定义色暂不生效" else "点选种子色即时换肤，明暗两套自动派生",
                            fontSize = 10.5.sp, lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            item {
                                AccentDot("", "默认", Color(0xFF2F4C8F), disabled = dynColor)
                            }
                            AccentPresets.forEach { (hex, name) ->
                                item {
                                    AccentDot(hex, name, parseSeedColor(hex), disabled = dynColor)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ══════════ 板块四：关于（含支持范围） ══════════
        item {
            GlassCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GroupHeader("ℹ️", "关于")
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("媒体下载器 Android v1.1", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("包名 com.ep.donwnloader", fontSize = 10.5.sp, lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp))
                    }
                    RowDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("支持范围", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 7.dp),
                        ) {
                            listOf(
                                "X 推文免登录", "X 主页批量*", "Bluesky 全支持", "IG 帖子/主页*",
                                "系统分享直达", "剪贴板识别", "收件箱 · 回收站", "代理优选", "本地素材导入",
                            ).forEach { SupportTag(it) }
                        }
                        Text("* 需配置 Cookie；主页批量与 IG 解析为实验性", fontSize = 9.5.sp, lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 7.dp))
                    }
                    RowDivider()
                    Text("素材保存于应用专属目录 Android/data/com.ep.donwnloader/files/downloads",
                        fontSize = 10.5.sp, lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}

/** 可折叠分区块头：动画 chevron 指示展开方向（内容侧过渡由 AnimatedVisibility 处理）。 */
@Composable
private fun CollapseRow(title: String, subtitle: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .clickable { onToggle() }
        .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 10.5.sp, lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp))
        }
        Chevron(open = open, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 板块组头：主色圆片图标 + 标题 + 可选尾注。 */
@Composable
private fun GroupHeader(icon: String, title: String, hint: String? = null) {
    val c = ios()
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(c.primary.copy(alpha = if (c.isDark) 0.22f else 0.10f))
                .border(0.5.dp, c.primary.copy(alpha = 0.22f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(icon, fontSize = 12.sp) }
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = c.text)
        if (hint != null) {
            Spacer(Modifier.width(7.dp))
            Text(hint, fontSize = 10.5.sp, color = c.faint)
        }
    }
}

/** 行间细分隔线（iOS 设置列表语言：左对齐起点由行内 padding 决定，此处通栏淡线）。 */
@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 1.dp)
        .height(1.dp).background(ios().line.copy(alpha = 0.55f)))
}

/** 开关行：标题 + 说明 + 尾部开关（统一字号体系：13sp / 10.5sp）。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 10.5.sp, lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp))
        }
        IosSwitch(checked = checked, onChange = onChange, modifier = Modifier.padding(start = 10.dp))
    }
}

/** 支持范围小胶囊：fill 底 + 细描边，紧凑字号。 */
@Composable
private fun SupportTag(text: String) {
    val c = ios()
    Text(text, fontSize = 10.5.sp, lineHeight = 13.sp, color = c.text2,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.fill.copy(alpha = if (c.isDark) 0.9f else 0.72f))
            .border(0.5.dp, c.rim, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp))
}

/** 同步进度快照：phase=hash 校验指纹 / register 登记（TaskManager.syncExternalDir 回调）。 */
private data class SyncProgress(val phase: String, val done: Int, val total: Int, val registered: Int)

/** 同步其他目录子页：输入路径 → 验证可用性 → 同步登记进素材库（可取消，2026-09-11）。 */
@Composable
private fun SyncExternalScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(Store.prefs.syncDir.ifBlank { "/sdcard/Download" }) }
    var checked by remember { mutableStateOf<String?>(null) }
    var checkedOk by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    // 取消标志（IO 协程在指纹分批/逐条登记处轮询）+ 实时进度
    val cancelFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var progress by remember { mutableStateOf<SyncProgress?>(null) }
    // 目录退订（2026-09-11）：先计数预览 → 确认弹窗 → 整体解除登记（文件保留原位）
    var removing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pendingCount by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("◂ 返回")
            }
            Text("同步其他目录", textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
        }
        GlassCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目录路径", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                GlassTextField(
                    value = path,
                    onValueChange = { path = it; checked = null; checkedOk = false; result = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "/sdcard/Download",
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("验证目录", onClick = {
                        scope.launch(Dispatchers.IO) {
                            checked = try {
                                val p = Store.tasks.probeExternalDir(path)
                                checkedOk = true
                                (if (p.rootMirror) "Root 直读通道已打通："
                                else "可用：") + "共 ${p.total} 个媒体（识别 Edqiu 导出 ${p.edqiu} / 普通 ${p.plain}）；共享引用，Edqiu 原件不动、零复制"
                            } catch (e: Exception) {
                                checkedOk = false
                                e.message ?: "目录不可用"
                            }
                        }
                    }, enabled = !syncing, modifier = Modifier.weight(1f))
                    GlassButton("开始同步", onClick = {
                        cancelFlag.set(false)
                        scope.launch(Dispatchers.IO) {
                            syncing = true
                            progress = null
                            result = try {
                                val r = Store.tasks.syncExternalDir(path,
                                    isCancelled = { cancelFlag.get() },
                                    onProgress = { ph, done, tot, reg ->
                                        progress = SyncProgress(ph, done, tot, reg)
                                    })
                                Store.prefs.syncDir = path
                                if (r.cancelled)
                                    "已取消：本次已入库 ${r.registered}（重复跳过 ${r.skippedDup}），已登记部分保留，再次同步自动续传"
                                else
                                    "同步完成：入库 ${r.registered}（归真实作者 ${r.authorized}｜推文归并 ${r.tweetOnly}｜普通 ${r.plain}），重复跳过 ${r.skippedDup}" +
                                        if (r.rootMirror) " · Root 直读模式" else ""
                            } catch (e: Exception) {
                                "同步失败：${e.message}"
                            }
                            syncing = false
                            progress = null
                        }
                    }, enabled = checkedOk && !syncing, kind = ButtonKind.Ghost,
                        modifier = Modifier.weight(1f))
                }
                checked?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = if (checkedOk) OkColor else ErrColor)
                }
                if (syncing) {
                    IosProgress(
                        pct = progress?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total },
                        modifier = Modifier.fillMaxWidth())
                    progress?.let { p ->
                        Text(
                            if (p.phase == "hash") "校验指纹 ${p.done}/${p.total}"
                            else "登记 ${p.done}/${p.total} · 已入库 ${p.registered}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlassButton("取消同步", onClick = { cancelFlag.set(true) },
                        kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
                }
                result?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = when {
                            it.startsWith("同步完成") || it.startsWith("已取消") -> OkColor
                            it.startsWith("同步失败") || it.startsWith("移除失败") -> ErrColor
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                }
                // 目录退订（2026-09-11）：不再需要某目录的素材时，整体解除登记
                GlassButton("移除该目录登记", onClick = {
                    scope.launch(Dispatchers.IO) {
                        removing = true
                        pendingCount = runCatching { Store.tasks.countExternalDir(path) }.getOrDefault(0)
                        removing = false
                        if (pendingCount > 0) confirmRemove = true
                        else Store.tasks.toast.value = "该目录暂无已登记素材"
                    }
                }, enabled = !syncing && !removing, kind = ButtonKind.Ghost,
                    modifier = Modifier.fillMaxWidth())
                Text("说明：共享引用模式——扫描该目录（含子目录）的图片与视频登记进素材库，文件保留原位由属主 App（如 Edqiu）继续管理；Edqiu 导出的素材自动归并到真实作者与推文并带「E」角标；对方新下载后再次点同步即增量入账。同步可随时取消，已入库部分保留；不再需要时填回原路径点「移除该目录登记」整体退订，文件保留原位。从本 App 删除共享素材只解除登记，不动对方文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (confirmRemove) {
            ConfirmDialog(
                "移除该目录登记？",
                "将解除登记 $pendingCount 项素材（含回收站对应条目）；文件全部保留原位，Edqiu 侧不受影响，之后可随时重新同步回来。",
                onConfirm = {
                    confirmRemove = false
                    scope.launch(Dispatchers.IO) {
                        removing = true
                        result = try {
                            val n = Store.tasks.removeExternalDir(path)
                            "已解除登记 $n 项，文件保留原位（可随时重新同步）"
                        } catch (e: Exception) {
                            "移除失败：${e.message}"
                        }
                        removing = false
                    }
                },
                onDismiss = { confirmRemove = false },
                confirmText = "解除登记")
        }
    }
}

// ---------------- 回收站子页 ----------------

/** 主题颜色预设种子（hex, 名称）；"" = 默认深蓝。 */
private val AccentPresets = listOf(
    "#B0483C" to "绯红", "#C97A26" to "琥珀", "#2E9E7C" to "青翠", "#0F7FA8" to "湖蓝",
    "#7C5CD6" to "黛紫", "#C25584" to "玫瑰", "#4A5568" to "石墨",
)

private fun parseSeedColor(hex: String): Color =
    if (hex.isBlank()) Color(0xFF2F4C8F)
    else try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { Color(0xFF2F4C8F) }

/** 色板圆点：选中描边 + ✓；莫奈取色开启时置灰禁点。 */
@Composable
private fun AccentDot(hex: String, name: String, color: Color, disabled: Boolean) {
    val c = ios()
    val selected = Store.accentSeed == hex
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = !disabled) {
            Store.accentSeed = hex
            Store.prefs.accentSeed = hex
        },
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(if (disabled) color.copy(alpha = 0.35f) else color)
                .border(
                    if (selected) 2.5.dp else 1.dp,
                    when { selected -> c.text; disabled -> c.line; else -> c.rim },
                    CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("✓", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(name, fontSize = 10.sp,
            color = when { selected -> c.text; disabled -> c.faint; else -> c.text2 },
            modifier = Modifier.padding(top = 3.dp), maxLines = 1)
    }
}

/** 回收站子页：统计卡 + 缩略图网格 + 单项/批量恢复与彻底删除 + 清空。 */
@Composable
private fun RecycleBinScreen(onBack: () -> Unit) {
    val c = ios()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(listOf<MediaItem>()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var purgeAllOpen by remember { mutableStateOf(false) }
    var purgeOneOpen by remember { mutableStateOf<MediaItem?>(null) }
    var purgeBatchOpen by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            items = Store.db.listDeleted()
            totalBytes = Store.db.deletedBytes()
        }
    }
    LaunchedEffect(Unit) { reload() }
    BackHandler(enabled = selectMode) { selectMode = false; selectedIds.clear() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("◂ 返回")
            }
            Text("回收站", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                fontSize = 20.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (items.isNotEmpty()) purgeAllOpen = true },
                contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("清空", color = if (items.isNotEmpty()) ErrColor else c.faint)
            }
        }
        GlassCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("${items.size} 项 · 共 ${fmtBytes(totalBytes)}",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.text)
                Text("删除的素材暂存于此（保留原文件）；恢复即回到素材库，彻底删除/清空会连同文件一起删除",
                    style = MaterialTheme.typography.labelSmall, color = c.faint,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        // 批量操作栏（长按进入）
        if (selectMode) {
            GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selectedIds.size} 项", fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        selectedIds.clear()
                        items.forEach { selectedIds.add(it.id) }
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("全选") }
                    TextButton(onClick = {
                        val ids = selectedIds.toList()
                        scope.launch(Dispatchers.IO) {
                            ids.forEach { Store.db.restoreMedia(it) }
                            Store.mediaVersion++
                            reload()
                        }
                        selectMode = false
                        selectedIds.clear()
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("恢复") }
                    TextButton(onClick = {
                        if (selectedIds.isNotEmpty()) purgeBatchOpen = true
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("彻底删除", color = ErrColor)
                    }
                    TextButton(onClick = { selectMode = false; selectedIds.clear() },
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("取消") }
                }
            }
        }
        if (items.isEmpty()) {
            Text("回收站是空的\n删除的素材会出现在这里",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 100.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.id }) { m ->
                    MediaCell(m,
                        selected = selectMode && m.id in selectedIds,
                        onClick = {
                            if (selectMode) {
                                if (m.id in selectedIds) selectedIds.remove(m.id) else selectedIds.add(m.id)
                            } else selected = m
                        },
                        onLongClick = {
                            if (!selectMode) { selectMode = true; selectedIds.clear() }
                            if (m.id in selectedIds) selectedIds.remove(m.id) else selectedIds.add(m.id)
                        })
                }
            }
        }
    }

    // 单项详情弹层
    selected?.let { m ->
        RecycleSheet(m,
            onDismiss = { selected = null },
            onRestore = {
                scope.launch(Dispatchers.IO) {
                    Store.db.restoreMedia(m.id)
                    Store.mediaVersion++  // 恢复 → 素材库即时刷新
                    reload()
                }
                selected = null
            },
            onPurge = {
                selected = null
                purgeOneOpen = m
            })
    }
    if (purgeAllOpen) {
        ConfirmDialog("清空回收站？", "将彻底删除全部 ${items.size} 项及对应文件，不可恢复。",
            onConfirm = {
                purgeAllOpen = false
                scope.launch(Dispatchers.IO) {
                    val rows = Store.db.deletedRows()
                    val (ok, fail) = MediaFiles.purgeEntries(rows)
                    Store.tasks.toast.value = if (fail == 0) "已清空：$ok 项及对应文件已删除"
                        else "已删 $ok 项；$fail 项文件删除失败，暂留回收站"
                    reload()
                }
            },
            onDismiss = { purgeAllOpen = false })
    }
    purgeOneOpen?.let { m ->
        ConfirmDialog("彻底删除？", "@${m.handle} 的该素材与文件将被永久删除，不可恢复。",
            onConfirm = {
                purgeOneOpen = null
                scope.launch(Dispatchers.IO) {
                    val rows = Store.db.deletedRows().filter { it.first == m.id }
                    val (ok, fail) = MediaFiles.purgeEntries(rows)
                    Store.tasks.toast.value = if (ok > 0) "已彻底删除（含文件）"
                        else "文件删除失败，已保留在回收站"
                    reload()
                }
            },
            onDismiss = { purgeOneOpen = null })
    }
    if (purgeBatchOpen) {
        ConfirmDialog("彻底删除 ${selectedIds.size} 项？", "所选素材与对应文件将被永久删除，不可恢复。",
            onConfirm = {
                purgeBatchOpen = false
                val ids = selectedIds.toList()
                scope.launch(Dispatchers.IO) {
                    val rows = Store.db.deletedRows().filter { it.first in ids }
                    val (ok, fail) = MediaFiles.purgeEntries(rows)
                    Store.tasks.toast.value = if (fail == 0) "已彻底删除 $ok 项（含文件）"
                        else "已删 $ok 项；$fail 项文件删除失败，暂留回收站"
                    reload()
                }
                selectMode = false
                selectedIds.clear()
            },
            onDismiss = { purgeBatchOpen = false })
    }
}

/** 回收站单项详情：玻璃底部弹层（封面 + 信息 + 恢复 / 彻底删除）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecycleSheet(m: MediaItem, onDismiss: () -> Unit, onRestore: () -> Unit, onPurge: () -> Unit) {
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
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 34.dp)) {
            val cover = if (m.mediaType == "photo") m.filePath else m.thumbPath
            if (cover.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(java.io.File(cover)).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    imageLoader = Store.imageLoader,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)),
                )
                Spacer(Modifier.height(14.dp))
            }
            Text("@${m.handle}", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text("${platName(m.platform)} · ${if (m.mediaType == "video") "视频" else "图片"} · ${m.ext} · ${fmtBytes(m.fileSize)}",
                style = MaterialTheme.typography.labelSmall, color = c.faint)
            Text("删除于 ${if (m.deletedAt.isNotBlank()) fmtIso(m.deletedAt) else "—"}",
                style = MaterialTheme.typography.labelSmall, color = c.faint,
                modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("恢复到素材库", onClick = onRestore, modifier = Modifier.weight(1f))
                GlassButton("彻底删除", onClick = onPurge, kind = ButtonKind.Ghost,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 通用确认弹窗。 */
@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
                          confirmText: String = "确认删除") {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(text, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = ErrColor) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ---------------- Cookie 掩码行（v1.1：明文默认隐藏，防旁人窥屏；存储层另经 Keystore 加密） ----------------

/** 隐藏态掩码：固定 12 点，不泄露真实长度；仅真实值为空时显示占位。 */
private const val COOKIE_MASK = "••••••••••••"

/** Cookie 输入行：输入框 + 「显示/隐藏」按钮。隐藏态掩码只读（enabled=false，不触发 onValueChange），明文永不被掩码污染。 */
@Composable
private fun CookieFieldRow(
    value: String,
    reveal: Boolean,
    placeholder: String,
    onReveal: () -> Unit,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassTextField(
            value = if (reveal || value.isBlank()) value else COOKIE_MASK,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = placeholder,
            singleLine = singleLine,
            minLines = minLines,
            enabled = reveal,
        )
        Text(
            text = if (reveal) "隐藏" else "显示",
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onReveal() }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}
