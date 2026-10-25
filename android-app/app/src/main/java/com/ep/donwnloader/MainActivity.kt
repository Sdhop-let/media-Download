package com.ep.donwnloader

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    private var lastClip: String? = null

    /** App 是否在前台：NotifyHub 用它决定是否发系统通知兜底（前台走 toast 流即可）。 */
    companion object {
        @Volatile var isForeground = false
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        // 剪贴板捕获：前台时检测到平台链接自动收进收件箱
        try {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return
            if (text == lastClip || text == Store.draftInput.value) return
            if (Extractors.detect(text) == null) return
            lastClip = text
            Store.scope.launch(Dispatchers.IO) {
                val (added, _) = Store.tasks.capture(listOf(text))
                if (added > 0) {
                    Store.tasks.toast.value = "检测到剪贴板链接，已捕获到收件箱"
                    Store.tasks.refreshInbox()
                }
            }
        } catch (_: Exception) { }
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { DownloaderApp() }
    }
}

@Composable
fun DownloaderApp() {
    val ctx = LocalContext.current
    // 主题解析：设置页可切 跟随系统 / 浅色 / 深色；强调色由种子色（默认 Edqiu 深蓝）派生
    val dark = when (Store.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val c = EqTheme.resolve(dark, Store.accentSeedColor())
    val scheme = eqScheme(c)

    LaunchedEffect(dark) {
        // 状态栏/导航栏图标明暗跟随当前主题
        runCatching {
            val w = (ctx as android.app.Activity).window
            val ctrl = WindowCompat.getInsetsController(w, w.decorView)
            ctrl.isAppearanceLightStatusBars = !dark
            ctrl.isAppearanceLightNavigationBars = !dark
        }
    }
    // 全局 toast（任务完成 / 代理自动切换）
    LaunchedEffect(Unit) {
        Store.tasks.toast.collect {
            if (it != null) {
                Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
                Store.tasks.toast.value = null
            }
        }
    }
    LaunchedEffect(Unit) {
        Store.proxy.toast.collect {
            if (it != null) {
                Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
                Store.proxy.toast.value = null
            }
        }
    }

    CompositionLocalProvider(LocalEq provides c) {
        MaterialTheme(colorScheme = scheme) {
            // tab 提升到 Store（跨页共享：素材库空状态「去下载页」引导跳转）
            val tab = Store.selectedTab
            val labels = listOf("收件箱", "素材库", "代理", "设置")
            // 磨砂强度 → 可见背景层柔化半径（Edqiu AppNav 同式：frost² × 64dp，0.6 → 23dp）
            val frost = ThemeEffects.GlassFrostStrength.current
            val stageBlur = (frost * frost * 64f).dp

            // 页面内容捕获链：底栏（链外）采样它折射「正下方滚动的内容」；
            // 链内卡片只采样舞台链（LocalStageBackdrop），无递归捕获（SIGSEGV 铁律）
            val shellBackdrop = rememberLayerBackdrop {
                drawRect(c.bg)
                drawContent()
            }

            StageBackground(Modifier.fillMaxSize(), blurRadius = stageBlur) {
                Box(
                    Modifier.fillMaxSize()
                        .padding(WindowInsets.statusBars.asPaddingValues())
                ) {
                    CompositionLocalProvider(LocalAppBackdrop provides shellBackdrop) {
                        Box(
                            Modifier.fillMaxSize()
                                .layerBackdrop(shellBackdrop)
                        ) {
                            when (tab) {
                                0 -> DownloadScreen()
                                1 -> LibraryScreen()
                                2 -> ProxyScreen()
                                else -> SettingsScreen()
                            }
                        }
                    }
                }
                // 底栏整体上移：完整避开手势条区域，50dp 高全可见；真折射采样页面内容
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    GlassTabBar(labels, tab,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 10.dp)) { Store.selectedTab = it }
                }
            }
        }
    }
}
