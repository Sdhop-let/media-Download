package com.ep.donwnloader

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/**
 * 全屏竖滑视频流（效仿 X 播放页，2026-09-10 用户定型）：
 * - 上下滑动切换上/下一个视频，切换后继续自动播放
 * - 每页只显示「头像 + 显示名 + @ID」（不要"正在关注"、评论/点赞/回复栏）
 * - 顶栏：左上返回；右上 ⋮ 菜单 = 跳转到该视频 / 跳转到该作者主页
 * - 视频居中 FIT（上下留黑边）；底部中央明确的暂停/播放圆钮（点击画面不触发暂停）
 */
@Composable
fun VideoFeedDialog(items: List<MediaItem>, initialIndex: Int, onDismiss: () -> Unit) {
    if (items.isEmpty()) { onDismiss(); return }
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
                FeedPage(
                    m = items[page],
                    isCurrent = pagerState.settledPage == page,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun FeedPage(m: MediaItem, isCurrent: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var paused by remember(m.id) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 沉浸式覆盖层：播放开始 1.5s 后自动淡出全部文字/按钮；点击画面唤出/隐藏；暂停时常显
    var overlayVisible by remember(m.id) { mutableStateOf(true) }

    // 播放源：自有路径直接用；外部共享路径（root 直读登记的原路径）先按需物化到 cache
    // （su cat 流式单文件复制，秒级），prepare 就绪后才允许 playWhenReady
    var prepared by remember(m.id) { mutableStateOf(false) }
    val player = remember(m.id) {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build()
    }
    DisposableEffect(m.id) { onDispose { player.release() } }
    LaunchedEffect(m.id) {
        val src = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ContentAccess.local(m.filePath)
        }
        if (src != null) {
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(src)))
            player.prepare()
            prepared = true
        }
    }
    // 翻页状态复位 + 播放控制：当前页且未暂停且已就绪才播（修复：paused 变化必须联动 playWhenReady）
    LaunchedEffect(isCurrent, paused, prepared) {
        if (!isCurrent) { paused = false; overlayVisible = true }
        player.playWhenReady = isCurrent && !paused && prepared
    }
    // 沉浸计时：播放中且覆盖层可见 → 1.5s 后淡出（key 含 overlayVisible：点击唤出后重新计时）
    LaunchedEffect(isCurrent, paused, overlayVisible) {
        if (isCurrent && !paused && overlayVisible) {
            kotlinx.coroutines.delay(1500)
            overlayVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { if (isCurrent) overlayVisible = !overlayVisible }) {
        androidx.compose.ui.viewinterop.AndroidView(
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
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(10.dp))
                Text("准备中…", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }

        // 覆盖层（顶栏 + 头像 ID 行 + 暂停钮）：随 overlayVisible 整体淡入淡出
        androidx.compose.animation.AnimatedVisibility(
            visible = overlayVisible,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(250)),
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
                                DropdownMenuItem(
                                    text = { Text("跳转到该视频") },
                                    onClick = {
                                        menuOpen = false
                                        Store.openUrl(m.postUrl)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("跳转到该作者主页") },
                                    onClick = {
                                        menuOpen = false
                                        Store.openUrl(m.profileUrl)
                                    },
                                )
                            }
                        }
                    }
                    // 头像 + ID 行
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
                        Column {
                            Text(m.authorName.ifBlank { m.handle }.ifBlank { "未知作者" },
                                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("@${m.handle}", color = Color(0x99FFFFFF), fontSize = 12.5.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // 底部中央：暂停/播放圆钮
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
                        androidx.compose.foundation.Canvas(Modifier.size(22.dp, 24.dp)) {
                            val w = size.width
                            val barW = w * 0.28f
                            val h = size.height
                            drawRoundRect(
                                color = Color.White,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                                size = androidx.compose.ui.geometry.Size(barW, h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                            )
                            drawRoundRect(
                                color = Color.White,
                                topLeft = androidx.compose.ui.geometry.Offset(w - barW, 0f),
                                size = androidx.compose.ui.geometry.Size(barW, h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                            )
                        }
                    }
                }
            }
        }
    }
}
