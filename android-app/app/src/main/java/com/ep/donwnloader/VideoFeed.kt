package com.ep.donwnloader

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 查看器内「长按 → 详细页」所需按住时长。 */
private const val LONG_PRESS_DETAIL_MS = 5_000L

/**
 * 全屏媒体查看器（2026-10-15 统一，替代原「视频流 + 单图查看」两条路径）。
 *
 * 关键约定：**传入的列表必须同类型**——图片视图只含图片、视频视图只含视频
 * （由调用方按 mediaType 过滤）。因此上下滑动永远只在同类内容间切换。
 *
 * 手势（2026-10-15 用户拍板）：
 * - 上下滑动：切换上/下一项；视频切换后继续自动播放
 * - 轻击：隐藏 / 显示浮层（**浮层不再自动隐藏**，由用户显式收起）
 * - 双击：暂停 / 继续（视频）；图片无播放器，双击仅唤出浮层
 * - 长按 5 秒：打开详细页（MediaSheet，含播放/分享/复制链接/原帖/作者/删除）
 * - 浮层内 ⋮ 菜单同样可进详细页与各项操作
 */
@Composable
fun MediaViewerDialog(
    items: List<MediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onShowDetails: (MediaItem) -> Unit,
) {
    if (items.isEmpty()) {
        // 不能在 composition 阶段直接调用会改状态的 onDismiss，挪进副作用
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.size - 1)) {
        items.size
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,   // 预建相邻页，滑动即播不断流
            ) { page ->
                ViewerPage(
                    m = items[page],
                    position = page,
                    total = items.size,
                    isCurrent = pagerState.settledPage == page,
                    onDismiss = onDismiss,
                    onShowDetails = onShowDetails,
                )
            }
        }
    }
}

