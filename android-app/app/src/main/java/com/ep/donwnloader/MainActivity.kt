package com.ep.donwnloader

import android.os.Build
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // 兜底：换壁纸（尤其厂商引擎壁纸不发 colorsChanged）后回前台强制重算取色
        if (android.os.Build.VERSION.SDK_INT >= 31) Store.monetEpoch.intValue++
        // 剪贴板捕获：前台时检测到平台链接自动收进收件箱
        try {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return
            if (text == lastClip || text == Store.draftInput.value) return
            if (Extractors.detect(text) == null) return
            lastClip = text
            Store.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
        registerWallpaperColorListener()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { DownloaderApp() }
    }

    /** 壁纸取色实时监听（API 31+）：静态/live 壁纸 colors 变化 → epoch 自增 → 全 UI 重算取色。
     *  回调后 350ms/1200ms 两次自增：SystemUI 主题 overlay 打入有亚秒延迟，兜底刷新。 */
    private fun registerWallpaperColorListener() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        try {
            val wm = getSystemService(android.app.WallpaperManager::class.java)
            if (wm == null) { android.util.Log.w("MonetEpoch", "WallpaperManager null, listener not registered"); return }
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            wm.addOnColorsChangedListener({ colors, which ->
                android.util.Log.i("MonetEpoch", "onColorsChanged which=$which wc=${colors?.primaryColor}")
                if (which and android.app.WallpaperManager.FLAG_SYSTEM != 0) {
                    Store.monetEpoch.intValue++
                    handler.postDelayed({ Store.monetEpoch.intValue++ }, 350)
                    handler.postDelayed({ Store.monetEpoch.intValue++ }, 1200)
                }
            }, handler)
            android.util.Log.i("MonetEpoch", "wallpaper color listener registered")
        } catch (e: Exception) {
            android.util.Log.w("MonetEpoch", "register failed: $e")
        }
    }
}

@Composable
fun DownloaderApp() {
    val ctx = LocalContext.current
    // 主题解析：设置页可切 跟随系统 / 浅色 / 深色
    val dark = when (Store.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val baseC = if (dark) MonetDark else MonetLight
    // 壁纸取色版本号：壁纸 colors 变化 / 回前台时自增，作为 remember key 驱动重算
    val monetEpoch = Store.monetEpoch.intValue
    // 派生优先级：莫奈动态取色（Android 12+，壁纸文件自取色 > 壁纸 colors > 系统值）> 种子色 > 默认
    val dynamicOn = Store.dynamicColor && Build.VERSION.SDK_INT >= 31
    // 快速首帧（主线程安全）：系统 scheme / WallpaperColors 直取
    val quick = if (dynamicOn) {
        remember(monetEpoch, dark, Store.dynamicScope) { MonetResolver.quickResolve(ctx, dark) }
    } else null
    // 深度取色（IO 线程）：读壁纸文件量化 + ColorSpec 2025 派生；epoch 变化 / 回前台重算
    var deep by remember { mutableStateOf<MonetResolved?>(null) }
    LaunchedEffect(monetEpoch, dark, dynamicOn) {
        if (dynamicOn) deep = withContext(Dispatchers.IO) { MonetResolver.deepResolve(ctx, dark) }
    }
    val resolved = if (dynamicOn) (deep ?: quick) else null
    val dynScheme = resolved?.sysScheme
    val c = if (resolved != null) {
        ThemeDerive.fromDynamic(baseC, resolved.primary, resolved.secondary,
            resolved.tertiary, Store.dynamicScope)
    } else {
        val seed = Store.accentSeedColor()
        if (seed != null) ThemeDerive.fromSeed(baseC, seed, Store.dynamicScope) else baseC
    }
    // M3 scheme：系统色可信且 accent 作用域时直用系统值，否则由派生色生成（玻璃令牌同步换肤）
    val scheme = if (dynScheme != null && Store.dynamicScope == "accent") dynScheme else monetScheme(c)

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

    CompositionLocalProvider(LocalMonet provides c) {
        MaterialTheme(colorScheme = scheme) {
            // tab 提升到 Store（跨页共享：素材库空状态「去下载页」引导跳转）
            val tab = Store.selectedTab
            val labels = listOf("下载", "素材库", "代理", "设置")

            GlassBackground(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .padding(WindowInsets.statusBars.asPaddingValues())
                ) {
                    when (tab) {
                        0 -> DownloadScreen()
                        1 -> LibraryScreen()
                        2 -> ProxyScreen()
                        else -> SettingsScreen()
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    GlassTabBar(labels, tab,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) { Store.selectedTab = it }
                }
            }
        }
    }
}