@Composable
private fun ViewerPage(
    m: MediaItem,
    position: Int,
    total: Int,
    isCurrent: Boolean,
    onDismiss: () -> Unit,
    onShowDetails: (MediaItem) -> Unit,
) {
    val ctx = LocalContext.current
    val isVideo = m.mediaType == "video"
    var paused by remember(m.id) { mutableStateOf(false) }
    var menuOpen by remember(m.id) { mutableStateOf(false) }
    // 浮层：默认显示、不自动隐藏（轻击收起 / 双击唤出）
    var overlayVisible by remember(m.id) { mutableStateOf(true) }
    // 按住状态：驱动「长按满 5 秒 → 详细页」的计时
    var pressed by remember(m.id) { mutableStateOf(false) }

    // 图片：解析本地可读文件（自有路径直接用；外部共享路径经 su cat 按需物化到 cache）
    var image by remember(m.id) { mutableStateOf(File(m.filePath).takeIf { it.isFile }) }
    LaunchedEffect(m.id) {
        if (!isVideo && image == null) {
            val f = withContext(Dispatchers.IO) { ContentAccess.local(m.filePath) }
            if (f != null) image = f
        }
    }

    // 视频：ExoPlayer 仅视频页创建；prepare 就绪后才允许 playWhenReady
    val player: androidx.media3.exoplayer.ExoPlayer? = remember(m.id) {
        if (isVideo) androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build() else null
    }
    var prepared by remember(m.id) { mutableStateOf(false) }
    DisposableEffect(m.id) { onDispose { player?.release() } }
    LaunchedEffect(m.id) {
        val p = player ?: return@LaunchedEffect
        val src = withContext(Dispatchers.IO) { ContentAccess.local(m.filePath) }
        if (src != null) {
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(src)))
            p.prepare()
            prepared = true
        }
    }
    // 翻页状态复位 + 播放控制：当前页且未暂停且已就绪才播（paused 变化必须联动 playWhenReady）
    LaunchedEffect(isCurrent, paused, prepared) {
        if (!isCurrent) { paused = false; overlayVisible = true }
        player?.playWhenReady = isCurrent && !paused && prepared
    }
    // 长按满 5 秒 → 详细页（提前抬起则 cancelled，不触发）
    LaunchedEffect(pressed, m.id) {
        if (pressed) {
            delay(LONG_PRESS_DETAIL_MS)
            if (pressed) onShowDetails(m)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // ① 只跟踪「按下 / 抬起」，不消费事件——与下面的轻击/双击检测共存；
            //    判定基于 change.pressed 而非消费状态，避免被另一个手势检测器影响。
            .pointerInput(m.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.none { it.pressed }) break
                        }
                    } finally {
                        pressed = false   // 抬起或手势被取消（含 pointerInput 被销毁）都要复位
                    }
                }
            }
            // ② 轻击 → 收起/唤出浮层；双击 → 暂停/继续（视频）
            .pointerInput(m.id, isCurrent, isVideo) {
                detectTapGestures(
                    onTap = { if (isCurrent) overlayVisible = !overlayVisible },
                    onDoubleTap = {
                        if (isCurrent) {
                            if (isVideo) paused = !paused
                            overlayVisible = true
                        }
                    },
                    // 占位：让「按住不放」不被误判成轻击（真正的 5 秒判定由 ① 的计时负责）
                    onLongPress = { },
                )
            },
    ) {
        if (isVideo) {
            AndroidView(
                factory = { vctx ->
                    androidx.media3.ui.PlayerView(vctx).apply {
                        this.player = player
                        useController = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 外部视频物化/缓冲期指示（root 直读：首播前 su cat 单文件，秒级）
            if (!prepared && isCurrent) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("准备中…", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
        } else {
            val f = image
            if (f != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(f).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,   // 完整显示，不裁切
                    imageLoader = Store.imageLoader,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (isCurrent) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("读取中…", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
        }

        // 浮层（顶栏 + 作者行 + 手势提示 + 视频暂停钮）：随 overlayVisible 淡入淡出
        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                        .align(Alignment.TopCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("←", color = Color.White, fontSize = 22.sp,
                            modifier = Modifier
                                .padding(6.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onDismiss() })
                        Spacer(Modifier.weight(1f))
                        Text("${position + 1} / $total", color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Box {
                            Text("⋮", color = Color.White, fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(6.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { menuOpen = true })
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                // 详情弹层保留全部操作（播放/分享/复制链接/原帖/作者/删除）
                                DropdownMenuItem(
                                    text = { Text("详情与操作") },
                                    onClick = { menuOpen = false; onShowDetails(m) },
                                )
                                DropdownMenuItem(
                                    text = { Text("分享") },
                                    onClick = { menuOpen = false; shareMedia(m) },
                                )
                                DropdownMenuItem(
                                    text = { Text("复制帖子链接") },
                                    onClick = { menuOpen = false; copyLink(m) },
                                )
                                DropdownMenuItem(
                                    text = { Text("跳转到原帖") },
                                    onClick = { menuOpen = false; Store.openUrl(m.postUrl) },
                                )
                                DropdownMenuItem(
                                    text = { Text("跳转到作者主页") },
                                    onClick = { menuOpen = false; Store.openUrl(m.profileUrl) },
                                )
                            }
                        }
                    }
                    // 头像 + 作者行
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape)
                            .background(Color(0xFF26324A))) {
                            if (m.avatarUrl.isNotBlank()) AsyncImage(
                                model = m.avatarUrl, contentDescription = null,
                                contentScale = ContentScale.Crop,
                                imageLoader = Store.imageLoader,
                                modifier = Modifier.fillMaxSize())
                            else Text((m.authorName.ifBlank { m.handle }).take(1).uppercase().ifBlank { "?" },
                                color = Color(0xB3FFFFFF), fontSize = 14.sp,
                                modifier = Modifier.align(Alignment.Center))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.authorName.ifBlank { m.handle }.ifBlank { "未知作者" },
                                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("@${m.handle} · ${if (isVideo) "视频" else "图片"} · ${fmtIso(m.postTime)}",
                                color = Color(0x99FFFFFF), fontSize = 12.5.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // 手势提示（浮层可随时收起，不干扰沉浸观看）
                    Text(
                        if (isVideo) "轻击收起浮层 · 双击暂停 · 长按 5 秒查看详情"
                        else "轻击收起浮层 · 长按 5 秒查看详情",
                        color = Color(0x59FFFFFF), fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // 视频页：底部中央暂停/播放圆钮（图片页不显示）
                if (isVideo) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)
                            .size(64.dp).clip(CircleShape)
                            .background(Color(0x66000000))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { if (isCurrent) paused = !paused },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (paused) {
                            Text("▶", color = Color.White, fontSize = 26.sp)
                        } else {
                            // 播放中：双竖条暂停图标（Canvas 绘制，不依赖字体）
                            Canvas(Modifier.size(22.dp, 24.dp)) {
                                val w = size.width
                                val barW = w * 0.28f
                                val h = size.height
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = Offset(0f, 0f),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(6f, 6f),
                                )
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = Offset(w - barW, 0f),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(6f, 6f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
